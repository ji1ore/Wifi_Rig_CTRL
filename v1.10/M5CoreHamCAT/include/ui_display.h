/****************************************************
 *  M5CoreHamCAT ui_display.h
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#pragma once
#include <Arduino.h>
#include <M5GFX.h>
#include "globals.h"

extern M5Canvas canvas;

void drawCentered(const char *txt, int x, int y, uint16_t color = WHITE);
void drawLabel(const char *txt, int x, int y, uint16_t color = WHITE);

void drawWifiScreen();
void drawPasswordScreen();
void drawPiConfigScreen();
void drawRigSelectScreen();
void drawDeviceSelectScreen();
void drawMainUI(const String &freq, const String &mode, const String &model, int signalStrength);
