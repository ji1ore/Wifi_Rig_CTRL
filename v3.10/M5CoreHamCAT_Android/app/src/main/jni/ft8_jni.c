#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdbool.h>
#include <android/log.h>

#include "ft8/message.h"
#include "ft8/encode.h"
#include "ft8/decode.h"
#include "ft8/constants.h"
#include "common/monitor.h"

#define TAG "Ft8Jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Callsign hash table ────────────────────────────────────────────────────
// Enables correct decoding of non-standard callsigns (hash-based lookup).

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
            return; // already present
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

// ── GFSK synthesis (from gen_ft8.c demo) ──────────────────────────────────

#define GFSK_CONST_K 5.336446f
#define FT8_SYMBOL_BT 2.0f

static void gfsk_pulse(int n_spsym, float bt, float* pulse) {
    for (int i = 0; i < 3 * n_spsym; i++) {
        float t    = i / (float)n_spsym - 1.5f;
        pulse[i]   = (erff(GFSK_CONST_K * bt * (t + 0.5f)) -
                      erff(GFSK_CONST_K * bt * (t - 0.5f))) / 2.0f;
    }
}

// Returns heap-allocated float signal (caller must free). Sets *out_len.
static float* synth_gfsk(const uint8_t* tones, int n_sym,
                          float f0, float bt, float sym_period,
                          int rate, int* out_len) {
    int n_spsym = (int)(0.5f + rate * sym_period);
    int n_wave  = n_sym * n_spsym;
    *out_len    = n_wave;

    float* dphi   = (float*)calloc(n_wave + 2 * n_spsym, sizeof(float));
    float* signal = (float*)malloc(n_wave * sizeof(float));
    if (!dphi || !signal) { free(dphi); free(signal); *out_len = 0; return NULL; }

    float dphi_base = 2.0f * (float)M_PI * f0 / (float)rate;
    float dphi_peak = 2.0f * (float)M_PI / (float)n_spsym;
    for (int i = 0; i < n_wave + 2 * n_spsym; i++) dphi[i] = dphi_base;

    float* pulse = (float*)malloc(3 * n_spsym * sizeof(float));
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
    // Ramp envelope on first/last symbol to avoid clicks
    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; i++) {
        float env = (1.0f - cosf(2.0f * (float)M_PI * (float)i / (2 * n_ramp))) / 2.0f;
        signal[i]           *= env;
        signal[n_wave-1-i]  *= env;
    }
    free(dphi);
    return signal;
}

#define FT4_SYMBOL_BT 1.0f

