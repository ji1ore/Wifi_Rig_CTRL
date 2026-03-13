/****************************************************
 *  M5CoreHamCAT globals.h
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#pragma once
#include <Arduino.h>
#include <vector>
#include <Preferences.h>
#include <M5Unified.h>
#include <WiFi.h>
#include <Unit_Encoder.h>
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

#define NUMPIXELS 1



// ==== UI共通 ====
extern M5Canvas canvas;
extern Preferences prefs;
extern bool mainFirstDraw;
extern bool showErrorDialog;
extern String lastErrorMessage;

// ==== エンコーダ ====
extern Unit_Encoder encoder;
extern int32_t lastEncVal;

// ==== アプリ状態 ====
enum AppState
{
  STATE_WIFI,
  STATE_PASSWORD,
  STATE_PI_CONNECT,
  STATE_DEVICE_SELECT,
  STATE_RIG_CONNECT,
  STATE_MAIN_UI,
  STATE_FREQ_INPUT,
  STATE_CONNECT_FAILED
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
  MENU_SPK,
  MENU_PTT,
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
  FIELD_BAUDRATE
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
extern i2s_config_t i2s_config;
extern i2s_pin_config_t pin_config;

// ==== スクリーンタイムアウト ====
extern unsigned long screenTimeout;
extern std::vector<int> screenTimeoutOptions; 
extern int selScreenTimeout;

// ==== 無線機状態 ====
extern int64_t lastFreqHz;
extern String lastMode;
extern String lastModel;
extern int lastWidth;
extern int selWidthIndex;
extern std::vector<int> supportedWidths;

extern int txPowerWatt;
extern int txVolPercent;
extern int rxVolPercent;
extern int rxVolume;
extern bool bkinOn;
extern bool isTransmitting;
extern int signalStrength;

extern bool txEnabled;      // TXボタンのON/OFF
extern bool txKeyPressed;   // portBキーの状態

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

extern float currentPowerNorm;
extern float sqlLevel;

