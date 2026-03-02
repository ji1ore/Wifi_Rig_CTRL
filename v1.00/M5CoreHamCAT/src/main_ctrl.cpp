/****************************************************
 *  M5CoreHamCAT 無線機制御UI画面
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <map>
#include <stdlib.h>

std::map<String, int> modeStepMap;

static int touchStartX = -1;
static int touchStartY = -1;

extern String freqInputBuffer;
extern bool connected;
extern TaskHandle_t streamTaskHandle;

std::vector<String> supportedModes;
float currentPowerNorm = 0.0f;
float sqlLevel = 0.0f;
struct RigStatus;

static unsigned long lastSpkActionTime = 0;
const unsigned long spkLockoutInterval = 1000; // 1000ms以内の連打を無視

RigStatus fetchRigStatus();
void handleSwipe(int dir);
void changeFreq(int dir);
void newFreq(int64_t newFreqHz);
bool fetchModeList();
void startFreqInputUI();
void drawFreqInputScreen();
void handleFreqInputScreen();
void drawPasswordNumpad();
char detectPasswordNumpadKey(int x, int y);
void sendModeToRig(const String &mode);
void loadModeStepPrefs();

void drawMainUI(const String &freq, const String &mode, const String &model, int signalStrength)
{
  canvas.fillScreen(BLACK);

  // --- 上部情報（小さめフォント） ---
  canvas.setTextColor(WHITE);
  canvas.setTextDatum(top_left);
  canvas.setFont(&fonts::efontJA_12);
  canvas.setCursor(10, 10);
  canvas.printf("%s", model.c_str());

  // --- 送信状態表示（TX） ---
  int txBoxX = 180;
  int txBoxY = 10;
  int txBoxW = 30;
  int txBoxH = 20;

  uint16_t txBgColor = isTransmitting ? RED : WHITE;
  uint16_t txTextColor = isTransmitting ? WHITE : BLACK;

  canvas.fillRoundRect(txBoxX, txBoxY, txBoxW, txBoxH, 4, txBgColor);
  canvas.setTextColor(txTextColor);
  canvas.setFont(&fonts::efontJA_12);
  canvas.setTextDatum(middle_center);
  canvas.drawString("TX", txBoxX + txBoxW / 2, txBoxY + txBoxH / 2);
  canvas.setTextDatum(top_left);

  // --- 周波数（やや小さめ） ---
  double freqHz = freq.toDouble();
  double freqMHz = freqHz / 1e6;
  char freqStr[16];
  snprintf(freqStr, sizeof(freqStr), "%.5f", freqMHz);
  canvas.setFont(&fonts::efontJA_24);
  canvas.setTextColor(WHITE);
  canvas.setTextDatum(top_left);
  int textWidth = canvas.textWidth(freqStr);
  int xRight = 235;
  int xLeft = xRight - textWidth;
  canvas.setCursor(xLeft, 35);
  canvas.print(freqStr);
  canvas.setTextDatum(top_left);

  // --- 信号強度バー受信 ---
  int barX = 215, barY = 10;
  for (int i = 0; i < 10; ++i)
  {
    uint16_t color;
    if (i < signalStrength)
    {
      color = (i >= 8) ? RED : YELLOW; // 9本目と10本目は赤
    }
    else
    {
      color = DARKGREY;
    }
    canvas.fillRect(barX + i * 10, barY, 8, 20, color);
  }

  // --- 状態表示（1行） ---
  canvas.setTextColor(WHITE);
  canvas.setCursor(10, 85);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("Step:");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%s ", stepList[selStep].label);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("Pow"
                "%:");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%d ", (int)(currentPowerNorm * 100));
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("Mode");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%s", mode.c_str());

  // --- 状態表示（width） ---
  canvas.setTextColor(WHITE);
  canvas.setCursor(240, 30);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("width:");
  canvas.setCursor(250, 45);
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%d", lastWidth);
  // --- 状態表示（SQL） ---
  canvas.setCursor(240, 65);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("SQL:");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%d", (int)(sqlLevel * 100));
  // --- 状態表示（VOL） ---
  /*
  canvas.setCursor(240, 85);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("VOL:");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%d", (int)(currentVolume * 100));
  */

  // --- ボタン定義 ---
  struct Button
  {
    const char *label;
    MenuItem id;
  } buttons[12] = {
      {"Freq", MENU_FREQ},
      {"Step", MENU_STEP},
      {"Mode", MENU_MODE},
      {"Wid", MENU_WIDTH},
      {"Pow", MENU_POW},
      {"SQL", MENU_SQL},
      {"Vol", MENU_RVOL},
      {"", MENU_NONE},
      {"Back", MENU_BACK},
      {"SPK", MENU_SPK},
      {"DOWN", MENU_DOWN},
      {"UP", MENU_UP}};

  // --- ボタン描画（横幅最大化） ---
  int btnW = 72, btnH = 36;
  int spacingX = 4, spacingY = 8;
  int startX = (320 - (btnW * 4 + spacingX * 3)) / 2;
  int startY = 110;

  canvas.setFont(&fonts::efontJA_16);
  canvas.setTextDatum(middle_center);

  for (int i = 0; i < 12; ++i)
  {
    int row = i / 4;
    int col = i % 4;
    int x = startX + col * (btnW + spacingX);
    int y = startY + row * (btnH + spacingY);

    uint16_t color;

    if (buttons[i].id == MENU_BACK)
    {
      color = RED;
    }
    else if (buttons[i].id == MENU_NONE)
    {
      color = DARKGREY;
    }
    else if (buttons[i].id == MENU_SPK)
    {
      color = spkEnabled ? CYAN : BLUE;
    }
    else if (buttons[i].id == MENU_UP || buttons[i].id == MENU_DOWN)
    {
      color = ORANGE;
    }
    else
    {
      // ★ 他のボタンは selectedItem で色を決める
      color = (buttons[i].id == selectedItem) ? GREEN : BLUE;
    }

    uint16_t textColor = WHITE;
    if (color == ORANGE || color == CYAN || color == GREEN)
    {
      textColor = BLACK;
    }

    canvas.fillRoundRect(x, y, btnW, btnH, 6, color);
    canvas.setTextColor(textColor);
    canvas.drawString(buttons[i].label, x + btnW / 2, y + btnH / 2);
    canvas.setTextColor(WHITE);
  }

  canvas.pushSprite(0, 0);
}

