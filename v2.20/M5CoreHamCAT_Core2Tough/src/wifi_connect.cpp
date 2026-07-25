/****************************************************
 *  Wifi_Rig_CTRL Wi-Fi 画面描画
 *  Ver2.20
 *  by JI1ORE
 ****************************************************/
#include <WiFi.h>
#include <Preferences.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"

// ---- レイアウト定義 ----
const int WIFI_TITLE_Y = 10;
const int WIFI_LIST_Y = 40;
const int WIFI_LIST_H = 150;
const int WIFI_BTN_Y = 200;
const int WIFI_BTN_H = 30;
const int WIFI_ROW_H = 26;

// ---- WiFi.begin()の異常ブロッキング対策 ----
// WiFi.begin()自体やその後のステータス確認が、まれにこのArduino-ESP32コアの
// 既知の不具合で異常に長くブロックすることがある(PTT用DNS解決の件と同根)。
// バックグラウンドタスク化し、呼び出し側は外側から独立したタイムアウトで
// 打ち切れるようにする(tryConnectWifi()参照)。
struct WifiConnectTask
{
    char ssid[64];
    char pass[64];
    volatile bool done = false;
    volatile bool connected = false;
};

static void wifiConnectTaskFunc(void *param)
{
    WifiConnectTask *t = (WifiConnectTask *)param;
    WiFi.begin(t->ssid, t->pass);

    unsigned long start = millis();
    bool ok = false;
    while (millis() - start < 8000)
    {
        if (WiFi.status() == WL_CONNECTED)
        {
            ok = true;
            break;
        }
        delay(200);
    }
    t->connected = ok;
    t->done = true;
    vTaskDelete(NULL);
}

static bool tryConnectWifiOnce(const String &ssid, const String &pass, bool &timedOut)
{
    static WifiConnectTask task;
    task.done = false;
    task.connected = false;
    ssid.toCharArray(task.ssid, sizeof(task.ssid));
    pass.toCharArray(task.pass, sizeof(task.pass));

    TaskHandle_t th = nullptr;
    xTaskCreatePinnedToCore(wifiConnectTaskFunc, "wifiConnect", 4096, &task, 1, &th, 1);

    unsigned long start = millis();
    const unsigned long outerTimeoutMs = 10000; // タスク内部の8秒より少し長め
    while (!task.done && millis() - start < outerTimeoutMs)
    {
        delay(50);
    }

    if (!task.done)
    {
        // ハングしたタスクを強制破棄して先に進む(WiFi.begin()自体が異常に
        // ブロックしている場合、UIだけでも復帰させる)
        if (th) vTaskDelete(th);
        timedOut = true;
        Serial.println("[wifi] connect timed out (background task did not finish)");
        return false;
    }

    timedOut = false;
    return task.connected;
}

// 戻り値: 接続できた場合true。タイムアウト(外側の待ち上限)の場合はfalseで
// timedOutをtrueにする(バックグラウンドタスクは打ち切って先に進む)。
// passwd.cpp(未保存SSIDのパスワード入力後の接続)からも共用する。
// 電波状況等による一時的な失敗を吸収するため、最大3回まで再試行する。
bool tryConnectWifi(const String &ssid, const String &pass, bool &timedOut)
{
    const int maxAttempts = 3;
    for (int attempt = 1; attempt <= maxAttempts; attempt++)
    {
        // ★ 再試行中であることを画面に出す(でないと最大30秒近く無反応に見えてしまう)
        char msg[24];
        snprintf(msg, sizeof(msg), "Connecting (%d/%d)", attempt, maxAttempts);
        canvas.fillRect(60, 80, 200, 80, BLACK);
        canvas.drawRect(60, 80, 200, 80, BLUE);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString(msg, 160, 120);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);

        bool to = false;
        bool ok = tryConnectWifiOnce(ssid, pass, to);
        Serial.printf("[wifi] connect attempt %d/%d ok=%d timedOut=%d\n", attempt, maxAttempts, ok, to);

        if (ok)
        {
            timedOut = false;
            return true;
        }

        timedOut = to;
        if (attempt < maxAttempts)
        {
            WiFi.disconnect();
            delay(300);
        }
    }
    return false;
}

/****************************************************
 * SSID タップ判定
 ****************************************************/
int detectTouchedSSID(int x, int y)
{
    if (y < WIFI_LIST_Y || y > WIFI_LIST_Y + WIFI_LIST_H)
        return -1;

    for (int i = 0; i < wifiList.size(); i++)
    {
        int y1 = WIFI_LIST_Y + i * WIFI_ROW_H - wifiScrollOffset;
        int y2 = y1 + WIFI_ROW_H;
        if (y >= y1 && y <= y2)
            return i;
    }
    return -1;
}

