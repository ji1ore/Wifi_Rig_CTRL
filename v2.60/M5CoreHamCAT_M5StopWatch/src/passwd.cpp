/****************************************************
 *  Wifi_Rig_CTRL パスワード入力/汎用フィールド編集画面
 *  (M5StopWatch 円形466x466向け、キー配列はSP2ALART_M5STOP_1.00/src/passwd.cppの
 *   円形デザインを踏襲。Wi-Fiパスワードだけでなく、Pi接続/PTT/APRS/CI-V設定の
 *   ~20種類のフィールド編集にも使う汎用エディタとしての機能はそのまま維持)
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "globals.h"
#include <WiFi.h>

void drawWifiScreen();

// 文字キー(qwerty/asdf/zxcv)は大きめの丸ボタン。数字行は10個並ぶため
// 少しだけ小さくして安全域(円)に収める。(SP2ALART_M5STOP_1.00と同じ配置)
static const int KEY_R = 19, PITCH = 40;
static const int DIGIT_R = 17, DIGIT_PITCH = 37;
static const int ROW1_Y = 207; // qwertyuiop
static const int ROW2_Y = 249; // asdfghjkl
static const int ROW3_Y = 291; // zxcvbnm
static const int ROW4_Y = 333; // 0-9

static const int SHIFT_R = 21;
static const int BACK_CX = 318, BACK_CY = 54, BACK_R = 24;
static const int KBMODE_CX = 148, KBMODE_CY = 54, KBMODE_R = 24;
static const int DOTLOCAL_W = 96, DOTLOCAL_H = 28;
static const int DOTLOCAL_X = CANVAS_CENTER - DOTLOCAL_W / 2, DOTLOCAL_Y = 54 - DOTLOCAL_H / 2;

// NUMPAD(電話風3列x4行、大きめの丸ボタン)
static const int NUM_R = 32, NUM_COL_PITCH = 76;
static const int NUM_ROW_Y[4] = {ROW1_Y, ROW2_Y, ROW3_Y, ROW4_Y};

static int row1X(int i) { return CANVAS_CENTER - (10 * PITCH) / 2 + i * PITCH + PITCH / 2; }
static int row2X(int i) { return CANVAS_CENTER - (9 * PITCH) / 2 + i * PITCH + PITCH / 2; }
static int row3X(int i) { return CANVAS_CENTER - (7 * PITCH) / 2 + i * PITCH + PITCH / 2; }
static int row4X(int i) { return CANVAS_CENTER - (10 * DIGIT_PITCH) / 2 + i * DIGIT_PITCH + DIGIT_PITCH / 2; }
static int shiftX() { return row3X(0) - PITCH; }
static int numColX(int col) { return CANVAS_CENTER + (col - 1) * NUM_COL_PITCH; }

static void drawKeyRow(const char *chars, int count, int (*xFn)(int), int y, int r)
{
    for (int i = 0; i < count; i++)
    {
        int x = xFn(i);
        char c = chars[i];
        if (shiftOn)
            c = toupper(c);
        canvas.fillSmoothCircle(x, y, r, COL_SURFACE);
        canvas.setFont(&fonts::efontJA_16_b);
        drawCentered(String(c).c_str(), x, y + 1, COL_TEXT);
    }
}

// タップされた行内のキー文字を返す。ヒットしなければ0。
static char hitKeyRow(int x, int y, const char *chars, int count, int (*xFn)(int), int rowY, int r)
{
    for (int i = 0; i < count; i++)
    {
        if (hitCircleButton(x, y, xFn(i), rowY, r + 5))
        {
            char c = chars[i];
            if (shiftOn)
                c = toupper(c);
            return c;
        }
    }
    return 0;
}

// NUMPAD: 1-9,.,0 を3列x4行(電話風)で配置。row3の3列目(9個目)まで数字、
// row4は "." と "0" のみ(3列目は空)。
static const char NUMPAD_CHARS[12] = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 0};

static void drawNumpad()
{
    canvas.setFont(&fonts::efontJA_16_b);
    for (int i = 0; i < 11; i++)
    {
        char c = NUMPAD_CHARS[i];
        if (c == 0) continue;
        int col = i % 3, row = i / 3;
        int x = numColX(col), y = NUM_ROW_Y[row];
        canvas.fillSmoothCircle(x, y, NUM_R, COL_SURFACE);
        drawCentered(String(c).c_str(), x, y + 1, COL_TEXT);
    }
}

static char hitNumpad(int x, int y)
{
    for (int i = 0; i < 11; i++)
    {
        char c = NUMPAD_CHARS[i];
        if (c == 0) continue;
        int col = i % 3, row = i / 3;
        if (hitCircleButton(x, y, numColX(col), NUM_ROW_Y[row], NUM_R + 5))
            return c;
    }
    return 0;
}

static const char *editFieldTitle()
{
    if (passwordForWifi)
        return "Wi-Fi Password";

    switch (editingField)
    {
    case FIELD_HOST: return "Edit Hostname";
    case FIELD_API_PORT: return "Edit API Port";
    case FIELD_AUDIO_PORT: return "Edit AUDIO Port";
    case FIELD_API_KEY: return "Edit API Key";
    case FIELD_BAUDRATE: return "Edit BaudRate";
    case FIELD_PTT_HOST: return "Edit PTT Host";
    case FIELD_PTT_PORT: return "Edit PTT Port";
    case FIELD_APRS_TXFREQ: return "Edit APRS TX Freq";
    case FIELD_APRS_LAT: return "Edit APRS Latitude";
    case FIELD_APRS_LON: return "Edit APRS Longitude";
    case FIELD_APRS_CALLSIGN: return "Edit APRS Callsign";
    case FIELD_APRS_AP96_FREQ: return "Edit AP96 Freq";
    case FIELD_APRS_AP96_BAUD: return "Edit AP96 Baud";
    case FIELD_APRS_AP12_FREQ: return "Edit AP12 Freq";
    case FIELD_APRS_AP12_BAUD: return "Edit AP12 Baud";
    case FIELD_CIV_HOST: return "Edit CI-V Host";
    case FIELD_CIV_PORT1: return "Edit CI-V Ctrl Port";
    case FIELD_CIV_PORT2: return "Edit CI-V Port";
    case FIELD_CIV_USERNAME: return "Edit CI-V Username";
    case FIELD_CIV_PASSWORD: return "Edit CI-V Password";
    case FIELD_CIV_ADDRESS: return "Edit CI-V Address (hex)";
    default: return "Edit Value";
    }
}

void drawPasswordScreen()
{
    canvas.fillScreen(COL_BG);
    canvas.setFont(&fonts::efontJA_16); // ★ 前の画面のフォントを引き継がないよう明示
    ui_drawTitle(editFieldTitle());

    drawCircleButton(KBMODE_CX, KBMODE_CY, KBMODE_R, COL_SURFACE,
                      kbMode == KB_QWERTY ? "123" : "ABC", COL_TEXT_DIM, &fonts::efontJA_12);
    drawCircleButton(BACK_CX, BACK_CY, BACK_R, COL_SURFACE, "X", COL_TEXT_DIM);

    bool showDotLocal = (editingField == FIELD_HOST || editingField == FIELD_CIV_HOST);
    if (showDotLocal)
        drawPill(DOTLOCAL_X, DOTLOCAL_Y, DOTLOCAL_W, DOTLOCAL_H, COL_SURFACE, ".local", COL_TEXT_DIM);

    // 入力中の値をピル(チップ)表示
    String shown = inputPassword;
    if (shown.length() > 18)
        shown = "..." + shown.substring(shown.length() - 15);
    canvas.fillSmoothRoundRect(CANVAS_CENTER - 160, 138, 320, 44, 22, COL_SURFACE);
    canvas.setFont(&fonts::efontJA_24_b);
    drawCentered(shown.length() ? shown.c_str() : "-", CANVAS_CENTER, 138 + 22 + 1, COL_ACCENT);
    canvas.setFont(&fonts::efontJA_16);

    if (kbMode == KB_QWERTY)
    {
        drawKeyRow("qwertyuiop", 10, row1X, ROW1_Y, KEY_R);
        drawKeyRow("asdfghjkl", 9, row2X, ROW2_Y, KEY_R);
        drawKeyRow("zxcvbnm", 7, row3X, ROW3_Y, KEY_R);
        drawKeyRow("0123456789", 10, row4X, ROW4_Y, DIGIT_R);

        canvas.fillSmoothCircle(shiftX(), ROW3_Y, SHIFT_R, shiftOn ? COL_ACCENT : COL_SURFACE);
        canvas.setFont(&fonts::efontJA_16_b);
        drawCentered(shiftOn ? "AB" : "ab", shiftX(), ROW3_Y + 1, shiftOn ? (uint16_t)0x0000 : COL_TEXT_DIM);
        canvas.setFont(&fonts::efontJA_16);
    }
    else
    {
        drawNumpad();
    }

    static const int DEL_PILL_W = 130, OK_PILL_W = 130, ACTION_PILL_H = 46;
    static const int ACTION_Y = 378;
    static const int DEL_PILL_X = CANVAS_CENTER - DEL_PILL_W - 5;
    static const int OK_PILL_X = CANVAS_CENTER + 5;
    drawPill(DEL_PILL_X, ACTION_Y, DEL_PILL_W, ACTION_PILL_H, COL_DANGER, "DEL", 0x0000);
    drawPill(OK_PILL_X, ACTION_Y, OK_PILL_W, ACTION_PILL_H, COL_ACCENT, "OK", 0x0000);

    canvas.pushSprite(0, 0);
}

/****************************************************
 * パスワード画面タッチ処理
 ****************************************************/