MenuItem detectTouchedButton(int x, int y)
{
  int btnW = 72, btnH = 36;
  int spacingX = 4, spacingY = 8;
  int startX = (320 - (btnW * 4 + spacingX * 3)) / 2;
  int startY = 110;

  struct Button
  {
    MenuItem id;
    int row, col;
  } buttons[] = {
      {MENU_FREQ, 0, 0},
      {MENU_STEP, 0, 1},
      {MENU_MODE, 0, 2},
      {MENU_WIDTH, 0, 3},
      {MENU_POW, 1, 0},
      {MENU_SQL, 1, 1},
      {MENU_RVOL, 1, 2},
      {MENU_NONE, 1, 3},
      {MENU_BACK, 2, 0},
      {MENU_SPK, 2, 1},
      {MENU_DOWN, 2, 2},
      {MENU_UP, 2, 3}};

  for (auto &btn : buttons)
  {
    int bx = startX + btn.col * (btnW + spacingX);
    int by = startY + btn.row * (btnH + spacingY);

    if (x >= bx && x <= bx + btnW &&
        y >= by && y <= by + btnH)
    {
      return btn.id;
    }
  }

  return MENU_NONE;
}

void handleMainUIScreen()
{
  if (appState != STATE_MAIN_UI)
    return;
  static bool firstDraw = true;
  if (mainFirstDraw)
  {

    if (!fetchModeList())
    {
      /*
      // モード取得失敗時の処理（エラーメッセージ表示など）
      canvas.fillScreen(BLACK);
      canvas.setCursor(10, 10);
      canvas.setTextColor(RED);
      canvas.setFont(&fonts::efontJA_16);
      canvas.print("モード情報を取得できません");
      canvas.pushSprite(0, 0);
      */
      return;
    }

    fetchRigStatus();
    drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
    mainFirstDraw = false;
  }

  // --- ロータリーエンコーダー ---
  int32_t val = encoder.getEncoderValue();
  int32_t diff = val - lastEncVal;

  diff /= 2;

  if (diff != 0)
  {
    int dir = (diff > 0) ? +1 : -1;
    handleSwipe(dir);
    lastEncVal = val;
  }

  // --- 定時状態取得処理 ---
  static unsigned long lastPoll = 0;
  unsigned long now = millis();

  if (needRedraw)
  {
    drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
    isTransmitting = sharedTx;
    needRedraw = false;
  }

  auto t = M5.Touch.getDetail();
  // --- タッチ処理 ---
  if (t.wasPressed())
  {
    touchStartX = t.x;
    touchStartY = t.y;

    // --- 周波数表示タップで数値入力モードへ ---
    if (t.y >= 30 && t.y <= 70)
    { // 周波数表示の高さ
      appState = STATE_FREQ_INPUT;
      startFreqInputUI(); // 数値入力画面へ遷移
      return;
    }

    MenuItem touched = detectTouchedButton(t.x, t.y);

    if (touched == MENU_BACK)
    {
      Serial.println("BACK BUTTON PRESSED");
      spkEnabled = false;
      appState = STATE_DEVICE_SELECT;
      rigSelectFirstDraw = true;
      return;
    }
    else if (touched == MENU_UP)
    {
      handleSwipe(+1);
      drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
      return;
    }
    else if (touched == MENU_DOWN)
    {
      handleSwipe(-1);
      drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
      return;
    }

    else if (touched == MENU_SPK)
    {
      unsigned long now = millis();
      if (now - lastSpkActionTime > spkLockoutInterval)
      {
        lastSpkActionTime = now;

        spkEnabled = !spkEnabled;
        drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);

        // outが使える状態ならSetGain
        if (connected && out && streamTaskHandle == nullptr)
        {
          Serial.println("Calling SetGain()");
          out->SetGain(currentVolume);
        }
      }
      return;
    }

    else
    {
      if (selectedItem == touched)
      {
        selectedItem = MENU_NONE;
      }
      else
      {
        selectedItem = touched;
      }
      drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
    }
  }

  delay(10);
}

