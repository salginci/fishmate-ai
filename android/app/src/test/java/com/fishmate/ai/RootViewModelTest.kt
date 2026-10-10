package com.fishmate.ai

import com.fishmate.ai.data.repository.AuthRepository
import com.fishmate.ai.ui.RootViewModel
import com.fishmate.ai.ui.StartupState
import com.fishmate.ai.ui.navigation.Routes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Where the app opens.
 *
 * This is the regression guard for a bug that survived because nothing
 * asserted the decision: `FishMateNavHost` defaulted its `startDestination` to
 * onboarding and the root composable called it with no arguments, so a signed
 * in angler was sent through the carousel and the sign-in form on **every**
 * launch while a valid 30-day token sat in DataStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repository(
        token: String?,
        onboarded: Boolean = false,
        language: String = "en",
    ): AuthRepository = mockk(relaxed = true) {
        every { this@mockk.token } returns flowOf(token)
        every { this@mockk.onboarded } returns flowOf(onboarded)
        every { this@mockk.language } returns flowOf(language)
    }

    @Test
    fun `a stored token opens on home, not on sign-in`() = runTest(dispatcher) {
        val model = RootViewModel(repository(token = "a-valid-token"))

        val state = model.state.first { it is StartupState.Ready } as StartupState.Ready
        assertEquals(Routes.HOME, state.startDestination)
    }

    @Test
    fun `no token and never onboarded opens on the carousel`() = runTest(dispatcher) {
        val model = RootViewModel(repository(token = null, onboarded = false))

        val state = model.state.first { it is StartupState.Ready } as StartupState.Ready
        assertEquals(Routes.ONBOARDING, state.startDestination)
    }

    @Test
    fun `no token but already onboarded skips the carousel`() = runTest(dispatcher) {
        val model = RootViewModel(repository(token = null, onboarded = true))

        val state = model.state.first { it is StartupState.Ready } as StartupState.Ready
        assertEquals(Routes.AUTH, state.startDestination)
    }

    @Test
    fun `a blank token is not a session`() = runTest(dispatcher) {
        // DataStore returns "" rather than null if a write ever stored one, and
        // sending a blank bearer token would 401 every request from Home.
        val model = RootViewModel(repository(token = "   ", onboarded = true))

        val state = model.state.first { it is StartupState.Ready } as StartupState.Ready
        assertEquals(Routes.AUTH, state.startDestination)
    }

    @Test
    fun `the stored language is used, not the product default`() = runTest(dispatcher) {
        val model = RootViewModel(repository(token = "t", language = "tr"))

        val state = model.state.first { it is StartupState.Ready } as StartupState.Ready
        assertEquals("tr", state.language)
    }

    @Test
    fun `a token cleared mid-session reports the session ended`() = runTest(dispatcher) {
        val tokens = MutableStateFlow<String?>("a-valid-token")
        val repository: AuthRepository = mockk(relaxed = true) {
            every { this@mockk.token } returns tokens
            every { onboarded } returns flowOf(true)
            every { language } returns flowOf("en")
        }
        val model = RootViewModel(repository)
        model.state.first { it is StartupState.Ready }

        var ended = 0
        // Collected on the test scope, not `backgroundScope`: as of coroutines
        // 1.11 `advanceUntilIdle` deliberately does not run background work --
        // an endless `collect` there would never let it return -- so a
        // collector in `backgroundScope` never starts and the assertion below
        // passes or fails for the wrong reason.
        val collector = launch { model.sessionEnded.collect { ended++ } }
        testScheduler.runCurrent()

        // What UnauthorizedInterceptor does when the backend rejects the token.
        tokens.value = null
        testScheduler.runCurrent()

        assertEquals(1, ended)
        collector.cancel()
    }

    @Test
    fun `sitting on the sign-in screen is not an expiry`() = runTest(dispatcher) {
        // The token reads as absent on every emission while signed out. Acting
        // on the state rather than the transition would navigate to sign-in
        // repeatedly from the screen the user is already on.
        val tokens = MutableStateFlow<String?>(null)
        val repository: AuthRepository = mockk(relaxed = true) {
            every { this@mockk.token } returns tokens
            every { onboarded } returns flowOf(true)
            every { language } returns flowOf("en")
        }
        val model = RootViewModel(repository)
        model.state.first { it is StartupState.Ready }

        var ended = 0
        val collector = launch { model.sessionEnded.collect { ended++ } }
        testScheduler.runCurrent()

        tokens.value = null
        tokens.value = ""
        testScheduler.runCurrent()

        assertEquals(0, ended)
        collector.cancel()
    }

    @Test
    fun `finishing the carousel is remembered`() = runTest(dispatcher) {
        val repository: AuthRepository = mockk(relaxed = true) {
            every { token } returns flowOf(null)
            every { onboarded } returns flowOf(false)
            every { language } returns flowOf("en")
            coEvery { setOnboarded(any()) } returns Unit
        }
        val model = RootViewModel(repository)
        model.state.first { it is StartupState.Ready }

        model.markOnboarded()
        testScheduler.advanceUntilIdle()

        coVerify { repository.setOnboarded(true) }
    }
}
