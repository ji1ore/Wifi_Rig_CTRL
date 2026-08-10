/****************************************************
 *  Wifi_Rig_CTRL globals.cpp
 *  Ver2.33
 *  by JI1ORE
 ****************************************************/
#include "globals.h"
#include <Arduino.h>

TaskHandle_t aprsTaskHandle = NULL;

// ==== UI共通 ====
M5Canvas canvas(&M5.Display);
LGFX_Sprite listCanvas(&canvas);
Preferences prefs;
bool mainFirstDraw = true;
bool aprsSettingsFirstDraw = false;
bool aprsReceivedFirstDraw = false;
bool passwordFirstDraw = true;  // ★ パスワード画面の初回描画フラグ
bool showErrorDialog = false;
String lastErrorMessage = "";

// ==== エンコーダ ====
Unit_Encoder encoder;
int32_t lastEncVal = 0;
bool encoderPresent = false;

// ==== アプリ状態 ====
AppState appState = STATE_WIFI;

// ==== メニュー ====
int selectedItem = 0;

// ==== Wi-Fi 関連 ====
std::vector<WifiEntry> wifiList;
bool wifiScanned = false;
int selectedWifiIndex = -1;
int wifiScrollOffset = 0;
String ssid = "";
String pass = "";

// ==== パスワード入力・キーボード ====
bool passwordForWifi = false;
String inputPassword = "";
bool shiftOn = false;
EditField editingField = FIELD_NONE;
KeyboardMode kbMode = KB_QWERTY;

// ==== 周波数ステップ設定 ====
StepSetting stepList[] = {
    {"10", 10},
    {"100", 100},
    {"500", 500},
    {"1k", 1000},
    {"5k", 5000},
    {"10k", 10000},
    {"20k", 20000}};
int selStep = 1; // 初期値は100Hz

// ==== Raspberry Pi 接続設定 ====
bool useMDNS = true;
String raspiHost = "raspizero";
String HostName = "";
int apiPort = 8000;
int audioPort = 50000;
String apiKey = "";
int baudRate = 38400;
bool useInternalSpeaker = true;

// ==== Rig / デバイス選択 ====
std::vector<int> rigIds;
std::vector<String> rigNames;
std::vector<String> devList;
std::vector<String> catList;
int selRig = 0;
int selCat = 0;
int selBaud = 3; // デフォルトは38400
std::vector<int> baudRates = {4800, 9600, 19200, 38400, 57600, 115200};
bool rigSelectFirstDraw = true;
bool rigConnectFirstDraw = true;
std::vector<int> samplingRates = {0, 8000, 16000, 32000, 44100, 48000};
int selSampling = 4;
int SRate = 32000;
bool speakerEnabled = true;

i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = SRate,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = 0,
    .dma_buf_count = 8,
    .dma_buf_len = 512,
    .use_apll = true,
    .tx_desc_auto_clear = true,
    .fixed_mclk = 0};

i2s_pin_config_t pin_config = {
    .mck_io_num = SYS_I2S_MCLK_PIN,
    .bck_io_num = SYS_I2S_SCLK_PIN,
    .ws_io_num = SYS_I2S_LRCK_PIN,
    .data_out_num = SYS_I2S_DOUT_PIN,
    .data_in_num = SYS_I2S_DIN_PIN,
};

// ==== スクリーンタイムアウト ====
unsigned long screenTimeout = 60000;                                // 初期値：1分（例）
std::vector<int> screenTimeoutOptions = {1, 5, 10, 30, 60, 120, 0}; // 秒単位（0はオフ）
int selScreenTimeout = 2;                                           // デフォルトは10分

// ==== 無線機状態 ====
int64_t lastFreqHz = 0;
String lastModel = "No_RIG";
int lastWidth = 2400;
int selWidthIndex = 0;
std::vector<int> supportedWidths = {50, 100, 200, 300, 500, 1000, 1500, 2400, 2700, 3000};

// --- VFO A/B or MAIN/SUB 切替 ---
String vfoModeStr = "ab";
String vfoCurrentSide = "";

