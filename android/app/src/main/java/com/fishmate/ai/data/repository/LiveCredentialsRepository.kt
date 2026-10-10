package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.LiveSessionRequest
import com.fishmate.ai.live.LiveCredentials
import kotlinx.serialization.json.JsonObject

/**
 * Obtains short-lived Gemini Live credentials from our backend.
 *
 * Nothing is cached to disk. Tokens are valid for minutes and are the only
 * thing standing between a decompiled APK and our Gemini quota, so they live
 * in memory for one session and are re-minted on demand (docs/SECURITY.md).
 */
class LiveCredentialsRepository(
    private val api: FishMateApi,
) {
    /** Last issued credentials, kept only to decide whether a re-mint is due. */
    @Volatile
    private var cached: LiveCredentials? = null

    /** Primed memory from the backend, shown in the UI as "what I remember". */
    @Volatile
    var lastUserBrief: JsonObject? = null
        private set

    suspend fun issue(
        language: String?,
        voiceName: String? = null,
        expectsVideo: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
    ): AppResult<LiveCredentials> {
        val result = apiCall(TAG) {
            api.createLiveSession(
                LiveSessionRequest(language, voiceName, expectsVideo, latitude, longitude),
            )
        }
        return when (result) {
            is AppResult.Ok -> {
                val dto = result.value
                val credentials = LiveCredentials(
                    token = dto.token,
                    wsUrl = dto.wsUrl,
                    model = dto.model,
                    setup = dto.setup,
                    inputSampleRateHz = dto.inputSampleRateHz,
                    outputSampleRateHz = dto.outputSampleRateHz,
                    inputAudioMime = dto.inputAudioMime,
                    expiresAtEpochMs = parseInstantMillis(
                        dto.expiresAt,
                        System.currentTimeMillis() + DEFAULT_TTL_MS,
                    ),
                    newSessionExpiresAtEpochMs = parseInstantMillis(
                        dto.newSessionExpiresAt,
                        System.currentTimeMillis() + DEFAULT_NEW_SESSION_MS,
                    ),
                )
                cached = credentials
                lastUserBrief = dto.userBrief
                Log2.i(
                    TAG,
                    "live credentials issued model=${dto.model} " +
                        "validForMs=${credentials.expiresAtEpochMs - System.currentTimeMillis()}",
                )
                AppResult.Ok(credentials)
            }
            is AppResult.Err -> {
                Log2.w(TAG, "live credentials unavailable: ${result.error.code}")
                result
            }
        }
    }

    /**
     * Credentials usable right now, re-minting when they are close to expiry.
     *
     * The margin matters: a token that expires mid-reconnect strands the
     * session, and minting is cheap.
     */
    suspend fun current(
        language: String?,
        voiceName: String? = null,
        expectsVideo: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
    ): AppResult<LiveCredentials> {
        val existing = cached
        val now = System.currentTimeMillis()
        // Reusing a cached token is only safe while it can still *open* a
        // socket. `newSessionExpireTime` is ~60 s — far shorter than the ~30 min
        // `expireTime` — so a token that is merely "not expired" is usually
        // already useless for a reconnect.
        val canOpen = existing != null && existing.canStartNewSession(now + OPEN_MARGIN_MS)
        if (existing != null && canOpen && now < existing.expiresAtEpochMs - RENEW_MARGIN_MS) {
            return AppResult.Ok(existing)
        }
        return issue(language, voiceName, expectsVideo, latitude, longitude)
    }

    fun invalidate() {
        cached = null
    }

    companion object {
        private const val TAG = "LiveCredentials"
        private const val DEFAULT_TTL_MS = 30 * 60 * 1000L
        private const val DEFAULT_NEW_SESSION_MS = 60 * 1000L

        /** Re-mint when under two minutes remain on the send window. */
        private const val RENEW_MARGIN_MS = 2 * 60 * 1000L

        /**
         * Safety margin on the much shorter "may open a new session" window,
         * so a token is not handed out microseconds before it stops working.
         */
        private const val OPEN_MARGIN_MS = 5 * 1000L
    }
}
