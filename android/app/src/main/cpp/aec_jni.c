/*
 * A thin wrapper over SpeexDSP's echo canceller.
 *
 * Thin on purpose. The decisions — how the far end is aligned to the
 * microphone, what counts as a barge-in afterwards — live in Kotlin where
 * they can be read and tested. What cannot be written in Kotlin is the
 * canceller itself: a hand-rolled NLMS reached 89 % cancellation against a
 * synthetic room and would not converge on a handset, where the path moves
 * under the filter. This is the same job done by a library that has been
 * cancelling echo in the field for twenty years.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

typedef struct {
    SpeexEchoState *echo;
    SpeexPreprocessState *preprocess;
    int frame_size;
} fishmate_aec_t;

JNIEXPORT jlong JNICALL
Java_com_fishmate_ai_audio_SpeexEchoCanceller_nativeCreate(
        JNIEnv *env, jclass clazz, jint frame_size, jint filter_length, jint rate) {
    (void) env;
    (void) clazz;
    fishmate_aec_t *state = calloc(1, sizeof(fishmate_aec_t));
    if (state == NULL) {
        return 0;
    }
    state->frame_size = frame_size;
    state->echo = speex_echo_state_init(frame_size, filter_length);
    if (state->echo == NULL) {
        free(state);
        return 0;
    }
    speex_echo_ctl(state->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);

    /* The preprocessor is given the echo state so its residual suppressor
       knows what was already removed. It also carries the noise suppression
       this app wants anyway: the angler is on a shore, in wind. */
    state->preprocess = speex_preprocess_state_init(frame_size, rate);
    if (state->preprocess != NULL) {
        int on = 1;
        int noise_suppress = -25;
        speex_preprocess_ctl(state->preprocess, SPEEX_PREPROCESS_SET_DENOISE, &on);
        speex_preprocess_ctl(
                state->preprocess, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &noise_suppress);
        speex_preprocess_ctl(
                state->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, state->echo);
    }
    return (jlong) (intptr_t) state;
}

JNIEXPORT void JNICALL
Java_com_fishmate_ai_audio_SpeexEchoCanceller_nativeProcess(
        JNIEnv *env, jclass clazz, jlong handle,
        jshortArray mic, jshortArray far, jshortArray out) {
    (void) clazz;
    fishmate_aec_t *state = (fishmate_aec_t *) (intptr_t) handle;
    if (state == NULL) {
        return;
    }
    jshort *mic_samples = (*env)->GetShortArrayElements(env, mic, NULL);
    jshort *far_samples = (*env)->GetShortArrayElements(env, far, NULL);
    jshort *out_samples = (*env)->GetShortArrayElements(env, out, NULL);
    if (mic_samples != NULL && far_samples != NULL && out_samples != NULL) {
        speex_echo_cancellation(state->echo, mic_samples, far_samples, out_samples);
        if (state->preprocess != NULL) {
            speex_preprocess_run(state->preprocess, out_samples);
        }
    }
    if (mic_samples != NULL) (*env)->ReleaseShortArrayElements(env, mic, mic_samples, JNI_ABORT);
    if (far_samples != NULL) (*env)->ReleaseShortArrayElements(env, far, far_samples, JNI_ABORT);
    if (out_samples != NULL) (*env)->ReleaseShortArrayElements(env, out, out_samples, 0);
}

/*
 * Where the room's response peaks, in samples.
 *
 * The filter's own answer to the question the alignment is guessing at: tap k
 * means "this much of the far end, k samples ago", so the largest tap is the
 * delay between the reference we hand it and the echo in the microphone. A
 * peak at the far end of the filter means we are feeding the reference too
 * late and most of the filter is being spent on pure delay instead of on the
 * room; a peak at zero means we are feeding it too early and the start of the
 * response has been cut off.
 */
JNIEXPORT jint JNICALL
Java_com_fishmate_ai_audio_SpeexEchoCanceller_nativePeakTap(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    fishmate_aec_t *state = (fishmate_aec_t *) (intptr_t) handle;
    if (state == NULL || state->echo == NULL) {
        return -1;
    }
    spx_int32_t size = 0;
    if (speex_echo_ctl(state->echo, SPEEX_ECHO_GET_IMPULSE_RESPONSE_SIZE, &size) != 0 ||
        size <= 0) {
        return -1;
    }
    spx_int32_t *response = calloc((size_t) size, sizeof(spx_int32_t));
    if (response == NULL) {
        return -1;
    }
    jint peak = -1;
    if (speex_echo_ctl(state->echo, SPEEX_ECHO_GET_IMPULSE_RESPONSE, response) == 0) {
        spx_int32_t best = 0;
        for (spx_int32_t i = 0; i < size; i++) {
            spx_int32_t magnitude = response[i] < 0 ? -response[i] : response[i];
            if (magnitude > best) {
                best = magnitude;
                peak = i;
            }
        }
    }
    free(response);
    return peak;
}

JNIEXPORT void JNICALL
Java_com_fishmate_ai_audio_SpeexEchoCanceller_nativeReset(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    fishmate_aec_t *state = (fishmate_aec_t *) (intptr_t) handle;
    if (state != NULL && state->echo != NULL) {
        speex_echo_state_reset(state->echo);
    }
}

JNIEXPORT void JNICALL
Java_com_fishmate_ai_audio_SpeexEchoCanceller_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    fishmate_aec_t *state = (fishmate_aec_t *) (intptr_t) handle;
    if (state == NULL) {
        return;
    }
    if (state->preprocess != NULL) {
        speex_preprocess_state_destroy(state->preprocess);
    }
    if (state->echo != NULL) {
        speex_echo_state_destroy(state->echo);
    }
    free(state);
}
