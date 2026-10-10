package com.fishmate.ai.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the assistant can see.
 *
 * The Gemini layer depends on this interface, never on CameraX, so the same
 * session code can later be fed by smart glasses, an action camera or a
 * wearable without touching the AI pipeline (docs/ARCHITECTURE.md ->
 * "Camera abstraction").
 *
 * Frames are **JPEG stills**, not a video stream. That is the deliberate
 * design: the Live API takes realtime video as periodic frames, and sending a
 * throttled low-resolution still costs a fraction of the tokens, battery and
 * mobile data of continuous full-resolution video — which matters when the
 * angler is out for three hours on a phone battery.
 */
interface CameraSource {

    val state: StateFlow<CameraState>

    /** Which physical camera is active. */
    val facing: StateFlow<CameraFacing>

    /** True when this source can switch between front and back. */
    val supportsSwitching: Boolean

    /**
     * Throttled JPEG frames while collected.
     *
     * @param config resolution, quality and frame rate; see [FrameConfig]
     */
    fun frames(config: FrameConfig = FrameConfig()): Flow<CameraFrame>

    /**
     * Grab a single frame on demand.
     *
     * Used when the angler says "look at this" — a one-off capture is far
     * cheaper than turning on a stream.
     */
    suspend fun captureStill(config: FrameConfig = FrameConfig()): CameraFrame?

    suspend fun switchCamera()

    fun release()
}

class CameraFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Frame budget.
 *
 * Defaults are chosen for cost, not fidelity: 0.5 fps and 768 px is enough for
 * the model to identify a lure or a fish, and keeps a long session affordable
 * (docs/GEMINI_LIVE.md -> "Cost control"). The user can raise the rate in
 * Settings if they want a more responsive "watching" feel.
 */
data class FrameConfig(
    val framesPerSecond: Double = 0.5,
    val maxDimensionPx: Int = 768,
    val jpegQuality: Int = 70,
) {
    val intervalMillis: Long
        get() = if (framesPerSecond <= 0) Long.MAX_VALUE
        else (1000.0 / framesPerSecond).toLong()

    init {
        require(jpegQuality in 1..100) { "jpegQuality must be 1..100" }
        require(maxDimensionPx >= 128) { "maxDimensionPx must be at least 128" }
    }
}

sealed interface CameraState {
    data object Idle : CameraState
    data object Starting : CameraState
    data object Streaming : CameraState
    data class Error(val message: String) : CameraState
}

enum class CameraFacing { BACK, FRONT }
