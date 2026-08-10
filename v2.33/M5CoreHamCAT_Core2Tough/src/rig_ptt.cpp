/****************************************************
 *  Wifi_Rig_CTRL PTT方式選択画面
 *  Ver2.33
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"

static void drawRigPTTScreen(int selPttDevice);
extern void drawRigConnectScreen(); // rig_connect.cpp。Back時に未保存の選択を破棄せず再描画するために直接呼ぶ

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
    static bool origExtSpk = false;
    static bool origExtMic = false;

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
#ifdef M5CORES3
        // SPK/Mic出力先はM5.begin()時に確定するため、変更されたら再起動が必要。
        // 画面に入った時点の値を記憶しておき、OK時に変化があったか判定する。
        origExtSpk = useExternalSpk;
        origExtMic = useExternalMic;
#endif
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
        if (x >= 10 && x <= 105 && y >= 150 && y <= 180)
        {
            pttType = "RTS";
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- DTR ----
        if (x >= 112 && x <= 207 && y >= 150 && y <= 180)
        {
            pttType = "DTR";
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- RIG (CAT内蔵PTT) ----
        if (x >= 214 && x <= 309 && y >= 150 && y <= 180)
        {
            pttType = "RIG";
            drawRigPTTScreen(selPttDevice);
            return;
        }
    }

#ifdef M5CORES3
    // ---- SPK Output (内蔵 / ES8388) ----
    if (x >= 10 && x <= 115 && y >= 182 && y <= 204)
    {
        useExternalSpk = false;
        drawRigPTTScreen(selPttDevice);
        return;
    }
    if (x >= 125 && x <= 240 && y >= 182 && y <= 204)
    {
        useExternalSpk = true;
        drawRigPTTScreen(selPttDevice);
        return;
    }

    // ---- Mic Input (内蔵 / ES8388、SPKの選択とは独立) ----
    if (x >= 10 && x <= 115 && y >= 208 && y <= 230)
    {
        useExternalMic = false;
        drawRigPTTScreen(selPttDevice);
        return;
    }
    if (x >= 125 && x <= 240 && y >= 208 && y <= 230)
    {
        useExternalMic = true;
        drawRigPTTScreen(selPttDevice);
        return;
    }
#endif

    // ---- OK ----
    if (x >= 250 && x <= 310 && y >= 200 && y <= 230)
    {
        prefs.begin("device", false);
        prefs.putBool("useWifiPTT", useWifiPTT);
        prefs.putString("pttHost", pttHost);
        prefs.putInt("pttPort", pttPort);
        prefs.putString("pttDevice", pttDevice);
        prefs.putString("pttType", pttType);
#ifdef M5CORES3
        prefs.putBool("useExtSpk", useExternalSpk);
        prefs.putBool("useExtMic", useExternalMic);
#endif
        prefs.end();

#ifdef M5CORES3
        // SPK/Mic出力先(内蔵/ES8388)はM5.begin()時の設定でハード構成が決まるため、
        // 変更された場合はメッセージを出して自動的に再起動する。
        if (useExternalSpk != origExtSpk || useExternalMic != origExtMic)
        {
            canvas.fillScreen(BLACK);
            canvas.setFont(&fonts::efontJA_16);
            canvas.setTextColor(WHITE);
            canvas.setTextDatum(middle_center);
            canvas.drawString("SPK/Mic settings changed", 160, 105);
            canvas.drawString("Restarting...", 160, 135);
            canvas.setTextDatum(top_left);
            canvas.pushSprite(0, 0);
            delay(1500);
            ESP.restart();
            return;
        }
#endif

        firstDraw = true;
        if (useCIV)
        {
            // CI-Vは専用のPTT設定画面を経由しないため、CI-V接続画面へ戻す
            appState = STATE_PI_CONNECT;
        }
        else
        {
            // ★ rigConnectFirstDraw = true にすると、Rig Connect画面側で「保存済み設定を
            //   読み直す」処理が走り、その画面でまだ未保存だった選択(機種/CATデバイス等)が
            //   消えてしまっていた。ここでは単に描き直すだけにして、選択状態を保持する。
            appState = STATE_DEVICE_SELECT;
            drawRigConnectScreen();
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

        // ★ RIG(CAT内蔵PTT)を追加。IC-705/IC-7300等、USB経由のRTS/DTRピン制御が
        //   USBオーディオリセットを起こす機種向け。機種による自動切替はせず、
        //   ここで選んだ方式をそのままPiへ渡す(Android版と同じ設計)。
        canvas.fillRoundRect(10, 150, 95, 30, 6, (pttType == "RTS") ? BLUE : DARKGREY);
        drawCentered("RTS", 57, 165, WHITE);

        canvas.fillRoundRect(112, 150, 95, 30, 6, (pttType == "DTR") ? BLUE : DARKGREY);
        drawCentered("DTR", 159, 165, WHITE);

        canvas.fillRoundRect(214, 150, 95, 30, 6, (pttType == "RIG") ? BLUE : DARKGREY);
        drawCentered("RIG", 261, 165, WHITE);
    }

#ifdef M5CORES3
    // ---- SPK Output (内蔵 / Module Audio ES8388) ----
    // Int側は文字がはみ出さない幅を確保し、ES8388側はフォントを一段小さくして収める
    canvas.setFont(&fonts::efontJA_12);
    canvas.fillRoundRect(10, 182, 105, 22, 6, !useExternalSpk ? GREEN : DARKGREY);
    drawCentered("SPK:Int", 62, 193, !useExternalSpk ? BLACK : WHITE);

    canvas.fillRoundRect(125, 182, 115, 22, 6, useExternalSpk ? BLUE : DARKGREY);
    canvas.setFont(&fonts::efontJA_10);
    drawCentered("SPK:ES8388", 182, 193, WHITE);
    canvas.setFont(&fonts::efontJA_12);

    // ---- Mic Input (内蔵 / ES8388、SPKの選択とは独立) ----
    canvas.fillRoundRect(10, 208, 105, 22, 6, !useExternalMic ? GREEN : DARKGREY);
    drawCentered("Mic:Int", 62, 219, !useExternalMic ? BLACK : WHITE);

    canvas.fillRoundRect(125, 208, 115, 22, 6, useExternalMic ? BLUE : DARKGREY);
    canvas.setFont(&fonts::efontJA_10);
    drawCentered("Mic:ES8388", 182, 219, WHITE);
    canvas.setFont(&fonts::efontJA_12);
#endif

    // ---- OK ボタン ----
    canvas.fillRoundRect(250, 200, 60, 30, 6, BLUE);
    drawCentered("OK", 280, 215, WHITE);

    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}
