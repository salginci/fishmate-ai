package com.fishmate.ai.data.remote

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fishmate.ai.core.Log2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.authDataStore by preferencesDataStore(name = "fishmate_auth")

/**
 * Stores the backend session token.
 *
 * Note what is *not* here: no Gemini credential is ever persisted. Live tokens
 * live for minutes, are held in memory only, and are re-minted on demand
 * (docs/SECURITY.md). Persisting one would recreate exactly the long-lived
 * on-device credential the architecture exists to avoid.
 */
class AuthStore(private val context: Context) {

    /** English is the product default; Turkish is fully supported. */
    private val DEFAULT_LANGUAGE = "en"


    private val tokenKey = stringPreferencesKey("access_token")
    private val userIdKey = stringPreferencesKey("user_id")
    private val displayNameKey = stringPreferencesKey("display_name")
    private val languageKey = stringPreferencesKey("language")
    private val onboardedKey = booleanPreferencesKey("onboarded")

    val token: Flow<String?> = context.authDataStore.data.map { it[tokenKey] }
    val userId: Flow<String?> = context.authDataStore.data.map { it[userIdKey] }
    val displayName: Flow<String?> = context.authDataStore.data.map { it[displayNameKey] }
    val language: Flow<String> = context.authDataStore.data.map { it[languageKey] ?: DEFAULT_LANGUAGE }
    val onboarded: Flow<Boolean> = context.authDataStore.data.map { it[onboardedKey] ?: false }

    suspend fun save(token: String, userId: String, displayName: String, language: String) {
        context.authDataStore.edit {
            it[tokenKey] = token
            it[userIdKey] = userId
            it[displayNameKey] = displayName
            it[languageKey] = language
        }
    }

    suspend fun setLanguage(language: String) {
        context.authDataStore.edit { it[languageKey] = language }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.authDataStore.edit { it[onboardedKey] = value }
    }

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
        Log2.i("AuthStore", "credentials cleared")
    }

    /**
     * Synchronous read for the OkHttp interceptor.
     *
     * Interceptors are not suspending, so this blocks briefly on the
     * DataStore flow. Reads are served from DataStore's in-memory cache after
     * the first, so this is not a per-request disk hit.
     */
    fun tokenBlocking(): String? = runCatching { runBlocking { token.first() } }.getOrNull()

    /**
     * Synchronous clear, for the same reason as [tokenBlocking].
     *
     * Called from an OkHttp interceptor when the backend rejects the token, so
     * it cannot suspend. The write is small and DataStore serialises it.
     */
    fun clearBlocking() {
        runCatching { runBlocking { clear() } }
            .onFailure { Log2.w("AuthStore", "failed to clear credentials", it) }
    }
}