bool touchDeleteButton(int x, int y)
{
    int bx = 10, by = WIFI_BTN_Y, bw = 80, bh = WIFI_BTN_H;
    return (x >= bx && x <= bx + bw && y >= by && y <= by + bh);
}

bool touchConnectButton(int x, int y)
{
    int bx = 120, by = WIFI_BTN_Y, bw = 180, bh = WIFI_BTN_H;
    return (x >= bx && x <= bx + bw && y >= by && y <= by + bh);
}

/****************************************************
 * Wi-Fi 画面処理
 ****************************************************/
void handleWifiScreen()
{
    if (appState != STATE_WIFI)
        return;
    auto t = M5.Touch.getDetail();

    // ★ WiFi.scanNetworks()の同期呼び出しはまれに(WiFi.begin()と同根の既知の不具合で)
    //   異常にブロックし続け、画面ごとフリーズすることがあったため、非同期スキャン
    //   (scanNetworks(true) + scanComplete()ポーリング)+ タイムアウトに変更する。
    static bool scanInProgress = false;
    static unsigned long scanStartTime = 0;
    const unsigned long scanTimeoutMs = 10000;

    if (!wifiScanned)
    {
        if (!scanInProgress)
        {
            WiFi.mode(WIFI_STA);
            WiFi.disconnect();
            delay(100);
            WiFi.scanNetworks(true); // 非同期スキャン開始
            scanInProgress = true;
            scanStartTime = millis();

            canvas.fillScreen(BLACK);
            canvas.setFont(&fonts::efontJA_16);
            canvas.setTextColor(WHITE);
            canvas.setTextDatum(middle_center);
            canvas.drawString("Scanning...", 160, 120);
            canvas.setTextDatum(top_left);
            canvas.pushSprite(0, 0);
            return;
        }

        int16_t n = WiFi.scanComplete();
        if (n < 0) // WIFI_SCAN_RUNNING(-1)だけでなく、開始直後にWIFI_SCAN_FAILED(-2)を
                   // 一瞬返すことがあるため、負値は全てタイムアウトまで待つ扱いにする
        {
            if (millis() - scanStartTime > scanTimeoutMs)
            {
                // 異常に長引く/失敗したスキャンはタイムアウトで打ち切り、空リストのまま進む
                WiFi.scanDelete();
                n = 0;
            }
            else
            {
                return; // スキャン継続中、次のループで再チェック
            }
        }

        scanInProgress = false;
        wifiScanned = true;

        wifiList.clear();
        for (int i = 0; i < n; i++)
        {
            String s = WiFi.SSID(i);
            int r = WiFi.RSSI(i);
            bool found = false;
            for (auto &e : wifiList)
            {
                if (e.ssid == s) { if (r > e.rssi) e.rssi = r; found = true; break; }
            }
            if (!found) { WifiEntry e; e.ssid = s; e.rssi = r; wifiList.push_back(e); }
        }
        WiFi.scanDelete();
        drawWifiScreen();
        return;
    }

    if (showErrorDialog)
    {
        if (t.wasPressed())
        {
            showErrorDialog = false;
            drawWifiScreen();
        }
        return;
    }

    static int lastY = -1;

    if (t.isPressed())
    {
        if (lastY >= 0)
        {
            int dy = lastY - t.y;
            wifiScrollOffset += dy;

            int maxScroll = std::max(0,
                                     (int)wifiList.size() * WIFI_ROW_H - WIFI_LIST_H);

            wifiScrollOffset = std::max(0, std::min(wifiScrollOffset, maxScroll));
            drawWifiScreen();
            return;
        }
        lastY = t.y;
    }
    else
    {
        lastY = -1;
    }

    if (t.wasPressed())
    {
        int idx = detectTouchedSSID(t.x, t.y);
        if (idx >= 0)
        {
            selectedWifiIndex = idx;
            drawWifiScreen();
            return;
        }

        // DEL ボタン（保存削除）
        if (touchDeleteButton(t.x, t.y))
        {
            if (selectedWifiIndex >= 0)
            {
                prefs.begin("wifi", false);
                prefs.remove(wifiList[selectedWifiIndex].ssid.c_str());
                prefs.end();
            }

            drawWifiScreen();
            return;
        }

        if (touchConnectButton(t.x, t.y))
        {
            if (selectedWifiIndex >= 0)
            {
                prefs.begin("wifi", true);
                String savedPass = prefs.getString(wifiList[selectedWifiIndex].ssid.c_str(), "");
                prefs.end();

                if (savedPass.length() > 0)
                {
                    // --- WiFi 接続処理(バックグラウンドタスク+外側タイムアウトで
                    //     WiFi.begin()自体の異常ブロッキングからも復帰でき、最大3回まで
                    //     再試行する。「Connecting (N/3)」表示はtryConnectWifi()内で行う) ---
                    bool timedOut = false;
                    bool connected = tryConnectWifi(wifiList[selectedWifiIndex].ssid, savedPass, timedOut);
                    Serial.printf("[wifi] connect ok=%d timedOut=%d\n", connected, timedOut);

                    if (connected)
                    {
                        ssid = wifiList[selectedWifiIndex].ssid.c_str();
                        pass = savedPass.c_str();
                        // 飛ばしモード用に最後のSSIDを保存
                        prefs.begin("wifi", false);
                        prefs.putString("lastssid", ssid);
                        prefs.end();
                        showErrorDialog = false;
                        appState = STATE_PI_CONNECT;
                    }
                    else
                    {
                        showErrorDialog = true;
                        appState = STATE_WIFI;
                        drawWifiScreen(); // ★ エラーダイアログを即描画しないと画面が固まって見える
                    }
                    return;
                }

                // --- 未保存 → パスワード入力画面へ ---
                inputPassword = "";
                passwordForWifi = true;
                appState = STATE_PASSWORD;
                return;
                // drawPasswordScreen();
            }
        }
    }
}

