/****************************************************
 *  Wifi_Rig_CTRL ラズパイ/CI-V接続画面 (M5StopWatch 円形466x466向け)
 *  多数のフィールドを持つため、Wi-Fi画面と同じ「ピル形状の行リスト
 *  (スクロール可・円弧スクロールバー)」パターンで再設計。
 *  行タップ = フィールド編集(パスワード画面へ)/トグル/画面遷移。
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "globals.h"
#include "civ_client.h"
#include <ESPmDNS.h>
#include <WiFiClient.h>
#include <WiFiUDP.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

// ---- ネットワークスキャン ----
struct DiscoveredPi { String ip; String hostname; int apiPort; int audioPort; };
static std::vector<DiscoveredPi> scanResults;
static bool scanOverlayActive = false;

static void runPiScan()
{
    scanResults.clear();

    canvas.fillScreen(COL_BG);
    drawCentered("Scanning...", CANVAS_CENTER, CANVAS_CENTER, COL_TEXT);
    canvas.pushSprite(0, 0);

    WiFiUDP udp;
    udp.begin(0);

    const char *magic = "WIFI_RIG_CTRL_DISCOVER";
    int magicLen = strlen(magic);

    IPAddress limitedBcast(255, 255, 255, 255);
    udp.beginPacket(limitedBcast, 5001);
    udp.write((const uint8_t *)magic, magicLen);
    udp.endPacket();

    IPAddress local = WiFi.localIP();
    IPAddress sub = WiFi.subnetMask();
    IPAddress directed;
    for (int i = 0; i < 4; i++) directed[i] = local[i] | (~sub[i] & 0xFF);
    if (directed != limitedBcast)
    {
        udp.beginPacket(directed, 5001);
        udp.write((const uint8_t *)magic, magicLen);
        udp.endPacket();
    }

    unsigned long deadline = millis() + 2500;
    uint8_t buf[256];
    while (millis() < deadline)
    {
        int size = udp.parsePacket();
        if (size > 0 && size < (int)sizeof(buf))
        {
            int n = udp.read(buf, sizeof(buf) - 1);
            buf[n] = 0;
            String msg = String((char *)buf);
            if (msg.startsWith("WIFI_RIG_CTRL_HERE:"))
            {
                String rest = msg.substring(19);
                int c1 = rest.indexOf(':');
                int c2 = (c1 >= 0) ? rest.indexOf(':', c1 + 1) : -1;
                String hostname = (c1 >= 0) ? rest.substring(0, c1) : rest;
                int aPort = (c1 >= 0 && c2 > c1) ? rest.substring(c1 + 1, c2).toInt() : 8210;
                int audPort = (c2 >= 0) ? rest.substring(c2 + 1).toInt() : 8211;
                if (aPort == 0) aPort = 8210;
                if (audPort == 0) audPort = 8211;
                String ip = udp.remoteIP().toString();
                bool dup = false;
                for (auto &r : scanResults) if (r.ip == ip) { dup = true; break; }
                if (!dup) scanResults.push_back({ip, hostname, aPort, audPort});
            }
        }
        delay(10);
    }
    udp.stop();
    scanOverlayActive = true;
}

static const int SCAN_ROW_H = 46, SCAN_ROW_GAP = 8;

static void drawScanOverlay()
{
    canvas.fillScreen(COL_BG);
    ui_drawTitle("Servers");

    int y = 90;
    if (scanResults.empty())
    {
        drawCentered("No servers found", CANVAS_CENTER, 190, COL_TEXT_DIM);
        drawCentered("(Pi not on same network)", CANVAS_CENTER, 220, COL_TEXT_DIM);
    }
    else
    {
        int maxShow = min((int)scanResults.size(), 3);
        for (int i = 0; i < maxShow; i++)
        {
            String label = scanResults[i].ip + " (" + scanResults[i].hostname + ")";
            drawPill(CANVAS_CENTER - 160, y, 320, SCAN_ROW_H, COL_SURFACE, label.c_str(), COL_TEXT);
            y += SCAN_ROW_H + SCAN_ROW_GAP;
        }
    }

    drawPill(CANVAS_CENTER - 70, 340, 140, 44, COL_DANGER, "Cancel", 0x0000);
    canvas.pushSprite(0, 0);
}

static bool handleScanOverlayTouch(int x, int y)
{
    if (hitRect(x, y, CANVAS_CENTER - 70, 340, 140, 44))
    {
        scanOverlayActive = false;
        return true;
    }
    if (!scanResults.empty())
    {
        int ry = 90;
        int maxShow = min((int)scanResults.size(), 3);
        for (int i = 0; i < maxShow; i++)
        {
            if (hitRect(x, y, CANVAS_CENTER - 160, ry, 320, SCAN_ROW_H))
            {
                raspiHost = scanResults[i].ip;
                useMDNS = false;
                Preferences p; p.begin("piconn", false);
                p.putString("host", raspiHost);
                p.putBool("useMDNS", useMDNS);
                p.end();
                scanOverlayActive = false;
                return true;
            }
            ry += SCAN_ROW_H + SCAN_ROW_GAP;
        }
    }
    return false;
}

// ============================================================
//  行リスト(Wi-Fi画面と同じピル形状+スクロール+円弧スクロールバー)
// ============================================================
enum RowId
{
    ROW_MDNS_TOGGLE, ROW_HOSTNAME, ROW_API_PORT, ROW_AUDIO_PORT, ROW_API_KEY,
    ROW_CIV_HOST, ROW_CIV_PORT1, ROW_CIV_PORT2, ROW_CIV_ADDR, ROW_CIV_USER,
    ROW_CIV_PASS, ROW_TIMEOUT, ROW_PTT_LINK
};

static const int PI_LIST_Y = 84, PI_LIST_H = 256;
static const int ROW_H = 44, ROW_GAP = 8, ROW_PITCH = ROW_H + ROW_GAP;
static const int PILL_W = 320, PILL_X = CANVAS_CENTER - PILL_W / 2;
static const int SCROLL_R = 222;
static int piScrollOffset = 0;

static int buildRowList(RowId *out)
{
    if (!useCIV)
    {
        out[0] = ROW_MDNS_TOGGLE; out[1] = ROW_HOSTNAME; out[2] = ROW_API_PORT;
        out[3] = ROW_AUDIO_PORT; out[4] = ROW_API_KEY;
        return 5;
    }
    out[0] = ROW_CIV_HOST; out[1] = ROW_CIV_PORT1; out[2] = ROW_CIV_PORT2;
    out[3] = ROW_CIV_ADDR; out[4] = ROW_CIV_USER; out[5] = ROW_CIV_PASS;
    out[6] = ROW_TIMEOUT; out[7] = ROW_PTT_LINK;
    return 8;
}

static void rowLabelValue(RowId id, String &label, String &value)
{
    switch (id)
    {
    case ROW_MDNS_TOGGLE: label = "Lookup"; value = useMDNS ? "mDNS" : "IP"; break;
    case ROW_HOSTNAME: label = useMDNS ? "Hostname" : "IP Address"; value = raspiHost; break;
    case ROW_API_PORT: label = "API Port"; value = String(apiPort); break;
    case ROW_AUDIO_PORT: label = "Audio Port"; value = String(audioPort); break;
    case ROW_API_KEY: label = "API Key"; value = apiKey.isEmpty() ? "(none)" : "****"; break;
    case ROW_CIV_HOST: label = "Rig Host"; value = civHost; break;
    case ROW_CIV_PORT1: label = "Ctrl Port"; value = String(civPort1); break;
    case ROW_CIV_PORT2: label = "CIV Port"; value = String(civPort2); break;
    case ROW_CIV_ADDR: { label = "Addr(hex)"; char b[8]; snprintf(b, sizeof(b), "0x%02X", civAddress & 0xFF); value = b; break; }
    case ROW_CIV_USER: label = "Username"; value = civUsername.isEmpty() ? "(none)" : civUsername; break;
    case ROW_CIV_PASS: label = "Password"; value = civPassword.isEmpty() ? "(none)" : "****"; break;
    case ROW_TIMEOUT: label = "Timeout"; value = (screenTimeoutOptions[selScreenTimeout] == 0) ? "Off" : String(screenTimeoutOptions[selScreenTimeout]) + " min"; break;
    case ROW_PTT_LINK: label = "PTT"; value = "Settings >"; break;
    }
}

static void drawRowList()
{
    RowId rows[8];
    int n = buildRowList(rows);

    canvas.setFont(&fonts::efontJA_14);
    for (int i = 0; i < n; i++)
    {
        int y = PI_LIST_Y + i * ROW_PITCH - piScrollOffset;
        if (y < PI_LIST_Y - ROW_PITCH || y > PI_LIST_Y + PI_LIST_H)
            continue;

        String label, value;
        rowLabelValue(rows[i], label, value);

        canvas.fillSmoothRoundRect(PILL_X, y, PILL_W, ROW_H, ROW_H / 2, COL_SURFACE);
        canvas.setTextDatum(middle_left);
        canvas.setTextColor(COL_TEXT_DIM);
        canvas.setFont(&fonts::efontJA_14);
        canvas.drawString(label, PILL_X + 22, y + ROW_H / 2);
        canvas.setTextDatum(middle_right);
        canvas.setTextColor(COL_ACCENT);
        canvas.setFont(&fonts::efontJA_16_b);
        String v = value;
        if (v.length() > 14) v = v.substring(0, 13) + "..";
        canvas.drawString(v, PILL_X + PILL_W - 22, y + ROW_H / 2);
        canvas.setTextDatum(top_left);
    }

    int totalHeight = n * ROW_PITCH;
    if (totalHeight > PI_LIST_H)
    {
        const float trackStart = -55, trackEnd = 55;
        float ratio = (float)PI_LIST_H / totalHeight;
        float thumbSpan = (trackEnd - trackStart) * ratio;
        float thumbStart = trackStart + (trackEnd - trackStart) * ((float)piScrollOffset / totalHeight);
        drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, SCROLL_R, trackStart, trackEnd, COL_SURFACE, 4);
        drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, SCROLL_R, thumbStart, thumbStart + thumbSpan, COL_ACCENT, 4);
    }
}

static int detectTouchedRow(int x, int y, RowId *rows, int n)
{
    if (y < PI_LIST_Y || y > PI_LIST_Y + PI_LIST_H)
        return -1;
    for (int i = 0; i < n; i++)
    {
        int y1 = PI_LIST_Y + i * ROW_PITCH - piScrollOffset;
        if (y >= y1 && y <= y1 + ROW_H)
            return i;
    }
    return -1;
}

/****************************************************
 * ラズパイ / CI-V WiFi直結 設定画面
 ****************************************************/
