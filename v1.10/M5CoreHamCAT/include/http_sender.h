#pragma once
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

// HTTPコマンド構造体
struct HttpCommand {
    String path;
    String body;
};

// キュー（main.cppで実体を作る）
extern QueueHandle_t httpQueue;

// 送信タスク
void httpSenderTask(void *param);

// 送信API
void sendPtt(bool on);
void sendFreq(int64_t freq);
void sendMode(String mode, int width);
void sendLevel(String name, float value);
void sendPower(float power);
