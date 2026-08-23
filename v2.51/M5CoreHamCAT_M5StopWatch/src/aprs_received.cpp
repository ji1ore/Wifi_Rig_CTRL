/****************************************************
 *  Wifi_Rig_CTRL aprs_received.cpp
 *  Ver2.5
 *  by JI1ORE
 *
 *  APRS受信ビーコン一覧画面。TX方式(DireWolf/FTX-1内蔵モデム)に関わらず、
 *  Pi側で常時デコードしている受信局(/aprs_received)をポーリング表示する。
 ****************************************************/
#include <M5Unified.h>
#include <M5GFX.h>
#include <HTTPClient.h>
#include "globals.h"
#include "ui_core.h"
#include <ArduinoJson.h>
#include <vector>
#include <math.h>

extern M5Canvas canvas;

struct HeardStation
{
    String call;
    bool hasPos;
    float lat;
    float lon;
    String comment;
    String symbol; // APRSシンボル(テーブル+コードの2文字。例: "/>" = Car)
    int ageSec;
};

// ★ symbolは"テーブル文字+コード文字"の2文字。drawAprsSymbolIconはコード文字だけを見るので
//   最後の1文字を渡す(受信局はテーブル違いもあり得るが、アイコンの見た目はコード側で決まる)。
static String symbolCode(const String &symbol)
{
    return symbol.length() > 0 ? String(symbol[symbol.length() - 1]) : String("");
}

static std::vector<HeardStation> heardList;
static int scrollOffset = 0;
static int touchStartY = -1;
static unsigned long lastFetch = 0;
static int detailIdx = -1; // -1=一覧表示中、それ以外=heardList[detailIdx]の詳細(大きいコンパス)表示中

const int RX_HEADER_H = 50;
const int RX_ROW_H = 40;

// ★ 自局位置(aprsManualLat/Lon)から受信局までの距離(km)と方位(コンパス表記)を計算する。
//   GPS使用時もgps_relay.cppがaprsManualLat/Lonを継続更新しているため、常にこれが自局位置。
static float toRad(float deg) { return deg * (float)M_PI / 180.0f; }

float aprsDistanceKm(float lat1, float lon1, float lat2, float lon2)
{
    const float R = 6371.0f;
    float dLat = toRad(lat2 - lat1);
    float dLon = toRad(lon2 - lon1);
    float a = sinf(dLat / 2) * sinf(dLat / 2) +
              cosf(toRad(lat1)) * cosf(toRad(lat2)) * sinf(dLon / 2) * sinf(dLon / 2);
    float c = 2.0f * atan2f(sqrtf(a), sqrtf(1.0f - a));
    return R * c;
}

float aprsBearingDeg(float lat1, float lon1, float lat2, float lon2)
{
    float dLon = toRad(lon2 - lon1);
    float y = sinf(dLon) * cosf(toRad(lat2));
    float x = cosf(toRad(lat1)) * sinf(toRad(lat2)) -
              sinf(toRad(lat1)) * cosf(toRad(lat2)) * cosf(dLon);
    return fmodf((atan2f(y, x) * 180.0f / (float)M_PI) + 360.0f, 360.0f);
}

const char *aprsCompassDir(float lat1, float lon1, float lat2, float lon2)
{
    float bearing = aprsBearingDeg(lat1, lon1, lat2, lon2);
    static const char *dirs[] = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                  "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    int idx = (int)((bearing + 11.25f) / 22.5f) % 16;
    return dirs[idx];
}

