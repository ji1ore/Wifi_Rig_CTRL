/****************************************************
 *  Wifi_Rig_CTRL gps_relay.h
 *  WiFiテザリング元のAndroid(Tasker)へM5から定期的に位置情報を取りに行き、
 *  Raspberry Pi(FastAPIバックエンド)の /gps へ中継する。
 *  by JI1ORE
 ****************************************************/
#pragma once

// AndroidのTasker HTTPサーバーが listen するポート（M5がここへポーリングしにいく）
static const int TASKER_HTTP_PORT = 8080;

// WiFi接続後に一度だけ呼ぶ（内部でポーリングタスクを起動。二重呼び出しは無視される）
void startGpsPolling();

// Androidから直近 maxAgeMs 以内に位置情報を取得済みなら true
bool gpsFixIsFresh(unsigned long maxAgeMs);

// Androidへその場で同期的に1回だけGPSを取りに行く（バックグラウンドの30秒周期を待たない）。
// 成功時は lastGpsFix を更新し、aprsUseGPS中ならaprsManualLat/Lonも更新する。
bool fetchGpsNow(float &lat, float &lon);