int txPowerWatt = 0;
int txVolPercent = 50;
int rxVolPercent = 50;
int rxVolume = 0;
bool bkinOn = false;
bool isTransmitting = false;
int signalStrength = 0;

// ==== タスク ====
TaskHandle_t audioTaskHandle = nullptr;

bool txEnabled = false;
bool txKeyPressed = false;

// 共有用の変数（描画や他処理で使う）
String sharedFreq;
String sharedMode;
String sharedModel;
float sharedSignal = 0;
bool sharedTx = false;
bool needRedraw = true;
bool spkEnabled = false;
float currentVolume = 0.05f;

TaskHandle_t playbackTaskHandle = nullptr;

int sharedWidth;
float sharedSQL;
float sharedPower;
float sharedVolume;

unsigned long lastUserFreqChange = 0;
unsigned long lastUserModeChange = 0;
unsigned long lastUserWidthChange = 0;
unsigned long lastUserSQLChange = 0;
unsigned long lastUserPowerChange = 0;
unsigned long lastUserVolumeChange = 0;
unsigned long lastUserMicGainChange = 0;

// --- ES8388マイクのPGAゲイン (0-8 = 0dB刻みで0～24dB) ---
int es8388MicGainIdx = 8; // 既定: 24dB(最大)

float currentPowerNorm = 0.0f;
float sqlLevel = 0.0f;

bool aprsEnabled = false;
bool aprsActive = false;
float aprsTxFreq = 144.660; // 例：APRS 送信周波数

bool aprsUseGPS = true;

float aprsManualLat = 35.0000;
float aprsManualLon = 135.0000;

int aprsBaud = 1200; // 1200 or 9600

String aprsCallsign = "xxxxxxx";
int aprsSSID = 7;                    // 枝番
int aprsIntervalSec = 60;            // デフォルト60秒
String aprsPath = "WIDE1-1,WIDE2-1"; // デフォルト
String aprsSymbol = "v";             // デフォルトはバイク
String aprsDestination = "APDW18";   // デフォルトは自作TNC
String aprsSoundDevice = "hw:CARD=CODEC,DEV=0";
std::vector<String> soundDeviceList;
std::vector<String> soundDeviceLabel;
String aprsRigID = "3085"; // デフォルト値
String aprsCatDevice = "/dev/ttyACM0";
bool aprsTxInProgress = false;
bool aprsUseRigModem = false; // false=DireWolf(Pi) true=無線機内蔵APRSモデム(CAT)
int aprsModemSel = 2; // 1=AUTO 2=MAIN 3=SUB

// --- FTX-1内蔵モデム時のワンタップ切替プリセット(AP96/AP12) ---
float aprsPreset1Freq = 144.64f;
int aprsPreset1Baud = 9600;
float aprsPreset2Freq = 144.66f;
int aprsPreset2Baud = 1200;
bool aprsAtPreset2 = false;

// --- 受信ビーコン: 同一局の再通知を抑制する間隔(秒) ---
int aprsHeardSuppressSec = 600; // 既定10分


