/****************************************************
 *  M5CoreHamCAT ui_core.h
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#pragma once
#include <M5Unified.h>

extern M5Canvas canvas;

void ui_init();
void ui_clear();
void ui_drawTitle(const char* title);
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
