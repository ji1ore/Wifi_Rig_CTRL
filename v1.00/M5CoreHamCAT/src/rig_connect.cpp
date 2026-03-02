/****************************************************
 *  M5CoreHamCAT 無線機接続画面
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"
#include <HTTPClient.h>
#include <ArduinoJson.h>

void drawRigConnectScreen();

void handleRigConnectScreen()
{

    auto t = M5.Touch.getDetail();
    static bool loaded = false;

    if (appState == STATE_CONNECT_FAILED)
    {
        canvas.fillScreen(BLACK);
        canvas.setTextColor(RED);
        canvas.setFont(&fonts::efontJA_16);
        canvas.drawString("Connect Failed", 80, 100);
        canvas.setTextColor(WHITE);
        canvas.fillRoundRect(100, 150, 120, 30, 6, BLUE);
        canvas.setTextColor(WHITE);
        canvas.setTextDatum(middle_center);
        canvas.drawString("OK", 160, 165);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);

        if (t.wasPressed() && t.x >= 100 && t.x <= 220 && t.y >= 150 && t.y <= 180)
        {
            appState = STATE_DEVICE_SELECT;
            rigConnectFirstDraw = true;
        }
        return;
    }

    if (appState != STATE_DEVICE_SELECT)
        return;

    if (!loaded)
    {
        bool rigsOK = false;

        // --- /rigs ---
        {
            HTTPClient http;
            String url = "http://" + HostName + ":" + String(apiPort) + "/rigs";
            http.begin(url);
            int code = http.GET();
            Serial.printf("[/rigs] HTTP code = %d\n", code);

            if (code != 200)
            {
                lastErrorMessage = "Open Failed (" + String(code) + ")";
                appState = STATE_CONNECT_FAILED;
                rigConnectFirstDraw = true;

                return;
            }
            else if (code == 200)
            {
                String body = http.getString();
                JsonDocument doc;
                if (deserializeJson(doc, body) == DeserializationError::Ok)
                {
                    for (JsonObject r : doc["rigs"].as<JsonArray>())
                    {
                        rigIds.push_back(r["id"].as<int>());
                        rigNames.push_back(r["name"].as<String>());
                    }
                    rigsOK = true;
                }
            }
            http.end();
        }

        // --- /devices（空でもOK） ---
        {
            HTTPClient http;
            String url = "http://" + HostName + ":" + String(apiPort) + "/devices";
            http.begin(url);
            int code = http.GET();
            Serial.printf("[/devices] HTTP code = %d\n", code);

            if (code == 200)
            {
                String body = http.getString();
                JsonDocument doc;
                if (!deserializeJson(doc, body))
                {
                    // CATデバイス
                    JsonArray serial = doc["serial"].as<JsonArray>();
                    for (JsonVariant d : serial)
                    {
                        catList.push_back(d.as<String>());
                    }
                    std::sort(catList.begin(), catList.end());
                }
            }
            http.end();
        }

        if (rigsOK)
        {
            if (catList.empty())
                catList.push_back("None");

            // ★ rigIds[] が埋まった後に rigId を読み込む！
            prefs.begin("device", true);
            int savedRigId = prefs.getInt("rigId", -1);
            /*
            String savedCatName = prefs.getString("catName", "");
            */
            selSampling = prefs.getInt("sampling", 0);
            prefs.end();

            // rigIds から一致するインデックスを探す
            selRig = 0;
            for (int i = 0; i < rigIds.size(); ++i)
            {
                if (rigIds[i] == savedRigId)
                {
                    selRig = i;
                    break;
                }
            }

            selCat = 0;
            /*
            bool catFound = false;
            for (int i = 0; i < catList.size(); ++i)
            {
                if (catList[i] == savedCatName)
                {
                    selCat = i;
                    catFound = true;
                    break;
                }
            }
            if (!catFound)
            {
                Serial.printf("⚠️ CATデバイス '%s' が見つかりません。デフォルトに戻します。\n", savedCatName.c_str());
            }
            */
            loaded = true;
            rigConnectFirstDraw = true;
            return;
        }
    }

    if (!loaded)
    {
        canvas.fillScreen(BLACK);
        canvas.setTextColor(WHITE);
        canvas.drawString("Loading...", 100, 120);
        canvas.pushSprite(0, 0);
        return;
    }

    // --- 初回ロード ---
    if (rigConnectFirstDraw)
    {
        drawRigConnectScreen();
        rigConnectFirstDraw = false;
    }

    // --- タッチ処理 ---
    if (!t.wasPressed())
        return;

    int x = t.x, y = t.y;
    // Rig model
    if (x >= 10 && x <= 310 && y >= 30 && y <= 58)
    {
        appState = STATE_RIG_CONNECT;
        rigConnectFirstDraw = true;
        return;
    }

    // CAT
    if (x >= 10 && x <= 310 && y >= 90 && y <= 118)
    {
        selCat = (selCat + 1) % catList.size();
        drawRigConnectScreen();
        return;
    }

    // Baud Rate
    if (x >= 10 && x <= 150 && y >= 150 && y <= 180)
    {
        selBaud = (selBaud + 1) % baudRates.size();
        drawRigConnectScreen();
        return;
    }

    // Sampling Rate
    if (x >= 170 && x <= 310 && y >= 150 && y <= 180)
    {
        selSampling = (selSampling + 1) % samplingRates.size();
        drawRigConnectScreen();
        return;
    }

    // Back ボタン
    if (x >= 10 && x <= 110 && y >= 200 && y <= 235)
    {
        appState = STATE_WIFI; // または戻りたい画面
        rigConnectFirstDraw = true;
        return;
    }

    // Connect ボタン
    if (x >= 130 && x <= 310 && y >= 200 && y <= 235)
    {

        if (rigNames.empty() || rigIds.empty())
        {
            appState = STATE_CONNECT_FAILED;
            lastErrorMessage = "Device Not Found";
            rigConnectFirstDraw = true;
            return;
        }

        canvas.fillRect(60, 80, 200, 80, BLACK); // 背景クリア
        canvas.drawRect(60, 80, 200, 80, BLUE);  // 青い枠
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.setFont(&fonts::efontJA_16);
        canvas.drawString("Connecting", 160, 120); // 中央に表示
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);

        prefs.begin("device", false);
        prefs.putInt("rigId", rigIds[selRig]);
        /*
        String catToSave = (catList[selCat] == "None") ? "" : catList[selCat];
        prefs.putString("catName", catToSave);
        */
        prefs.putInt("baud", selBaud);
        prefs.putInt("sampling", selSampling);
        prefs.end();

        String catParam = (catList[selCat] == "None") ? "" : catList[selCat];

        String url = "http://" + HostName + ":" + String(apiPort) +
                     "/radio/open?model=" + rigIds[selRig] +
                     "&cat=" + catParam +
                     "&baud=" + String(baudRates[selBaud]);

        HTTPClient http;
        http.begin(url);
        http.GET();
        http.end();
        delay(1000);

        // --- ステータス取得待ち ---
        bool ready = false;
        for (int i = 0; i < 30; ++i) // 最大3秒待つ（100ms × 30回）
        {
            delay(100);
            HTTPClient statusHttp;
            String statusUrl = "http://" + HostName + ":" + String(apiPort) + "/radio/status";
            statusHttp.begin(statusUrl);
            int statusCode = statusHttp.GET();
            if (statusCode == 200)
            {
                String body = statusHttp.getString();
                JsonDocument doc;
                if (!deserializeJson(doc, body))
                {
                    if (doc["freq"].is<String>()) // 必要なキーがあるか確認
                    {
                        ready = true;
                        break;
                    }
                }
            }
            statusHttp.end();
        }

        if (ready)
        {
            appState = STATE_MAIN_UI;
            rigConnectFirstDraw = true;
        }
        else
        {
            lastErrorMessage = "Connect Time Out";
            appState = STATE_CONNECT_FAILED;
            rigConnectFirstDraw = true;
            return;
        }

        return;
    }
}