void loadAprsSettings()
{
    Preferences prefs;
    prefs.begin("aprs", true); // 読み取り専用

    aprsEnabled = prefs.getBool("enabled", false);
    aprsUseGPS = prefs.getBool("useGPS", true);
    aprsManualLat = prefs.getFloat("lat", 35.00000);
    aprsManualLon = prefs.getFloat("lon", 135.00000);
    aprsBaud = prefs.getInt("baud", 1200);
    aprsCallsign = prefs.getString("call", "xxxxxxx");
    aprsSSID = prefs.getInt("ssid", 7);
    aprsTxFreq = prefs.getFloat("aprsfreq", 144.660);
    aprsIntervalSec = prefs.getInt("interval", 60);
    aprsPath = prefs.getString("path", "WIDE1-1,WIDE2-1");
    aprsSymbol = prefs.getString("symbol", "v"); // デフォルトはバイク
    aprsDestination = prefs.getString("destination", "APDW18");
    aprsSoundDevice = prefs.getString("sounddev", "hw:CARD=CODEC,DEV=0");
    aprsRigID = prefs.getString("rigid", "3085");
    aprsCatDevice = prefs.getString("catdev", "/dev/ttyACM0");
    aprsUseRigModem = prefs.getBool("useRigModem", false);
    aprsModemSel = prefs.getInt("modemSel", 2);
    aprsPreset1Freq = prefs.getFloat("p1freq", 144.64f);
    aprsPreset1Baud = prefs.getInt("p1baud", 9600);
    aprsPreset2Freq = prefs.getFloat("p2freq", 144.66f);
    aprsPreset2Baud = prefs.getInt("p2baud", 1200);
    aprsHeardSuppressSec = prefs.getInt("heardSupp", 600);
    useWifiPTT = prefs.getBool("useWifiPTT", true);
}
void loadPttSettings()
{
    prefs.begin("device", true);
    useWifiPTT = prefs.getBool("useWifiPTT", true);
    pttHost = prefs.getString("pttHost", "rigpttserv01");
    pttPort = prefs.getInt("pttPort", 8888);
    pttDevice = prefs.getString("pttDevice", "");
    pttType = prefs.getString("pttType", "RTS");
    dayMode = prefs.getBool("dayMode", false);
    uiTheme = prefs.getInt("uiTheme", 0);
    useExternalSpk = prefs.getBool("useExtSpk", false);
    useExternalMic = prefs.getBool("useExtMic", false);
    es8388MicGainIdx = prefs.getInt("micGainIdx", 8);
    if (es8388MicGainIdx < 0) es8388MicGainIdx = 0;
    if (es8388MicGainIdx > 8) es8388MicGainIdx = 8;
    prefs.end();
}


// --- PTT Method ---
bool useWifiPTT = true; // 初期値：Wifi_Rig_PTT

// --- Wifi_Rig_PTT 接続先（mDNS固定） ---
String pttHost = "rigpttserv01";
int pttPort = 8888;

// --- Hamlib PTT ---
String pttDevice = "";    // ""=NONE
String pttType = "RTS";   // "RTS" or "DTR"

// --- SPK出力先 / Mic入力先 (AUDIO_SRC_SELECTABLE ボードのみ有効) ---
bool useExternalSpk = false; // 既定: 本体内蔵SPK
bool useExternalMic = false; // 既定: 本体内蔵Mic

// --- 日中/夜間 表示モード ---
bool dayMode = false;
uint8_t nightBrightness = 200; // setup()実測値で上書きされる暫定値

// --- デザインパターン(配色テーマ) ---
int uiTheme = 0; // 0=OCEAN 1=AMBER 2=MONO 3=AQUA

// --- 起動画面:タイムアウト時に Normal/Skip どちらへ進むかの既定値 ---
bool autoSkipDefault = false;
void loadAutoSkipSetting()
{
    prefs.begin("device", true);
    autoSkipDefault = prefs.getBool("autoSkip", false);
    prefs.end();
}

// --- スクロール ---
int rigScrollY = 0;
int rigScrollMin = -150;

// --- 飛ばしモード ---
bool skipModeActive = false;

// --- マイクTX ---
bool audioTxRunning = false;
TaskHandle_t audioTxTaskHandle = nullptr;
unsigned long lastStreamAttempt = 0;

void loadPiSettings()
{
    Preferences piPrefs;
    piPrefs.begin("piconn", true);
    raspiHost = piPrefs.getString("host", "raspizero");
    apiPort   = piPrefs.getInt("apiPort", 8000);
    audioPort = piPrefs.getInt("audioPort", 50000);
    useMDNS   = piPrefs.getBool("useMDNS", true);
    apiKey    = piPrefs.getString("apiKey", "");
    piPrefs.end();
}

// --- CI-V WiFi直結 ---
bool useCIV = false;
String civHost = "192.168.1.1";
int civPort1 = 50001;
int civPort2 = 50002;
int civPort3 = 50003; // 音声ポート(IC-705は既定でport2+1固定のため通常変更不要)
String civUsername = "";
String civPassword = "";
int civAddress = 0xA4;


