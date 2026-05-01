/****************************************************
 *  Wifi_Rig_CTRL aprs_settings.cpp
 *  Ver1.20
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include <M5GFX.h>
#include <HTTPClient.h>
#include "globals.h"
#include "ui_core.h"
#include <ArduinoJson.h>

extern M5Canvas canvas;

static int scrollOffset = 0;
static int touchStartY = -1;

const int HEADER_H = 50; // タイトル＋OKボタンの高さ

void fetchSoundDevices()
{
    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/devices";
    http.begin(url);
    int code = http.GET();

    if (code == 200)
    {
        String payload = http.getString();
        DynamicJsonDocument doc(4096);
        deserializeJson(doc, payload);

        soundDeviceList.clear();
        soundDeviceLabel.clear();

        for (auto v : doc["audio"].as<JsonArray>())
        {
            soundDeviceList.push_back(String(v["id"].as<const char *>()));
            soundDeviceLabel.push_back(String(v["label"].as<const char *>()));
        }
    }
    http.end();
}

// ==============================
//  描画
// ==============================
void drawAprsSettingsUI()
{
    canvas.fillScreen(BLACK);

    // --- タイトル（固定） ---
    canvas.setFont(&fonts::efontJA_16_b);
    canvas.setTextColor(CYAN);
    canvas.setCursor(10, 10);
    canvas.print("APRS Settings");

    // --- OK ボタン（固定） ---
    int btnW = 80;
    int btnH = 30;
    int btnX = 320 - btnW - 10;
    int btnY = 10;

    canvas.fillRoundRect(btnX, btnY, btnW, btnH, 6, BLUE);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_center);
    canvas.drawString("OK", btnX + btnW / 2, btnY + btnH / 2);
    canvas.setTextDatum(top_left);

    // --- スクロール領域 ---
    int y = HEADER_H + scrollOffset;
    int rowH = 26;

    int labelX = 10;
    int boxX = 158;
    int boxW = 162;

    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextColor(WHITE);

    auto drawRow = [&](const char *label, const String &value)
    {
        // ★ ヘッダーより上に来た行は描画しない
        if (y + rowH < HEADER_H)
        {
            y += rowH + 6;
            return;
        }

        // ★ 画面下に完全に出た行も描画しない
        if (y > 240)
        {
            y += rowH + 6;
            return;
        }

        // --- 通常描画 ---
        canvas.setCursor(labelX, y + 4);
        canvas.print(label);

        canvas.drawRect(boxX, y, boxW, rowH, WHITE);
        canvas.setCursor(boxX + 6, y + 4);
        canvas.print(value);

        y += rowH + 6;
    };

    drawRow("APRS Enabled", aprsEnabled ? "ON" : "OFF");
    drawRow("Use GPS", aprsUseGPS ? "ON" : "OFF");
    drawRow("Latitude", String(aprsManualLat, 5));
    drawRow("Longitude", String(aprsManualLon, 5));
    drawRow("APRS TXFreq", String(aprsTxFreq, 2));
    drawRow("Baudrate", String(aprsBaud));
    drawRow("TX Interval", String(aprsIntervalSec) + " sec");
    drawRow("Callsign", aprsCallsign);
    drawRow("SSID", "-" + String(aprsSSID));

    canvas.setFont(&fonts::efontJA_10);
    drawRow("Path", aprsPath);

    canvas.setFont(&fonts::efontJA_12);
    drawRow("Symbol", aprsSymbol);
    drawRow("Destination", aprsDestination);
    canvas.setFont(&fonts::lgfxJapanGothic_8);
    drawRow("Sound Device", aprsSoundDevice);
    canvas.setFont(&fonts::efontJA_12);

    canvas.pushSprite(0, 0);
}

// ==============================
//  タッチ処理
// ==============================
void handleAPRSSettingsScreen()
{
    if (aprsSettingsFirstDraw)
    {
        fetchSoundDevices();
        drawAprsSettingsUI();
        aprsSettingsFirstDraw = false;
    }

    auto t = M5.Touch.getDetail();

    // --- OK ボタン座標 ---
    int btnW = 80;
    int btnH = 30;
    int btnX = 320 - btnW - 10;
    int btnY = 10;

    // ==============================
    //  OK ボタン（最優先）
    // ==============================
    if (t.wasReleased())
    {
        if (t.x >= btnX && t.x <= btnX + btnW &&
            t.y >= btnY && t.y <= btnY + btnH)
        {
            // ---- JSON 送信 ----
            HTTPClient http;
            String url = "http://" + HostName + ":" + String(apiPort) + "/aprs_config";
            http.begin(url);
            http.addHeader("Content-Type", "application/json");

            String body = "{";
            body += "\"callsign\":\"" + aprsCallsign + "\",";
            body += "\"ssid\":" + String(aprsSSID) + ",";
            body += "\"path\":\"" + aprsPath + "\",";
            body += "\"interval\":" + String(aprsIntervalSec) + ",";
            body += "\"freq\":" + String(aprsTxFreq) + ",";
            body += "\"baud\":" + String(aprsBaud) + ",";
            body += "\"use_gps\":" + String(aprsUseGPS ? "true" : "false") + ",";
            body += "\"manual_lat\":" + String(aprsManualLat, 6) + ",";
            body += "\"manual_lon\":" + String(aprsManualLon, 6) + ",";
            body += "\"symbol\":\"" + aprsSymbol + "\",";
            body += "\"destination\":\"" + aprsDestination + "\",";
            body += "\"sound_device\":\"" + aprsSoundDevice + "\",";
            body += "\"rig_id\":\"" + aprsRigID + "\",";
            body += "\"cat_device\":\"" + aprsCatDevice + "\"";
            body += "}";

            int code = http.POST(body);
            http.end();

            if (code == 200)
            {
                Preferences prefs;
                prefs.begin("aprs", false);
                prefs.putBool("enabled", aprsEnabled);
                prefs.putBool("useGPS", aprsUseGPS);
                prefs.putFloat("lat", aprsManualLat);
                prefs.putFloat("lon", aprsManualLon);
                prefs.putFloat("aprsfreq", aprsTxFreq);
                prefs.putInt("baud", aprsBaud);
                prefs.putString("call", aprsCallsign);
                prefs.putInt("ssid", aprsSSID);
                prefs.putInt("interval", aprsIntervalSec);
                prefs.putString("path", aprsPath);
                prefs.putString("symbol", aprsSymbol);
                prefs.putString("destination", aprsDestination);
                prefs.putString("sounddev", aprsSoundDevice);
                prefs.putString("rigid", aprsRigID);
                prefs.putString("catdev", aprsCatDevice);
                prefs.end();
            }

            appState = STATE_MAIN_UI;
            mainFirstDraw = true;
            return;
        }
    }

    // ==============================
    //  スクロール開始
    // ==============================
    if (t.wasPressed())
    {
        touchStartY = t.y;
    }

    // ==============================
    //  スクロール処理（HEADER_H より下のみ）
    // ==============================
    if (t.wasReleased())
    {
        if (t.y > HEADER_H)
        {
            int dy = t.y - touchStartY;

            if (abs(dy) > 20)
            {
                scrollOffset += (dy > 0 ? 20 : -20);
                if (scrollOffset > 0)
                    scrollOffset = 0;
                if (scrollOffset < -260)
                    scrollOffset = -260;

                drawAprsSettingsUI();
                return;
            }
        }

        // ==============================
        //  設定項目タップ処理
        // ==============================
        int y = (t.y - HEADER_H) - scrollOffset; // ★表示と完全一致

        if (y >= 0 && y < 32)
            aprsEnabled = !aprsEnabled;
        else if (y >= 32 && y < 64)
            aprsUseGPS = !aprsUseGPS;
        else if (y >= 64 && y < 96)
        {
            editingField = FIELD_APRS_LAT;
            inputPassword = String(aprsManualLat, 5);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;
        }
        else if (y >= 96 && y < 128)
        {
            editingField = FIELD_APRS_LON;
            inputPassword = String(aprsManualLon, 5);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;
        }
        else if (y >= 128 && y < 160)
        {
            editingField = FIELD_APRS_TXFREQ;
            inputPassword = String(aprsTxFreq, 2);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;
        }
        else if (y >= 160 && y < 192)
            aprsBaud = (aprsBaud == 1200 ? 9600 : 1200);
        else if (y >= 192 && y < 224)
        {
            int list[] = {30, 60, 120, 180, 300, 600};
            int count = 6;
            int idx = 0;
            for (int i = 0; i < count; i++)
                if (aprsIntervalSec == list[i])
                    idx = i;
            idx = (idx + 1) % count;
            aprsIntervalSec = list[idx];
        }
        else if (y >= 224 && y < 256)
        {
            editingField = FIELD_APRS_CALLSIGN;
            inputPassword = aprsCallsign;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            return;
        }
        else if (y >= 256 && y < 288)
            aprsSSID = (aprsSSID + 1) % 16;
        else if (y >= 288 && y < 320)
        {
            static const char *pathList[] = {
                "WIDE1-1", "WIDE1-1,WIDE2-1", "WIDE2-1", "DIRECT", "NONE"};
            int count = 5;
            int idx = 0;
            for (int i = 0; i < count; i++)
                if (aprsPath == pathList[i])
                    idx = i;
            idx = (idx + 1) % count;
            aprsPath = pathList[idx];
        }
        else if (y >= 320 && y < 352)
        {
            static const char *symList[] = {">", "v", "[", "/", "-"};
            int count = 5;
            int idx = 0;
            for (int i = 0; i < count; i++)
                if (aprsSymbol == symList[i])
                    idx = i;
            idx = (idx + 1) % count;
            aprsSymbol = symList[idx];
        }
        else if (y >= 352 && y < 384)
        {
            static const char *destList[] = {
                "APDW18", "APDW19", "APDW20", "APYA05", "APMI05", "APRS00"};
            int count = 6;
            int idx = 0;
            for (int i = 0; i < count; i++)
                if (aprsDestination == destList[i])
                    idx = i;
            idx = (idx + 1) % count;
            aprsDestination = destList[idx];
        }

        else if (y >= 384 && y < 416)
        { // Sound Device の行
            if (soundDeviceList.size() > 0)
            {
                // 現在の index を探す
                int idx = 0;
                for (int i = 0; i < soundDeviceList.size(); i++)
                {
                    if (aprsSoundDevice == soundDeviceList[i])
                    {
                        idx = i;
                        break;
                    }
                }

                // 次のデバイスへ
                idx = (idx + 1) % soundDeviceList.size();
                aprsSoundDevice = soundDeviceList[idx];
            }

            drawAprsSettingsUI();
            return;
        }

        drawAprsSettingsUI();
    }
}
