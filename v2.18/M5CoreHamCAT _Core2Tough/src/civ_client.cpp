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
                               int civLocalPort, const String &username,
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
  p[0x71] = 0x00; // 音声TX無効（フェーズ1は制御のみ）
  p[0x72] = 0x04;
  p[0x73] = 0x00; // 音声コーデック未使用
  putBE32(p, 0x74, 8000);
  putBE32(p, 0x78, 8000);
  putBE32(p, 0x7c, (uint32_t)civLocalPort);
  putBE32(p, 0x80, 0); // audioLocalPort: 音声未使用のため0
  putBE32(p, 0x84, 200);
  p[0x88] = 0x00; // RXのみ（TX音声は使わない）
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

// ==== 受信タスク ====
static void civCtrlRxTask(void *param)
{
  unsigned long lastKeepalive = millis();
  uint8_t buf[256];
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
    if (packetSize > 0)
    {
      if (!handleCtrlPing(buf, packetSize))
      {
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
      if (!handleCivPing(buf, packetSize))
      {
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
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  civDataRxTaskHandle = nullptr;
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

  if (!civStreamOpened)
  {
    uint8_t oc[OPENCLOSE_SIZE];
    makeOpenClose(oc, ++civPktSeq, civSendSeqB++, true, civMyId, civRemoteId);
    sendCivPkt(oc, OPENCLOSE_SIZE);
  }

  uint8_t pkt[PING_SIZE + 16];
  int pktLen = makeCivData(pkt, ++civPktSeq, civSendSeqB++, civFrame, civFrameLen, civMyId, civRemoteId);
  sendCivPkt(pkt, pktLen);

  unsigned long deadline = millis() + effTimeout;
  CivFrameMsg msg;
  while ((long)(millis() - deadline) < 0)
  {
    long remain = (long)(deadline - millis());
    if (remain <= 0) break;
    if (xQueueReceive(civRxQueue, &msg, pdMS_TO_TICKS(min(remain, 50L))) != pdTRUE) continue;

    if (msg.len <= PING_SIZE) continue;
    if (getLE16(msg.data, 4) == 0x01) continue;
    int dataLen = getLE16(msg.data, 17);
    if (msg.len < PING_SIZE + dataLen || dataLen <= 0) continue;
    const uint8_t *civData = msg.data + PING_SIZE;

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

  civCtrlRxActive = false;
  civDataRxActive = false;
  unsigned long t = millis();
  while ((civCtrlRxTaskHandle || civDataRxTaskHandle) && millis() - t < 600) delay(10);

  ctrlUdp.stop();
  civUdp.stop();
  civAddrValid = false;

  if (civRxQueue) { CivFrameMsg m; while (xQueueReceive(civRxQueue, &m, 0) == pdTRUE) {} }
}

bool civIsConnected() { return civConnected; }

String connectToCivRadio()
{
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
  if (!civExchangeMutex) civExchangeMutex = xSemaphoreCreateMutex();

  // WiFiUDPにlocalPort()が無いため、begin(0)のOS割当エフェメラルポートは取得できない。
  // 代わりに接続の都度ランダムなローカルポートを明示指定する
  // （固定ポートだと無線機が再接続を古いセッションとして扱うため、毎回変える）。
  uint16_t ctrlLocalPort = (uint16_t)random(20000, 60000);
  uint16_t civLocalPort  = (uint16_t)random(20000, 60000);
  ctrlUdp.begin(ctrlLocalPort);
  civUdp.begin(civLocalPort);

  IPAddress localIp = WiFi.localIP();
  ctrlMyId = computeMyId(localIp, ctrlLocalPort);
  civMyId  = computeMyId(localIp, civLocalPort);

  // civDataRxTaskはCI-Vソケット確保後すぐ起動し、以降のpingは全てここで処理する
  civDataRxActive = true;
  xTaskCreatePinnedToCore(civDataRxTask, "civDataRx", 4096, nullptr, 2, &civDataRxTaskHandle, 1);

  uint8_t buf[256]; int len;

  // ---- 事前ウェイクバースト ----
  // IC-705のWiFiがパワーセーブ中だと最初のAreYouThereに応答しないことがあるため、
  // Android版と同様に本番のAYTループの前に軽く連投して起こしておく。
  for (int i = 0; i < 5; i++)
  {
    uint8_t wakePkt[CONTROL_SIZE];
    makeCtrl(wakePkt, 0x03, 0, ctrlMyId, 0);
    sendCtrl(wakePkt, CONTROL_SIZE);
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
    makeRequestStream(pkt, ++ctrlSeq, authSeq++, civTokReq, civToken, civLocalPort, civUsername,
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
        makeRequestStream(pkt2, ++ctrlSeq, authSeq++, civTokReq, civToken, civLocalPort, civUsername,
                           buf + 0x2a, savedRadioName, ctrlMyId, ctrlRemoteId);
        sendCtrl(pkt2, CONNINFO_SIZE);
      }
    }
  }
  if (!gotStatus) { civDisconnect(); return "CI-V: No response from radio (RequestStream)"; }

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

  civCtrlRxActive = true;
  xTaskCreatePinnedToCore(civCtrlRxTask, "civCtrlRx", 4096, nullptr, 2, &civCtrlRxTaskHandle, 1);

  civConnected = true;
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
    if (!civExchange(frame, flen, 0x04, -1, body, &bn, 800)) return false;
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
  if (isCw) fc = (width <= 150) ? 0x03 : (width <= 350 ? 0x02 : 0x01);
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
    ok = civExchange(frame, flen, 0x15, 0x02, body, &bn, 400);
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
    ok = civExchange(frame, flen, 0x14, 0x0A, body, &bn, 800);
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
    ok = civExchange(frame, flen, 0x14, 0x03, body, &bn, 800);
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
  if (!civExchange(frame, flen, 0x1C, 0x00, body, &bn, 400)) return false;
  if (bn < 5) return false;
  *outTx = (body[4] == 0x01);
  return true;
}

bool civSetPtt(bool on)
{
  uint8_t bytes[] = {0x1C, 0x00, (uint8_t)(on ? 0x01 : 0x00)};
  uint8_t frame[8];
  int flen = buildCivFrame(frame, civAddress, bytes, 3);
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
RigStatus civFetchRigStatus()
{
  RigStatus st;
  st.valid = false;
  st.tx = false; st.bkin = false; st.signal = 0;

  if (!civConnected) return st;

  int64_t freq;
  if (civGetFrequencyRaw(&freq)) st.freq = String((long)freq);

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
  if (civGetTxStateRaw(&tx)) st.tx = tx;

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
