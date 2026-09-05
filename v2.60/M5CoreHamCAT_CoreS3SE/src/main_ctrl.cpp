/****************************************************
 *  Wifi_Rig_CTRL 無線機制御UI画面
 *  Ver2.5
 *  by JI1ORE
 ****************************************************/
#include <M5Unified.h>
#include "ui_core.h"
#include "ui_display.h"
#include "globals.h"
#include "civ_client.h"
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <map>
#include <stdlib.h>
#include "http_sender.h"
#include "gps_relay.h"

std::map<String, int> modeStepMap;
static MenuItem aprsTouchTarget = MENU_NONE;

static int touchStartX = -1;
static int touchStartY = -1;

// Tasker位置情報がこの時間以内に届いていなければ「位置情報未取得」扱い
static const unsigned long APRS_GPS_STALE_MS = 10UL * 60UL * 1000UL; // 10分

// ★ APRS受信ポップアップ(無線機のビーコン受信表示のような一時的な通知)。
//   位置情報がある局は大きいコンパスで方向・距離をはっきり表示する。
static String aprsToastCall = "";
static bool aprsToastHasPos = false;
static float aprsToastLat = 0, aprsToastLon = 0;
static String aprsToastComment = "";
static String aprsToastSymbol = "";
static unsigned long aprsToastUntilMs = 0;

// symbolは"テーブル文字+コード文字"の2文字。アイコン形状はコード文字(最後の1文字)で決まる。
static String toastSymbolCode()
{
  return aprsToastSymbol.length() > 0 ? String(aprsToastSymbol[aprsToastSymbol.length() - 1]) : String("");
}

extern String freqInputBuffer;
extern bool connected;
extern TaskHandle_t streamTaskHandle;

static void applyDefaultStep(const String &mode)
{
  if (modeStepMap.count(mode))
  {
    selStep = modeStepMap[mode];
  }
  else if (mode == "AM" || mode.indexOf("FM") >= 0)
  {
    selStep = 6; // 20k（AM、FM/PKTFM/WFM等FM含みモード）
  }
  else
  {
    selStep = 1; // 100Hz
  }
}

std::vector<String> supportedModes;
struct RigStatus;

bool uiLocked = false;

static unsigned long aprsPressStart = 0;
static unsigned long lastSpkActionTime = 0;
const unsigned long spkLockoutInterval = 2000;
static MenuItem spkTouchTarget = MENU_NONE;
static unsigned long spkPressStart = 0;
static bool spkLongPressActive = false;
const unsigned long spkLongPressMs = 600;

static MenuItem pttTouchTarget = MENU_NONE;
static unsigned long pttPressStart = 0;
static bool pttLongPressActive = false;
const unsigned long pttLongPressMs = 600;

// ★ FreqボタンとStepボタンを統合(短押し=Freq選択、長押し=Step選択)
static MenuItem freqTouchTarget = MENU_NONE;
static unsigned long freqPressStart = 0;
static bool freqLongPressActive = false;
const unsigned long freqLongPressMs = 600;
unsigned long lastRigCmdTime = 0;
const unsigned long rigCmdInterval = 300;
int accumulatedDir = 0;
unsigned long lastEncoderMoveTime = 0;
const unsigned long encoderStopDelay = 150;

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
void updatePTT_UI(bool on);

// ★ 指定パスのコマンドをキューから全て除去するヘルパー
static void drainQueueByPath(const String &path)
{
  HttpCommand *oldCmd;
  int count = uxQueueMessagesWaiting(httpQueue);
  for (int i = 0; i < count; i++)
  {
    if (xQueueReceive(httpQueue, &oldCmd, 0) != pdTRUE)
      break;
    if (oldCmd->path == path)
    {
      delete oldCmd; // 古い同種コマンドを破棄
    }
    else
    {
      xQueueSend(httpQueue, &oldCmd, 0); // 別コマンドは戻す
    }
  }
}

// ---- RGB565 成分空間での線形補間（軽量グラデーション用） ----
static inline uint16_t lerpColor565(uint16_t c0, uint16_t c1, float t)
{
  if (t < 0) t = 0;
  if (t > 1) t = 1;
  int r0 = (c0 >> 11) & 0x1F, g0 = (c0 >> 5) & 0x3F, b0 = c0 & 0x1F;
  int r1 = (c1 >> 11) & 0x1F, g1 = (c1 >> 5) & 0x3F, b1 = c1 & 0x1F;
  int r = r0 + (int)((r1 - r0) * t);
  int g = g0 + (int)((g1 - g0) * t);
  int b = b0 + (int)((b1 - b0) * t);
  return (uint16_t)((r << 11) | (g << 5) | b);
}

// ---- Sメーター用 3区間グラデーション（Blue→Cyan→Yellow→Red） ----
static uint16_t meterColorAt(float frac, uint16_t cBlue, uint16_t cCyan, uint16_t cYellow, uint16_t cRed)
{
  if (frac < 0.6f) return lerpColor565(cBlue, cCyan, frac / 0.6f);
  if (frac < 0.75f) return lerpColor565(cCyan, cYellow, (frac - 0.6f) / 0.15f);
  float t = (frac - 0.75f) / 0.25f;
  if (t > 1.0f) t = 1.0f;
  return lerpColor565(cYellow, cRed, t);
}

// ---- デザインパターン(配色テーマ) ----
static const int UI_THEME_COUNT = 4;
static const char *uiThemeNames[UI_THEME_COUNT] = {"OCN", "AMB", "MONO", "AQUA"};

// 背景色に対して視認性の高い文字色(黒/白)を輝度から自動選択
static uint16_t contrastTextColor(uint16_t bg)
{
  int r = (bg >> 11) & 0x1F, g = (bg >> 5) & 0x3F, b = bg & 0x1F;
  float lum = 0.299f * (r / 31.0f) + 0.587f * (g / 63.0f) + 0.114f * (b / 31.0f);
  return (lum > 0.55f) ? BLACK : WHITE;
}

