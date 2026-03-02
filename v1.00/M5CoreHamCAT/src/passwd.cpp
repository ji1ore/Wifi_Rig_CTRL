/****************************************************
 *  M5CoreHamCAT パスワード入力画面
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"
#include <WiFi.h>

void drawCentered(const char *txt, int x, int y, uint16_t color)
{
    uint8_t oldDatum = canvas.getTextDatum(); // 現在の基準点を保存
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(color);
    canvas.drawString(txt, x, y);
    canvas.setTextDatum(oldDatum); // 元に戻す
    canvas.setTextColor(WHITE);
}

void drawLabel(const char *txt, int x, int y, uint16_t color)
{
    uint8_t oldDatum = canvas.getTextDatum();
    auto oldFont = canvas.getFont();
    canvas.setFont(&fonts::efontJA_12); // ★ ラベル用に小さいフォント
    canvas.setTextDatum(middle_left);
    canvas.setTextColor(color);
    canvas.drawString(txt, x, y);
    canvas.setFont(oldFont);
    canvas.setTextDatum(oldDatum);
    canvas.setTextColor(WHITE);
}

void drawPasswordScreen()
{
    canvas.fillScreen(BLACK);

    canvas.setTextColor(YELLOW);

    String title;
    if (passwordForWifi)
    {
        title = "Wi-Fi Password";
    }
    else
    {
        switch (editingField)
        {
        case FIELD_HOST:
            title = "Edit Hostname";
            break;
        case FIELD_API_PORT:
            title = "Edit API Port";
            break;
        case FIELD_AUDIO_PORT:
            title = "Edit AUDIO Port";
            break;
        case FIELD_BAUDRATE:
            title = "Edit BaudRate";
            break;
        default:
            title = "Edit Value";
            break;
        }
    }

    canvas.drawString(title, 10, 10);
    canvas.drawString(inputPassword, 10, 35);

    // キーボード切替（QWERTY ↔ NUMPAD）
    canvas.fillRoundRect(240, 40, 70, 25, 6, GREEN);
    drawCentered(kbMode == KB_QWERTY ? "123" : "ABC", 275, 52, BLACK);

    if (kbMode == KB_QWERTY)
    {
        const char *row1 = "qwertyuiop";
        const char *row2 = "asdfghjkl";
        const char *row3 = "zxcvbnm";

        int keyW = 28, keyH = 28;

        // --- 大文字小文字 ボタン ---
        canvas.fillRoundRect(10, 70 + (keyH + 5) * 3, 50, keyH, 6, CYAN);
        drawCentered(shiftOn ? "↓" : "↑", 35, 70 + (keyH + 5) * 3 + keyH / 2, BLACK);

        // 1段目
        for (int i = 0; i < 10; i++)
        {
            int x = 10 + i * (keyW + 2);
            int y = 70;
            canvas.drawRect(x, y, keyW, keyH, WHITE);

            char c = row1[i];
            if (shiftOn)
                c = toupper(c);

            drawCentered(String(c).c_str(), x + keyW / 2, y + keyH / 2);
        }

        // 2段目
        for (int i = 0; i < 9; i++)
        {
            int x = 24 + i * (keyW + 2);
            int y = 70 + keyH + 5;
            canvas.drawRect(x, y, keyW, keyH, WHITE);

            char c = row2[i];
            if (shiftOn)
                c = toupper(c);

            drawCentered(String(c).c_str(), x + keyW / 2, y + keyH / 2);
        }

        // 3段目
        for (int i = 0; i < 7; i++)
        {
            int x = 52 + i * (keyW + 2);
            int y = 70 + (keyH + 5) * 2;
            canvas.drawRect(x, y, keyW, keyH, WHITE);

            char c = row3[i];
            if (shiftOn)
                c = toupper(c);

            drawCentered(String(c).c_str(), x + keyW / 2, y + keyH / 2);
        }
    }
    else
    {
        const char *nums = "1234567890";
        int keyW = 60, keyH = 40;
        int startX = (320 - (keyW * 3 + 5 * 2)) / 2;
        int startY = 70;
        // 1〜9
        for (int i = 0; i < 9; i++)
        {
            int col = i % 3;
            int row = i / 3;
            int x = startX + col * (keyW + 5);
            int y = startY + row * (keyH + 5);
            canvas.drawRect(x, y, keyW, keyH, WHITE);
            drawCentered(String(nums[i]).c_str(), x + keyW / 2, y + keyH / 2);
        }
        // 最下段の Y 座標
        int yBottom = startY + (keyH + 5) * 3;

        // DEL と OK の位置（あなたの UI に合わせて固定）
        int delRight = 10 + 80; // DEL: x=10, w=80
        int okLeft = 230;       // OK:  x=230

        // 区間を3等分
        int interval = okLeft - delRight;
        int step = interval / 3;

        // 0 の位置
        int x0 = delRight + step - keyW / 2;
        canvas.drawRect(95, yBottom, 65, keyH, WHITE);
        drawCentered("0", 95 + keyW / 2, yBottom + keyH / 2);
        // "." の位置
        int xDot = delRight + step * 2 - keyW / 2;

        canvas.drawRect(165, yBottom, 65, keyH, WHITE);
        drawCentered(".", 165 + keyW / 2, yBottom + keyH / 2);
    }

    // DEL / OK / BACK ボタン
    canvas.fillRoundRect(10, 210, 80, 30, 6, RED);
    drawCentered("DEL", 50, 225);

    canvas.fillRoundRect(240, 210, 80, 30, 6, BLUE);
    drawCentered("OK", 280, 225);

    canvas.fillRoundRect(240, 10, 70, 25, 6, RED);
    drawCentered("BACK", 275, 22);

    canvas.pushSprite(0, 0);
}

/****************************************************
 * パスワード画面タッチ処理
 ****************************************************/
