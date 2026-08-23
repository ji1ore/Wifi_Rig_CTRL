/****************************************************
 *  Wifi_Rig_CTRL Wi-Fi 画面 (M5StopWatch 円形466x466向け)
 *  SP2ALART_M5STOP_1.00/src/wifi_connect.cpp を移植。
 *  WiFiスキャン・接続ロジック(非同期scan、バックグラウンドタスクでの
 *  connect+3回リトライ、NVS Preferences("wifi")への保存)はそのまま。
 ****************************************************/
#include <WiFi.h>
#include <Preferences.h>
#include <algorithm>
#include "ui_core.h"
#include "globals.h"

void drawWifiScreen();

// ---- レイアウト定義 (円形デザイン: ピル形状の行(大きめ文字) + テキスト付き
//      ボタン + 円弧スクロールバー) ----
static const int WIFI_LIST_Y = 96;
static const int WIFI_LIST_H = 244; // y: 96-340
static const int WIFI_ROW_H = 50;
static const int WIFI_ROW_GAP = 8;
static const int WIFI_ROW_PITCH = WIFI_ROW_H + WIFI_ROW_GAP;
static const int WIFI_PILL_W = 320;
static const int WIFI_PILL_X = CANVAS_CENTER - WIFI_PILL_W / 2;

static const int WIFI_BTN_Y = 384, WIFI_BTN_H = 46;
static const int WIFI_DEL_W = 90;
static const int WIFI_CONNECT_W = 165;
static const int WIFI_DEL_X = CANVAS_CENTER - 132;
static const int WIFI_CONNECT_X = WIFI_DEL_X + WIFI_DEL_W + 10;
static const int WIFI_SCROLL_R = 222;

// ---- WiFi.begin()の異常ブロッキング対策 ----
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

static bool tryConnectWifiOnce(const String &ssidIn, const String &passIn, bool &timedOut)
{
    static WifiConnectTask task;
    task.done = false;
    task.connected = false;
    ssidIn.toCharArray(task.ssid, sizeof(task.ssid));
    passIn.toCharArray(task.pass, sizeof(task.pass));

    TaskHandle_t th = nullptr;
    xTaskCreatePinnedToCore(wifiConnectTaskFunc, "wifiConnect", 4096, &task, 1, &th, 1);

    unsigned long start = millis();
    const unsigned long outerTimeoutMs = 10000;
    while (!task.done && millis() - start < outerTimeoutMs)
    {
        delay(50);
    }

    if (!task.done)
    {
        if (th)
            vTaskDelete(th);
        timedOut = true;
        Serial.println("[wifi] connect timed out (background task did not finish)");
        return false;
    }

    timedOut = false;
    return task.connected;
}

static void drawConnectingMessage(const char *msg)
{
    canvas.fillScreen(COL_BG);
    // 円弧スピナー(装飾): 画面中央を囲む部分円弧
    static float spin = 0;
    spin += 28;
    if (spin >= 360) spin -= 360;
    drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, 96, spin, spin + 70, COL_ACCENT, 5);
    drawCentered(msg, CANVAS_CENTER, CANVAS_CENTER, COL_TEXT);
    canvas.pushSprite(0, 0);
}

