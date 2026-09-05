/****************************************************
 *  Wifi_Rig_CTRL 起動画面(M5StopWatch 円形466x466向け)
 ****************************************************/
#include <Arduino.h>
#include "globals.h"
#include "ui_core.h"
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

    canvas.setFont(&fonts::lgfxJapanGothic_36);
    canvas.setTextDatum(middle_center);
    canvas.setTextColor(titleColor);
    canvas.drawString("Wifi_Rig_CTRL", CANVAS_CENTER, CANVAS_CENTER - 65);

    canvas.setFont(&fonts::lgfxJapanGothic_24);
    canvas.setTextColor(authorColor);
    canvas.drawString("by JI1ORE", CANVAS_CENTER, CANVAS_CENTER - 25);

    canvas.pushSprite(0, 0);
    delay(50);
  }

  canvas.setFont(&fonts::lgfxJapanGothic_20);
  canvas.setTextDatum(middle_center);
  canvas.setTextColor(WHITE);
  canvas.drawString("Ver 2.51", CANVAS_CENTER, CANVAS_CENTER + 10);
  canvas.setTextDatum(top_left);
  canvas.pushSprite(0, 0);

  delay(1000); // 1秒待機してからボタン表示

  // --- Normal / Skip ボタン ---
  const int btnW = 140, btnH = 50, btnY = CANVAS_CENTER + 85, gap = 12;
  const int normalX = CANVAS_CENTER - gap / 2 - btnW;
  const int skipX = CANVAS_CENTER + gap / 2;

  drawPill(normalX, btnY, btnW, btnH, COL_SURFACE, "Normal", WHITE);
  drawPill(skipX, btnY, btnW, btnH, COL_ACCENT, "Skip", BLACK);
  canvas.pushSprite(0, 0);

  // --- 無操作タイムアウト時の既定値トグル(Normal/Skipボタンの上) ---
  const int togW = 170, togH = 24, togX = CANVAS_CENTER - togW / 2, togY = btnY - togH - 12;
  auto drawAutoSkipToggle = [&]()
  {
    drawPill(togX, togY, togW, togH, autoSkipDefault ? COL_ACCENT : COL_SURFACE,
             (String("Default:") + (autoSkipDefault ? "Skip" : "Normal")).c_str(),
             autoSkipDefault ? BLACK : WHITE);
  };
  drawAutoSkipToggle();
  canvas.pushSprite(0, 0);

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
      if (hitRect(t.x, t.y, togX, togY, togW, togH))
      {
        autoSkipDefault = !autoSkipDefault;
        prefs.begin("device", false);
        prefs.putBool("autoSkip", autoSkipDefault);
        prefs.end();
        drawAutoSkipToggle();
        canvas.pushSprite(0, 0);
        continue;
      }

      if (hitRect(t.x, t.y, skipX, btnY, btnW, btnH))
      {
        explicitChoice = true;
        explicitSkip = true;
      }
      else if (hitRect(t.x, t.y, normalX, btnY, btnW, btnH))
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
