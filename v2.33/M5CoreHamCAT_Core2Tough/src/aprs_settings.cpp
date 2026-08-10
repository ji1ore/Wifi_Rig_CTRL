/****************************************************
 *  Wifi_Rig_CTRL aprs_settings.cpp
 *  Ver2.33
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include <M5GFX.h>
#include <HTTPClient.h>
#include "globals.h"
#include "ui_core.h"
#include "gps_relay.h"
#include <ArduinoJson.h>

extern M5Canvas canvas;

static int scrollOffset = 0;
static int touchStartY = -1;

const int HEADER_H = 50; // タイトル＋OKボタンの高さ
const int ROW_STEP = 32; // 26px描画 + 6pxスペーシング

// ★ APRS設定画面の行。TX Method(DireWolf/Rig)によって表示する行が変わるため、
//   描画とタッチ判定の両方がこのリストを共通で参照して食い違いを防ぐ。
enum AprsRow
{
    AR_ENABLED,
    AR_TX_METHOD,
    AR_MODEM_SEL,   // Rigモードのみ
    AR_AP96_FREQ,   // Rigモードのみ
    AR_AP96_BAUD,   // Rigモードのみ
    AR_AP12_FREQ,   // Rigモードのみ
    AR_AP12_BAUD,   // Rigモードのみ
    AR_TXFREQ,      // DireWolfモードのみ
    AR_BAUDRATE,    // DireWolfモードのみ
    AR_USE_GPS,
    AR_LAT,
    AR_LON,
    AR_TX_INTERVAL,
    AR_CALLSIGN,
    AR_SSID,
    AR_PATH,
    AR_SYMBOL,
    AR_DESTINATION,
    AR_SOUND_DEVICE, // DireWolfモードのみ
    AR_TASKER_INFO,
    AR_HEARD_SUPPRESS, // 受信ポップアップの同一局抑制時間(TX方式に関わらず共通)
    AR_VIEW_RECEIVED,  // 受信ビーコン一覧へ(TX方式に関わらず共通)
};

static const int AR_MAX_ROWS = 20;

static int buildRowList(AprsRow *rows)
{
    int n = 0;
    rows[n++] = AR_ENABLED;
    rows[n++] = AR_TX_METHOD;

    if (aprsUseRigModem)
    {
        // ★ FTX-1内蔵モデム使用時、コールサイン/シンボル/パス/位置情報は無線機が
        //   自前でパケットを組み立てる際に使うため、無線機側メニューでしか設定できない
        //   (CATコマンドでの設定は不可と確認済み)。M5からはCATで制御できる項目のみ表示する。
        rows[n++] = AR_MODEM_SEL;
        rows[n++] = AR_AP96_FREQ;
        rows[n++] = AR_AP96_BAUD;
        rows[n++] = AR_AP12_FREQ;
        rows[n++] = AR_AP12_BAUD;
        rows[n++] = AR_TX_INTERVAL;
    }
    else
    {
        rows[n++] = AR_TXFREQ;
        rows[n++] = AR_BAUDRATE;
        rows[n++] = AR_USE_GPS;
        rows[n++] = AR_LAT;
        rows[n++] = AR_LON;
        rows[n++] = AR_TX_INTERVAL;
        rows[n++] = AR_CALLSIGN;
        rows[n++] = AR_SSID;
        rows[n++] = AR_PATH;
        rows[n++] = AR_SYMBOL;
        rows[n++] = AR_DESTINATION;
        rows[n++] = AR_SOUND_DEVICE;
        rows[n++] = AR_TASKER_INFO;
    }

    // ★ 受信は方式に関わらずPi側のdirewolfが常時デコードしているため、共通で表示する
    rows[n++] = AR_HEARD_SUPPRESS;
    rows[n++] = AR_VIEW_RECEIVED;

    return n;
}

void fetchSoundDevices()
{
    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/devices";
    http.begin(url);
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
    int code = http.GET();

    if (code == 200)
    {
        String payload = http.getString();
        JsonDocument doc;
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

// APRSシンボル1文字を簡易アイコンで描画(ボックス内、iconCx/iconCyはアイコン中心座標)。
// ★ 受信局一覧(aprs_received.cpp)・メイン画面の受信ポップアップ(main_ctrl.cpp)からも
//   呼ぶためstaticにしない。showCaption=falseだと英語キャプションを省略し、
//   場所を取らない一覧の行内などコンパクトに表示したい場面で使う。
void drawAprsSymbolIcon(const String &symbol, int iconCx, int iconCy, bool showCaption)
{
    uint16_t col = YELLOW;
    const char *caption = "?";

    if (symbol == ">")
    {
        caption = "Car";
        canvas.fillRoundRect(iconCx - 13, iconCy - 4, 26, 9, 3, col);
        canvas.fillRoundRect(iconCx - 7, iconCy - 8, 15, 5, 2, col);
        canvas.fillCircle(iconCx - 7, iconCy + 6, 3, WHITE);
        canvas.fillCircle(iconCx + 7, iconCy + 6, 3, WHITE);
    }
    else if (symbol == "v")
    {
        caption = "Van";
        canvas.fillRoundRect(iconCx - 13, iconCy - 8, 26, 15, 2, col);
        canvas.fillCircle(iconCx - 7, iconCy + 8, 3, WHITE);
        canvas.fillCircle(iconCx + 7, iconCy + 8, 3, WHITE);
    }
    else if (symbol == "<")
    {
        caption = "Moto";
        canvas.fillCircle(iconCx - 8, iconCy + 6, 4, col);
        canvas.fillCircle(iconCx + 8, iconCy + 6, 4, col);
        canvas.drawLine(iconCx - 8, iconCy + 6, iconCx + 2, iconCy - 4, col);
        canvas.drawLine(iconCx + 2, iconCy - 4, iconCx + 8, iconCy + 6, col);
        canvas.drawLine(iconCx + 2, iconCy - 4, iconCx + 2, iconCy - 9, col);
        canvas.drawLine(iconCx - 2, iconCy - 9, iconCx + 6, iconCy - 9, col);
    }
    else if (symbol == "[")
    {
        caption = "Runner";
        canvas.fillCircle(iconCx, iconCy - 7, 3, col);
        canvas.drawLine(iconCx, iconCy - 4, iconCx, iconCy + 3, col);
        canvas.drawLine(iconCx, iconCy - 1, iconCx - 6, iconCy - 5, col);
        canvas.drawLine(iconCx, iconCy - 1, iconCx + 6, iconCy + 2, col);
        canvas.drawLine(iconCx, iconCy + 3, iconCx - 5, iconCy + 9, col);
        canvas.drawLine(iconCx, iconCy + 3, iconCx + 5, iconCy + 9, col);
    }
    else if (symbol == "-")
    {
        caption = "House";
        canvas.fillTriangle(iconCx - 11, iconCy, iconCx, iconCy - 9, iconCx + 11, iconCy, col);
        canvas.fillRect(iconCx - 7, iconCy, 14, 9, col);
    }
    else
    {
        // "/" 等、専用アイコンを持たないその他のシンボルは汎用ピンで表示
        caption = "Pin";
        canvas.fillCircle(iconCx, iconCy - 3, 6, col);
        canvas.fillTriangle(iconCx - 5, iconCy, iconCx + 5, iconCy, iconCx, iconCy + 8, col);
        canvas.fillCircle(iconCx, iconCy - 3, 2, BLACK);
    }

    if (showCaption)
    {
        canvas.setTextDatum(middle_left);
        canvas.setTextColor(WHITE);
        canvas.drawString(caption, iconCx + 20, iconCy);
        canvas.setTextDatum(top_left);
    }
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

    auto drawRow = [&](const char *label, const String &value, bool locked = false) -> int
    {
        // ★ ヘッダーより上に来た行は描画しない
        if (y + rowH < HEADER_H)
        {
            y += ROW_STEP;
            return -1;
        }

        // ★ 画面下に完全に出た行も描画しない
        if (y > 240)
        {
            y += ROW_STEP;
            return -1;
        }

        // --- 通常描画 ---
        int rowY = y;
        canvas.setCursor(labelX, y + 4);
        canvas.print(label);

        canvas.drawRect(boxX, y, boxW, rowH, locked ? DARKGREY : WHITE);
        canvas.setCursor(boxX + 6, y + 4);
        canvas.print(value);

        y += ROW_STEP;
        return rowY;
    };

    AprsRow rows[AR_MAX_ROWS];
    int rowCount = buildRowList(rows);

    for (int i = 0; i < rowCount; i++)
    {
        switch (rows[i])
        {
        case AR_ENABLED:
            drawRow("APRS Enabled", aprsEnabled ? "ON" : "OFF");
            break;

        case AR_TX_METHOD:
            // ★ APRS送信方式: DireWolf(Pi経由の外部音声TNC) か 無線機内蔵APRSモデム(CAT直接制御) か
            //   よく切り替える項目なので上の方に配置する
            drawRow("TX Method", aprsUseRigModem ? "Rig(FTX-1)" : "DireWolf(Pi)");
            break;

        case AR_MODEM_SEL:
        {
            const char *modemSelLabel = (aprsModemSel == 1) ? "AUTO" : (aprsModemSel == 3) ? "SUB" : "MAIN";
            drawRow("Modem Select", modemSelLabel);
            break;
        }

        case AR_AP96_FREQ:
            drawRow("AP96 Freq", String(aprsPreset1Freq, 2));
            break;

        case AR_AP96_BAUD:
            drawRow("AP96 Baud", String(aprsPreset1Baud));
            break;

        case AR_AP12_FREQ:
            drawRow("AP12 Freq", String(aprsPreset2Freq, 2));
            break;

        case AR_AP12_BAUD:
            drawRow("AP12 Baud", String(aprsPreset2Baud));
            break;

        case AR_TXFREQ:
            drawRow("APRS TXFreq", String(aprsTxFreq, 2));
            break;

        case AR_BAUDRATE:
            drawRow("Baudrate", String(aprsBaud));
            break;

        case AR_USE_GPS:
            drawRow("Use GPS", aprsUseGPS ? "ON" : "OFF");
            break;

        case AR_LAT:
            // GPS ON中はTasker由来の値で自動更新されるため手動編集不可(枠をグレーで明示)
            drawRow("Latitude", String(aprsManualLat, 5), aprsUseGPS);
            break;

        case AR_LON:
            drawRow("Longitude", String(aprsManualLon, 5), aprsUseGPS);
            break;

        case AR_TX_INTERVAL:
            drawRow("TX Interval", String(aprsIntervalSec) + " sec");
            break;

        case AR_CALLSIGN:
            drawRow("Callsign", aprsCallsign);
            break;

        case AR_SSID:
            drawRow("SSID", "-" + String(aprsSSID));
            break;

        case AR_PATH:
            canvas.setFont(&fonts::efontJA_10);
            drawRow("Path", aprsPath);
            canvas.setFont(&fonts::efontJA_12);
            break;

        case AR_SYMBOL:
        {
            // ★ Symbolは生の記号テキストの代わりに簡易アイコン+英語キャプションで表示
            int symRowY = drawRow("Symbol", "");
            if (symRowY >= 0)
                drawAprsSymbolIcon(aprsSymbol, boxX + 20, symRowY + rowH / 2, true);
            break;
        }

        case AR_DESTINATION:
            drawRow("Destination", aprsDestination);
            break;

        case AR_SOUND_DEVICE:
            canvas.setFont(&fonts::lgfxJapanGothic_8);
            drawRow("Sound Device", aprsSoundDevice);
            canvas.setFont(&fonts::efontJA_12);
            break;

        case AR_TASKER_INFO:
        {
            // ★ M5がAndroid(Tasker)へ位置情報を取りに行く方式(読み取り専用の案内表示)。
            //   Tasker側にHTTP Server (Port:TASKER_HTTP_PORT, Path:/gps, Body:%loc_lat,%loc_lon)
            //   を設定しておく必要がある。長いので既存行より小さいフォントで表示する。
            canvas.setFont(&fonts::lgfxJapanGothic_8);
            String taskerInfo = "Tasker HTTP Server :" + String(TASKER_HTTP_PORT) + " /gps -> lat,lon";
            drawRow("Tasker Setup", taskerInfo);
            canvas.setFont(&fonts::efontJA_12);
            break;
        }

        case AR_HEARD_SUPPRESS:
        {
            int s = aprsHeardSuppressSec;
            String label = (s < 60) ? (String(s) + "sec") : (String(s / 60) + "min");
            drawRow("Heard Suppress", label);
            break;
        }

        case AR_VIEW_RECEIVED:
            drawRow("Received Beacons", ">");
            break;
        }
    }

    canvas.pushSprite(0, 0);
}

// ==============================
//  タッチ処理
// ==============================
void handleAPRSSettingsScreen()
{
    // 画面入口でのAPRS状態を記録（OK押下時の変化検出用）
    static bool entryEnabled = false;
    static bool entryRigModem = false;

    if (aprsSettingsFirstDraw)
    {
        entryEnabled = aprsEnabled;
        entryRigModem = aprsUseRigModem;
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
            if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);

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
            body += "\"cat_device\":\"" + aprsCatDevice + "\",";
            body += "\"use_rig_modem\":" + String(aprsUseRigModem ? "true" : "false") + ",";
            body += "\"modem_sel\":" + String(aprsModemSel) + ",";
            body += "\"enabled\":" + String(aprsEnabled ? "true" : "false") + ",";
            body += "\"heard_suppress_sec\":" + String(aprsHeardSuppressSec);
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
                prefs.putBool("useRigModem", aprsUseRigModem);
                prefs.putInt("modemSel", aprsModemSel);
                prefs.putFloat("p1freq", aprsPreset1Freq);
                prefs.putInt("p1baud", aprsPreset1Baud);
                prefs.putFloat("p2freq", aprsPreset2Freq);
                prefs.putInt("p2baud", aprsPreset2Baud);
                prefs.putInt("heardSupp", aprsHeardSuppressSec);
                prefs.end();

                // ---- Enabled / TX Method 変更に応じて APRS を停止・起動 ----
                bool nowEnabled = aprsEnabled;
                bool nowRigModem = aprsUseRigModem;
                // Enabled ON→OFF は aprsActive によらず必ず停止
                bool shouldStop = (entryEnabled && !nowEnabled) ||
                                  (aprsActive && entryRigModem != nowRigModem);
                // Enabled OFF→ON、またはTX Method変更後に再起動
                bool shouldStart = (nowEnabled && !entryEnabled) ||
                                   (shouldStop && nowEnabled);

                if (shouldStop)
                {
                    HTTPClient stopHttp;
                    stopHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_stop");
                    if (!apiKey.isEmpty()) stopHttp.addHeader("X-API-Key", apiKey);
                    stopHttp.POST("");
                    stopHttp.end();
                    aprsActive = false;
                    aprsAtPreset2 = false;
                }

                if (shouldStart)
                {
                    // リグモデムは AP96(プリセット1)、DireWolfは通常スタート
                    float startFreq = aprsUseRigModem ? aprsPreset1Freq : aprsTxFreq;
                    int   startBaud = aprsUseRigModem ? aprsPreset1Baud : aprsBaud;

                    // プリセット周波数/ボーレートで再度 /aprs_config を送信
                    HTTPClient cfgHttp;
                    cfgHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_config");
                    cfgHttp.addHeader("Content-Type", "application/json");
                    if (!apiKey.isEmpty()) cfgHttp.addHeader("X-API-Key", apiKey);
                    String startBody = "{";
                    startBody += "\"callsign\":\"" + aprsCallsign + "\",";
                    startBody += "\"ssid\":" + String(aprsSSID) + ",";
                    startBody += "\"path\":\"" + aprsPath + "\",";
                    startBody += "\"interval\":" + String(aprsIntervalSec) + ",";
                    startBody += "\"freq\":" + String(startFreq) + ",";
                    startBody += "\"baud\":" + String(startBaud) + ",";
                    startBody += "\"use_gps\":" + String(aprsUseGPS ? "true" : "false") + ",";
                    startBody += "\"manual_lat\":" + String(aprsManualLat, 6) + ",";
                    startBody += "\"manual_lon\":" + String(aprsManualLon, 6) + ",";
                    startBody += "\"symbol\":\"" + aprsSymbol + "\",";
                    startBody += "\"destination\":\"" + aprsDestination + "\",";
                    startBody += "\"sound_device\":\"" + aprsSoundDevice + "\",";
                    startBody += "\"rig_id\":\"" + aprsRigID + "\",";
                    startBody += "\"cat_device\":\"" + aprsCatDevice + "\",";
                    startBody += "\"use_rig_modem\":" + String(aprsUseRigModem ? "true" : "false") + ",";
                    startBody += "\"modem_sel\":" + String(aprsModemSel) + ",";
                    startBody += "\"enabled\":" + String(aprsEnabled ? "true" : "false") + ",";
                    startBody += "\"heard_suppress_sec\":" + String(aprsHeardSuppressSec);
                    startBody += "}";
                    int cfgCode = cfgHttp.POST(startBody);
                    cfgHttp.end();

                    if (cfgCode == 200)
                    {
                        delay(1500);
                        HTTPClient startHttp;
                        startHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_start");
                        startHttp.addHeader("Content-Type", "application/json");
                        if (!apiKey.isEmpty()) startHttp.addHeader("X-API-Key", apiKey);
                        String startCmd = "{\"freq\":" + String(startFreq) + ",\"interval\":" + String(aprsIntervalSec) + "}";
                        int startCode = startHttp.POST(startCmd);
                        startHttp.end();
                        if (startCode == 200)
                        {
                            aprsActive = true;
                            aprsAtPreset2 = false;
                        }
                    }
                }
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
                if (scrollOffset < -330)
                    scrollOffset = -330;

                drawAprsSettingsUI();
                return;
            }
        }

        // ==============================
        //  設定項目タップ処理
        // ==============================
        int y = (t.y - HEADER_H) - scrollOffset; // ★表示と完全一致

        if (y < 0)
            return;

        AprsRow rows[AR_MAX_ROWS];
        int rowCount = buildRowList(rows);
        int idx = y / ROW_STEP;
        if (idx < 0 || idx >= rowCount)
            return;

        switch (rows[idx])
        {
        case AR_ENABLED:
            aprsEnabled = !aprsEnabled;
            break;

        case AR_TX_METHOD:
            aprsUseRigModem = !aprsUseRigModem;
            break;

        case AR_MODEM_SEL:
            // 1:AUTO -> 2:MAIN -> 3:SUB -> 1:AUTO ...
            aprsModemSel = (aprsModemSel % 3) + 1;
            break;

        case AR_AP96_FREQ:
            editingField = FIELD_APRS_AP96_FREQ;
            inputPassword = String(aprsPreset1Freq, 2);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;

        case AR_AP96_BAUD:
            aprsPreset1Baud = (aprsPreset1Baud == 1200 ? 9600 : 1200);
            break;

        case AR_AP12_FREQ:
            editingField = FIELD_APRS_AP12_FREQ;
            inputPassword = String(aprsPreset2Freq, 2);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;

        case AR_AP12_BAUD:
            aprsPreset2Baud = (aprsPreset2Baud == 1200 ? 9600 : 1200);
            break;

        case AR_TXFREQ:
            editingField = FIELD_APRS_TXFREQ;
            inputPassword = String(aprsTxFreq, 2);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;

        case AR_BAUDRATE:
            aprsBaud = (aprsBaud == 1200 ? 9600 : 1200);
            break;

        case AR_USE_GPS:
            aprsUseGPS = !aprsUseGPS;
            break;

        case AR_LAT:
            // GPS ON中はTaskerから自動更新されるため手動編集させない
            if (aprsUseGPS)
                return;
            editingField = FIELD_APRS_LAT;
            inputPassword = String(aprsManualLat, 5);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;

        case AR_LON:
            if (aprsUseGPS)
                return;
            editingField = FIELD_APRS_LON;
            inputPassword = String(aprsManualLon, 5);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            return;

        case AR_TX_INTERVAL:
        {
            int list[] = {30, 60, 120, 180, 300, 600};
            int count = 6;
            int idx2 = 0;
            for (int i = 0; i < count; i++)
                if (aprsIntervalSec == list[i])
                    idx2 = i;
            idx2 = (idx2 + 1) % count;
            aprsIntervalSec = list[idx2];
            break;
        }

        case AR_CALLSIGN:
            editingField = FIELD_APRS_CALLSIGN;
            inputPassword = aprsCallsign;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            return;

        case AR_SSID:
            aprsSSID = (aprsSSID + 1) % 16;
            break;

        case AR_PATH:
        {
            static const char *pathList[] = {
                "WIDE1-1", "WIDE1-1,WIDE2-1", "WIDE2-1", "DIRECT", "NONE"};
            int count = 5;
            int idx2 = 0;
            for (int i = 0; i < count; i++)
                if (aprsPath == pathList[i])
                    idx2 = i;
            idx2 = (idx2 + 1) % count;
            aprsPath = pathList[idx2];
            break;
        }

        case AR_SYMBOL:
        {
            static const char *symList[] = {">", "<", "v", "[", "/", "-"};
            int count = 6;
            int idx2 = 0;
            for (int i = 0; i < count; i++)
                if (aprsSymbol == symList[i])
                    idx2 = i;
            idx2 = (idx2 + 1) % count;
            aprsSymbol = symList[idx2];
            break;
        }

        case AR_DESTINATION:
        {
            static const char *destList[] = {
                "APDW18", "APDW19", "APDW20", "APYA05", "APMI05", "APRS00"};
            int count = 6;
            int idx2 = 0;
            for (int i = 0; i < count; i++)
                if (aprsDestination == destList[i])
                    idx2 = i;
            idx2 = (idx2 + 1) % count;
            aprsDestination = destList[idx2];
            break;
        }

        case AR_SOUND_DEVICE:
            if (soundDeviceList.size() > 0)
            {
                // 現在の index を探す
                int idx2 = 0;
                for (int i = 0; i < soundDeviceList.size(); i++)
                {
                    if (aprsSoundDevice == soundDeviceList[i])
                    {
                        idx2 = i;
                        break;
                    }
                }

                // 次のデバイスへ
                idx2 = (idx2 + 1) % soundDeviceList.size();
                aprsSoundDevice = soundDeviceList[idx2];
            }
            break;

        case AR_TASKER_INFO:
            // 情報表示のみ、タップ無効
            break;

        case AR_HEARD_SUPPRESS:
        {
            int list[] = {0, 60, 300, 600, 1800, 3600};
            int count = 6;
            int idx2 = 0;
            for (int i = 0; i < count; i++)
                if (aprsHeardSuppressSec == list[i])
                    idx2 = i;
            idx2 = (idx2 + 1) % count;
            aprsHeardSuppressSec = list[idx2];
            break;
        }

        case AR_VIEW_RECEIVED:
            aprsReceivedFirstDraw = true;
            appState = STATE_APRS_RECEIVED;
            return;
        }

        drawAprsSettingsUI();
    }
}
