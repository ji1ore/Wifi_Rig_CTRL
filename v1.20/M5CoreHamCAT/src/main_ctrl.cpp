/****************************************************
 *  Wifi_Rig_CTRL 無線機制御UI画面
 *  Ver1.20
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
#include "http_sender.h"

std::map<String, int> modeStepMap;
static MenuItem aprsTouchTarget = MENU_NONE;

static int touchStartX = -1;
static int touchStartY = -1;

extern String freqInputBuffer;
extern bool connected;
extern TaskHandle_t streamTaskHandle;

std::vector<String> supportedModes;
struct RigStatus;

bool uiLocked = false;

static unsigned long aprsPressStart = 0;
static unsigned long lastSpkActionTime = 0;
const unsigned long spkLockoutInterval = 2000; // 2000ms以内の連打を無視
unsigned long lastRigCmdTime = 0;
const unsigned long rigCmdInterval = 300; // 300ms に 1 回だけ送信
int accumulatedDir = 0;
unsigned long lastEncoderMoveTime = 0;
const unsigned long encoderStopDelay = 150; // 150ms 回転が止まったら送信

unsigned long lastInteractionTime = 0;
bool screenOn = true;

RigStatus fetchRigStatus();
void handleSwipe(int dir);
void newFreq(int64_t newFreqHz);
bool fetchModeList();
void startFreqInputUI();
void drawFreqInputScreen();
void handleFreqInputScreen();
void drawPasswordNumpad();
char detectPasswordNumpadKey(int x, int y);
void loadModeStepPrefs();
void lockUIExceptPTTandSPK();
void unlockUI();

void drawMainUI(void)
{
  canvas.fillScreen(BLACK);

  // --- 上部情報（小さめフォント） ---
  // --- 上部情報（小さめフォント） ---
  canvas.setTextColor(WHITE);
  canvas.setTextDatum(top_left);

  // 文字数でフォント切り替え
  if (sharedModel.length() > 14)
  {
    canvas.setFont(&fonts::efontJA_10); // 一段小さいフォント
  }
  else
  {
    canvas.setFont(&fonts::efontJA_12); // 通常フォント
  }

  canvas.setCursor(10, 10);
  canvas.printf("%s", sharedModel.c_str());

  // --- 送信状態表示（TX） ---
  int txBoxX = 170;
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
  double freqHz = sharedFreq.toDouble();
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
  int barX = 202, barY = 10;
  for (int i = 0; i < 22; ++i)
  {
    uint16_t color;
    if (i < sharedSignal * 2)
    {
      if (i <= 8 * 2)
        color = BLUE;
      else if (i <= 9 * 2)
        color = CYAN;
      else if (i <= 10 * 2)
        color = YELLOW;
      else if (i > 10 * 2)
        color = RED;
    }
    else
    {
      color = DARKGREY;
    }
    canvas.fillRect(barX + i * 5, barY, 4, 20, color);
  }

  //--- 信号強度受信数字 ---
  // canvas.setTextColor(WHITE);
  // canvas.setCursor(220, 10);
  // canvas.setFont(&fonts::efontJA_10);
  // canvas.printf("%.2f", sharedSignal);

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
  canvas.printf("%d ", (int)(sharedPower * 100));
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("Mode");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%s", sharedMode.c_str());

  // --- 状態表示（width） ---
  canvas.setTextColor(WHITE);
  canvas.setCursor(240, 30);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("width:");
  canvas.setCursor(250, 45);
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%d", sharedWidth);
  // --- 状態表示（SQL） ---
  canvas.setCursor(240, 65);
  canvas.setFont(&fonts::efontJA_10);
  canvas.printf("SQL:");
  canvas.setFont(&fonts::efontJA_12);
  canvas.printf("%d", (int)(sharedSQL * 100));

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
      {"APRS", MENU_APRS},
      {"PTT", MENU_PTT},
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
    else if (buttons[i].id == MENU_PTT)
    {
      color = txEnabled ? MAGENTA : PURPLE;
    }
    else if (buttons[i].id == MENU_SPK)
    {
      // ★ サンプリングレート 0 → スピーカー無効（グレーアウト）
      if (samplingRates[selSampling] == 0)
      {
        color = DARKGREY; // グレーアウト
      }
      else
      {
        color = spkEnabled ? GREEN : DARKGREEN;
      }
    }

    else if (buttons[i].id == MENU_APRS)
    {
      if (!aprsEnabled)
      {
        color = DARKGREY; // APRS 機能 OFF
      }
      else
      {
        // APRS 機能 ON
        if (aprsActive)
          color = GREEN; // 送信 ON（明るい緑）
        else
          color = DARKGREEN; // 送信 OFF（濃い緑）
      }
    }

    else if (buttons[i].id == MENU_UP || buttons[i].id == MENU_DOWN)
    {
      color = ORANGE;
    }
    else
    {
      // ★ 他のボタンは selectedItem で色を決める
      color = (buttons[i].id == selectedItem) ? CYAN : BLUE;
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
      {MENU_APRS, 1, 2},
      {MENU_PTT, 1, 3},
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

  // ★ APRS heartbeat（常に 10 秒ごと）
  if (aprsActive)
  {
    static unsigned long lastBeat = 0;
    if (millis() - lastBeat > 10000)
    {
      HTTPClient http;
      http.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_heartbeat");
      http.POST("");
      http.end();
      lastBeat = millis();
    }
  }

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
    drawMainUI();
    mainFirstDraw = false;

    lastInteractionTime = millis();
  }

  // --- ロータリーエンコーダー ---
  if (encoderPresent)
  {
    int32_t val = encoder.getEncoderValue();
    int32_t diff = val - lastEncVal;
    diff /= 2;

    if (diff != 0)
    {
      int dir = (diff > 0) ? +1 : -1;
      accumulatedDir += dir;
      lastEncoderMoveTime = millis();
      lastEncVal = val;
    }
  }

  // --- エンコーダ蓄積処理（最新だけ送る方式） ---
  if (accumulatedDir != 0 &&
      (millis() - lastEncoderMoveTime > encoderStopDelay))
  {
    int steps = accumulatedDir;

    // ★ Step は即時反映・インターバル無視でOK
    if (selectedItem == MENU_STEP)
    {
      accumulatedDir = 0;
      selStep += steps;
      if (selStep < 0)
        selStep = 0;
      if (selStep > 6)
        selStep = 6;
      needRedraw = true;
      drawMainUI();
      return;
    }

    // ★ 送信可能かチェック（250ms 経過していなければ送信しない）
    if (millis() - lastRigCmdTime >= rigCmdInterval)
    {
      lastRigCmdTime = millis();

      // ★ ここで初めて accumulatedDir をリセット
      accumulatedDir = 0;

      switch (selectedItem)
      {
      case MENU_FREQ:
      {
        int64_t baseFreq = sharedFreq.toInt();
        int64_t newFreqVal = baseFreq + steps * stepList[selStep].stepHz;

        sharedFreq = String(newFreqVal);
        lastUserFreqChange = millis();
        sendFreq(newFreqVal);
        break;
      }

      case MENU_MODE:
      {
        int modeIndex = 0;
        for (int i = 0; i < supportedModes.size(); i++)
          if (supportedModes[i] == sharedMode)
            modeIndex = i;

        int idx = (modeIndex + steps) % supportedModes.size();
        if (idx < 0)
          idx += supportedModes.size();

        sharedMode = supportedModes[idx];
        lastUserModeChange = millis();
        sendMode(sharedMode, sharedWidth);
        break;
      }

      case MENU_WIDTH:
      {
        selWidthIndex = (selWidthIndex + steps) % supportedWidths.size();
        if (selWidthIndex < 0)
          selWidthIndex += supportedWidths.size();

        sharedWidth = supportedWidths[selWidthIndex];
        lastUserWidthChange = millis();
        sendMode(sharedMode, sharedWidth);
        break;
      }

      case MENU_POW:
      {
        currentPowerNorm += steps * 0.01f;
        currentPowerNorm = constrain(currentPowerNorm, 0.0f, 1.0f);
        sharedPower = currentPowerNorm;
        lastUserPowerChange = millis();
        sendPower(sharedPower);
        break;
      }

      case MENU_SQL:
      {
        sqlLevel += steps * 0.01f;
        sqlLevel = constrain(sqlLevel, 0.0f, 1.0f);
        sharedSQL = sqlLevel;
        lastUserSQLChange = millis();
        sendLevel("SQL", sharedSQL);
        break;
      }

      case MENU_RVOL:
      {
        currentVolume += steps * 0.05f;
        currentVolume = constrain(currentVolume, 0.0f, 1.0f);
        sharedVolume = currentVolume;
        lastUserVolumeChange = millis();
        sendLevel("VOL", sharedVolume);
        if (out)
          out->SetGain(currentVolume);
        break;
      }
      }

      needRedraw = true;
    }

    // ★ 送信できない場合は accumulatedDir を消さない（重要）
    drawMainUI();
  }

  // --- 定時状態取得処理 ---
  static unsigned long lastPoll = 0;
  unsigned long now = millis();

  if (needRedraw)
  {
    drawMainUI();
    isTransmitting = sharedTx;
    needRedraw = false;
  }

  auto t = M5.Touch.getDetail();
  // --- 無操作で画面オフ ---
  if (screenOn && screenTimeout > 0 && (millis() - lastInteractionTime > screenTimeout))
  {
    M5.Lcd.sleep(); // 画面オフ
    screenOn = false;
  }

  // --- 操作したら復帰 ---
  if (!screenOn && t.wasPressed())
  {
    M5.Lcd.wakeup(); // 画面オン
    screenOn = true;
    lastInteractionTime = millis(); // 再度タイマーリセット
    mainFirstDraw = true;           // 再描画フラグ
    return;
  }

  // --- タッチ処理 ---
  if (t.wasPressed())
  {
    touchStartX = t.x;
    touchStartY = t.y;

    // --- 周波数表示タップで数値入力モードへ ---
    if (t.y >= 30 && t.y <= 70)
    {
      if (txEnabled)
      {
        // ★ PTT 中は周波数入力画面を出さない
        return;
      }

      appState = STATE_FREQ_INPUT;
      startFreqInputUI();
      return;
    }

    MenuItem touched = detectTouchedButton(t.x, t.y);

    if (touched == MENU_APRS)
    {
      aprsTouchTarget = MENU_APRS;
      aprsPressStart = millis();
    }
    else
    {
      aprsTouchTarget = MENU_NONE;
    }

    // ★ PTT ON の間は BACK / SPK / PTT 以外は無視
    if (txEnabled)
    {
      MenuItem touched = detectTouchedButton(t.x, t.y);
      if (touched != MENU_PTT && touched != MENU_SPK && touched != MENU_BACK)
      {
        return;
      }
    }

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
      drawMainUI();
      return;
    }
    else if (touched == MENU_DOWN)
    {
      handleSwipe(-1);
      drawMainUI();
      return;
    }

    else if (touched == MENU_PTT)
    {
      txEnabled = !txEnabled;

      if (txEnabled)
      {
        // ★ UI を 2 秒ロック（PTT と SPK 以外）
        lockUIExceptPTTandSPK();
        selectedItem = MENU_NONE; // ★ 他のメニュー選択解除

        // ★ ボタン色更新
        drawMainUI();

        // ★ 2 秒後にロック解除
        xTaskCreate(
            [](void *)
            {
              vTaskDelay(2000 / portTICK_PERIOD_MS);
              unlockUI();
              vTaskDelete(NULL);
            },
            "unlockTask",
            2048,
            NULL,
            1,
            NULL);

        // ★ FastAPI の poll 停止
        HTTPClient http;
        http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/poll");
        http.addHeader("Content-Type", "application/x-www-form-urlencoded");
        http.POST("state=0");
        http.end();
        setLedColor(0, 255, 0);
      }
      else
      {
        unlockUI();
        selectedItem = MENU_NONE;
        drawMainUI();

        // ★ poll 再開
        HTTPClient http;
        http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/poll");
        http.addHeader("Content-Type", "application/x-www-form-urlencoded");
        http.POST("state=1");
        http.end();
        clearLed();
      }

      return;
    }

    else if (touched == MENU_SPK)
    {

      if (samplingRates[selSampling] == 0)
      {
        return;
      }

      unsigned long now = millis();
      if (now - lastSpkActionTime > spkLockoutInterval)
      {
        lastSpkActionTime = now;

        spkEnabled = !spkEnabled;
        drawMainUI();

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
      // ★ APRS は SELECT 対象外（他の選択を外さない）
      if (touched == MENU_APRS)
        return;

      if (selectedItem == touched)
      {
        selectedItem = MENU_NONE;
      }
      else
      {
        selectedItem = touched;
      }
      drawMainUI();
    }
  }

  // --- APRS ボタン長押し・短押し処理 ---
  if (aprsTouchTarget == MENU_APRS)
  {
    // 押している間（長押し判定）
    if (t.isPressed())
    {
      if (millis() - aprsPressStart > 700)
      {
        appState = STATE_APRS_SETTINGS;
        aprsSettingsFirstDraw = true;
        aprsTouchTarget = MENU_NONE;
        return;
      }
      // return;
    }

    if (t.wasReleased())
    {

      if (!aprsEnabled)
      {
        aprsTouchTarget = MENU_NONE;
        return;
      }

      aprsActive = !aprsActive;

      HTTPClient http;
      if (aprsActive)
      {

        // APRS 設定を送信する
        HTTPClient http;
        http.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_config");
        http.addHeader("Content-Type", "application/json");

        String cfg = "{";
        cfg += "\"callsign\":\"" + aprsCallsign + "\",";
        cfg += "\"ssid\":" + String(aprsSSID) + ",";
        cfg += "\"path\":\"" + aprsPath + "\",";
        cfg += "\"symbol\":\"/\",";
        cfg += "\"destination\":\"APRS\",";
        cfg += "\"interval\":" + String(aprsIntervalSec) + ",";
        cfg += "\"freq\":" + String(aprsTxFreq) + ",";
        cfg += "\"baud\":" + String(aprsBaud) + ",";
        cfg += "\"use_gps\":" + String(aprsUseGPS ? "true" : "false") + ",";
        cfg += "\"manual_lat\":" + String(aprsManualLat) + ",";
        cfg += "\"manual_lon\":" + String(aprsManualLon) + ",";
        cfg += "\"symbol\":\"" + aprsSymbol + "\",";
        cfg += "\"destination\":\"" + aprsDestination + "\",";
        cfg += "\"sound_device\":\"" + aprsSoundDevice + "\",";
        cfg += "\"rig_id\":\"" + aprsRigID + "\",";
        cfg += "\"cat_device\":\"" + aprsCatDevice + "\"";
        cfg += "}";

        http.POST(cfg);
        http.end();

        delay(500);

        // APRS 開始
        http.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_start");
        http.addHeader("Content-Type", "application/json");

        String body = "{";
        body += "\"callsign\":\"" + aprsCallsign + "\",";
        body += "\"ssid\":" + String(aprsSSID) + ",";
        body += "\"path\":\"" + aprsPath + "\",";
        body += "\"interval\":" + String(aprsIntervalSec) + ",";
        body += "\"freq\":" + String(aprsTxFreq) + ",";
        body += "\"baud\":" + String(aprsBaud) + ",";
        body += "\"use_gps\":" + String(aprsUseGPS ? "true" : "false") + ",";
        body += "\"manual_lat\":" + String(aprsManualLat) + ",";
        body += "\"manual_lon\":" + String(aprsManualLon);
        body += "}";

        http.POST(body);
      }
      else
      {
        // APRS 停止
        http.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_stop");
        http.POST("");
      }
      http.end();

      drawMainUI();
      aprsTouchTarget = MENU_NONE;
      return;
    }
  }
  delay(1);
}

RigStatus fetchRigStatus()
{
  RigStatus st;
  st.valid = false;

  HTTPClient http;
  String url = "http://" + HostName + ":" + String(apiPort) + "/radio/status";
  http.begin(url);
  int code = http.GET();

  if (code != 200)
  {
    http.end();
    return st;
  }

  String body = http.getString();
  http.end();

  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok)
    return st;

  st.freq = doc["freq"].as<String>();
  st.mode = doc["mode"].as<String>();
  st.model = rigNames[selRig];
  st.signal = doc["signal"].as<float>();
  st.tx = doc["tx"].as<bool>();

  float raw = st.signal;
  if (raw < 0)
    raw = 0;

  // --- log 変換 ---
  float x = raw + 1.0f;
  float v = log10f(x);

  // 最大値（20 を想定）
  float vmax = log10f(20.0f + 1.0f);

  // S1〜S9
  float S = 9.0f * (v / vmax);

  // S9+ 拡張
  if (S > 9.0f)
  {
    float over = S - 9.0f;
    S = 9.0f + over * 2.0f;
  }

  if (S < 0)
    S = 0;
  if (S > 15)
    S = 15;

  st.signal = S;

  // --- POWER ---
  if (doc["power"].is<float>() || doc["power"].is<double>() || doc["power"].is<int>())
  {
    String powerStr = doc["power"].as<String>();
    currentPowerNorm = powerStr.toFloat();
  }

  // --- WIDTH ---
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

  // --- SQL ---
  if (doc["sql"].is<String>())
  {
    String sqlStr = doc["sql"].as<String>();
    sqlLevel = sqlStr.toFloat();
  }

  // --- UI へ反映（UI操作直後は上書きしない） ---
  unsigned long now = millis();

  if (now - lastUserFreqChange > 1000)
    sharedFreq = st.freq;

  if (now - lastUserModeChange > 1000)
    sharedMode = st.mode;

  if (now - lastUserWidthChange > 1000)
    sharedWidth = lastWidth;

  if (now - lastUserSQLChange > 1000)
    sharedSQL = sqlLevel;

  if (now - lastUserPowerChange > 1000)
    sharedPower = currentPowerNorm;

  if (now - lastUserVolumeChange > 1000)
    sharedVolume = currentVolume;

  sharedSignal = st.signal;

  sharedTx = st.tx;
  sharedModel = st.model;

  st.valid = true;
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
      sharedMode = supportedModes[idx];
      lastUserModeChange = millis();
      drawMainUI();
      sendMode(sharedMode, sharedWidth);

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
  {
    // 現在の sharedFreq を基準に計算
    int64_t baseFreq = sharedFreq.toInt();
    int64_t newFreqVal = baseFreq + dir * stepList[selStep].stepHz;

    // UI に即時反映
    sharedFreq = String(newFreqVal);
    lastUserFreqChange = millis();

    // 画面を即時更新（HTTP待ちなし）
    drawMainUI();

    // 無線機への送信は非同期タスクで行う
    sendFreq(newFreqVal);

    break;
  }

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
  {
    unsigned long now = millis();
    if (now - lastRigCmdTime < rigCmdInterval)
      break;
    lastRigCmdTime = now;
    lastUserWidthChange = now;
    if (!supportedWidths.empty())
    {
      selWidthIndex += dir;
      if (selWidthIndex < 0)
        selWidthIndex = supportedWidths.size() - 1;
      if (selWidthIndex >= supportedWidths.size())
        selWidthIndex = 0;

      int newWidth = supportedWidths[selWidthIndex];
      sharedWidth = newWidth;
      drawMainUI();
      sendMode(sharedMode, sharedWidth);
    }
    break;
  }
  case MENU_POW:
  {
    unsigned long now = millis();
    if (now - lastRigCmdTime < rigCmdInterval)
      break;
    lastRigCmdTime = now;
    lastUserPowerChange = now;

    currentPowerNorm += dir * 0.01f;
    if (currentPowerNorm < 0.0f)
      currentPowerNorm = 0.0f;
    if (currentPowerNorm > 1.0f)
      currentPowerNorm = 1.0f;
    sharedPower = currentPowerNorm;
    drawMainUI();
    sendPower(sharedPower);
  }
  break;

  case MENU_SQL:
  {
    unsigned long now = millis();
    if (now - lastRigCmdTime < rigCmdInterval)
      break;
    lastRigCmdTime = now;
    lastUserSQLChange = now;

    sqlLevel += dir * 0.01f;
    if (sqlLevel < 0.0f)
      sqlLevel = 0.0f;
    if (sqlLevel > 1.0f)
      sqlLevel = 1.0f;
    sharedSQL = sqlLevel;
    drawMainUI();
    sendLevel("SQL", sharedSQL);
  }
  break;

  case MENU_RVOL:
  {
    unsigned long now = millis();
    if (now - lastRigCmdTime < rigCmdInterval)
      break;
    lastRigCmdTime = now;
    lastUserVolumeChange = now;

    currentVolume += dir * 0.05f;
    if (currentVolume < 0.0f)
      currentVolume = 0.0f;
    if (currentVolume > 1.0f)
      currentVolume = 1.0f;

    sharedVolume = currentVolume;
    drawMainUI();
    sendLevel("VOL", sharedVolume);

    if (out)
    {
      out->SetGain(currentVolume);
    }
  }
  break;

  default:
    break;
  }
  drawMainUI();
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
      drawMainUI();
      return;
    }

    double freqMHz = atof(freqInputBuffer.c_str());
    int64_t newFreqHz = (int64_t)(freqMHz * 1e6 + 0.5);

    sharedFreq = newFreqHz;
    drawMainUI();
    sendFreq(newFreqHz);
    appState = STATE_MAIN_UI;
    mainFirstDraw = true;

    drawMainUI();
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

bool isAccumulatedMenu(MenuItem item)
{
  return true;
}

void lockUIExceptPTTandSPK()
{
  uiLocked = true;
}

void unlockUI()
{
  uiLocked = false;
}

void sendFreq(int64_t freq)
{
  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setfreq";
  cmd->body = "f=" + String(freq);

  // ★ 古い FREQ コマンドを捨てる
  HttpCommand *oldCmd;
  while (uxQueueMessagesWaiting(httpQueue) > 0)
  {
    if (xQueueReceive(httpQueue, &oldCmd, 0) == pdTRUE)
    {
      if (oldCmd->path == "/radio/setfreq")
      {
        delete oldCmd; // ★ 古い周波数コマンドを破棄
      }
      else
      {
        // 他のコマンドは戻す
        xQueueSend(httpQueue, &oldCmd, 0);
        break;
      }
    }
  }

  // ★ 最新の周波数だけ送る
  xQueueSend(httpQueue, &cmd, 0);
}

void sendMode(String mode, int width)
{
  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setmode";
  cmd->body = "mode=" + mode + "&width=" + String(width);

  // ★ 古い FREQ コマンドを捨てる
  HttpCommand *oldCmd;
  while (uxQueueMessagesWaiting(httpQueue) > 0)
  {
    if (xQueueReceive(httpQueue, &oldCmd, 0) == pdTRUE)
    {
      if (oldCmd->path == "/radio/setfreq")
      {
        delete oldCmd; // ★ 古い周波数コマンドを破棄
      }
      else
      {
        // 他のコマンドは戻す
        xQueueSend(httpQueue, &oldCmd, 0);
        break;
      }
    }
  }

  // ★ 最新の周波数だけ送る
  xQueueSend(httpQueue, &cmd, 0);
}

void sendLevel(String name, float value)
{
  char valStr[16];
  snprintf(valStr, sizeof(valStr), "%.3f", value);

  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setlevel";
  cmd->body = "name=" + name + "&value=" + String(valStr);

  // ★ 古い FREQ コマンドを捨てる
  HttpCommand *oldCmd;
  while (uxQueueMessagesWaiting(httpQueue) > 0)
  {
    if (xQueueReceive(httpQueue, &oldCmd, 0) == pdTRUE)
    {
      if (oldCmd->path == "/radio/setfreq")
      {
        delete oldCmd; // ★ 古い周波数コマンドを破棄
      }
      else
      {
        // 他のコマンドは戻す
        xQueueSend(httpQueue, &oldCmd, 0);
        break;
      }
    }
  }

  // ★ 最新の周波数だけ送る
  xQueueSend(httpQueue, &cmd, 0);
}

void sendPower(float power)
{
  char powerStr[16];
  snprintf(powerStr, sizeof(powerStr), "%.3f", power);

  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setpower";
  cmd->body = "value=" + String(powerStr);

  // ★ 古い FREQ コマンドを捨てる
  HttpCommand *oldCmd;
  while (uxQueueMessagesWaiting(httpQueue) > 0)
  {
    if (xQueueReceive(httpQueue, &oldCmd, 0) == pdTRUE)
    {
      if (oldCmd->path == "/radio/setfreq")
      {
        delete oldCmd; // ★ 古い周波数コマンドを破棄
      }
      else
      {
        // 他のコマンドは戻す
        xQueueSend(httpQueue, &oldCmd, 0);
        break;
      }
    }
  }

  // ★ 最新の周波数だけ送る
  xQueueSend(httpQueue, &cmd, 0);
}
