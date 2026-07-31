/****************************************************
 *  Wifi_Rig_CTRL main.cpp
 *  Ver2.30
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include <M5GFX.h>
#include "ui_display.h"
#include "ui_core.h"
#include "globals.h"
#include "civ_client.h"
#include "gps_relay.h"
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
unsigned long wifiConnectedSinceMs = 0;

// ★ オーディオポインタ保護用 mutex
SemaphoreHandle_t audioMutex = nullptr;

AudioI2c device;
// setup() より前（グローバルコンストラクタ実行時、FreeRTOSスケジューラ起動前）に
// ES8388 のコンストラクタが Wire1.begin() を直接呼んでしまうと、まだ準備が
// 整っていないランタイムに対して I2C 初期化を行うことになり、内部状態が破壊されて
// 後段の ADC 初期化が "ADC: CONFLICT! driver_ng is not allowed to be used with
// the legacy driver" で abort() する原因になっていた。
// そのため実体の生成を setup() 内まで遅延させる。
ES8388 *es8388 = nullptr;

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
void sendHamlibPollState(bool pollEnabled);
static void sendApiPollState(bool enabled);
static void sendApiPttHeartbeat();
String connectToRasPiServices();

#ifdef M5TOUGH
volatile int encoderPos = 0;
static volatile uint8_t encoderState = 0;

// 標準的なクアドラチュアデコード遷移表。
// (直前2bit<<2 | 現在2bit) をインデックスとし、正常な1ステップ遷移だけ±1を返す。
// チャタリングやノイズによる不正な遷移（例: 00→11）は0を返して無視することで、
// 「左に回してもプラス判定になる」ような瞬間的な誤方向判定を防ぐ。
static const int8_t QUAD_ENC_TABLE[16] = {
   0, -1,  1,  0,
   1,  0,  0, -1,
  -1,  0,  0,  1,
   0,  1, -1,  0
};

void IRAM_ATTR encoderISR()
{
  uint8_t a = digitalRead(GPIO_A_ENCODER);
  uint8_t b = digitalRead(GPIO_B_ENCODER);
  uint8_t newState = (a << 1) | b;
  encoderState = ((encoderState << 2) | newState) & 0x0F;
  encoderPos -= QUAD_ENC_TABLE[encoderState]; // 実機の回転方向に合わせて符号反転
}
#endif

void setup()
{
#ifdef AUDIO_SRC_SELECTABLE
  // SPK出力先/Mic入力先(内蔵/ES8388)の設定はM5.begin()より前に読み込んでおく必要がある
  prefs.begin("device", true);
  useExternalSpk = prefs.getBool("useExtSpk", false);
  useExternalMic = prefs.getBool("useExtMic", false);
  prefs.end();
#endif

  auto cfg = M5.config();

  // --- M5 本体の初期化 ---
#ifdef AUDIO_SRC_SELECTABLE
  // SPK/Mic共に設定画面(PTT Method)で内蔵/ES8388(Module Audio)を切替可能。
  cfg.internal_mic = !useExternalMic;
  cfg.internal_spk = !useExternalSpk;
  cfg.external_spk = useExternalSpk;
#ifdef M5CORE2
  // ボード自動判定が失敗した場合に ADC 系ボードへ誤フォールバックして
  // "ADC: CONFLICT! driver_ng is not allowed to be used with the legacy driver"
  // で無限リブートするのを防ぐため、Core2 (AXP192, I2C経由) を明示する。
  cfg.fallback_board = m5::board_t::board_M5Tough;
#endif
#else
  cfg.internal_mic = false;
  cfg.internal_spk = false;
  cfg.external_spk = true;
  // ボード自動判定が失敗した場合に ADC 系ボードへ誤フォールバックして
  // "ADC: CONFLICT! driver_ng is not allowed to be used with the legacy driver"
  // で無限リブートするのを防ぐため、Core2/Tough (AXP192, I2C経由) を明示する。
  cfg.fallback_board = m5::board_t::board_M5Tough;
#endif

  Serial.begin(115200);

  M5.begin(cfg);

#ifdef M5TOUGH
  // ★ 画面を上下逆（180度回転）にする
  M5.Display.setRotation((M5.Display.getRotation() + 2) % 4);
#endif

  // ★ 夜間モード復帰用に、起動直後(=通常時)のバックライト輝度を記憶しておく
  nightBrightness = M5.Display.getBrightness();

  // ★ WiFiモデムスリープを無効化（省電力モードがTCP接続失敗の原因になるため）
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);

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

  // Port C: G13-GND間にトグルスイッチを接続。GNDは実ピンなので仮想GND不要。
  pinMode(TX_KEY_PIN, INPUT_PULLUP);

#ifdef M5TOUGH
  pinMode(GPIO_A_ENCODER, INPUT_PULLUP); // Encoder A
  pinMode(GPIO_B_ENCODER, INPUT_PULLUP); // Encoder
  attachInterrupt(GPIO_A_ENCODER, encoderISR, CHANGE);
  attachInterrupt(GPIO_B_ENCODER, encoderISR, CHANGE);
#endif

#ifdef AUDIO_SRC_SELECTABLE
  // SPK出力先とMic入力先は互いに独立して内蔵/ES8388を選べる(4通りの組み合わせ全て可)。
  if (useExternalSpk || useExternalMic)
  {
    // ES8388(Module Audio)のI2C側初期化。SPK出力・Mic入力のどちらかで使う場合に必要。
    device.begin(&Wire1, I2C_SDA_AUDIO, I2C_SCL_AUDIO);
    device.setHPMode(AUDIO_HPMODE_NATIONAL);

    es8388 = new ES8388(&Wire1, I2C_SDA_AUDIO, I2C_SCL_AUDIO);
    if (!es8388->init())
    {
      Serial.println("ES8388 init failed!");
    }
    else
    {
      Serial.println("ES8388 init OK!");
    }

    es8388->setADCVolume(100);
    es8388->setDACVolume(80);
    es8388->setDACOutput(DAC_OUTPUT_OUT1);
    es8388->setBitsSample(ES_MODULE_ADC, BIT_LENGTH_16BITS);
    es8388->setSampleRate(SAMPLE_RATE_48K);
    // ★ setADCInput()は内部でI2C読み出し(read-modify-write)を行い、稀にバスタイムアウトで
    //   1秒近く止まることがあるため、毎回のTX開始時ではなく起動時に一度だけ設定しておく。
    es8388->setADCInput(ADC_INPUT_LINPUT1_RINPUT1);

    // ホワイトノイズ抑制: init() が 0xd0 でマイク入力をスピーカーにバイパスするのを防ぐ
    Wire1.beginTransmission(0x10); Wire1.write(0x27); Wire1.write(0x90); Wire1.endTransmission();
    Wire1.beginTransmission(0x10); Wire1.write(0x2a); Wire1.write(0x90); Wire1.endTransmission();
    // 起動時点(=RX想定)はマイクを閉じておく。実際のOPEN/CLOSE切替はTX開始/終了時に行う。
    device.setMICStatus(AUDIO_MIC_CLOSE);
    es8388->setMicGain(MIC_GAIN_0DB);
    Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0xFF); Wire1.endTransmission();
  }

  if (!useExternalSpk)
  {
    // 本体内蔵SPKを使用する場合の初期化。
    {
      // streamTask/txControlTask/audioTxTask等の高優先度タスクは全てcore1に
      // pinしているため、Speakerの内部タスクはcore0へ逃がして競合(音切れ)を防ぐ。
      auto spk_cfg = M5.Speaker.config();
      spk_cfg.task_pinned_core = 0;
      spk_cfg.task_priority = 5;
      spk_cfg.dma_buf_count = 8;
      spk_cfg.dma_buf_len = 512;
      M5.Speaker.config(spk_cfg);
    }
    M5.Speaker.setVolume(200);
    M5.Speaker.begin();
  }

  if (!useExternalMic)
  {
    // 本体内蔵マイクは既定ゲイン(magnification=2)だと無線機のDATAモードを
    // 継続して駆動するには信号が弱いため、ゲインを上げる。
    auto mic_cfg = M5.Mic.config();
    mic_cfg.magnification = 24;
    M5.Mic.config(mic_cfg);
  }
  M5.Mic.end();
#else
  device.begin(&Wire1, I2C_SDA_AUDIO, I2C_SCL_AUDIO);
  device.setHPMode(AUDIO_HPMODE_NATIONAL);

  es8388 = new ES8388(&Wire1, I2C_SDA_AUDIO, I2C_SCL_AUDIO);
  if (!es8388->init())
  {
    Serial.println("ES8388 init failed!");
  }
  else
  {
    Serial.println("ES8388 init OK!");
  }

  es8388->setADCVolume(100);
  es8388->setDACVolume(80);
  es8388->setDACOutput(DAC_OUTPUT_OUT1);
  es8388->setBitsSample(ES_MODULE_ADC, BIT_LENGTH_16BITS);
  es8388->setSampleRate(SAMPLE_RATE_48K);
  // ★ setADCInput()は内部でI2C読み出し(read-modify-write)を行い、稀にバスタイムアウトで
  //   1秒近く止まることがあるため、毎回のTX開始時ではなく起動時に一度だけ設定しておく。
  es8388->setADCInput(ADC_INPUT_LINPUT1_RINPUT1);

  // ホワイトノイズ抑制
  // 1) LI2LO/RI2RO をクリア: init() が 0xd0 でマイク入力をスピーカーにバイパスするのを防ぐ
  Wire1.beginTransmission(0x10); Wire1.write(0x27); Wire1.write(0x90); Wire1.endTransmission();
  Wire1.beginTransmission(0x10); Wire1.write(0x2a); Wire1.write(0x90); Wire1.endTransmission();
  // 2) MIC を物理的に切断（デフォルトは OPEN = 24dB PGA がアンビエントノイズを増幅）
  device.setMICStatus(AUDIO_MIC_CLOSE);
  // 3) ADC PGA ゲインを 0dB に下げる（init() は 24dB に設定）
  es8388->setMicGain(MIC_GAIN_0DB);
  // 4) ADC モジュール全体をパワーダウン（SPK 受信時は ADC 不要）
  Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0xFF); Wire1.endTransmission();
#endif

  canvas.setColorDepth(8);       // 軽量化
  canvas.createSprite(320, 240); // CoreS3SEの画面サイズ
  loadAutoSkipSetting();         // 起動画面のタイムアウト既定値(Normal/Skip)を復元
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
  loadCivSettings();  // CI-V WiFi直結設定を復元
  loadPttSettings();     // dayMode(日中表示モード)もここで復元される
  if (dayMode) M5.Display.setBrightness(255); // 前回終了時が日中モードなら輝度を復元
  civStartCmdTask();  // CI-Vコマンドキュー処理タスク(useCIV=falseの間はキューが空のまま待機するだけ)

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

#ifdef AUDIO_SRC_SELECTABLE
      // I2S_NUM_0はSPK外部出力(ES8388)選択時のみ使用。内蔵SPK/マイクはI2S_NUM_1のため触れない。
      if (useExternalSpk) {
        i2s_driver_uninstall(I2S_NUM_0);
      }
#else
      // I2S 既存ドライバ解放のみ（インストールは streamTask/audioTxTask が行う）
      i2s_driver_uninstall(I2S_NUM_0);
#endif
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
      if (!apiKey.isEmpty()) skipHttp.addHeader("X-API-Key", apiKey);
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
        if (!apiKey.isEmpty()) sHttp.addHeader("X-API-Key", apiKey);
        if (sHttp.GET() == 200) { ready = true; sHttp.end(); break; }
        sHttp.end();
      }

      if (ready)
      {
        // APRS の RigID / CAT Device を更新
        aprsRigID    = String(rigIds[selRig]);
        aprsCatDevice = savedCat;

        // ★ 飛ばしモードでも、この機種がVFO A/B型かMAIN/SUB型か・現在どちら側かを取得する
        //   (通常の接続フローと同じくrig_connect.cppと共通のfetchVfoState()を使う)
        fetchVfoState();

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

  // ★ デバッグ: endPacket() ENOMEMエラーの原因切り分け用。ヒープ/WiFi TXバッファの
  //   枯渇が本当に起きているかを5秒毎に記録する。
  {
    static unsigned long dbgLastHeapDump = 0;
    if (millis() - dbgLastHeapDump > 5000)
    {
      Serial.printf("[%lu][heap][dbg] free=%u minFree=%u maxAlloc=%u wifiStatus=%d\n",
                    millis(), ESP.getFreeHeap(), ESP.getMinFreeHeap(), ESP.getMaxAllocHeap(), (int)WiFi.status());
      dbgLastHeapDump = millis();
    }
  }

  // ★ WiFi接続後、Android(Tasker)への位置情報ポーリングを一度だけ起動する。
  //   GPS中継はRaspberry Pi(/gps)への送信が主目的で、CI-Vモード(Piを経由しない)
  //   では無意味なだけでなく、定期的なHTTP接続試行がネットワークスタックの
  //   リソースを消費し、CI-V接続時のUDP送信(endPacket)がENOMEMで失敗しやすく
  //   なる一因になっていた可能性があるため、CI-Vモードでは起動しない。
  if (WiFi.status() == WL_CONNECTED)
  {
    if (!useCIV) startGpsPolling();
    if (wifiConnectedSinceMs == 0) wifiConnectedSinceMs = millis();
  }
  else
  {
    wifiConnectedSinceMs = 0; // 切断されたらリセット(再接続時に再度待つ)
  }

  if (appState != lastAppState || lastspkEnabled != spkEnabled)
  {
    if (appState == STATE_MAIN_UI && !connected && !audioTxRunning && audioTxTaskHandle == nullptr && streamTaskHandle == nullptr && spkEnabled == true && !useCIV)
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

  if (appState == STATE_MAIN_UI && !connected && !audioTxRunning && audioTxTaskHandle == nullptr && streamTaskHandle == nullptr && spkEnabled == true && !useCIV)
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

  case STATE_APRS_RECEIVED:
    handleAprsReceivedScreen();
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
    // ★ CI-Vモードは1回のfetchRigStatus()で周波数/モード/Sメーター/TX状態/パワー/
    //   スケルチの6項目を順にcivExchange()で問い合わせる(往復応答待ち)。200ms間隔だと
    //   無線機のCI-V処理+音声ストリーミングと合わさって無線機側の負荷が高くなり、
    //   無線機自身がWiFi接続を切断してしまう事象が実機で確認された(IC-705本体の
    //   画面でWLAN切断表示、距離50cm・電波強度良好でも発生)。無線機側の負担を
    //   減らすため、CI-Vモードはポーリング間隔を500msに緩める。
    vTaskDelay((useCIV ? 500 : 200) / portTICK_PERIOD_MS);
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

#ifdef AUDIO_SRC_SELECTABLE
  // SPK出力先: 本体内蔵ならfalse、Module Audio(ES8388)経由ならtrue
  bool spkExternal = useExternalSpk;
#else
  const bool spkExternal = true;
#endif

  if (spkExternal) {
    // I2S TX インストール (Module Audio ES8388 経由でSPK出力)
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
  }

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
    if (spkExternal) i2s_driver_uninstall(I2S_NUM_0);
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
    if (spkExternal) i2s_driver_uninstall(I2S_NUM_0);
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
  // M5.Speaker.playRaw()はバッファをコピーせず直接参照するため、単一バッファを
  // 使い回すと前回分の再生完了前に上書きしてしまい音切れの原因になる。
  // 3面バッファを回して、再生中のバッファに触れないようにする(i2s_write経路でも無害)。
  static int16_t stereoBufPool[3][PCM_BYTES];
  int stereoBufIdx = 0;

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
      int16_t *stereoBuf = stereoBufPool[stereoBufIdx];
      stereoBufIdx = (stereoBufIdx + 1) % 3;
      for (int i = 0; i < samples; i++) {
        float s = src[i] * currentVolume;
        if (s >  32767.0f) s =  32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        stereoBuf[i * 2]     = (int16_t)s;
        stereoBuf[i * 2 + 1] = (int16_t)s;
      }
      if (spkExternal) {
        size_t written;
        // ★ タイムアウトを短くし、PTT切替時にstreamTaskが素早く抜けられるようにする
        i2s_write(I2S_NUM_0, stereoBuf, (size_t)(samples * 4), &written, pdMS_TO_TICKS(20));
      }
#ifdef AUDIO_SRC_SELECTABLE
      else {
        M5.Speaker.playRaw(stereoBuf, (size_t)(samples * 2), (uint32_t)samplingRates[selSampling], true, 1, 0);
      }
#endif

    } else {
      if (avail <= 0) { vTaskDelay(1 / portTICK_PERIOD_MS); continue; }
      int toRead = min((int)avail, (int)PCM_BYTES);
      int n = client.read(pcmBuf, toRead);
      if (n <= 0) { vTaskDelay(1 / portTICK_PERIOD_MS); continue; }

      int samples = n / 2;
      int16_t *src = (int16_t *)pcmBuf;
      int16_t *stereoBuf = stereoBufPool[stereoBufIdx];
      stereoBufIdx = (stereoBufIdx + 1) % 3;
      for (int i = 0; i < samples; i++) {
        float s = src[i] * currentVolume;
        if (s >  32767.0f) s =  32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        stereoBuf[i * 2]     = (int16_t)s;
        stereoBuf[i * 2 + 1] = (int16_t)s;
      }
      if (spkExternal) {
        size_t written;
        // ★ タイムアウトを短くし、PTT切替時にstreamTaskが素早く抜けられるようにする
        i2s_write(I2S_NUM_0, stereoBuf, (size_t)(samples * 4), &written, pdMS_TO_TICKS(20));
      }
#ifdef AUDIO_SRC_SELECTABLE
      else {
        M5.Speaker.playRaw(stereoBuf, (size_t)(samples * 2), (uint32_t)samplingRates[selSampling], true, 1, 0);
      }
#endif
    }
  }

  client.stop();
  if (spkExternal) i2s_driver_uninstall(I2S_NUM_0);
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

  // ★ WiFiClient::connect()内のDNS解決がブロックしていると、isReconnecting=trueを
  //   streamTaskが確認できるのは接続試行の合間だけなので、1秒待っても終わらないことがある。
  //   放置すると優先度10のタスクがCore1を専有し続け、他タスクを飢餓状態にして
  //   割り込みウォッチドッグでクラッシュする(実機で確認済み)。強制的に片付ける。
  if (streamTaskHandle != nullptr)
  {
    Serial.println("[stopPlayback] streamTask did not exit in time, force-deleting");
    vTaskDelete(streamTaskHandle);
    streamTaskHandle = nullptr;
#ifdef AUDIO_SRC_SELECTABLE
    if (useExternalSpk) i2s_driver_uninstall(I2S_NUM_0);
#else
    i2s_driver_uninstall(I2S_NUM_0);
#endif
  }

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
  Serial.printf("[%lu]再生停止\n", millis());
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
  static bool lastKeyDbg = false;
  // ★ 以前TX_KEY_PINの読み取りが不安定に見えた(押し続けても数秒でOFF扱いになる)ため
  //   Button_Classで3秒の非対称デバウンスを入れていたが、真因は無線機のUSB端子に
  //   Raspberry Pi(Hamlibモード用)が接続されたまま同時にCI-Vモードでも制御しようと
  //   していたことによる競合と判明(Piとの接続を外すことで解消)。GPIO読み取り自体は
  //   正常だったため、デバウンスを外し単純な直接読み取りに戻す(3秒デバウンスにより
  //   フットスイッチを離してから反応が3秒遅れる新たな不具合が出ていたため)。
  while (true)
  {
    bool key = (digitalRead(TX_KEY_PIN) == LOW);
    if (key != lastKeyDbg)
    {
      Serial.printf("[%lu][txkey] key=%d txEnabled=%d txKeyPressed=%d\n", millis(), key, txEnabled, txKeyPressed);
      lastKeyDbg = key;
    }

    // --- PTT ON ---
    if (txEnabled && key && !txKeyPressed)
    {
      txKeyPressed = true;

      // PTT信号を先に送り、無線機をTX状態にしてから音声ストリームを開始する
      if (useCIV)
      {
        bool pttOk = civSetPtt(true);
        Serial.printf("[%lu][txkey] civSetPtt(true) result=%d\n", millis(), pttOk);
      }
      else if (useWifiPTT)
      {
        sendWifiPTT(true);
        sendApiPollState(false);  // TX中はrigctldポーリングを停止
      }
      else
        sendHamlibPTT(true);

      // ★ マイク送信(Pi経由)はCAT制御方式(CI-V/Hamlib)と独立して動作するため、
      //   CI-Vモードでも開始する
      startAudioTx();

      // ★ rigctldポーリング停止はマイク音声開始をブロックしないよう後回しにする
      if (!useCIV && !useWifiPTT)
        sendHamlibPollState(false);

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

      if (useCIV)
      {
        // ★ PTT OFFは無線機がTX状態のまま固着することを防ぐ安全上重要なコマンドのため、
        //   civExchange()のタイムアウト失敗時は即座に複数回リトライする(単純なfire-and-
        //   forgetの取りこぼしや、一時的な輻輳による応答遅延に対する保険)。
        bool pttOffOk = civSetPtt(false);
        for (int retry = 0; !pttOffOk && retry < 3; retry++)
        {
          Serial.printf("[%lu][txkey] civSetPtt(false) failed, retry %d/3\n", millis(), retry + 1);
          pttOffOk = civSetPtt(false);
        }
        if (!pttOffOk)
        {
          Serial.println("[txkey] WARNING: civSetPtt(false) failed after retries - radio may be stuck in TX!");
        }
        setLedColor(0, 255, 0);
      }
      else if (useWifiPTT)
      {
        sendWifiPTT(false);
        sendApiPollState(true);   // ポーリング再開
        setLedColor(0, 0, 255);
      }
      else
      {
        sendHamlibPTT(false);
        sendHamlibPollState(true); // ポーリング再開
        setLedColor(0, 255, 0);
      }
      tx_ing = false;
    }

    // --- heartbeat ---
    // CI-V はctrl/dataタスクの自前keepaliveでセッション維持するため、
    // ここでの追加送信は不要(civSetPtt(true)の再送も行わない)。
    if (tx_ing && !useCIV && millis() - lastHeartbeat > 500)
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
}

// ★ rigctldポーリングの一時停止/再開。PTT ON直後の音声開始をブロックしないよう、
//   sendHamlibPTT()から分離し、startAudioTx()の後に呼べるようにしている。
void sendHamlibPollState(bool pollEnabled)
{
  HTTPClient poll;
  poll.begin("http://" + HostName + ":" + String(apiPort) + "/radio/poll");
  poll.setTimeout(800);
  poll.addHeader("Content-Type", "application/x-www-form-urlencoded");
  if (!apiKey.isEmpty()) poll.addHeader("X-API-Key", apiKey);
  poll.POST(String("state=") + (pollEnabled ? "1" : "0"));
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

  // --- DNS 解決(キャッシュ) ---
  // WiFi.hostByName()(mDNS解決含む)は数百ms〜数秒ブロックすることがあり、
  // PTT保持中は txControlTask から500ms毎に呼ばれるため、毎回解決すると
  // audioTxTask のI2S DMAバッファ(128ms分)を溢れさせ音声が途切れる原因になっていた。
  // ホスト名が変わらない限り、一度解決したIPを使い回す(60秒毎にのみ再解決)。
  static String cachedHost;
  static IPAddress cachedIp;
  static unsigned long lastResolveMs = 0;
  const unsigned long RESOLVE_TTL_MS = 60000;

  bool needResolve = (cachedHost != pttHost) || (cachedIp == IPAddress(0, 0, 0, 0)) ||
                      (millis() - lastResolveMs > RESOLVE_TTL_MS);

  if (needResolve)
  {
    // WiFi.hostByName()は".local"が付いていないとmDNS解決を試みないため、
    // 必要なら自動的に補完する(IPアドレスがそのまま入力されている場合は付けない)。
    String pttResolveHost = pttHost;
    IPAddress rawPttIp;
    if (!pttResolveHost.endsWith(".local") && !rawPttIp.fromString(pttResolveHost))
    {
      pttResolveHost += ".local";
    }

    IPAddress resolvedIp;
    if (WiFi.hostByName(pttResolveHost.c_str(), resolvedIp))
    {
      cachedHost = pttHost;
      cachedIp = resolvedIp;
      lastResolveMs = millis();
    }
    else if (cachedIp == IPAddress(0, 0, 0, 0))
    {
      // キャッシュも無い初回失敗時のみ送信を諦める(TX中はキャッシュを使い続ける)
      Serial.println("❌ WifiPTT: DNS failed");
      return;
    }
  }

  IPAddress pttIP = cachedIp;

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
  const int BUF_SIZE = 512;

#ifdef AUDIO_SRC_SELECTABLE
  // Mic入力先: 本体内蔵ならfalse、ES8388(Module Audio)経由ならtrue
  bool micExternal = useExternalMic;
#else
  const bool micExternal = true;
#endif

  // ★ 外部(ES8388/raw I2S)は画面上のサンプリングレート設定に追従できるが、
  //   内蔵マイク(M5.Mic非同期エンジン)は8000Hz固定とする。
  const int RATE = micExternal
                       ? ((samplingRates[selSampling] > 0) ? samplingRates[selSampling] : 8000)
                       : 8000;

  if (micExternal) {
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
        // ES8388のADCはMCLKが供給されないと正常にサンプリングできない
        // (RX単独構成だとMCLKが供給されず無音/固定値になっていたため明示的に指定)
        .mck_io_num  = SYS_I2S_MCLK_PIN,
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
  }

  // HTTP POST (chunked transfer) 開始
  WiFiClient client;
  if (!client.connect(HostName.c_str(), apiPort))
  {
    Serial.println("[audioTx] connect failed");
#ifdef AUDIO_SRC_SELECTABLE
    if (!micExternal) M5.Mic.end();
    if (!useExternalSpk) M5.Speaker.begin();
#endif
    if (micExternal) i2s_driver_uninstall(I2S_NUM_0);
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
  int dbgReadCount = 0; // DEBUG: 一時的な振幅ロギング用カウンタ

#ifdef AUDIO_SRC_SELECTABLE
  // M5.Mic.record()は非同期(呼び出し即座には録音完了しない)なので、公式サンプル
  // (Microphone.ino)同様にリングバッファへ先読みで発行し続け、数コマ遅れた
  // (=実時間的に確実に録音済みの)スロットを送信に使う。(内蔵マイク使用時のみ)
  const int RING_SLOTS = 6;
  const int RING_LAG   = 3; // 送信は録音発行より何コマ遅らせるか
  static int16_t ring[RING_SLOTS][BUF_SIZE / 2];
  int recIdx = 0;
  int sendIdx = 0;
  const unsigned long frameMs = (unsigned long)((BUF_SIZE / 2) * 1000UL / RATE);
  if (!micExternal) {
    for (int i = 0; i < RING_LAG; i++)
    {
      M5.Mic.record(ring[recIdx], BUF_SIZE / 2, RATE, false);
      recIdx = (recIdx + 1) % RING_SLOTS;
    }
  }
#endif

  // マイク入力(特にES8388)は電源投入直後の数秒間ポップノイズ/不安定な値が乗るため、
  // TX開始直後は送信データを無音化してポップを無線に乗せないようにする。
  const unsigned long MIC_MUTE_MS = 100;
  unsigned long txStartTime = millis();

  Serial.printf("[%lu][audioTx] streaming started\n", millis());
  while (audioTxRunning)
  {
    if (!client.connected())
    {
      Serial.println("[audioTx] server closed connection");
      break;
    }
    esp_err_t err;
    if (micExternal) {
      err = i2s_read(I2S_NUM_0, buf, BUF_SIZE, &bytesRead, pdMS_TO_TICKS(50));
    }
#ifdef AUDIO_SRC_SELECTABLE
    else {
      M5.Mic.record(ring[recIdx], BUF_SIZE / 2, RATE, false);
      recIdx = (recIdx + 1) % RING_SLOTS;
      memcpy(buf, ring[sendIdx], BUF_SIZE);
      sendIdx = (sendIdx + 1) % RING_SLOTS;
      bytesRead = (size_t)BUF_SIZE;
      err = ESP_OK;
      vTaskDelay(pdMS_TO_TICKS(frameMs)); // 実時間ペースに合わせる
    }
#endif
    if (err == ESP_OK && bytesRead > 0)
    {
      if (millis() - txStartTime < MIC_MUTE_MS)
      {
        // TX開始直後のポップノイズ区間: 無音データを送る
        memset(buf, 0, bytesRead);
      }
      // 最初の数回は読み取りデータをログ出力（無音チェック）
      if (firstRead)
      {
        bool hasAudio = false;
        for (size_t i = 0; i < bytesRead; i++) { if (buf[i] != 0) { hasAudio = true; break; } }
        Serial.printf("[%lu][audioTx] firstRead %u bytes, hasAudio=%d, sample0=%d\n",
                      millis(), bytesRead, hasAudio, (int16_t)((buf[1]<<8)|buf[0]));
        firstRead = false;
      }
      // DEBUG: 最初の約2秒分、バッファ全体のmin/max/平均絶対値を出力(マイク振幅の診断用)
      if (dbgReadCount < 60)
      {
        int16_t vmin = 32767, vmax = -32768;
        long sumAbs = 0;
        size_t nSamples = bytesRead / 2;
        int16_t *sp = (int16_t *)buf;
        for (size_t i = 0; i < nSamples; i++)
        {
          int16_t s = sp[i];
          if (s < vmin) vmin = s;
          if (s > vmax) vmax = s;
          sumAbs += abs((int)s);
        }
        Serial.printf("[audioTx][dbg] read=%d min=%d max=%d avgAbs=%ld\n",
                      dbgReadCount, vmin, vmax, (nSamples ? sumAbs / (long)nSamples : 0));
        dbgReadCount++;
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

#ifdef AUDIO_SRC_SELECTABLE
  // ループが完全に停止してから切り替えることで、record()実行中の競合を避ける
  if (!micExternal) M5.Mic.end();
  if (!useExternalSpk) M5.Speaker.begin();
#endif
  if (micExternal) i2s_driver_uninstall(I2S_NUM_0);
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

  Serial.printf("[%lu][audioTx] startAudioTx() enter\n", millis());

#ifdef AUDIO_SRC_SELECTABLE
  if (useExternalMic)
  {
    // ES8388のマイクを有効化(TX用にPGAゲインを上げる)。ADC入力選択は起動時に設定済み。
    Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0x00); Wire1.endTransmission(); // ADC パワーアップ
    es8388->setMicGain((es_mic_gain_t)es8388MicGainIdx);
    device.setMICStatus(AUDIO_MIC_OPEN);
  }
#else
  // MIC有効化をstopPlayback()より先に行い、I2C処理をSPK停止と並行させる
  // (ADC入力選択は起動時に設定済み。毎回I2C読み出しを伴うためここでは呼ばない)
  Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0x00); Wire1.endTransmission(); // ADC パワーアップ
  es8388->setMicGain((es_mic_gain_t)es8388MicGainIdx); // PGA ゲインを TX 用に回復
  device.setMICStatus(AUDIO_MIC_OPEN);
#endif

  Serial.printf("[%lu][audioTx] mic I2C done, calling stopPlayback()\n", millis());
  stopPlayback(); // RX停止・I2Sドライバ解放（out が null の場合は解放しない）
  Serial.printf("[%lu][audioTx] stopPlayback() returned\n", millis());

  connected = false;

  if (useCIV)
  {
    // CI-Vモードの音声送信はPi経由ではなく無線機とのUDP直結(civ_client.cpp)で行う。
    // ハードウェア切替(内蔵/外部マイク・スピーカー)も含めて自己完結している。
    civStartTxAudio();
  }
  else
  {
#ifdef AUDIO_SRC_SELECTABLE
    // 本体内蔵マイク/スピーカーは同じI2Sポート(I2S_NUM_1)を共有していて
    // 同時使用できないため、streamTask完全停止後にマイクへ切り替える。
    // (SPKが外部ES8388の場合はI2S_NUM_1を使わないため、内蔵Speakerには触れない)
    if (!useExternalSpk) M5.Speaker.end();
    if (!useExternalMic) M5.Mic.begin();
#else
    i2s_driver_uninstall(I2S_NUM_0); // out が null だった場合の残存ドライバを確実に解放
#endif
    xTaskCreatePinnedToCore(audioTxTask, "audioTxTask", 8192, NULL, 5, &audioTxTaskHandle, 1);
  }
  Serial.printf("[%lu][audioTx] startAudioTx()\n", millis());
}

void stopAudioTx()
{
  if (!audioTxRunning) return;
  audioTxRunning = false;

#ifdef AUDIO_SRC_SELECTABLE
  if (useExternalMic)
  {
    // MIC入力は呼び出し元のタスクコンテキストで無効化（taskから呼ぶとWire競合でクラッシュするため）
    device.setMICStatus(AUDIO_MIC_CLOSE);
    es8388->setMicGain(MIC_GAIN_0DB);
    Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0xFF); Wire1.endTransmission();
  }
#else
  // MIC入力は呼び出し元のタスクコンテキストで無効化（taskから呼ぶとWire競合でクラッシュするため）
  device.setMICStatus(AUDIO_MIC_CLOSE);
  es8388->setMicGain(MIC_GAIN_0DB);           // PGA ゲインを 0dB に戻してノイズ床を下げる
  Wire1.beginTransmission(0x10); Wire1.write(0x03); Wire1.write(0xFF); Wire1.endTransmission(); // ADC パワーダウン
#endif
  // 非CI-V/AUDIO_SRC_SELECTABLE: マイク終了/スピーカー再開は audioTxTask() 末尾で行う
  // (record()実行中に他タスクから切り替えて競合するのを避けるため)
  if (useCIV)
  {
    // CI-VモードはcivStartTxAudio()で自前ハードウェア切替しているため、ここで停止する。
    civStopTxAudio();
  }

  connected = false;
  Serial.println("[audioTx] stopAudioTx()");
}
