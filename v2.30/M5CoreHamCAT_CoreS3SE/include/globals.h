/****************************************************
 *  Wifi_Rig_CTRL globals.h
 *  Ver2.30
 *  by JI1ORE
 ****************************************************/
#pragma once
#include <Arduino.h>
#include <vector>
#include <Preferences.h>
#include <M5Unified.h>
#include <WiFi.h>
#include <Unit_Encoder.h>
#include "driver/i2s.h"
#include "AudioOutputI2S.h"
#include <Adafruit_NeoPixel.h>

#ifdef M5CORE2
#define I2C_SDA_ENCODER 32
#define I2C_SCL_ENCODER 33
#define I2C_SDA_AUDIO 21
#define I2C_SCL_AUDIO 22
#define SYS_I2S_MCLK_PIN 0
#define SYS_I2S_SCLK_PIN 19
#define SYS_I2S_LRCK_PIN 27
#define SYS_I2S_DOUT_PIN 2
#define SYS_I2S_DIN_PIN 34
#define TX_KEY_PIN 13
#define TX_LED_PIN 14

#elif M5TOUGH
// Port A (GND, 5V, G32, G33)。GPIO26/36(Port B)はB相がプルアップ非対応の
// 入力専用ピンでフローティングになり誤動作するため、Port Aに変更。
#define GPIO_A_ENCODER 32
#define GPIO_B_ENCODER 33
#define I2C_SDA_AUDIO 21
#define I2C_SCL_AUDIO 22
#define SYS_I2S_MCLK_PIN 0
#define SYS_I2S_SCLK_PIN 19
#define SYS_I2S_LRCK_PIN 27
#define SYS_I2S_DOUT_PIN 2
#define SYS_I2S_DIN_PIN 34
// Port C (GND, 5V, G14, G13) の G13。もう一方の端子はPort CのGNDピンへ。
#define TX_KEY_PIN 13
#define TX_LED_PIN 14

#elif M5CORES3
#define I2C_SDA_ENCODER 2
#define I2C_SCL_ENCODER 1
#define I2C_SDA_AUDIO 12
#define I2C_SCL_AUDIO 11
#define SYS_I2S_MCLK_PIN 0
#define SYS_I2S_SCLK_PIN 7
#define SYS_I2S_LRCK_PIN 6
#define SYS_I2S_DOUT_PIN 13
#define SYS_I2S_DIN_PIN 14
#define TX_KEY_PIN 8
#define TX_LED_PIN 9
#endif

#if defined(M5CORES3) || defined(M5CORE2)
// 本体内蔵SPK/Micと外部(Module Audio ES8388)を設定画面(PTT Method)から選択できるボード
#define AUDIO_SRC_SELECTABLE
#endif

#define NUMPIXELS 1

// ==== UI共通 ====
extern M5Canvas canvas;
extern Preferences prefs;
extern bool mainFirstDraw;
extern bool aprsSettingsFirstDraw;
extern bool aprsReceivedFirstDraw;
extern bool passwordFirstDraw;   // ★ パスワード画面の初回描画フラグ
extern bool showErrorDialog;
extern String lastErrorMessage;

// ==== オーディオ保護 mutex ====
#include <freertos/semphr.h>
extern SemaphoreHandle_t audioMutex;

// ==== エンコーダ ====
extern Unit_Encoder encoder;
extern int32_t lastEncVal;
extern bool encoderPresent;
#ifdef M5TOUGH
extern volatile int encoderPos;
#endif

// ==== アプリ状態 ====
enum AppState
{
  STATE_WIFI,
  STATE_PASSWORD,
  STATE_PI_CONNECT,
  STATE_DEVICE_SELECT,
  STATE_RIG_CONNECT,
  STATE_RIG_PTT,
  STATE_MAIN_UI,
  STATE_FREQ_INPUT,
  STATE_CONNECT_FAILED,
  STATE_APRS_EDIT,
  STATE_APRS_SETTINGS,
  STATE_APRS_RECEIVED
};
extern AppState appState;

// ==== メニュー項目 ====
enum MenuItem
{
  MENU_UP,
  MENU_DOWN,
  MENU_BACK,
  MENU_FREQ,
  MENU_MODE,
  MENU_POW,
  MENU_SQL,
  MENU_STEP,
  MENU_WIDTH,
  MENU_RVOL,
  MENU_APRS,
  MENU_SPK,
  MENU_PTT,
  MENU_MICGAIN,
  MENU_VFOTOGGLE,
  MENU_NONE
};
extern int selectedItem;

// ==== Wi-Fi 関連 ====
struct WifiEntry
{
  String ssid;
  int rssi;
};
extern std::vector<WifiEntry> wifiList;
extern bool wifiScanned;
extern int selectedWifiIndex;
extern int wifiScrollOffset;
extern String ssid;
extern String pass;

// ==== パスワード入力・キーボード ====
extern bool passwordForWifi;
extern String inputPassword;
extern bool shiftOn;

