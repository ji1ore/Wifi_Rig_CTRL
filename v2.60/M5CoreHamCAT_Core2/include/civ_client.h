/****************************************************
 *  Wifi_Rig_CTRL civ_client.h
 *  CI-V WiFi直結（Icom WLAN Remote, RS-BA1互換UDPプロトコル）
 *  制御(周波数/モード/PTT/Sメーター)に加え、音声(port3, RS-BA1互換PCM16/8kHz)にも対応。
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

// ---- 音声(port3)送信の開始/停止。main.cppのstartAudioTx()/stopAudioTx()から、
//      useCIV時にPTT信号送信の"後"(ON)/"前"(OFF)のタイミングで呼ぶこと ----
void civStartTxAudio();
void civStopTxAudio();

// ---- タスク起動（setup()で一度だけ） ----
void civStartCmdTask();
