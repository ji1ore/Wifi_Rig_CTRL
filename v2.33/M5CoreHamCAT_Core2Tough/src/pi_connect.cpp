/****************************************************
 *  Wifi_Rig_CTRL ラズパイ接続画面
 *  Ver2.33
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
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

    canvas.fillRect(30, 80, 260, 80, BLACK);
    canvas.drawRect(30, 80, 260, 80, WHITE);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("Scanning...", 160, 120);
    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);

    WiFiUDP udp;
    udp.begin(0);

    const char *magic = "WIFI_RIG_CTRL_DISCOVER";
    int magicLen = strlen(magic);

    // サブネットブロードキャストと255.255.255.255の両方に送信
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
                String rest = msg.substring(19); // len("WIFI_RIG_CTRL_HERE:") = 19
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

static void drawScanOverlay()
{
    int ox = 10, oy = 30, ow = 300, oh = 200;
    canvas.fillRect(ox, oy, ow, oh, BLACK);
    canvas.drawRect(ox, oy, ow, oh, WHITE);
    canvas.setFont(&fonts::efontJA_16_b);
    canvas.setTextColor(CYAN);
    canvas.setTextDatum(top_left);
    if (scanResults.empty())
    {
        canvas.drawString("No servers found", ox + 10, oy + 10);
        canvas.drawString("(Pi not running or not on", ox + 10, oy + 40);
        canvas.drawString(" same network)", ox + 10, oy + 65);
    }
    else
    {
        canvas.drawString("Found servers:", ox + 10, oy + 8);
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextColor(WHITE);
        int maxShow = min((int)scanResults.size(), 3);
        for (int i = 0; i < maxShow; i++)
        {
            int btnY = oy + 35 + i * 40;
            canvas.fillRoundRect(ox + 8, btnY, ow - 16, 32, 4, canvas.color565(0, 60, 120));
            String label = scanResults[i].ip + " (" + scanResults[i].hostname + ")";
            canvas.setTextDatum(middle_left);
            canvas.drawString(label, ox + 14, btnY + 16);
            canvas.setTextDatum(top_left);
        }
    }
    // Cancel ボタン
    canvas.fillRoundRect(ox + 90, oy + oh - 36, 120, 28, 6, RED);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(WHITE);
    canvas.drawString("Cancel", ox + 150, oy + oh - 22);
    canvas.setTextDatum(top_left);
    canvas.pushSprite(0, 0);
}

/****************************************************
 * ラズパイ / CI-V WiFi直結 設定画面
 ****************************************************/
