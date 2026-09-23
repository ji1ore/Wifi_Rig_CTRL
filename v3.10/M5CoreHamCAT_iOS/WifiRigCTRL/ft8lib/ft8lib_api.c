// ft8lib_api.c — Pure-C FT8 encode/decode for iOS (adapted from ft8_jni.c).
// Include paths: $(SRCROOT)/WifiRigCTRL/ft8lib must be in HEADER_SEARCH_PATHS.

#include "ft8lib_api.h"
#include "ft8/message.h"
#include "ft8/encode.h"
#include "ft8/decode.h"
#include "ft8/constants.h"
#include "common/monitor.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdbool.h>

// ── Callsign hash table ────────────────────────────────────────────────────

#define HASHTABLE_SIZE 256

static struct {
    char     callsign[12];
    uint32_t hash;
} g_hashtable[HASHTABLE_SIZE];

static void hashtable_reset(void) {
    memset(g_hashtable, 0, sizeof(g_hashtable));
}

static bool hashtable_lookup(ftx_callsign_hash_type_t type, uint32_t hash, char* call) {
    uint8_t  shift = (type == FTX_CALLSIGN_HASH_10_BITS) ? 12u :
                     (type == FTX_CALLSIGN_HASH_12_BITS) ? 10u : 0u;
    uint16_t h10   = (hash >> (12u - shift)) & 0x3FFu;
    int idx = ((int)h10 * 23) % HASHTABLE_SIZE;
    while (g_hashtable[idx].callsign[0]) {
        if (((g_hashtable[idx].hash & 0x3FFFFFu) >> shift) == hash) {
            strcpy(call, g_hashtable[idx].callsign);
            return true;
        }
        idx = (idx + 1) % HASHTABLE_SIZE;
    }
    call[0] = '\0';
    return false;
}

static void hashtable_add(const char* call, uint32_t hash) {
    uint16_t h10 = (hash >> 12u) & 0x3FFu;
    int idx = ((int)h10 * 23) % HASHTABLE_SIZE;
    while (g_hashtable[idx].callsign[0]) {
        if ((g_hashtable[idx].hash & 0x3FFFFFu) == hash &&
            strcmp(g_hashtable[idx].callsign, call) == 0)
            return;
        idx = (idx + 1) % HASHTABLE_SIZE;
    }
    strncpy(g_hashtable[idx].callsign, call, 11);
    g_hashtable[idx].callsign[11] = '\0';
    g_hashtable[idx].hash = hash;
}

static ftx_callsign_hash_interface_t g_hash_if = {
    .lookup_hash = hashtable_lookup,
    .save_hash   = hashtable_add,
};

// ── GFSK synthesis ─────────────────────────────────────────────────────────

#define GFSK_CONST_K 5.336446f
#define FT8_SYMBOL_BT 2.0f

static void gfsk_pulse(int n_spsym, float bt, float* pulse) {
    for (int i = 0; i < 3 * n_spsym; i++) {
        float t  = i / (float)n_spsym - 1.5f;
        pulse[i] = (erff(GFSK_CONST_K * bt * (t + 0.5f)) -
                    erff(GFSK_CONST_K * bt * (t - 0.5f))) / 2.0f;
    }
}

static float* synth_gfsk(const uint8_t* tones, int n_sym,
                          float f0, float bt, float sym_period,
                          int rate, int* out_len) {
    int n_spsym = (int)(0.5f + rate * sym_period);
    int n_wave  = n_sym * n_spsym;
    *out_len    = n_wave;

    float* dphi   = (float*)calloc((size_t)(n_wave + 2 * n_spsym), sizeof(float));
    float* signal = (float*)malloc((size_t)n_wave * sizeof(float));
    if (!dphi || !signal) { free(dphi); free(signal); *out_len = 0; return NULL; }

    float dphi_base = 2.0f * (float)M_PI * f0 / (float)rate;
    float dphi_peak = 2.0f * (float)M_PI / (float)n_spsym;
    for (int i = 0; i < n_wave + 2 * n_spsym; i++) dphi[i] = dphi_base;

    float* pulse = (float*)malloc((size_t)(3 * n_spsym) * sizeof(float));
    if (!pulse) { free(dphi); free(signal); *out_len = 0; return NULL; }
    gfsk_pulse(n_spsym, bt, pulse);

    for (int i = 0; i < n_sym; i++) {
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; j++)
            dphi[j + ib] += dphi_peak * (float)tones[i] * pulse[j];
    }
    for (int j = 0; j < 2 * n_spsym; j++) {
        dphi[j]                   += dphi_peak * pulse[j + n_spsym] * (float)tones[0];
        dphi[j + n_sym * n_spsym] += dphi_peak * pulse[j]           * (float)tones[n_sym - 1];
    }
    free(pulse);

    float phi = 0.0f;
    for (int k = 0; k < n_wave; k++) {
        signal[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2.0f * (float)M_PI);
    }
    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; i++) {
        float env = (1.0f - cosf(2.0f * (float)M_PI * (float)i / (2 * n_ramp))) / 2.0f;
        signal[i]          *= env;
        signal[n_wave-1-i] *= env;
    }
    free(dphi);
    return signal;
}

