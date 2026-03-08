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

struct RigStatus
{
  String freq;
  String mode;
  String model;
  int signal;
  bool tx;
  bool bkin;
  bool valid; // 取得成功フラグ
};

RigStatus fetchRigStatus();