RigStatus fetchRigStatus()
{
  RigStatus st;
  st.valid = false;

  const int maxRetries = 3;
  const int retryDelayMs = 300;

  for (int attempt = 0; attempt < maxRetries; ++attempt)
  {
    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/radio/status";
    http.begin(url);
    int code = http.GET();

    if (code == 200)
    {
      String body = http.getString();
      http.end();

      JsonDocument doc;
      if (deserializeJson(doc, body) == DeserializationError::Ok)
      {
        st.freq = doc["freq"].as<String>();
        st.mode = doc["mode"].as<String>();
        st.model = rigNames[selRig];
        st.signal = doc["signal"].as<int>();
        st.tx = doc["tx"].as<bool>();

        if (doc["power"].is<String>())
        {
          String powerStr = doc["power"].as<String>();
          currentPowerNorm = powerStr.toFloat();
        }

        if (doc["width"].is<int>())
        {
          lastWidth = doc["width"].as<int>();
          for (int i = 0; i < supportedWidths.size(); i++)
          {
            if (supportedWidths[i] == lastWidth)
            {
              selWidthIndex = i;
              break;
            }
          }
        }

        if (doc["sql"].is<String>())
        {
          String sqlStr = doc["sql"].as<String>();
          sqlLevel = sqlStr.toFloat();
        }

        lastModel = rigNames[selRig];
        lastFreqHz = st.freq.toInt();
        lastMode = st.mode;
        signalStrength = st.signal;
        sharedModel = st.model;

        st.valid = true;
        return st;
      }
    }

    http.end();
    delay(retryDelayMs); // 少し待ってから再試行
  }

  return st;
}

