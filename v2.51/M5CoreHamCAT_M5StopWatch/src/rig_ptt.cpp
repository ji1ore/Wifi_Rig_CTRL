/****************************************************
 *  Wifi_Rig_CTRL PTT方式選択画面
 *  Ver2.5
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"

static void drawRigPTTScreen(int selPttDevice);
extern void drawRigConnectScreen(); // rig_connect.cpp。Back時に未保存の選択を破棄せず再描画するために直接呼ぶ

// ★ 丸型ディスプレイ(466x466, 中心233,半径233)向けレイアウト。
//   タッチ判定(handleRigPTTScreen)と描画(drawRigPTTScreen)の両方でこの定数群を
//   共有し、ズレが起きないようにする。全ての行は円の可視領域(半径215の安全マージン
//   込み)に収まるよう計算済み。ボタン高さも50pxに拡大し、押しやすくしてある。
static const int PTT_TITLE_Y   = 85;
static const int PTT_ROW_H     = 50;
static const int PTT_METHOD_Y  = 145;               // Wifi_PTT / Hamlib トグル行
static const int PTT_METHOD_PILL_W = 145;
static const int PTT_METHOD_GAP    = 10;
static const int PTT_METHOD_L_X = CANVAS_CENTER - PTT_METHOD_PILL_W - PTT_METHOD_GAP / 2; // 78
static const int PTT_METHOD_R_X = CANVAS_CENTER + PTT_METHOD_GAP / 2;                     // 238
static const int PTT_ROW1_Y     = 225;               // Host / PTT Device 行
static const int PTT_ROW1_W     = 300;
static const int PTT_ROW1_X     = CANVAS_CENTER - PTT_ROW1_W / 2; // 83
static const int PTT_ROW2_Y     = 305;               // Port / RTS,DTR,RIG 行
static const int PTT_PORT_W     = 200;
static const int PTT_PORT_X     = CANVAS_CENTER - PTT_PORT_W / 2; // 133
static const int PTT_TYPE_BTN_W = 100;
static const int PTT_TYPE_GAP   = 10;
static const int PTT_TYPE_X0    = CANVAS_CENTER - (PTT_TYPE_BTN_W * 3 + PTT_TYPE_GAP * 2) / 2; // 73
static const int PTT_OK_Y       = 390;
static const int PTT_OK_W       = 140;
static const int PTT_OK_X       = CANVAS_CENTER - PTT_OK_W / 2; // 163

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
#ifdef AUDIO_SRC_SELECTABLE
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
    if (x >= PTT_METHOD_L_X && x <= PTT_METHOD_L_X + PTT_METHOD_PILL_W && y >= PTT_METHOD_Y && y <= PTT_METHOD_Y + PTT_ROW_H)
    {
        useWifiPTT = true;
        drawRigPTTScreen(selPttDevice);
        return;
    }

    // ---- Hamlib ----
    if (x >= PTT_METHOD_R_X && x <= PTT_METHOD_R_X + PTT_METHOD_PILL_W && y >= PTT_METHOD_Y && y <= PTT_METHOD_Y + PTT_ROW_H)
    {
        useWifiPTT = false;
        drawRigPTTScreen(selPttDevice);
        return;
    }

    if (useWifiPTT)
    {
        // ---- Host 編集 ----
        if (x >= PTT_ROW1_X && x <= PTT_ROW1_X + PTT_ROW1_W && y >= PTT_ROW1_Y && y <= PTT_ROW1_Y + PTT_ROW_H)
        {
            editingField = FIELD_PTT_HOST;
            inputPassword = pttHost;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            firstDraw = true;
            return;
        }

        // ---- Port 編集 ----
        if (x >= PTT_PORT_X && x <= PTT_PORT_X + PTT_PORT_W && y >= PTT_ROW2_Y && y <= PTT_ROW2_Y + PTT_ROW_H)
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
        if (x >= PTT_ROW1_X && x <= PTT_ROW1_X + PTT_ROW1_W && y >= PTT_ROW1_Y && y <= PTT_ROW1_Y + PTT_ROW_H)
        {
            auto opts = buildPttDeviceOptions();
            selPttDevice = (selPttDevice + 1) % (int)opts.size();
            pttDevice = (selPttDevice == 0) ? "" : opts[selPttDevice];
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- RTS ----
        if (x >= PTT_TYPE_X0 && x <= PTT_TYPE_X0 + PTT_TYPE_BTN_W && y >= PTT_ROW2_Y && y <= PTT_ROW2_Y + PTT_ROW_H)
        {
            pttType = "RTS";
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- DTR ----
        if (x >= PTT_TYPE_X0 + PTT_TYPE_BTN_W + PTT_TYPE_GAP && x <= PTT_TYPE_X0 + PTT_TYPE_BTN_W * 2 + PTT_TYPE_GAP && y >= PTT_ROW2_Y && y <= PTT_ROW2_Y + PTT_ROW_H)
        {
            pttType = "DTR";
            drawRigPTTScreen(selPttDevice);
            return;
        }

        // ---- RIG (CAT内蔵PTT) ----
        if (x >= PTT_TYPE_X0 + (PTT_TYPE_BTN_W + PTT_TYPE_GAP) * 2 && x <= PTT_TYPE_X0 + PTT_TYPE_BTN_W * 3 + PTT_TYPE_GAP * 2 && y >= PTT_ROW2_Y && y <= PTT_ROW2_Y + PTT_ROW_H)
        {
            pttType = "RIG";
            drawRigPTTScreen(selPttDevice);
            return;
        }
    }

#ifdef AUDIO_SRC_SELECTABLE
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
    if (x >= PTT_OK_X && x <= PTT_OK_X + PTT_OK_W && y >= PTT_OK_Y && y <= PTT_OK_Y + PTT_ROW_H)
    {
        prefs.begin("device", false);
        prefs.putBool("useWifiPTT", useWifiPTT);
        prefs.putString("pttHost", pttHost);
        prefs.putInt("pttPort", pttPort);
        prefs.putString("pttDevice", pttDevice);
        prefs.putString("pttType", pttType);
#ifdef AUDIO_SRC_SELECTABLE
        prefs.putBool("useExtSpk", useExternalSpk);
        prefs.putBool("useExtMic", useExternalMic);
#endif
        prefs.end();

#ifdef AUDIO_SRC_SELECTABLE
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
    canvas.setTextSize(1); // ★ 前の画面のスケールを引き継がないよう明示
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_center);
    canvas.drawString("PTT Method", CANVAS_CENTER, PTT_TITLE_Y);

    canvas.setTextSize(1.15); // ★ RigConnect画面のボタンと文字サイズを揃える
    canvas.fillRoundRect(PTT_METHOD_L_X, PTT_METHOD_Y, PTT_METHOD_PILL_W, PTT_ROW_H, 10, useWifiPTT ? BLUE : DARKGREY);
    drawCentered("Wifi_PTT", PTT_METHOD_L_X + PTT_METHOD_PILL_W / 2, PTT_METHOD_Y + PTT_ROW_H / 2, WHITE);

    canvas.fillRoundRect(PTT_METHOD_R_X, PTT_METHOD_Y, PTT_METHOD_PILL_W, PTT_ROW_H, 10, !useWifiPTT ? GREEN : DARKGREY);
    drawCentered("Hamlib", PTT_METHOD_R_X + PTT_METHOD_PILL_W / 2, PTT_METHOD_Y + PTT_ROW_H / 2, !useWifiPTT ? BLACK : WHITE);
    canvas.setTextSize(1);

    if (useWifiPTT)
    {
        // ---- Host ----
        canvas.setFont(&fonts::efontJA_12);
        canvas.setTextDatum(middle_center);
        canvas.drawString("PTT Host (mDNS)", CANVAS_CENTER, PTT_ROW1_Y - 16);
        canvas.drawRoundRect(PTT_ROW1_X, PTT_ROW1_Y, PTT_ROW1_W, PTT_ROW_H, 10, WHITE);
        // ★ ラベル用のefontJA_12のまま値を描いていたため、RigConnect画面(値はefontJA_16+1.15倍)
        //   と比べて値の文字が小さくなっていた。ここで値用に明示的に大きいフォントへ切り替える。
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextSize(1.15);
        drawCentered(pttHost.c_str(), CANVAS_CENTER, PTT_ROW1_Y + PTT_ROW_H / 2, WHITE);
        canvas.setTextSize(1);
        canvas.setFont(&fonts::efontJA_12);

        // ---- Port ----
        canvas.drawString("PTT Port", CANVAS_CENTER, PTT_ROW2_Y - 16);
        canvas.drawRoundRect(PTT_PORT_X, PTT_ROW2_Y, PTT_PORT_W, PTT_ROW_H, 10, WHITE);
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextSize(1.15);
        drawCentered(String(pttPort).c_str(), CANVAS_CENTER, PTT_ROW2_Y + PTT_ROW_H / 2, WHITE);
        canvas.setTextSize(1);
        canvas.setFont(&fonts::efontJA_12);
    }
    else
    {
        // ---- PTT Device ----
        canvas.setFont(&fonts::efontJA_12);
        canvas.setTextDatum(middle_center);
        canvas.drawString("PTT Device (tap to cycle)", CANVAS_CENTER, PTT_ROW1_Y - 16);

        auto opts = buildPttDeviceOptions();
        String devLabel = opts[selPttDevice];

        canvas.drawRoundRect(PTT_ROW1_X, PTT_ROW1_Y, PTT_ROW1_W, PTT_ROW_H, 10, WHITE);
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextSize(1.15);
        drawCentered(devLabel.c_str(), CANVAS_CENTER, PTT_ROW1_Y + PTT_ROW_H / 2, WHITE);
        canvas.setTextSize(1);
        canvas.setFont(&fonts::efontJA_12);

        // ---- PTT Type ----
        canvas.drawString("PTT Type", CANVAS_CENTER, PTT_ROW2_Y - 16);

        // ★ RIG(CAT内蔵PTT)を追加。IC-705/IC-7300等、USB経由のRTS/DTRピン制御が
        //   USBオーディオリセットを起こす機種向け。機種による自動切替はせず、
        //   ここで選んだ方式をそのままPiへ渡す(Android版と同じ設計)。
        int typeY = PTT_ROW2_Y;
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextSize(1.15);
        canvas.fillRoundRect(PTT_TYPE_X0, typeY, PTT_TYPE_BTN_W, PTT_ROW_H, 10, (pttType == "RTS") ? BLUE : DARKGREY);
        drawCentered("RTS", PTT_TYPE_X0 + PTT_TYPE_BTN_W / 2, typeY + PTT_ROW_H / 2, WHITE);

        int dtrX = PTT_TYPE_X0 + PTT_TYPE_BTN_W + PTT_TYPE_GAP;
        canvas.fillRoundRect(dtrX, typeY, PTT_TYPE_BTN_W, PTT_ROW_H, 10, (pttType == "DTR") ? BLUE : DARKGREY);
        drawCentered("DTR", dtrX + PTT_TYPE_BTN_W / 2, typeY + PTT_ROW_H / 2, WHITE);

        int rigX = PTT_TYPE_X0 + (PTT_TYPE_BTN_W + PTT_TYPE_GAP) * 2;
        canvas.fillRoundRect(rigX, typeY, PTT_TYPE_BTN_W, PTT_ROW_H, 10, (pttType == "RIG") ? BLUE : DARKGREY);
        drawCentered("RIG", rigX + PTT_TYPE_BTN_W / 2, typeY + PTT_ROW_H / 2, WHITE);
        canvas.setTextSize(1);
        canvas.setFont(&fonts::efontJA_12);
    }

#ifdef AUDIO_SRC_SELECTABLE
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
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextSize(1.15);
    canvas.fillRoundRect(PTT_OK_X, PTT_OK_Y, PTT_OK_W, PTT_ROW_H, 10, BLUE);
    drawCentered("OK", CANVAS_CENTER, PTT_OK_Y + PTT_ROW_H / 2, WHITE);
    canvas.setTextSize(1);

    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}
