/****************************************************
 *  M5CoreHamCAT ui_core.cpp
 ****************************************************/
#include "ui_core.h"
#include "globals.h"
#include <math.h>

// 既定の共通パレット(Wi-Fi/パスワード/設定等の画面で使用)。
// メイン操作画面は独自にgetThemePalette()(main_ctrl.cpp)で配色するため、
// ここでは触れない。
ColorTheme theme = {
    0x0000, 0x2104, 0x39C7, 0x07FF, 0xFFFF, 0x8410, 0xF800, 0x07E0, 0xFEA0,
};

void ui_init()
{
    canvas.createSprite(CANVAS_SIZE, CANVAS_SIZE);
    canvas.setTextDatum(top_left);
    canvas.setTextColor(WHITE);
    canvas.setFont(&fonts::efontJA_16);
}

void ui_clear()
{
    canvas.fillScreen(COL_BG);
}

void ui_drawTitle(const char *title)
{
    // ★ フォントを明示的に指定していなかったため、直前の画面(メイン画面のfreq表示
    //   efontJA_24等)で設定されたフォントサイズがそのまま引き継がれ、画面によって
    //   タイトルの大きさがバラバラになっていた。明示的に設定し、呼び出し元が続けて
    //   描画する内容に影響しないよう元のフォントに戻す。
    auto oldFont = canvas.getFont();
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextColor(COL_ACCENT);
    canvas.setTextDatum(top_center);
    canvas.drawString(title, CANVAS_CENTER, 20);
    canvas.setTextDatum(top_left);
    canvas.setTextColor(COL_TEXT);
    canvas.setFont(oldFont);
}

void ui_drawBezelRing()
{
    drawArcIndicator(CANVAS_CENTER, CANVAS_CENTER, CANVAS_CENTER - 2, 0, 360, COL_SURFACE, 3);
}

int safeHalfWidthAt(int dy)
{
    long r = CANVAS_CENTER - 8; // 8pxの余白を持たせる
    long d = dy;
    long v = r * r - d * d;
    if (v <= 0)
        return 0;
    return (int)sqrt((double)v);
}

void drawCentered(const char *txt, int x, int y, uint16_t color)
{
    uint8_t oldDatum = canvas.getTextDatum();
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(color);
    canvas.drawString(txt, x, y);
    canvas.setTextDatum(oldDatum);
    canvas.setTextColor(WHITE);
}

void drawLabel(const char *txt, int x, int y, uint16_t color)
{
    uint8_t oldDatum = canvas.getTextDatum();
    auto oldFont = canvas.getFont();
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextDatum(middle_left);
    canvas.setTextColor(color);
    canvas.drawString(txt, x, y);
    canvas.setFont(oldFont);
    canvas.setTextDatum(oldDatum);
    canvas.setTextColor(WHITE);
}

void drawHint(const char *txt, int y, uint16_t color)
{
    auto oldFont = canvas.getFont();
    canvas.setFont(&fonts::efontJA_14);
    drawCentered(txt, CANVAS_CENTER, y, color);
    canvas.setFont(oldFont);
}

void drawCircleButton(int cx, int cy, int r, uint16_t fillColor, const char *label, uint16_t labelColor, const lgfx::IFont *font)
{
    canvas.fillSmoothCircle(cx, cy, r, fillColor);
    if (label && label[0])
    {
        auto oldFont = canvas.getFont();
        canvas.setFont(font ? font : &fonts::efontJA_16);
        drawCentered(label, cx, cy + 1, labelColor);
        canvas.setFont(oldFont);
    }
}

// ★ メイン画面のメニューボタン用。中心(cx,cy)・半幅hw・半高hhの角丸四角形
//   (丸ボタンより同じ半径でも対角が短く、円形メーターリングとの干渉を避けやすい)。
//   hh省略時はhwと同じ(正方形)。
void drawSquareButton(int cx, int cy, int hw, uint16_t fillColor, const char *label, uint16_t labelColor, const lgfx::IFont *font, int hh)
{
    if (hh <= 0) hh = hw;
    canvas.fillSmoothRoundRect(cx - hw, cy - hh, hw * 2, hh * 2, 8, fillColor);
    if (label && label[0])
    {
        auto oldFont = canvas.getFont();
        canvas.setFont(font ? font : &fonts::efontJA_16);
        canvas.setTextSize(1.3); // ★ 文字を大きく見やすく(efontJA_16に中間サイズが無いためスケールで拡大)
        drawCentered(label, cx, cy + 1, labelColor);
        canvas.setTextSize(1);
        canvas.setFont(oldFont);
    }
}

bool hitSquareButton(int touchX, int touchY, int cx, int cy, int hw, int hh)
{
    if (hh <= 0) hh = hw;
    return touchX >= cx - hw && touchX <= cx + hw && touchY >= cy - hh && touchY <= cy + hh;
}

bool hitCircleButton(int touchX, int touchY, int cx, int cy, int r)
{
    long dx = touchX - cx, dy = touchY - cy;
    return (dx * dx + dy * dy) <= (long)(r * r);
}

void drawPill(int x, int y, int w, int h, uint16_t fillColor, const char *label, uint16_t labelColor)
{
    canvas.fillSmoothRoundRect(x, y, w, h, h / 2, fillColor);
    if (label && label[0])
    {
        auto oldFont = canvas.getFont();
        canvas.setFont(&fonts::efontJA_16);
        drawCentered(label, x + w / 2, y + h / 2 + 1, labelColor);
        canvas.setFont(oldFont);
    }
}

bool hitRect(int touchX, int touchY, int x, int y, int w, int h)
{
    return touchX >= x && touchX <= x + w && touchY >= y && touchY <= y + h;
}

void drawArcIndicator(int cx, int cy, int r, float startDeg, float endDeg, uint16_t color, int thickness)
{
    canvas.fillArc(cx, cy, r - thickness, r, startDeg, endDeg, color);
}

// NeoPixel等の物理LEDはM5StopWatchに非搭載のため no-op
// (呼び出し元のtxControlTask等は変更せずそのまま使えるように関数自体は残す)。
void initLed() {}
void clearLed() {}
void setLedColor(uint8_t r, uint8_t g, uint8_t b) {}
