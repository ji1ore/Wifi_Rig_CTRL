/****************************************************
 *  Wifi_Rig_CTRL PTT方式選択画面
 *  Ver2.18
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"

static void drawRigPTTScreen(int selPttDevice);

// catList + "NONE" 先頭の選択肢リストを返す
static std::vector<String> buildPttDeviceOptions()
{
    std::vector<String> opts = {"NONE"};
    for (auto &d : catList)
        opts.push_back(d);
    return opts;
}

void handleRigPTTScreen()
{
    if (appState != STATE_RIG_PTT)
        return;

    static bool firstDraw = true;
    static int selPttDevice = 0;

    if (firstDraw)
    {
        // pttDevice グローバルから初期インデックスを復元
        selPttDevice = 0;
        auto opts = buildPttDeviceOptions();
        for (int i = 0; i < (int)opts.size(); i++)
        {
            if (opts[i] == (pttDevice.isEmpty() ? "NONE" : pttDevice))
            {
                selPttDevice = i;
                break;
            }
        }
        drawRigPTTScreen(selPttDevice);
        firstDraw = false;
    }

    auto t = M5.Touch.getDetail();
    if (!t.wasPressed())
        return;

    int x = t.x, y = t.y;

    // ---- Wifi_PTT ----
    if (x >= 10 && x <= 150 && y >= 30 && y <= 60)
    {
        useWifiPTT = true;
        drawRigPTTScreen(selPttDevice);
        return;
    }

    // ---- Hamlib ----
    if (x >= 170 && x <= 310 && y >= 30 && y <= 60)
    {
        useWifiPTT = false;
        drawRigPTTScreen(selPttDevice);
        return;
    }

    if (useWifiPTT)
    {
        // ---- Host 編集 ----
        if (x >= 10 && x <= 310 && y >= 90 && y <= 120)
        {
            editingField = FIELD_PTT_HOST;
            inputPassword = pttHost;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            firstDraw = true;
            return;
        }

        // ---- Port 編集 ----
        if (x >= 10 && x <= 160 && y >= 150 && y <= 180)
        {
            editingField = FIELD_PTT_PORT;
            inputPassword = String(pttPort);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            firstDraw = true;
            return;
        }
    }
    else
    {
        // ---- PTT Device サイクル ----
        if (x >= 10 && x <= 310 && y >= 90 && y <= 120)
        {
            auto opts = buildPttDeviceOptions();
            selPttDevice = (selPttDevice + 1) % (int)opts.size();
            pttDevice = (selPttDevice == 0) ? "" : opts[selPttDevice];
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- RTS ----
        if (x >= 10 && x <= 155 && y >= 150 && y <= 180)
        {
            pttType = "RTS";
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- DTR ----
        if (x >= 165 && x <= 310 && y >= 150 && y <= 180)
        {
            pttType = "DTR";
            drawRigPTTScreen(selPttDevice);
            return;
        }
    }

    // ---- OK ----
    if (x >= 250 && x <= 310 && y >= 200 && y <= 230)
    {
        prefs.begin("device", false);
        prefs.putBool("useWifiPTT", useWifiPTT);
        prefs.putString("pttHost", pttHost);
        prefs.putInt("pttPort", pttPort);
        prefs.putString("pttDevice", pttDevice);
        prefs.putString("pttType", pttType);
        prefs.end();

        firstDraw = true;
        if (useCIV)
        {
            // CI-Vは専用のPTT設定画面を経由しないため、CI-V接続画面へ戻す
            appState = STATE_PI_CONNECT;
        }
        else
        {
            appState = STATE_DEVICE_SELECT;
            rigConnectFirstDraw = true;
        }
        return;
    }
}

static void drawRigPTTScreen(int selPttDevice)
{
    canvas.fillScreen(BLACK);

    // ---- PTT Method ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_left);
    canvas.drawString("PTT Method", 10, 15);

    canvas.setFont(&fonts::efontJA_16);
    canvas.fillRoundRect(10, 30, 140, 30, 6, useWifiPTT ? BLUE : DARKGREY);
    drawCentered("Wifi_PTT", 80, 45, WHITE);

    canvas.fillRoundRect(170, 30, 140, 30, 6, !useWifiPTT ? GREEN : DARKGREY);
    drawCentered("Hamlib", 240, 45, BLACK);

    if (useWifiPTT)
    {
        // ---- Host ----
        canvas.setFont(&fonts::efontJA_12);
        canvas.drawString("PTT Host (mDNS)", 10, 75);
        canvas.drawRect(10, 90, 300, 30, WHITE);
        canvas.setFont(&fonts::efontJA_16);
        drawCentered(pttHost.c_str(), 160, 105, WHITE);

        // ---- Port ----
        canvas.setFont(&fonts::efontJA_12);
        canvas.drawString("PTT Port", 10, 135);
        canvas.drawRect(10, 150, 150, 30, WHITE);
        canvas.setFont(&fonts::efontJA_16);
        drawCentered(String(pttPort).c_str(), 85, 165, WHITE);
    }
    else
    {
        // ---- PTT Device ----
        canvas.setFont(&fonts::efontJA_12);
        canvas.drawString("PTT Device (tap to cycle)", 10, 75);

        auto opts = buildPttDeviceOptions();
        String devLabel = opts[selPttDevice];

        canvas.drawRect(10, 90, 300, 30, WHITE);
        canvas.setFont(&fonts::efontJA_16);
        drawCentered(devLabel.c_str(), 160, 105, WHITE);

        // ---- PTT Type ----
        canvas.setFont(&fonts::efontJA_12);
        canvas.drawString("PTT Type", 10, 135);

        canvas.fillRoundRect(10, 150, 145, 30, 6, (pttType == "RTS") ? BLUE : DARKGREY);
        drawCentered("RTS", 82, 165, WHITE);

        canvas.fillRoundRect(165, 150, 145, 30, 6, (pttType == "DTR") ? BLUE : DARKGREY);
        drawCentered("DTR", 237, 165, WHITE);
    }

    // ---- OK ボタン ----
    canvas.fillRoundRect(250, 200, 60, 30, 6, BLUE);
    drawCentered("OK", 280, 215, WHITE);

    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}
