package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.TranslateRequest

/**
 * Translates finalised transcript lines for the on-screen subtitle.
 *
 * The cache is the point: a reconnect replays recent transcript, and the
 * assistant repeats stock phrases ("Let me check the conditions") many times
 * in a session. Without it the subtitle track would cost a request per repeat.
 */
class TranslationRepository(
    private val api: FishMateApi,
) {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_CACHED
    }

    suspend fun translate(text: String, targetLanguage: String): AppResult<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return AppResult.Ok("")

        val key = "$targetLanguage:$trimmed"
        synchronized(cache) { cache[key] }?.let { return AppResult.Ok(it) }

        val result = apiCall(TAG) {
            api.translate(TranslateRequest(text = trimmed, targetLanguage = targetLanguage))
        }
        return when (result) {
            is AppResult.Ok -> {
                val translated = result.value.text
                synchronized(cache) { cache[key] = translated }
                AppResult.Ok(translated)
            }
            is AppResult.Err -> result
        }
    }

    private companion object {
        const val TAG = "TranslationRepo"
        const val MAX_CACHED = 128
    }
}
