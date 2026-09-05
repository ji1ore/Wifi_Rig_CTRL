/****************************************************
 *  Wifi_Rig_CTRL gps_relay.cpp
 *  WiFiテザリング元のAndroid(Tasker)へM5から定期的に位置情報を取りに行き、
 *  Raspberry Pi(FastAPIバックエンド)の /gps へ中継する。
 *
 *  AndroidがWiFiテザリングの親機の場合、M5からのWiFi.gatewayIP()は
 *  自動的にAndroid自身のIPになるため、mDNS等の名前解決は不要。
 *
 *  Tasker側は「HTTP Server」機能で以下のように設定しておく:
 *      Event : HTTP Request Received  (Port: TASKER_HTTP_PORT, Path: /gps)
 *      Action: HTTP Response Send     Body: %loc_lat,%loc_lon
 *  （事前に Get Location 等で %loc_lat/%loc_lon を新鮮に保っておくこと）
 *
 *  by JI1ORE
 ****************************************************/
#include "gps_relay.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include "globals.h"

static const unsigned long GPS_POLL_INTERVAL_MS = 30000; // 30秒毎にポーリング
static volatile unsigned long lastGpsFixMs = 0; // 0 = 未取得
static bool gpsPollStarted = false;

// Pi側 /gps は Pydantic モデル(JSONボディ)を要求するため、
// フォームエンコード前提の既存httpQueue/httpSenderTaskは使わず、専用に送信する。
static void sendGpsToPi(float lat, float lon)
{
    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/gps";
    http.begin(url);
    http.setConnectTimeout(3000);
    http.setTimeout(3000);
    http.addHeader("Content-Type", "application/json");
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);

    char body[64];
    snprintf(body, sizeof(body), "{\"lat\":%.6f,\"lon\":%.6f}", lat, lon);
    int code = http.POST(body);
    Serial.printf("[gpsPoll] -> Pi /gps lat=%.6f lon=%.6f code=%d\n", lat, lon, code);
    http.end();
}

// AndroidのTasker HTTPサーバーへGETし、"lat,lon" 形式のレスポンスをパースする
static bool pollAndroidGps(float &lat, float &lon)
{
    IPAddress gw = WiFi.gatewayIP();
    if (gw == IPAddress(0, 0, 0, 0))
    {
        Serial.println("[gpsPoll] gateway IP not available yet");
        return false;
    }

    HTTPClient http;
    String url = "http://" + gw.toString() + ":" + String(TASKER_HTTP_PORT) + "/gps";
    http.setConnectTimeout(2000);
    http.setTimeout(2000);
    http.begin(url);
    int code = http.GET();
    if (code != 200)
    {
        Serial.printf("[gpsPoll] Android GET failed code=%d url=%s\n", code, url.c_str());
        http.end();
        return false;
    }

    String body = http.getString();
    http.end();

    int commaIdx = body.indexOf(',');
    if (commaIdx <= 0)
    {
        Serial.printf("[gpsPoll] unexpected response body: %s\n", body.c_str());
        return false;
    }

    lat = body.substring(0, commaIdx).toFloat();
    lon = body.substring(commaIdx + 1).toFloat();
    return true;
}

static void gpsPollTask(void *param)
{
    while (true)
    {
        // ★ startGpsPolling()はgpsPollStarted済みなら再起動しないラッチのため、
        //   WiFi接続直後・useCIVがまだtrueに切り替わる前の一瞬にこのタスクが
        //   起動してしまうと、その後CI-Vモードに切り替わってもタスク自体は
        //   止まらずHTTP GET(WiFiClient)を撃ち続けてしまう。CI-Vモード中は
        //   このブロッキングTCP通信がlwipの内部リソースを消費し、CI-VのUDP
        //   送信(endPacket)がENOMEMで失敗する一因になっていたため、ループの
        //   各周回でuseCIVを見て実際の通信はスキップする。
        if (!useCIV)
        {
            float lat, lon;
            if (pollAndroidGps(lat, lon))
            {
                lastGpsFixMs = millis();

                // GPS ONの間は、APRS Enabled/稼働状態に関わらずAndroidの位置情報で
                // M5側の位置情報（aprsManualLat/Lon）を常に更新しておく
                if (aprsUseGPS)
                {
                    aprsManualLat = lat;
                    aprsManualLon = lon;
                }

                Serial.printf("[gpsPoll] got from Android: lat=%.6f lon=%.6f\n", lat, lon);
                sendGpsToPi(lat, lon);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(GPS_POLL_INTERVAL_MS));
    }
}

void startGpsPolling()
{
    if (gpsPollStarted) return;
    gpsPollStarted = true;
    xTaskCreatePinnedToCore(gpsPollTask, "gpsPoll", 4096, nullptr, 1, nullptr, 1);
}

bool gpsFixIsFresh(unsigned long maxAgeMs)
{
    if (lastGpsFixMs == 0) return false; // 一度も取得していない
    return (millis() - lastGpsFixMs) < maxAgeMs;
}

bool fetchGpsNow(float &lat, float &lon)
{
    if (!pollAndroidGps(lat, lon)) return false;

    lastGpsFixMs = millis();
    if (aprsUseGPS)
    {
        aprsManualLat = lat;
        aprsManualLon = lon;
    }

    Serial.printf("[gpsPoll] on-demand fetch from Android: lat=%.6f lon=%.6f\n", lat, lon);
    sendGpsToPi(lat, lon);
    return true;
}
