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

// ★ 丸型ディスプレイ(466x466, 中心233, 半径233)向けレイアウト。
//   タッチ判定(handleRigConnectScreen)と描画(drawRigConnectScreen)の両方でこの
//   定数群を共有し、ズレが起きないようにする。全ての行は円の可視領域(半径215の
//   安全マージン込み)に収まるよう計算済み。行の高さも50pxに拡大し、押しやすく
//   してある。
static const int RC_TITLE_Y = 70;
static const int RC_ROW_H   = 36;                // ★ Audio Device行追加のため若干詰めた(46→36)
static const int RC_TITLE_OFF = 12;              // 各行の見出しをROW_Yの何px上に描くか
static const int RC_ROW1_Y  = 130;               // Rig model(全幅)
static const int RC_ROW1_W  = 300;
static const int RC_ROW1_X  = CANVAS_CENTER - RC_ROW1_W / 2; // 83
static const int RC_PAIR_W  = 150;               // CAT/Disp、Baud/Sampling、CW Dev/CW Connectの2列行
static const int RC_PAIR_GAP = 10;
static const int RC_PAIR_L_X = CANVAS_CENTER - RC_PAIR_W - RC_PAIR_GAP / 2; // 78
static const int RC_PAIR_R_X = CANVAS_CENTER + RC_PAIR_GAP / 2;             // 238
// ★ 各行の見出しはROW_YのRC_TITLE_OFF px上に描画するため、ピッチはROW_H+RC_TITLE_OFF
//   以上必要(見出しが上の行の枠に食い込むのを防ぐ)。
static const int RC_ROW2_Y  = 180;               // CAT Device / Disp Off
static const int RC_ROW3_Y  = 230;               // Baud Rate / Sampling Rate
static const int RC_ROW4_Y  = 280;               // CW Device / CW Connect
static const int RC_ROW5_Y  = 330;               // ★ Audio Device(全幅、cw行と同様タップで巡回)
static const int RC_BTN_Y   = 384;               // Back / Connect / PTT
static const int RC_BTN_H   = 50;
static const int RC_BACK_W    = 90;
static const int RC_CONNECT_W = 140;
static const int RC_PTT_W     = 90;
static const int RC_BTN_GAP   = 10;
static const int RC_BTN_X0    = CANVAS_CENTER - (RC_BACK_W + RC_CONNECT_W + RC_PTT_W + RC_BTN_GAP * 2) / 2; // 63
static const int RC_BACK_X    = RC_BTN_X0;
static const int RC_CONNECT_X = RC_BACK_X + RC_BACK_W + RC_BTN_GAP;
static const int RC_PTT_X     = RC_CONNECT_X + RC_CONNECT_W + RC_BTN_GAP;

