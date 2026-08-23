/****************************************************
 *  M5CoreHamCAT ui_core.h
 *  M5StopWatch(466x466円形AMOLED)向け共通デザインシステム
 *
 *  M5StopWatchは1.75インチの円形AMOLEDで、フレームバッファは正方形だが
 *  実際に見えるのは中心の円形部分だけ。このアプリの全画面は
 *  「円であること」を前提にしたUI言語で統一する:
 *   - 角のある矩形パネル/ボタンは使わない。ボタンは円形(アイコン)か
 *     完全なピル形状(角丸=高さの半分)のみ。
 *   - リストの行もピル形状。
 *   - S-メーターやページ送り等のインジケータは円弧(fillArc)で表現する。
 *  (SP2ALART_M5STOP_1.00のui_core.h/cppと同一のデザイン言語)
 ****************************************************/
#pragma once
#include <M5Unified.h>

extern M5Canvas canvas;

// ---- カラーパレット ----
// 既存のuiTheme(0=OCEAN 1=AMBER 2=MONO 3=AQUA)×dayMode(昼/夜)の組み合わせは
// main_ctrl.cppのgetThemePalette()が個別に返す(メイン画面専用の配色ロジックを
// 維持)。ui_core側のColorThemeはそれ以外の画面(Wi-Fi/パスワード/設定等)で使う
// 共通パレットで、実行時にtheme構造体を丸ごと差し替えるとCOL_*マクロ経由で
// 全画面の配色が切り替わる。
struct ColorTheme
{
    uint16_t bg;        // ほぼ黒
    uint16_t surface;    // カード/行の下地
    uint16_t surfaceHi;  // 選択中の行など、少し明るい下地
    uint16_t accent;     // 主要アクション
    uint16_t text;       // 主要文字色(白)
    uint16_t textDim;    // 中間グレー
    uint16_t danger;     // 赤(削除/エラー/切断)
    uint16_t success;    // 緑(保存済み/接続成功/ON状態)
    uint16_t warn;       // 黄(注意)
};
extern ColorTheme theme;

#define COL_BG (theme.bg)
#define COL_SURFACE (theme.surface)
#define COL_SURFACE_HI (theme.surfaceHi)
#define COL_ACCENT (theme.accent)
#define COL_TEXT (theme.text)
#define COL_TEXT_DIM (theme.textDim)
#define COL_DANGER (theme.danger)
#define COL_SUCCESS (theme.success)
#define COL_WARN (theme.warn)

void ui_init();
void ui_clear();
void ui_drawTitle(const char *title);
// 画面外周ぎりぎりに細いリングを描き、円形ディスプレイであることを強調する
// ベゼル演出。fillScreen()の直後、他のコンテンツを描く前に呼ぶこと。
void ui_drawBezelRing();

// 円の中心からの垂直距離dyにおいて、画面円内に収まる安全な半幅(px)を返す。
int safeHalfWidthAt(int dy);

void drawCentered(const char *txt, int x, int y, uint16_t color = WHITE);
void drawLabel(const char *txt, int x, int y, uint16_t color = WHITE);
// 画面下部などに置く操作ヒント(小さめ・控えめな色のテキスト)。
void drawHint(const char *txt, int y, uint16_t color = COL_TEXT_DIM);

// ---- 円形デザインシステムの基本パーツ ----

// 完全な円形ボタン(アイコン/短いラベル用)。塗りつぶし+中央ラベル。
void drawCircleButton(int cx, int cy, int r, uint16_t fillColor, const char *label, uint16_t labelColor = WHITE, const lgfx::IFont *font = nullptr);
bool hitCircleButton(int touchX, int touchY, int cx, int cy, int r);
void drawSquareButton(int cx, int cy, int hw, uint16_t fillColor, const char *label, uint16_t labelColor = WHITE, const lgfx::IFont *font = nullptr, int hh = 0);
bool hitSquareButton(int touchX, int touchY, int cx, int cy, int hw, int hh = 0);

// ピル形状(完全な角丸長方形、半径=高さの半分)の行/ボタン。
void drawPill(int x, int y, int w, int h, uint16_t fillColor, const char *label = nullptr, uint16_t labelColor = WHITE);
bool hitRect(int touchX, int touchY, int x, int y, int w, int h);

// S-メーターやページ/位置インジケータ用の円弧。angle: 0=真上、時計回りに度数。
void drawArcIndicator(int cx, int cy, int r, float startDeg, float endDeg, uint16_t color, int thickness = 6);

// NeoPixel等の物理LEDはM5StopWatchに非搭載のため no-op。
// TX状態表示等は画面上のリング(メイン画面側の描画)で行う。
void initLed();
void clearLed();
void setLedColor(uint8_t r, uint8_t g, uint8_t b);


struct RigStatus
{
  String freq;
  String mode;
  String model;
  float signal;
  bool tx;
  bool bkin;
  bool valid; // 取得成功フラグ
};

RigStatus fetchRigStatus();

void startAprsBeaconTask();
void stopAprsBeaconTask();
void sendAprsBeacon();
void handleAPRSSettingsScreen();
