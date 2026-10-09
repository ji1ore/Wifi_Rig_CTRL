// ft8_encode_main.cpp  — standalone FT8/FT4 PCM encoder for Raspberry Pi
// Usage: ft8_encode <message> <base_hz> [sample_rate] [is_ft4]
// Output: raw S16_LE mono PCM to stdout
//
// Build (after cloning kgoba/ft8_lib to /opt/ft8_lib):
//   g++ -O2 -std=c++17 -I/opt/ft8_lib \
//       /opt/ft8_lib/ft8/encode.c  /opt/ft8_lib/ft8/message.c \
//       /opt/ft8_lib/ft8/ldpc.c   /opt/ft8_lib/ft8/crc.c \
//       /opt/ft8_lib/ft4/encode.c \
//       ft8_encode_main.cpp -lm -o /usr/local/bin/ft8_encode

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cstdint>

#include "ft8/message.h"
#include "ft8/encode.h"
#include "ft8/constants.h"

static bool noop_lookup(ftx_callsign_hash_type_t, uint32_t, char* c) {
    if (c) c[0] = '\0';
    return false;
}
static void noop_save(const char*, uint32_t) {}
static ftx_callsign_hash_interface_t s_hash = { noop_lookup, noop_save };

int main(int argc, char* argv[]) {
    if (argc < 3) {
        fprintf(stderr, "Usage: ft8_encode <message> <base_hz> [sample_rate] [is_ft4]\n");
        return 1;
    }

    const char* msg_text   = argv[1];
    float       base_hz    = (float)atof(argv[2]);
    int         sample_rate = (argc > 3) ? atoi(argv[3]) : 12000;
    int         is_ft4     = (argc > 4) ? atoi(argv[4]) : 0;

    ftx_message_t msg = {};
    ftx_message_rc_t rc = ftx_message_encode(&msg, &s_hash, msg_text);
    if (rc != FTX_MESSAGE_RC_OK) {
        fprintf(stderr, "ftx_message_encode failed rc=%d for '%s'\n", rc, msg_text);
        return 2;
    }

    int   n_tones    = is_ft4 ? FT4_NN           : FT8_NN;
    float sym_period = is_ft4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    float tone_space = 1.0f / sym_period;

    // Max of FT8_NN and FT4_NN
    uint8_t tones[160] = {};
    if (is_ft4) ft4_encode(msg.payload, tones);
    else        ft8_encode(msg.payload, tones);

    int samp_per_sym = (int)(sym_period * sample_rate + 0.5f);
    int n_samples    = n_tones * samp_per_sym;

    int16_t* pcm = (int16_t*)malloc(n_samples * sizeof(int16_t));
    if (!pcm) { fprintf(stderr, "malloc failed\n"); return 3; }

    float phase   = 0.0f;
    const float TWO_PI = 6.28318530718f;
    for (int i = 0; i < n_tones; i++) {
        float freq   = base_hz + tones[i] * tone_space;
        float dphase = TWO_PI * freq / sample_rate;
        int   base   = i * samp_per_sym;
        for (int j = 0; j < samp_per_sym; j++) {
            pcm[base + j] = (int16_t)(sinf(phase) * 32767.0f);
            phase += dphase;
            if (phase >= TWO_PI) phase -= TWO_PI;
        }
    }

    fwrite(pcm, sizeof(int16_t), n_samples, stdout);
    free(pcm);
    return 0;
}