/****************************************************
 * Wi-Fi 画面描画
 ****************************************************/
void drawWifiScreen()
{
    canvas.fillScreen(BLACK);

    canvas.setTextColor(CYAN);
    canvas.setFont(&fonts::efontJA_16_b);
    canvas.drawString("Wi-Fi CONNECT", 10, WIFI_TITLE_Y);
    canvas.setFont(&fonts::efontJA_16);
    for (int i = 0; i < wifiList.size(); i++)
    {
        int y = WIFI_LIST_Y + i * WIFI_ROW_H - wifiScrollOffset;
        if (y < WIFI_LIST_Y - WIFI_ROW_H || y > WIFI_LIST_Y + WIFI_LIST_H)
            continue;

        // 保存済みチェック
        prefs.begin("wifi", true);
        String savedPass = prefs.getString(wifiList[i].ssid.c_str(), "");
        prefs.end();

        // 色分け
        if (i == selectedWifiIndex)
            canvas.setTextColor(GREEN);
        else if (savedPass.length() > 0)
            canvas.setTextColor(YELLOW); // 保存済み SSID
        else
            canvas.setTextColor(WHITE);

        canvas.drawString(wifiList[i].ssid, 10, y);

        // 保存済みなら * を表示
        if (savedPass.length() > 0)
            canvas.drawString("*", 280, y);
    }

    int totalHeight = wifiList.size() * WIFI_ROW_H;
    if (totalHeight > WIFI_LIST_H)
    {
        float ratio = (float)WIFI_LIST_H / totalHeight;
        int barHeight = WIFI_LIST_H * ratio;
        int barY = WIFI_LIST_Y + (wifiScrollOffset * ratio);
        canvas.fillRect(305, barY, 6, barHeight, DARKGREY);
    }

    // DEL ボタン
    canvas.fillRoundRect(10, WIFI_BTN_Y, 80, WIFI_BTN_H, 6, RED);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("DEL", 10 + 40, WIFI_BTN_Y + WIFI_BTN_H / 2);
    canvas.setTextDatum(top_left);

    // Connect ボタン
    int btnX = 120, btnY = WIFI_BTN_Y, btnW = 180, btnH = WIFI_BTN_H;
    canvas.fillRoundRect(btnX, btnY, btnW, btnH, 6, BLUE);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("Connect", btnX + btnW / 2, btnY + btnH / 2);
    canvas.setTextDatum(top_left);

    if (showErrorDialog)
    {
        int dx = 20, dy = 40, dw = 280, dh = 160;
        canvas.fillRect(dx, dy, dw, dh, BLACK);
        canvas.drawRect(dx, dy, dw, dh, RED);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("Failed to Connect", dx + dw / 2, dy + 40);
        canvas.drawString("Press OK ", dx + dw / 2, dy + 80);
        canvas.fillRoundRect(dx + 100, dy + 110, 80, 30, 6, BLUE);
        canvas.drawString("OK", dx + 140, dy + 125);
        canvas.setTextDatum(top_left);
    }

    canvas.pushSprite(0, 0);
}
