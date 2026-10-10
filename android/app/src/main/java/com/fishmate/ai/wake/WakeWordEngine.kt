package com.fishmate.ai.wake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pluggable wake-word detection.
 *
 * Deliberately an interface with a no-op default. Two honest constraints:
 *
 * 1. **Android will not allow arbitrary background microphone access.** An app
 *    cannot sit silently listening forever; microphone use requires a
 *    foreground service with an active notification, and the platform may
 *    still cut it. So FishMate is built around an explicitly started Fishing
 *    Mode rather than pretending always-on listening is available.
 * 2. **A cloud connection must never be used just to spot a wake word.** That
 *    would stream audio (and cost) continuously for no benefit. A real
 *    implementation belongs on-device — Porcupine, or a small local model.
 *
 * Once Fishing Mode is running, no wake word is needed at all: server-side VAD
 * handles turn-taking, so the angler just talks. This interface exists for the
 * future case of waking the app *from idle* hands-free.
 */
interface WakeWordEngine {
    val isAvailable: Boolean

    /** Emits once per detection while collected. */
    fun detections(): Flow<WakeWordDetection>

    suspend fun start()

    suspend fun stop()
}

data class WakeWordDetection(
    val keyword: String,
    val confidence: Float,
    val atEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Default implementation: reports unavailable and emits nothing.
 *
 * Chosen over a fake that pretends to work, so the UI can honestly tell the
 * user that hands-free wake-up needs Fishing Mode to be started first.
 */
class NoOpWakeWordEngine : WakeWordEngine {
    override val isAvailable: Boolean = false

    override fun detections(): Flow<WakeWordDetection> = emptyFlow()

    override suspend fun start() = Unit

    override suspend fun stop() = Unit
}