void handleRigConnectScreen()
{

    int btnY = RC_BTN_Y;
    int btnH = RC_BTN_H;

    auto t = M5.Touch.getDetail();
    static bool loaded = false;

    // ★ rigConnectFirstDraw が立ったらリセット（Back→再入時の重複防止）
    if (rigConnectFirstDraw && loaded)
    {
        Serial.printf("[dbg][rig_connect] reset triggered: selRig(before)=%d\n", selRig);
        loaded = false;
        rigIds.clear();
        rigNames.clear();
        catList.clear();
        audioDevList.clear();
    }

    if (appState == STATE_CONNECT_FAILED)
    {
        // ★ 以前は320x240時代の左上寄せ座標のままだったため、他画面と同じく
        //   CANVAS_CENTER基準の中央寄せレイアウトに修正。
        canvas.fillScreen(COL_BG);
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextColor(COL_DANGER);
        canvas.setTextDatum(middle_center);
        canvas.drawString("Connect Failed", CANVAS_CENTER, CANVAS_CENTER - 40);
        if (lastErrorMessage.length() > 0)
        {
            canvas.setFont(&fonts::efontJA_12);
            canvas.setTextColor(COL_TEXT_DIM);
            canvas.drawString(lastErrorMessage, CANVAS_CENTER, CANVAS_CENTER - 10);
        }

        int okW = 130, okH = 46, okX = CANVAS_CENTER - okW / 2, okY = CANVAS_CENTER + 30;
        drawPill(okX, okY, okW, okH, COL_ACCENT, "OK", 0x0000);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);

        if (t.wasPressed() && hitRect(t.x, t.y, okX, okY, okW, okH))
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

                    // 音声デバイス(ALSA)。選択肢としては"id"のみ使う(labelは長すぎて丸画面に収まらない)
                    JsonArray audio = doc["audio"].as<JsonArray>();
                    for (JsonObject a : audio)
                    {
                        String id = a["id"].as<String>();
                        if (id.length() > 0)
                            audioDevList.push_back(id);
                    }
                }
            }
            http.end();
        }

        if (rigsOK)
        {
            if (catList.empty())
                catList.push_back("None");
            if (audioDevList.empty())
                audioDevList.push_back("None");

            // ★ rigIds[] が埋まった後に rigId を読み込む！
            prefs.begin("device", true);
            int savedRigId = prefs.getInt("rigId", -1);
            selSampling = prefs.getInt("sampling", 4); // ★ デフォルトは44100Hz(index4)。0だとOff扱いでSPKが有効化できない
            selScreenTimeout = prefs.getInt("disp", 2); // デフォルトは10分（index 2）
            String savedCatDev = prefs.getString("catDev", "");
            String savedCwDev = prefs.getString("cwDev", "");
            String savedAudioDev = prefs.getString("audioDev", "");
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
            // 保存済み CW Device を catList から探して復元（見つからなければ 0）
            selCwDevice = 0;
            for (int i = 0; i < catList.size(); ++i)
            {
                if (catList[i] == savedCwDev)
                {
                    selCwDevice = i;
                    break;
                }
            }
            cwBridgeOpen = false; // 再接続の度にConnectを取り直す
            cwOpenedDevice = "";

            // 保存済み Audio Device を audioDevList から探して復元（見つからなければ 0）
            selAudioDevice = 0;
            for (int i = 0; i < audioDevList.size(); ++i)
            {
                if (audioDevList[i] == savedAudioDev)
                {
                    selAudioDevice = i;
                    break;
                }
            }

            loaded = true;
            Serial.printf("[dbg][rig_connect] reload done: savedRigId=%d rigIds.size=%d selRig=%d name=%s savedCatDev=%s selCat=%d\n",
                          savedRigId, (int)rigIds.size(), selRig,
                          rigIds.empty() ? "?" : rigNames[selRig].c_str(),
                          savedCatDev.c_str(), selCat);
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
    if (x >= RC_ROW1_X && x <= RC_ROW1_X + RC_ROW1_W && y >= RC_ROW1_Y && y <= RC_ROW1_Y + RC_ROW_H)
    {
        appState = STATE_RIG_CONNECT;
        rigConnectFirstDraw = true;
        return;
    }

    // CAT
    if (x >= RC_PAIR_L_X && x <= RC_PAIR_L_X + RC_PAIR_W && y >= RC_ROW2_Y && y <= RC_ROW2_Y + RC_ROW_H)
    {
        selCat = (selCat + 1) % catList.size();
        drawRigConnectScreen();
        return;
    }

    // Timeout（CATの右側）
    if (x >= RC_PAIR_R_X && x <= RC_PAIR_R_X + RC_PAIR_W && y >= RC_ROW2_Y && y <= RC_ROW2_Y + RC_ROW_H)
    {
        selScreenTimeout = (selScreenTimeout + 1) % screenTimeoutOptions.size();
        screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;
        drawRigConnectScreen();
        return;
    }

    // Baud Rate
    if (x >= RC_PAIR_L_X && x <= RC_PAIR_L_X + RC_PAIR_W && y >= RC_ROW3_Y && y <= RC_ROW3_Y + RC_ROW_H)
    {
        selBaud = (selBaud + 1) % baudRates.size();
        drawRigConnectScreen();
        return;
    }

    // Sampling Rate
    if (x >= RC_PAIR_R_X && x <= RC_PAIR_R_X + RC_PAIR_W && y >= RC_ROW3_Y && y <= RC_ROW3_Y + RC_ROW_H)
    {
        selSampling = (selSampling + 1) % samplingRates.size();
        speakerEnabled = (samplingRates[selSampling] != 0);
        drawRigConnectScreen();
        return;
    }

    // CW Device(左): CATと同じ/devicesのシリアル一覧から選ぶ(別デバイス選択用)
    if (x >= RC_PAIR_L_X && x <= RC_PAIR_L_X + RC_PAIR_W && y >= RC_ROW4_Y && y <= RC_ROW4_Y + RC_ROW_H)
    {
        if (!catList.empty())
        {
            selCwDevice = (selCwDevice + 1) % catList.size();
            cwBridgeOpen = false; // デバイスを変えたら再Connectが必要
        }
        drawRigConnectScreen();
        return;
    }

    // CW Bridge Connect(右): ラズパイのcw_bridge.pyを起動(/cw/open)
    if (x >= RC_PAIR_R_X && x <= RC_PAIR_R_X + RC_PAIR_W && y >= RC_ROW4_Y && y <= RC_ROW4_Y + RC_ROW_H)
    {
        String dev = catList.empty() ? "" : catList[selCwDevice];
        if (dev.length() > 0 && dev != "None")
        {
            canvas.fillRoundRect(RC_PAIR_R_X, RC_ROW4_Y, RC_PAIR_W, RC_ROW_H, 10, YELLOW);
            drawCentered("Opening...", RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW4_Y + RC_ROW_H / 2, BLACK);
            canvas.pushSprite(0, 0);

            HTTPClient cwHttp;
            String url = "http://" + HostName + ":" + String(apiPort) + "/cw/open?port=" + dev;
            cwHttp.begin(url);
            if (!apiKey.isEmpty()) cwHttp.addHeader("X-API-Key", apiKey);
            int code = cwHttp.GET();
            cwHttp.end();
            cwBridgeOpen = (code == 200);
            cwOpenedDevice = cwBridgeOpen ? dev : "";
            Serial.printf("[dbg][rig_connect] CW open port=%s code=%d ok=%d\n", dev.c_str(), code, cwBridgeOpen);

            prefs.begin("device", false);
            prefs.putString("cwDev", dev);
            prefs.end();
        }
        drawRigConnectScreen();
        return;
    }

    // Audio Device(全幅): ラズパイ側ALSAデバイス(FTX-1等、音声デバイスを明示指定しないと
    // 音が出ない機種向け)。CW Bridgeと違いこの場で開く処理は不要で、Connect時に
    // /radio/audio_deviceへまとめて送る(catDev/baudと同じ扱い)。
    if (x >= RC_ROW1_X && x <= RC_ROW1_X + RC_ROW1_W && y >= RC_ROW5_Y && y <= RC_ROW5_Y + RC_ROW_H)
    {
        if (!audioDevList.empty())
            selAudioDevice = (selAudioDevice + 1) % audioDevList.size();
        drawRigConnectScreen();
        return;
    }

    // Back
    if (x >= RC_BACK_X && x <= RC_BACK_X + RC_BACK_W && y >= btnY && y <= btnY + btnH)
    {
        Serial.printf("[dbg][rig_connect] BACK pressed: selRig=%d name=%s -> STATE_WIFI\n",
                      selRig, rigNames.empty() ? "?" : rigNames[selRig].c_str());
        appState = STATE_WIFI;
        rigConnectFirstDraw = true;
        return;
    }

    // Connect ボタン
    if (x >= RC_CONNECT_X && x <= RC_CONNECT_X + RC_CONNECT_W && y >= btnY && y <= btnY + btnH)
    {
        {

            if (rigNames.empty() || rigIds.empty())
            {
                appState = STATE_CONNECT_FAILED;
                lastErrorMessage = "Device Not Found";
                rigConnectFirstDraw = true;
                return;
            }

            canvas.fillRoundRect(CANVAS_CENTER - 100, CANVAS_CENTER - 40, 200, 80, 12, BLACK); // 背景クリア
            canvas.drawRoundRect(CANVAS_CENTER - 100, CANVAS_CENTER - 40, 200, 80, 12, BLUE);  // 青い枠
            canvas.setTextDatum(middle_center);
            canvas.setTextColor(WHITE);
            canvas.setFont(&fonts::efontJA_16);
            canvas.drawString("Connecting", CANVAS_CENTER, CANVAS_CENTER); // 中央に表示
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
            String audioDevParam = audioDevList.empty() ? "" : audioDevList[selAudioDevice];
            if (audioDevParam == "None") audioDevParam = "";
            prefs.putString("audioDev", audioDevParam);
            prefs.putBool("useWifiPTT", useWifiPTT);
            prefs.putString("pttHost", pttHost);
            prefs.putInt("pttPort", pttPort);
            prefs.putString("pttDevice", pttDevice);
            prefs.putString("pttType", pttType);
            prefs.end();
            Serial.println("[connect] prefs saved");

            // ★ FTX-1等、ラズパイ側で音声デバイスを明示指定しないと音が出ない機種向け。
            //   Android版のsetAudioDevice()と同じ /radio/audio_device に captur/playback
            //   両方に同じIDを送る(未選択"None"の場合はスキップしてデフォルトのまま)。
            if (audioDevParam.length() > 0)
            {
                HTTPClient audioHttp;
                String audioUrl = "http://" + HostName + ":" + String(apiPort) + "/radio/audio_device";
                audioHttp.begin(audioUrl);
                audioHttp.addHeader("Content-Type", "application/json");
                if (!apiKey.isEmpty()) audioHttp.addHeader("X-API-Key", apiKey);
                JsonDocument audioDoc;
                audioDoc["capture"] = audioDevParam;
                audioDoc["playback"] = audioDevParam;
                String audioBody;
                serializeJson(audioDoc, audioBody);
                int audioCode = audioHttp.POST(audioBody);
                Serial.printf("[connect] /radio/audio_device code=%d dev=%s\n", audioCode, audioDevParam.c_str());
                audioHttp.end();
            }

            String catParam = (catList[selCat] == "None") ? "" : catList[selCat];

            SRate = samplingRates[selSampling];
            speakerEnabled = (SRate != 0);

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
    if (x >= RC_PTT_X && x <= RC_PTT_X + RC_PTT_W && y >= btnY && y <= btnY + btnH)
    {
        // ★ rigConnectFirstDraw = true にすると、PTT画面から戻った直後の
        //   handleRigConnectScreen()先頭のリセット処理(loaded=false化)が発火し、
        //   catList/rigIds を再取得してprefsの保存値で選択を上書きしてしまう。
        //   ここではRig Connect画面自体の状態は変えていないので立てない。
        appState = STATE_RIG_PTT; // ← PTT 画面へ遷移
        return;
    }
}

void drawRigConnectScreen()
{
    canvas.fillScreen(BLACK);

    // ---- タイトル ----
    canvas.setTextSize(1); // ★ 前の画面のスケールを引き継がないよう明示
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_center);
    canvas.drawString("Rig Connect", CANVAS_CENTER, RC_TITLE_Y);

    // ---- Rig model ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("Rig model", CANVAS_CENTER, RC_ROW1_Y - RC_TITLE_OFF);
    canvas.drawRoundRect(RC_ROW1_X, RC_ROW1_Y, RC_ROW1_W, RC_ROW_H, 10, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextSize(1.15); // ★ 文字を大きく見やすく
    drawCentered(rigNames[selRig].c_str(), CANVAS_CENTER, RC_ROW1_Y + RC_ROW_H / 2, WHITE);
    canvas.setTextSize(1);

    // ---- CAT Device / Disp Off ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("CAT Device", RC_PAIR_L_X + RC_PAIR_W / 2, RC_ROW2_Y - RC_TITLE_OFF);
    canvas.drawString("Disp Off", RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW2_Y - RC_TITLE_OFF);

    canvas.drawRoundRect(RC_PAIR_L_X, RC_ROW2_Y, RC_PAIR_W, RC_ROW_H, 10, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextSize(1.15);
    drawCentered(catList[selCat].c_str(), RC_PAIR_L_X + RC_PAIR_W / 2, RC_ROW2_Y + RC_ROW_H / 2, WHITE);

    canvas.drawRoundRect(RC_PAIR_R_X, RC_ROW2_Y, RC_PAIR_W, RC_ROW_H, 10, WHITE);
    String timeoutLabel = (screenTimeoutOptions[selScreenTimeout] == 0) ? "Off" : String(screenTimeoutOptions[selScreenTimeout]) + " min";
    drawCentered(timeoutLabel.c_str(), RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW2_Y + RC_ROW_H / 2, WHITE);
    canvas.setTextSize(1);

    // ---- Baud Rate / Sampling Rate ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("Baud Rate", RC_PAIR_L_X + RC_PAIR_W / 2, RC_ROW3_Y - RC_TITLE_OFF);
    canvas.drawString("Sampling", RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW3_Y - RC_TITLE_OFF);

    canvas.drawRoundRect(RC_PAIR_L_X, RC_ROW3_Y, RC_PAIR_W, RC_ROW_H, 10, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextSize(1.15);
    drawCentered((String(baudRates[selBaud]) + " bps").c_str(), RC_PAIR_L_X + RC_PAIR_W / 2, RC_ROW3_Y + RC_ROW_H / 2, WHITE);

    canvas.drawRoundRect(RC_PAIR_R_X, RC_ROW3_Y, RC_PAIR_W, RC_ROW_H, 10, WHITE);
    // ★ 0Hzは「SPK無効(Off)」の意味だが"0 Hz"表記だと分かりづらいため明示する
    String samplingLabel = (samplingRates[selSampling] == 0) ? "Off" : (String(samplingRates[selSampling]) + " Hz");
    drawCentered(samplingLabel.c_str(), RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW3_Y + RC_ROW_H / 2, WHITE);
    canvas.setTextSize(1);

    // ---- CW Device / CW Connect(リモートキーヤー用USBデバイス、CATとは別) ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("CW Device", RC_PAIR_L_X + RC_PAIR_W / 2, RC_ROW4_Y - RC_TITLE_OFF);
    canvas.drawString("CW Bridge", RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW4_Y - RC_TITLE_OFF);

    canvas.drawRoundRect(RC_PAIR_L_X, RC_ROW4_Y, RC_PAIR_W, RC_ROW_H, 10, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextSize(1.15);
    String cwDevLabel = catList.empty() ? "None" : catList[selCwDevice];
    drawCentered(cwDevLabel.c_str(), RC_PAIR_L_X + RC_PAIR_W / 2, RC_ROW4_Y + RC_ROW_H / 2, WHITE);

    bool cwIsOpen = cwBridgeOpen && !catList.empty() && cwOpenedDevice == catList[selCwDevice];
    canvas.fillRoundRect(RC_PAIR_R_X, RC_ROW4_Y, RC_PAIR_W, RC_ROW_H, 10, cwIsOpen ? GREEN : DARKGREY);
    drawCentered(cwIsOpen ? "Connected" : "Connect", RC_PAIR_R_X + RC_PAIR_W / 2, RC_ROW4_Y + RC_ROW_H / 2, cwIsOpen ? BLACK : WHITE);
    canvas.setTextSize(1);

    // ---- Audio Device(全幅、タップで巡回。FTX-1等の無音対策) ----
    canvas.setFont(&fonts::efontJA_12);
    canvas.drawString("Audio Device", CANVAS_CENTER, RC_ROW5_Y - RC_TITLE_OFF);
    canvas.drawRoundRect(RC_ROW1_X, RC_ROW5_Y, RC_ROW1_W, RC_ROW_H, 10, WHITE);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextSize(1.05); // ★ デバイスIDが長い場合があるため他行よりやや控えめ
    String audioDevLabel = audioDevList.empty() ? "None" : audioDevList[selAudioDevice];
    while (canvas.textWidth(audioDevLabel) > RC_ROW1_W - 20)
        audioDevLabel.remove(audioDevLabel.length() - 1);
    drawCentered(audioDevLabel.c_str(), CANVAS_CENTER, RC_ROW5_Y + RC_ROW_H / 2, WHITE);
    canvas.setTextSize(1);

    int btnY = RC_BTN_Y;
    int btnH = RC_BTN_H;

    canvas.setTextSize(1.15); // ★ ボタン文字も大きく
    // Back ボタン
    canvas.fillRoundRect(RC_BACK_X, btnY, RC_BACK_W, btnH, 10, RED);
    drawCentered("Back", RC_BACK_X + RC_BACK_W / 2, btnY + btnH / 2, WHITE);

    // Connect ボタン
    canvas.fillRoundRect(RC_CONNECT_X, btnY, RC_CONNECT_W, btnH, 10, BLUE);
    drawCentered("Connect", RC_CONNECT_X + RC_CONNECT_W / 2, btnY + btnH / 2, WHITE);

    // PTT ボタン
    canvas.fillRoundRect(RC_PTT_X, btnY, RC_PTT_W, btnH, 10, YELLOW);
    drawCentered("PTT", RC_PTT_X + RC_PTT_W / 2, btnY + btnH / 2, BLACK);
    canvas.setTextSize(1);

    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}
