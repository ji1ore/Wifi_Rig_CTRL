/****************************************************
 *  Wifi_Rig_CTRL 無線機選択画面 (M5StopWatch 円形466x466向け)
 *  Wi-Fi画面と同じピル形状リスト+円弧スクロールバーで再設計。
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "globals.h"

int rigScroll = 0;

static const int RIG_LIST_Y = 96, RIG_LIST_H = 244;
static const int RIG_ROW_H = 50, RIG_ROW_GAP = 8, RIG_ROW_PITCH = RIG_ROW_H + RIG_ROW_GAP;
static const int RIG_PILL_W = 320, RIG_PILL_X = CANVAS_CENTER - RIG_PILL_W / 2;
static const int RIG_SCROLL_R = 222;
static const int RIG_BTN_Y = 384, RIG_BTN_H = 46;
static const int RIG_BACK_W = 130, RIG_OK_W = 130;
static const int RIG_BACK_X = CANVAS_CENTER - (RIG_BACK_W + 10 + RIG_OK_W) / 2;
static const int RIG_OK_X = RIG_BACK_X + RIG_BACK_W + 10;

/****************************************************
 * リグ一覧描画（ラズパイ API から取得した rigNames を表示）
 ****************************************************/
void drawRigSelectScreen()
{
    if (rigSelectFirstDraw)
    {
        // ★ rigScrollはpx単位のスクロール量。以前はインデックス差(selRig-2)を
        //   そのままpxとして使っていたため、選択済みリグが下の方にあると
        //   スクロールされずリスト上部に隠れて見えなかった(選択自体は保存されていた)。
        rigScroll = (selRig - 2) * RIG_ROW_PITCH;
        if (rigScroll < 0)
            rigScroll = 0;
    }

    canvas.fillScreen(COL_BG);
    canvas.setFont(&fonts::efontJA_16); // ★ 前の画面のフォントを引き継がないよう明示
    ui_drawTitle("Select Rig");

    int total = (int)rigNames.size();
    int maxScroll = max(0, total * RIG_ROW_PITCH - RIG_LIST_H);
    rigScroll = constrain(rigScroll, 0, maxScroll);

    canvas.setFont(&fonts::efontJA_16);
    for (int i = 0; i < total; i++)
    {
        int y = RIG_LIST_Y + i * RIG_ROW_PITCH - rigScroll;
        if (y < RIG_LIST_Y - RIG_ROW_PITCH || y > RIG_LIST_Y + RIG_LIST_H)
            continue;

        bool selected = (i == selRig);
        uint16_t pillColor = selected ? COL_ACCENT : COL_SURFACE;
        uint16_t textColor = selected ? (uint16_t)0x0000 : COL_TEXT;
        canvas.fillSmoothRoundRect(RIG_PILL_X, y, RIG_PILL_W, RIG_ROW_H, RIG_ROW_H / 2, pillColor);

        String name = rigNames[i];
        canvas.setFont(&fonts::efontJA_16);
        while (canvas.textWidth(name) > RIG_PILL_W - 60 && name.length() > 1)
            name.remove(name.length() - 1);
        if (name != rigNames[i])
            name += "..";
        canvas.setTextDatum(middle_left);
        canvas.setTextColor(textColor);
        canvas.drawString(name, RIG_PILL_X + 24, y + RIG_ROW_H / 2);
        canvas.setTextDatum(top_left);
        canvas.setFont(&fonts::efontJA_16);
    }

    int totalHeight = total * RIG_ROW_PITCH;
    if (totalHeight > RIG_LIST_H)
    {
        const float trackStart = -55, trackEnd = 55;
        float ratio = (float)RIG_LIST_H / totalHeight;
        float thumbSpan = (trackEnd - trackStart) * ratio;
        float thumbStart = trackStart + (trackEnd - trackStart) * ((float)rigScroll / totalHeight);
        drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, RIG_SCROLL_R, trackStart, trackEnd, COL_SURFACE, 4);
        drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, RIG_SCROLL_R, thumbStart, thumbStart + thumbSpan, COL_ACCENT, 4);
    }

    drawPill(RIG_BACK_X, RIG_BTN_Y, RIG_BACK_W, RIG_BTN_H, COL_DANGER, "BACK", 0x0000);
    drawPill(RIG_OK_X, RIG_BTN_Y, RIG_OK_W, RIG_BTN_H, COL_ACCENT, "OK", 0x0000);

    canvas.pushSprite(0, 0);
}