// 最大3回まで再試行する。passwd.cpp からも共用する。
bool tryConnectWifi(const String &ssidIn, const String &passIn, bool &timedOut)
{
    const int maxAttempts = 3;
    for (int attempt = 1; attempt <= maxAttempts; attempt++)
    {
        char msg[24];
        snprintf(msg, sizeof(msg), "Connecting (%d/%d)", attempt, maxAttempts);
        drawConnectingMessage(msg);

        bool to = false;
        bool ok = tryConnectWifiOnce(ssidIn, passIn, to);
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
static int detectTouchedSSID(int x, int y)
{
    if (y < WIFI_LIST_Y || y > WIFI_LIST_Y + WIFI_LIST_H)
        return -1;

    for (size_t i = 0; i < wifiList.size(); i++)
    {
        int y1 = WIFI_LIST_Y + (int)i * WIFI_ROW_PITCH - wifiScrollOffset;
        int y2 = y1 + WIFI_ROW_H;
        if (y >= y1 && y <= y2)
            return (int)i;
    }
    return -1;
}

static bool touchDeleteButton(int x, int y)
{
    return hitRect(x, y, WIFI_DEL_X, WIFI_BTN_Y, WIFI_DEL_W, WIFI_BTN_H);
}

static bool touchConnectButton(int x, int y)
{
    return hitRect(x, y, WIFI_CONNECT_X, WIFI_BTN_Y, WIFI_CONNECT_W, WIFI_BTN_H);
}

/****************************************************
 * 起動時のlastssid自動再接続 (STATE_WIFIへ最初に入った時に1回だけ試みる)
 ****************************************************/
static void tryAutoReconnect()
{
    prefs.begin("wifi", true);
    String lastSsid = prefs.getString("lastssid", "");
    prefs.end();
    if (lastSsid.length() == 0)
        return;

    prefs.begin("wifi", true);
    String savedPass = prefs.getString(lastSsid.c_str(), "");
    prefs.end();
    if (savedPass.length() == 0)
        return;

    drawConnectingMessage("Reconnecting...");
    bool timedOut = false;
    bool connected = tryConnectWifi(lastSsid, savedPass, timedOut);
    Serial.printf("[wifi] auto-reconnect ok=%d\n", connected);
    if (connected)
    {
        ssid = lastSsid;
        pass = savedPass;
        appState = STATE_PI_CONNECT;
    }
}

/****************************************************
 * Wi-Fi 画面処理
 ****************************************************/
void handleWifiScreen()
{
    if (appState != STATE_WIFI)
        return;

    if (!autoConnectTried)
    {
        autoConnectTried = true;
        tryAutoReconnect();
        if (appState != STATE_WIFI)
            return; // 自動再接続に成功しSTATE_PI_CONNECTへ遷移した
    }

    auto t = M5.Touch.getDetail();

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
            WiFi.scanNetworks(true);
            scanInProgress = true;
            scanStartTime = millis();

            drawConnectingMessage("Scanning...");
            return;
        }

        int16_t n = WiFi.scanComplete();
        if (n < 0)
        {
            if (millis() - scanStartTime > scanTimeoutMs)
            {
                WiFi.scanDelete();
                n = 0;
            }
            else
            {
                return;
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
                if (e.ssid == s)
                {
                    if (r > e.rssi)
                        e.rssi = r;
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                WifiEntry e;
                e.ssid = s;
                e.rssi = r;
                wifiList.push_back(e);
            }
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

            int maxScroll = std::max(0, (int)wifiList.size() * WIFI_ROW_PITCH - WIFI_LIST_H);
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
                    bool timedOut = false;
                    bool connected = tryConnectWifi(wifiList[selectedWifiIndex].ssid, savedPass, timedOut);
                    Serial.printf("[wifi] connect ok=%d timedOut=%d\n", connected, timedOut);

                    if (connected)
                    {
                        ssid = wifiList[selectedWifiIndex].ssid;
                        pass = savedPass;
                        prefs.begin("wifi", false);
                        prefs.putString("lastssid", ssid);
                        prefs.end();
                        showErrorDialog = false;
                        appState = STATE_PI_CONNECT;
                    }
                    else
                    {
                        showErrorDialog = true;
                        drawWifiScreen();
                    }
                    return;
                }

                inputPassword = "";
                passwordForWifi = true;
                passwordFirstDraw = true;
                appState = STATE_PASSWORD;
                return;
            }
        }
    }
}

/****************************************************
 * Wi-Fi 画面描画
 ****************************************************/