// ★ 方位を円形コンパス+針で描画する(中心cx,cy 半径r、bearingDegは北=0度、時計回り)。
//   showAllDirsをtrueにするとN/E/S/Wの4方位ラベルも出す(詳細画面や受信ポップアップの
//   大きいコンパス用)。main_ctrl.cppの受信ポップアップからも呼ぶためstaticにしない。
void drawCompassIcon(int cx, int cy, int r, float bearingDeg, bool showAllDirs)
{
    // ★ 現在の配色テーマに合わせてコンパスの色を決める。コンパスは(一覧行・詳細画面・
    //   受信ポップアップいずれも)常に黒background上に描画されるため、昼夜設定に関わらず
    //   常に「夜」用パレット(明るい配色)を使う。MONO/AMBERテーマの昼配色は黒背景に対して
    //   暗すぎて見えなくなるため(false固定=夜配色を強制)。
    uint16_t bgDark, panelBg, borderCol, accentTeal, accentBlue, textSecondary,
        txRedCol, chipBg, btnIdle, btnIdleBorder, spkOnCol, aprsOnCol, navOrangeCol;
    getThemePalette(uiTheme, false, bgDark, panelBg, borderCol, accentTeal, accentBlue,
                    textSecondary, txRedCol, chipBg, btnIdle, btnIdleBorder, spkOnCol, aprsOnCol, navOrangeCol);

    uint16_t ringCol = accentBlue;
    uint16_t faceCol = chipBg;
    uint16_t needleTailCol = accentTeal;
    uint16_t tickCol = borderCol;
    uint16_t needleTipCol = txRedCol;

    canvas.fillCircle(cx, cy, r, faceCol);
    canvas.drawCircle(cx, cy, r, ringCol);

    // ★ 30度ごとの目盛り(N/E/S/Wはやや長め)
    for (int a = 0; a < 360; a += 30)
    {
        float rr = toRad((float)a);
        bool cardinal = (a % 90 == 0);
        int inner = cardinal ? r - 8 : r - 4;
        int x1 = cx + (int)(sinf(rr) * r);
        int y1 = cy - (int)(cosf(rr) * r);
        int x2 = cx + (int)(sinf(rr) * inner);
        int y2 = cy - (int)(cosf(rr) * inner);
        canvas.drawLine(x1, y1, x2, y2, cardinal ? ringCol : tickCol);
    }

    canvas.setFont(&fonts::efontJA_10);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(ringCol);
    canvas.drawString("N", cx, cy - r - 9);
    if (showAllDirs)
    {
        canvas.drawString("S", cx, cy + r + 9);
        canvas.drawString("E", cx + r + 9, cy);
        canvas.drawString("W", cx - r - 9, cy);
    }
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextDatum(top_left);

    // ★ 針: 先端は矢じり(三角形)、根本は細いテールのみのシンプルな矢印形状
    float rad = toRad(bearingDeg);
    float backRad = rad + (float)M_PI;
    float perpRad = rad + (float)M_PI / 2.0f;
    int tipLen = r - 6;
    int baseLen = (int)(tipLen * 0.55f);
    int tailLen = (int)(r * 0.4f);
    int headW = r > 30 ? 7 : 4;

    int tipX = cx + (int)(sinf(rad) * tipLen);
    int tipY = cy - (int)(cosf(rad) * tipLen);
    int baseX = cx + (int)(sinf(rad) * baseLen);
    int baseY = cy - (int)(cosf(rad) * baseLen);
    int leftX = baseX + (int)(sinf(perpRad) * headW);
    int leftY = baseY - (int)(cosf(perpRad) * headW);
    int rightX = baseX - (int)(sinf(perpRad) * headW);
    int rightY = baseY + (int)(cosf(perpRad) * headW);
    int tailX = cx + (int)(sinf(backRad) * tailLen);
    int tailY = cy - (int)(cosf(backRad) * tailLen);

    canvas.drawLine(tailX, tailY, baseX, baseY, needleTailCol);
    canvas.fillTriangle(tipX, tipY, leftX, leftY, rightX, rightY, needleTipCol);
    canvas.fillCircle(cx, cy, r > 30 ? 3 : 2, ringCol);
}

static void fetchReceivedBeacons()
{
    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/aprs_received";
    http.begin(url);
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
    int code = http.GET();

    if (code == 200)
    {
        String payload = http.getString();
        JsonDocument doc;
        if (!deserializeJson(doc, payload))
        {
            heardList.clear();
            for (auto v : doc["stations"].as<JsonArray>())
            {
                HeardStation hs;
                hs.call = v["call"].is<const char *>() ? String(v["call"].as<const char *>()) : "?";
                hs.hasPos = !v["lat"].isNull();
                hs.lat = hs.hasPos ? v["lat"].as<float>() : 0.0f;
                hs.lon = hs.hasPos ? v["lon"].as<float>() : 0.0f;
                hs.comment = v["comment"].is<const char *>() ? String(v["comment"].as<const char *>()) : "";
                hs.symbol = v["symbol"].is<const char *>() ? String(v["symbol"].as<const char *>()) : "";
                hs.ageSec = v["age_sec"].as<int>();
                heardList.push_back(hs);
            }
        }
    }
    http.end();
    lastFetch = millis();
}

