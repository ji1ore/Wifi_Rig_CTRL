/****************************************************
 *  Wifi_Rig_CTRL civ_client.cpp
 *  CI-V WiFi直結（Icom WLAN Remote, RS-BA1互換UDPプロトコル）
 *  フェーズ1: 制御のみ（周波数/モード/PTT/Sメーター）。音声トンネリングは対象外。
 *
 *  Android版 CivTcpService.kt（Wifi_RIG_CTRL_ForAndroid）のバイトレイアウトを
 *  そのまま移植。詳細は各パケットビルダーのコメントを参照。
 *  by JI1ORE
 ****************************************************/
#include "civ_client.h"
#include "globals.h"
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Preferences.h>

// ==== パケットサイズ定数 ====
static const int CONTROL_SIZE   = 0x10; // 16
static const int PING_SIZE      = 0x15; // 21 (CIVデータヘッダも兼ねる)
static const int TOKEN_SIZE     = 0x40; // 64
static const int LOGIN_SIZE     = 0x80; // 128
static const int LOGIN_RESPONSE_SIZE = 0x60; // 96
static const int STATUS_SIZE    = 0x50; // 80
static const int CONNINFO_SIZE  = 0x90; // 144
static const int OPENCLOSE_SIZE = 0x16; // 22
static const int CTRL_ADDRESS   = 0xE0;

// ==== エンディアン変換ヘルパー ====
static inline void putLE32(uint8_t *b, int off, uint32_t v)
{
  b[off] = v & 0xFF; b[off+1] = (v>>8)&0xFF; b[off+2] = (v>>16)&0xFF; b[off+3] = (v>>24)&0xFF;
}
static inline void putLE16(uint8_t *b, int off, uint16_t v)
{
  b[off] = v & 0xFF; b[off+1] = (v>>8)&0xFF;
}
static inline void putBE16(uint8_t *b, int off, uint16_t v)
{
  b[off] = (v>>8)&0xFF; b[off+1] = v & 0xFF;
}
static inline void putBE32(uint8_t *b, int off, uint32_t v)
{
  b[off] = (v>>24)&0xFF; b[off+1] = (v>>16)&0xFF; b[off+2] = (v>>8)&0xFF; b[off+3] = v & 0xFF;
}
static inline uint32_t getLE32(const uint8_t *b, int off)
{
  return (uint32_t)b[off] | ((uint32_t)b[off+1]<<8) | ((uint32_t)b[off+2]<<16) | ((uint32_t)b[off+3]<<24);
}
static inline uint16_t getLE16(const uint8_t *b, int off)
{
  return (uint16_t)b[off] | ((uint16_t)b[off+1]<<8);
}
static inline uint16_t getBE16(const uint8_t *b, int off)
{
  return ((uint16_t)b[off]<<8) | (uint16_t)b[off+1];
}

static void fillHeader(uint8_t *p, int len, int type, int seq, uint32_t sentId, uint32_t rcvdId)
{
  putLE32(p, 0, (uint32_t)len);
  putLE16(p, 4, (uint16_t)type);
  putLE16(p, 6, (uint16_t)seq);
  putLE32(p, 8, sentId);
  putLE32(p, 12, rcvdId);
}

// ==== passcode() 置換暗号（ユーザー名/パスワード難読化） ====
static const uint8_t PASSCODE[256] PROGMEM = {
  0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
  0x47,0x5d,0x4c,0x42,0x66,0x20,0x23,0x46,0x4e,0x57,0x45,0x3d,0x67,0x76,0x60,0x41,
  0x62,0x39,0x59,0x2d,0x68,0x7e,0x7c,0x65,0x7d,0x49,0x29,0x72,0x73,0x78,0x21,0x6e,
  0x5a,0x5e,0x4a,0x3e,0x71,0x2c,0x2a,0x54,0x3c,0x3a,0x63,0x4f,0x43,0x75,0x27,0x79,
  0x5b,0x35,0x70,0x48,0x6b,0x56,0x6f,0x34,0x32,0x6c,0x30,0x61,0x6d,0x7b,0x2f,0x4b,
  0x64,0x38,0x2b,0x2e,0x50,0x40,0x3f,0x55,0x33,0x37,0x25,0x77,0x24,0x26,0x74,0x6a,
  0x28,0x53,0x4d,0x69,0x22,0x5c,0x44,0x31,0x36,0x58,0x3b,0x7a,0x51,0x5f,0x52,0,
  0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
};

// passcode(input) を out[0..15] に書き込む（16バイト固定長フィールド用。余りは0埋め）
static void passcodeInto(const String &in, uint8_t *out, size_t outLen)
{
  memset(out, 0, outLen);
  size_t n = min((size_t)in.length(), outLen);
  for (size_t i = 0; i < n; i++)
  {
    int p = (uint8_t)in[i] + (int)i;
    if (p > 126) p = 32 + p % 127;
    out[i] = pgm_read_byte(&PASSCODE[p]);
  }
}

// ==== パケットビルダー ====
// 戻り値は呼び出し側が渡すバッファに書き込む形（動的確保を避ける）

static void makeCtrl(uint8_t *p, int type, int seq, uint32_t sentId, uint32_t rcvdId)
{
  fillHeader(p, CONTROL_SIZE, type, seq, sentId, rcvdId);
}

static void makePong(uint8_t *out, const uint8_t *ping, uint32_t myId, uint32_t remoteId)
{
  putLE32(out, 0, PING_SIZE);
  putLE16(out, 4, 0x07);
  putLE16(out, 6, getLE16(ping, 6));
  putLE32(out, 8, myId);
  putLE32(out, 12, remoteId);
  out[16] = 0x01;
  memcpy(out + 17, ping + 17, 4);
}

static void makeLogin(uint8_t *p, int seq, int innerSeq, int tokReq, uint32_t ctrlMyId, uint32_t ctrlRemoteId,
                       const String &username, const String &password)
{
  memset(p, 0, LOGIN_SIZE);
  fillHeader(p, LOGIN_SIZE, 0x00, seq, ctrlMyId, ctrlRemoteId);
  putBE32(p, 16, (uint32_t)(LOGIN_SIZE - 0x10));
  p[20] = 0x01; p[21] = 0x00;
  putBE16(p, 22, innerSeq);
  putLE16(p, 26, tokReq);
  passcodeInto(username, p + 0x40, 16);
  passcodeInto(password, p + 0x50, 16);
  memset(p + 0x60, 0, 16);
  memcpy(p + 0x60, "Android", 7);
}

static void makeToken(uint8_t *p, int seq, int innerSeq, int tokReq, uint32_t tokenVal, int magic,
                       uint32_t ctrlMyId, uint32_t ctrlRemoteId)
{
  memset(p, 0, TOKEN_SIZE);
  fillHeader(p, TOKEN_SIZE, 0x00, seq, ctrlMyId, ctrlRemoteId);
  putBE32(p, 16, (uint32_t)(TOKEN_SIZE - 0x10));
  p[20] = 0x01; p[21] = (uint8_t)magic;
  putBE16(p, 22, innerSeq);
  putLE16(p, 26, tokReq);
  putLE32(p, 28, tokenVal);
  putBE16(p, 0x24, 0x0798);
}

static void makeRequestStream(uint8_t *p, int seq, int innerSeq, int tokReq, uint32_t tokenVal,
                               int civLocalPort, int audioLocalPort, const String &username,
                               const uint8_t *macAddr, const uint8_t *radioName,
                               uint32_t ctrlMyId, uint32_t ctrlRemoteId)
{
  memset(p, 0, CONNINFO_SIZE);
  fillHeader(p, CONNINFO_SIZE, 0x00, seq, ctrlMyId, ctrlRemoteId);
  putBE32(p, 16, (uint32_t)(CONNINFO_SIZE - 0x10));
  p[20] = 0x01; p[21] = 0x03;
  putBE16(p, 22, innerSeq);
  putLE16(p, 26, tokReq);
  putLE32(p, 28, tokenVal);
  putLE16(p, 0x27, 0x8010);
  if (macAddr) memcpy(p + 0x2a, macAddr, 6);
  if (radioName) memcpy(p + 0x40, radioName, 32);
  passcodeInto(username, p + 0x60, 16);
  p[0x70] = 0x01;
  p[0x71] = 0x01; // 音声TX+RX有効(Android版に合わせる)
  p[0x72] = 0x04;
  p[0x73] = 0x04; // 音声コーデック(Android版に合わせる)
  putBE32(p, 0x74, 8000); // RXサンプルレート(再生側)
  putBE32(p, 0x78, 8000); // TXサンプルレート(送信側。実際に送るPCMのレートと一致させる必要がある)
  putBE32(p, 0x7c, (uint32_t)civLocalPort);
  putBE32(p, 0x80, (uint32_t)audioLocalPort); // 音声ソケットの実ローカルポート(0は不可)
  putBE32(p, 0x84, 200); // TX音声ジッタバッファ(ms)
  p[0x88] = 0x01; // TX+RXセッションモード
}

static void makeOpenClose(uint8_t *p, int seq, int sendSeq, bool open, uint32_t sentId, uint32_t rcvdId)
{
  fillHeader(p, OPENCLOSE_SIZE, 0x00, seq, sentId, rcvdId);
  putLE16(p, 16, 0x01c0);
  putBE16(p, 19, sendSeq);
  p[21] = open ? 0x04 : 0x00;
}

// civData: 21B ヘッダ + civFrameLen バイト。呼び出し側が out に civFrame を先に置いておくか、
// ここで civFrame をコピーする。out のサイズは PING_SIZE + civFrameLen 以上であること。
static int makeCivData(uint8_t *out, int seq, int sendSeq, const uint8_t *civFrame, int civFrameLen,
                        uint32_t civMyId, uint32_t civRemoteId)
{
  int totalLen = PING_SIZE + civFrameLen;
  fillHeader(out, totalLen, 0x00, seq, civMyId, civRemoteId);
  out[16] = 0xC1;
  putLE16(out, 17, civFrameLen);
  putBE16(out, 19, sendSeq);
  memcpy(out + PING_SIZE, civFrame, civFrameLen);
  return totalLen;
}

// CI-Vフレーム FE FE <civAddr> <ctrlAddr> <cmd> [sub] [data...] FD を構築
static int buildCivFrame(uint8_t *out, int civAddr, const uint8_t *bytes, int n)
{
  out[0] = 0xFE; out[1] = 0xFE;
  out[2] = (uint8_t)civAddr; out[3] = (uint8_t)CTRL_ADDRESS;
  for (int i = 0; i < n; i++) out[4 + i] = bytes[i];
  out[4 + n] = 0xFD;
  return 4 + n + 1;
}

// ==== 音声(port3)パケット ====
// 音声パケット: 16Bヘッダ(fillHeader) + 8Bサブヘッダ + PCMペイロード = AUDIO_HDR(24) + N
static const int AUDIO_HDR = 24;

// TX(マイク→無線機)音声パケットを構築。out のサイズは AUDIO_HDR + pcmLen 以上であること。
static int makeTxAudioPacket(uint8_t *out, int outerSeq, uint16_t innerTxSeq,
                              const uint8_t *pcm, int pcmLen,
                              uint32_t audioMyId, uint32_t audioRemoteId)
{
  int totalLen = AUDIO_HDR + pcmLen;
  fillHeader(out, totalLen, 0x00, outerSeq, audioMyId, audioRemoteId);
  out[16] = 0x80; // TX方向マーカー(コントローラ→無線機)
  out[17] = 0x00; // 実機で確認済みの値(0x07は未使用)
  putBE16(out, 18, innerTxSeq); // 内部シーケンス(ビッグエンディアン)
  out[20] = 0x00; out[21] = 0x00; // 予約(常に0)
  putBE16(out, 22, (uint16_t)pcmLen);
  memcpy(out + AUDIO_HDR, pcm, pcmLen);
  return totalLen;
}

