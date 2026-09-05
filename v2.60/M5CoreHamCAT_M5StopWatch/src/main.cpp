/****************************************************
 *  Wifi_Rig_CTRL main.cpp
 *  Ver2.5
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
#include "http_sender.h"

TaskHandle_t streamTaskHandle = nullptr;
AppState lastAppState = STATE_WIFI;
bool lastspkEnabled = false;
int retries = 10;
bool connected = false;
volatile bool isReconnecting = false;
bool tx_ing = false;
unsigned long wifiConnectedSinceMs = 0;

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
void stopPlayback();
void txControlTask(void *param);
void httpSenderTask(void *param);
void audioTxTask(void *param);
void sendWifiPTT(bool on);
void sendHamlibPTT(bool on);
void sendHamlibPollState(bool pollEnabled);
static void sendApiPollState(bool enabled);
static void sendApiPttHeartbeat();
String connectToRasPiServices();

void setup()
{
  auto cfg = M5.config();

  // --- M5 本体の初期化 ---
  // M5StopWatchには外部オーディオコーデック(ES8388/Module Audio)ポートが無いため
  // 内蔵Mic/Speakerのみを使う。
  cfg.internal_mic = true;
  cfg.internal_spk = true;
  cfg.external_spk = false;

  Serial.begin(115200);

  // ★ デバッグ用: 起動直後にNVSへ保存されている生のrigId値を読んでおく。
  //   USB-CDCは起動直後の出力をホスト側が取りこぼすことがあるため、ここでは
  //   printせず変数に保持しておき、後で(WiFi接続後など確実にログが取れる
  //   タイミングで)まとめて出力する。
  {
    Preferences dbgPrefs;
    dbgPrefs.begin("device", true);
    dbgBootSavedRigId = dbgPrefs.getInt("rigId", -12345);
    dbgPrefs.end();
  }

  M5.begin(cfg);

  // ★ 夜間モード復帰用に、起動直後(=通常時)のバックライト輝度を記憶しておく
  nightBrightness = M5.Display.getBrightness();

  // ★ WiFiモデムスリープを無効化（省電力モードがTCP接続失敗の原因になるため）
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);

  // 本体内蔵SPKの初期化。
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

  // 本体内蔵マイクは既定ゲイン(magnification=2)だと無線機のDATAモードを
  // 継続して駆動するには信号が弱いため、ゲインを上げる。
  {
    auto mic_cfg = M5.Mic.config();
    mic_cfg.magnification = 2.0f + (float)es8388MicGainIdx * 2.75f; // 0-8 -> 約2~24倍
    M5.Mic.config(mic_cfg);
  }
  M5.Mic.end();

  canvas.setColorDepth(8);                     // 軽量化
  canvas.createSprite(CANVAS_SIZE, CANVAS_SIZE); // M5StopWatchの画面サイズ(円形466x466)
  loadAutoSkipSetting();         // 起動画面のタイムアウト既定値(Normal/Skip)を復元
  drawSplashScreen();
  canvas.setTextSize(2);
  canvas.setTextColor(WHITE);
  canvas.setFont(&fonts::efontJA_16);

  httpQueue = xQueueCreate(10, sizeof(HttpCommand *));

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
      M5.Speaker.playRaw(stereoBuf, (size_t)(samples * 2), (uint32_t)samplingRates[selSampling], true, 1, 0);

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
      M5.Speaker.playRaw(stereoBuf, (size_t)(samples * 2), (uint32_t)samplingRates[selSampling], true, 1, 0);
    }
  }

  client.stop();
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
  }

  isReconnecting = false;
  Serial.printf("[%lu]再生停止\n", millis());
}

static unsigned long lastHeartbeat = 0;

void txControlTask(void *param)
{
  static bool lastKeyDbg = false;
  // M5StopWatchには外部フットスイッチ用のGPIOポートが無いため、メイン画面PTT
  // ボタンのタップで直接ON/OFFする(txEnabled自体がキー状態を兼ねる)。
  while (true)
  {
    bool key = txEnabled;
    if (key != lastKeyDbg)
    {
      Serial.printf("[%lu][txkey] key=%d txEnabled=%d txKeyPressed=%d\n", millis(), key, txEnabled, txKeyPressed);
      lastKeyDbg = key;
    }

    // --- PTT ON ---
    if (txEnabled && key && !txKeyPressed)
    {
      txKeyPressed = true;

      // ★ 遅延低減: PTT信号送信(ネットワークI/O、数十〜数百ms)と、マイク側の準備
      //   (RXタスク停止待ち+I2S切替、こちらもローカルながら数十〜最大1秒かかりうる)
      //   は互いに依存しないため、直列ではなく並列に実行する。以前は直列だったため
      //   両方の所要時間の合計だけ録音開始が遅れ、「PTTを押した直後の発話の頭が
      //   録れない」原因になっていた。PTT送信は別タスクへ切り出し、こちらのタスクは
      //   待たずにstartAudioTx()へ進む。
      xTaskCreatePinnedToCore(
          [](void *) {
            if (useCIV)
            {
              bool pttOk = civSetPtt(true);
              Serial.printf("[%lu][txkey] civSetPtt(true) result=%d\n", millis(), pttOk);
            }
            else if (useWifiPTT)
            {
              sendWifiPTT(true);
              sendApiPollState(false); // TX中はrigctldポーリングを停止
            }
            else
              sendHamlibPTT(true);
            vTaskDelete(NULL);
          },
          "pttOnTask", 4096, NULL, 5, NULL, 0);

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
  // PTT ON: FTX-1F等、PTT ONで音声USBが瞬断しrigctldごと再起動することがあり
  // (USB再列挙+CAT再接続で通常2〜3秒、まれにそれ以上)、固定回数リトライでは
  // 復旧を待ちきれず諦めてしまうことがあった。固定回数ではなく経過時間ベースで
  // 最大5秒間リトライし続けることで、復旧が多少遅れてもPTT ONを取りこぼさない
  // ようにする。
  // PTT OFF: 即座に1回のみ送る(確実な送達はFastAPI側のPTT OFF watchdogが担う)。
  unsigned long deadline = millis() + 5000;
  int attempt = 0;
  while (true)
  {
    attempt++;
    HTTPClient http;
    http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/ptt");
    http.setTimeout(800);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
    int code = http.POST(String("state=") + (on ? "1" : "0"));
    http.end();
    Serial.printf("🔧 HamlibPTT %s attempt=%d code=%d\n", on ? "ON" : "OFF", attempt, code);
    if (code == 200 || !on) break;
    if ((long)(millis() - deadline) >= 0) break;
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

// initLed()/clearLed()/setLedColor()はui_core.cppで定義(NeoPixel非搭載のため
// TX状態は画面上のリング表示で示す。関数自体はtxControlTask等からの既存呼び出し
// 互換のために残し、中身は no-op にしてある)。

// ============================================================
//  マイクTX (AudioTx)
// ============================================================
void audioTxTask(void *param)
{
  const int BUF_SIZE = 512;

  // M5StopWatchは内蔵マイク(M5.Mic)のみ。非同期エンジンのため8000Hz固定とする。
  const int RATE = 8000;

  // HTTP POST (chunked transfer) 開始
  WiFiClient client;
  if (!client.connect(HostName.c_str(), apiPort))
  {
    Serial.println("[audioTx] connect failed");
    M5.Mic.end();
    M5.Speaker.begin();
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

  // M5.Mic.record()は非同期(呼び出し即座には録音完了しない)なので、公式サンプル
  // (Microphone.ino)同様にリングバッファへ先読みで発行し続け、数コマ遅れた
  // (=実時間的に確実に録音済みの)スロットを送信に使う。
  const int RING_SLOTS = 6;
  const int RING_LAG   = 3; // 送信は録音発行より何コマ遅らせるか
  static int16_t ring[RING_SLOTS][BUF_SIZE / 2];
  int recIdx = 0;
  int sendIdx = 0;
  const unsigned long frameMs = (unsigned long)((BUF_SIZE / 2) * 1000UL / RATE);
  for (int i = 0; i < RING_LAG; i++)
  {
    M5.Mic.record(ring[recIdx], BUF_SIZE / 2, RATE, false);
    recIdx = (recIdx + 1) % RING_SLOTS;
  }

  // マイク入力は電源投入直後の数秒間ポップノイズ/不安定な値が乗るため、
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
    M5.Mic.record(ring[recIdx], BUF_SIZE / 2, RATE, false);
    recIdx = (recIdx + 1) % RING_SLOTS;
    memcpy(buf, ring[sendIdx], BUF_SIZE);
    sendIdx = (sendIdx + 1) % RING_SLOTS;
    bytesRead = (size_t)BUF_SIZE;
    err = ESP_OK;
    vTaskDelay(pdMS_TO_TICKS(frameMs)); // 実時間ペースに合わせる
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

  // ループが完全に停止してから切り替えることで、record()実行中の競合を避ける
  M5.Mic.end();
  M5.Speaker.begin();
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

  // 内蔵マイクのゲインをTX用に反映(0-8 -> 約2~24倍)しておく。
  {
    auto mic_cfg = M5.Mic.config();
    mic_cfg.magnification = 2.0f + (float)es8388MicGainIdx * 2.75f;
    M5.Mic.config(mic_cfg);
  }

  Serial.printf("[%lu][audioTx] mic gain set, calling stopPlayback()\n", millis());
  stopPlayback(); // RX停止
  Serial.printf("[%lu][audioTx] stopPlayback() returned\n", millis());

  connected = false;

  if (useCIV)
  {
    // CI-Vモードの音声送信はPi経由ではなく無線機とのUDP直結(civ_client.cpp)で行う。
    // ハードウェア切替(内蔵マイク/スピーカー)も含めて自己完結している。
    civStartTxAudio();
  }
  else
  {
    // 本体内蔵マイク/スピーカーはクロック系ピン(MCK/BCK/WS)を共有しており、
    // 同時初期化はできない(実機確認済み: SPK.end()を省略するとSPKが鳴らなくなる)。
    // streamTask完全停止後にマイクへ切り替える。
    M5.Speaker.end();
    M5.Mic.begin();
    xTaskCreatePinnedToCore(audioTxTask, "audioTxTask", 8192, NULL, 5, &audioTxTaskHandle, 1);
  }
  Serial.printf("[%lu][audioTx] startAudioTx()\n", millis());
}

void stopAudioTx()
{
  if (!audioTxRunning) return;
  audioTxRunning = false;

  // 非CI-V: マイク終了/スピーカー再開は audioTxTask() 末尾で行う
  // (record()実行中に他タスクから切り替えて競合するのを避けるため)
  if (useCIV)
  {
    // CI-VモードはcivStartTxAudio()で自前ハードウェア切替しているため、ここで停止する。
    civStopTxAudio();
  }

  connected = false;
  Serial.println("[audioTx] stopAudioTx()");
}