static const int MODE_PILL_W = 190, MODE_PILL_H = 32, MODE_PILL_Y = 22;
static const int BACK_W = 90, SCAN_W = 90, CONNECT_W = 130, ACTION_H = 44, ACTION_Y = 376;

void drawPiConnectScreen()
{
    canvas.fillScreen(COL_BG);
    canvas.setFont(&fonts::efontJA_16); // ★ 前の画面のフォントを引き継がないよう明示

    drawPill(CANVAS_CENTER - MODE_PILL_W / 2, MODE_PILL_Y, MODE_PILL_W, MODE_PILL_H,
             useCIV ? COL_WARN : COL_SURFACE, useCIV ? "CI-V Mode" : "RasPi Mode",
             useCIV ? (uint16_t)0x0000 : COL_TEXT);

    drawRowList();

    // ---- Back / Scan / Connect ----
    if (!useCIV)
    {
        int totalW = BACK_W + SCAN_W + CONNECT_W + 20;
        int x0 = CANVAS_CENTER - totalW / 2;
        drawPill(x0, ACTION_Y, BACK_W, ACTION_H, COL_DANGER, "Back", 0x0000);
        drawPill(x0 + BACK_W + 10, ACTION_Y, SCAN_W, ACTION_H, COL_SUCCESS, "Scan", 0x0000);
        drawPill(x0 + BACK_W + SCAN_W + 20, ACTION_Y, CONNECT_W, ACTION_H, COL_ACCENT, "Connect", 0x0000);
    }
    else
    {
        int backW = 140, connW = 170, totalW = backW + connW + 10;
        int x0 = CANVAS_CENTER - totalW / 2;
        drawPill(x0, ACTION_Y, backW, ACTION_H, COL_DANGER, "Back", 0x0000);
        drawPill(x0 + backW + 10, ACTION_Y, connW, ACTION_H, COL_ACCENT, "Connect", 0x0000);
    }

    if (showErrorDialog)
    {
        int dw = 290, dh = 220;
        int dx = CANVAS_CENTER - dw / 2, dy = CANVAS_CENTER - dh / 2;
        canvas.fillSmoothRoundRect(dx, dy, dw, dh, 28, COL_SURFACE);
        canvas.drawRoundRect(dx, dy, dw, dh, 28, COL_DANGER);
        drawCircleButton(CANVAS_CENTER, dy + 46, 24, COL_DANGER, "!", 0x0000);
        canvas.setFont(&fonts::efontJA_16_b);
        drawCentered("Connection Failed", CANVAS_CENTER, dy + 90, COL_TEXT);
        canvas.setFont(&fonts::efontJA_14);
        drawCentered(lastErrorMessage.c_str(), CANVAS_CENTER, dy + 120, COL_TEXT_DIM);
        canvas.setFont(&fonts::efontJA_16);
        drawCircleButton(CANVAS_CENTER, dy + dh - 42, 30, COL_ACCENT, "OK", 0x0000);
    }

    canvas.pushSprite(0, 0);
}

