cmake_minimum_required(VERSION 3.22.1)
project(fishmate_aec C)

# SpeexDSP, vendored under speexdsp/ from the 1.2.1 release tarball.
#
# Only the echo canceller and the preprocessor are built: the resampler,
# jitter buffer and the rest of the library are not used here and would be
# dead weight in the APK.
add_library(fishmate_aec SHARED
    aec_jni.c
    speexdsp/mdf.c
    speexdsp/preprocess.c
    speexdsp/filterbank.c
    speexdsp/fftwrap.c
    speexdsp/kiss_fft.c
    speexdsp/kiss_fftr.c
    speexdsp/smallft.c
)

target_include_directories(fishmate_aec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp
    ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp/include
)

target_compile_definitions(fishmate_aec PRIVATE HAVE_CONFIG_H)

# Speex's own sources are not warning-clean under the NDK's clang and are not
# ours to change: keeping them quiet makes our warnings visible.
target_compile_options(fishmate_aec PRIVATE -O2 -ffast-math -w)

find_library(log-lib log)
target_link_libraries(fishmate_aec ${log-lib})
