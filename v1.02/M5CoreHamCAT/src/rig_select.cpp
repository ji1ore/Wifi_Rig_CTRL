/****************************************************
 *  M5CoreHamCAT 無線機選択画面
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"

int rigScroll = 0;
int rigRowH = 26;

// ---- リグ選択画面レイアウト（Wi-Fi と同じ） ----
const int RIG_TITLE_Y = 10;
const int RIG_LIST_Y = 40;
const int RIG_LIST_H = 150;
const int RIG_BTN_Y = 200;
const int RIG_BTN_H = 30;
const int RIG_ROW_H = 26;

/****************************************************
 * リグ一覧描画（ラズパイ API から取得した rigNames を表示）
 ****************************************************/
void drawRigSelectScreen()
{
    if (rigSelectFirstDraw)
    {
        rigScroll = selRig - 2;
        if (rigScroll < 0)
            rigScroll = 0;
        int maxScroll = max(0, (int)rigNames.size() - 6);
        if (rigScroll > maxScroll)
            rigScroll = maxScroll;
    }

    canvas.fillScreen(BLACK);

    // タイトル（Wi-Fi と同じ）
    canvas.setFont(&fonts::efontJA_16_b);
    canvas.setTextColor(CYAN);
    canvas.drawString("SELECT RIG", 10, RIG_TITLE_Y);
    canvas.setFont(&fonts::efontJA_16);

    // ---- リスト描画（Wi-Fi と同じ方式）----
    const int VISIBLE_ROWS = 6;

    int total = rigNames.size();
    int maxScroll = max(0, total - VISIBLE_ROWS); // rigScroll は「行番号」として扱う
    rigScroll = constrain(rigScroll, 0, maxScroll);
    for (int row = 0; row < VISIBLE_ROWS; row++)
    {
        int idx = row + rigScroll;
        if (idx >= total)
            break;
        int y = RIG_LIST_Y + row * RIG_ROW_H;
        canvas.setTextColor(idx == selRig ? GREEN : WHITE);
        String name = rigNames[idx];
        while (canvas.textWidth(name) > 260)
            name.remove(name.length() - 1);
        if (name != rigNames[idx])
            name += "...";
        canvas.drawString(name, 10, y);
    }

    // ---- スクロールバー（Wi-Fi と同じ）----
    int totalHeight = rigNames.size() * RIG_ROW_H;
    if (totalHeight > RIG_LIST_H)
    {
        float ratio = (float)RIG_LIST_H / totalHeight;
        int barHeight = RIG_LIST_H * ratio;
        int barY = RIG_LIST_Y + (rigScroll * ratio);
        canvas.fillRect(305, barY, 6, barHeight, DARKGREY);
    }

    // BACK ボタン
    canvas.fillRoundRect(10, RIG_BTN_Y, 110, RIG_BTN_H, 6, RED);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("BACK", 10 + 55, RIG_BTN_Y + RIG_BTN_H / 2);

    // OK ボタン
    canvas.fillRoundRect(200, RIG_BTN_Y, 110, RIG_BTN_H, 6, BLUE);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("OK", 200 + 55, RIG_BTN_Y + RIG_BTN_H / 2);

    // 必ず最後に戻す
    canvas.setTextDatum(top_left);

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

    auto t = M5.Touch.getDetail();
    static int lastY = -1;

    // ---- ① ボタン押下を最優先 ----
    if (t.wasPressed())
    {
        // BACK
        if (t.x >= 10 && t.x <= 120 &&
            t.y >= RIG_BTN_Y && t.y <= RIG_BTN_Y + RIG_BTN_H)
        {
            appState = STATE_PI_CONNECT;
            rigSelectFirstDraw = true;
            return;
        }

        // OK ボタン
        if (t.x >= 200 && t.x <= 310 &&
            t.y >= RIG_BTN_Y && t.y <= RIG_BTN_Y + RIG_BTN_H)
        {
            prefs.begin("device", false);
            prefs.putInt("rigId", rigIds[selRig]); // ← rigId を保存！
            prefs.end();

            appState = STATE_DEVICE_SELECT;
            rigSelectFirstDraw = true;
            return;
        }
    }

    // ---- ② リストタップ ----
    if (t.wasPressed())
    {
        if (t.y >= RIG_LIST_Y && t.y <= RIG_LIST_Y + RIG_LIST_H)
        {
            int row = (t.y - RIG_LIST_Y) / RIG_ROW_H;
            int idx = row + rigScroll;
            if (idx >= 0 && idx < rigNames.size())
            {
                selRig = idx;
                drawRigSelectScreen();
                return;
            }
        }
    }

    // ---- ③ スクロール（行単位）----
    if (t.isPressed() &&
        t.y >= RIG_LIST_Y &&
        t.y <= RIG_LIST_Y + RIG_LIST_H)
    {
        if (lastY >= 0)
        {
            int dy = lastY - t.y;

            // 5px 以上動いたらスクロール（軽くする）
            if (abs(dy) > 5)
            {
                if (dy > 0)
                    rigScroll++;
                else
                    rigScroll--;

                int maxScroll = max(0, (int)rigNames.size() - 6);
                rigScroll = constrain(rigScroll, 0, maxScroll);

                // ★ここが重要：スクロール後に基準点を更新
                lastY = t.y;

                drawRigSelectScreen();
                return;
            }
        }
        else
        {
            lastY = t.y;
        }
    }
    else
    {
        lastY = -1;
    }
}
