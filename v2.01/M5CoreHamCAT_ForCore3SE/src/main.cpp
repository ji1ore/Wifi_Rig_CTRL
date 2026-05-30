/****************************************************
 *  Wifi_Rig_CTRL main.cpp
 *  Ver2.01
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include <M5GFX.h>
#include "ui_display.h"
#include "ui_core.h"
#include "globals.h"
#include "driver/i2s.h"
#include <HTTPClient.h>
#include "audio_i2c.hpp"
#include "es8388.hpp"
#include "AudioFileSourceICYStream.h"
#include "AudioFileSourceBuffer.h"
#include "AudioOutputI2S.h"
#include "http_sender.h"
#include <Adafruit_NeoPixel.h>

AudioOutputI2S *out = nullptr;
AudioFileSourceICYStream *file = nullptr;
AudioFileSourceBuffer *buff = nullptr;
TaskHandle_t streamTaskHandle = nullptr;
AppState lastAppState = STATE_WIFI;
bool lastspkEnabled = false;
int retries = 10;
bool connected = false;
volatile bool isReconnecting = false;
bool tx_ing = false;

// ★ オーディオポインタ保護用 mutex
SemaphoreHandle_t audioMutex = nullptr;

AudioI2c device;
ES8388 es8388(&Wire1, I2C_SDA_AUDIO, I2C_SCL_AUDIO);

Adafruit_NeoPixel pixels(NUMPIXELS, TX_LED_PIN, NEO_GRB + NEO_KHZ800);

M5GFX display;
const char *url = "http://";
unsigned long lastSend = 0;
unsigned long lastReconnectAttempt = 0;
const unsigned long reconnectInterval = 600000;

QueueHandle_t httpQueue = nullptr;

// M5Canvas canvas(&M5.Display);
MenuItem detectTouchedButton(int x, int y);

void handleWifiScreen();
void handleRigConnectScreen();
void handleMainUIScreen();
void handlePasswordScreen();
void handlePiConnectScreen();
void handleRigSelectScreen();
void handleFreqInputScreen();
void handleAPRSSettingsScreen();
void handleRigPTTScreen();
void startAprsBeaconTask();
void stopAprsBeaconTask();
void sendAprsBeacon();
void drawSplashScreen();
void statusTask(void *param);
void streamTask(void *param);
void playbackTask(void *param);
void stopPlayback();
void updateSampleRate(int rate);
void txControlTask(void *param);
void httpSenderTask(void *param);
void audioTxTask(void *param);
void initLed();
void clearLed();
void setLedColor(uint8_t r, uint8_t g, uint8_t b);
void sendWifiPTT(bool on);
void sendHamlibPTT(bool on);
static void sendApiPollState(bool enabled);
static void sendApiPttHeartbeat();
String connectToRasPiServices();

#ifdef M5TOUGH
volatile int encoderPos = 0;

void IRAM_ATTR isrA()
{
  if (digitalRead(26) == digitalRead(36))
    encoderPos++;
  else
    encoderPos--;
}

void IRAM_ATTR isrB()
{
  if (digitalRead(26) != digitalRead(36))
    encoderPos++;
  else
    encoderPos--;
}
#endif

void setup()
{
  auto cfg = M5.config();

  // --- M5 本体の初期化 ---
  cfg.internal_mic = false;
  cfg.internal_spk = false;
  cfg.external_spk = true;

  Serial.begin(115200);

  M5.begin(cfg);

#ifdef M5TOUGH
#else
  Wire.begin(I2C_SDA_ENCODER, I2C_SCL_ENCODER); // SDA=32, SCL=33 (Core2)
  encoder.begin(&Wire, 0x40);                   // U135 の I2C アドレス
  lastEncVal = encoder.getEncoderValue();
#endif

  encoderPresent = false;

  Wire.beginTransmission(0x40);
  if (Wire.endTransmission() == 0)
  {
    encoderPresent = true;
    Serial.println("Encoder detected!");
  }
  else
  {
    Serial.println("Encoder NOT detected!");
  }

  pinMode(TX_KEY_PIN, INPUT_PULLUP);

#ifdef M5TOUGH
  pinMode(GPIO_A_ENCODER, INPUT_PULLUP); // Encoder A
  pinMode(GPIO_B_ENCODER, INPUT_PULLUP); // Encoder
  attachInterrupt(GPIO_A_ENCODER, isrA, CHANGE);
  attachInterrupt(GPIO_B_ENCODER, isrB, CHANGE);
#endif

  device.begin(&Wire1, I2C_SDA_AUDIO, I2C_SCL_AUDIO);
  device.setHPMode(AUDIO_HPMODE_NATIONAL);
  if (!es8388.init())
  {
    Serial.println("ES8388 init failed!");
  }
  else
  {
    Serial.println("ES8388 init OK!");
  }

  es8388.setADCVolume(100);
  es8388.setDACVolume(80);
  es8388.setDACOutput(DAC_OUTPUT_OUT1);
  es8388.setBitsSample(ES_MODULE_ADC, BIT_LENGTH_16BITS);
  es8388.setSampleRate(SAMPLE_RATE_48K);

  // ホワイトノイズ抑制
  // 1) LI2LO/RI2RO をクリア: init() が 0xd0 でマイク入力をスピーカーにバイパスするのを防ぐ
  Wire1.beginTransmission(0x10); Wire1.write(0x27); Wire1.write(0x90); Wire1.endTransmission();
  Wire1.beginTransmission(0x10); Wire1.write(0x2a); Wire1.write(0x90); Wire1.endTransmission();
  // 2) MIC を物理的に切断（デフォルトは OPEN = 24dB PGA がアンビエントノイズを増幅）
  device.setMICStatus(AUDIO_MIC_CLOSE);
  // 3) ADC PGA ゲインを 0dB に下げる（init() は 24dB に設定）
  es8388.setMicGain(MIC_GAIN_0DB);
  // 4) ADC モジュール全体をパワーダウン（SPK 受信時は ADC 不要）
  Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0xFF); Wire1.endTransmission();

  canvas.setColorDepth(8);       // 軽量化
  canvas.createSprite(320, 240); // CoreS3SEの画面サイズ
  drawSplashScreen();
  canvas.setTextSize(2);
  canvas.setTextColor(WHITE);
  canvas.setFont(&fonts::efontJA_16);

  httpQueue = xQueueCreate(10, sizeof(HttpCommand *));
  audioMutex = xSemaphoreCreateMutex(); // ★ オーディオ mutex 初期化

  initLed();
  clearLed();

  xTaskCreatePinnedToCore(statusTask, "StatusTask", 4096, NULL, 5, NULL, 0);
  xTaskCreatePinnedToCore(txControlTask, "txControlTask", 4096, NULL, 5, NULL, 1);
  xTaskCreatePinnedToCore(httpSenderTask, "httpSender", 8192, NULL, 1, NULL, 1);

  appState = STATE_WIFI;
  lastReconnectAttempt = millis();
  loadPiSettings();   // hostname / apiPort / audioPort / useMDNS / apiKey を復元
  loadAprsSettings();
  loadPttSettings();

  if (skipModeActive)
  {
    // --- 飛ばしモード: 保存済み設定で一気にメインUIへ ---
    canvas.fillScreen(BLACK);
    canvas.setFont(&fonts::efontJA_16);
    canvas.setTextColor(WHITE);
    canvas.setTextDatum(middle_center);
    canvas.drawString("Skip Mode...", 160, 120);
    canvas.pushSprite(0, 0);

    // 保存SSIDを読む
    Preferences wPrefs;
    wPrefs.begin("wifi", true);
    String lastSSID = wPrefs.getString("lastssid", "");
    String lastPass = wPrefs.getString(lastSSID.c_str(), "");
    wPrefs.end();

    bool skipOK = false;
    if (lastSSID.length() > 0 && lastPass.length() > 0)
    {
      // WiFi接続
      WiFi.begin(lastSSID.c_str(), lastPass.c_str());
      unsigned long wstart = millis();
      while (millis() - wstart < 10000)
      {
        if (WiFi.status() == WL_CONNECTED) { skipOK = true; break; }
        delay(200);
      }
    }

    if (skipOK)
    {
      // Pi設定をロードして接続
      loadPiSettings();
      String piResult = connectToRasPiServices();
      if (piResult != "")
        skipOK = false;
    }

    if (skipOK)
    {
      // デバイス設定ロード
      Preferences dPrefs;
      dPrefs.begin("device", true);
      int savedBaud = dPrefs.getInt("baud", 3);
      selSampling      = dPrefs.getInt("sampling", 0);
      selScreenTimeout = dPrefs.getInt("disp", 2);
      String savedCat  = dPrefs.getString("catDev", "");
      dPrefs.end();

      screenTimeout = screenTimeoutOptions[selScreenTimeout] * 60 * 1000UL;
      SRate = samplingRates[selSampling];
      speakerEnabled = (SRate != 0);

      // I2S 既存ドライバ解放のみ（インストールは streamTask/audioTxTask が行う）
      i2s_driver_uninstall(I2S_NUM_0);
      i2s_config.sample_rate = (SRate > 0) ? SRate : 8000;

      // CAT設定（保存済みデバイス文字列を直接使用）
      String catParam = (savedCat.isEmpty() || savedCat == "None") ? "" : savedCat;

      // リグオープン
      HTTPClient skipHttp;
      String openUrl = "http://" + HostName + ":" + String(apiPort) +
                       "/radio/open?model=" + String(rigIds[selRig]) +
                       "&cat=" + catParam +
                       "&baud=" + String(baudRates[savedBaud]);
      skipHttp.begin(openUrl);
      skipHttp.GET();
      skipHttp.end();
      delay(1000);

      // ステータス待ち
      bool ready = false;
      for (int i = 0; i < 30; ++i)
      {
        delay(100);
        HTTPClient sHttp;
        sHttp.begin("http://" + HostName + ":" + String(apiPort) + "/radio/status");
        if (sHttp.GET() == 200) { ready = true; sHttp.end(); break; }
        sHttp.end();
      }

      if (ready)
      {
        // APRS の RigID / CAT Device を更新
        aprsRigID    = String(rigIds[selRig]);
        aprsCatDevice = savedCat;

        appState = STATE_MAIN_UI;
        mainFirstDraw = true;
        lastUserFreqChange  = millis();
        lastUserModeChange  = millis();
        lastUserWidthChange = millis();
        lastUserSQLChange   = millis();
        lastUserPowerChange = millis();
        lastUserVolumeChange = millis();
      }
      else
      {
        skipOK = false;
      }
    }

    if (!skipOK)
    {
      skipModeActive = false;
      appState = STATE_WIFI;
    }

    canvas.setTextDatum(top_left);
  }
}

void loop()
{
  M5.update();

  if (appState != lastAppState || lastspkEnabled != spkEnabled)
  {
    if (appState == STATE_MAIN_UI && !connected && !audioTxRunning && audioTxTaskHandle == nullptr && streamTaskHandle == nullptr && spkEnabled == true)
    {
      if (millis() - lastStreamAttempt > 2000)
      {
        lastStreamAttempt = millis();
        Serial.println("streamTask 起動！");
        xTaskCreatePinnedToCore(streamTask, "streamTask", 8192, NULL, 10, &streamTaskHandle, 1);
      }
    }
    else if (lastAppState == STATE_MAIN_UI && appState != STATE_MAIN_UI || spkEnabled == false)
    {
      stopPlayback();
      connected = false;
    }
    lastAppState = appState;
    lastspkEnabled = spkEnabled;
  }

  if (appState == STATE_MAIN_UI && !connected && !audioTxRunning && audioTxTaskHandle == nullptr && streamTaskHandle == nullptr && spkEnabled == true)
  {
    if (millis() - lastStreamAttempt > 2000)
    {
      lastStreamAttempt = millis();
      Serial.println("streamTask 起動！");
      xTaskCreatePinnedToCore(streamTask, "streamTask", 8192, NULL, 10, &streamTaskHandle, 1);
    }
  }
  if (streamTaskHandle != nullptr)
  {
    eTaskState state = eTaskGetState(streamTaskHandle);
    if (state == eDeleted)
    {
      streamTaskHandle = nullptr;
    }
  }

  if (appState == STATE_MAIN_UI && spkEnabled && connected)
  {
    if (millis() - lastReconnectAttempt > reconnectInterval)
    {
      Serial.printf("🔄 [%lu ms] 10分経過、再接続を試みます\n", millis());
      stopPlayback();
      connected = false;
      lastReconnectAttempt = millis();
    }
  }

  switch (appState)
  {
  case STATE_WIFI:
    handleWifiScreen();
    break;

  case STATE_PASSWORD:
    handlePasswordScreen();
    break;

  case STATE_PI_CONNECT:
    handlePiConnectScreen();
    break;
  case STATE_RIG_CONNECT:
    handleRigSelectScreen();
    break;

  case STATE_DEVICE_SELECT:
    handleRigConnectScreen();
    break;

  case STATE_RIG_PTT:
    handleRigPTTScreen();
    break;

  case STATE_APRS_SETTINGS:
    handleAPRSSettingsScreen();
    break;

  case STATE_MAIN_UI:
  {
    handleMainUIScreen();
    break;
  }
  case STATE_FREQ_INPUT:
    handleFreqInputScreen();
    return;
  }
}

void statusTask(void *param)
{
  while (true)
  {
    if (appState == STATE_MAIN_UI)
    {
      RigStatus st = fetchRigStatus();
      if (st.valid)
      {
        // 共有変数にコピー（mutexがあるとより安全）
        unsigned long now = millis();

        if (now - lastUserFreqChange > 300)
          sharedFreq = st.freq;

        if (now - lastUserModeChange > 300)
          sharedMode = st.mode;

        if (now - lastUserWidthChange > 300)
          sharedWidth = lastWidth;

        if (now - lastUserSQLChange > 300)
          sharedSQL = sqlLevel;

        if (now - lastUserPowerChange > 300)
          sharedPower = currentPowerNorm;

        if (now - lastUserVolumeChange > 300)
          sharedVolume = currentVolume;

        sharedSignal = st.signal;
        if (txEnabled)
        {
          sharedTx = tx_ing;
        }
        else
        {
          sharedTx = st.tx;
        }

        needRedraw = true;
      }
    }
    vTaskDelay(200 / portTICK_PERIOD_MS); // 200msごとに取得
  }
}

void streamTask(void *param)
{
  Serial.println("[stream] 開始");
  connected = false;

  if (samplingRates[selSampling] == 0) {
    Serial.println("[stream] SamplingRate=0, skip");
    streamTaskHandle = nullptr;
    vTaskDelete(NULL);
    return;
  }

  // I2S TX インストール
  i2s_config_t tx_cfg = {
    .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate          = (uint32_t)samplingRates[selSampling],
    .bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format       = I2S_CHANNEL_FMT_RIGHT_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags     = 0,
    .dma_buf_count        = 4,
    .dma_buf_len          = 256,
    .use_apll             = false,
    .tx_desc_auto_clear   = true,
    .fixed_mclk           = 0,
  };
  i2s_pin_config_t tx_pins = {
    .mck_io_num   = SYS_I2S_MCLK_PIN,  // GPIO0: ES8388 に MCLK を供給
    .bck_io_num   = SYS_I2S_SCLK_PIN,
    .ws_io_num    = SYS_I2S_LRCK_PIN,
    .data_out_num = SYS_I2S_DOUT_PIN,
    .data_in_num  = I2S_PIN_NO_CHANGE,
  };

  if (i2s_driver_install(I2S_NUM_0, &tx_cfg, 0, NULL) != ESP_OK) {
    Serial.println("[stream] I2S TX install failed");
    streamTaskHandle = nullptr;
    vTaskDelete(NULL);
    return;
  }
  i2s_set_pin(I2S_NUM_0, &tx_pins);
  Serial.println("[stream] I2S TX installed");

  // HTTP接続（リトライあり）
  WiFiClient client;
  bool httpOK = false;
  for (int attempt = 0; attempt < retries && !audioTxRunning; ++attempt) {
    Serial.printf("[stream] connect attempt %d\n", attempt + 1);
    if (client.connect(HostName.c_str(), audioPort)) {
      httpOK = true;
      break;
    }
    vTaskDelay(500 / portTICK_PERIOD_MS);
  }

  if (!httpOK || audioTxRunning) {
    Serial.println("[stream] connect failed");
    i2s_driver_uninstall(I2S_NUM_0);
    streamTaskHandle = nullptr;
    vTaskDelete(NULL);
    return;
  }

  // HTTP GET リクエスト
  String path = "/radio/audio?rate=" + String(samplingRates[selSampling]);
  client.print("GET " + path + " HTTP/1.1\r\n");
  client.print("Host: " + HostName + ":" + String(audioPort) + "\r\n");
  if (!apiKey.isEmpty()) client.print("X-API-Key: " + apiKey + "\r\n");
  client.print("Connection: close\r\n");
  client.print("\r\n");

  // ステータス行解析
  int statusCode = 0;
  {
    String line = "";
    unsigned long t0 = millis();
    while (client.connected() && millis() - t0 < 5000) {
      if (client.available()) {
        char c = client.read();
        if (c == '\n') break;
        if (c != '\r') line += c;
      } else {
        vTaskDelay(1 / portTICK_PERIOD_MS);
      }
    }
    int sp = line.indexOf(' ');
    if (sp >= 0) statusCode = line.substring(sp + 1, sp + 4).toInt();
    Serial.printf("[stream] HTTP status=%d\n", statusCode);
  }

  if (statusCode != 200) {
    client.stop();
    i2s_driver_uninstall(I2S_NUM_0);
    streamTaskHandle = nullptr;
    vTaskDelete(NULL);
    return;
  }

  // 残りのヘッダ行を解析（chunked 検出）
  bool chunked = false;
  {
    String line = "";
    unsigned long t0 = millis();
    while (client.connected() && millis() - t0 < 5000) {
      if (client.available()) {
        char c = client.read();
        if (c == '\n') {
          line.trim();
          if (line.length() == 0) break; // 空行 = ヘッダ終了
          String lo = line; lo.toLowerCase();
          if (lo.indexOf("transfer-encoding") >= 0 && lo.indexOf("chunked") >= 0)
            chunked = true;
          line = "";
          t0 = millis();
        } else if (c != '\r') {
          line += c;
        }
      } else {
        vTaskDelay(1 / portTICK_PERIOD_MS);
      }
    }
  }

  Serial.printf("[stream] streaming, chunked=%d, rate=%d\n", chunked, samplingRates[selSampling]);
  connected = true;
  isReconnecting = false;

  const int PCM_BYTES = 512;
  uint8_t  pcmBuf[PCM_BYTES];
  int16_t  stereoBuf[PCM_BYTES]; // PCM_BYTES/2 mono samples × 2ch = PCM_BYTES int16 elements

  int chunkRemain = 0;

  while (!isReconnecting && !audioTxRunning) {
    if (!client.connected() && !client.available()) {
      Serial.println("[stream] server closed");
      break;
    }

    int avail = client.available();

    if (chunked) {
      if (chunkRemain == 0) {
        // チャンクサイズ行を読む
        String sizeLine = "";
        unsigned long t0 = millis();
        bool gotLine = false;
        while (!gotLine && millis() - t0 < 1000 && !isReconnecting && !audioTxRunning) {
          if (client.available()) {
            char c = client.read();
            if (c == '\n') { gotLine = true; }
            else if (c != '\r') sizeLine += c;
          } else {
            vTaskDelay(1 / portTICK_PERIOD_MS);
          }
        }
        if (!gotLine) break;
        chunkRemain = (int)strtol(sizeLine.c_str(), NULL, 16);
        if (chunkRemain == 0) { Serial.println("[stream] chunked end"); break; }
        continue;
      }

      if (avail <= 0) { vTaskDelay(1 / portTICK_PERIOD_MS); continue; }
      int toRead = min((int)chunkRemain, min((int)avail, (int)PCM_BYTES));
      int n = client.read(pcmBuf, toRead);
      if (n <= 0) { vTaskDelay(1 / portTICK_PERIOD_MS); continue; }
      chunkRemain -= n;

      if (chunkRemain == 0) {
        // チャンク末尾の \r\n を読み捨て
        unsigned long t0 = millis();
        int skip = 0;
        while (skip < 2 && millis() - t0 < 200) {
          if (client.available()) { client.read(); skip++; }
          else vTaskDelay(1 / portTICK_PERIOD_MS);
        }
      }

      int samples = n / 2;
      int16_t *src = (int16_t *)pcmBuf;
      for (int i = 0; i < samples; i++) {
        float s = src[i] * currentVolume;
        if (s >  32767.0f) s =  32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        stereoBuf[i * 2]     = (int16_t)s;
        stereoBuf[i * 2 + 1] = (int16_t)s;
      }
      size_t written;
      i2s_write(I2S_NUM_0, stereoBuf, (size_t)(samples * 4), &written, pdMS_TO_TICKS(100));

    } else {
      if (avail <= 0) { vTaskDelay(1 / portTICK_PERIOD_MS); continue; }
      int toRead = min((int)avail, (int)PCM_BYTES);
      int n = client.read(pcmBuf, toRead);
      if (n <= 0) { vTaskDelay(1 / portTICK_PERIOD_MS); continue; }

      int samples = n / 2;
      int16_t *src = (int16_t *)pcmBuf;
      for (int i = 0; i < samples; i++) {
        float s = src[i] * currentVolume;
        if (s >  32767.0f) s =  32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        stereoBuf[i * 2]     = (int16_t)s;
        stereoBuf[i * 2 + 1] = (int16_t)s;
      }
      size_t written;
      i2s_write(I2S_NUM_0, stereoBuf, (size_t)(samples * 4), &written, pdMS_TO_TICKS(100));
    }
  }

  client.stop();
  i2s_driver_uninstall(I2S_NUM_0);
  connected = false;
  isReconnecting = false;
  Serial.println("[stream] stopped");
  streamTaskHandle = nullptr;
  vTaskDelete(NULL);
}

void stopPlayback()
{
  isReconnecting = true; // streamTask のループを止める
  connected = false;

  // streamTask が I2S を解放するのを待つ（最大1秒）
  unsigned long t = millis();
  while (streamTaskHandle != nullptr && millis() - t < 1000)
    vTaskDelay(50 / portTICK_PERIOD_MS);

  // 旧アーキテクチャ残留ポインタを安全にクリア（通常はすべて nullptr）
  if (audioMutex && xSemaphoreTake(audioMutex, pdMS_TO_TICKS(200)) == pdTRUE)
  {
    if (out)  { out->stop(); delete out;  out  = nullptr; }
    if (buff) { delete buff; buff = nullptr; }
    if (file) { delete file; file = nullptr; }
    xSemaphoreGive(audioMutex);
  }
  else
  {
    out = nullptr; buff = nullptr; file = nullptr;
  }

  isReconnecting = false;
  Serial.println("再生停止");
}

// 🎧 再生専用タスク
void playbackTask(void *pvParameters)
{
  static uint8_t buffer[512];
  while (true)
  {
    if (!connected || isReconnecting)
    {
      delay(100);
      continue;
    }

    // ★ mutex を取得してからポインタを使う
    if (audioMutex && xSemaphoreTake(audioMutex, pdMS_TO_TICKS(10)) == pdTRUE)
    {
      if (buff && buff->isOpen() && out)
      {
        int len = buff->read(buffer, sizeof(buffer));
        if (len > 0)
        {
          int sampleCount = len / 2;
          int16_t *samples16 = reinterpret_cast<int16_t *>(buffer);

          for (int i = 0; i < sampleCount; ++i)
          {
            int16_t sample[2] = {samples16[i], samples16[i]};
            int retry = 0;
            while (out && !isReconnecting && !out->ConsumeSample(sample))
            {
              xSemaphoreGive(audioMutex);
              delay(1);
              if (++retry > 200)
              {
                Serial.println("⚠️ ConsumeSample timeout!");
                goto next_cycle;
              }
              if (xSemaphoreTake(audioMutex, pdMS_TO_TICKS(10)) != pdTRUE)
                goto next_cycle;
            }
          }
        }
        else
        {
          xSemaphoreGive(audioMutex);
          delay(1);
          continue;
        }
      }
      else
      {
        xSemaphoreGive(audioMutex);
        delay(10);
        continue;
      }
      xSemaphoreGive(audioMutex);
    }
    else
    {
      delay(5);
    }

    continue;
  next_cycle:
    xSemaphoreGive(audioMutex);
    delay(1);
  }
}

void updateSampleRate(int rate)
{
  if (!out) return;
  out->SetRate(rate);
  Serial.printf("[updateSampleRate] %d Hz\n", rate);
}

static unsigned long lastHeartbeat = 0;

void txControlTask(void *param)
{
  while (true)
  {
    bool key = (digitalRead(TX_KEY_PIN) == LOW);

    // --- PTT ON ---
    if (txEnabled && key && !txKeyPressed)
    {
      txKeyPressed = true;

      // PTT信号を先に送り、無線機をTX状態にしてから音声ストリームを開始する
      if (useWifiPTT)
      {
        sendWifiPTT(true);
        sendApiPollState(false);  // TX中はrigctldポーリングを停止
      }
      else
        sendHamlibPTT(true);

      startAudioTx();

      tx_ing = true;
      lastHeartbeat = millis();
      setLedColor(255, 0, 0);
    }
    // --- PTT OFF ---
    else if ((!key || !txEnabled) && txKeyPressed)
    {
      txKeyPressed = false;
      stopAudioTx(); // マイクTX停止（audioTxRunning=false でタスクが自己終了）

      // audioTxTask がチャンク終端送信・I2S解放を完了するまで待つ（最大3秒）
      {
        unsigned long t = millis();
        while (audioTxTaskHandle != nullptr && millis() - t < 3000)
          vTaskDelay(50 / portTICK_PERIOD_MS);
      }
      // ラジオ側バッファ消化のための追加遅延
      vTaskDelay(500 / portTICK_PERIOD_MS);

      if (useWifiPTT)
      {
        sendWifiPTT(false);
        sendApiPollState(true);   // ポーリング再開
        setLedColor(0, 0, 255);
      }
      else
      {
        sendHamlibPTT(false);
        setLedColor(0, 255, 0);
      }
      tx_ing = false;
    }

    // --- heartbeat ---
    if (tx_ing && millis() - lastHeartbeat > 500)
    {
      if (useWifiPTT)
      {
        sendWifiPTT(true);
        sendApiPttHeartbeat();    // FastAPI watchdog heartbeat を更新
      }
      else
        sendHamlibPTT(true);

      lastHeartbeat = millis();
    }

    vTaskDelay(10 / portTICK_PERIOD_MS);
  }
}

void httpSenderTask(void *param)
{
  HTTPClient http;
  WiFiClient client;

  while (true)
  {
    HttpCommand *cmd;

    if (xQueueReceive(httpQueue, &cmd, portMAX_DELAY) == pdTRUE)
    {
      String url = "http://" + HostName + ":" + String(apiPort) + cmd->path;

      http.begin(client, url);
      http.setReuse(false);
      http.setTimeout(2000);
      http.addHeader("Connection", "close");
      http.addHeader("Content-Type", "application/x-www-form-urlencoded");
      if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);

      http.POST(cmd->body);

      http.end();

      delete cmd; // ★ ここで安全に破棄
    }
  }
}

/*
void sendPtt(bool on)
{
  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/ptt";
  cmd->body = "state=" + String(on ? 1 : 0);

  xQueueSend(httpQueue, &cmd, 0);
}
*/

