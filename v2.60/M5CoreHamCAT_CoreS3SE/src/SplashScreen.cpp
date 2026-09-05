/****************************************************
 *  Wifi_Rig_CTRL 起動画面
 *  Ver2.5
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

  // フェードインアニメーション
  for (int i = 0; i <= 20; ++i)
  {
    canvas.fillScreen(BLACK);

    uint8_t brightness = i * 12;
    uint16_t titleColor  = canvas.color565(brightness, brightness, 255);
    uint16_t authorColor = canvas.color565(brightness / 2, brightness / 2, brightness / 2);

    canvas.setFont(&fonts::lgfxJapanGothic_40);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(titleColor);
    canvas.drawString("Wifi_Rig_CTRL", 160, 90);

    canvas.setFont(&fonts::lgfxJapanGothic_24);
    canvas.setTextColor(authorColor);
    canvas.drawString("by JI1ORE", 160, 130);

    canvas.pushSprite(0, 0);
    delay(50);
  }

  canvas.setFont(&fonts::lgfxJapanGothic_20);
  canvas.setTextDatum(bottom_right);
  canvas.setTextColor(WHITE);
  canvas.drawString("Ver 2.51", 319, 165);
  canvas.setTextDatum(top_left);
  canvas.pushSprite(0, 0);

  delay(1000); // 1秒待機してからボタン表示

  // --- Normal / Skip ボタン ---
  canvas.setFont(&fonts::lgfxJapanGothic_20);
  canvas.setTextDatum(middle_center);

  canvas.fillRoundRect(10, 178, 140, 50, 8, 0x2104); // ダークグレー
  canvas.setTextColor(WHITE);
  canvas.drawString("Normal", 80, 203);

  canvas.fillRoundRect(170, 178, 140, 50, 8, 0x07FF); // シアン
  canvas.setTextColor(BLACK);
  canvas.drawString("Skip", 240, 203);

  canvas.setTextDatum(top_left);
  canvas.pushSprite(0, 0);

  // --- 無操作タイムアウト時の既定値トグル(Normal/Skipボタンの上、Verの左)---
  const int togX = 10, togY = 146, togW = 140, togH = 22;
  auto drawAutoSkipToggle = [&]()
  {
    canvas.fillRoundRect(togX, togY, togW, togH, 6, autoSkipDefault ? (uint16_t)0x07FF : (uint16_t)0x2104);
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(autoSkipDefault ? BLACK : WHITE);
    canvas.drawString(String("Default:") + (autoSkipDefault ? "Skip" : "Normal"), togX + togW / 2, togY + togH / 2 + 1);
    canvas.setTextDatum(top_left);
  };
  drawAutoSkipToggle();

  // タッチ待ち（8秒タイムアウトで既定値へ）
  bool explicitChoice = false;
  bool explicitSkip = false;
  unsigned long start = millis();
  while (millis() - start < 8000)
  {
    M5.update();
    auto t = M5.Touch.getDetail();
    if (t.wasPressed())
    {
      // --- 既定値トグル：タップしても選択は確定させず、既定値だけ更新してループ継続 ---
      if (t.x >= togX && t.x <= togX + togW && t.y >= togY && t.y <= togY + togH)
      {
        autoSkipDefault = !autoSkipDefault;
        prefs.begin("device", false);
        prefs.putBool("autoSkip", autoSkipDefault);
        prefs.end();
        drawAutoSkipToggle();
        canvas.pushSprite(0, 0);
        continue;
      }

      if (t.x >= 170 && t.x <= 310 && t.y >= 178 && t.y <= 228)
      {
        explicitChoice = true;
        explicitSkip = true;
      }
      else if (t.x >= 10 && t.x <= 150 && t.y >= 178 && t.y <= 228)
      {
        explicitChoice = true;
        explicitSkip = false;
      }
      break;
    }
    delay(10);
  }

  skipModeActive = explicitChoice ? explicitSkip : autoSkipDefault;
}
