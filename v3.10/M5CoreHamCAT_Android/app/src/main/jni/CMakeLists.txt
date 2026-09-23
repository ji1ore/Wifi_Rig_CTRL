cmake_minimum_required(VERSION 3.22.1)
project(ft8jni)

set(FT8LIB_DIR ${CMAKE_SOURCE_DIR}/ft8_lib)

# ft8 core (encode + decode)
set(FT8_SOURCES
    ${FT8LIB_DIR}/ft8/constants.c
    ${FT8LIB_DIR}/ft8/crc.c
    ${FT8LIB_DIR}/ft8/ldpc.c
    ${FT8LIB_DIR}/ft8/message.c
    ${FT8LIB_DIR}/ft8/encode.c
    ${FT8LIB_DIR}/ft8/decode.c
    ${FT8LIB_DIR}/ft8/text.c
)

# Waterfall builder (uses kiss_fft, no ALSA dependency)
set(MONITOR_SOURCES
    ${FT8LIB_DIR}/common/monitor.c
)

# FFT
set(FFT_SOURCES
    ${FT8LIB_DIR}/fft/kiss_fft.c
    ${FT8LIB_DIR}/fft/kiss_fftr.c
)

add_library(ft8jni SHARED
    ft8_jni.c
    ${FT8_SOURCES}
    ${MONITOR_SOURCES}
    ${FFT_SOURCES}
)

# Include from ft8_lib root so that <ft8/decode.h>, <fft/kiss_fft.h>,
# <common/monitor.h> style includes all resolve correctly.
target_include_directories(ft8jni PRIVATE
    ${FT8LIB_DIR}
)

target_compile_options(ft8jni PRIVATE -O2 -ffast-math)

find_library(log-lib log)
target_link_libraries(ft8jni ${log-lib} m)
