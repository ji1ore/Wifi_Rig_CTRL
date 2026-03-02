/****************************************************
 *  M5CoreHamCAT globals.cpp
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include "globals.h"

// ==== UI共通 ====
M5Canvas canvas(&M5.Display);
LGFX_Sprite listCanvas(&canvas);
Preferences prefs;
bool mainFirstDraw = true;
bool showErrorDialog = false;
String lastErrorMessage = "";

// ==== エンコーダ ====
Unit_Encoder encoder;
int32_t lastEncVal = 0;

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
std::vector<int> samplingRates = {8000, 16000, 32000, 44100, 48000};
int selSampling = 4;


// ==== 無線機状態 ====
int64_t lastFreqHz = 0;
String lastMode = "USB";
String lastModel = "No_RIG";
int lastWidth = 2400;
int selWidthIndex = 0;
std::vector<int> supportedWidths = {50, 100, 200, 300, 500, 1000, 1500, 2400, 2700, 3000};

int txPowerWatt = 0;
int txVolPercent = 50;
int rxVolPercent = 50;
int rxVolume = 0;
bool bkinOn = false;
bool isTransmitting = false;
int signalStrength = 0;

// ==== タスク ====
TaskHandle_t audioTaskHandle = nullptr;

// 共有用の変数（描画や他処理で使う）
String sharedFreq;
String sharedMode;
String sharedModel;
int sharedSignal = 0;
bool sharedTx = false;
bool needRedraw = true;
bool spkEnabled = false;
float currentVolume = 0.25f;