// ─────────────────────────────────────────────────────────────────────────────
// JNI: nativeEncode
// Returns S16LE PCM for one FT8 (15s) or FT4 (7.5s) slot.
// isFt4=1 → FT4 mode, 0 → FT8 mode.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jshortArray JNICALL
Java_com_ji1ore_wifi_1rig_1ctrl_data_Ft8Jni_nativeEncode(
        JNIEnv* env, jclass clazz,
        jstring jmsg, jfloat baseHz, jint sampleRate, jint isFt4)
{
    const char* msg_text = (*env)->GetStringUTFChars(env, jmsg, NULL);
    if (!msg_text) return NULL;

    ftx_message_t msg;
    ftx_message_rc_t rc = ftx_message_encode(&msg, &g_hash_if, msg_text);
    (*env)->ReleaseStringUTFChars(env, jmsg, msg_text);

    if (rc != FTX_MESSAGE_RC_OK) {
        LOGE("ftx_message_encode failed rc=%d", (int)rc);
        return NULL;
    }

    int sig_len = 0;
    float* signal;
    int total;
    if (isFt4) {
        uint8_t tones[FT4_NN];
        ft4_encode(msg.payload, tones);
        signal = synth_gfsk(tones, FT4_NN, baseHz, FT4_SYMBOL_BT,
                            FT4_SYMBOL_PERIOD, sampleRate, &sig_len);
        total  = (int)(FT4_SLOT_TIME * (float)sampleRate);
    } else {
        uint8_t tones[FT8_NN];
        ft8_encode(msg.payload, tones);
        signal = synth_gfsk(tones, FT8_NN, baseHz, FT8_SYMBOL_BT,
                            FT8_SYMBOL_PERIOD, sampleRate, &sig_len);
        total  = (int)(FT8_SLOT_TIME * (float)sampleRate);
    }
    if (!signal) return NULL;

    // FT4: place signal at slot start (silence=0) so dt≈0.
    // ftx_find_candidates only searches time_offset=-10..19 (max dt=0.912s at 8kHz/FT4),
    // so the centered dt=1.23s is out of range and cannot be decoded.
    // FT8: keep centering — FT8 dt=1.18s maps to block 7, within range.
    int silence = isFt4 ? 0 : (total - sig_len) / 2;

    jshortArray out = (*env)->NewShortArray(env, total);
    if (!out) { free(signal); return NULL; }

    jshort* buf = (*env)->GetShortArrayElements(env, out, NULL);
    memset(buf, 0, (size_t)total * sizeof(jshort));
    for (int i = 0; i < sig_len && (silence + i) < total; i++) {
        float s = signal[i];
        if (s >  1.0f) s =  1.0f;
        if (s < -1.0f) s = -1.0f;
        buf[silence + i] = (jshort)(s * 32767.0f);
    }
    (*env)->ReleaseShortArrayElements(env, out, buf, 0);
    free(signal);

    LOGI("encode ok: %d samples (%.2fs)", total, (float)total / (float)sampleRate);
    return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: nativeDecode
// Processes one 15-second window of audio, returns JSON strings per message.
// Each JSON: {"freq":1234.5,"snr":3.5,"dt":0.15,"msg":"CQ JF9KKE QN02"}
// ─────────────────────────────────────────────────────────────────────────────
#define MAX_CANDIDATES  140
#define MAX_DECODED      50
#define MIN_SCORE        10
#define LDPC_ITERATIONS  25

JNIEXPORT jobjectArray JNICALL
Java_com_ji1ore_wifi_1rig_1ctrl_data_Ft8Jni_nativeDecode(
        JNIEnv* env, jclass clazz,
        jshortArray jsamples, jint sampleRate,
        jfloat freqMin, jfloat freqMax,
        jstring jMyCall, jstring jDxCall,
        jint maxResults, jint isFt4)
{
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray empty = (*env)->NewObjectArray(env, 0, str_cls, NULL);

    // Pre-seed hash table with myCall/dxCall for better compound-call decode
    const char* my_call = (*env)->GetStringUTFChars(env, jMyCall, NULL);
    const char* dx_call = (*env)->GetStringUTFChars(env, jDxCall, NULL);
    hashtable_reset();
    // (ft8lib will call save_hash as it decodes messages; pre-seeding is optional)
    (*env)->ReleaseStringUTFChars(env, jMyCall, my_call);
    (*env)->ReleaseStringUTFChars(env, jDxCall, dx_call);

    // Convert S16 → float
    jsize n_samp = (*env)->GetArrayLength(env, jsamples);
    jshort* s16 = (*env)->GetShortArrayElements(env, jsamples, NULL);
    if (!s16) return empty;

    float* samples = (float*)malloc((size_t)n_samp * sizeof(float));
    if (!samples) {
        (*env)->ReleaseShortArrayElements(env, jsamples, s16, JNI_ABORT);
        return empty;
    }
    for (jsize i = 0; i < n_samp; i++)
        samples[i] = (float)s16[i] / 32768.0f;
    (*env)->ReleaseShortArrayElements(env, jsamples, s16, JNI_ABORT);

    // Build waterfall with monitor
    monitor_config_t cfg = {
        .f_min       = freqMin,
        .f_max       = freqMax,
        .sample_rate = (int)sampleRate,
        .time_osr    = 2,
        .freq_osr    = 2,
        .protocol    = isFt4 ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8,
    };
    monitor_t mon;
    monitor_init(&mon, &cfg);

    for (int pos = 0; pos + mon.block_size <= (int)n_samp; pos += mon.block_size)
        monitor_process(&mon, samples + pos);
    free(samples);

    // Find sync candidates
    ftx_candidate_t cands[MAX_CANDIDATES];
    int n_cands = ftx_find_candidates(&mon.wf, MAX_CANDIDATES, cands, MIN_SCORE);
    LOGI("decode: %d candidates", n_cands);

    // Deduplicate decoded messages
    ftx_message_t decoded[MAX_DECODED];
    ftx_message_t* dedup_ht[MAX_DECODED];
    memset(dedup_ht, 0, sizeof(dedup_ht));

    int cap = (maxResults > 0 && maxResults < MAX_DECODED) ? (int)maxResults : MAX_DECODED;
    char** json_arr = (char**)calloc((size_t)cap, sizeof(char*));
    int n_out = 0;

    for (int i = 0; i < n_cands && n_out < cap; i++) {
        const ftx_candidate_t* cand = &cands[i];
        ftx_message_t message;
        ftx_decode_status_t status;
        if (!ftx_decode_candidate(&mon.wf, cand, LDPC_ITERATIONS, &message, &status))
            continue;

        // Deduplicate by payload hash
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
                is_dup = true;
                break;
            }
        }
        if (is_dup) continue;

        // Unpack to text
        char text[FTX_MAX_MESSAGE_LENGTH + 1];
        ftx_message_offsets_t offsets;
        if (ftx_message_decode(&message, &g_hash_if, text, &offsets) != FTX_MESSAGE_RC_OK)
            continue;

        // Compute frequency and time
        float freq_hz = ((float)mon.min_bin
                         + (float)cand->freq_offset
                         + (float)cand->freq_sub / (float)mon.wf.freq_osr)
                        / mon.symbol_period;
        float dt_sec  = ((float)cand->time_offset
                         + (float)cand->time_sub / (float)mon.wf.time_osr)
                        * mon.symbol_period;
        float snr     = (float)cand->score * 0.5f;

        // Escape quotes in message text
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

    // Build Java String[]
    jobjectArray result = (*env)->NewObjectArray(env, n_out, str_cls, NULL);
    for (int i = 0; i < n_out; i++) {
        jstring js = (*env)->NewStringUTF(env, json_arr[i]);
        (*env)->SetObjectArrayElement(env, result, i, js);
        (*env)->DeleteLocalRef(env, js);
        free(json_arr[i]);
    }
    free(json_arr);

    LOGI("decode done: %d messages", n_out);
    return result;
}
