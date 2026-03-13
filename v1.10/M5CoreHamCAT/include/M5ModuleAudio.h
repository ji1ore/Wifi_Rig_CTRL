/****************************************************
 *  M5CoreHamCAT M5ModuleAudio.h
 *  Ver1.00
 *  by JI1ORE
 ****************************************************/
#pragma once
#include "audio_i2c.hpp"
#include "es8388.hpp"

class M5ModuleAudio {
public:
  AudioI2c device;
  ES8388* es8388 = nullptr;

  void begin(TwoWire* wire = &Wire, int sda = 12, int scl = 11) {
    device.begin(wire, sda, scl);
    device.setHPMode(AUDIO_HPMODE_NATIONAL);
    device.setMICStatus(AUDIO_MIC_OPEN);
    device.setRGBBrightness(100);

    es8388 = new ES8388(wire, sda, scl);
    if (!es8388->init()) {
      Serial.println("ES8388 init failed!");
    }
    es8388->setADCVolume(100);
    es8388->setDACVolume(80);
    es8388->setDACOutput(DAC_OUTPUT_OUT1);
    es8388->setBitsSample(ES_MODULE_ADC, BIT_LENGTH_16BITS);
    es8388->setSampleRate(SAMPLE_RATE_44K);
  }

  void setVolume(uint8_t volume) {
    if (es8388) es8388->setDACVolume(volume);
  }

  void setVolumeBoost(bool enable) {
    if (es8388) {
      es8388->setDACVolume(enable ? 100 : 60);
    }
  }

  void tone(uint16_t freq, uint32_t duration) {
    // 未実装
  }
};