void handlePasswordScreen()
{
    // --- NUMPAD の座標を再計算（0 と . のため） ---
    int keyW = 60;
    int keyH = 40;
    int startX = (320 - (keyW * 3 + 5 * 2)) / 2;
    int startY = 70;

    // 最下段の Y 座標
    int yBottom = startY + (keyH + 5) * 3;

    // 0 のキー位置
    int x0 = startX;

    // . のキー位置（0 の右）
    int xDot = startX + keyW + 5;

    if (appState != STATE_PASSWORD)
        return;
    static bool firstDraw = true;
    if (firstDraw)
    {
        drawPasswordScreen();
        firstDraw = false;
    }

    auto t = M5.Touch.getDetail();
    if (!t.wasPressed())
        return;

    int x = t.x, y = t.y;

    // ---- キーボード切替 ----
    if (x >= 240 && x <= 240 + 70 && y >= 40 && y <= 40 + 25)
    {
        kbMode = (kbMode == KB_QWERTY) ? KB_NUMPAD : KB_QWERTY;
        drawPasswordScreen();
        return;
    }

    // BACK ボタン
    if (x >= 240 && x <= 310 && y >= 10 && y <= 35)
    {
        if (passwordForWifi)
        {
            // Wi-Fi パスワード入力から戻る
            appState = STATE_WIFI;
        }
        else
        {
            // ラズパイ設定編集から戻る
            appState = STATE_PI_CONNECT;
        }

        // 状態をクリア
        editingField = FIELD_NONE;
        passwordForWifi = false;
        firstDraw = true;
        return;
    }

    // 大文字小文字 ボタン
    if (kbMode == KB_QWERTY)
    {
        int keyW = 28, keyH = 28;
        int sx = 10;
        int sy = 70 + (keyH + 5) * 3;

        if (x >= sx && x <= sx + 50 && y >= sy && y <= sy + keyH)
        {
            shiftOn = !shiftOn;
            drawPasswordScreen();
            return;
        }
    }

    // ---- DEL ----
    if (x >= 10 && x <= 10 + 80 && y >= 210 && y <= 210 + 30)
    {
        if (inputPassword.length() > 0)
            inputPassword.remove(inputPassword.length() - 1);
        drawPasswordScreen();
        return;
    }

    // OK ボタン
    if (x >= 240 && x <= 240 + 80 && y >= 210 && y <= 210 + 30)
    {
        if (!passwordForWifi)
        {
            switch (editingField)
            {
            case FIELD_HOST:
                raspiHost = inputPassword;
                break;
            case FIELD_API_PORT:
                apiPort = inputPassword.toInt();
                break;
            case FIELD_AUDIO_PORT:
                audioPort = inputPassword.toInt();
                break;
            }

            inputPassword = "";
            editingField = FIELD_NONE;
            appState = STATE_PI_CONNECT;
            firstDraw = true;
            return;
        }
        canvas.fillRect(60, 80, 200, 80, BLACK);
        canvas.drawRect(60, 80, 200, 80, BLUE);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("Connecting", 160, 120);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);
        WiFi.begin(wifiList[selectedWifiIndex].ssid.c_str(), inputPassword.c_str());
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
            prefs.begin("wifi", false);
            prefs.putString(wifiList[selectedWifiIndex].ssid.c_str(), inputPassword);
            prefs.end();
            ssid = prefs.getString(wifiList[selectedWifiIndex].ssid.c_str());
            pass = inputPassword;
            appState = STATE_PI_CONNECT;
        }
        else
        {
            showErrorDialog = true;
            appState = STATE_WIFI;
            // drawWifiScreen();
        }
        firstDraw = true;
        return;
    }

    if (kbMode == KB_QWERTY)
    {
        int keyW = 28, keyH = 28;

        const char *row1 = "qwertyuiop";
        for (int i = 0; i < 10; i++)
        {
            int kx = 10 + i * (keyW + 2);
            int ky = 70;
            if (x >= kx && x <= kx + keyW && y >= ky && y <= ky + keyH)
            {
                inputPassword += row1[i];
                drawPasswordScreen();
                return;
            }
        }

        const char *row2 = "asdfghjkl";
        for (int i = 0; i < 9; i++)
        {
            int kx = 24 + i * (keyW + 2);
            int ky = 70 + keyH + 5;
            if (x >= kx && x <= kx + keyW && y >= ky && y <= ky + keyH)
            {
                inputPassword += row2[i];
                drawPasswordScreen();
                return;
            }
        }

        const char *row3 = "zxcvbnm";
        for (int i = 0; i < 7; i++)
        {
            int kx = 52 + i * (keyW + 2);
            int ky = 70 + (keyH + 5) * 2;
            if (x >= kx && x <= kx + keyW && y >= ky && y <= ky + keyH)
            {
                inputPassword += row3[i];
                drawPasswordScreen();
                return;
            }
        }
    }

    else
    {
        const char *nums = "1234567890";
        int keyW = 60, keyH = 40;

        int startX = (320 - (keyW * 3 + 5 * 2)) / 2;
        int startY = 70;

        // 1〜9 のタッチ判定
        for (int i = 0; i < 9; i++)
        {
            int col = i % 3;
            int row = i / 3;

            int kx = startX + col * (keyW + 5);
            int ky = startY + row * (keyH + 5);

            if (x >= kx && x <= kx + keyW &&
                y >= ky && y <= ky + keyH)
            {
                inputPassword += nums[i];
                drawPasswordScreen();
                return;
            }
        }

        // --- 0 のタッチ判定（表示位置に合わせる） ---
        if (x >= 95 && x <= 160 && y >= yBottom && y <= yBottom + keyH)
        {
            inputPassword += '0';
            drawPasswordScreen();
            return;
        }

        // --- "." のタッチ判定（表示位置に合わせる） ---
        if (x >= 165 && x <= 230 && y >= yBottom && y <= yBottom + keyH)
        {
            inputPassword += '.';
            drawPasswordScreen();
            return;
        }
    }
}