void handleSwipe(int dir)
{
  switch (selectedItem)
  {

  case MENU_MODE:
  {
    if (supportedModes.size() > 0)
    {
      int idx = -1;
      for (int i = 0; i < supportedModes.size(); i++)
      {
        if (supportedModes[i] == lastMode)
        {
          idx = i;
          break;
        }
      }

      if (idx < 0)
        idx = 0;

      idx += dir;

      // ★ ループさせる
      if (idx < 0)
        idx = supportedModes.size() - 1;
      if (idx >= supportedModes.size())
        idx = 0;

      String newMode = supportedModes[idx];
      sendModeToRig(newMode);

      // モードに対応するステップを復元
      if (modeStepMap.count(newMode))
      {
        selStep = modeStepMap[newMode];
      }
      else
      {
        selStep = 0; // デフォルトステップ
      }
    }
  }
  break;
  case MENU_FREQ:
    changeFreq(dir);
    break;

  case MENU_STEP:
  {
    selStep += dir;
    if (selStep < 0)
      selStep = 0;
    if (selStep > 6)
      selStep = 6;

    // 現在のモードに対して保存
    modeStepMap[lastMode] = selStep;

    Preferences prefs;
    prefs.begin("modeStep", false);
    prefs.putInt(lastMode.c_str(), selStep);
    prefs.end();
  }
  break;
  case MENU_WIDTH:
    if (!supportedWidths.empty())
    {
      selWidthIndex += dir;
      if (selWidthIndex < 0)
        selWidthIndex = supportedWidths.size() - 1;
      if (selWidthIndex >= supportedWidths.size())
        selWidthIndex = 0;

      int newWidth = supportedWidths[selWidthIndex];
      sendModeToRig(lastMode); // widthも一緒に送る
    }
    break;
  case MENU_POW:
  {
    currentPowerNorm += dir * 0.01f; // 1%刻みで増減
    if (currentPowerNorm < 0.0f)
      currentPowerNorm = 0.0f;
    if (currentPowerNorm > 1.0f)
      currentPowerNorm = 1.0f;

    char powerStr[8];
    snprintf(powerStr, sizeof(powerStr), "%.2f", currentPowerNorm);

    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/radio/setpower?value=" + String(powerStr);
    http.begin(url);
    http.GET();
    http.end();
    break;
  }
  case MENU_SQL:
  {
    sqlLevel += dir * 0.01f;
    // float型なので0.05刻みで調整
    if (sqlLevel < 0.0f)
      sqlLevel = 0.0f;
    if (sqlLevel > 1.0f)
      sqlLevel = 1.0f;

    char sqlStr[8];
    snprintf(sqlStr, sizeof(sqlStr), "%.2f", sqlLevel);

    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/radio/setlevel?name=SQL&value=" + String(sqlStr);
    http.begin(url);
    http.GET();
    http.end();
  }
  break;
  case MENU_RVOL:
  {
    currentVolume += dir * 0.05f; // 5%刻みで調整
    if (currentVolume < 0.0f)
      currentVolume = 0.0f;
    if (currentVolume > 1.0f)
      currentVolume = 1.0f;

    char volStr[8];
    snprintf(volStr, sizeof(volStr), "%.2f", currentVolume);

    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/radio/setlevel?name=VOL&value=" + String(volStr);
    http.begin(url);
    http.GET();
    http.end();

    if (out)
    {
      out->SetGain(currentVolume);
    }
  }
  break;

  default:
    break;
  }
  drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
}

void changeFreq(int dir)
{
  long step = stepList[selStep].stepHz;
  long newFreq = lastFreqHz + dir * step;

  lastFreqHz = newFreq;

  char freqStr[16];
  snprintf(freqStr, sizeof(freqStr), "%ld", newFreq); // ← 明示的に整数として文字列化

  HTTPClient http;
  String url = "http://" + HostName + ":" + String(apiPort) + "/radio/setfreq?f=" + String(freqStr);
  http.begin(url);
  http.GET();
  http.end();
}

void newFreq(int64_t newFreqHz)
{
  lastFreqHz = newFreqHz;

  char freqStr[20];
  snprintf(freqStr, sizeof(freqStr), "%" PRId64, newFreqHz); // ← int64_tを安全に文字列化

  HTTPClient http;
  String url = "http://" + HostName + ":" + String(apiPort) + "/radio/setfreq?f=" + String(freqStr);
  http.begin(url);
  http.GET();
  http.end();
}

void startFreqInputUI()
{
  freqInputBuffer = "";

  canvas.fillScreen(BLACK);
  canvas.setCursor(10, 20);
  canvas.setTextColor(WHITE);
  canvas.setFont(&fonts::efontJA_16);
  canvas.print("Enter Frequency:");

  drawPasswordNumpad();

  canvas.pushSprite(0, 0);
}

bool fetchModeList()
{
  supportedModes.clear();
  HTTPClient http;
  String url = "http://" + HostName + ":" + String(apiPort) + "/radio/caps";
  http.begin(url);
  int code = http.GET();
  if (code != 200)
  {
    http.end();
    return false;
  }

  String body = http.getString();
  http.end();

  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok)
    return false;

  if (doc["modes"].is<JsonArray>())
  {
    JsonArray arr = doc["modes"].as<JsonArray>();
    for (auto v : arr)
    {
      supportedModes.push_back(v.as<String>());
    }
  }

  loadModeStepPrefs();

  return !supportedModes.empty(); // ← モードが1つもなければ false
}