void drawPiConnectScreen()
{
    canvas.fillScreen(BLACK);
    canvas.setTextColor(CYAN);
    canvas.setTextDatum(top_left);
    canvas.setFont(&fonts::efontJA_16_b);
    canvas.drawString(useCIV ? "CI-V CONNECT" : "RasPi CONNECT", 10, 5);
    canvas.setFont(&fonts::efontJA_16);

    // ---- モード切替(Pi / CI-V)----
    // ★ このボタンだけ小さいフォント/サイズにして、狭い枠内でも文字が収まるようにする
    //   (画面全体のデフォルトはsetTextSize(2)のままにしておく)
    canvas.fillRoundRect(225, 2, 85, 24, 4, useCIV ? ORANGE : DARKGREY);
    {
        auto oldFont = canvas.getFont();
        canvas.setFont(&fonts::efontJA_16);
        canvas.setTextSize(1);
        drawCentered(useCIV ? "CI-V" : "Pi Mode", 267, 14, BLACK);
        canvas.setFont(oldFont);
        canvas.setTextSize(2);
    }

    if (!useCIV)
    {
        // ---- mDNS / IP ----
        canvas.fillRoundRect(10, 30, 135, 28, 6, ORANGE);
        drawCentered(useMDNS ? "Use mDNS" : "Use IP", 77, 44, BLACK);

        // ---- Hostname ----
        drawLabel(useMDNS ? "Hostname" : "IP Address:", 10, 63);
        canvas.drawRect(10, 74, 300, 28, WHITE);
        drawCentered(raspiHost.c_str(), 160, 88, WHITE);

        // ---- API Port / Audio Port ----
        drawLabel("API Port", 10, 107);
        canvas.drawRect(10, 118, 130, 28, WHITE);
        drawCentered(String(apiPort).c_str(), 75, 132, WHITE);

        drawLabel("Audio Port", 160, 107);
        canvas.drawRect(160, 118, 150, 28, WHITE);
        drawCentered(String(audioPort).c_str(), 235, 132, WHITE);

        // ---- API Key ----
        drawLabel("API Key (blank=none)", 10, 151);
        canvas.drawRect(10, 162, 300, 28, WHITE);
        // キーは部分表示（長い場合は短縮）
        String keyDisp = apiKey.isEmpty() ? "(none)" : apiKey.substring(0, min(20, (int)apiKey.length()));
        drawCentered(keyDisp.c_str(), 160, 176, apiKey.isEmpty() ? DARKGREY : WHITE);
    }
    else
    {
        // ★ 通常サイズ(size2)のまま描画し、長すぎる可変長文字列だけを幅に収まるよう切り詰める
        auto fitText = [](String s, int maxW) -> String {
            if (canvas.textWidth(s) <= maxW) return s;
            while (s.length() > 1 && canvas.textWidth(s + "...") > maxW)
                s.remove(s.length() - 1);
            return s + "...";
        };

        // ---- Host(無線機のWiFi IPアドレス)----
        // ★ タイトル文字と被らないよう、この区間全体を少し下へシフトしている
        canvas.fillRoundRect(10, 42, 300, 28, 6, ORANGE);
        drawCentered(fitText(civHost, 280).c_str(), 160, 56, BLACK);

        // ---- Ctrl Port / CIV Port / Addr ----
        drawLabel("Ctrl Port", 10, 75);
        canvas.drawRect(10, 83, 95, 28, WHITE);
        drawCentered(String(civPort1).c_str(), 57, 97, WHITE);

        drawLabel("CIV Port", 112, 75);
        canvas.drawRect(112, 83, 95, 28, WHITE);
        drawCentered(String(civPort2).c_str(), 159, 97, WHITE);

        drawLabel("Addr(hex)", 215, 75);
        canvas.drawRect(215, 83, 95, 28, WHITE);
        char addrBuf[8];
        snprintf(addrBuf, sizeof(addrBuf), "0x%02X", civAddress & 0xFF);
        drawCentered(addrBuf, 262, 97, WHITE);

        // ---- Username / Password ----
        drawLabel("Username", 10, 116);
        canvas.drawRect(10, 124, 140, 28, WHITE);
        String userDisp = civUsername.isEmpty() ? "(none)" : fitText(civUsername, 120);
        drawCentered(userDisp.c_str(), 80, 138, civUsername.isEmpty() ? DARKGREY : WHITE);

        drawLabel("Password", 160, 116);
        canvas.drawRect(160, 124, 150, 28, WHITE);
        String passDisp = civPassword.isEmpty() ? "(none)" : "********";
        drawCentered(passDisp.c_str(), 235, 138, civPassword.isEmpty() ? DARKGREY : WHITE);

        // ---- Screen Timeout / PTT ----
        drawLabel("Timeout", 10, 157);
        canvas.fillRoundRect(10, 165, 140, 28, 6, ORANGE);
        String toDisp = (screenTimeoutOptions[selScreenTimeout] == 0) ? "Off" : String(screenTimeoutOptions[selScreenTimeout]) + " min";
        drawCentered(toDisp.c_str(), 80, 179, BLACK);

        canvas.fillRoundRect(160, 165, 150, 28, 6, BLUE);
        drawCentered("PTT", 235, 179, WHITE);

        canvas.setTextSize(2); // ★ 以降(Connect/Backボタン等)は元のサイズへ復元
    }

    // ---- Back / Scan / Connect ----
    canvas.fillRoundRect(10, 204, 88, 30, 6, RED);
    drawCentered("BACK", 54, 219, WHITE);

    if (!useCIV)
    {
        canvas.fillRoundRect(108, 204, 70, 30, 6, canvas.color565(0, 100, 60));
        drawCentered("Scan", 143, 219, WHITE);
    }

    canvas.fillRoundRect(188, 204, 132, 30, 6, BLUE);
    drawCentered("Connect", 254, 219, WHITE);

    if (showErrorDialog)
    {
        int dx = 20, dy = 40, dw = 280, dh = 160;
        canvas.fillRect(dx, dy, dw, dh, BLACK);
        canvas.drawRect(dx, dy, dw, dh, RED);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("Connection Failed", dx + dw / 2, dy + 40);
        canvas.drawString(lastErrorMessage, dx + dw / 2, dy + 80);

        canvas.fillRoundRect(dx + 100, dy + 110, 80, 30, 6, BLUE);
        canvas.drawString("OK", dx + 140, dy + 125);
        canvas.setTextDatum(top_left);
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

        drawPiConnectScreen();
        firstDraw = false;
    }
    auto t = M5.Touch.getDetail();

    // ---- スキャン結果オーバーレイ ----
    if (scanOverlayActive)
    {
        drawScanOverlay();
        if (t.wasPressed())
        {
            int ox = 10, oy = 30, oh = 200;
            int x = t.x, y = t.y;
            // Cancel
            if (x >= ox + 90 && x <= ox + 210 && y >= oy + oh - 36 && y <= oy + oh - 8)
            {
                scanOverlayActive = false;
                drawPiConnectScreen();
            }
            else if (!scanResults.empty())
            {
                int maxShow = min((int)scanResults.size(), 3);
                for (int i = 0; i < maxShow; i++)
                {
                    int btnY = oy + 35 + i * 40;
                    if (x >= ox + 8 && x <= ox + 292 && y >= btnY && y <= btnY + 32)
                    {
                        // IPをコピーした場合はUse IPモードに切り替える。
                        // API Port / Audio Portはユーザー設定を維持するためコピーしない。
                        raspiHost = scanResults[i].ip;
                        useMDNS = false;
                        { Preferences p; p.begin("piconn", false);
                          p.putString("host", raspiHost);
                          p.putBool("useMDNS", useMDNS);
                          p.end(); }
                        scanOverlayActive = false;
                        drawPiConnectScreen();
                        break;
                    }
                }
            }
        }
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

    if (!t.wasPressed())
        return;

    int x = t.x, y = t.y;

    // ---- モード切替(Pi / CI-V)----
    if (x >= 225 && x <= 310 && y >= 2 && y <= 26)
    {
        useCIV = !useCIV;
        drawPiConnectScreen();
        return;
    }

    if (!useCIV)
    {
        // ---- mDNS / IP ----
        if (x >= 10 && x <= 145 && y >= 30 && y <= 58)
        {
            useMDNS = !useMDNS;
            drawPiConnectScreen();
            return;
        }

        // ---- Hostname ----
        if (x >= 10 && x <= 310 && y >= 74 && y <= 102)
        {
            editingField = FIELD_HOST;
            inputPassword = raspiHost;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- API Port ----
        if (x >= 10 && x <= 140 && y >= 118 && y <= 146)
        {
            editingField = FIELD_API_PORT;
            inputPassword = String(apiPort);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- Audio Port ----
        if (x >= 160 && x <= 310 && y >= 118 && y <= 146)
        {
            editingField = FIELD_AUDIO_PORT;
            inputPassword = String(audioPort);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- API Key ----
        if (x >= 10 && x <= 310 && y >= 162 && y <= 190)
        {
            editingField = FIELD_API_KEY;
            inputPassword = apiKey;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }
    }
    else
    {
        // ---- Host ----
        if (x >= 10 && x <= 310 && y >= 42 && y <= 70)
        {
            editingField = FIELD_CIV_HOST;
            inputPassword = civHost;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- Ctrl Port (Port1) ----
        if (x >= 10 && x <= 105 && y >= 83 && y <= 111)
        {
            editingField = FIELD_CIV_PORT1;
            inputPassword = String(civPort1);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- CIV Port (Port2) ----
        if (x >= 112 && x <= 207 && y >= 83 && y <= 111)
        {
            editingField = FIELD_CIV_PORT2;
            inputPassword = String(civPort2);
            kbMode = KB_NUMPAD;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- CI-V Address (hex) ----
        if (x >= 215 && x <= 310 && y >= 83 && y <= 111)
        {
            editingField = FIELD_CIV_ADDRESS;
            inputPassword = String(civAddress, HEX);
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- Username ----
        if (x >= 10 && x <= 150 && y >= 124 && y <= 152)
        {
            editingField = FIELD_CIV_USERNAME;
            inputPassword = civUsername;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- Password ----
        if (x >= 160 && x <= 310 && y >= 124 && y <= 152)
        {
            editingField = FIELD_CIV_PASSWORD;
            inputPassword = civPassword;
            kbMode = KB_QWERTY;
            appState = STATE_PASSWORD;
            passwordForWifi = false;
            firstDraw = true;
            return;
        }

        // ---- Screen Timeout(タップでサイクル)----
        if (x >= 10 && x <= 150 && y >= 165 && y <= 193)
        {
            selScreenTimeout = (selScreenTimeout + 1) % screenTimeoutOptions.size();
            screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;
            drawPiConnectScreen();
            return;
        }

        // ---- PTT Settings ----
        if (x >= 160 && x <= 310 && y >= 165 && y <= 193)
        {
            appState = STATE_RIG_PTT;
            firstDraw = true;
            return;
        }
    }

    // ---- Scan (Pi モードのみ) ----
    if (!useCIV && x >= 108 && x <= 178 && y >= 204 && y <= 234)
    {
        runPiScan();
        drawScanOverlay();
        return;
    }

    // ---- Connect ----
    if (x >= 188 && x <= 320 && y >= 204 && y <= 234)
    {
        canvas.fillRect(60, 80, 200, 80, BLACK);
        canvas.drawRect(60, 80, 200, 80, BLUE);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("Connecting...", 160, 120);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);

        String result;
        if (useCIV)
        {
            // 接続試行前に設定を保存（接続失敗時も保持されるよう）
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
            // 接続試行前に設定を保存（接続失敗時も保持されるよう）
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
                // ★ ここでI2S_NUM_0を無条件アンインストールしていたのは削除。
                //   connectToCivRadio()が直前にcivInstallSpkI2S()で同じI2S_NUM_0を
                //   インストール済みで、civI2SReadyフラグもtrueにしているため、ここで
                //   壊すとフラグと実体が食い違い、後続のi2s_write()がNULLポインタ
                //   参照でクラッシュする(実機でGuru Meditation Error確認済み)。
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

    if (showErrorDialog)
    {
        int dx = 20, dy = 40, dw = 280, dh = 160;
        canvas.fillRect(dx, dy, dw, dh, BLACK);
        canvas.drawRect(dx, dy, dw, dh, RED);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("Connection Failed", dx + dw / 2, dy + 40);

        canvas.drawString(lastErrorMessage, dx + dw / 2, dy + 80);

        canvas.fillRoundRect(dx + 100, dy + 110, 80, 30, 6, BLUE);
        canvas.drawString("OK", dx + 140, dy + 125);
        canvas.setTextDatum(top_left);
    }

    // ---- Back ----
    if (x >= 10 && x <= 98 && y >= 204 && y <= 234)
    {
        firstDraw = true;
        appState = STATE_WIFI;
        return;
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
    // ★ 初回接続時はARP解決待ちなどでconnect()が失敗することがあるため、
    //   短い間隔でリトライする（lwIP内部でのconnect timeout対策）
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
    if (!doc.containsKey("rigs") || !doc["rigs"].is<JsonArray>())
    {
        return "API not ready";
    }

    JsonArray arr = doc["rigs"].as<JsonArray>();

    if (arr.size() == 0)
    {
        return "No rigs"; // ← ここを追加
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