// テーマ番号(0=OCEAN/1=AMBER/2=MONO) と 昼/夜 からメイン画面の配色一式を決定する。
// カード類(chipBg/btnIdle)はどのテーマ・昼夜でも濃色のままにしてあり、
// 既存の白文字ラベル描画をそのまま流用できるようにしている。
// ★ aprs_received.cpp(コンパス描画)からもテーマ配色を参照するためstaticにしない
void getThemePalette(int theme, bool day,
    uint16_t &bgDark, uint16_t &panelBg, uint16_t &borderCol,
    uint16_t &accentTeal, uint16_t &accentBlue, uint16_t &textSecondary,
    uint16_t &txRed, uint16_t &chipBg, uint16_t &btnIdle, uint16_t &btnIdleBorder,
    uint16_t &spkOnCol, uint16_t &aprsOnCol, uint16_t &navOrange)
{
  auto c = [](uint8_t r, uint8_t g, uint8_t b) { return canvas.color565(r, g, b); };

  switch (theme)
  {
  case 1: // AMBER(真空管/VFD風の琥珀色)
    if (day)
    {
      bgDark = c(0xf7, 0xf0, 0xe2); panelBg = c(0xec, 0xdf, 0xc0); borderCol = c(0x8a, 0x75, 0x50);
      accentTeal = c(0x8a, 0x5a, 0x00); accentBlue = c(0x6b, 0x4c, 0x00); textSecondary = c(0x5c, 0x4a, 0x28);
      txRed = c(0xc8, 0x1e, 0x14); chipBg = c(0x24, 0x1a, 0x0c); btnIdle = c(0x2a, 0x1e, 0x0e);
      btnIdleBorder = c(0x6b, 0x55, 0x30); spkOnCol = c(0x4a, 0x7a, 0x1a); aprsOnCol = c(0xa0, 0x6a, 0x00);
      navOrange = c(0xa0, 0x50, 0x00);
    }
    else
    {
      bgDark = c(0x10, 0x0b, 0x02); panelBg = c(0x1f, 0x15, 0x0a); borderCol = c(0x4a, 0x3a, 0x1a);
      accentTeal = c(0xff, 0xb0, 0x00); accentBlue = c(0xff, 0xcf, 0x40); textSecondary = c(0xa6, 0x8a, 0x55);
      txRed = c(0xff, 0x3b, 0x30); chipBg = c(0x24, 0x1a, 0x0c); btnIdle = c(0x2a, 0x1e, 0x0e);
      btnIdleBorder = c(0x5a, 0x46, 0x20); spkOnCol = c(0x8f, 0xd1, 0x3f); aprsOnCol = c(0xff, 0xcf, 0x40);
      navOrange = c(0xff, 0x8a, 0x00);
    }
    break;

  case 2: // MONO(無彩色・最大コントラスト)
    if (day)
    {
      bgDark = c(0xff, 0xff, 0xff); panelBg = c(0xe6, 0xe6, 0xe6); borderCol = c(0x40, 0x40, 0x40);
      accentTeal = c(0x00, 0x00, 0x00); accentBlue = c(0x20, 0x20, 0x20); textSecondary = c(0x20, 0x20, 0x20);
      txRed = c(0xc0, 0x00, 0x00); chipBg = c(0x1a, 0x1a, 0x1a); btnIdle = c(0x20, 0x20, 0x20);
      btnIdleBorder = c(0x70, 0x70, 0x70); spkOnCol = c(0x30, 0x30, 0x30); aprsOnCol = c(0x00, 0x00, 0x00);
      navOrange = c(0x60, 0x60, 0x60);
    }
    else
    {
      bgDark = c(0x00, 0x00, 0x00); panelBg = c(0x14, 0x14, 0x14); borderCol = c(0x55, 0x55, 0x55);
      accentTeal = c(0xff, 0xff, 0xff); accentBlue = c(0xc0, 0xc0, 0xc0); textSecondary = c(0xaa, 0xaa, 0xaa);
      txRed = c(0xff, 0x20, 0x20); chipBg = c(0x1a, 0x1a, 0x1a); btnIdle = c(0x20, 0x20, 0x20);
      btnIdleBorder = c(0x60, 0x60, 0x60); spkOnCol = c(0xe0, 0xe0, 0xe0); aprsOnCol = c(0xff, 0xff, 0xff);
      navOrange = c(0xb0, 0xb0, 0xb0);
    }
    break;

  case 3: // AQUA(青×緑を基調にした配色)
    if (day)
    {
      bgDark = c(0xea, 0xf5, 0xf0); panelBg = c(0xcf, 0xe8, 0xda); borderCol = c(0x5a, 0x8f, 0x7a);
      accentTeal = c(0x1a, 0xa0, 0x5a); accentBlue = c(0x0a, 0x6c, 0xd9); textSecondary = c(0x2e, 0x4a, 0x3e);
      txRed = c(0xd8, 0x1f, 0x16); chipBg = c(0x14, 0x24, 0x1e); btnIdle = c(0x18, 0x2a, 0x22);
      btnIdleBorder = c(0x4a, 0x66, 0x58); spkOnCol = c(0x22, 0xc1, 0x6b); aprsOnCol = c(0xe0, 0x9a, 0x00);
      navOrange = c(0xe0, 0x7a, 0x00);
    }
    else
    {
      bgDark = c(0x07, 0x14, 0x12); panelBg = c(0x0e, 0x1f, 0x1a); borderCol = c(0x22, 0x3a, 0x30);
      accentTeal = c(0x2a, 0xe8, 0x7a); accentBlue = c(0x3f, 0xa7, 0xff); textSecondary = c(0x7a, 0x9a, 0x8c);
      txRed = c(0xff, 0x3b, 0x30); chipBg = c(0x14, 0x24, 0x1e); btnIdle = c(0x18, 0x2a, 0x22);
      btnIdleBorder = c(0x30, 0x4a, 0x3e); spkOnCol = c(0x34, 0xf0, 0x9a); aprsOnCol = c(0xfb, 0xbf, 0x24);
      navOrange = c(0xff, 0x95, 0x00);
    }
    break;

  default: // 0 = OCEAN(従来配色)
    if (day)
    {
      bgDark = c(0xf0, 0xf2, 0xf5); panelBg = c(0xd9, 0xdd, 0xe2); borderCol = c(0x8a, 0x91, 0x99);
      accentTeal = c(0x00, 0x8f, 0x80); accentBlue = c(0x00, 0x5b, 0xc9); textSecondary = c(0x40, 0x46, 0x4e);
      txRed = c(0xd8, 0x1f, 0x16); chipBg = c(0x1c, 0x21, 0x28); btnIdle = c(0x21, 0x26, 0x2d);
      btnIdleBorder = c(0x55, 0x5c, 0x66); spkOnCol = c(0x1f, 0xa8, 0x77); aprsOnCol = c(0xe0, 0x9a, 0x00);
      navOrange = c(0xe0, 0x7a, 0x00);
    }
    else
    {
      bgDark = c(0x0d, 0x11, 0x17); panelBg = c(0x16, 0x1b, 0x22); borderCol = c(0x30, 0x36, 0x3d);
      accentTeal = c(0x00, 0xd9, 0xc0); accentBlue = c(0x3f, 0xa7, 0xff); textSecondary = c(0x8b, 0x94, 0x9e);
      txRed = c(0xff, 0x3b, 0x30); chipBg = c(0x1c, 0x21, 0x28); btnIdle = c(0x21, 0x26, 0x2d);
      btnIdleBorder = c(0x3a, 0x40, 0x4a); spkOnCol = c(0x34, 0xd3, 0x99); aprsOnCol = c(0xfb, 0xbf, 0x24);
      navOrange = c(0xff, 0x95, 0x00);
    }
    break;
  }
}