void handlePiConnectScreen()
{
    if (appState != STATE_PI_CONNECT)
        return;

    static bool firstDraw = true;
    if (firstDraw)
    {
        // ★ CI-VはPi専用のDEVICE_SELECT画面を経由しないため、ここで画面OFF時間を復元する
        Preferences dPrefs;
        dPrefs.begin("device", true);
        selScreenTimeout = dPrefs.getInt("disp", 2);
        dPrefs.end();
        screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;

        piScrollOffset = 0;
        drawPiConnectScreen();
        firstDraw = false;
    }
    auto t = M5.Touch.getDetail();

    if (scanOverlayActive)
    {
        drawScanOverlay();
        if (t.wasPressed() && handleScanOverlayTouch(t.x, t.y))
            drawPiConnectScreen();
        return;
    }

    if (showErrorDialog)
    {
        if (t.wasPressed())
        {
            showErrorDialog = false;
            drawPiConnectScreen();
        }
        return;
    }

    RowId rows[8];
    int n = buildRowList(rows);

    // ---- リストのドラッグスクロール ----
    static int lastY = -1;
    if (t.isPressed())
    {
        if (lastY >= 0)
        {
            int dy = lastY - t.y;
            piScrollOffset += dy;
            int maxScroll = std::max(0, n * ROW_PITCH - PI_LIST_H);
            piScrollOffset = std::max(0, std::min(piScrollOffset, maxScroll));
            drawPiConnectScreen();
            return;
        }
        lastY = t.y;
    }
    else
    {
        lastY = -1;
    }

    if (!t.wasPressed())
        return;

    int x = t.x, y = t.y;

    // ---- モード切替(Pi / CI-V)----
    if (hitRect(x, y, CANVAS_CENTER - MODE_PILL_W / 2, MODE_PILL_Y, MODE_PILL_W, MODE_PILL_H))
    {
        useCIV = !useCIV;
        piScrollOffset = 0;
        drawPiConnectScreen();
        return;
    }

    // ---- 行タップ ----
    int idx = detectTouchedRow(x, y, rows, n);
    if (idx >= 0)
    {
        RowId id = rows[idx];
        switch (id)
        {
        case ROW_MDNS_TOGGLE:
            useMDNS = !useMDNS;
            drawPiConnectScreen();
            return;

        case ROW_TIMEOUT:
            selScreenTimeout = (selScreenTimeout + 1) % screenTimeoutOptions.size();
            screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;
            drawPiConnectScreen();
            return;

        case ROW_PTT_LINK:
            appState = STATE_RIG_PTT;
            firstDraw = true;
            return;

        case ROW_HOSTNAME:
            editingField = FIELD_HOST; inputPassword = raspiHost; kbMode = KB_QWERTY;
            break;
        case ROW_API_PORT:
            editingField = FIELD_API_PORT; inputPassword = String(apiPort); kbMode = KB_NUMPAD;
            break;
        case ROW_AUDIO_PORT:
            editingField = FIELD_AUDIO_PORT; inputPassword = String(audioPort); kbMode = KB_NUMPAD;
            break;
        case ROW_API_KEY:
            editingField = FIELD_API_KEY; inputPassword = apiKey; kbMode = KB_QWERTY;
            break;
        case ROW_CIV_HOST:
            editingField = FIELD_CIV_HOST; inputPassword = civHost; kbMode = KB_QWERTY;
            break;
        case ROW_CIV_PORT1:
            editingField = FIELD_CIV_PORT1; inputPassword = String(civPort1); kbMode = KB_NUMPAD;
            break;
        case ROW_CIV_PORT2:
            editingField = FIELD_CIV_PORT2; inputPassword = String(civPort2); kbMode = KB_NUMPAD;
            break;
        case ROW_CIV_ADDR:
            editingField = FIELD_CIV_ADDRESS; inputPassword = String(civAddress, HEX); kbMode = KB_QWERTY;
            break;
        case ROW_CIV_USER:
            editingField = FIELD_CIV_USERNAME; inputPassword = civUsername; kbMode = KB_QWERTY;
            break;
        case ROW_CIV_PASS:
            editingField = FIELD_CIV_PASSWORD; inputPassword = civPassword; kbMode = KB_QWERTY;
            break;
        default:
            return;
        }
        appState = STATE_PASSWORD;
        passwordForWifi = false;
        passwordFirstDraw = true;
        firstDraw = true;
        return;
    }

    // ---- Back / Scan / Connect ----
    bool hitBack, hitScan = false, hitConnect;
    if (!useCIV)
    {
        int totalW = BACK_W + SCAN_W + CONNECT_W + 20;
        int x0 = CANVAS_CENTER - totalW / 2;
        hitBack = hitRect(x, y, x0, ACTION_Y, BACK_W, ACTION_H);
        hitScan = hitRect(x, y, x0 + BACK_W + 10, ACTION_Y, SCAN_W, ACTION_H);
        hitConnect = hitRect(x, y, x0 + BACK_W + SCAN_W + 20, ACTION_Y, CONNECT_W, ACTION_H);
    }
    else
    {
        int backW = 140, connW = 170, totalW = backW + connW + 10;
        int x0 = CANVAS_CENTER - totalW / 2;
        hitBack = hitRect(x, y, x0, ACTION_Y, backW, ACTION_H);
        hitConnect = hitRect(x, y, x0 + backW + 10, ACTION_Y, connW, ACTION_H);
    }

    if (hitBack)
    {
        firstDraw = true;
        appState = STATE_WIFI;
        return;
    }

    if (hitScan)
    {
        runPiScan();
        drawScanOverlay();
        return;
    }

    if (hitConnect)
    {
        canvas.fillScreen(COL_BG);
        drawCentered("Connecting...", CANVAS_CENTER, CANVAS_CENTER, COL_TEXT);
        canvas.pushSprite(0, 0);

        String result;
        if (useCIV)
        {
            Preferences civPrefs;
            civPrefs.begin("civconn", false);
            civPrefs.putBool("useCIV", useCIV);
            civPrefs.putString("host", civHost);
            civPrefs.putInt("port1", civPort1);
            civPrefs.putInt("port2", civPort2);
            civPrefs.putString("user", civUsername);
            civPrefs.putString("pass", civPassword);
            civPrefs.putInt("civaddr", civAddress);
            civPrefs.end();

            Preferences dPrefs;
            dPrefs.begin("device", false);
            dPrefs.putInt("disp", selScreenTimeout);
            dPrefs.end();

            result = connectToCivRadio();
        }
        else
        {
            Preferences piPrefs;
            piPrefs.begin("piconn", false);
            piPrefs.putString("host", raspiHost);
            piPrefs.putInt("apiPort", apiPort);
            piPrefs.putInt("audioPort", audioPort);
            piPrefs.putBool("useMDNS", useMDNS);
            piPrefs.putString("apiKey", apiKey);
            piPrefs.end();

            // CI-V接続を一度でも使うとuseCIV=trueが永続化され次回起動時もCI-V画面に
            // なってしまうため、RasPiコネクト選択時は明示的にfalseを保存する
            Preferences civPrefs;
            civPrefs.begin("civconn", false);
            civPrefs.putBool("useCIV", false);
            civPrefs.end();

            result = connectToRasPiServices();
        }

        if (result == "")
        {
            firstDraw = true;

            if (useCIV)
            {
                // CI-Vはリグ固定(IC-705)のため、Pi専用のリグ選択/CATデバイス選択画面を
                // 経由せずメインUIへ直接遷移する。音声(SPK/Mic)もPiを経由せず無線機との
                // UDP直結(civ_client.cppのRS-BA1互換音声プロトコル)で行うため、Pi接続は
                // 一切不要。保存済みのサンプリングレート設定のみ復元する。
                Preferences dPrefs;
                dPrefs.begin("device", true);
                selSampling = dPrefs.getInt("sampling", 0);
                dPrefs.end();
                SRate = samplingRates[selSampling];
                speakerEnabled = (SRate != 0);

                appState = STATE_MAIN_UI;
                mainFirstDraw = true;
                lastUserFreqChange = millis();
                lastUserModeChange = millis();
                lastUserWidthChange = millis();
                lastUserSQLChange = millis();
                lastUserPowerChange = millis();
                lastUserVolumeChange = millis();
            }
            else
            {
                rigSelectFirstDraw = true;
                appState = STATE_RIG_CONNECT;
            }
            return;
        }
        else
        {
            showErrorDialog = true;
            lastErrorMessage = result;
            appState = STATE_PI_CONNECT;
            firstDraw = true;
            return;
        }
    }
}