void drawWifiScreen()
{
    canvas.fillScreen(COL_BG);
    canvas.setFont(&fonts::efontJA_16); // ★ 前の画面のフォントを引き継がないよう明示
    ui_drawTitle("Wi-Fi");

    canvas.setFont(&fonts::efontJA_16);
    for (size_t i = 0; i < wifiList.size(); i++)
    {
        int y = WIFI_LIST_Y + (int)i * WIFI_ROW_PITCH - wifiScrollOffset;
        if (y < WIFI_LIST_Y - WIFI_ROW_PITCH || y > WIFI_LIST_Y + WIFI_LIST_H)
            continue;

        prefs.begin("wifi", true);
        String savedPass = prefs.getString(wifiList[i].ssid.c_str(), "");
        prefs.end();
        bool saved = savedPass.length() > 0;
        bool selected = (int)i == selectedWifiIndex;

        uint16_t pillColor = selected ? COL_ACCENT : COL_SURFACE;
        uint16_t textColor = selected ? 0x0000 : (saved ? COL_WARN : COL_TEXT);
        canvas.fillSmoothRoundRect(WIFI_PILL_X, y, WIFI_PILL_W, WIFI_ROW_H, WIFI_ROW_H / 2, pillColor);

        // 信号強度インジケータ(小さな3本バー、ピル左端の内側)
        int barsX = WIFI_PILL_X + 18;
        int rssi = wifiList[i].rssi;
        int bars = rssi > -55 ? 3 : (rssi > -75 ? 2 : 1);
        for (int b = 0; b < 3; b++)
        {
            int bh = 6 + b * 5;
            uint16_t bc = (b < bars) ? textColor : (selected ? 0x0410 : COL_TEXT_DIM);
            canvas.fillRect(barsX + b * 7, y + WIFI_ROW_H / 2 + 8 - bh, 5, bh, bc);
        }

        canvas.setFont(&fonts::efontJA_24);
        canvas.setTextDatum(middle_left);
        canvas.setTextColor(textColor);
        String label = wifiList[i].ssid;
        if (label.length() > 12)
            label = label.substring(0, 11) + "..";
        if (saved)
            label += " *";
        canvas.drawString(label, barsX + 32, y + WIFI_ROW_H / 2);
        canvas.setTextDatum(top_left);
        canvas.setTextColor(COL_TEXT);
        canvas.setFont(&fonts::efontJA_16);
    }

    // スクロール位置を右端の円弧で示す(直線バーではなく画面の丸みに沿わせる)
    int totalHeight = (int)wifiList.size() * WIFI_ROW_PITCH;
    if (totalHeight > WIFI_LIST_H)
    {
        const float trackStart = -55, trackEnd = 55;
        float ratio = (float)WIFI_LIST_H / totalHeight;
        float thumbSpan = (trackEnd - trackStart) * ratio;
        float thumbStart = trackStart + (trackEnd - trackStart) * ((float)wifiScrollOffset / totalHeight);
        drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, WIFI_SCROLL_R, trackStart, trackEnd, COL_SURFACE, 4);
        drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, WIFI_SCROLL_R, thumbStart, thumbStart + thumbSpan, COL_ACCENT, 4);
    }

    // 選択中のみ削除ボタンを表示、接続ボタンは常時表示。両方とも文字ラベル付き。
    if (selectedWifiIndex >= 0)
    {
        // COL_TEXTだとテーマによってはCOL_DANGERとのコントラストが弱くなり得るため、
        // バッジ内文字は固定で黒。
        drawPill(WIFI_DEL_X, WIFI_BTN_Y, WIFI_DEL_W, WIFI_BTN_H, COL_DANGER, "DEL", 0x0000);
        drawPill(WIFI_CONNECT_X, WIFI_BTN_Y, WIFI_CONNECT_W, WIFI_BTN_H, COL_ACCENT, "CONNECT", 0x0000);
    }
    else
    {
        drawHint("Tap an SSID to select", WIFI_BTN_Y + WIFI_BTN_H / 2 + 2, COL_TEXT_DIM);
    }

    if (showErrorDialog)
    {
        int dw = 290, dh = 200;
        int dx = CANVAS_CENTER - dw / 2, dy = CANVAS_CENTER - dh / 2;
        canvas.fillSmoothRoundRect(dx, dy, dw, dh, 28, COL_SURFACE);
        canvas.drawRoundRect(dx, dy, dw, dh, 28, COL_DANGER);
        drawCircleButton(CANVAS_CENTER, dy + 46, 24, COL_DANGER, "!", 0x0000);
        canvas.setFont(&fonts::efontJA_16_b);
        drawCentered("Failed to", CANVAS_CENTER, dy + 90, COL_TEXT);
        drawCentered("Connect", CANVAS_CENTER, dy + 116, COL_TEXT);
        canvas.setFont(&fonts::efontJA_16);
        drawCircleButton(CANVAS_CENTER, dy + dh - 42, 30, COL_ACCENT, "OK", 0x0000);
    }

    canvas.pushSprite(0, 0);
}