// ==== セッション状態 ====
static WiFiUDP ctrlUdp;
static WiFiUDP civUdp;
static SemaphoreHandle_t civCtrlMutex = nullptr;
static SemaphoreHandle_t civDataMutex = nullptr;
static SemaphoreHandle_t civExchangeMutex = nullptr;

static IPAddress civRemoteIp;
static bool civAddrValid = false;

static uint32_t ctrlMyId = 0, civMyId = 0;
static uint32_t ctrlRemoteId = 0, civRemoteId = 0;
static uint32_t civToken = 0;
static int civTokReq = 0;
static int ctrlSeq = 0, civPktSeq = 0, civSendSeqB = 0, authSeq = 0;
static uint8_t savedMac[6] = {0};
static uint8_t savedRadioName[32] = {0};
static bool haveSavedMac = false;

static volatile bool civConnected = false;
static volatile bool civStreamOpened = false;
static volatile unsigned long lastCtrlPingMs = 0;
static volatile unsigned long lastCivPingMs = 0;
static volatile unsigned long lastTokenRenewalMs = 0;

// ==== 音声(port3)セッション状態 ====
static WiFiUDP audioUdp;
static SemaphoreHandle_t civAudioMutex = nullptr;
// M5.Speaker/M5.Mic/I2S_NUM_0への物理アクセスを保護する。civAudioRxTask(別タスクで
// 継続動作)がSPKへ再生中に、civStartTxAudio()/civStopTxAudio()が同時にM5.Speaker.end()等
// でハードウェアを切り替えるとクラッシュしうるため、両者はこのmutexを必ず取得すること。
static SemaphoreHandle_t civSpkHwMutex = nullptr;
static uint32_t audioMyId = 0, audioRemoteId = 0;
static uint16_t audioLocalPort = 0;
static int audioPktSeq = 0;       // 外側ヘッダseq(送信パケット全体で共有)
static uint16_t audioTxSeq = 0;   // TX音声データの内部seq(ビッグエンディアン)
static volatile bool audioSessionReady = false;
static volatile bool civAudioRxActive = false;
static TaskHandle_t civAudioRxTaskHandle = nullptr;
static TaskHandle_t civAudioTxTaskHandle = nullptr;
static volatile bool civAudioTxTaskRunning = false;
static volatile unsigned long lastAudioPingMs = 0;
static volatile unsigned long lastAudioKeepaliveMs = 0;
static volatile unsigned long lastTxAudioMs = 0;
static volatile bool civTxAudioActive = false; // PTT ON中はtrue(送信ループが動作)
// I2S_NUM_0が実際にインストール済みかどうか。i2s_driver_install()が何らかの理由で
// 失敗した(戻り値がESP_OK以外)場合でも、以前は無条件でi2s_write()/i2s_read()が
// 呼ばれてしまい、未インストール状態のI2Sドライバへのアクセスでクラッシュしていた
// (実機でGuru Meditation Error: LoadProhibitedを確認済み)。このフラグで確実にガードする。
static volatile bool civI2SReady = false;

// TX再送キャッシュ: 無線機からの再送要求に応えるため直近送信パケットを保持
static const int TX_CACHE_SLOTS = 128;
struct TxAudioCacheEntry { uint16_t seq; uint8_t data[AUDIO_HDR + 320]; int len; bool valid; };
static TxAudioCacheEntry txAudioCache[TX_CACHE_SLOTS];

static TaskHandle_t civCtrlRxTaskHandle = nullptr;
static TaskHandle_t civDataRxTaskHandle = nullptr;
static volatile bool civCtrlRxActive = false;
static volatile bool civDataRxActive = false;

// CI-Vデータ受信キュー（civExchange()が消費する）
struct CivFrameMsg { uint8_t data[80]; uint8_t len; };
static QueueHandle_t civRxQueue = nullptr;

// ブロードキャストキャッシュ: (cmd,sub) -> body
struct CivCacheEntry { uint8_t cmd; uint8_t sub; uint8_t body[16]; uint8_t bodyLen; unsigned long ts; bool valid; };
static CivCacheEntry civCache[8];

static void cacheCivFrames(const uint8_t *data, int len)
{
  int i = 0;
  while (i < len - 1)
  {
    if (data[i] != 0xFE || data[i+1] != 0xFE) { i++; continue; }
    uint8_t body[32]; int bn = 0;
    int j = i + 2;
    while (j < len && bn < (int)sizeof(body))
    {
      if (data[j] == 0xFD) break;
      body[bn++] = data[j]; j++;
    }
    if (bn >= 3 && body[0] == CTRL_ADDRESS)
    {
      uint8_t cmd = body[2];
      uint8_t sub = (bn >= 4) ? body[3] : 0xFF;
      // 空きスロット or 既存(cmd,sub)を探して上書き
      int slot = -1;
      for (int k = 0; k < 8; k++)
      {
        if (civCache[k].valid && civCache[k].cmd == cmd && civCache[k].sub == sub) { slot = k; break; }
      }
      if (slot < 0)
      {
        for (int k = 0; k < 8; k++) { if (!civCache[k].valid) { slot = k; break; } }
      }
      if (slot < 0) slot = 0; // 全埋まりなら先頭を使い回す
      civCache[slot].cmd = cmd; civCache[slot].sub = sub;
      civCache[slot].bodyLen = min(bn, (int)sizeof(civCache[slot].body));
      memcpy(civCache[slot].body, body, civCache[slot].bodyLen);
      civCache[slot].ts = millis();
      civCache[slot].valid = true;
    }
    i++;
  }
}

// sub<0 は「このcmdなら何でも良い」
static bool getCached(int cmd, int sub, uint8_t *outBody, uint8_t *outLen, unsigned long maxAgeMs = 3000)
{
  unsigned long now = millis();
  for (int k = 0; k < 8; k++)
  {
    if (!civCache[k].valid) continue;
    if (civCache[k].cmd != cmd) continue;
    if (sub >= 0 && civCache[k].sub != sub) continue;
    if (now - civCache[k].ts >= maxAgeMs) continue;
    memcpy(outBody, civCache[k].body, civCache[k].bodyLen);
    *outLen = civCache[k].bodyLen;
    return true;
  }
  return false;
}

// ==== ソケット送信ヘルパー ====
static void sendCtrl(const uint8_t *pkt, int len)
{
  if (!civAddrValid) return;
  if (xSemaphoreTake(civCtrlMutex, pdMS_TO_TICKS(200)) == pdTRUE)
  {
    ctrlUdp.beginPacket(civRemoteIp, civPort1);
    ctrlUdp.write(pkt, len);
    ctrlUdp.endPacket();
    xSemaphoreGive(civCtrlMutex);
  }
}

// サブネットのブロードキャストアドレスへも同じパケットを送る(事前ウェイクバースト専用)。
// 無線機のWiFiがパワーセーブでスリープ中だと、宛先固定のユニキャストはAPに溜められて
// DTIM周期までしか届かないことがあるが、ブロードキャストは即時配送されやすい。
// Android版のconnect()と同じ挙動(ユニキャスト+ブロードキャスト併用)に合わせる。
static void sendCtrlBroadcast(const uint8_t *pkt, int len)
{
  IPAddress localIp = WiFi.localIP();
  if (localIp[0] == 0) return;
  IPAddress bcast(localIp[0], localIp[1], localIp[2], 0xFF);
  if (xSemaphoreTake(civCtrlMutex, pdMS_TO_TICKS(200)) == pdTRUE)
  {
    ctrlUdp.beginPacket(bcast, civPort1);
    ctrlUdp.write(pkt, len);
    ctrlUdp.endPacket();
    xSemaphoreGive(civCtrlMutex);
  }
}
static void sendCivPkt(const uint8_t *pkt, int len)
{
  if (!civAddrValid) return;
  if (xSemaphoreTake(civDataMutex, pdMS_TO_TICKS(200)) == pdTRUE)
  {
    civUdp.beginPacket(civRemoteIp, civPort2);
    civUdp.write(pkt, len);
    civUdp.endPacket();
    xSemaphoreGive(civDataMutex);
  }
}
static void sendAudio(const uint8_t *pkt, int len)
{
  if (!civAddrValid) return;
  if (xSemaphoreTake(civAudioMutex, pdMS_TO_TICKS(200)) == pdTRUE)
  {
    audioUdp.beginPacket(civRemoteIp, civPort3);
    audioUdp.write(pkt, len);
    audioUdp.endPacket();
    xSemaphoreGive(civAudioMutex);
  }
}

// ping(21B,type=7,byte16=0)を検知したら即pongを返す。処理したらtrueを返す。
static bool handleCtrlPing(const uint8_t *r, int len)
{
  if (len != PING_SIZE) return false;
  if (getLE16(r, 4) != 0x07) return false;
  if (r[16] != 0x00) return false;
  uint32_t sender = getLE32(r, 8);
  if (ctrlRemoteId == 0 || sender != ctrlRemoteId) ctrlRemoteId = sender;
  uint8_t pong[PING_SIZE];
  makePong(pong, r, ctrlMyId, ctrlRemoteId);
  sendCtrl(pong, PING_SIZE);
  lastCtrlPingMs = millis();
  return true;
}
static bool handleCivPing(const uint8_t *r, int len)
{
  if (len != PING_SIZE) return false;
  if (getLE16(r, 4) != 0x07) return false;
  if (r[16] != 0x00) return false;
  uint32_t sender = getLE32(r, 8);
  if (civRemoteId == 0 || sender != civRemoteId) civRemoteId = sender;
  uint8_t pong[PING_SIZE];
  makePong(pong, r, civMyId, civRemoteId);
  sendCivPkt(pong, PING_SIZE);
  lastCivPingMs = millis();
  return true;
}
// 音声(port3)ping応答。無線機は約80秒毎にaudioRemoteIdをローテーションするため追従する。
static bool handleAudioPing(const uint8_t *r, int len)
{
  if (len != PING_SIZE) return false;
  if (getLE16(r, 4) != 0x07) return false;
  if (r[16] != 0x00) return false;
  uint32_t sender = getLE32(r, 8);
  if (audioRemoteId == 0 || sender != audioRemoteId) audioRemoteId = sender;
  uint8_t pong[PING_SIZE];
  makePong(pong, r, audioMyId, audioRemoteId);
  sendAudio(pong, PING_SIZE);
  lastAudioPingMs = millis();
  return true;
}

