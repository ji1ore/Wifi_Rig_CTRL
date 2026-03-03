/****************************************************
 *  M5CoreHamCAT 起動画面
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include <Arduino.h>
#include "globals.h"
#include <M5Unified.h>
#include <M5GFX.h>

void drawSplashScreen()
{
  canvas.fillScreen(BLACK);
  canvas.pushSprite(0, 0);

  for (int i = 0; i <= 20; ++i)
  {
    canvas.fillScreen(BLACK);

    // 明るさを段階的に上げる（0〜255）
    uint8_t brightness = i * 12;                                                            // 12×20 = 240まで
    uint16_t titleColor = canvas.color565(brightness, brightness, 255);                     // 青系で明るく
    uint16_t authorColor = canvas.color565(brightness / 2, brightness / 2, brightness / 2); // グレー系

    canvas.setFont(&fonts::lgfxJapanGothic_40);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(titleColor);
    canvas.drawString("M5CoreHamCAT", 160, 100);

    canvas.setFont(&fonts::lgfxJapanGothic_24);
    canvas.setTextColor(authorColor);
    canvas.drawString("by JI1ORE", 160, 140);

    canvas.pushSprite(0, 0);
    delay(50); // アニメーション速度
  }
  canvas.setFont(&fonts::lgfxJapanGothic_20); // サイズはお好みで調整してね
  canvas.setTextDatum(bottom_right);
  canvas.setTextColor(WHITE);
  canvas.drawString("Ver 1.00", 319, 239); // 320x240画面の右下に表示
  canvas.pushSprite(0, 0);

  canvas.setTextDatum(top_left); // 元に戻す
}