// ── Encode ─────────────────────────────────────────────────────────────────

#define FT4_SYMBOL_BT 1.0f

Ft8EncodeResult ft8lib_encode(const char* msg, float base_hz, int sample_rate, int is_ft4) {
    Ft8EncodeResult r = {NULL, 0};
    if (!msg) return r;

    ftx_message_t message;
    ftx_message_rc_t rc = ftx_message_encode(&message, &g_hash_if, msg);
    if (rc != FTX_MESSAGE_RC_OK) return r;

    int sig_len = 0;
    float* signal;
    int total;

    if (is_ft4) {
        uint8_t tones[FT4_NN];
        ft4_encode(message.payload, tones);
        signal = synth_gfsk(tones, FT4_NN, base_hz, FT4_SYMBOL_BT,
                            FT4_SYMBOL_PERIOD, sample_rate, &sig_len);
        total  = (int)(FT4_SLOT_TIME * (float)sample_rate);
    } else {
        uint8_t tones[FT8_NN];
        ft8_encode(message.payload, tones);
        signal = synth_gfsk(tones, FT8_NN, base_hz, FT8_SYMBOL_BT,
                            FT8_SYMBOL_PERIOD, sample_rate, &sig_len);
        total  = (int)(FT8_SLOT_TIME * (float)sample_rate);
    }
    if (!signal) return r;

    // FT4: place signal at slot start (silence=0) so dt≈0.
    // ftx_find_candidates only searches time_offset=-10..19 (max dt=0.912s at 8kHz/FT4),
    // so the centered dt=1.23s is out of range and cannot be decoded.
    // FT8: keep centering — FT8 dt=1.18s maps to block 7, within range.
    int silence = is_ft4 ? 0 : (total - sig_len) / 2;

    int16_t* buf = (int16_t*)calloc((size_t)total, sizeof(int16_t));
    if (!buf) { free(signal); return r; }

    for (int i = 0; i < sig_len && (silence + i) < total; i++) {
        float s = signal[i];
        if (s >  1.0f) s =  1.0f;
        if (s < -1.0f) s = -1.0f;
        buf[silence + i] = (int16_t)(s * 32767.0f);
    }
    free(signal);

    r.samples = buf;
    r.count   = total;
    return r;
}

void ft8lib_free_encode_result(Ft8EncodeResult* r) {
    if (r && r->samples) { free(r->samples); r->samples = NULL; r->count = 0; }
}

// ── Decode ─────────────────────────────────────────────────────────────────

#define MAX_CANDIDATES  140
#define MAX_DECODED      50
#define MIN_SCORE        10
#define LDPC_ITERATIONS  25

