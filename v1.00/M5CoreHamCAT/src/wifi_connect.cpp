/****************************************************
 *  M5CoreHamCAT Wi-Fi 画面描画
 *  Ver1.00
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

    if (!wifiScanned)
    {
        wifiScanned = true;
        WiFi.mode(WIFI_STA);
        WiFi.disconnect();
        delay(100);

        int n = WiFi.scanNetworks();
        wifiList.clear();
        for (int i = 0; i < n; i++)
        {
            WifiEntry e;
            e.ssid = WiFi.SSID(i);
            e.rssi = WiFi.RSSI(i);
            wifiList.push_back(e);
        }
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
                    // --- Connecting 表示 ---
                    canvas.fillRect(60, 80, 200, 80, BLACK);
                    canvas.drawRect(60, 80, 200, 80, BLUE);
                    canvas.setTextDatum(middle_center);
                    canvas.setTextColor(WHITE);
                    canvas.drawString("Connecting", 160, 120);
                    canvas.setTextDatum(top_left);
                    if (savedPass.length() > 0)
                    {
                        // --- Connecting 表示 ---
                        canvas.fillRect(60, 80, 200, 80, BLACK);
                        canvas.drawRect(60, 80, 200, 80, BLUE);
                        canvas.setTextDatum(middle_center);
                        canvas.setTextColor(WHITE);
                        canvas.drawString("Connecting", 160, 120);
                        canvas.setTextDatum(top_left);
                        canvas.pushSprite(0, 0);

                        // --- WiFi 接続処理 ---
                        WiFi.begin(
                            wifiList[selectedWifiIndex].ssid.c_str(),
                            savedPass.c_str());

                        unsigned long start = millis();
                        bool connected = false;

                        while (millis() - start < 8000)
                        {
                            if (WiFi.status() == WL_CONNECTED)
                            {
                                connected = true;
                                break;
                            }
                            delay(200);
                        }

                        if (connected)
                        {
                            ssid = wifiList[selectedWifiIndex].ssid.c_str();
                            pass = savedPass.c_str();
                            showErrorDialog = false;
                            appState = STATE_PI_CONNECT;
                            // drawPiConfigScreen();
                        }
                        else
                        {
                            showErrorDialog = true;
                            appState = STATE_WIFI;
                            // drawWifiScreen();
                        }
                        return;
                    }

                    canvas.pushSprite(0, 0);

                    // --- WiFi 接続処理 ---
                    WiFi.begin(
                        wifiList[selectedWifiIndex].ssid.c_str(),
                        savedPass.c_str());

                    unsigned long start = millis();
                    bool connected = false;

                    while (millis() - start < 8000)
                    {
                        if (WiFi.status() == WL_CONNECTED)
                        {
                            connected = true;
                            break;
                        }
                        delay(200);
                    }

                    if (connected)
                    {
                        ssid = wifiList[selectedWifiIndex].ssid.c_str();
                        pass = savedPass.c_str();
                        appState = STATE_PI_CONNECT;
                        // drawPiConfigScreen();
                    }
                    else
                    {
                        showErrorDialog = true;
                        appState = STATE_WIFI;
                        // drawWifiScreen();
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