void sendHamlibPTT(bool on)
{
  // PTT ON: rigctld タイムアウト時にサーバーが 500 を返すため最大5回リトライ
  int maxRetry = on ? 5 : 1;
  for (int attempt = 0; attempt < maxRetry; ++attempt)
  {
    HTTPClient http;
    http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/ptt");
    http.setTimeout(800);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
    int code = http.POST(String("state=") + (on ? "1" : "0"));
    http.end();
    Serial.printf("🔧 HamlibPTT %s attempt=%d code=%d\n", on ? "ON" : "OFF", attempt + 1, code);
    if (code == 200) break;
    if (on && attempt < maxRetry - 1)
      vTaskDelay(200 / portTICK_PERIOD_MS);
  }

  // --- poll 停止/再開 ---
  HTTPClient poll;
  poll.begin("http://" + HostName + ":" + String(apiPort) + "/radio/poll");
  poll.setTimeout(800);
  poll.addHeader("Content-Type", "application/x-www-form-urlencoded");
  if (!apiKey.isEmpty()) poll.addHeader("X-API-Key", apiKey);
  poll.POST(String("state=") + (on ? "0" : "1"));
  poll.end();
}

void sendWifiPTT(bool on)
{
  static WiFiUDP udp; // ★ 毎回 begin() しない（高速化）
  static bool udpInit = false;

  if (!udpInit)
  {
    udp.begin(0); // 任意ポート
    udpInit = true;
  }

  // --- DNS 解決 ---
  IPAddress pttIP;
  if (!WiFi.hostByName(pttHost.c_str(), pttIP))
  {
    Serial.println("❌ WifiPTT: DNS failed");
    return;
  }

  // --- PTT パケット作成 ---
  byte pkt[10];
  pkt[0] = on ? 0x01 : 0x00;
  pkt[1] = 0x01; // trxSel 固定

  uint64_t nowMs = millis();
  for (int i = 0; i < 8; i++)
    pkt[2 + i] = (nowMs >> (56 - i * 8)) & 0xFF;

  // ON は 1 回、OFF は 5 回 (60ms 間隔) で重複除去を回避しながら確実に届ける
  int repeat = on ? 1 : 5;
  int interval = on ? 0 : 60;

  for (int i = 0; i < repeat; i++)
  {
    // OFF の 2 回目以降はタイムスタンプを更新して送る
    if (!on && i > 0)
    {
      uint64_t nowMs2 = millis();
      for (int j = 0; j < 8; j++)
        pkt[2 + j] = (nowMs2 >> (56 - j * 8)) & 0xFF;
    }
    udp.beginPacket(pttIP, pttPort);
    udp.write(pkt, 10);
    udp.endPacket();
    if (i < repeat - 1) delay(interval);
  }

  Serial.printf("📡 WifiPTT %s sent\n", on ? "ON" : "OFF");
}