void handlePasswordScreen()
{
    static const int DEL_PILL_W = 130, OK_PILL_W = 130, ACTION_PILL_H = 46;
    static const int ACTION_Y = 378;
    static const int DEL_PILL_X = CANVAS_CENTER - DEL_PILL_W - 5;
    static const int OK_PILL_X = CANVAS_CENTER + 5;

    if (appState != STATE_PASSWORD)
        return;

    if (passwordFirstDraw)
    {
        drawPasswordScreen();
        passwordFirstDraw = false;
    }

    auto t = M5.Touch.getDetail();
    if (!t.wasPressed())
        return;

    int x = t.x, y = t.y;

    // ---- キーボード切替 ----
    if (hitCircleButton(x, y, KBMODE_CX, KBMODE_CY, KBMODE_R))
    {
        kbMode = (kbMode == KB_QWERTY) ? KB_NUMPAD : KB_QWERTY;
        drawPasswordScreen();
        return;
    }

    // ---- BACK ----
    if (hitCircleButton(x, y, BACK_CX, BACK_CY, BACK_R))
    {
        if (passwordForWifi)
        {
            appState = STATE_WIFI;
        }
        else
        {
            // ★ editingField に応じて戻り先を決定
            switch (editingField)
            {
            case FIELD_HOST:
            case FIELD_API_PORT:
            case FIELD_AUDIO_PORT:
            case FIELD_API_KEY:
            case FIELD_BAUDRATE:
            case FIELD_CIV_HOST:
            case FIELD_CIV_PORT1:
            case FIELD_CIV_PORT2:
            case FIELD_CIV_USERNAME:
            case FIELD_CIV_PASSWORD:
            case FIELD_CIV_ADDRESS:
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_APRS_TXFREQ:
            case FIELD_APRS_LAT:
            case FIELD_APRS_LON:
            case FIELD_APRS_CALLSIGN:
            case FIELD_APRS_AP96_FREQ:
            case FIELD_APRS_AP96_BAUD:
            case FIELD_APRS_AP12_FREQ:
            case FIELD_APRS_AP12_BAUD:
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_PTT_HOST:
            case FIELD_PTT_PORT:
                appState = STATE_RIG_PTT;
                break;

            default:
                appState = STATE_PI_CONNECT;
                break;
            }
        }

        editingField = FIELD_NONE;
        passwordForWifi = false;
        passwordFirstDraw = true;
        return;
    }

    // ---- .local 補完 (ホスト名入力時のみ) ----
    bool showDotLocal = (editingField == FIELD_HOST || editingField == FIELD_CIV_HOST);
    if (showDotLocal && hitRect(x, y, DOTLOCAL_X, DOTLOCAL_Y, DOTLOCAL_W, DOTLOCAL_H))
    {
        if (!inputPassword.endsWith(".local"))
            inputPassword += ".local";
        drawPasswordScreen();
        return;
    }

    // 大文字小文字 ボタン
    if (kbMode == KB_QWERTY && hitCircleButton(x, y, shiftX(), ROW3_Y, SHIFT_R + 5))
    {
        shiftOn = !shiftOn;
        drawPasswordScreen();
        return;
    }

    // ---- DEL ----
    if (hitRect(x, y, DEL_PILL_X, ACTION_Y, DEL_PILL_W, ACTION_PILL_H))
    {
        if (inputPassword.length() > 0)
            inputPassword.remove(inputPassword.length() - 1);
        drawPasswordScreen();
        return;
    }

    // ---- OK ----
    if (hitRect(x, y, OK_PILL_X, ACTION_Y, OK_PILL_W, ACTION_PILL_H))
    {
        if (!passwordForWifi)
        {
            switch (editingField)
            {
            case FIELD_HOST:
                raspiHost = inputPassword;
                { Preferences p; p.begin("piconn", false); p.putString("host", raspiHost); p.end(); }
                appState = STATE_PI_CONNECT;
                break;
            case FIELD_API_PORT:
                apiPort = inputPassword.toInt();
                { Preferences p; p.begin("piconn", false); p.putInt("apiPort", apiPort); p.end(); }
                appState = STATE_PI_CONNECT;
                break;
            case FIELD_AUDIO_PORT:
                audioPort = inputPassword.toInt();
                { Preferences p; p.begin("piconn", false); p.putInt("audioPort", audioPort); p.end(); }
                appState = STATE_PI_CONNECT;
                break;
            case FIELD_API_KEY:
                apiKey = inputPassword;
                { Preferences p; p.begin("piconn", false); p.putString("apiKey", apiKey); p.end(); }
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_PTT_HOST:
                pttHost = inputPassword;
                appState = STATE_RIG_PTT;
                break;

            case FIELD_PTT_PORT:
                pttPort = inputPassword.toInt();
                appState = STATE_RIG_PTT;
                break;

            case FIELD_APRS_TXFREQ:
                aprsTxFreq = inputPassword.toFloat();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_LAT:
                aprsManualLat = inputPassword.toFloat();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_LON:
                aprsManualLon = inputPassword.toFloat();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_CALLSIGN:
                aprsCallsign = inputPassword;
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_AP96_FREQ:
                aprsPreset1Freq = inputPassword.toFloat();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_AP96_BAUD:
                aprsPreset1Baud = inputPassword.toInt();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_AP12_FREQ:
                aprsPreset2Freq = inputPassword.toFloat();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_APRS_AP12_BAUD:
                aprsPreset2Baud = inputPassword.toInt();
                appState = STATE_APRS_SETTINGS;
                break;

            case FIELD_CIV_HOST:
                civHost = inputPassword;
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_CIV_PORT1:
                civPort1 = inputPassword.toInt();
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_CIV_PORT2:
                civPort2 = inputPassword.toInt();
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_CIV_USERNAME:
                civUsername = inputPassword;
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_CIV_PASSWORD:
                civPassword = inputPassword;
                appState = STATE_PI_CONNECT;
                break;

            case FIELD_CIV_ADDRESS:
                civAddress = (int)strtol(inputPassword.c_str(), nullptr, 16);
                appState = STATE_PI_CONNECT;
                break;

            default:
                break;
            }

            inputPassword = "";
            passwordFirstDraw = true;
            editingField = FIELD_NONE;
            return;
        }

        // --- Wi-Fiパスワード入力からのOK: 接続を試みる ---
        canvas.fillScreen(COL_BG);
        drawCentered("Connecting", CANVAS_CENTER, CANVAS_CENTER, COL_TEXT);
        canvas.pushSprite(0, 0);
        bool timedOut = false;
        bool connected = tryConnectWifi(wifiList[selectedWifiIndex].ssid, inputPassword, timedOut);
        Serial.printf("[wifi] connect ok=%d timedOut=%d\n", connected, timedOut);
        if (connected)
        {
            ssid = wifiList[selectedWifiIndex].ssid;
            pass = inputPassword;
            prefs.begin("wifi", false);
            prefs.putString(ssid.c_str(), inputPassword);
            prefs.putString("lastssid", ssid); // 飛ばしモード用
            prefs.end();
            appState = STATE_PI_CONNECT;
        }
        else
        {
            showErrorDialog = true;
            appState = STATE_WIFI;
        }
        passwordForWifi = false;
        passwordFirstDraw = true;
        return;
    }

    // ---- キー入力 ----
    char c;
    if (kbMode == KB_QWERTY)
    {
        if ((c = hitKeyRow(x, y, "qwertyuiop", 10, row1X, ROW1_Y, KEY_R)) ||
            (c = hitKeyRow(x, y, "asdfghjkl", 9, row2X, ROW2_Y, KEY_R)) ||
            (c = hitKeyRow(x, y, "zxcvbnm", 7, row3X, ROW3_Y, KEY_R)) ||
            (c = hitKeyRow(x, y, "0123456789", 10, row4X, ROW4_Y, DIGIT_R)))
        {
            inputPassword += c;
            drawPasswordScreen();
            return;
        }
    }
    else
    {
        if ((c = hitNumpad(x, y)))
        {
            inputPassword += c;
            drawPasswordScreen();
            return;
        }
    }
}