// ==== 受信タスク ====
static void civCtrlRxTask(void *param)
{
  unsigned long lastKeepalive = millis();
  uint8_t buf[256];
  // ★ デバッグ: ctrlソケット(port1)のping応答性を検証する。無線機がセッション
  //   全体の生存確認をここで行っている場合、ここへの応答遅延/欠落がPTT自動解除の
  //   トリガーになっている可能性がある。
  long dbgPingCount = 0, dbgOtherCount = 0;
  unsigned long dbgLastDump = millis();
  while (civCtrlRxActive)
  {
    int packetSize = 0;
    if (xSemaphoreTake(civCtrlMutex, pdMS_TO_TICKS(50)) == pdTRUE)
    {
      packetSize = ctrlUdp.parsePacket();
      if (packetSize > 0)
      {
        packetSize = ctrlUdp.read(buf, min(packetSize, (int)sizeof(buf)));
      }
      xSemaphoreGive(civCtrlMutex);
    }
    unsigned long now = millis();
    if (now - dbgLastDump > 5000)
    {
      Serial.printf("[%lu][civCtrlRx][dbg] ping=%ld other=%ld ctrlRemoteId=0x%08X\n",
                    now, dbgPingCount, dbgOtherCount, (unsigned)ctrlRemoteId);
      dbgLastDump = now;
    }
    if (packetSize > 0)
    {
      if (handleCtrlPing(buf, packetSize))
      {
        dbgPingCount++;
      }
      else
      {
        dbgOtherCount++;
        // トークン更新応答など（フェーズ1では詳細処理は省略、ログのみ）
        if (packetSize == TOKEN_SIZE && getLE16(buf, 4) != 0x01 && buf[21] == 0x05)
        {
          uint32_t renewErr = getLE32(buf, 0x30);
          if (renewErr != 0) Serial.printf("[CIV] token renewal rejected err=0x%08X\n", renewErr);
        }
      }
    }
    if (now - lastKeepalive > 2000 && ctrlRemoteId != 0)
    {
      uint8_t pkt[CONTROL_SIZE];
      makeCtrl(pkt, 0x00, ++ctrlSeq, ctrlMyId, ctrlRemoteId);
      sendCtrl(pkt, CONTROL_SIZE);
      lastKeepalive = now;
    }
    if (lastTokenRenewalMs > 0 && now - lastTokenRenewalMs > 60000 && civToken != 0 && ctrlRemoteId != 0)
    {
      uint8_t pkt[TOKEN_SIZE];
      makeToken(pkt, ++ctrlSeq, authSeq++, civTokReq, civToken, 0x05, ctrlMyId, ctrlRemoteId);
      sendCtrl(pkt, TOKEN_SIZE);
      lastTokenRenewalMs = now;
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  civCtrlRxTaskHandle = nullptr;
  vTaskDelete(nullptr);
}

static void civDataRxTask(void *param)
{
  unsigned long lastKeepalive = millis();
  uint8_t buf[256];
  // ★ デバッグ: civGetTxStateRawなどが応答なしで失敗し続ける現象の切り分け用。
  //   このタスクが実際に無線機からCI-Vポート(port2)のパケットを受信できているか
  //   (ping/データいずれか)を5秒毎にダンプする。
  long dbgPingCount = 0, dbgDataCount = 0, dbgKeepaliveSent = 0;
  unsigned long dbgLastDump = millis();
  while (civDataRxActive)
  {
    int packetSize = 0;
    if (xSemaphoreTake(civDataMutex, pdMS_TO_TICKS(50)) == pdTRUE)
    {
      packetSize = civUdp.parsePacket();
      if (packetSize > 0)
      {
        packetSize = civUdp.read(buf, min(packetSize, (int)sizeof(buf)));
      }
      xSemaphoreGive(civDataMutex);
    }
    unsigned long now = millis();
    if (packetSize > 0)
    {
      if (handleCivPing(buf, packetSize))
      {
        dbgPingCount++;
      }
      else
      {
        dbgDataCount++;
        // ★ civExchange()が呼ばれた時にしかcacheCivFrames()が実行されない設計だと、
        //   getCached()でキャッシュヒットし続けている間はcivExchange()自体が呼ばれず、
        //   無線機からのトランシーブブロードキャスト(TX状態変化など)がキューに溜まった
        //   ままキャッシュに反映されない。その結果、無線機が実際にTXを解除しても、
        //   画面のTX表示が古い値のまま張り付く不具合が起きていた。受信した時点で
        //   即座にキャッシュへ反映することで、civExchange()の呼び出し頻度に依存せず
        //   常に最新のブロードキャスト情報を参照できるようにする。
        if (packetSize > (int)PING_SIZE && getLE16(buf, 4) != 0x01)
        {
          int dataLen = getLE16(buf, 17);
          if (dataLen > 0 && packetSize >= (int)PING_SIZE + dataLen)
          {
            cacheCivFrames(buf + PING_SIZE, dataLen);
          }
        }
        if (civRxQueue)
        {
          CivFrameMsg msg;
          msg.len = min(packetSize, (int)sizeof(msg.data));
          memcpy(msg.data, buf, msg.len);
          xQueueSend(civRxQueue, &msg, 0);
        }
      }
    }
    if (now - lastKeepalive > 2000 && civRemoteId != 0)
    {
      uint8_t pkt[CONTROL_SIZE];
      makeCtrl(pkt, 0x00, ++civPktSeq, civMyId, civRemoteId);
      sendCivPkt(pkt, CONTROL_SIZE);
      lastKeepalive = now;
      dbgKeepaliveSent++;
    }
    if (now - dbgLastDump > 5000)
    {
      Serial.printf("[%lu][civDataRx][dbg] ping=%ld data=%ld keepaliveSent=%ld civRemoteId=0x%08X\n",
                    now, dbgPingCount, dbgDataCount, dbgKeepaliveSent, (unsigned)civRemoteId);
      dbgLastDump = now;
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  civDataRxTaskHandle = nullptr;
  vTaskDelete(nullptr);
}

// ==== 音声ハードウェア(CI-V専用) ====
// M5StopWatchは外部オーディオコーデックポートを持たないため、内蔵M5.Speaker/M5.Mic
// のみを使う(常にfalse=内蔵)。civInstallSpkI2S()等の生I2S経路はCoreS3/Core2の
// Module Audio(ES8388)専用だったため、この移植では未使用(呼び出し側はexternal=false
// の分岐で内蔵デバイスを使う)。
static inline bool civSpkExternal() { return false; }
static inline bool civMicExternal() { return false; }

// ==== 音声(port3)受信タスク ====
// ping応答/音声データ再生/再送要求への対応を継続的に行う。接続中は常時稼働し、
// 実際の再生はspkEnabled&&送信中でない場合のみ行う(半二重: 送受信は同時に起きない)。
static void civAudioRxTask(void *param)
{
  unsigned long lastKeepalive = millis();
  uint8_t buf[512];
  static int16_t stereoBufPool[3][160 * 2];
  int stereoBufIdx = 0;

  // ★ デバッグ: RX側の受信状況を切り分けるためのカウンタ(5秒毎にダンプ)。
  unsigned long dbgLastDump = millis();
  long dbgPingCount = 0, dbgDataCount = 0, dbgPlayedCount = 0, dbgMutexMissCount = 0;

  while (civAudioRxActive)
  {
    if (millis() - dbgLastDump > 5000)
    {
      Serial.printf("[%lu][civAudioRx][dbg] ping=%ld data=%ld played=%ld mutexMiss=%ld spkEnabled=%d civI2SReady=%d civTxAudioActive=%d spkExternal=%d\n",
                    millis(), dbgPingCount, dbgDataCount, dbgPlayedCount, dbgMutexMissCount,
                    spkEnabled, civI2SReady, civTxAudioActive, civSpkExternal());
      dbgLastDump = millis();
    }
    int packetSize = 0;
    if (xSemaphoreTake(civAudioMutex, pdMS_TO_TICKS(50)) == pdTRUE)
    {
      packetSize = audioUdp.parsePacket();
      if (packetSize > 0)
      {
        packetSize = audioUdp.read(buf, min(packetSize, (int)sizeof(buf)));
      }
      xSemaphoreGive(civAudioMutex);
    }
    unsigned long now = millis();

    if (packetSize > 0)
    {
      int type = getLE16(buf, 4);
      if (packetSize == PING_SIZE && type == 0x07 && buf[16] == 0x00)
      {
        dbgPingCount++;
        handleAudioPing(buf, packetSize);
      }
      else if (packetSize > AUDIO_HDR && buf[16] == 0x81)
      {
        dbgDataCount++;
        // 音声データ(無線機→こちら)。pingが多少遅れても切断扱いしないよう生存確認にも使う。
        lastAudioPingMs = now;
        int pcmLen = packetSize - AUDIO_HDR;
        int samples = min(pcmLen / 2, 160);
        if (spkEnabled && !civTxAudioActive && samples > 0)
        {
          // ★ civStartTxAudio()/civStopTxAudio()とのハードウェア切替競合を避けるため、
          //   取得できなければ諦める(取れない=ちょうどPTT切替中なので再生しない方が安全)。
          if (xSemaphoreTake(civSpkHwMutex, 0) == pdTRUE)
          {
            if (spkEnabled && !civTxAudioActive)
            {
              dbgPlayedCount++;
              const int16_t *mono = (const int16_t *)(buf + AUDIO_HDR);
              int16_t *stereoBuf = stereoBufPool[stereoBufIdx];
              stereoBufIdx = (stereoBufIdx + 1) % 3;
              for (int i = 0; i < samples; i++)
              {
                float s = mono[i] * currentVolume;
                if (s > 32767.0f) s = 32767.0f;
                if (s < -32768.0f) s = -32768.0f;
                stereoBuf[i * 2]     = (int16_t)s;
                stereoBuf[i * 2 + 1] = (int16_t)s;
              }
              if (civSpkExternal())
              {
                // civI2SReadyが立っていない(未インストール/インストール失敗)間は
                // i2s_write()を呼ばない。呼ぶとNULLポインタ参照でクラッシュする。
                if (civI2SReady)
                {
                  size_t written;
                  i2s_write(I2S_NUM_0, stereoBuf, (size_t)(samples * 4), &written, pdMS_TO_TICKS(20));
                }
              }
              else
              {
                M5.Speaker.playRaw(stereoBuf, (size_t)(samples * 2), 8000, true, 1, 0);
              }
            }
            xSemaphoreGive(civSpkHwMutex);
          }
          else
          {
            dbgMutexMissCount++;
          }
        }
      }
      else if (packetSize == CONTROL_SIZE && type == 0x0001)
      {
        // 単発の再送要求: 該当seqのキャッシュ済みTXパケットを2回再送する
        uint16_t missingSeq = getLE16(buf, 6);
        Serial.printf("[%lu][civAudioRx][dbg] retransmit request seq=%u\n", millis(), missingSeq);
        bool found = false;
        for (int k = 0; k < TX_CACHE_SLOTS; k++)
        {
          if (txAudioCache[k].valid && txAudioCache[k].seq == missingSeq)
          {
            sendAudio(txAudioCache[k].data, txAudioCache[k].len);
            sendAudio(txAudioCache[k].data, txAudioCache[k].len);
            found = true;
            break;
          }
        }
        if (!found) Serial.printf("[%lu][civAudioRx][dbg] retransmit seq=%u NOT in cache\n", millis(), missingSeq);
      }
    }

    if (now - lastKeepalive > 2000 && audioRemoteId != 0 && now - lastTxAudioMs > 2000)
    {
      uint8_t pkt[CONTROL_SIZE];
      makeCtrl(pkt, 0x00, ++audioPktSeq & 0xFFFF, audioMyId, audioRemoteId);
      sendAudio(pkt, CONTROL_SIZE);
      lastKeepalive = now;
    }
    vTaskDelay(pdMS_TO_TICKS(5));
  }
  civAudioRxTaskHandle = nullptr;
  vTaskDelete(nullptr);
}

// ==== CI-Vフレームの応答抽出 ====
static bool extractCivBody(const uint8_t *data, int len, int cmd, int subCmd, uint8_t *outBody, uint8_t *outLen)
{
  int i = 0;
  while (i < len - 1)
  {
    if (data[i] != 0xFE || data[i+1] != 0xFE) { i++; continue; }
    uint8_t body[32]; int bn = 0;
    int j = i + 2;
    while (j < len && bn < (int)sizeof(body))
    {
      if (data[j] == 0xFD) break;
      body[bn++] = data[j]; j++;
    }
    if (bn >= 3 && body[0] == CTRL_ADDRESS && body[2] == cmd &&
        (subCmd < 0 || (bn >= 4 && body[3] == subCmd)))
    {
      memcpy(outBody, body, bn);
      *outLen = bn;
      return true;
    }
    i++;
  }
  return false;
}

// civFrame(civAddr込みで組み立て済み)を送信し、cmd/subCmdに一致する応答を待つ。
// 受信した全フレームはcacheCivFrames()でキャッシュする。
static bool civExchange(const uint8_t *civFrame, int civFrameLen, int cmd, int subCmd,
                         uint8_t *outBody, uint8_t *outLen, int timeoutMs = 1500)
{
  if (!civAddrValid) return false;
  if (xSemaphoreTake(civExchangeMutex, pdMS_TO_TICKS(2000)) != pdTRUE) return false;

  bool result = false;
  int effTimeout = timeoutMs;

  // ★ civStreamOpenedがfalseの間、civExchange()が呼ばれるたびに毎回OpenCloseを
  //   再送すると(civFetchRigStatus()は1周期で6回civExchange()を呼ぶため、無線機に
  //   1秒間に何度もOpenCloseが飛ぶ)、無線機からのACK類がcivRxQueueを埋め尽くし
  //   (実機ログでqueueMsgs=48-67/1.5秒、dataMsgs=0が継続)、肝心のクエリ応答が
  //   認識されなくなる事象が確認された。OpenCloseの再送は間引き、最短でも2秒に
  //   1回までとする。
  static unsigned long lastOpenCloseSentMs = 0;
  if (!civStreamOpened)
  {
    unsigned long nowOc = millis();
    if (lastOpenCloseSentMs == 0 || nowOc - lastOpenCloseSentMs > 2000)
    {
      uint8_t oc[OPENCLOSE_SIZE];
      makeOpenClose(oc, ++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId);
      sendCivPkt(oc, OPENCLOSE_SIZE);
      lastOpenCloseSentMs = nowOc;
    }
  }
  else
  {
    lastOpenCloseSentMs = 0;
  }

  uint8_t pkt[PING_SIZE + 16];
  int pktLen = makeCivData(pkt, ++civPktSeq, civSendSeqB++, civFrame, civFrameLen, civMyId, civRemoteId);
  sendCivPkt(pkt, pktLen);

  unsigned long deadline = millis() + effTimeout;
  CivFrameMsg msg;
  // ★ デバッグ: 周波数等の問い合わせが応答なしで失敗し続ける現象の切り分け用。
  //   タイムアウト時に「そもそもキューに何も届かなかった」のか「フレームは届いたが
  //   cmd/subCmdが一致しなかった」のかを区別できるようにする(低頻度: 失敗時のみ出力)。
  int dbgTotalMsgs = 0, dbgDataMsgs = 0;
  // ★ デバッグ: キューを埋めている「非ping・非データ扱い」メッセージの正体を
  //   特定するため、最初の数件のtype/len/dataLenをサンプリングしておく。
  int dbgSampleN = 0;
  int dbgSampleType[3] = {0,0,0}, dbgSampleLen[3] = {0,0,0}, dbgSampleDataLen[3] = {0,0,0};
  while ((long)(millis() - deadline) < 0)
  {
    long remain = (long)(deadline - millis());
    if (remain <= 0) break;
    if (xQueueReceive(civRxQueue, &msg, pdMS_TO_TICKS(min(remain, 50L))) != pdTRUE) continue;
    dbgTotalMsgs++;

    if (msg.len <= PING_SIZE) { if (dbgSampleN < 3) { dbgSampleType[dbgSampleN]=getLE16(msg.data,4); dbgSampleLen[dbgSampleN]=msg.len; dbgSampleDataLen[dbgSampleN]=-1; dbgSampleN++; } continue; }
    if (getLE16(msg.data, 4) == 0x01) continue;
    int dataLen = getLE16(msg.data, 17);
    if (msg.len < PING_SIZE + dataLen || dataLen <= 0)
    {
      if (dbgSampleN < 3) { dbgSampleType[dbgSampleN]=getLE16(msg.data,4); dbgSampleLen[dbgSampleN]=msg.len; dbgSampleDataLen[dbgSampleN]=dataLen; dbgSampleN++; }
      continue;
    }
    const uint8_t *civData = msg.data + PING_SIZE;
    dbgDataMsgs++;

    cacheCivFrames(civData, dataLen);

    uint8_t body[32]; uint8_t bn;
    if (extractCivBody(civData, dataLen, cmd, subCmd, body, &bn))
    {
      civStreamOpened = true;
      memcpy(outBody, body, bn); *outLen = bn;
      result = true; break;
    }
    if (cmd == 0x03 && extractCivBody(civData, dataLen, 0x00, -1, body, &bn))
    {
      civStreamOpened = true;
      memcpy(outBody, body, bn); *outLen = bn;
      result = true; break;
    }
  }
  if (!result)
  {
    Serial.printf("[%lu][civ][dbg] civExchange TIMEOUT cmd=0x%02X subCmd=0x%02X timeoutMs=%d queueMsgs=%d dataMsgs=%d civStreamOpened=%d sample=[%d/%d/%d, %d/%d/%d, %d/%d/%d]\n",
                  millis(), cmd, subCmd, effTimeout, dbgTotalMsgs, dbgDataMsgs, civStreamOpened,
                  dbgSampleType[0], dbgSampleLen[0], dbgSampleDataLen[0],
                  dbgSampleType[1], dbgSampleLen[1], dbgSampleDataLen[1],
                  dbgSampleType[2], dbgSampleLen[2], dbgSampleDataLen[2]);
  }
  xSemaphoreGive(civExchangeMutex);
  return result;
}

// ==== 接続 ====
static uint32_t computeMyId(IPAddress ip, uint16_t port)
{
  return ((uint32_t)ip[2] << 24) | ((uint32_t)ip[3] << 16) | (uint32_t)port;
}

static bool waitCtrl(int timeoutMs, uint8_t *outBuf, int *outLen)
{
  unsigned long deadline = millis() + timeoutMs;
  while ((long)(millis() - deadline) < 0)
  {
    int packetSize = 0;
    if (xSemaphoreTake(civCtrlMutex, pdMS_TO_TICKS(50)) == pdTRUE)
    {
      packetSize = ctrlUdp.parsePacket();
      if (packetSize > 0) packetSize = ctrlUdp.read(outBuf, min(packetSize, 256));
      xSemaphoreGive(civCtrlMutex);
    }
    if (packetSize > 0) { *outLen = packetSize; return true; }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  return false;
}

// civ_client.hで宣言されている音声送信タスクの開始/停止(civDisconnect()から先行参照するため前方宣言)
void civStartTxAudio();
void civStopTxAudio();

void civDisconnect()
{
  civConnected = false;
  civStreamOpened = false;

  if (civAddrValid && ctrlRemoteId != 0 && civToken != 0)
  {
    uint8_t pkt[TOKEN_SIZE];
    makeToken(pkt, ++ctrlSeq, authSeq++, civTokReq, civToken, 0x01, ctrlMyId, ctrlRemoteId);
    sendCtrl(pkt, TOKEN_SIZE);
  }
  if (civAddrValid && civRemoteId != 0)
  {
    uint8_t pkt[OPENCLOSE_SIZE];
    makeOpenClose(pkt, ++civPktSeq, civSendSeqB++, false, civMyId, civRemoteId);
    sendCivPkt(pkt, OPENCLOSE_SIZE);
  }
  if (civAddrValid && ctrlRemoteId != 0)
  {
    uint8_t pkt[CONTROL_SIZE];
    makeCtrl(pkt, 0x05, 0, ctrlMyId, ctrlRemoteId);
    sendCtrl(pkt, CONTROL_SIZE);
  }
  delay(50);

  // 音声送信中であれば先に止める(マイク/SPKハードウェアの後片付けも行われる)
  if (civAudioTxTaskHandle) civStopTxAudio();

  civCtrlRxActive = false;
  civDataRxActive = false;
  civAudioRxActive = false;
  unsigned long t = millis();
  while ((civCtrlRxTaskHandle || civDataRxTaskHandle || civAudioRxTaskHandle) && millis() - t < 600) delay(10);

  // ★ タイムアウトしても居座っているタスクがあれば強制終了する。放置すると、次回接続時に
  //   civInstallSpkI2S()より先にこの古いタスクがi2s_write()を呼び続け、civI2SReady=false
  //   の状態と食い違ってクラッシュ/不整合の原因になる。
  if (civCtrlRxTaskHandle)  { vTaskDelete(civCtrlRxTaskHandle);  civCtrlRxTaskHandle  = nullptr; }
  if (civDataRxTaskHandle)  { vTaskDelete(civDataRxTaskHandle);  civDataRxTaskHandle  = nullptr; }
  if (civAudioRxTaskHandle) { vTaskDelete(civAudioRxTaskHandle); civAudioRxTaskHandle = nullptr; }

  audioSessionReady = false;

  ctrlUdp.stop();
  civUdp.stop();
  audioUdp.stop();
  civAddrValid = false;

  if (civRxQueue) { CivFrameMsg m; while (xQueueReceive(civRxQueue, &m, 0) == pdTRUE) {} }
}

bool civIsConnected() { return civConnected; }

String connectToCivRadio()
{
  // ★ WiFi接続確立直後はWiFi/LWIPスタックがまだ不安定で、UDP送信(endPacket)が
  //   ENOMEMで失敗し続けることがある(実機で確認済み)。無線機を先に起動しておくと
  //   M5のWiFi接続がすぐ確立してしまい、接続確立からCI-V接続開始までの間隔が
  //   極端に短くなる(実測2秒程度)ことでこの問題が顕在化し、接続に失敗していた。
  //   M5を先に接続開始する場合は自然な間隔(操作時間)ができるため問題が起きにくかった。
  //   接続確立から最低3秒は経過するまで待つことで、順序に依らず安定させる。
  if (wifiConnectedSinceMs != 0)
  {
    unsigned long elapsed = millis() - wifiConnectedSinceMs;
    const unsigned long MIN_WIFI_SETTLE_MS = 3000;
    if (elapsed < MIN_WIFI_SETTLE_MS)
    {
      Serial.printf("[CIV] WiFi connected %lums ago, waiting %lums more for stack to settle\n",
                    elapsed, MIN_WIFI_SETTLE_MS - elapsed);
      delay(MIN_WIFI_SETTLE_MS - elapsed);
    }
  }

  civDisconnect();

  ctrlSeq = 0; civPktSeq = 0; civSendSeqB = 0; authSeq = 0;
  ctrlMyId = 0; civMyId = 0; ctrlRemoteId = 0; civRemoteId = 0;
  civToken = 0; civTokReq = 0; civStreamOpened = false;
  haveSavedMac = false;
  for (int i = 0; i < 8; i++) civCache[i].valid = false;

  IPAddress ip;
  if (!WiFi.hostByName(civHost.c_str(), ip))
  {
    return "CI-V: Host resolve failed";
  }
  civRemoteIp = ip;
  civAddrValid = true;

  if (!civRxQueue) civRxQueue = xQueueCreate(32, sizeof(CivFrameMsg));
  if (!civCtrlMutex) civCtrlMutex = xSemaphoreCreateMutex();
  if (!civDataMutex) civDataMutex = xSemaphoreCreateMutex();
  if (!civAudioMutex) civAudioMutex = xSemaphoreCreateMutex();
  if (!civSpkHwMutex) civSpkHwMutex = xSemaphoreCreateMutex();
  if (!civExchangeMutex) civExchangeMutex = xSemaphoreCreateMutex();

  // WiFiUDPにlocalPort()が無いため、begin(0)のOS割当エフェメラルポートは取得できない。
  // 代わりに接続の都度ランダムなローカルポートを明示指定する
  // （固定ポートだと無線機が再接続を古いセッションとして扱うため、毎回変える）。
  uint16_t ctrlLocalPort = (uint16_t)random(20000, 60000);
  uint16_t civLocalPort  = (uint16_t)random(20000, 60000);
  audioLocalPort         = (uint16_t)random(20000, 60000);
  ctrlUdp.begin(ctrlLocalPort);
  civUdp.begin(civLocalPort);
  audioUdp.begin(audioLocalPort);

  IPAddress localIp = WiFi.localIP();
  ctrlMyId  = computeMyId(localIp, ctrlLocalPort);
  civMyId   = computeMyId(localIp, civLocalPort);
  audioMyId = computeMyId(localIp, audioLocalPort);
  audioRemoteId = 0;
  audioPktSeq = 0; audioTxSeq = 0;
  audioSessionReady = false;
  for (int i = 0; i < TX_CACHE_SLOTS; i++) txAudioCache[i].valid = false;

  // civDataRxTaskはCI-Vソケット確保後すぐ起動し、以降のpingは全てここで処理する
  // ★ 優先度をcivAudioTxTask(優先度5)と同じにする。以前は2だったため、PTT中は
  //   civAudioTxTaskに完全にCPUを奪われ、無線機からのCI-V ping(生存確認)への
  //   pong応答が慢性的に遅延/欠落していた可能性が高い(無線機がクライアント無応答と
  //   判断し、安全のためPTTを自動解除しているとみられる不具合の根本原因と推測)。
  civDataRxActive = true;
  xTaskCreatePinnedToCore(civDataRxTask, "civDataRx", 4096, nullptr, 5, &civDataRxTaskHandle, 1);

  uint8_t buf[256]; int len;

  // ---- 事前ウェイクバースト ----
  // IC-705のWiFiがパワーセーブ中だと最初のAreYouThereに応答しないことがあるため、
  // Android版と同様に本番のAYTループの前に軽く連投して起こしておく。
  // ユニキャストのみだとAP側のパワーセーブバッファ(DTIM周期まで配送遅延)に載って
  // 届かないことがあるため、サブネットブロードキャストにも同じパケットを送る
  // (Android版のconnect()と同じ挙動)。
  for (int i = 0; i < 5; i++)
  {
    uint8_t wakePkt[CONTROL_SIZE];
    makeCtrl(wakePkt, 0x03, 0, ctrlMyId, 0);
    sendCtrl(wakePkt, CONTROL_SIZE);
    sendCtrlBroadcast(wakePkt, CONTROL_SIZE);
    delay(150);
  }
  delay(300);

  // ---- Step1: AreYouThere -> IAmHere ----
  // ★ IC-705は内部メンテナンス(ハウスキーピング)ウィンドウ中は応答しないことがあり、
  //   これが数十秒続く場合があるため、Android版に合わせて最大90秒待つ
  //   （UIは"Connecting..."表示のまま待機するが、確実な接続を優先する）。
  Serial.println("[CIV] step1 AreYouThere (up to 90s)");
  bool gotIAH = false;
  unsigned long aytEnd = millis() + 90000;
  while ((long)(millis() - aytEnd) < 0)
  {
    uint8_t pkt[CONTROL_SIZE];
    makeCtrl(pkt, 0x03, 0, ctrlMyId, 0);
    sendCtrl(pkt, CONTROL_SIZE);
    if (waitCtrl(1000, buf, &len))
    {
      if (len == CONTROL_SIZE && getLE16(buf, 4) == 0x04)
      {
        ctrlRemoteId = getLE32(buf, 8);
        gotIAH = true;
        break;
      }
    }
  }
  if (!gotIAH) { civDisconnect(); return "CI-V: No response from radio (AreYouThere 90s)"; }

  // AYTバースト中に溜まった応答を掃く
  { int drained = 0; while (waitCtrl(10, buf, &len) && drained++ < 30) {} }

  // ---- Step2: AreYouReady -> IAmReady ----
  Serial.println("[CIV] step2 AreYouReady");
  {
    uint8_t pkt[CONTROL_SIZE];
    makeCtrl(pkt, 0x06, 1, ctrlMyId, ctrlRemoteId);
    sendCtrl(pkt, CONTROL_SIZE);
  }
  bool gotIAR = false;
  unsigned long ayrEnd = millis() + 3000;
  while ((long)(millis() - ayrEnd) < 0)
  {
    if (waitCtrl(500, buf, &len) && len == CONTROL_SIZE && getLE16(buf, 4) == 0x06) { gotIAR = true; break; }
  }
  if (!gotIAR) { civDisconnect(); return "CI-V: No response from radio (IAmReady)"; }

  // ---- Step3: Login ----
  Serial.println("[CIV] step3 Login");
  civTokReq = random(1, 0xFFFF);
  {
    uint8_t pkt[LOGIN_SIZE];
    makeLogin(pkt, ++ctrlSeq, authSeq++, civTokReq, ctrlMyId, ctrlRemoteId, civUsername, civPassword);
    sendCtrl(pkt, LOGIN_SIZE);
  }
  bool gotLogin = false;
  unsigned long loginEnd = millis() + 5000;
  while ((long)(millis() - loginEnd) < 0)
  {
    if (!waitCtrl(500, buf, &len)) continue;
    if (len != LOGIN_RESPONSE_SIZE) continue;
    uint32_t err = getLE32(buf, 0x30);
    if (err == 0xFEFFFFFFUL) { civDisconnect(); return "CI-V: Authentication failed (username/password)"; }
    civToken = getLE32(buf, 0x1c);
    gotLogin = true;
    break;
  }
  if (!gotLogin) { civDisconnect(); return "CI-V: No response from radio (Login)"; }

  // ---- Step4: Token -> capabilities ----
  Serial.println("[CIV] step4 Token");
  {
    uint8_t pkt[TOKEN_SIZE];
    makeToken(pkt, ++ctrlSeq, authSeq++, civTokReq, civToken, 0x02, ctrlMyId, ctrlRemoteId);
    sendCtrl(pkt, TOKEN_SIZE);
  }
  bool gotTokenResp = false;
  const int CAPS_HDR = 0x42;
  unsigned long tokenEnd = millis() + 5000;
  while ((long)(millis() - tokenEnd) < 0)
  {
    if (!waitCtrl(500, buf, &len)) continue;
    if (len == TOKEN_SIZE && getLE16(buf, 4) != 0x01) { if (buf[21] == 0x05) { gotTokenResp = true; break; } }
    else if (len == CONNINFO_SIZE && getLE16(buf, 4) != 0x01)
    {
      if (!haveSavedMac) { memcpy(savedMac, buf + 0x2a, 6); haveSavedMac = true; }
      gotTokenResp = true; break;
    }
    else if (len > CONNINFO_SIZE && getLE16(buf, 4) != 0x01)
    {
      if (len >= CAPS_HDR + 0x10) { memcpy(savedMac, buf + CAPS_HDR + 0x0a, 6); haveSavedMac = true; }
      if (len >= CAPS_HDR + 0x30) memcpy(savedRadioName, buf + CAPS_HDR + 0x10, 32);
      if (len >= CAPS_HDR + 0x53)
      {
        int capsAddr = buf[CAPS_HDR + 0x52];
        if (capsAddr != 0) civAddress = capsAddr;
      }
    }
  }
  if (!gotTokenResp) { civDisconnect(); return "CI-V: No response from radio (Token)"; }
  lastTokenRenewalMs = millis();

  // ---- Step5: RequestStream -> STATUS ----
  Serial.println("[CIV] step5 RequestStream");
  {
    uint8_t pkt[CONNINFO_SIZE];
    makeRequestStream(pkt, ++ctrlSeq, authSeq++, civTokReq, civToken, civLocalPort, audioLocalPort, civUsername,
                       haveSavedMac ? savedMac : nullptr, savedRadioName, ctrlMyId, ctrlRemoteId);
    sendCtrl(pkt, CONNINFO_SIZE);
  }
  bool gotStatus = false;
  unsigned long rsEnd = millis() + 8000;
  while ((long)(millis() - rsEnd) < 0 && !gotStatus)
  {
    if (!waitCtrl(500, buf, &len)) continue;
    if (len == STATUS_SIZE)
    {
      uint32_t err = getLE32(buf, 0x30);
      if (err == 0xFFFFFFFFUL) { civDisconnect(); return "CI-V: Connection failed (Status)"; }
      gotStatus = true;
    }
    else if (len == CONNINFO_SIZE)
    {
      uint32_t busy = getLE32(buf, 0x60);
      if (busy <= 1)
      {
        uint8_t pkt2[CONNINFO_SIZE];
        makeRequestStream(pkt2, ++ctrlSeq, authSeq++, civTokReq, civToken, civLocalPort, audioLocalPort, civUsername,
                           buf + 0x2a, savedRadioName, ctrlMyId, ctrlRemoteId);
        sendCtrl(pkt2, CONNINFO_SIZE);
      }
    }
  }
  if (!gotStatus) { civDisconnect(); return "CI-V: No response from radio (RequestStream)"; }

  // ---- Step5.5: 音声(port3) AYT -> IAmHere -> AYR -> IAmReady ----
  // 無線機はIAmHereの代わりにpingを返すことがあるため、最大5回リトライする。
  Serial.println("[CIV] step5.5 audio handshake");
  bool gotAudioReady = false;
  for (int aytAttempt = 0; aytAttempt < 5 && !gotAudioReady; aytAttempt++)
  {
    uint8_t pkt[CONTROL_SIZE];
    makeCtrl(pkt, 0x03, 0, audioMyId, 0);
    sendAudio(pkt, CONTROL_SIZE);

    unsigned long aWaitEnd = millis() + 1000;
    bool gotAudioIAH = false;
    while ((long)(millis() - aWaitEnd) < 0)
    {
      int packetSize = 0;
      if (xSemaphoreTake(civAudioMutex, pdMS_TO_TICKS(50)) == pdTRUE)
      {
        packetSize = audioUdp.parsePacket();
        if (packetSize > 0) packetSize = audioUdp.read(buf, min(packetSize, 256));
        xSemaphoreGive(civAudioMutex);
      }
      if (packetSize <= 0) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }

      int rType = getLE16(buf, 4);
      if (!gotAudioIAH && packetSize == CONTROL_SIZE && rType == 0x04)
      {
        audioRemoteId = getLE32(buf, 8);
        gotAudioIAH = true;
        uint8_t ayr[CONTROL_SIZE];
        makeCtrl(ayr, 0x06, 1, audioMyId, audioRemoteId);
        sendAudio(ayr, CONTROL_SIZE);

        unsigned long iarEnd = millis() + 1000;
        while ((long)(millis() - iarEnd) < 0)
        {
          int ps2 = 0;
          if (xSemaphoreTake(civAudioMutex, pdMS_TO_TICKS(50)) == pdTRUE)
          {
            ps2 = audioUdp.parsePacket();
            if (ps2 > 0) ps2 = audioUdp.read(buf, min(ps2, 256));
            xSemaphoreGive(civAudioMutex);
          }
          if (ps2 <= 0) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }
          int t2 = getLE16(buf, 4);
          if (t2 == 0x06) { gotAudioReady = true; break; }
          if (ps2 == PING_SIZE && t2 == 0x07) handleAudioPing(buf, ps2);
        }
        break;
      }
      else if (packetSize == PING_SIZE && rType == 0x07)
      {
        handleAudioPing(buf, packetSize);
      }
    }
    if (!gotAudioReady && aytAttempt < 4) delay(200);
  }
  audioSessionReady = gotAudioReady;
  if (!gotAudioReady)
  {
    // 音声セッションが確立できなくてもCAT制御自体は継続する(音声が使えないだけ)
    Serial.println("[CIV] audio handshake failed - continuing without audio");
  }
  else
  {
    civAudioRxActive = true;
    xTaskCreatePinnedToCore(civAudioRxTask, "civAudioRx", 8192, nullptr, 2, &civAudioRxTaskHandle, 1);
    Serial.println("[CIV] audio session ready");
  }

  // ---- Step6: CI-Vポート ハンドシェイク ----
  Serial.println("[CIV] step6 CIV handshake");
  bool gotCivIAH = false, gotCivReady = false;
  unsigned long civEnd = millis() + 8000;
  while ((long)(millis() - civEnd) < 0)
  {
    if (!gotCivIAH)
    {
      uint8_t pkt[CONTROL_SIZE];
      makeCtrl(pkt, 0x03, 0, civMyId, 0);
      sendCivPkt(pkt, CONTROL_SIZE);
    }
    CivFrameMsg msg;
    if (xQueueReceive(civRxQueue, &msg, pdMS_TO_TICKS(500)) != pdTRUE) continue;
    if (msg.len != CONTROL_SIZE) continue;
    int type = getLE16(msg.data, 4);
    if (type == 0x04)
    {
      civRemoteId = getLE32(msg.data, 8);
      if (!gotCivIAH)
      {
        gotCivIAH = true;
        uint8_t pkt[CONTROL_SIZE];
        makeCtrl(pkt, 0x06, 1, civMyId, civRemoteId);
        sendCivPkt(pkt, CONTROL_SIZE);
      }
    }
    else if (type == 0x06)
    {
      civRemoteId = getLE32(msg.data, 8);
      uint8_t pkt[OPENCLOSE_SIZE];
      makeOpenClose(pkt, ++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId);
      sendCivPkt(pkt, OPENCLOSE_SIZE);
      gotCivReady = true;
      break;
    }
  }
  if (!gotCivReady)
  {
    if (civRemoteId != 0)
    {
      uint8_t pkt[OPENCLOSE_SIZE];
      makeOpenClose(pkt, ++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId);
      sendCivPkt(pkt, OPENCLOSE_SIZE);
    }
    else
    {
      civDisconnect();
      return "CI-V: No response from radio (CI-V socket)";
    }
  }

  // トランシーブ(自動ブロードキャスト)モードを有効化
  {
    uint8_t frame[8];
    uint8_t bytes[] = {0x16, 0x02, 0x01};
    int flen = buildCivFrame(frame, civAddress, bytes, 3);
    uint8_t pkt[PING_SIZE + 8];
    int pktLen = makeCivData(pkt, ++civPktSeq, civSendSeqB++, frame, flen, civMyId, civRemoteId);
    sendCivPkt(pkt, pktLen);
  }

  // ★ civDataRxTaskと同じ理由でcivAudioTxTask(優先度5)と同等に引き上げる。
  //   ctrlソケット(port1)はセッション全体の生存確認(ping)を担っており、
  //   ここへの応答遅延も無線機側のセッション/PTTタイムアウト誤判定の一因となりうる。
  civCtrlRxActive = true;
  xTaskCreatePinnedToCore(civCtrlRxTask, "civCtrlRx", 4096, nullptr, 5, &civCtrlRxTaskHandle, 1);

  civConnected = true;

  // ★ 安全対策: 本体が送信中にクラッシュ/再起動した場合、無線機側はPTT ONのまま
  //   固着してしまう。接続完了直後に明示的にPTT OFFを送り、万一固着していても解除する。
  {
    uint8_t bytes[] = {0x1C, 0x00, 0x00};
    uint8_t frame[8];
    int flen = buildCivFrame(frame, civAddress, bytes, 3);
    uint8_t body[16]; uint8_t bn;
    civExchange(frame, flen, 0xFB, -1, body, &bn, 1000);
  }

  Serial.printf("[CIV] connect OK civAddr=0x%02X\n", civAddress);
  return "";
}

// ==== 周波数 ====
static bool civGetFrequencyRaw(int64_t *outHz)
{
  uint8_t body[16]; uint8_t bn;
  int cacheCmd[] = {0x03, 0x00};
  for (int c = 0; c < 2; c++)
  {
    if (getCached(cacheCmd[c], -1, body, &bn) && bn >= 8)
    {
      int64_t freq = 0, mult = 1;
      for (int i = 3; i <= 7; i++) { int b = body[i]; freq += (b & 0xF) * mult; mult *= 10; freq += (b >> 4) * mult; mult *= 10; }
      *outHz = freq;
      return true;
    }
  }
  uint8_t bytes[] = {0x03};
  uint8_t frame[8];
  int flen = buildCivFrame(frame, civAddress, bytes, 1);
  if (!civExchange(frame, flen, 0x03, -1, body, &bn)) return false;
  if (bn < 8) return false;
  int64_t freq = 0, mult = 1;
  for (int i = 3; i <= 7; i++) { int b = body[i]; freq += (b & 0xF) * mult; mult *= 10; freq += (b >> 4) * mult; mult *= 10; }
  *outHz = freq;
  return true;
}

static bool civSetFrequencyRaw(int64_t hz)
{
  int64_t f = hz;
  uint8_t bcd[5];
  for (int i = 0; i < 5; i++) { int lo = f % 10; f /= 10; int hi = f % 10; f /= 10; bcd[i] = (hi << 4) | lo; }
  uint8_t bytes[] = {0x05, bcd[0], bcd[1], bcd[2], bcd[3], bcd[4]};
  uint8_t frame[10];
  int flen = buildCivFrame(frame, civAddress, bytes, 6);
  uint8_t body[16]; uint8_t bn;
  return civExchange(frame, flen, 0xFB, -1, body, &bn);
}

// ==== モード ====
static String modeCodeToName(int mc)
{
  switch (mc)
  {
    case 0x00: return "LSB"; case 0x01: return "USB"; case 0x02: return "AM"; case 0x03: return "CW";
    case 0x04: return "RTTY"; case 0x05: return "FM"; case 0x06: return "WFM"; case 0x07: return "CWR";
    case 0x08: return "RTTYR"; case 0x17: return "DSTAR"; default: return "USB";
  }
}
static int modeNameToCode(const String &m)
{
  String u = m; u.toUpperCase();
  if (u == "LSB") return 0x00; if (u == "USB") return 0x01; if (u == "AM") return 0x02; if (u == "CW") return 0x03;
  if (u == "RTTY") return 0x04; if (u == "FM") return 0x05; if (u == "WFM") return 0x06;
  if (u == "CWR" || u == "CW-R") return 0x07; if (u == "RTTYR") return 0x08;
  if (u == "DSTAR" || u == "D-STAR") return 0x17;
  return 0x01;
}

static bool civGetModeRaw(String *outMode, int *outWidth)
{
  uint8_t body[16]; uint8_t bn;
  if (!getCached(0x04, -1, body, &bn))
  {
    uint8_t bytes[] = {0x04};
    uint8_t frame[8];
    int flen = buildCivFrame(frame, civAddress, bytes, 1);
    // ★ 400/800msの短いタイムアウトだと、無線機の実際の応答遅延(実測1秒超のことがある)
    //   に間に合わず、応答が「次の問い合わせ」の待ち受け窓に遅れて紛れ込んでミス
    //   マッチし続ける(civExchange TIMEOUT時のqueueMsgs/dataMsgsログで確認)現象が
    //   あったため、周波数問い合わせと同じ1500msに統一する。
    if (!civExchange(frame, flen, 0x04, -1, body, &bn, 1500)) return false;
  }
  if (bn < 5) return false;
  int mc = body[3], fc = body[4];
  *outMode = modeCodeToName(mc);
  int w;
  if (mc == 0x03 || mc == 0x07) w = (fc == 0x03) ? 100 : (fc == 0x02 ? 250 : 500);
  else if (mc == 0x02) w = 6000;
  else if (mc == 0x05) w = 15000;
  else if (mc == 0x06) w = 200000;
  else w = (fc == 0x03) ? 500 : (fc == 0x02 ? 1800 : 2400);
  *outWidth = w;
  return true;
}

static bool civSetModeRaw(const String &mode, int width)
{
  int mc = modeNameToCode(mode);
  bool isCw = (mc == 0x03 || mc == 0x07);
  int fc;
  if (mc == 0x17) fc = 0x01;  // DSTAR: IC-705 only accepts filter 0x01
  else if (isCw) fc = (width <= 150) ? 0x03 : (width <= 350 ? 0x02 : 0x01);
  else fc = (width <= 600) ? 0x03 : (width <= 2000 ? 0x02 : 0x01);
  uint8_t bytes[] = {0x06, (uint8_t)mc, (uint8_t)fc};
  uint8_t frame[8];
  int flen = buildCivFrame(frame, civAddress, bytes, 3);
  uint8_t body[16]; uint8_t bn;
  return civExchange(frame, flen, 0xFB, -1, body, &bn);
}

// ==== Sメーター ====
// IC-705の生BCD値(0-241程度、120=S9目安)を、既存UIが期待する0-15スケールへ換算。
static float civGetSmeterRaw()
{
  uint8_t body[16]; uint8_t bn;
  bool ok = getCached(0x15, 0x02, body, &bn);
  if (!ok)
  {
    uint8_t bytes[] = {0x15, 0x02};
    uint8_t frame[8];
    int flen = buildCivFrame(frame, civAddress, bytes, 2);
    ok = civExchange(frame, flen, 0x15, 0x02, body, &bn, 1500);
  }
  if (!ok || bn < 6) return -1;
  int raw = ((body[4] >> 4) * 1000) + ((body[4] & 0xF) * 100) + ((body[5] >> 4) * 10) + (body[5] & 0xF);
  float s = (raw / 241.0f) * 15.0f;
  if (s < 0) s = 0; if (s > 15) s = 15;
  return s;
}

// ==== RFパワー（0x14 0x0A、レベル値0000-0255の2バイトBCD、上位バイト先出し） ====
static float civGetPowerRaw()
{
  uint8_t body[16]; uint8_t bn;
  bool ok = getCached(0x14, 0x0A, body, &bn);
  if (!ok)
  {
    uint8_t bytes[] = {0x14, 0x0A};
    uint8_t frame[8];
    int flen = buildCivFrame(frame, civAddress, bytes, 2);
    ok = civExchange(frame, flen, 0x14, 0x0A, body, &bn, 1500);
  }
  if (!ok || bn < 6) return -1;
  int raw = ((body[4] >> 4) * 1000) + ((body[4] & 0xF) * 100) + ((body[5] >> 4) * 10) + (body[5] & 0xF);
  float norm = raw / 255.0f;
  if (norm < 0) norm = 0; if (norm > 1.0f) norm = 1.0f;
  return norm;
}

static bool civSetPowerRaw(float norm)
{
  if (norm < 0) norm = 0; if (norm > 1.0f) norm = 1.0f;
  int level = (int)(norm * 255.0f + 0.5f);
  int hundreds = (level / 100) % 10;
  int tens = (level / 10) % 10;
  int ones = level % 10;
  uint8_t bytes[] = {0x14, 0x0A, (uint8_t)hundreds, (uint8_t)((tens << 4) | ones)};
  uint8_t frame[10];
  int flen = buildCivFrame(frame, civAddress, bytes, 4);
  uint8_t body[16]; uint8_t bn;
  return civExchange(frame, flen, 0xFB, -1, body, &bn);
}

// ==== スケルチ（0x14 0x03、パワーと同じ2バイトBCDレベル形式） ====
static float civGetSquelchRaw()
{
  uint8_t body[16]; uint8_t bn;
  bool ok = getCached(0x14, 0x03, body, &bn);
  if (!ok)
  {
    uint8_t bytes[] = {0x14, 0x03};
    uint8_t frame[8];
    int flen = buildCivFrame(frame, civAddress, bytes, 2);
    ok = civExchange(frame, flen, 0x14, 0x03, body, &bn, 1500);
  }
  if (!ok || bn < 6) return -1;
  int raw = ((body[4] >> 4) * 1000) + ((body[4] & 0xF) * 100) + ((body[5] >> 4) * 10) + (body[5] & 0xF);
  float norm = raw / 255.0f;
  if (norm < 0) norm = 0; if (norm > 1.0f) norm = 1.0f;
  return norm;
}

static bool civSetSquelchRaw(float norm)
{
  if (norm < 0) norm = 0; if (norm > 1.0f) norm = 1.0f;
  int level = (int)(norm * 255.0f + 0.5f);
  int hundreds = (level / 100) % 10;
  int tens = (level / 10) % 10;
  int ones = level % 10;
  uint8_t bytes[] = {0x14, 0x03, (uint8_t)hundreds, (uint8_t)((tens << 4) | ones)};
  uint8_t frame[10];
  int flen = buildCivFrame(frame, civAddress, bytes, 4);
  uint8_t body[16]; uint8_t bn;
  return civExchange(frame, flen, 0xFB, -1, body, &bn);
}

// ==== TX状態 / PTT ====
static bool civGetTxStateRaw(bool *outTx)
{
  uint8_t body[16]; uint8_t bn;
  if (getCached(0x1C, 0x00, body, &bn) && bn >= 5) { *outTx = (body[4] == 0x01); return true; }
  uint8_t bytes[] = {0x1C, 0x00};
  uint8_t frame[8];
  int flen = buildCivFrame(frame, civAddress, bytes, 2);
  if (!civExchange(frame, flen, 0x1C, 0x00, body, &bn, 1500)) return false;
  if (bn < 5) return false;
  *outTx = (body[4] == 0x01);
  return true;
}

// ==== 音声(port3)送信タスク(マイク→無線機、PTT ON中のみ稼働) ====
static void civAudioTxTask(void *param)
{
  const int SAMPLES_PER_PKT = 160; // 20ms @ 8000Hz
  const int PKT_BYTES = SAMPLES_PER_PKT * 2;
  uint8_t pkt[AUDIO_HDR + PKT_BYTES];
  bool micExt = civMicExternal();

  // プライミング: 無音パケットを6個先に送ってIC-705側のジッタバッファを埋めておく
  uint8_t silence[PKT_BYTES] = {0};
  for (int i = 0; i < 6 && civAudioTxTaskRunning; i++)
  {
    uint16_t seq = (uint16_t)(++audioPktSeq & 0xFFFF);
    uint16_t txSeq = (uint16_t)(++audioTxSeq & 0xFFFF);
    int len = makeTxAudioPacket(pkt, seq, txSeq, silence, PKT_BYTES, audioMyId, audioRemoteId);
    int slot = seq % TX_CACHE_SLOTS;
    txAudioCache[slot].seq = seq;
    memcpy(txAudioCache[slot].data, pkt, len);
    txAudioCache[slot].len = len;
    txAudioCache[slot].valid = true;
    sendAudio(pkt, len);
    vTaskDelay(pdMS_TO_TICKS(20));
  }

  // 内蔵マイクはM5.Mic.record()が非同期のため、公式サンプルと同様にリングバッファへ
  // 先読みで発行し続け、数コマ遅れたスロットを送信に使う。
  const int RING_SLOTS = 6, RING_LAG = 3;
  static int16_t ring[RING_SLOTS][SAMPLES_PER_PKT];
  int recIdx = 0, sendIdx = 0;
  if (!micExt)
  {
    for (int i = 0; i < RING_LAG; i++)
    {
      M5.Mic.record(ring[recIdx], SAMPLES_PER_PKT, 8000, false);
      recIdx = (recIdx + 1) % RING_SLOTS;
    }
  }

  // ★ FIXED_GAIN=8はAndroid版(OSマイクからの生振幅、ハードウェアゲインなし)を前提にした
  //   値。ES8388(外部)使用時はPGAで既に増幅された後の信号を、内蔵マイクもmain.cppの
  //   setup()でmagnification=24(Hamlib DATAモード駆動用に強めに設定済み)された後の
  //   信号を受け取っているため、どちらも生振幅が既にHARD_LIMITに迫っている状態
  //   (実測inPeak最大32752)。ここでさらに8倍すると常時tanhの非線形域で強く圧縮され、
  //   歪んで こもった音質になる(内蔵マイクでも実機で確認済み)。両方とも下げて
  //   線形域に収まるようにし、素直な音質にする。全体の音量バランスは、ES8388は
  //   PTT長押しのハードウェアゲイン(es8388MicGainIdx)で追い込む前提。
  const float FIXED_GAIN = micExt ? 0.6f : 1.0f;
  const float HARD_LIMIT = 29491.0f;

  Serial.printf("[%lu][civAudioTx] start micExt=%d gain=%.1f es8388GainIdx=%d audioMyId=0x%08X audioRemoteId=0x%08X\n",
                millis(), micExt, FIXED_GAIN, es8388MicGainIdx, (unsigned)audioMyId, (unsigned)audioRemoteId);

  unsigned long nextSendMs = millis();
  int16_t pcm[SAMPLES_PER_PKT];
  long dbgPktCount = 0;
  unsigned long dbgLastActualSendMs = 0; // タスク毎にリセット(PTTセッションをまたいで計測しないように)
  while (civAudioTxTaskRunning)
  {
    if (micExt)
    {
      // civI2SReadyが立っていない(未インストール/インストール失敗)間はi2s_read()を
      // 呼ばない。呼ぶとNULLポインタ参照でクラッシュする。pcmは前回値のまま送信される
      // (無音相当)が、それでもクラッシュよりは安全。
      if (civI2SReady)
      {
        size_t bytesRead = 0;
        esp_err_t rdErr = i2s_read(I2S_NUM_0, pcm, PKT_BYTES, &bytesRead, pdMS_TO_TICKS(50));
        // ★ 戻り値/読み取りバイト数を確認せずpcmを使い回すと、下のtanhリミッターが
        //   「前回の生入力」ではなく「前回の(ゲイン適用済み)出力」を再度ゲイン+tanh
        //   してしまい、読み取り失敗のたびに信号が指数関数的に減衰して数百msで
        //   無音同然になるバグがあった(実機で確認: 話し始めた直後だけ変調が乗り、
        //   その後急速に減衰して無変調になる)。読み取り失敗時は明示的に無音を
        //   送り、古い出力を再圧縮しないようにする。
        if (rdErr != ESP_OK || bytesRead < (size_t)PKT_BYTES)
        {
          memset(pcm, 0, sizeof(pcm));
        }
      }
    }
    else
    {
      M5.Mic.record(ring[recIdx], SAMPLES_PER_PKT, 8000, false);
      recIdx = (recIdx + 1) % RING_SLOTS;
      memcpy(pcm, ring[sendIdx], PKT_BYTES);
      sendIdx = (sendIdx + 1) % RING_SLOTS;
    }

    // ★ デバッグ: 入力(生マイク)のRMS/ピークを送信前に採取。「音は出るが変調が乗らない」
    //   切り分け用 — ここが常時0付近ならマイク側(ハードウェア/ゲイン)が原因、
    //   非0で無線機側に届いていないならセッション/パケット側が原因と判断できる。
    int64_t sumSq = 0; int16_t inPeak = 0; // ★ longだと160サンプル分のs*s合計が32bitでオーバーフローしrms=nanになることがあったためint64_tに
    for (int i = 0; i < SAMPLES_PER_PKT; i++)
    {
      int16_t s = pcm[i];
      sumSq += (long)s * (long)s;
      int16_t a = s < 0 ? -s : s;
      if (a > inPeak) inPeak = a;
    }
    float rms = sqrtf((float)sumSq / SAMPLES_PER_PKT);

    int16_t outPeak = 0;
    for (int i = 0; i < SAMPLES_PER_PKT; i++)
    {
      float limited = tanhf((float)pcm[i] * FIXED_GAIN / HARD_LIMIT) * HARD_LIMIT;
      pcm[i] = (int16_t)limited;
      int16_t a = pcm[i] < 0 ? (int16_t)-pcm[i] : pcm[i];
      if (a > outPeak) outPeak = a;
    }

    uint16_t seq = (uint16_t)(++audioPktSeq & 0xFFFF);
    uint16_t txSeq = (uint16_t)(++audioTxSeq & 0xFFFF);
    int len = makeTxAudioPacket(pkt, seq, txSeq, (uint8_t *)pcm, PKT_BYTES, audioMyId, audioRemoteId);
    int slot = seq % TX_CACHE_SLOTS;
    txAudioCache[slot].seq = seq;
    memcpy(txAudioCache[slot].data, pkt, len);
    txAudioCache[slot].len = len;
    txAudioCache[slot].valid = true;
    sendAudio(pkt, len);
    lastTxAudioMs = millis();

    // ★ デバッグ: 実際の送信間隔を計測。無線機はRS-BA1の20ms周期音声パケットの
    //   タイミングが乱れる(遅延/欠落)と、ジッタバッファ異常とみなしてTXを自動解除
    //   している可能性がある。この仮説を検証するため、想定(20ms)より大きく
    //   ずれた間隔を記録する。
    if (dbgLastActualSendMs != 0)
    {
      long gap = (long)(lastTxAudioMs - dbgLastActualSendMs);
      if (gap > 40)
      {
        Serial.printf("[%lu][civAudioTx][dbg] SEND GAP=%ldms (expected ~20ms)\n", lastTxAudioMs, gap);
      }
    }
    dbgLastActualSendMs = lastTxAudioMs;

    if ((dbgPktCount++ % 10) == 0)
    {
      Serial.printf("[%lu][civAudioTx] rms=%.0f inPeak=%d outPeak=%d seq=%u txSeq=%u remoteId=0x%08X\n",
                    millis(), rms, inPeak, outPeak, seq, txSeq, (unsigned)audioRemoteId);
    }

    nextSendMs += 20;
    long waitMs = (long)(nextSendMs - millis());
    if (waitMs > 0) vTaskDelay(pdMS_TO_TICKS(waitMs));
    else nextSendMs = millis();
  }

  civAudioTxTaskHandle = nullptr;
  vTaskDelete(nullptr);
}

// PTT ON時に呼ぶ(main.cppのstartAudioTx()から、CI-Vモードの場合に呼ばれる)。
// 内蔵/外部マイクのハードウェア切替も含めて自己完結する。
void civStartTxAudio()
{
  if (!civConnected || civAudioTxTaskHandle) return;

  // ★ 先にフラグを立て、civAudioRxTaskの再生判定(spkEnabled && !civTxAudioActive)を
  //   即座に不成立にしてから、civSpkHwMutexを取ってハードウェアを切り替える。
  //   civAudioRxTask側もこのmutexを(トライロックで)取ってから再生するため、
  //   ここでmutexを保持している間はM5.Speaker/I2Sへ同時アクセスされない。
  civTxAudioActive = true;

  if (civSpkHwMutex) xSemaphoreTake(civSpkHwMutex, portMAX_DELAY);

  // 本体内蔵マイク/スピーカーはI2S_NUM_1を共有し同時使用できないため、まずSPKを止める。
  M5.Speaker.end();
  M5.Mic.begin();

  if (civSpkHwMutex) xSemaphoreGive(civSpkHwMutex);

  civAudioTxTaskRunning = true;
  xTaskCreatePinnedToCore(civAudioTxTask, "civAudioTx", 8192, nullptr, 5, &civAudioTxTaskHandle, 1);
}

// PTT OFF時に呼ぶ(main.cppのstopAudioTx()から、CI-Vモードの場合に呼ばれる)。
// 送信タスクの完全停止を待ってからSPK側を復帰させる。
void civStopTxAudio()
{
  civAudioTxTaskRunning = false;
  unsigned long t = millis();
  while (civAudioTxTaskHandle && millis() - t < 1000) delay(10);

  // ★ civAudioTxTaskが時間内に自己終了しなかった場合、以降のI2S/M5.Mic破棄処理と
  //   タスク側のi2s_read()/M5.Mic.record()が同時にハードウェアへアクセスし、
  //   PTT連打でクラッシュ/フリーズする原因になる(stopPlayback()と同種の問題)。
  //   タイムアウト時は強制終了してから安全にハードウェアを戻す。
  if (civAudioTxTaskHandle)
  {
    Serial.println("[CIV] civAudioTxTask did not exit in time, force-deleting");
    vTaskDelete(civAudioTxTaskHandle);
    civAudioTxTaskHandle = nullptr;
  }

  if (civSpkHwMutex) xSemaphoreTake(civSpkHwMutex, portMAX_DELAY);

  M5.Mic.end();
  M5.Speaker.begin();

  if (civSpkHwMutex) xSemaphoreGive(civSpkHwMutex);

  // ハードウェアが再生可能な状態に戻ってからflagを下ろす(civAudioRxTaskの再生再開はこの後)。
  civTxAudioActive = false;
}

// PTTコマンドの送受信のみを行う。音声(送信)の開始/停止はmain.cppのstartAudioTx()/
// stopAudioTx()からcivStartTxAudio()/civStopTxAudio()を呼ぶことで行われる
// (sendHamlibPTT()/sendWifiPTT()と同様、PTT信号を先に送ってから音声を開始する順序を守るため)。
bool civSetPtt(bool on)
{
  uint8_t bytes[] = {0x1C, 0x00, (uint8_t)(on ? 0x01 : 0x00)};
  uint8_t frame[8];
  int flen = buildCivFrame(frame, civAddress, bytes, 3);

  // ★ iOS版(WifiRigCTRL_iOS)に厳密に合わせる: fire-and-forgetにするのはPTT ON方向のみ。
  //   PTT OFFはUDPパケットロスで無線機に届かないと、無線機がTX状態のまま固着してしまう
  //   (実機で確認済み、安全上重大)。そのためOFF方向は必ずcivExchange()で応答を待ち、
  //   確実に無線機がPTT解除コマンドを受理したことを確認してから戻る。
  if (on && civStreamOpened)
  {
    uint8_t pkt[PING_SIZE + 16];
    int pktLen = makeCivData(pkt, ++civPktSeq, civSendSeqB++, frame, flen, civMyId, civRemoteId);
    sendCivPkt(pkt, pktLen);
    return true;
  }

  // ★ PTT OFFのタイムアウトを1500msに短縮したところ、無線機からの応答が本来
  //   必要とする時間(1〜3秒程度観測)を待たずに失敗と判定され、リトライしても
  //   間に合わずTXが解除されない(無線機がTXのまま固着する)事象が実機で再発した。
  //   反映速度より確実性を優先し、3000msに戻す。
  uint8_t body[16]; uint8_t bn;
  return civExchange(frame, flen, 0xFB, -1, body, &bn, on ? 1500 : 3000);
}

// ==== コマンドキュー（非同期化） ====
enum CivCmdType { CIV_CMD_FREQ, CIV_CMD_MODE, CIV_CMD_POWER, CIV_CMD_SQL };
struct CivCommand { CivCmdType type; int64_t freqHz; String mode; int width; float value; };
static QueueHandle_t civCmdQueue = nullptr;

static void civCmdTask(void *param)
{
  CivCommand *cmd;
  while (true)
  {
    if (xQueueReceive(civCmdQueue, &cmd, portMAX_DELAY) == pdTRUE)
    {
      if (civConnected)
      {
        if (cmd->type == CIV_CMD_FREQ) civSetFrequencyRaw(cmd->freqHz);
        else if (cmd->type == CIV_CMD_MODE) civSetModeRaw(cmd->mode, cmd->width);
        else if (cmd->type == CIV_CMD_POWER) civSetPowerRaw(cmd->value);
        else civSetSquelchRaw(cmd->value);
      }
      delete cmd;
    }
  }
}

void civStartCmdTask()
{
  if (!civCmdQueue) civCmdQueue = xQueueCreate(10, sizeof(CivCommand *));
  xTaskCreatePinnedToCore(civCmdTask, "civCmdTask", 4096, nullptr, 1, nullptr, 1);
}

void civSendFreq(int64_t hz)
{
  if (!civCmdQueue) return;
  CivCommand *cmd = new CivCommand{CIV_CMD_FREQ, hz, "", 0, 0};
  xQueueSend(civCmdQueue, &cmd, 0);
}

void civSendMode(const String &mode, int width)
{
  if (!civCmdQueue) return;
  CivCommand *cmd = new CivCommand{CIV_CMD_MODE, 0, mode, width, 0};
  xQueueSend(civCmdQueue, &cmd, 0);
}

void civSendPower(float norm)
{
  if (!civCmdQueue) return;
  CivCommand *cmd = new CivCommand{CIV_CMD_POWER, 0, "", 0, norm};
  xQueueSend(civCmdQueue, &cmd, 0);
}

void civSendSquelch(float norm)
{
  if (!civCmdQueue) return;
  CivCommand *cmd = new CivCommand{CIV_CMD_SQL, 0, "", 0, norm};
  xQueueSend(civCmdQueue, &cmd, 0);
}

// ==== ステータス取得 ====
// ★ iOS/Android版のpollStatus()に合わせたCI-Vデータチャンネル復旧ロジック。
//   PTT中(tx_ing)は無線機がCI-V問い合わせに応答しにくくなることがあり、これを
//   異常として扱うと再オープン処理自体が輻輳の原因になるため、PTT中の失敗は無視する。
//   PTT中でない時に3回連続で失敗したら、CI-Vデータチャンネルを再オープンする
//   (OpenCloseの再送信 + トランシーブモード再有効化)。
static int civPollFailCount = 0;
RigStatus civFetchRigStatus()
{
  RigStatus st;
  st.valid = false;
  st.tx = false; st.bkin = false; st.signal = 0;

  if (!civConnected) return st;

  // ★ デバッグ: civFetchRigStatus()自体がどの程度の頻度で呼ばれているか、
  //   tx_ing(PTT中フラグ)の状態と合わせて5秒毎にダンプする。
  static long dbgFetchCount = 0;
  static unsigned long dbgFetchLastDump = 0;
  dbgFetchCount++;
  if (millis() - dbgFetchLastDump > 5000)
  {
    Serial.printf("[%lu][civ][dbg] civFetchRigStatus called %ld times in last window, tx_ing=%d\n",
                  millis(), dbgFetchCount, tx_ing);
    dbgFetchCount = 0;
    dbgFetchLastDump = millis();
  }

  int64_t freq;
  bool freqOk = civGetFrequencyRaw(&freq);
  if (freqOk)
  {
    st.freq = String((long)freq);
    civPollFailCount = 0;
  }
  else if (!tx_ing)
  {
    civPollFailCount++;
    Serial.printf("[%lu][civ][dbg] poll fail %d/3\n", millis(), civPollFailCount);
    if (civPollFailCount >= 3)
    {
      // ★ 以前はここでOpenCloseを再送してcivStreamOpened=falseにしていたが、
      //   civStreamOpened=falseの間はcivExchange()が問い合わせのたびに毎回
      //   OpenCloseを再送する設計のため、一度この状態に入ると「再送→無線機が混乱
      //   →二度と応答が来ない→civStreamOpenedが永久にfalseのまま」という回復不能な
      //   デッドロックに陥ることが実機ログで確認された(civExchange TIMEOUT時の
      //   civStreamOpened=0が固定、dataMsgs=0が継続)。当初の失敗の根本原因は
      //   各問い合わせのタイムアウトが無線機の実応答時間より短すぎたことだった
      //   (1500msに統一済み)。この自動reopenはむしろ有害なため無効化し、
      //   カウンタのリセットのみ行う(次のfreqOk成功時にも0クリアされる)。
      Serial.println("[CIV] CI-V freq poll failing repeatedly (not reopening - this was found harmful)");
      civPollFailCount = 0;
    }
  }

  String mode; int width;
  if (civGetModeRaw(&mode, &width))
  {
    st.mode = mode;
    lastWidth = width;
    for (int i = 0; i < (int)supportedWidths.size(); i++)
    {
      if (supportedWidths[i] == lastWidth) { selWidthIndex = i; break; }
    }
  }

  float s = civGetSmeterRaw();
  if (s >= 0) st.signal = s;

  bool tx;
  static bool lastTxDbg = false;
  static unsigned long dbgTxLastDump = 0;
  bool txOk = civGetTxStateRaw(&tx);
  if (txOk)
  {
    st.tx = tx;
    if (tx != lastTxDbg)
    {
      Serial.printf("[%lu][civ][dbg] radio-reported TX state changed: tx=%d\n", millis(), tx);
      lastTxDbg = tx;
    }
  }
  else
  {
    Serial.printf("[%lu][civ][dbg] civGetTxStateRaw FAILED (no response)\n", millis());
  }
  // ★ 変化の有無に関わらず、現在値を定期的にダンプ(txがずっと変化しない場合の
  //   切り分け用。無線機が本当にTX=trueを一度でも報告しているかを確認する)。
  if (millis() - dbgTxLastDump > 2000)
  {
    Serial.printf("[%lu][civ][dbg] tx poll: ok=%d tx=%d tx_ing(local)=%d\n", millis(), txOk, tx, tx_ing);
    dbgTxLastDump = millis();
  }

  // ★ PTT ON未受理の保険: civSetPtt(true)はUDPロス対策でfire-and-forget
  //   (応答を待たない)にしているため、その1パケットがロストすると無線機側は
  //   PTTを受理しないまま(tx=false)で、M5側だけがtx_ing=trueで音声送信を続け
  //   結果的に無線機からは完全に無音、という事象が実機で確認された。
  //   ローカルでTX中のはずなのに無線機がtx=falseを報告し続ける場合は
  //   civSetPtt(true)を再送して受理を促す(最大3回、800ms間隔)。
  static unsigned long txConfirmRetryLastMs = 0;
  static int txConfirmRetryCount = 0;
  if (tx_ing)
  {
    if (txOk && !tx)
    {
      unsigned long now = millis();
      if (txConfirmRetryLastMs == 0) txConfirmRetryLastMs = now;
      if (now - txConfirmRetryLastMs > 800 && txConfirmRetryCount < 3)
      {
        Serial.printf("[%lu][civ][dbg] PTT ON not confirmed by radio - resending civSetPtt(true) (retry %d/3)\n",
                      now, txConfirmRetryCount + 1);
        civSetPtt(true);
        txConfirmRetryCount++;
        txConfirmRetryLastMs = now;
      }
    }
    else if (txOk && tx)
    {
      txConfirmRetryLastMs = 0;
      txConfirmRetryCount = 0;
    }
  }
  else
  {
    txConfirmRetryLastMs = 0;
    txConfirmRetryCount = 0;
  }

  float pw = civGetPowerRaw();
  if (pw >= 0) currentPowerNorm = pw;

  float sq = civGetSquelchRaw();
  if (sq >= 0) sqlLevel = sq;

  st.model = "IC-705";
  st.valid = true;
  return st;
}

// ==== 設定ロード ====
void loadCivSettings()
{
  Preferences p;
  p.begin("civconn", true);
  useCIV      = p.getBool("useCIV", false);
  civHost     = p.getString("host", "192.168.1.1");
  civPort1    = p.getInt("port1", 50001);
  civPort2    = p.getInt("port2", 50002);
  civPort3    = p.getInt("port3", 50003);
  civUsername = p.getString("user", "");
  civPassword = p.getString("pass", "");
  civAddress  = p.getInt("civaddr", 0xA4);
  bool civResetApplied = p.getBool("civResetV1", false);
  p.end();

  // 旧バージョンでRasPiコネクト選択時にuseCIV=falseを保存し忘れていたため、
  // useCIV=trueのまま固着した端末が存在する。初回起動時のみ強制的にRasPi側へ
  // リセットし、以後は通常どおり最後に使ったモードを記憶させる。
  if (!civResetApplied)
  {
    useCIV = false;
    Preferences pw;
    pw.begin("civconn", false);
    pw.putBool("useCIV", false);
    pw.putBool("civResetV1", true);
    pw.end();
  }
}