void drawMainUI(void)
{
  // ============================================================
  //  カラーパレット
  // ============================================================
  uint16_t bgDark, panelBg, borderCol, accentTeal, accentBlue, textSecondary,
      txRed, chipBg, btnIdle, btnIdleBorder, spkOnCol, aprsOnCol, navOrange;

  getThemePalette(uiTheme, dayMode, bgDark, panelBg, borderCol, accentTeal, accentBlue,
                  textSecondary, txRed, chipBg, btnIdle, btnIdleBorder, spkOnCol, aprsOnCol, navOrange);

  canvas.fillScreen(bgDark);

  // ============================================================
  //  ヘッダーバー（機種名 + TXピル）
  // ============================================================
  canvas.fillRect(0, 0, 320, 22, panelBg);
  canvas.drawFastHLine(0, 22, 320, borderCol);

  canvas.setTextDatum(middle_left);
  canvas.setTextColor(textSecondary);
  canvas.setFont(sharedModel.length() > 14 ? &fonts::efontJA_10 : &fonts::efontJA_12);
  canvas.drawString(sharedModel.c_str(), 8, 11);

  {
    int txW = 42, txH = 16, txX = 320 - txW - 6, txY = 3;
    // ★ APRS送信中はTXピルをオレンジにして、通常のPTT送信(赤)と区別する
    uint16_t txBg = isTransmitting ? (aprsTxInProgress ? navOrange : txRed) : chipBg;
    uint16_t txFg = isTransmitting ? WHITE : textSecondary;
    canvas.fillRoundRect(txX, txY, txW, txH, 8, txBg);
    if (!isTransmitting) canvas.drawRoundRect(txX, txY, txW, txH, 8, borderCol);
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextColor(txFg);
    canvas.setTextDatum(middle_center);
    canvas.drawString("TX", txX + txW / 2, txY + txH / 2 + 1);

    // ★ 日中/夜間 表示モード切替チップ(TXピルの左)
    int dnW = 38, dnH = 16, dnX = txX - dnW - 6, dnY = 3;
    uint16_t dnBg = dayMode ? canvas.color565(0xff, 0xd5, 0x00) : chipBg;
    uint16_t dnFg = dayMode ? BLACK : textSecondary;
    canvas.fillRoundRect(dnX, dnY, dnW, dnH, 8, dnBg);
    if (!dayMode) canvas.drawRoundRect(dnX, dnY, dnW, dnH, 8, borderCol);
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextColor(dnFg);
    canvas.setTextDatum(middle_center);
    canvas.drawString(dayMode ? "DAY" : "NGT", dnX + dnW / 2, dnY + dnH / 2 + 1);

    // ★ デザインパターン(配色テーマ)切替チップ(日中/夜間チップの左)
    //   背景はテーマの代表色(accentTeal)にして、一目でどのテーマか分かるようにする
    int thW = 44, thH = 16, thX = dnX - thW - 6, thY = 3;
    uint16_t thBg = accentTeal;
    uint16_t thFg = contrastTextColor(thBg);
    canvas.fillRoundRect(thX, thY, thW, thH, 8, thBg);
    canvas.drawRoundRect(thX, thY, thW, thH, 8, borderCol);
    canvas.setFont(&fonts::efontJA_12);
    canvas.setTextColor(thFg);
    canvas.setTextDatum(middle_center);
    canvas.drawString(uiThemeNames[uiTheme], thX + thW / 2, thY + thH / 2 + 1);
  }

  // ============================================================
  //  周波数表示（グロー風 + グラデーションアンダーライン）
  // ============================================================
  // ★ グローバルに setTextSize(2) が設定されているため、ここで指定する
  //   efontJA_24/10 等のフォントが実寸の2倍で描画され、被りの原因になっていた。
  //   この区間（周波数～Sメーター～ステータスチップ）だけ等倍に戻し、
  //   ボタン部の描画に入る前に元のサイズへ復元する。
  canvas.setTextSize(1);

  double freqHz = sharedFreq.toDouble();
  double freqMHz = freqHz / 1e6;
  char freqStr[16];
  snprintf(freqStr, sizeof(freqStr), "%.5f", freqMHz);

  canvas.setFont(&fonts::efontJA_24);
  canvas.setTextSize(2); // 周波数表示を倍サイズに
  int freqFontH = canvas.fontHeight(); // 実測フォント高さ（被り防止のため固定値を使わない）
  canvas.setTextDatum(top_right);
  int freqRightX = 276, freqY = 24; // ヘッダ直下まで持ち上げる

  // 日中は明るいページ背景の上に乗るため、白文字ではなく黒文字+淡いグレーのグローにする
  uint16_t freqTextCol = dayMode ? BLACK : WHITE;
  uint16_t freqGlowCol = dayMode ? canvas.color565(0xb5, 0xba, 0xc0) : canvas.color565(0x00, 0x55, 0x50);
  canvas.setTextColor(freqGlowCol); // グロー（淡色を1px下にずらす）
  canvas.drawString(freqStr, freqRightX + 1, freqY + 1);
  canvas.setTextColor(freqTextCol);
  canvas.drawString(freqStr, freqRightX, freqY);

  canvas.setTextSize(1); // 以降（MHz～チップ行）は等倍に戻す
  int freqBottom = freqY + freqFontH;

  canvas.setFont(&fonts::efontJA_10);
  canvas.setTextSize(2); // MHz表示を倍サイズに
  canvas.setTextColor(accentTeal);
  canvas.setTextDatum(bottom_left);
  canvas.drawString("MHz", freqRightX + 4, freqBottom);
  canvas.setTextSize(1); // 以降（Sメーター～チップ行）は等倍に戻す

  int lineY = freqBottom + 2;
  canvas.drawGradientHLine(10, lineY, 300, accentTeal, accentBlue);

  // ============================================================
  //  Sメーター（数値目盛は廃止し色のグラデーションだけで強度を表現）
  // ============================================================
  int chipY; // チップ行Yはメーター描画後に確定するため先に宣言
  {
    int mX = 10, mY = lineY + 3, mW = 300, mH = 8;
    canvas.fillRoundRect(mX - 1, mY - 1, mW + 2, mH + 2, 3, chipBg);

    uint16_t cBlue   = accentBlue;
    uint16_t cCyan   = accentTeal;
    uint16_t cYellow = canvas.color565(0xff, 0xd5, 0x00);
    uint16_t cRed    = txRed;
    uint16_t trackCol = canvas.color565(0x23, 0x29, 0x31);

    int filled = constrain((int)(sharedSignal / 15.0f * mW), 0, mW);
    for (int i = 0; i < mW; i += 2)
    {
      uint16_t col = (i < filled) ? meterColorAt((float)i / mW, cBlue, cCyan, cYellow, cRed) : trackCol;
      canvas.fillRect(mX + i, mY, 2, mH, col);
    }

    chipY = mY + mH + 5;
  }

  // ============================================================
  //  ステータスチップ（Step / Pow / Mode / Width）※SQLはSQLボタン側に表示
  // ============================================================
  {
    struct Chip { const char *label; String value; uint16_t accent; } chips[4];
    char buf[16];

    chips[0] = {"ST", String(stepList[selStep].label), accentBlue};
    snprintf(buf, sizeof(buf), "%d%%", (int)(sharedPower * 100));
    chips[1] = {"PW", String(buf), navOrange};
    chips[2] = {"MD", sharedMode, accentTeal};
    snprintf(buf, sizeof(buf), "%d", sharedWidth);
    chips[3] = {"WD", String(buf), canvas.color565(0xbf, 0x5a, 0xf2)};

    canvas.setFont(&fonts::efontJA_14);
    canvas.setTextDatum(middle_left);
    int chipH = canvas.fontHeight() + 4, gap = 4; // 実測フォント高さ＋余白（被り防止のため固定値を使わない）
    int chipW = (300 - gap * 3) / 4;
    int chipX = 10;

    for (int i = 0; i < 4; i++)
    {
      int x = chipX + i * (chipW + gap);
      canvas.fillRoundRect(x, chipY, chipW, chipH, 4, chipBg);
      canvas.fillRect(x, chipY + 2, 2, chipH - 4, chips[i].accent);
      canvas.setTextColor(textSecondary);
      canvas.drawString(chips[i].label, x + 6, chipY + chipH / 2);
      int labelW = canvas.textWidth(chips[i].label);
      canvas.setTextColor(WHITE);
      canvas.drawString(chips[i].value.c_str(), x + 6 + labelW + 4, chipY + chipH / 2);
    }
  }

  canvas.setTextSize(2); // 元のサイズへ復元（ボタン以下はこれまでと同じ描画）

  // ============================================================
  //  ボタン定義
  // ============================================================
  struct Button
  {
    const char *label;
    MenuItem id;
  } buttons[12] = {
      {"A/B", MENU_VFOTOGGLE},
      {"Freq", MENU_FREQ},
      {"Mode", MENU_MODE},
      {"Pow", MENU_POW},
      {"Wid", MENU_WIDTH},
      {"SQL", MENU_SQL},
      {"APRS", MENU_APRS},
      {"PTT", MENU_PTT},
      {"Back", MENU_BACK},
      {"SPK", MENU_SPK},
      {"DOWN", MENU_DOWN},
      {"UP", MENU_UP}};

  // --- ボタン描画（横幅最大化） ---
  // ★ チップ行が下に拡張された分、ボタン側を詰めて被りを防止
  int btnW = 72, btnH = 34;
  int spacingX = 4, spacingY = 6;
  int startX = (320 - (btnW * 4 + spacingX * 3)) / 2;
  int startY = 114;

  canvas.setFont(&fonts::efontJA_16);
  canvas.setTextDatum(middle_center);

  for (int i = 0; i < 12; ++i)
  {
    int row = i / 4;
    int col = i % 4;
    int x = startX + col * (btnW + spacingX);
    int y = startY + row * (btnH + spacingY);

    uint16_t color;
    uint16_t borderShade = btnIdleBorder;
    bool darkText = false;

    if (buttons[i].id == MENU_BACK)
    {
      color = txRed;
    }
    else if (buttons[i].id == MENU_FREQ)
    {
      if (selectedItem == MENU_STEP)
      {
        // ★ 長押しでStep選択モード中は選択色で強調表示
        color = accentTeal;
        borderShade = accentTeal;
        darkText = true;
      }
      else
      {
        color = (selectedItem == MENU_FREQ) ? accentTeal : btnIdle;
        if (selectedItem == MENU_FREQ) { borderShade = accentTeal; darkText = true; }
      }
    }
    else if (buttons[i].id == MENU_PTT)
    {
      if (selectedItem == MENU_MICGAIN)
      {
        // ★ 長押しでMic Gain調整モード中は選択色で強調表示
        color = accentTeal;
        darkText = true;
      }
      else
      {
        color = txEnabled ? canvas.color565(0xff, 0x55, 0x77) : canvas.color565(0x3a, 0x3f, 0x8f);
      }
    }
    else if (buttons[i].id == MENU_SPK)
    {
      // ★ サンプリングレート 0 → スピーカー無効（グレーアウト）
      if (samplingRates[selSampling] == 0)
      {
        color = btnIdleBorder; // グレーアウト
      }
      else if (selectedItem == MENU_RVOL)
      {
        // ★ 長押しで音量調整モード中は選択色で強調表示
        color = accentTeal;
        darkText = true;
      }
      else
      {
        color = spkEnabled ? spkOnCol : canvas.color565(0x1f, 0x6f, 0x4a);
        if (spkEnabled) darkText = true;
      }
    }
    else if (buttons[i].id == MENU_APRS)
    {
      if (!aprsEnabled)
      {
        color = btnIdleBorder; // APRS 機能 OFF
      }
      else
      {
        if (aprsActive) { color = aprsOnCol; darkText = true; } // 送信 ON
        else color = canvas.color565(0x7a, 0x5c, 0x00); // 送信 OFF
      }
    }
    else if (buttons[i].id == MENU_UP || buttons[i].id == MENU_DOWN)
    {
      color = navOrange;
      darkText = true;
    }
    else
    {
      // ★ 他のボタンは selectedItem で色を決める
      color = (buttons[i].id == selectedItem) ? accentTeal : btnIdle;
      if (buttons[i].id == selectedItem) { borderShade = accentTeal; darkText = true; }
    }

    uint16_t textColor = darkText ? BLACK : WHITE;

    canvas.fillRoundRect(x, y, btnW, btnH, 6, color);
    canvas.drawRoundRect(x, y, btnW, btnH, 6, borderShade);
    // 上部ハイライト（簡易ベベルで立体感を出す）
    uint16_t hiColor = lerpColor565(color, WHITE, 0.25f);
    canvas.drawFastHLine(x + 6, y + 2, btnW - 12, hiColor);

    canvas.setTextColor(textColor);

    // ★ SQL/Powボタンは選択した瞬間、または操作後5秒間は設定値（%）を表示
    char valBuf[8];
    const char *label = buttons[i].label;
    if (buttons[i].id == MENU_FREQ && selectedItem == MENU_STEP)
    {
      // ★ Freq長押しでStep選択モード中は現在のStep幅を表示
      label = stepList[selStep].label;
    }
    else if (buttons[i].id == MENU_VFOTOGGLE)
    {
      // ★ 現在どちら側か分かっていればその実際の状態(Main/Sub/A/B)を、
      //   未取得ならモード種別(M/S・A/B)を表示する
      if (vfoCurrentSide.length() > 0)
        label = vfoCurrentSide.c_str();
      else
        label = (vfoModeStr == "mainsub") ? "M/S" : "A/B";
    }
    else if (buttons[i].id == MENU_SQL &&
        (selectedItem == MENU_SQL || millis() - lastUserSQLChange < 5000))
    {
      snprintf(valBuf, sizeof(valBuf), "%d%%", (int)(sharedSQL * 100));
      label = valBuf;
    }
    else if (buttons[i].id == MENU_POW &&
             (selectedItem == MENU_POW || millis() - lastUserPowerChange < 5000))
    {
      snprintf(valBuf, sizeof(valBuf), "%d%%", (int)(sharedPower * 100));
      label = valBuf;
    }
    else if (buttons[i].id == MENU_SPK &&
             (selectedItem == MENU_RVOL || millis() - lastUserVolumeChange < 5000))
    {
      // ★ SPK長押しで音量調整モード中、または操作後5秒間は音量(%)を表示
      snprintf(valBuf, sizeof(valBuf), "%d%%", (int)(currentVolume * 100));
      label = valBuf;
    }
    else if (buttons[i].id == MENU_PTT &&
             (selectedItem == MENU_MICGAIN || millis() - lastUserMicGainChange < 5000))
    {
      // ★ PTT長押しでMic Gain調整モード中、または操作後5秒間はゲイン(dB)を表示
      snprintf(valBuf, sizeof(valBuf), "%ddB", es8388MicGainIdx * 3);
      label = valBuf;
    }
    else if (buttons[i].id == MENU_APRS && aprsUseRigModem && aprsActive)
    {
      // ★ FTX-1内蔵モデム使用時、送信中はプリセット(AP96=プリセット1 / AP12=プリセット2)を表示
      label = aprsAtPreset2 ? "AP12" : "AP96";
    }
    canvas.drawString(label, x + btnW / 2, y + btnH / 2);
  }

  // ★ APRS受信ポップアップ(無線機のビーコン受信表示のように、受信した瞬間に
  //   コールサイン・方位コンパス・距離を大きく表示する)。他の描画の上に重ねて出す。
  if (aprsToastUntilMs != 0 && millis() < aprsToastUntilMs)
  {
    int px = 50, py = 28, pw = 220, ph = 185;
    // ★ 現在のテーマ(配色パターン・昼夜)に合わせた色(コンパスと統一感を出す)
    uint16_t popupAccent = accentBlue;
    canvas.fillRoundRect(px, py, pw, ph, 8, BLACK);
    canvas.drawRoundRect(px, py, pw, ph, 8, popupAccent);

    canvas.setFont(&fonts::efontJA_16_b);
    canvas.setTextColor(popupAccent);
    canvas.setTextDatum(top_center);
    canvas.drawString(aprsToastCall, px + pw / 2, py + 8);
    canvas.setTextDatum(top_left);

    if (aprsToastHasPos)
    {
      float distKm = aprsDistanceKm(aprsManualLat, aprsManualLon, aprsToastLat, aprsToastLon);
      float bearing = aprsBearingDeg(aprsManualLat, aprsManualLon, aprsToastLat, aprsToastLon);
      const char *dir = aprsCompassDir(aprsManualLat, aprsManualLon, aprsToastLat, aprsToastLon);

      // ★ コンパスのS方位ラベルと距離/方角の行との間隔を広めに取り、重なって見えないようにする
      drawCompassIcon(px + pw / 2, py + 98, 42, bearing, true);

      // ★ 距離表示とコンパス方角表示の間にリグ(局)のシンボルアイコンを置く
      int rowY = py + ph - 20;
      canvas.setFont(&fonts::efontJA_12);
      canvas.setTextColor(WHITE);
      char distBuf[16];
      snprintf(distBuf, sizeof(distBuf), "%.1f km", distKm);
      canvas.setTextDatum(middle_right);
      canvas.drawString(distBuf, px + pw / 2 - 16, rowY);

      drawAprsSymbolIcon(toastSymbolCode(), px + pw / 2, rowY, false);

      canvas.setTextDatum(middle_left);
      canvas.drawString(dir, px + pw / 2 + 16, rowY);
      canvas.setTextDatum(top_left);
    }
    else if (aprsToastComment.length() > 0)
    {
      canvas.setFont(&fonts::lgfxJapanGothic_8);
      canvas.setTextColor(LIGHTGREY);
      canvas.setTextDatum(top_center);
      canvas.drawString(aprsToastComment, px + pw / 2, py + ph / 2 + 10);
      canvas.setTextDatum(top_left);
    }
    canvas.setFont(&fonts::efontJA_12);
  }

  canvas.pushSprite(0, 0);
}

