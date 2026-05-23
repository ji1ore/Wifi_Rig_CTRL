cmake_minimum_required(VERSION 3.22.1)
project("ft8rx")

set(FT8_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib)

# Explicit source list (ft8_lib by kgoba - https://github.com/kgoba/ft8_lib)
set(FT8_SOURCES
    ${FT8_DIR}/ft8/constants.c
    ${FT8_DIR}/ft8/crc.c
    ${FT8_DIR}/ft8/decode.c
    ${FT8_DIR}/ft8/encode.c
    ${FT8_DIR}/ft8/ldpc.c
    ${FT8_DIR}/ft8/message.c
    ${FT8_DIR}/ft8/text.c
    ${FT8_DIR}/common/monitor.c
    ${FT8_DIR}/fft/kiss_fft.c
    ${FT8_DIR}/fft/kiss_fftr.c
)

add_library(ft8rx SHARED
    ft8_jni.cpp
    ${FT8_SOURCES}
)

target_include_directories(ft8rx PRIVATE
    ${FT8_DIR}
    ${FT8_DIR}/ft8
    ${FT8_DIR}/common
    ${FT8_DIR}/fft
)

target_compile_options(ft8rx PRIVATE -O2 -ffast-math)
target_link_libraries(ft8rx log)
