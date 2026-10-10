package com.fishmate.ai.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fishmate.ai.BuildConfig
import com.fishmate.ai.core.AppError
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val mode: AuthMode = AuthMode.REGISTER,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val loading: Boolean = false,
    val error: AppError? = null,
    val signedIn: Boolean = false,
)

enum class AuthMode { REGISTER, LOGIN }

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /**
     * Debug builds start on the sign-in tab with credentials pre-filled from
     * `local.properties`, so reinstalling during testing does not mean
     * retyping an email on a phone keyboard. Release builds get empty strings
     * (see app/build.gradle.kts), so nothing is ever shipped.
     */
    private val _state = MutableStateFlow(
        if (BuildConfig.DEBUG && BuildConfig.DEV_EMAIL.isNotBlank()) {
            AuthUiState(
                mode = AuthMode.LOGIN,
                email = BuildConfig.DEV_EMAIL,
                password = BuildConfig.DEV_PASSWORD,
            )
        } else {
            AuthUiState()
        },
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun setEmail(value: String) = update { it.copy(email = value.trim(), error = null) }
    fun setPassword(value: String) = update { it.copy(password = value, error = null) }
    fun setDisplayName(value: String) = update { it.copy(displayName = value, error = null) }

    fun toggleMode() = update {
        it.copy(
            mode = if (it.mode == AuthMode.REGISTER) AuthMode.LOGIN else AuthMode.REGISTER,
            error = null,
        )
    }

    /** Local validation first, so an obvious mistake costs no round trip. */
    private fun validate(state: AuthUiState): AppError? = when {
        state.email.isBlank() || !state.email.contains('@') ->
            AppError("invalid_email", "Enter a valid email address.")
        state.password.length < 8 ->
            AppError("invalid_password", "Password must be at least 8 characters.")
        state.mode == AuthMode.REGISTER && state.displayName.isBlank() ->
            AppError("invalid_name", "Tell me what to call you.")
        else -> null
    }

    fun submit(language: String) {
        val current = _state.value
        validate(current)?.let { problem ->
            update { it.copy(error = problem) }
            return
        }
        update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = if (current.mode == AuthMode.REGISTER) {
                authRepository.register(
                    current.email,
                    current.password,
                    current.displayName,
                    language,
                )
            } else {
                authRepository.login(current.email, current.password)
            }
            when (result) {
                is AppResult.Ok -> update { it.copy(loading = false, signedIn = true) }
                is AppResult.Err -> update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    private fun update(block: (AuthUiState) -> AuthUiState) {
        _state.value = block(_state.value)
    }
}