// WiFi PTT専用: FastAPI のrigctldポーリングを停止/再開する
static void sendApiPollState(bool enabled)
{
  HTTPClient http;
  http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/poll");
  http.setTimeout(800);
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
  http.POST(String("state=") + (enabled ? "1" : "0"));
  http.end();
}

// WiFi PTT専用: FastAPI PTT watchdog heartbeat を更新する
static void sendApiPttHeartbeat()
{
  HTTPClient http;
  http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/ptt_heartbeat");
  http.setTimeout(800);
  if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
  http.POST("");
  http.end();
}

void initLed()
{
  pixels.begin();
  pixels.setBrightness(50);
}

void clearLed()
{
  pixels.clear();
  pixels.show();
}

void setLedColor(uint8_t r, uint8_t g, uint8_t b)
{
  pixels.setPixelColor(0, pixels.Color(r, g, b));
  pixels.show();
}

// ============================================================
//  マイクTX (AudioTx)
// ============================================================
void audioTxTask(void *param)
{
  const int RATE     = 8000;
  const int BUF_SIZE = 512;

  i2s_config_t rx_cfg = {
      .mode              = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
      .sample_rate       = RATE,
      .bits_per_sample   = I2S_BITS_PER_SAMPLE_16BIT,
      .channel_format    = I2S_CHANNEL_FMT_ONLY_LEFT,
      .communication_format = I2S_COMM_FORMAT_STAND_I2S,
      .intr_alloc_flags  = 0,
      .dma_buf_count     = 4,    // 4×256=64ms: 起動速度と安定性のバランス
      .dma_buf_len       = 256,
      .use_apll          = false,
      .tx_desc_auto_clear = false,
      .fixed_mclk        = 0,
  };
  i2s_pin_config_t rx_pins = {
      .mck_io_num  = I2S_PIN_NO_CHANGE,
      .bck_io_num  = SYS_I2S_SCLK_PIN,
      .ws_io_num   = SYS_I2S_LRCK_PIN,
      .data_out_num = I2S_PIN_NO_CHANGE,
      .data_in_num  = SYS_I2S_DIN_PIN,
  };

  if (i2s_driver_install(I2S_NUM_0, &rx_cfg, 0, NULL) != ESP_OK)
  {
    Serial.println("[audioTx] I2S RX install failed");
    audioTxRunning = false;
    audioTxTaskHandle = nullptr;
    vTaskDelete(NULL);
    return;
  }
  i2s_set_pin(I2S_NUM_0, &rx_pins);

  // HTTP POST (chunked transfer) 開始
  WiFiClient client;
  if (!client.connect(HostName.c_str(), apiPort))
  {
    Serial.println("[audioTx] connect failed");
    i2s_driver_uninstall(I2S_NUM_0);
    audioTxRunning = false;
    audioTxTaskHandle = nullptr;
    vTaskDelete(NULL);
    return;
  }

  client.print("POST /radio/audio_tx?rate=" + String(RATE) + " HTTP/1.1\r\n");
  client.print("Host: " + HostName + "\r\n");
  client.print("Content-Type: application/octet-stream\r\n");
  client.print("Transfer-Encoding: chunked\r\n");
  client.print("Connection: close\r\n");
  if (!apiKey.isEmpty()) client.print("X-API-Key: " + apiKey + "\r\n");
  client.print("\r\n");

  uint8_t buf[BUF_SIZE];
  size_t  bytesRead = 0;
  unsigned long totalBytes = 0;
  bool firstRead = true;

  Serial.println("[audioTx] streaming started");
  while (audioTxRunning)
  {
    if (!client.connected())
    {
      Serial.println("[audioTx] server closed connection");
      break;
    }
    esp_err_t err = i2s_read(I2S_NUM_0, buf, BUF_SIZE, &bytesRead, pdMS_TO_TICKS(50));
    if (err == ESP_OK && bytesRead > 0)
    {
      // 最初の数回は読み取りデータをログ出力（無音チェック）
      if (firstRead)
      {
        bool hasAudio = false;
        for (size_t i = 0; i < bytesRead; i++) { if (buf[i] != 0) { hasAudio = true; break; } }
        Serial.printf("[audioTx] firstRead %u bytes, hasAudio=%d, sample0=%d\n",
                      bytesRead, hasAudio, (int16_t)((buf[1]<<8)|buf[0]));
        firstRead = false;
      }
      char hexLen[12];
      snprintf(hexLen, sizeof(hexLen), "%X\r\n", (unsigned)bytesRead);
      client.print(hexLen);
      client.write(buf, bytesRead);
      client.print("\r\n");
      totalBytes += bytesRead;
    }
    else if (err != ESP_OK)
    {
      Serial.printf("[audioTx] i2s_read err=0x%x\n", err);
    }
  }
  Serial.printf("[audioTx] loop ended, totalBytes=%lu\n", totalBytes);

  // チャンク終端
  client.print("0\r\n\r\n");
  client.stop();

  i2s_driver_uninstall(I2S_NUM_0);
  Serial.println("[audioTx] stopped");

  audioTxRunning    = false;
  lastStreamAttempt = 0;   // loop()に即時SPK再接続を促す
  audioTxTaskHandle = nullptr;
  vTaskDelete(NULL);
}

