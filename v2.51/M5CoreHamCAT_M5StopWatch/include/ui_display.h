/****************************************************
 *  Wifi_Rig_CTRL ui_display.h
 *  Ver2.5
 *  by JI1ORE
 ****************************************************/
#pragma once
#include <Arduino.h>
#include <M5GFX.h>
#include "globals.h"

extern M5Canvas canvas;

// drawCentered()/drawLabel()はui_core.hで宣言(円形デザインシステム側の実装を使う)。

void drawWifiScreen();
void drawPasswordScreen();
void drawPiConfigScreen();
void drawRigSelectScreen();
void drawDeviceSelectScreen();
void drawMainUI();