void drawRigConnectScreen()
{
    canvas.fillScreen(BLACK);

    // ---- Rig model ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_left);
    canvas.drawString("Rig model", 10, 15);

    canvas.drawRect(10, 30, 300, 30, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextDatum(middle_left);
    canvas.drawString(rigNames[selRig], 15, 45);

    // ---- CAT Device ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("CAT Device", 10, 75);

    canvas.drawRect(10, 90, 300, 30, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextDatum(middle_left);
    canvas.drawString(catList[selCat], 15, 105);

    // ---- Baud Rate ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("Baud Rate", 10, 135);
    canvas.drawRect(10, 150, 165, 30, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextDatum(middle_left);
    canvas.drawString(String(baudRates[selBaud]) + " bps", 15, 165);

    // ---- Sampling Rate ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("Sampling", 180, 135);
    canvas.drawRect(180, 150, 130, 30, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextDatum(middle_left);
    canvas.drawString(String(samplingRates[selSampling]) + " Hz", 180, 165);

    int btnY = 200;
    int btnH = 35;

    // Back ボタン
    canvas.fillRoundRect(10, btnY, 100, btnH, 6, RED);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("Back", 10 + 50, btnY + btnH / 2);

    // Connect ボタン
    canvas.fillRoundRect(130, btnY, 180, btnH, 6, BLUE);
    canvas.setTextColor(WHITE);
    canvas.drawString("Connect", 130 + 90, btnY + btnH / 2);

    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}
