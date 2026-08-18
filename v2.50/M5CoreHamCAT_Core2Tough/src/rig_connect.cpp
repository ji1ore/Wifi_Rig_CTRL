/****************************************************
 *  Wifi_Rig_CTRL 無線機接続画面
 *  Ver2.5
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"
#include <HTTPClient.h>
#include <ArduinoJson.h>

// WiFi.hostByName() は稀に(このArduino-ESP32コアで確認済み)DNS失敗時に
// 制御を返さずハングすることがある。UIスレッドを道連れにしないよう、
// 別タスクで実行しタイムアウトしたら強制的にタスクを破棄して失敗扱いにする。
struct PttDnsTask
{
    char host[64];
    IPAddress ip;
    volatile bool done = false;
    volatile bool ok = false;
};

static void pttDnsTaskFunc(void *param)
{
    PttDnsTask *t = (PttDnsTask *)param;
    IPAddress ip;
    bool ok = WiFi.hostByName(t->host, ip);
    t->ip = ip;
    t->ok = ok;
    t->done = true;
    vTaskDelete(NULL);
}

// 戻り値: 解決できた場合 true(outIp に結果を格納)。
// タイムアウトまたは解決失敗の場合 false(timedOut で区別可能)。
static bool resolveHostWithTimeout(const String &host, IPAddress &outIp, unsigned long timeoutMs, bool &timedOut)
{
    static PttDnsTask dnsTask;
    dnsTask.done = false;
    dnsTask.ok = false;
    host.toCharArray(dnsTask.host, sizeof(dnsTask.host));

    TaskHandle_t th = nullptr;
    xTaskCreatePinnedToCore(pttDnsTaskFunc, "pttDns", 4096, &dnsTask, 1, &th, 1);

    unsigned long start = millis();
    while (!dnsTask.done && millis() - start < timeoutMs)
    {
        delay(20);
    }

    if (!dnsTask.done)
    {
        // ハングしたタスクを強制破棄して先に進む
        if (th) vTaskDelete(th);
        timedOut = true;
        return false;
    }

    timedOut = false;
    if (dnsTask.ok)
        outIp = dnsTask.ip;
    return dnsTask.ok;
}

void drawRigConnectScreen();

void handleRigConnectScreen()
{

    int btnY = 200;
    int btnH = 35;

    auto t = M5.Touch.getDetail();
    static bool loaded = false;

    // ★ rigConnectFirstDraw が立ったらリセット（Back→再入時の重複防止）
    if (rigConnectFirstDraw && loaded)
    {
        loaded = false;
        rigIds.clear();
        rigNames.clear();
        catList.clear();
    }

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
            if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
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
            if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
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
            selSampling = prefs.getInt("sampling", 0);
            selScreenTimeout = prefs.getInt("disp", 2); // デフォルトは10分（index 2）
            String savedCatDev = prefs.getString("catDev", "");
            prefs.end();

            screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;

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

            // 保存済み CAT Device を catList から探して復元（見つからなければ 0）
            selCat = 0;
            for (int i = 0; i < catList.size(); ++i)
            {
                if (catList[i] == savedCatDev)
                {
                    selCat = i;
                    break;
                }
            }
            loaded = true;
            drawRigConnectScreen();
            rigConnectFirstDraw = false;
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
    if (x >= 10 && x <= 185 && y >= 90 && y <= 118)
    {
        selCat = (selCat + 1) % catList.size();
        drawRigConnectScreen();
        return;
    }

    // Timeout（CATの右側）
    if (x >= 190 && x <= 310 && y >= 90 && y <= 120)
    {
        selScreenTimeout = (selScreenTimeout + 1) % screenTimeoutOptions.size();
        screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;
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
        speakerEnabled = (samplingRates[selSampling] != 0);
        drawRigConnectScreen();
        return;
    }

    // Back
    if (x >= 10 && x <= 100 && y >= btnY && y <= btnY + btnH)
    {
        appState = STATE_WIFI;
        rigConnectFirstDraw = true;
        return;
    }

    // Connect ボタン
    if (x >= 110 && x <= 240 && y >= btnY && y <= btnY + btnH)
    {
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

            Serial.println("[connect] start");

            // ★ Wifi_Rig_PTT の場合、UDP Ping で接続確認
            if (useWifiPTT)
            {
                Serial.println("[connect] useWifiPTT: DNS resolve start (bounded)");
                WiFiUDP udp;
                udp.begin(0); // 任意ポートで開始

                // WiFi.hostByName()は".local"が付いていないとmDNS解決を試みず、
                // 通常のDNS問い合わせとして失敗するため、必要なら自動的に補完する
                // (IPアドレスがそのまま入力されている場合は付けない)。
                String pttResolveHost = pttHost;
                IPAddress rawIp;
                if (!pttResolveHost.endsWith(".local") && !rawIp.fromString(pttResolveHost))
                {
                    pttResolveHost += ".local";
                }

                IPAddress pttIP;
                bool dnsTimedOut = false;
                bool dnsOK = resolveHostWithTimeout(pttResolveHost, pttIP, 3000, dnsTimedOut);
                Serial.printf("[connect] PTT DNS resolve host=%s ok=%d timedOut=%d\n", pttResolveHost.c_str(), dnsOK, dnsTimedOut);
                if (!dnsOK)
                {
                    lastErrorMessage = dnsTimedOut ? "PTT Host Resolve Timeout" : "PTT Host Resolve Failed";
                    appState = STATE_CONNECT_FAILED;
                    rigConnectFirstDraw = true;
                    return;
                }

                // Ping送信（0xFF）
                byte ping = 0xFF;
                udp.beginPacket(pttIP, pttPort);
                udp.write(&ping, 1);
                udp.endPacket();

                unsigned long start = millis();
                bool pongOK = false;

                // Pong待ち（最大500ms）
                while (millis() - start < 500)
                {
                    int size = udp.parsePacket();
                    if (size == 1)
                    {
                        byte buf[1];
                        udp.read(buf, 1);
                        if (buf[0] == 0xFE) // Pong
                        {
                            pongOK = true;
                            break;
                        }
                    }
                    delay(10);
                }

                if (!pongOK)
                {
                    lastErrorMessage = "PTT Server No Response";
                    appState = STATE_CONNECT_FAILED;
                    rigConnectFirstDraw = true;
                    return;
                }
            }

            Serial.println("[connect] saving prefs");
            prefs.begin("device", false);
            prefs.putInt("rigId", rigIds[selRig]);
            prefs.putInt("baud", selBaud);
            prefs.putInt("disp", selScreenTimeout);
            prefs.putInt("sampling", selSampling);
            prefs.putString("catDev", catList[selCat]); // 飛ばしモード用
            prefs.putBool("useWifiPTT", useWifiPTT);
            prefs.putString("pttHost", pttHost);
            prefs.putInt("pttPort", pttPort);
            prefs.putString("pttDevice", pttDevice);
            prefs.putString("pttType", pttType);
            prefs.end();
            Serial.println("[connect] prefs saved");

            String catParam = (catList[selCat] == "None") ? "" : catList[selCat];

            // --- I2S 初期化 ---
            SRate = samplingRates[selSampling];
            speakerEnabled = (SRate != 0);
            i2s_config.sample_rate = (SRate > 0) ? SRate : 8000;

            // 既存ドライバを解放するだけ。インストールは streamTask/audioTxTask が行う
            Serial.println("[connect] i2s_driver_uninstall start");
            i2s_driver_uninstall(I2S_NUM_0);
            Serial.println("[connect] i2s_driver_uninstall done");

            String url = "http://" + HostName + ":" + String(apiPort) +
                         "/radio/open?model=" + rigIds[selRig] +
                         "&cat=" + catParam +
                         "&baud=" + String(baudRates[selBaud]);

            // Hamlib PTTかつデバイス指定あり → ptt/ptt_type パラメータを追加
            if (!useWifiPTT && !pttDevice.isEmpty())
            {
                url += "&ptt=" + pttDevice + "&ptt_type=" + pttType;
            }

            Serial.printf("[connect] GET %s\n", url.c_str());
            HTTPClient http;
            http.begin(url);
            http.setConnectTimeout(5000);
            http.setTimeout(5000);
            if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
            int openCode = http.GET();
            Serial.printf("[connect] /radio/open code=%d\n", openCode);
            http.end();
            delay(1000);

            // --- ステータス取得待ち ---
            bool ready = false;
            for (int i = 0; i < 30; ++i) // 最大3秒待つ（100ms × 30回）
            {
                delay(100);
                Serial.printf("[connect] /radio/status poll %d start\n", i);
                HTTPClient statusHttp;
                String statusUrl = "http://" + HostName + ":" + String(apiPort) + "/radio/status";
                statusHttp.begin(statusUrl);
                statusHttp.setConnectTimeout(3000);
                statusHttp.setTimeout(3000);
                if (!apiKey.isEmpty()) statusHttp.addHeader("X-API-Key", apiKey);
                int statusCode = statusHttp.GET();
                Serial.printf("[connect] /radio/status poll %d code=%d\n", i, statusCode);
                if (statusCode == 200)
                {
                    String body = statusHttp.getString();
                    JsonDocument doc;
                    if (!deserializeJson(doc, body))
                    {
                        if (doc["freq"].is<String>() || doc["freq"].is<int>() || doc["freq"].is<float>())
                        // 必要なキーがあるか確認
                        {
                            ready = true;
                            break;
                        }
                    }
                }
                statusHttp.end();
            }
            Serial.printf("[connect] status poll loop done, ready=%d\n", ready);

            if (ready)
            {

                // --- メインUI遷移前に APRS 用の RigID / CAT Device を更新 ---
                String newRigID = String(rigIds[selRig]);
                String newCatDev = catList[selCat];

                // 前回値と違うかチェック
                bool changed = false;
                if (aprsRigID != newRigID)
                    changed = true;
                if (aprsCatDevice != newCatDev)
                    changed = true;

                // 値を更新
                aprsRigID = newRigID;
                aprsCatDevice = newCatDev;

                // 変更があったら APRS Enabled を OFF にする
                if (changed)
                {
                    aprsEnabled = false;
                    Serial.println("APRS disabled because rigID/CAT changed");
                }

                // ★ この機種がVFO A/B型かMAIN/SUB型か、現在どちら側かをPiに問い合わせる
                //   (接続直後の\dump_caps判定がバックグラウンドで終わっているはずのタイミング)
                fetchVfoState();

                appState = STATE_MAIN_UI;
                rigConnectFirstDraw = true;
                lastUserFreqChange = millis();
                lastUserModeChange = millis();
                lastUserWidthChange = millis();
                lastUserSQLChange = millis();
                lastUserPowerChange = millis();
                lastUserVolumeChange = millis();
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
    // PTT Settings ボタン
    if (x >= 250 && x <= 310 && y >= btnY && y <= btnY + btnH)
    {
        appState = STATE_RIG_PTT; // ← PTT 画面へ遷移
        rigConnectFirstDraw = true;
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

    // CAT Device 選択欄
    canvas.drawRect(10, 90, 175, 30, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextDatum(middle_left);
    canvas.drawString(catList[selCat], 15, 105);

    // ---- Disp Off ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("Disp Off", 190, 75);

    // Disp Off 選択欄
    canvas.drawRect(190, 90, 115, 30, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    String timeoutLabel = (screenTimeoutOptions[selScreenTimeout] == 0) ? "Off" : String(screenTimeoutOptions[selScreenTimeout]) + " min";
    canvas.drawString(timeoutLabel, 190, 105);

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
    canvas.fillRoundRect(10, btnY, 90, btnH, 6, RED);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("Back", 10 + 45, btnY + btnH / 2);

    // Connect ボタン
    canvas.fillRoundRect(110, btnY, 130, btnH, 6, BLUE);
    canvas.setTextColor(WHITE);
    canvas.drawString("Connect", 110 + 65, btnY + btnH / 2);

    // PTT ボタン
    canvas.fillRoundRect(250, btnY, 60, btnH, 6, YELLOW);
    canvas.setTextColor(BLACK);
    canvas.drawString("PTT", 250 + 30, btnY + btnH / 2);

    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}