void startAudioTx()
{
  if (audioTxRunning) return;
  audioTxRunning = true;   // loop()がstreamTaskを起動しないよう最初に設定

  // MIC有効化をstopPlayback()より先に行い、I2C処理をSPK停止と並行させる
  Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0x00); Wire1.endTransmission(); // ADC パワーアップ
  es8388.setMicGain(MIC_GAIN_24DB);          // PGA ゲインを TX 用に回復
  device.setMICStatus(AUDIO_MIC_OPEN);
  es8388.setADCInput(ADC_INPUT_LINPUT1_RINPUT1);

  stopPlayback(); // RX停止・I2Sドライバ解放（out が null の場合は解放しない）
  i2s_driver_uninstall(I2S_NUM_0); // out が null だった場合の残存ドライバを確実に解放
  connected = false;

  xTaskCreatePinnedToCore(audioTxTask, "audioTxTask", 8192, NULL, 5, &audioTxTaskHandle, 1);
  Serial.println("[audioTx] startAudioTx()");
}

void stopAudioTx()
{
  if (!audioTxRunning) return;
  audioTxRunning = false;

  // MIC入力は呼び出し元のタスクコンテキストで無効化（taskから呼ぶとWire競合でクラッシュするため）
  device.setMICStatus(AUDIO_MIC_CLOSE);
  es8388.setMicGain(MIC_GAIN_0DB);           // PGA ゲインを 0dB に戻してノイズ床を下げる
  Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0xFF); Wire1.endTransmission(); // ADC パワーダウン

  connected = false;
  Serial.println("[audioTx] stopAudioTx()");
}