// ★ 局をタップした時の詳細画面。無線機のAPRSポップアップのような大きいコンパスで
//   方向・距離をはっきり表示する。
static void drawAprsDetailUI()
{
    if (detailIdx < 0 || detailIdx >= (int)heardList.size())
    {
        detailIdx = -1;
        return;
    }
    HeardStation &hs = heardList[detailIdx];

    canvas.fillScreen(BLACK);

    canvas.setFont(&fonts::efontJA_16_b);
    canvas.setTextColor(CYAN);
    canvas.setCursor(10, 10);
    canvas.print(hs.call);

    int btnW = 80, btnH = 30, btnX = 320 - btnW - 10, btnY = 10;
    canvas.fillRoundRect(btnX, btnY, btnW, btnH, 6, BLUE);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_center);
    canvas.drawString("Back", btnX + btnW / 2, btnY + btnH / 2);
    canvas.setTextDatum(top_left);

    if (hs.hasPos)
    {
        float distKm = aprsDistanceKm(aprsManualLat, aprsManualLon, hs.lat, hs.lon);
        float bearing = aprsBearingDeg(aprsManualLat, aprsManualLon, hs.lat, hs.lon);
        const char *dir = aprsCompassDir(aprsManualLat, aprsManualLon, hs.lat, hs.lon);

        // ★ コンパスのS方位ラベルと距離/方角の行が近すぎて重なって見えていたため、
        //   半径を少し小さくして距離行との間隔を空ける。
        drawCompassIcon(160, 100, 60, bearing, true);

        // ★ 距離表示とコンパス方角表示の間にリグ(局)のシンボルアイコンを置く
        int rowY = 188;
        canvas.setFont(&fonts::efontJA_16_b);
        canvas.setTextColor(WHITE);
        char distBuf[16];
        snprintf(distBuf, sizeof(distBuf), "%.1f km", distKm);
        canvas.setTextDatum(middle_right);
        canvas.drawString(distBuf, 160 - 16, rowY);

        drawAprsSymbolIcon(symbolCode(hs.symbol), 160, rowY, false);

        canvas.setTextDatum(middle_left);
        canvas.drawString(dir, 160 + 16, rowY);
        canvas.setTextDatum(top_left);
    }
    else
    {
        canvas.setTextColor(DARKGREY);
        canvas.setCursor(10, 100);
        canvas.print("(no position data)");
    }

    canvas.setFont(&fonts::lgfxJapanGothic_8);
    canvas.setTextColor(LIGHTGREY);
    canvas.setTextDatum(top_center);
    String info = String(hs.ageSec) + "s ago";
    if (hs.comment.length() > 0)
        info += "  " + hs.comment;
    canvas.drawString(info, 160, 215);
    canvas.setTextDatum(top_left);
    canvas.setFont(&fonts::efontJA_12);

    canvas.pushSprite(0, 0);
}