Ft8DecodeResult ft8lib_decode(const int16_t* samples, int n_samples, int sample_rate,
                               float freq_min, float freq_max,
                               const char* my_call, const char* dx_call,
                               int max_results, int is_ft4) {
    Ft8DecodeResult r = {NULL, 0};
    (void)my_call; (void)dx_call;  // pre-seeding optional

    hashtable_reset();

    float* fsamp = (float*)malloc((size_t)n_samples * sizeof(float));
    if (!fsamp) return r;
    for (int i = 0; i < n_samples; i++)
        fsamp[i] = (float)samples[i] / 32768.0f;

    monitor_config_t cfg = {
        .f_min       = freq_min,
        .f_max       = freq_max,
        .sample_rate = sample_rate,
        .time_osr    = 2,
        .freq_osr    = 2,
        .protocol    = is_ft4 ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8,
    };
    monitor_t mon;
    monitor_init(&mon, &cfg);
    // Compute max amplitude for debug (before freeing fsamp)
    float max_amp = 0.0f;
    for (int i = 0; i < n_samples; i++) {
        float a = fsamp[i] < 0 ? -fsamp[i] : fsamp[i];
        if (a > max_amp) max_amp = a;
    }

    for (int pos = 0; pos + mon.block_size <= n_samples; pos += mon.block_size)
        monitor_process(&mon, fsamp + pos);
    free(fsamp);

    ftx_candidate_t cands[MAX_CANDIDATES];
    int n_cands = ftx_find_candidates(&mon.wf, MAX_CANDIDATES, cands, MIN_SCORE);
    fprintf(stderr, "[ft8lib] decode n_cands=%d max_amp=%.4f is_ft4=%d\n", n_cands, max_amp, is_ft4);
    if (n_cands > 0) {
        fprintf(stderr, "[ft8lib] best: t=%d f=%d sub_t=%d sub_f=%d score=%d\n",
                cands[0].time_offset, cands[0].freq_offset,
                cands[0].time_sub, cands[0].freq_sub, cands[0].score);
    }

    ftx_message_t decoded[MAX_DECODED];
    ftx_message_t* dedup_ht[MAX_DECODED];
    memset(dedup_ht, 0, sizeof(dedup_ht));

    int cap = (max_results > 0 && max_results < MAX_DECODED) ? max_results : MAX_DECODED;
    char** json_arr = (char**)calloc((size_t)cap, sizeof(char*));
    if (!json_arr) { monitor_free(&mon); return r; }
    int n_out = 0;

    for (int i = 0; i < n_cands && n_out < cap; i++) {
        const ftx_candidate_t* cand = &cands[i];
        ftx_message_t message;
        ftx_decode_status_t status;
        bool ok = ftx_decode_candidate(&mon.wf, cand, LDPC_ITERATIONS, &message, &status);
        if (i < 5) {
            fprintf(stderr, "[ft8lib] cand[%d] t=%d f=%d score=%d ldpc_err=%d crc=%s\n",
                    i, cand->time_offset, cand->freq_offset, cand->score,
                    status.ldpc_errors,
                    (status.crc_extracted == status.crc_calculated) ? "ok" : "bad");
        }
        if (!ok) continue;

        int slot    = (int)(message.hash % (uint16_t)MAX_DECODED);
        bool is_dup = false;
        for (int k = 0; k < MAX_DECODED; k++) {
            int s = (slot + k) % MAX_DECODED;
            if (!dedup_ht[s]) {
                memcpy(&decoded[s], &message, sizeof(message));
                dedup_ht[s] = &decoded[s];
                break;
            }
            if (dedup_ht[s]->hash == message.hash &&
                memcmp(dedup_ht[s]->payload, message.payload, sizeof(message.payload)) == 0) {
                is_dup = true; break;
            }
        }
        if (is_dup) continue;

        char text[FTX_MAX_MESSAGE_LENGTH + 1];
        ftx_message_offsets_t offsets;
        if (ftx_message_decode(&message, &g_hash_if, text, &offsets) != FTX_MESSAGE_RC_OK)
            continue;

        float freq_hz = ((float)mon.min_bin
                         + (float)cand->freq_offset
                         + (float)cand->freq_sub / (float)mon.wf.freq_osr)
                        / mon.symbol_period;
        float dt_sec  = ((float)cand->time_offset
                         + (float)cand->time_sub / (float)mon.wf.time_osr)
                        * mon.symbol_period;
        float snr     = (float)cand->score * 0.5f;

        char safe[FTX_MAX_MESSAGE_LENGTH * 2 + 2];
        int si = 0;
        for (int j = 0; text[j] && si < (int)(sizeof(safe) - 2); j++) {
            if (text[j] == '"') safe[si++] = '\\';
            safe[si++] = text[j];
        }
        safe[si] = '\0';

        char json[160];
        snprintf(json, sizeof(json),
                 "{\"freq\":%.1f,\"snr\":%.1f,\"dt\":%.2f,\"msg\":\"%s\"}",
                 freq_hz, snr, dt_sec, safe);
        json_arr[n_out++] = strdup(json);
    }
    monitor_free(&mon);

    r.messages = json_arr;
    r.count    = n_out;
    return r;
}

void ft8lib_free_decode_result(Ft8DecodeResult* r) {
    if (!r) return;
    for (int i = 0; i < r->count; i++) free(r->messages[i]);
    free(r->messages);
    r->messages = NULL;
    r->count    = 0;
}
