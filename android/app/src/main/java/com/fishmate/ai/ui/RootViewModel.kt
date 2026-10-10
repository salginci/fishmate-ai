package com.fishmate.ai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fishmate.ai.data.repository.AuthRepository
import com.fishmate.ai.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Where the app opens, and whether anyone is signed in.
 *
 * [Loading] exists so the decision is made before the graph is built rather
 * than during it: `NavHost` reads `startDestination` once, on first
 * composition, so a destination that arrives a frame later is ignored. Showing
 * onboarding while the token is still being read and then jumping to Home
 * would be a visible flash of the wrong screen on every launch.
 */
sealed interface StartupState {
    data object Loading : StartupState

    data class Ready(val startDestination: String, val language: String) : StartupState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    /**
     * Fires when a session that *was* signed in stops being signed in.
     *
     * The backend token lasts 30 days, but it can still be rejected before
     * then — a revoked account, or a server that no longer recognises the
     * signature. `UnauthorizedInterceptor` clears the token when that happens,
     * and without this the angler would sit on a Home screen where every
     * request fails and the only route back to sign-in is buried in Settings.
     *
     * A conflated channel rather than a `SharedFlow`, because this must not
     * be lost and must not repeat. A `SharedFlow` with `replay = 0` drops an
     * emission that lands while nothing is collecting -- during a
     * configuration change, say -- and losing it leaves the angler on a Home
     * screen where every request fails. A channel holds the one pending event
     * until someone takes it, and delivers it exactly once.
     */
    private val _sessionEnded = Channel<Unit>(Channel.CONFLATED)
    val sessionEnded: Flow<Unit> = _sessionEnded.receiveAsFlow()

    init {
        viewModelScope.launch {
            // first() rather than collect(): this resolves the opening
            // destination and must not change it afterwards.
            val token = authRepository.token.first()
            val signedIn = !token.isNullOrBlank()
            _state.value = StartupState.Ready(
                startDestination = when {
                    // The token is trusted without a round trip on purpose.
                    // Validating it here would make a cold start on a shoreline
                    // with no signal land on the sign-in screen, which is the
                    // offline-first rule (docs/ARCHITECTURE.md) inverted: a
                    // rejected token is handled when a call actually fails.
                    signedIn -> Routes.HOME
                    // Someone who has signed out before has seen the carousel.
                    authRepository.onboarded.first() -> Routes.AUTH
                    else -> Routes.ONBOARDING
                },
                language = authRepository.language.first(),
            )
        }

        viewModelScope.launch {
            // Tracks the transition rather than the state: sitting on the
            // sign-in screen reads as "no token" every time, and reacting to
            // the state would look like an expiry on every emission.
            var previouslySignedIn = false
            authRepository.token.collect { token ->
                val signedIn = !token.isNullOrBlank()
                if (previouslySignedIn && !signedIn) _sessionEnded.trySend(Unit)
                previouslySignedIn = signedIn
            }
        }
    }

    /**
     * Remember that the carousel has been seen.
     *
     * Without this the flag is never written, so signing out sends the angler
     * back through the introduction they have already read.
     */
    fun markOnboarded() {
        viewModelScope.launch { authRepository.setOnboarded(true) }
    }
}
