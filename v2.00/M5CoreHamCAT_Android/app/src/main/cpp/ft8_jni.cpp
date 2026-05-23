#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <string>

// ft8_lib headers (kgoba/ft8_lib)
#include <ft8/decode.h>
#include <ft8/encode.h>
#include <ft8/message.h>
#include <ft8/constants.h>
#include <common/monitor.h>

#define LOG_TAG "ft8rx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int   kMaxCands  = 200;
static const int   kLdpcIter  = 40;
static const int   kMinScore  = 10;

// Minimal no-op callsign hash interface.
// Type 4 messages (non-standard calls like <WA9XYZ> PJ4/KA1ABC RR73) require
// hash lookup; without this, ftx_message_decode crashes on null hash_if->lookup_hash.
static bool noop_lookup_hash(ftx_callsign_hash_type_t, uint32_t, char* callsign) {
    if (callsign) callsign[0] = '\0';
    return false;   // not found — callsign will show as empty/hash
}
static void noop_save_hash(const char*, uint32_t) {}
static ftx_callsign_hash_interface_t kHashIf = {
    noop_lookup_hash,
    noop_save_hash,
};

static std::string jsonEscape(const char* s) {
    std::string out;
    for (const char* p = s; p && *p; ++p) {
        switch (*p) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            default:   out += *p;     break;
        }
    }
    return out;
}

// ────────────────────────────────────────────────────────────────
// nativeDecode(samples: FloatArray, sampleRate: Int, isFt4: Boolean): String
// Returns JSON: [{snr:float, dt:float, hz:float, msg:string}, ...]
// ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_ji1ore_wifi_1rig_1ctrl_Ft8Decoder_nativeDecode(
    JNIEnv* env, jclass,
    jfloatArray jSamples, jint jSampleRate, jboolean jIsFt4)
{
    bool isFt4 = (jIsFt4 == JNI_TRUE);
    jsize nSamples = env->GetArrayLength(jSamples);
    float* pcm     = env->GetFloatArrayElements(jSamples, nullptr);

    if (pcm == nullptr || nSamples <= 0) {
        if (pcm) env->ReleaseFloatArrayElements(jSamples, pcm, JNI_ABORT);
        LOGE("nativeDecode: null pcm or empty array");
        return env->NewStringUTF("[]");
    }

    // Configure monitor
    monitor_config_t cfg = {};
    cfg.sample_rate = (int)jSampleRate;
    cfg.f_min       = 200.0f;
    cfg.f_max       = 3200.0f;
    cfg.time_osr    = 2;
    cfg.freq_osr    = 2;
    cfg.protocol    = isFt4 ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8;

    monitor_t mon = {};
    monitor_init(&mon, &cfg);

    // Guard against allocation failure in monitor_init
    if (mon.block_size <= 0 || mon.window == nullptr ||
        mon.last_frame == nullptr || mon.fft_cfg == nullptr || mon.wf.mag == nullptr) {
        LOGE("monitor_init failed: block_size=%d window=%p last_frame=%p fft_cfg=%p mag=%p",
             mon.block_size, (void*)mon.window, (void*)mon.last_frame,
             (void*)mon.fft_cfg, (void*)mon.wf.mag);
        env->ReleaseFloatArrayElements(jSamples, pcm, JNI_ABORT);
        monitor_free(&mon);
        return env->NewStringUTF("[]");
    }

    int blockSize = mon.block_size;
    LOGI("monitor_init: sampleRate=%d  protocol=%s  blockSize=%d  nSamples=%d",
         cfg.sample_rate, isFt4 ? "FT4" : "FT8", blockSize, (int)nSamples);

    // Feed audio one block at a time
    for (int off = 0; off + blockSize <= nSamples; off += blockSize) {
        monitor_process(&mon, pcm + off);
    }

    env->ReleaseFloatArrayElements(jSamples, pcm, JNI_ABORT);

    // Find sync candidates
    ftx_candidate_t cands[kMaxCands];
    int nCands = ftx_find_candidates(&mon.wf, kMaxCands, cands, kMinScore);
    LOGI("ftx_find_candidates: %d candidates", nCands);

    // Decode each candidate
    std::string json = "[";
    bool first = true;

    for (int i = 0; i < nCands; i++) {
        ftx_message_t   msg    = {};
        ftx_decode_status_t st = {};

        bool ok = ftx_decode_candidate(&mon.wf, &cands[i], kLdpcIter, &msg, &st);
        if (!ok) continue;
        if (st.ldpc_errors > 0) continue;    // skip undecodable

        // Decode payload to text
        char text[FTX_MAX_MESSAGE_LENGTH + 1] = {};
        ftx_message_offsets_t offsets = {};
        ftx_message_decode(&msg, &kHashIf, text, &offsets);

        // Rough SNR from candidate score (empirical, ± a few dB)
        float snrDb = (float)cands[i].score * 0.5f - 30.0f;

        // st.freq and st.time are not populated by ftx_decode_candidate — compute from candidate
        float freq_hz = ((float)mon.min_bin + cands[i].freq_offset
                         + (float)cands[i].freq_sub / cfg.freq_osr)
                        * (float)cfg.sample_rate / mon.nfft;
        float dt_sec  = (float)(cands[i].time_offset * cfg.time_osr + cands[i].time_sub)
                        * mon.subblock_size / (float)cfg.sample_rate;

        char entry[400];
        snprintf(entry, sizeof(entry),
                 "%s{\"snr\":%.1f,\"dt\":%.2f,\"hz\":%.0f,\"msg\":\"%s\"}",
                 first ? "" : ",",
                 (double)snrDb,
                 (double)dt_sec,
                 (double)freq_hz,
                 jsonEscape(text).c_str());
        json += entry;
        first = false;
    }
    json += "]";

    monitor_free(&mon);
    return env->NewStringUTF(json.c_str());
}

