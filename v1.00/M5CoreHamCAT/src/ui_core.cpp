/****************************************************
 *  M5CoreHamCAT ui_core.cpp
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#include "ui_core.h"
#include "globals.h"


void ui_init() {
    canvas.createSprite(320, 240);
    canvas.setTextDatum(top_left);
    canvas.setTextColor(WHITE);
    canvas.setFont(&fonts::efontJA_16);
}

void ui_clear() {
    canvas.fillScreen(BLACK);
}

void ui_drawTitle(const char* title) {
    canvas.setTextColor(CYAN);
    canvas.drawString(title, 10, 10);
    canvas.setTextColor(WHITE);
}
