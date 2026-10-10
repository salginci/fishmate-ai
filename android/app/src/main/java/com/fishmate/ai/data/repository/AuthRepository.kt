package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.core.map
import com.fishmate.ai.data.local.FishMateDatabase
import com.fishmate.ai.data.remote.AuthStore
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.LoginRequest
import com.fishmate.ai.data.remote.dto.PreferencesDto
import com.fishmate.ai.data.remote.dto.PreferencesUpdate
import com.fishmate.ai.data.remote.dto.RegisterRequest
import com.fishmate.ai.data.remote.dto.UserDto
import kotlinx.coroutines.flow.Flow

class AuthRepository(
    private val api: FishMateApi,
    private val authStore: AuthStore,
    private val database: FishMateDatabase,
) {
    val token: Flow<String?> = authStore.token
    val displayName: Flow<String?> = authStore.displayName
    val language: Flow<String> = authStore.language
    val onboarded: Flow<Boolean> = authStore.onboarded

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        language: String,
    ): AppResult<UserDto> =
        apiCall("auth") {
            api.register(RegisterRequest(email, password, displayName, language))
        }.also { result ->
            result.getOrNull()?.let {
                authStore.save(it.accessToken, it.user.id, it.user.displayName, language)
            }
        }.map { it.user }

    suspend fun login(email: String, password: String): AppResult<UserDto> =
        apiCall("auth") { api.login(LoginRequest(email, password)) }.also { result ->
            result.getOrNull()?.let {
                authStore.save(
                    it.accessToken,
                    it.user.id,
                    it.user.displayName,
                    it.user.preferences?.language ?: "en",
                )
            }
        }.map { it.user }

    suspend fun refreshProfile(): AppResult<UserDto> = apiCall("auth") { api.me() }

    suspend fun updatePreferences(update: PreferencesUpdate): AppResult<PreferencesDto> =
        apiCall("auth") { api.updatePreferences(update) }.also { result ->
            result.getOrNull()?.let { authStore.setLanguage(it.language) }
        }

    suspend fun setOnboarded(value: Boolean) = authStore.setOnboarded(value)

    /**
     * Sign out, clearing the local mirror too.
     *
     * The cached catches and sessions belong to the signed-in angler, so they
     * must not be visible to whoever signs in next on the same device
     * (docs/PRIVACY.md).
     */
    suspend fun logout() {
        authStore.clear()
        runCatching {
            database.clearAllTables()
        }.onFailure { Log2.w("AuthRepository", "failed to clear local cache", it) }
    }

    /** Delete the account server-side, then wipe everything local. */
    suspend fun deleteAccount(): AppResult<Unit> =
        apiCall("auth") { api.deleteAccount() }.also { if (it.isOk) logout() }
}
