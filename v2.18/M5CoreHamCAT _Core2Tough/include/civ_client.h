/****************************************************
 *  Wifi_Rig_CTRL civ_client.h
 *  CI-V WiFi直結（Icom WLAN Remote, RS-BA1互換UDPプロトコル）
 *  フェーズ1: 制御のみ（周波数/モード/PTT/Sメーター）。音声トンネリングは対象外。
 *  by JI1ORE
 ****************************************************/
#pragma once
#include <Arduino.h>
#include "ui_core.h" // struct RigStatus

// ---- 設定の読み込み（Preferences("civconn")） ----
void loadCivSettings();

// ---- 接続/切断 ----
// 戻り値: 成功時 ""、失敗時はエラーメッセージ文字列（connectToRasPiServices()と同じ規約）
String connectToCivRadio();
void civDisconnect();
bool civIsConnected();

// ---- ステータス取得（fetchRigStatus()のCI-V版） ----
RigStatus civFetchRigStatus();

// ---- コマンド送信（非同期。sendFreq()/sendMode()のCI-V版） ----
void civSendFreq(int64_t hz);
void civSendMode(const String &mode, int width);
void civSendPower(float norm); // sendPower()のCI-V版（0.0-1.0正規化）
void civSendSquelch(float norm); // sendLevel("SQL",...)のCI-V版（0.0-1.0正規化）

// ---- PTT（txControlTaskから直接・同期呼び出し） ----
bool civSetPtt(bool on);

// ---- タスク起動（setup()で一度だけ） ----
void civStartCmdTask();