// ────────────────────────────────────────────────────────────────
// nativeEncode(message, isFt4, sampleRate, baseFreqHz): FloatArray
// Returns float PCM samples [-1..1] for the FT8/FT4 signal.
// Continuous-phase FSK; sample length: FT8_NN*samplesPerSym or FT4_NN*samplesPerSym
// ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ji1ore_wifi_1rig_1ctrl_Ft8Decoder_nativeEncode(
    JNIEnv* env, jclass,
    jstring jMessage, jboolean jIsFt4, jint jSampleRate, jfloat jBaseFreqHz)
{
    bool isFt4 = (jIsFt4 == JNI_TRUE);
    const char* msgCStr = env->GetStringUTFChars(jMessage, nullptr);
    if (!msgCStr) return env->NewFloatArray(0);
    std::string msgStr(msgCStr);
    env->ReleaseStringUTFChars(jMessage, msgCStr);

    // Encode message text → payload bits
    ftx_message_t msg = {};
    ftx_message_rc_t rc = ftx_message_encode(&msg, &kHashIf, msgStr.c_str());
    if (rc != FTX_MESSAGE_RC_OK) {
        LOGE("nativeEncode: ftx_message_encode failed rc=%d for '%s'", rc, msgStr.c_str());
        return env->NewFloatArray(0);
    }

    // Generate tone sequence (includes sync/Costas patterns)
    int nTones = isFt4 ? FT4_NN : FT8_NN;
    float symPeriod = isFt4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    float toneSpacingHz = 1.0f / symPeriod;
    uint8_t tones[FT4_NN > FT8_NN ? FT4_NN : FT8_NN];
    if (isFt4) ft4_encode(msg.payload, tones);
    else        ft8_encode(msg.payload, tones);

    // Generate continuous-phase FSK audio
    int sr = (int)jSampleRate;
    float baseHz = (float)jBaseFreqHz;
    int sampPerSym = (int)(symPeriod * (float)sr + 0.5f);
    int nSamples = nTones * sampPerSym;

    jfloatArray result = env->NewFloatArray(nSamples);
    if (!result) return env->NewFloatArray(0);
    float* out = env->GetFloatArrayElements(result, nullptr);
    if (!out) return result;

    float phase = 0.0f;
    const float twoPi = 2.0f * 3.14159265358979323846f;
    for (int i = 0; i < nTones; ++i) {
        float freq = baseHz + (float)tones[i] * toneSpacingHz;
        float dphase = twoPi * freq / (float)sr;
        int base = i * sampPerSym;
        for (int j = 0; j < sampPerSym; ++j) {
            out[base + j] = sinf(phase);
            phase += dphase;
            if (phase >= twoPi) phase -= twoPi;
        }
    }

    env->ReleaseFloatArrayElements(result, out, 0);
    LOGI("nativeEncode: '%s' → ft4=%d sr=%d base=%.0fHz tones=%d samples=%d",
         msgStr.c_str(), isFt4, sr, (double)baseHz, nTones, nSamples);
    return result;
}

// ────────────────────────────────────────────────────────────────
// nativeVersion(): String
// ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_ji1ore_wifi_1rig_1ctrl_Ft8Decoder_nativeVersion(JNIEnv* env, jclass)
{
    return env->NewStringUTF("ft8rx JNI v1.1 / kgoba ft8_lib");
}