void handleFreqInputScreen()
{
  auto t = M5.Touch.getDetail();
  if (!t.wasPressed())
    return;

  int x = t.x, y = t.y;

  // DEL
  if (x >= 10 && x <= 90 && y >= 210 && y <= 240)
  {
    if (freqInputBuffer.length() > 0)
      freqInputBuffer.remove(freqInputBuffer.length() - 1);
    drawFreqInputScreen();
    return;
  }

  // OK
  if (x >= 240 && x <= 320 && y >= 210 && y <= 240)
  {
    if (freqInputBuffer.length() == 0)
    { // 入力が空なら何もせず戻る
      appState = STATE_MAIN_UI;
      mainFirstDraw = true;
      drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
      return;
    }

    double freqMHz = atof(freqInputBuffer.c_str());
    int64_t newFreqHz = (int64_t)(freqMHz * 1e6 + 0.5);

    newFreq(newFreqHz);
    appState = STATE_MAIN_UI;
    mainFirstDraw = true;

    drawMainUI(sharedFreq, sharedMode, sharedModel, sharedSignal);
    return;
  }

  // 数字キー（パスワード画面のロジックをそのまま使う）
  char key = detectPasswordNumpadKey(x, y);
  if (key != 0)
  {
    freqInputBuffer += key;
    drawFreqInputScreen();
  }
}

String freqInputBuffer = "";

void drawFreqInputScreen()
{
  canvas.fillScreen(BLACK);

  canvas.setTextColor(YELLOW);
  canvas.setFont(&fonts::efontJA_16);
  canvas.setTextDatum(middle_left);
  canvas.drawString("Enter Frequency", 10, 20);

  canvas.setTextColor(WHITE);
  canvas.drawString(freqInputBuffer, 10, 50);

  drawPasswordNumpad();

  canvas.pushSprite(0, 0);
}

void drawPasswordNumpad()
{
  const char *nums = "1234567890";
  int keyW = 60, keyH = 40;
  int startX = (320 - (keyW * 3 + 5 * 2)) / 2;
  int startY = 70;

  // 1〜9
  for (int i = 0; i < 9; i++)
  {
    int col = i % 3;
    int row = i / 3;
    int x = startX + col * (keyW + 5);
    int y = startY + row * (keyH + 5);
    canvas.drawRect(x, y, keyW, keyH, WHITE);
    drawCentered(String(nums[i]).c_str(), x + keyW / 2, y + keyH / 2);
  }

  int yBottom = startY + (keyH + 5) * 3;

  // 0
  canvas.drawRect(95, yBottom, 65, keyH, WHITE);
  drawCentered("0", 95 + keyW / 2, yBottom + keyH / 2);

  // .
  canvas.drawRect(165, yBottom, 65, keyH, WHITE);
  drawCentered(".", 165 + keyW / 2, yBottom + keyH / 2);

  // DEL
  canvas.fillRoundRect(10, 210, 80, 30, 6, RED);
  drawCentered("DEL", 50, 225);

  // OK
  canvas.fillRoundRect(240, 210, 80, 30, 6, BLUE);
  drawCentered("OK", 280, 225);
}

char detectPasswordNumpadKey(int x, int y)
{
  int keyW = 60, keyH = 40;
  int startX = (320 - (keyW * 3 + 5 * 2)) / 2;
  int startY = 70;

  const char *nums = "1234567890";

  // 1〜9
  for (int i = 0; i < 9; i++)
  {
    int col = i % 3;
    int row = i / 3;
    int bx = startX + col * (keyW + 5);
    int by = startY + row * (keyH + 5);

    if (x >= bx && x <= bx + keyW &&
        y >= by && y <= by + keyH)
    {
      return nums[i];
    }
  }

  int yBottom = startY + (keyH + 5) * 3;

  // 0
  if (x >= 95 && x <= 160 && y >= yBottom && y <= yBottom + keyH)
    return '0';

  // .
  if (x >= 165 && x <= 230 && y >= yBottom && y <= yBottom + keyH)
    return '.';

  return 0;
}

void sendModeToRig(const String &mode)
{
  int width = supportedWidths[selWidthIndex]; // ← 選択中の帯域幅を取得！

  HTTPClient http;
  String url = "http://" + HostName + ":" + String(apiPort) +
               "/radio/setmode?mode=" + mode + "&width=" + String(width);

  http.begin(url);
  http.GET();
  http.end();

  lastMode = mode;
  lastWidth = width; // ← UI表示用に保存
}

void loadModeStepPrefs()
{
  Preferences prefs;
  prefs.begin("modeStep", true); // 読み取り専用

  for (const auto &mode : supportedModes)
  {
    int step = prefs.getInt(mode.c_str(), -1);
    if (step >= 0)
    {
      modeStepMap[mode] = step;
    }
  }

  prefs.end();
}