enum EditField
{
  FIELD_NONE,
  FIELD_HOST,
  FIELD_API_PORT,
  FIELD_AUDIO_PORT,
  FIELD_API_KEY,
  FIELD_BAUDRATE,
  FIELD_PTT_HOST,
  FIELD_PTT_PORT,
  FIELD_APRS_TXFREQ,
  FIELD_APRS_LAT,
  FIELD_APRS_LON,
  FIELD_APRS_CALLSIGN,
  FIELD_APRS_AP96_FREQ,
  FIELD_APRS_AP96_BAUD,
  FIELD_APRS_AP12_FREQ,
  FIELD_APRS_AP12_BAUD,
  FIELD_CIV_HOST,
  FIELD_CIV_PORT1,
  FIELD_CIV_PORT2,
  FIELD_CIV_USERNAME,
  FIELD_CIV_PASSWORD,
  FIELD_CIV_ADDRESS
};
extern EditField editingField;

enum KeyboardMode
{
  KB_QWERTY,
  KB_NUMPAD
};
extern KeyboardMode kbMode;

// ==== 周波数ステップ設定 ====
struct StepSetting
{
  const char *label;
  long stepHz;
};
extern StepSetting stepList[];
extern int selStep;

// ==== Raspberry Pi 接続設定 ====
extern bool useMDNS;
extern String raspiHost;
extern String HostName;
extern int apiPort;
extern int audioPort;
extern String apiKey; // FastAPI認証キー（空=""=認証なし）
extern int baudRate;
extern bool useInternalSpeaker;

// ==== Rig / デバイス選択 ====
extern std::vector<int> rigIds;
extern std::vector<String> rigNames;
extern std::vector<String> catList;
extern std::vector<String> devList;
extern int selRig;
extern int selCat;
extern int selBaud;
extern std::vector<int> baudRates;
extern bool rigSelectFirstDraw;
extern bool rigConnectFirstDraw;
extern std::vector<int> samplingRates;
extern int selSampling;
extern int SRate;
extern bool speakerEnabled;
extern i2s_config_t i2s_config;
extern i2s_pin_config_t pin_config;

// ==== スクリーンタイムアウト ====
extern unsigned long screenTimeout;
extern std::vector<int> screenTimeoutOptions;
extern int selScreenTimeout;

// ==== 無線機状態 ====
extern int64_t lastFreqHz;
extern String lastModel;
extern int lastWidth;
extern int selWidthIndex;
extern std::vector<int> supportedWidths;

// --- VFO A/B or MAIN/SUB 切替(機種のHamlibバックエンドがどちらに対応しているかで判定) ---
extern String vfoModeStr;    // "ab" or "mainsub"(Pi側 /radio/vfo_mode の結果)
extern String vfoCurrentSide; // "Main"/"Sub"または"A"/"B"。空文字="未取得"(ボタンにはvfoModeStrの汎用表示を使う)
void fetchVfoState();         // /radio/vfo_mode と /radio/vfo_current をまとめて取得する(接続直後に呼ぶ)

extern int txPowerWatt;
extern int txVolPercent;
extern int rxVolPercent;
extern int rxVolume;
extern bool bkinOn;
extern bool isTransmitting;
extern int signalStrength;

extern bool txEnabled;    // TXボタンのON/OFF
extern bool txKeyPressed; // portBキーの状態
extern bool tx_ing;       // 現在送信中かどうか(CI-VのTX中はCI-V応答ポーリング失敗を無視するため参照)
extern unsigned long wifiConnectedSinceMs; // WiFi接続確立を最初に検知したmillis()。0は未接続。
                                            // CI-V接続開始直後はWiFiスタックがまだ不安定で
                                            // UDP送信がENOMEMで失敗しやすいため、civ_client側で
                                            // 接続確立から十分な時間が経過するまで待つのに使う。

// ==== 別スレッド用 ====
extern String sharedFreq;
extern String sharedMode;
extern String sharedModel;
extern float sharedSignal;
extern bool sharedTx;
extern bool needRedraw;
extern bool spkEnabled;
extern float currentVolume;
extern AudioOutputI2S *out;

// ==== 関数 ====
String connectToRasPiServices();
bool tryConnectWifi(const String &ssid, const String &pass, bool &timedOut);
void loadAprsSettings();
void loadPttSettings();
extern std::vector<String> soundDeviceList;
extern std::vector<String> soundDeviceLabel;

