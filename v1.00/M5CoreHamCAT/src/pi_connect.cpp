/****************************************************
 *  M5CoreHamCAT ラズパイ接続画面
 *  Ver1.00
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
    canvas.drawString("RasPi CONNECT", 10, 10);
    canvas.setFont(&fonts::efontJA_16);

    // ---- mDNS / IP ----
    canvas.fillRoundRect(10, 45, 135, 30, 6, ORANGE);
    drawCentered(useMDNS ? "Use mDNS" : "Use IP", 10 + 135 / 2, 60, BLACK);

    // ---- Hostname ----
    drawLabel(useMDNS ? "Hostname" : "IP Address:", 10, 85);
    canvas.drawRect(10, 95, 280, 30, WHITE);
    drawCentered(raspiHost.c_str(), 150, 110, WHITE);

    // ---- API Port ----
    drawLabel("API Port", 10, 140);
    canvas.drawRect(10, 150, 130, 30, WHITE);
    drawCentered(String(apiPort).c_str(), 75, 165, WHITE);

    // ---- Audio Port ----
    drawLabel("Audio Port", 160, 140);
    canvas.drawRect(160, 150, 130, 30, WHITE);
    drawCentered(String(audioPort).c_str(), 225, 165, WHITE);

    canvas.fillRoundRect(150, 200, 150, 30, 6, BLUE);
    drawCentered("Connect", 150 + 75, 215, WHITE);

    // ---- Back ----
    canvas.fillRoundRect(10, 200, 120, 30, 6, RED);
    drawCentered("BACK", 10 + 60, 215, WHITE);

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
    if (x >= 10 && x <= 145 && y >= 40 && y <= 70)
    {
        useMDNS = !useMDNS;
        drawPiConnectScreen();
        return;
    }

    // ---- Hostname ----
    if (x >= 10 && x <= 290 && y >= 95 && y <= 125)
    {
        editingField = FIELD_HOST;
        inputPassword = raspiHost;
        kbMode = KB_QWERTY;
        appState = STATE_PASSWORD;
        passwordForWifi = false;
        // drawPasswordScreen();
        firstDraw = true;
        return;
    }

    // ---- API Port ----
    if (x >= 10 && x <= 140 && y >= 150 && y <= 180)
    {
        editingField = FIELD_API_PORT;
        inputPassword = String(apiPort);
        kbMode = KB_NUMPAD;
        appState = STATE_PASSWORD;
        passwordForWifi = false;
        // drawPasswordScreen();
        firstDraw = true;
        return;
    }

    // ---- Audio Port ----
    if (x >= 160 && x <= 290 && y >= 150 && y <= 180)
    {
        editingField = FIELD_AUDIO_PORT;
        inputPassword = String(audioPort);
        kbMode = KB_NUMPAD;
        appState = STATE_PASSWORD;
        passwordForWifi = false;
        firstDraw = true;
        return;
    }

    // ---- Connect ----
    if (x >= 150 && x <= 310 && y >= 200 && y <= 230)
    {
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
            if (useMDNS)
            {
               HostName = raspiHost + ".local";
            } else {
               HostName = raspiHost;

            }
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

        canvas.drawString(lastErrorMessage, dx + dw / 2, dy + 80); // ★ 追加

        canvas.fillRoundRect(dx + 100, dy + 110, 80, 30, 6, BLUE);
        canvas.drawString("OK", dx + 140, dy + 125);
        canvas.setTextDatum(top_left);
    }

    // ---- Back ----
    if (x >= 10 && x <= 130 && y >= 200 && y <= 230)
    {
        firstDraw = true;
        appState = STATE_WIFI;
        firstDraw = true;
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
    int code = http.GET();

    if (code != 200)
    {
        http.end();
        return "API not ready";
    }

    String body = http.getString();
    http.end();

    JsonDocument doc;
    if (deserializeJson(doc, body) != DeserializationError::Ok)
    {
        return "JSON parse error";
    }
    rigNames.clear();
    rigIds.clear();
    for (JsonObject r : doc["rigs"].as<JsonArray>())
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

    return "";
}