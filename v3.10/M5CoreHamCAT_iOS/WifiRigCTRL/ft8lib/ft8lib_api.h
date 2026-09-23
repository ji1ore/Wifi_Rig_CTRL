#ifndef FT8LIB_API_H
#define FT8LIB_API_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int16_t* samples;
    int      count;
} Ft8EncodeResult;

typedef struct {
    char** messages;
    int    count;
} Ft8DecodeResult;

// Encode FT8/FT4 message to S16LE PCM (caller must call ft8lib_free_encode_result).
// Set is_ft4=1 for FT4 (7.5-second slot), 0 for FT8 (15-second slot).
// Returns {NULL, 0} on error.
Ft8EncodeResult ft8lib_encode(const char* msg, float base_hz, int sample_rate, int is_ft4);
void            ft8lib_free_encode_result(Ft8EncodeResult* r);

// Decode one audio window to FT8/FT4 messages.
// Set is_ft4=1 for FT4 (pass 7.5s worth of samples), 0 for FT8 (15s).
// Each message is a JSON string: {"freq":1234.5,"snr":3.5,"dt":0.15,"msg":"CQ JA1XXX PM85"}
// Caller must call ft8lib_free_decode_result when done.
Ft8DecodeResult ft8lib_decode(const int16_t* samples, int n_samples, int sample_rate,
                               float freq_min, float freq_max,
                               const char* my_call, const char* dx_call,
                               int max_results, int is_ft4);
void            ft8lib_free_decode_result(Ft8DecodeResult* r);

#ifdef __cplusplus
}
#endif

#endif /* FT8LIB_API_H */