MenuItem detectTouchedButton(int x, int y)
{
  // ★ drawMainControlScreen のボタン位置定数と一致させること
  int btnW = 72, btnH = 34;
  int spacingX = 4, spacingY = 6;
  int startX = (320 - (btnW * 4 + spacingX * 3)) / 2;
  int startY = 114;

  struct Button
  {
    MenuItem id;
    int row, col;
  } buttons[] = {
      {MENU_VFOTOGGLE, 0, 0},
      {MENU_FREQ, 0, 1},
      {MENU_MODE, 0, 2},
      {MENU_POW, 0, 3},
      {MENU_WIDTH, 1, 0},
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

// ★ 無線機の「ビーコン受信」表示のように、新規に受信した局をメイン画面上部に一時表示する。
//   実際の抑制(同一局は一定時間だけ再通知しない)はPi側(/aprs_notify)で行っている。
// ★ 接続直後(通常フロー・飛ばしモード両方)に呼ぶ。この機種がVFO A/B型かMAIN/SUB型か、
//   および現在どちら側かをPiに問い合わせてvfoModeStr/vfoCurrentSideへ反映する。
void fetchVfoState()
{
  HTTPClient modeHttp;
  String modeUrl = "http://" + HostName + ":" + String(apiPort) + "/radio/vfo_mode";
  modeHttp.begin(modeUrl);
  modeHttp.setConnectTimeout(2000);
  modeHttp.setTimeout(2000);
  if (!apiKey.isEmpty()) modeHttp.addHeader("X-API-Key", apiKey);
  int modeCode = modeHttp.GET();
  Serial.printf("[vfo] GET /radio/vfo_mode code=%d\n", modeCode);
  if (modeCode == 200)
  {
    String body = modeHttp.getString();
    JsonDocument doc;
    if (!deserializeJson(doc, body) && doc["mode"].is<const char *>())
      vfoModeStr = String(doc["mode"].as<const char *>());
  }
  modeHttp.end();

  HTTPClient curHttp;
  String curUrl = "http://" + HostName + ":" + String(apiPort) + "/radio/vfo_current";
  curHttp.begin(curUrl);
  curHttp.setConnectTimeout(2000);
  curHttp.setTimeout(2000);
  if (!apiKey.isEmpty()) curHttp.addHeader("X-API-Key", apiKey);
  int curCode = curHttp.GET();
  Serial.printf("[vfo] GET /radio/vfo_current code=%d\n", curCode);
  if (curCode == 200)
  {
    String body = curHttp.getString();
    JsonDocument doc;
    if (!deserializeJson(doc, body) && doc["side"].is<const char *>())
      vfoCurrentSide = String(doc["side"].as<const char *>());
  }
  curHttp.end();
  Serial.printf("[vfo] vfoModeStr=%s vfoCurrentSide=%s\n", vfoModeStr.c_str(), vfoCurrentSide.c_str());
}

void pollAprsNotify()
{
  // トースト表示期限が切れていたら消して1回だけ再描画
  if (aprsToastUntilMs != 0 && millis() > aprsToastUntilMs)
  {
    aprsToastUntilMs = 0;
    drawMainUI();
  }

  static unsigned long lastPoll = 0;
  // ★ 起動後(またはAPRS ON後)最初の1回はPiに溜まっていた古い受信イベントを
  //   読み捨てるだけにし、ポップアップは出さない。次回以降の新着だけ表示する。
  static bool firstFetchDone = false;
  if (millis() - lastPoll < 3000)
    return;
  lastPoll = millis();

  HTTPClient http;
  http.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_notify");
  if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
  int code = http.GET();
  if (code == 200)
  {
    String payload = http.getString();
    JsonDocument doc;
    if (!deserializeJson(doc, payload))
    {
      JsonArray events = doc["events"].as<JsonArray>();
      if (!firstFetchDone)
      {
        firstFetchDone = true;
      }
      else if (events.size() > 0)
      {
        // ★ 複数溜まっていた場合は最新の1件だけ表示すれば十分
        JsonObject ev = events[events.size() - 1];
        aprsToastCall = ev["call"].is<const char *>() ? String(ev["call"].as<const char *>()) : "?";
        aprsToastComment = ev["comment"].is<const char *>() ? String(ev["comment"].as<const char *>()) : "";
        aprsToastSymbol = ev["symbol"].is<const char *>() ? String(ev["symbol"].as<const char *>()) : "";
        aprsToastHasPos = !ev["lat"].isNull();
        if (aprsToastHasPos)
        {
          aprsToastLat = ev["lat"].as<float>();
          aprsToastLon = ev["lon"].as<float>();
        }
        aprsToastUntilMs = millis() + 7000;
        drawMainUI();
      }
    }
  }
  http.end();
}

// ★ Main/SubボタンをPiが実際に把握している状態(rig_cycle_idx)に定期的に追従させる。
//   連打で要求がPi側の排他ロックに拒否された場合や、CATコマンドが実機に届かなかった
//   場合でも、このポーリングにより最終的にボタン表示と実機の状態のズレが解消される。
//   A/B機種はトグル応答だけで十分信頼できるためポーリング対象外(通信量削減)。
void pollVfoState()
{
  if (vfoModeStr != "mainsub")
    return;

  static unsigned long lastPoll = 0;
  if (millis() - lastPoll < 2000)
    return;
  lastPoll = millis();

  HTTPClient http;
  http.begin("http://" + HostName + ":" + String(apiPort) + "/radio/vfo_current");
  http.setConnectTimeout(2000);
  http.setTimeout(2000);
  if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
  int code = http.GET();
  if (code == 200)
  {
    String body = http.getString();
    JsonDocument doc;
    if (!deserializeJson(doc, body) && doc["side"].is<const char *>())
    {
      String newSide = String(doc["side"].as<const char *>());
      if (newSide != vfoCurrentSide)
      {
        vfoCurrentSide = newSide;
        drawMainUI();
      }
    }
  }
  http.end();
}

void handleMainUIScreen()
{
  if (appState != STATE_MAIN_UI)
    return;

  // ★ APRS受信ポップアップ(APRS機能が有効な間だけポーリング)
  if (aprsEnabled)
    pollAprsNotify();

  // ★ Main/Subボタン表示を実機の状態に追従(連打対策・ズレ解消)
  pollVfoState();

  // ★ APRS heartbeat（常に 10 秒ごと）
  if (aprsActive)
  {
    // GPSモードで位置情報が途絶えたらAPRSを自動停止（送信され続けるのを防ぐ）
    // ★ FTX-1内蔵モデム使用時はリグ自身のGPSを使うため、この自動停止の対象外とする。
    if (aprsUseGPS && !aprsUseRigModem && !gpsFixIsFresh(APRS_GPS_STALE_MS))
    {
      HTTPClient stopHttp;
      stopHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_stop");
      if (!apiKey.isEmpty()) stopHttp.addHeader("X-API-Key", apiKey);
      stopHttp.POST("");
      stopHttp.end();
      aprsActive = false;
      mainFirstDraw = true;
    }
    else
    {
      static unsigned long lastBeat = 0;
      if (millis() - lastBeat > 10000)
      {
        HTTPClient http;
        http.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_heartbeat");
        if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
        http.POST("");
        http.end();
        lastBeat = millis();
      }
    }
  }

  if (aprsTxInProgress)
  {
    auto t = M5.Touch.getDetail();
    if (t.wasPressed())
    {
      MenuItem touched = detectTouchedButton(t.x, t.y);
      if (touched != MENU_PTT && touched != MENU_SPK)
      {
        // ★ APRS TX中はタッチ操作だけ無効化
        // ただし return しない（画面更新や fetchRigStatus を止めない）
        touched = MENU_NONE;
      }
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
#ifdef M5TOUGH
  static int lastPos = 0;
  int pos = encoderPos; // ← main.cpp のグローバル
  int diff = pos - lastPos;

  if (diff != 0)
  {
    int dir = (diff > 0) ? +1 : -1;
    accumulatedDir += dir;
    lastEncoderMoveTime = millis();
    lastPos = pos;
  }
#else
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
#endif

  // --- エンコーダ蓄積処理（最新だけ送る方式） ---
  if (accumulatedDir != 0 &&
      (millis() - lastEncoderMoveTime > encoderStopDelay))
  {
    int steps = accumulatedDir;

    // ★ ソフトPTT(送信)中はエンコーダーを周波数専用にする（誤操作でモード等を
    //   変更してしまうのを防ぐ。選択中の項目に関わらず常に周波数が動く）
    int effItem = txEnabled ? (int)MENU_FREQ : selectedItem;

    // ★ Step は即時反映・インターバル無視でOK
    if (effItem == MENU_STEP)
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

      switch (effItem)
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
        applyDefaultStep(sharedMode);
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
        if (audioMutex && xSemaphoreTake(audioMutex, pdMS_TO_TICKS(10)) == pdTRUE)
        {
          if (out) out->SetGain(currentVolume);
          xSemaphoreGive(audioMutex);
        }
        break;
      }

      case MENU_MICGAIN:
      {
        // ES8388マイクのPGAゲイン(0-8 = 0～24dBを3dB刻みで調整)
        es8388MicGainIdx += steps;
        es8388MicGainIdx = constrain(es8388MicGainIdx, 0, 8);
        lastUserMicGainChange = millis();

        prefs.begin("device", false);
        prefs.putInt("micGainIdx", es8388MicGainIdx);
        prefs.end();
        // 実際のPGA反映は次回TX開始時(startAudioTx)に行われる
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

  // --- SPK音量調整モードの自動解除(5秒無操作) ---
  if (selectedItem == MENU_RVOL && millis() - lastUserVolumeChange > 5000)
  {
    selectedItem = MENU_NONE;
    drawMainUI();
  }

  // --- Mic Gain調整モードの自動解除(5秒無操作) ---
  if (selectedItem == MENU_MICGAIN && millis() - lastUserMicGainChange > 5000)
  {
    selectedItem = MENU_NONE;
    drawMainUI();
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

    // --- 日中/夜間モード切替チップ(ヘッダー右、TXピルの左)---
    // ★ drawMainUI() 側のチップ位置定数と一致させること。PTT中も切替可能。
    {
      int txW = 42, txX = 320 - txW - 6;
      int dnW = 38, dnH = 16, dnX = txX - dnW - 6, dnY = 3;
      if (t.x >= dnX && t.x <= dnX + dnW && t.y >= dnY && t.y <= dnY + dnH)
      {
        dayMode = !dayMode;
        M5.Display.setBrightness(dayMode ? 255 : nightBrightness);
        prefs.begin("device", false);
        prefs.putBool("dayMode", dayMode);
        prefs.end();
        drawMainUI();
        return;
      }

      // --- デザインパターン(配色テーマ)切替チップ ---
      int thW = 44, thH = 16, thX = dnX - thW - 6, thY = 3;
      if (t.x >= thX && t.x <= thX + thW && t.y >= thY && t.y <= thY + thH)
      {
        uiTheme = (uiTheme + 1) % UI_THEME_COUNT;
        prefs.begin("device", false);
        prefs.putInt("uiTheme", uiTheme);
        prefs.end();
        drawMainUI();
        return;
      }
    }

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

    if (touched == MENU_SPK)
    {
      spkTouchTarget = MENU_SPK;
      spkPressStart = millis();
      spkLongPressActive = false;
    }
    else
    {
      spkTouchTarget = MENU_NONE;
    }

    if (touched == MENU_PTT)
    {
      pttTouchTarget = MENU_PTT;
      pttPressStart = millis();
      pttLongPressActive = false;
    }
    else
    {
      pttTouchTarget = MENU_NONE;
    }

    // ★ PTT送信中はFreq/Stepの切替を禁止する(元のFreqボタンと同じ挙動を維持)
    if (touched == MENU_FREQ && !txEnabled)
    {
      freqTouchTarget = MENU_FREQ;
      freqPressStart = millis();
      freqLongPressActive = false;
    }
    else
    {
      freqTouchTarget = MENU_NONE;
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
      if (useCIV)
      {
        // CI-VはPi専用のリグ選択/CATデバイス選択画面を経由しないため、
        // 直接CI-V接続画面へ戻す
        civDisconnect();
        appState = STATE_PI_CONNECT;
        rigConnectFirstDraw = true;
      }
      else
      {
        appState = STATE_DEVICE_SELECT;
        rigSelectFirstDraw = true;
        rigConnectFirstDraw = true;
      }
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
      // ★ PTTはレイテンシが重要なため押した瞬間に即切替する(長押し判定は別途並行して行う)
      // ★ 連打対策: ON/OFF切替はCI-V交信・I2Sハードウェア切替を伴う重い処理のため、
      //   短時間の連続タップを無視して処理待ちの積み上がり(フリーズに見える遅延)を防ぐ。
      static unsigned long lastPttTouch = 0;
      unsigned long now = millis();
      if (now - lastPttTouch < 500) return;
      lastPttTouch = now;

      txEnabled = !txEnabled;
      updatePTT_UI(txEnabled);
      return;
    }

    else if (touched == MENU_SPK)
    {
      if (samplingRates[selSampling] == 0)
      {
        return;
      }
      // ★ 長押しで音量調整モードへ入れるよう、ミュート切替は指を離した時点まで遅延する
      return;
    }

    else if (touched == MENU_FREQ)
    {
      // ★ 長押しでStep選択モードへ入れるよう、短押しの選択切替は指を離した時点まで遅延する
      return;
    }

    else if (touched == MENU_VFOTOGGLE)
    {
      // ★ VFO A/B または MAIN/SUB 切替。連打対策のみ入れて即座に実行する。
      static unsigned long lastVfoToggleTouch = 0;
      unsigned long now = millis();
      if (now - lastVfoToggleTouch < 500) return;
      lastVfoToggleTouch = now;

      Serial.println("[vfo] VFOTOGGLE tapped");

      HTTPClient vfoHttp;
      vfoHttp.begin("http://" + HostName + ":" + String(apiPort) + "/radio/vfo_toggle");
      vfoHttp.setConnectTimeout(3000);
      vfoHttp.setTimeout(3000);
      if (!apiKey.isEmpty()) vfoHttp.addHeader("X-API-Key", apiKey);
      int vfoCode = vfoHttp.POST("");
      Serial.printf("[vfo] POST /radio/vfo_toggle code=%d\n", vfoCode);

      bool ok = false;
      if (vfoCode == 200)
      {
        String body = vfoHttp.getString();
        JsonDocument doc;
        if (!deserializeJson(doc, body) && doc["side"].is<const char *>())
        {
          vfoCurrentSide = String(doc["side"].as<const char *>());
          ok = true;
          Serial.printf("[vfo] toggled -> %s\n", vfoCurrentSide.c_str());
        }
      }
      vfoHttp.end();

      if (!ok)
      {
        // ★ Piに届いていない/失敗した場合、画面上でも分かるようにする(シリアルが見れない場合の切り分け用)
        canvas.fillRect(60, 80, 200, 80, BLACK);
        canvas.drawRect(60, 80, 200, 80, RED);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("VFO toggle failed", 160, 110);
        char codeBuf[24];
        snprintf(codeBuf, sizeof(codeBuf), "HTTP code=%d", vfoCode);
        canvas.drawString(codeBuf, 160, 140);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);
        delay(1200);
      }

      drawMainUI();
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

  // --- SPK ボタン長押し・短押し処理 ---
  if (spkTouchTarget == MENU_SPK)
  {
    // 押している間(長押し判定): 音量調整モードへ
    if (t.isPressed())
    {
      if (!spkLongPressActive && millis() - spkPressStart > spkLongPressMs)
      {
        spkLongPressActive = true;
        selectedItem = MENU_RVOL;
        lastUserVolumeChange = millis(); // 無操作タイムアウトの起点
        drawMainUI();
      }
    }

    if (t.wasReleased())
    {
      spkTouchTarget = MENU_NONE;

      if (!spkLongPressActive)
      {
        // 短押し: 従来通りミュート切替(音量調整モード中だった場合は抜ける)
        bool wasVolMode = (selectedItem == MENU_RVOL);
        if (wasVolMode) selectedItem = MENU_NONE;

        unsigned long now = millis();
        if (samplingRates[selSampling] != 0 && now - lastSpkActionTime > spkLockoutInterval)
        {
          lastSpkActionTime = now;

          spkEnabled = !spkEnabled;
          drawMainUI();

          // outが使える状態ならSetGain（ミューテックス保護）
          if (connected && streamTaskHandle == nullptr)
          {
            if (audioMutex && xSemaphoreTake(audioMutex, pdMS_TO_TICKS(10)) == pdTRUE)
            {
              if (out) out->SetGain(currentVolume);
              xSemaphoreGive(audioMutex);
            }
          }
        }
      }
      // 長押しで音量調整モードに入った場合は、指を離してもモードを維持し、
      // エンコーダでの調整を続けられるようにする。
      spkLongPressActive = false;
    }
  }

  // --- Freq ボタン長押し・短押し処理 (長押しでStep選択モードへ) ---
  if (freqTouchTarget == MENU_FREQ)
  {
    if (t.isPressed())
    {
      if (!freqLongPressActive && millis() - freqPressStart > freqLongPressMs)
      {
        freqLongPressActive = true;
        selectedItem = MENU_STEP;
        drawMainUI();
      }
    }

    if (t.wasReleased())
    {
      freqTouchTarget = MENU_NONE;

      if (!freqLongPressActive)
      {
        // 短押し: Freq選択のトグル(従来のStepボタン統合前と同じ挙動)
        if (selectedItem == MENU_FREQ)
          selectedItem = MENU_NONE;
        else
          selectedItem = MENU_FREQ;
        drawMainUI();
      }
      // 長押しでStep選択モードに入った場合は、指を離してもモードを維持する。
      freqLongPressActive = false;
    }
  }

  // --- PTT ボタン長押し・短押し処理 ---
  if (pttTouchTarget == MENU_PTT)
  {
    // 押している間(長押し判定): Mic Gain調整モードへ(ES8388のPGAゲイン)
    if (t.isPressed())
    {
      if (!pttLongPressActive && millis() - pttPressStart > pttLongPressMs)
      {
        pttLongPressActive = true;
        selectedItem = MENU_MICGAIN;
        lastUserMicGainChange = millis(); // 無操作タイムアウトの起点
        drawMainUI();
      }
    }

    if (t.wasReleased())
    {
      pttTouchTarget = MENU_NONE;

      if (pttLongPressActive)
      {
        // 長押し(Gain調整モードへ移行): 押した瞬間に切り替わったPTT状態を
        // 元に戻す(実際の送信意図ではないため)。Gain調整モード自体は維持し、
        // エンコーダでの調整を続けられるようにする。
        txEnabled = !txEnabled;
        updatePTT_UI(txEnabled);
      }
      pttLongPressActive = false;
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

      bool wantActive;
      bool aprsConfigOnly = false; // AP96→AP12: config update only, no /aprs_start
      if (aprsUseRigModem)
      {
        // ★ FTX-1内蔵モデム使用時: 短押しのたびに OFF -> AP96(プリセット1) -> AP12(プリセット2) -> OFF
        //   とサイクルする。AP96→AP12は稼働中のままプリセット(周波数/ボーレート)だけ切り替える。
        if (!aprsActive)
        {
          aprsTxFreq = aprsPreset1Freq;
          aprsBaud = aprsPreset1Baud;
          aprsAtPreset2 = false;
          wantActive = true;
        }
        else if (!aprsAtPreset2)
        {
          // AP96→AP12: 設定更新のみ。/aprs_startは不要(ビーコンはそのまま継続)
          aprsTxFreq = aprsPreset2Freq;
          aprsBaud = aprsPreset2Baud;
          aprsAtPreset2 = true;
          wantActive = true;
          aprsConfigOnly = true;
        }
        else
        {
          wantActive = false;
        }
      }
      else
      {
        wantActive = !aprsActive;
      }

      // GPSモードで位置情報が未取得/古い場合、バックグラウンドの30秒周期を待たず
      // その場で同期的に1回だけAndroidへ取りに行く(Android版は起動直前に必ず現在地を
      // 取得しており、M5もそれに合わせて初回取得の失敗を即リトライする)
      // ★ FTX-1内蔵モデム使用時はリグ自身のGPSで位置情報を付加するため、
      //   M5側(Android中継)の位置情報取得状況とは無関係にAPRSを動作させてよい。
      if (wantActive && aprsUseGPS && !aprsUseRigModem && !gpsFixIsFresh(APRS_GPS_STALE_MS))
      {
        float onDemandLat, onDemandLon;
        fetchGpsNow(onDemandLat, onDemandLon);
      }

      // GPSモードで位置情報が未取得/古い場合はAPRS開始を拒否(FTX-1内蔵モデム使用時は対象外)
      if (wantActive && aprsUseGPS && !aprsUseRigModem && !gpsFixIsFresh(APRS_GPS_STALE_MS))
      {
        canvas.fillRect(60, 80, 200, 80, BLACK);
        canvas.drawRect(60, 80, 200, 80, RED);
        canvas.setTextDatum(middle_center);
        canvas.setTextColor(WHITE);
        canvas.drawString("GPS location unavailable", 160, 105);
        canvas.drawString("Cannot start APRS", 160, 135);
        canvas.setTextDatum(top_left);
        canvas.pushSprite(0, 0);
        delay(1200);
        drawMainUI();
        aprsTouchTarget = MENU_NONE;
        return;
      }

      if (wantActive)
      {
        // APRS 設定を送信する(Android版と同じく、失敗したらstartへ進まない)
        HTTPClient cfgHttp;
        cfgHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_config");
        cfgHttp.addHeader("Content-Type", "application/json");
        if (!apiKey.isEmpty()) cfgHttp.addHeader("X-API-Key", apiKey);

        String cfg = "{";
        cfg += "\"callsign\":\"" + aprsCallsign + "\",";
        cfg += "\"ssid\":" + String(aprsSSID) + ",";
        cfg += "\"path\":\"" + aprsPath + "\",";
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
        cfg += "\"cat_device\":\"" + aprsCatDevice + "\",";
        cfg += "\"use_rig_modem\":" + String(aprsUseRigModem ? "true" : "false") + ",";
        cfg += "\"modem_sel\":" + String(aprsModemSel) + ",";
        cfg += "\"enabled\":" + String(aprsEnabled ? "true" : "false") + ",";
        cfg += "\"heard_suppress_sec\":" + String(aprsHeardSuppressSec);
        cfg += "}";

        int cfgCode = cfgHttp.POST(cfg);
        cfgHttp.end();

        if (aprsConfigOnly)
        {
          // AP96→AP12: 設定更新のみ。ビーコンは継続。次のハートビートでサーバーが再設定する。
          aprsActive = (cfgCode == 200);
          if (!aprsActive)
          {
            canvas.fillRect(60, 80, 200, 80, BLACK);
            canvas.drawRect(60, 80, 200, 80, RED);
            canvas.setTextDatum(middle_center);
            canvas.setTextColor(WHITE);
            canvas.drawString("APRS config failed", 160, 120);
            canvas.setTextDatum(top_left);
            canvas.pushSprite(0, 0);
            delay(1200);
          }
        }
        else
        {
          bool started = false;
          if (cfgCode == 200)
          {
            // ★ Android版と同じく、Pi側でdirewolfが設定を反映して再起動するのを待つ
            //   (500msでは短く、再起動中にaprs_startを送って失敗することがあった)
            delay(1500);

            // APRS 開始
            HTTPClient startHttp;
            startHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_start");
            startHttp.addHeader("Content-Type", "application/json");
            if (!apiKey.isEmpty()) startHttp.addHeader("X-API-Key", apiKey);

            String body = "{";
            body += "\"freq\":" + String(aprsTxFreq) + ",";
            body += "\"interval\":" + String(aprsIntervalSec);
            body += "}";

            int startCode = startHttp.POST(body);
            startHttp.end();
            started = (startCode == 200);
          }

          aprsActive = started;

          if (!started)
          {
            canvas.fillRect(60, 80, 200, 80, BLACK);
            canvas.drawRect(60, 80, 200, 80, RED);
            canvas.setTextDatum(middle_center);
            canvas.setTextColor(WHITE);
            canvas.drawString("APRS start failed", 160, 110);
            canvas.drawString("No response from Pi", 160, 140);
            canvas.setTextDatum(top_left);
            canvas.pushSprite(0, 0);
            delay(1200);
          }
        }
      }
      else
      {
        // APRS 停止
        HTTPClient stopHttp;
        stopHttp.begin("http://" + HostName + ":" + String(apiPort) + "/aprs_stop");
        if (!apiKey.isEmpty()) stopHttp.addHeader("X-API-Key", apiKey);
        stopHttp.POST("");
        stopHttp.end();
        aprsActive = false;
      }

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

  if (useCIV)
  {
    st = civFetchRigStatus();
    if (!st.valid)
      return st;
  }
  else
  {
    HTTPClient http;
    String url = "http://" + HostName + ":" + String(apiPort) + "/radio/status";
    http.begin(url);
    if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
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

    if (doc["tx_in_progress"].is<bool>())
    {
      aprsTxInProgress = doc["tx_in_progress"].as<bool>();
    }

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

    // --- SQL ---（文字列型・数値型どちらにも対応）
    if (doc["sql"].is<String>())
    {
      sqlLevel = doc["sql"].as<String>().toFloat();
    }
    else if (!doc["sql"].isNull())
    {
      sqlLevel = doc["sql"].as<float>();
    }
  }

  // --- UI へ反映（UI操作直後は上書きしない） ---
  unsigned long now = millis();

  // --- 周波数更新 ---
  if (aprsTxInProgress)
  {
    // ★ APRS TX中は FastAPI の freq を必ず反映する
    sharedFreq = st.freq;
  }
  else
  {
    // ★ 通常時はユーザー操作保護
    if (now - lastUserFreqChange > 1000)
      sharedFreq = st.freq;
  }

  if (now - lastUserModeChange > 1000)
  {
    // ★ リグ側(本体パネル等)でモードが変わった場合も、
    //   アプリ経由の変更と同様にデフォルトStepを適用する。
    static String lastPolledMode;
    if (st.mode != lastPolledMode)
    {
      lastPolledMode = st.mode;
      applyDefaultStep(st.mode);
    }
    sharedMode = st.mode;
  }

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
        if (supportedModes[i] == sharedMode)
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

      // モードに対応するステップを適用（AM/FMは20k）
      applyDefaultStep(newMode);
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
    modeStepMap[sharedMode] = selStep;

    Preferences prefs;
    prefs.begin("modeStep", false);
    prefs.putInt(sharedMode.c_str(), selStep);
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

    if (audioMutex && xSemaphoreTake(audioMutex, pdMS_TO_TICKS(10)) == pdTRUE)
    {
      if (out) out->SetGain(currentVolume);
      xSemaphoreGive(audioMutex);
    }
  }
  break;

  case MENU_MICGAIN:
  {
    unsigned long now = millis();
    if (now - lastRigCmdTime < rigCmdInterval)
      break;
    lastRigCmdTime = now;
    lastUserMicGainChange = now;

    es8388MicGainIdx += dir;
    es8388MicGainIdx = constrain(es8388MicGainIdx, 0, 8);

    prefs.begin("device", false);
    prefs.putInt("micGainIdx", es8388MicGainIdx);
    prefs.end();
    // 実際のPGA反映は次回TX開始時(startAudioTx)に行われる
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

  if (useCIV)
  {
    // フェーズ1: IC-705固定モードリスト(無線機からのcaps取得は非対応)
    supportedModes = {"LSB", "USB", "AM", "CW", "RTTY", "FM", "CWR", "RTTYR", "WFM", "DSTAR"};
    loadModeStepPrefs();
    applyDefaultStep(sharedMode);
    return true;
  }

  HTTPClient http;
  String url = "http://" + HostName + ":" + String(apiPort) + "/radio/caps";
  http.begin(url);
  if (!apiKey.isEmpty()) http.addHeader("X-API-Key", apiKey);
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

  // 初期表示時にモードに対応するステップを設定
  applyDefaultStep(sharedMode);

  return !supportedModes.empty();
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
  if (useCIV)
  {
    civSendFreq(freq);
    return;
  }

  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setfreq";
  cmd->body = "f=" + String(freq);

  drainQueueByPath("/radio/setfreq"); // ★ 古い setfreq を全て除去
  xQueueSend(httpQueue, &cmd, 0);
}

void sendMode(String mode, int width)
{
  if (useCIV)
  {
    civSendMode(mode, width);
    return;
  }

  // C4FM and D-STAR don't use a filter width in rigctld; force 0 to avoid errors
  if (mode == "C4FM" || mode == "DSTAR" || mode == "D-STAR") width = 0;

  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setmode";
  cmd->body = "mode=" + mode + "&width=" + String(width);

  drainQueueByPath("/radio/setmode"); // ★ 古い setmode を全て除去
  xQueueSend(httpQueue, &cmd, 0);
}

void sendLevel(String name, float value)
{
  if (useCIV)
  {
    // CI-Vフェーズ1はSQLのみ対応(VOLは音声非対応のため無視)
    if (name == "SQL") civSendSquelch(value);
    return;
  }

  char valStr[16];
  snprintf(valStr, sizeof(valStr), "%.3f", value);

  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setlevel";
  cmd->body = "name=" + name + "&value=" + String(valStr);

  drainQueueByPath("/radio/setlevel"); // ★ 古い setlevel を全て除去
  xQueueSend(httpQueue, &cmd, 0);
}

void sendPower(float power)
{
  if (useCIV)
  {
    civSendPower(power);
    return;
  }

  char powerStr[16];
  snprintf(powerStr, sizeof(powerStr), "%.3f", power);

  HttpCommand *cmd = new HttpCommand;
  cmd->path = "/radio/setpower";
  cmd->body = "value=" + String(powerStr);

  drainQueueByPath("/radio/setpower"); // ★ 古い setpower を全て除去
  xQueueSend(httpQueue, &cmd, 0);
}

void updatePTT_UI(bool on)
{
  if (on)
  {
    lockUIExceptPTTandSPK();
    selectedItem = MENU_NONE;
    drawMainUI();

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

    if (useWifiPTT)
      setLedColor(0, 0, 255);
    else
      setLedColor(0, 255, 0);
  }
  else
  {
    unlockUI();
    // PTT OFF時はFreq等の選択状態を保持する(ONの時だけ誤操作防止でクリアする)
    drawMainUI();
    clearLed();
  }
}