// 成功 → ""（空文字）
// 失敗 → "API" / "Hamlib" / "AudioRX" / "AudioTX" / "mDNS"
String connectToRasPiServices()
{
    String host = raspiHost;

    // TEMP DEBUG
    Serial.printf("[PIDBG] WiFi.status=%d SSID=%s localIP=%s useMDNS=%d raspiHost=%s apiPort=%d\n",
        WiFi.status(), WiFi.SSID().c_str(), WiFi.localIP().toString().c_str(),
        useMDNS, raspiHost.c_str(), apiPort);

    // ---- mDNS ----
    if (useMDNS)
    {
        if (!MDNS.begin("m5stack"))
        {
            return "mDNS start failed";
        }

        IPAddress ip = MDNS.queryHost(host.c_str());
        Serial.printf("[PIDBG] mDNS queryHost(%s) -> %s\n", host.c_str(), ip.toString().c_str());
        if (ip.toString() == "0.0.0.0")
        {
            return "mDNS resolve failed";
        }

        host  = ip.toString();

    }

    // ---- API ポートに接続できるか ----
    bool apiOk = false;
    for (int attempt = 0; attempt < 4 && !apiOk; ++attempt)
    {
        WiFiClient clientApi;
        Serial.printf("[PIDBG] clientApi.connect(%s, %d) attempt=%d ...\n", host.c_str(), apiPort, attempt + 1);
        apiOk = clientApi.connect(host.c_str(), apiPort);
        Serial.printf("[PIDBG] clientApi.connect result = %d\n", apiOk);
        if (apiOk)
        {
            clientApi.stop();
            break;
        }
        delay(300);
    }
    if (!apiOk)
    {
        return "API connect failed";
    }

    HTTPClient http;
    String url = "http://" + host + ":" + String(apiPort) + "/rigs";
    http.begin(url);
    http.setConnectTimeout(4000);
    http.setTimeout(4000);
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
    int code = http.GET();

    if (code != 200)
    {
        http.end();
        return "API not ready";
    }

    HostName = host;
    String body = http.getString();
    http.end();

    JsonDocument doc;
    if (deserializeJson(doc, body) != DeserializationError::Ok)
    {
        return "JSON parse error";
    }
    rigNames.clear();
    if (!doc["rigs"].is<JsonArray>())
    {
        return "API not ready";
    }

    JsonArray arr = doc["rigs"].as<JsonArray>();

    if (arr.size() == 0)
    {
        return "No rigs";
    }

    rigIds.clear();
    rigNames.clear();

    for (JsonObject r : arr)
    {
        rigIds.push_back(r["id"].as<int>());
        rigNames.push_back(r["name"].as<String>());
    }

    // ★ rigId を読み込んで selRig を復元！
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
    Serial.printf("[dbg][pi_connect] reload: savedRigId=%d rigIds.size=%d selRig=%d name=%s\n",
                  savedRigId, (int)rigIds.size(), selRig,
                  rigNames.empty() ? "?" : rigNames[selRig].c_str());

    // 飛ばしモード用にPi接続設定を保存
    Preferences piPrefs;
    piPrefs.begin("piconn", false);
    piPrefs.putString("host", raspiHost);
    piPrefs.putInt("apiPort", apiPort);
    piPrefs.putInt("audioPort", audioPort);
    piPrefs.putBool("useMDNS", useMDNS);
    piPrefs.putString("apiKey", apiKey);
    piPrefs.end();

    return "";
}