void drawAprsReceivedUI()
{
    canvas.fillScreen(BLACK);

    canvas.setFont(&fonts::efontJA_16_b);
    canvas.setTextColor(CYAN);
    canvas.setCursor(10, 10);
    canvas.print("APRS Received");

    int btnW = 80, btnH = 30, btnX = 320 - btnW - 10, btnY = 10;
    canvas.fillRoundRect(btnX, btnY, btnW, btnH, 6, BLUE);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_center);
    canvas.drawString("Back", btnX + btnW / 2, btnY + btnH / 2);
    canvas.setTextDatum(top_left);

    canvas.setFont(&fonts::efontJA_12);

    if (heardList.empty())
    {
        canvas.setTextColor(DARKGREY);
        canvas.setCursor(10, RX_HEADER_H + 10);
        canvas.print("(no stations heard yet)");
    }
    else
    {
        int y = RX_HEADER_H + scrollOffset;
        for (auto &hs : heardList)
        {
            if (y + RX_ROW_H >= RX_HEADER_H && y <= 240)
            {
                canvas.drawRect(6, y, 308, RX_ROW_H - 6, DARKGREY);

                // ★ リグ(局)のシンボルアイコン(キャプション無しでコンパクトに)
                drawAprsSymbolIcon(symbolCode(hs.symbol), 20, y + 17, false);

                canvas.setTextColor(WHITE);
                canvas.setCursor(34, y + 4);
                canvas.print(hs.call);

                char ageBuf[16];
                if (hs.ageSec < 60)
                    snprintf(ageBuf, sizeof(ageBuf), "%ds ago", hs.ageSec);
                else if (hs.ageSec < 3600)
                    snprintf(ageBuf, sizeof(ageBuf), "%dm ago", hs.ageSec / 60);
                else
                    snprintf(ageBuf, sizeof(ageBuf), "%dh ago", hs.ageSec / 3600);
                canvas.setTextDatum(top_right);
                canvas.setTextColor(YELLOW);
                canvas.drawString(ageBuf, 308, y + 4);
                canvas.setTextDatum(top_left);

                if (hs.hasPos)
                {
                    // ★ 自局位置(aprsManualLat/Lon)からの距離と、方位を示すコンパスアイコン。
                    //   GPS使用時もgps_relay.cppが常時更新しているため自局位置として使える。
                    float distKm = aprsDistanceKm(aprsManualLat, aprsManualLon, hs.lat, hs.lon);
                    float bearing = aprsBearingDeg(aprsManualLat, aprsManualLon, hs.lat, hs.lon);
                    drawCompassIcon(296, y + 19, 13, bearing, false);

                    canvas.setFont(&fonts::lgfxJapanGothic_8);
                    canvas.setTextColor(LIGHTGREY);
                    canvas.setCursor(34, y + 20);
                    char distBuf[16];
                    snprintf(distBuf, sizeof(distBuf), "%.1fkm ", distKm);
                    String line2 = String(distBuf);
                    if (hs.comment.length() > 0)
                        line2 += hs.comment;
                    canvas.print(line2);
                    canvas.setFont(&fonts::efontJA_12);
                }
                else
                {
                    canvas.setFont(&fonts::lgfxJapanGothic_8);
                    canvas.setTextColor(LIGHTGREY);
                    canvas.setCursor(34, y + 20);
                    String line2 = "(no position) ";
                    if (hs.comment.length() > 0)
                        line2 += hs.comment;
                    canvas.print(line2);
                    canvas.setFont(&fonts::efontJA_12);
                }
            }
            y += RX_ROW_H;
        }
    }

    canvas.pushSprite(0, 0);
}

void handleAprsReceivedScreen()
{
    if (aprsReceivedFirstDraw)
    {
        fetchReceivedBeacons();
        drawAprsReceivedUI();
        aprsReceivedFirstDraw = false;
    }

    // ★ 詳細(コンパス)表示中は一覧の自動更新でその上に描き直さないよう止める
    if (detailIdx < 0 && millis() - lastFetch > 5000)
    {
        fetchReceivedBeacons();
        drawAprsReceivedUI();
    }

    auto t = M5.Touch.getDetail();

    int btnW = 80, btnH = 30, btnX = 320 - btnW - 10, btnY = 10;

    if (t.wasReleased())
    {
        if (t.x >= btnX && t.x <= btnX + btnW && t.y >= btnY && t.y <= btnY + btnH)
        {
            if (detailIdx >= 0)
            {
                // 詳細画面のBack -> 一覧に戻る
                detailIdx = -1;
                drawAprsReceivedUI();
            }
            else
            {
                aprsSettingsFirstDraw = true;
                appState = STATE_APRS_SETTINGS;
            }
            return;
        }
    }

    if (detailIdx >= 0)
        return; // 詳細表示中はBack以外のタッチを無視

    if (t.wasPressed())
    {
        touchStartY = t.y;
    }

    if (t.wasReleased() && t.y > RX_HEADER_H)
    {
        int dy = t.y - touchStartY;
        if (abs(dy) > 20)
        {
            scrollOffset += (dy > 0 ? 20 : -20);
            if (scrollOffset > 0)
                scrollOffset = 0;
            int contentH = (int)heardList.size() * RX_ROW_H;
            int maxScroll = (240 - RX_HEADER_H) - contentH;
            if (maxScroll > 0)
                maxScroll = 0;
            if (scrollOffset < maxScroll)
                scrollOffset = maxScroll;
            drawAprsReceivedUI();
        }
        else
        {
            // ★ ドラッグでなければタップとみなし、その行の局の詳細(大きいコンパス)を開く
            int y = (t.y - RX_HEADER_H) - scrollOffset;
            int idx = y / RX_ROW_H;
            if (idx >= 0 && idx < (int)heardList.size())
            {
                detailIdx = idx;
                drawAprsDetailUI();
            }
        }
    }
}