void fetchSoundDevices();
void drawAprsReceivedUI();
void handleAprsReceivedScreen();
void pollAprsNotify();
void pollVfoState();
float aprsDistanceKm(float lat1, float lon1, float lat2, float lon2);
float aprsBearingDeg(float lat1, float lon1, float lat2, float lon2);
const char *aprsCompassDir(float lat1, float lon1, float lat2, float lon2);
void drawCompassIcon(int cx, int cy, int r, float bearingDeg, bool showAllDirs);
void drawAprsSymbolIcon(const String &symbol, int iconCx, int iconCy, bool showCaption);
void getThemePalette(int theme, bool day,
    uint16_t &bgDark, uint16_t &panelBg, uint16_t &borderCol,
    uint16_t &accentTeal, uint16_t &accentBlue, uint16_t &textSecondary,
    uint16_t &txRed, uint16_t &chipBg, uint16_t &btnIdle, uint16_t &btnIdleBorder,
    uint16_t &spkOnCol, uint16_t &aprsOnCol, uint16_t &navOrange);

extern TaskHandle_t playbackTaskHandle;

extern int sharedWidth;
extern float sharedSQL;
extern float sharedPower;
extern float sharedVolume;

extern unsigned long lastUserFreqChange;
extern unsigned long lastUserModeChange;
extern unsigned long lastUserWidthChange;
extern unsigned long lastUserSQLChange;
extern unsigned long lastUserPowerChange;
extern unsigned long lastUserVolumeChange;
extern unsigned long lastUserMicGainChange;

// --- ES8388マイクのPGAゲイン (0-8 = 0dB刻みで0～24dB, es_mic_gain_t にそのまま対応) ---
extern int es8388MicGainIdx;

extern float currentPowerNorm;
extern float sqlLevel;

extern bool aprsEnabled; // APRS 機能が有効か
extern bool aprsActive;  // APRS 定期送信中か
extern float aprsTxFreq;

extern bool aprsUseGPS;

extern float aprsManualLat;
extern float aprsManualLon;

extern int aprsBaud;

extern String aprsCallsign;
extern int aprsSSID;
extern String aprsPath;
extern int aprsIntervalSec;

extern String aprsSymbol;
extern String aprsDestination;
extern String aprsSoundDevice;
extern String aprsRigID;
extern String aprsCatDevice;
extern bool aprsUseRigModem; // true=無線機内蔵APRSモデム(CAT) false=DireWolf(Pi)
extern int aprsModemSel; // 無線機内蔵モデム使用時: 1=AUTO 2=MAIN 3=SUB

// --- FTX-1内蔵モデム時のワンタップ切替プリセット(AP96/AP12) ---
extern float aprsPreset1Freq; // AP96既定値
extern int aprsPreset1Baud;
extern float aprsPreset2Freq; // AP12既定値
extern int aprsPreset2Baud;
extern bool aprsAtPreset2;    // 実行時のみ: 現在プリセット2(AP12)側か

// --- 受信ビーコン: 同一局の再通知を抑制する間隔(秒) ---
extern int aprsHeardSuppressSec;

extern String pttHost;
extern int pttPort;
extern bool useWifiPTT;
extern String pttDevice; // Hamlib PTT用シリアルデバイス（""=NONE）
extern String pttType;   // "RTS" or "DTR"
extern bool aprsTxInProgress;

// --- SPK出力先 / Mic入力先 (M5CORES3のみ有効) ---
extern bool useExternalSpk; // false=本体内蔵SPK true=Module Audio(ES8388)経由
extern bool useExternalMic; // false=本体内蔵Mic true=Module Audio(ES8388)経由 (useExternalSpk=falseの場合は常にfalse)

// --- 日中/夜間 表示モード ---
extern bool dayMode;          // true=日中（高コントラスト・輝度最大）
extern uint8_t nightBrightness; // 夜間モード復帰時に戻すバックライト輝度（起動時のデフォルト値を保持）

// --- デザインパターン(配色テーマ) ---
extern int uiTheme; // 0=OCEAN 1=AMBER 2=MONO 3=AQUA(各テーマは dayMode で昼/夜配色を持つ)

// --- 起動画面:タイムアウト時に Normal/Skip どちらへ進むかの既定値 ---
extern bool autoSkipDefault; // true=Skip既定 false=Normal既定
void loadAutoSkipSetting();  // drawSplashScreen() より前に呼ぶこと

// ==== CI-V WiFi直結(Pi/hamlibをバイパスして無線機のWiFiへ直接UDP接続) ====
extern bool useCIV;
extern String civHost;
extern int civPort1; // controlポート
extern int civPort2; // CI-Vポート
extern int civPort3; // 音声ポート
extern String civUsername;
extern String civPassword;
extern int civAddress; // 無線機のCI-Vアドレス(IC-705既定=0xA4)

// ==== 飛ばしモード ====
extern bool skipModeActive;

// ==== マイクTX ====
extern bool audioTxRunning;
extern TaskHandle_t audioTxTaskHandle;
void startAudioTx();
void stopAudioTx();

// ==== SPK再接続タイマー（audioTxTask終了時にリセット） ====
extern unsigned long lastStreamAttempt;

// ==== Pi設定の保存/ロード ====
void loadPiSettings();

// スクロール位置
extern int rigScrollY;
extern int rigScrollMin;