void handleRigSelectScreen()
{
    if (appState != STATE_RIG_CONNECT)
        return;

    if (rigSelectFirstDraw)
    {
        prefs.begin("device", true);
        int savedRigId = prefs.getInt("rigId", -1);
        prefs.end();

        // ★ デバッグ用: 起動直後(setup())に読んだ生のNVS値と、ここで改めて
        //   読み直した値を突き合わせる。両者が食い違えば、起動後~この画面表示
        //   までの間に何かがNVSを上書き/クリアしていることになる。
        Serial.printf("[dbg][rig_select] firstDraw: dbgBootSavedRigId=%d savedRigId(reread)=%d rigIds.size=%d\n",
                      dbgBootSavedRigId, savedRigId, (int)rigIds.size());

        selRig = 0;
        for (int i = 0; i < rigIds.size(); ++i)
        {
            if (rigIds[i] == savedRigId)
            {
                selRig = i;
                break;
            }
        }

        drawRigSelectScreen();
        rigSelectFirstDraw = false;
    }

    // ★ 物理ボタン(青=BtnA/GPIO2, 黄=BtnB/GPIO1)でページ送り。
    //   タッチのページ送りボタンは押しづらいとの指摘で廃止し、こちらに一本化。
    {
        int pageTotalHeight = (int)rigNames.size() * RIG_ROW_PITCH;
        int maxScroll = max(0, pageTotalHeight - RIG_LIST_H);
        if (maxScroll > 0)
        {
            if (M5.BtnA.wasPressed())
            {
                rigScroll = constrain(rigScroll - RIG_LIST_H, 0, maxScroll);
                drawRigSelectScreen();
                return;
            }
            if (M5.BtnB.wasPressed())
            {
                rigScroll = constrain(rigScroll + RIG_LIST_H, 0, maxScroll);
                drawRigSelectScreen();
                return;
            }
        }
    }

    auto t = M5.Touch.getDetail();
    static int lastY = -1;

    if (t.wasPressed())
    {
        if (hitRect(t.x, t.y, RIG_BACK_X, RIG_BTN_Y, RIG_BACK_W, RIG_BTN_H))
        {
            appState = STATE_PI_CONNECT;
            rigSelectFirstDraw = true;
            return;
        }

        if (hitRect(t.x, t.y, RIG_OK_X, RIG_BTN_Y, RIG_OK_W, RIG_BTN_H))
        {
            prefs.begin("device", false);
            size_t wrote = prefs.putInt("rigId", rigIds[selRig]);
            // ★ 書き込み直後に読み戻して、NVSへの保存が本当に反映されたか検証する。
            int verify = prefs.getInt("rigId", -12345);
            prefs.end();
            Serial.printf("[dbg][rig_select] OK: selRig=%d rigIds[selRig]=%d name=%s putInt_wrote=%u verify_readback=%d\n",
                          selRig, rigIds[selRig], rigNames[selRig].c_str(), (unsigned)wrote, verify);
            if (verify != rigIds[selRig])
            {
                Serial.println("[dbg][rig_select] *** WARNING: rigId save verification FAILED! ***");
            }

            appState = STATE_DEVICE_SELECT;
            rigSelectFirstDraw = true;
            rigConnectFirstDraw = true;
            return;
        }

        if (t.y >= RIG_LIST_Y && t.y <= RIG_LIST_Y + RIG_LIST_H)
        {
            int idx = -1;
            for (int i = 0; i < (int)rigNames.size(); i++)
            {
                int y1 = RIG_LIST_Y + i * RIG_ROW_PITCH - rigScroll;
                if (t.y >= y1 && t.y <= y1 + RIG_ROW_H)
                {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0)
            {
                selRig = idx;
                drawRigSelectScreen();
                return;
            }
        }
    }

    // ---- スクロール（ドラッグ）----
    if (t.isPressed() && t.y >= RIG_LIST_Y && t.y <= RIG_LIST_Y + RIG_LIST_H)
    {
        if (lastY >= 0)
        {
            int dy = lastY - t.y;
            rigScroll += dy;
            int maxScroll = max(0, (int)rigNames.size() * RIG_ROW_PITCH - RIG_LIST_H);
            rigScroll = constrain(rigScroll, 0, maxScroll);
            lastY = t.y;
            drawRigSelectScreen();
            return;
        }
        lastY = t.y;
    }
    else
    {
        lastY = -1;
    }
}
