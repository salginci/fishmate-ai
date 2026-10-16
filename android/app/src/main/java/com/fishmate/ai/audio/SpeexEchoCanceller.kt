package com.fishmate.ai.audio

import com.fishmate.ai.core.Log2

/**
 * SpeexDSP's echo canceller, over JNI.
 *
 * ## Why a library and not our own
 *
 * The thing being cancelled is not loud, it is *known*: the microphone during
 * an echo carries a delayed, filtered copy of the signal we handed the
 * speaker, and we are holding the original. A hand-rolled adaptive filter was
 * written first and it works — [com.fishmate.ai.EchoCancellerTest] pins it at
 * 89 % cancellation against a synthetic room, a voice over the echo left
 * intact, and wind untouched. On a Galaxy A35 it would not converge: the
 * platform effects reshape the signal by a varying amount, the playback head
 * it aligned against jitters by tens of milliseconds, and a linear filter
 * cannot learn a room that moves underneath it. Making that robust is a
 * project in itself, which is why every voice product uses a canceller
 * somebody else has already spent years on.
 *
 * ## What this does and does not own
 *
 * It owns the subtraction. Alignment, the far-end ring and the decision about
 * what the residual means stay in Kotlin, where they can be read and tested.
 * The filter is [FILTER_MS] long, which is far longer than the 130-200 ms the
 * device was measured at, so the remaining delay is absorbed by the filter
 * rather than estimated — the estimate was the least reliable part of the
 * attempt this replaces.
 *
 * The preprocessor attached on the native side carries noise suppression as
 * well, which this app wants for its own sake: the angler is on a shore, in
 * wind.
 */
class SpeexEchoCanceller private constructor(
    private var handle: Long,
    val frameSamples: Int,
) {
    private val output = ShortArray(frameSamples)

    /**
     * Subtract the echo of [far] from [mic], both [frameSamples] long.
     * Returns the residual, valid until the next call.
     */
    fun process(mic: ShortArray, far: ShortArray): ShortArray {
        if (handle == 0L) return mic
        nativeProcess(handle, mic, far, output)
        return output
    }

    /**
     * Forget the room.
     *
     * Called when playback is flushed, because the playback head restarts at
     * zero there: every weight in the filter describes an alignment that no
     * longer exists, and carrying them into the next sentence is worse than
     * starting cold.
     */
    /**
     * The filter's own view of the delay, in samples, or -1 before it has
     * one. Diagnostic: it says whether the far end is being fed early enough.
     */
    fun peakTap(): Int = if (handle == 0L) -1 else nativePeakTap(handle)

    fun reset() {
        if (handle == 0L) return
        nativeReset(handle)
    }

    fun release() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        private const val TAG = "SpeexAec"

        /** How long a room the filter can model. The device measured 130-200 ms. */
        const val FILTER_MS = 300

        private val available: Boolean by lazy {
            runCatching { System.loadLibrary("fishmate_aec") }
                .onFailure { Log2.w(TAG, "native canceller unavailable: ${it.message}") }
                .isSuccess
        }

        /**
         * Returns null when the native library is missing — which is the case
         * in JVM unit tests, and must not be a crash there or on a device
         * whose ABI we did not ship.
         */
        fun create(frameSamples: Int, rateHz: Int): SpeexEchoCanceller? {
            if (!available) return null
            val filterSamples = rateHz * FILTER_MS / 1000
            val handle = runCatching { nativeCreate(frameSamples, filterSamples, rateHz) }
                .getOrDefault(0L)
            if (handle == 0L) {
                Log2.w(TAG, "native canceller refused to start")
                return null
            }
            Log2.i(TAG, "canceller ready frame=$frameSamples filter=${FILTER_MS}ms rate=$rateHz")
            return SpeexEchoCanceller(handle, frameSamples)
        }

        @JvmStatic
        private external fun nativeCreate(frameSize: Int, filterLength: Int, rate: Int): Long

        @JvmStatic
        private external fun nativeProcess(
            handle: Long,
            mic: ShortArray,
            far: ShortArray,
            out: ShortArray,
        )

        @JvmStatic
        private external fun nativePeakTap(handle: Long): Int

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
