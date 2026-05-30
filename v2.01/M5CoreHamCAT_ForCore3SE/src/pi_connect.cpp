/****************************************************
 *  Wifi_Rig_CTRL ラズパイ接続画面
 *  Ver2.01
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"
#include <ESPmDNS.h>
#include <WiFiClient.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

/****************************************************
 * ラズパイ設定画面
 ****************************************************/
void drawPiConnectScreen()
{
    canvas.fillScreen(BLACK);
    canvas.setTextColor(CYAN);
    canvas.setTextDatum(top_left);
    canvas.setFont(&fonts::efontJA_16_b);
    canvas.drawString("RasPi CONNECT", 10, 5);
    canvas.setFont(&fonts::efontJA_16);

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

    // ---- Connect / Back ----
    canvas.fillRoundRect(160, 204, 150, 30, 6, BLUE);
    drawCentered("Connect", 235, 219, WHITE);

    canvas.fillRoundRect(10, 204, 140, 30, 6, RED);
    drawCentered("BACK", 80, 219, WHITE);

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
        drawPiConnectScreen();
        firstDraw = false;
    }
    auto t = M5.Touch.getDetail();

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

    // ---- Connect ----
    if (x >= 160 && x <= 310 && y >= 204 && y <= 234)
    {
        // 接続試行前に設定を保存（接続失敗時も保持されるよう）
        {
            Preferences piPrefs;
            piPrefs.begin("piconn", false);
            piPrefs.putString("host", raspiHost);
            piPrefs.putInt("apiPort", apiPort);
            piPrefs.putInt("audioPort", audioPort);
            piPrefs.putBool("useMDNS", useMDNS);
            piPrefs.putString("apiKey", apiKey);
            piPrefs.end();
        }

        canvas.fillRect(60, 80, 200, 80, BLACK);
        canvas.drawRect(60, 80, 200, 80, BLUE);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("Connecting...", 160, 120);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);

        String result = connectToRasPiServices();

        if (result == "")
        {
            firstDraw = true;
            rigSelectFirstDraw = true;
            appState = STATE_RIG_CONNECT;
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

        return;
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
    if (x >= 10 && x <= 150 && y >= 204 && y <= 234)
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

    // ---- mDNS ----
    if (useMDNS)
    {
        if (!MDNS.begin("m5stack"))
        {
            return "mDNS start failed";
        }

        IPAddress ip = MDNS.queryHost(host.c_str());
        if (ip.toString() == "0.0.0.0")
        {
            return "mDNS resolve failed";
        }
        
        host  = ip.toString();
    
    }

    // ---- API ポートに接続できるか ----
    WiFiClient clientApi;
    if (!clientApi.connect(host.c_str(), apiPort))
    {
        return "API connect failed";
    }

    HTTPClient http;
    String url = "http://" + host + ":" + String(apiPort) + "/rigs";
    http.begin(url);
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