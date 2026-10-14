package com.fishmate.ai.ui.screens.fishing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fishmate.ai.audio.AudioCapture
import com.fishmate.ai.audio.AudioPlayer
import com.fishmate.ai.audio.AudioRouting
import com.fishmate.ai.camera.CameraSource
import com.fishmate.ai.data.repository.AuthRepository
import com.fishmate.ai.data.repository.FishingRepository
import com.fishmate.ai.data.repository.LiveCredentialsRepository
import com.fishmate.ai.data.repository.TranslationRepository
import com.fishmate.ai.live.AssistantPhase
import com.fishmate.ai.live.FishingModeController
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.live.FishingModeState
import com.fishmate.ai.live.LiveAiSession
import com.fishmate.ai.live.ToolOrchestrator
import com.fishmate.ai.location.LocationPrecision
import com.fishmate.ai.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class FishingModeViewModel @Inject constructor(
    session: LiveAiSession,
    credentials: LiveCredentialsRepository,
    toolOrchestrator: ToolOrchestrator,
    audioCapture: AudioCapture,
    audioPlayer: AudioPlayer,
    audioRouting: AudioRouting,
    private val locationProvider: LocationProvider,
    private val authRepository: AuthRepository,
    private val fishingRepository: FishingRepository,
    private val translationRepository: TranslationRepository,
) : ViewModel() {

    /**
     * The camera needs a LifecycleOwner, which only the composable has, so the
     * screen hands one in. Kept nullable because voice-only is a valid mode —
     * the assistant is useful without the camera.
     */
    private var cameraSource: CameraSource? = null

    private val controller = FishingModeController(
        session = session,
        credentials = credentials,
        toolOrchestrator = toolOrchestrator,
        audioCapture = audioCapture,
        audioPlayer = audioPlayer,
        audioRouting = audioRouting,
        locationProvider = locationProvider,
        scope = viewModelScope,
        cameraSourceProvider = { cameraSource },
    )

    val state: StateFlow<FishingModeState> = controller.state

    /** Latest camera frame, so the angler can see what the assistant sees. */
    val previewFrame: StateFlow<com.fishmate.ai.live.CameraPreviewFrame?> = controller.previewFrame

    val activeSession = fishingRepository.observeActiveSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    private val _showTranslation = MutableStateFlow(true)

    /** Whether to render the subtitle line under each transcript entry. */
    val showTranslation: StateFlow<Boolean> = _showTranslation.asStateFlow()

    /**
     * Language the subtitles are written in: the other one.
     *
     * The assistant speaks whichever language the angler set, so translating
     * into that same language would produce a duplicate of every line. An
     * English session reads along in Turkish and a Turkish session in English.
     */
    private val _subtitleLanguage = MutableStateFlow("tr")
    val subtitleLanguage: StateFlow<String> = _subtitleLanguage.asStateFlow()

    private var translationJob: Job? = null

    fun attachCamera(source: CameraSource) {
        cameraSource = source
    }

    fun start(cameraEnabled: Boolean, locationPrecision: LocationPrecision) {
        if (_started.value) return
        _started.value = true
        locationProvider.precision = locationPrecision
        viewModelScope.launch {
            val language = authRepository.language.first()
            _subtitleLanguage.value = if (language.startsWith("tr")) "en" else "tr"
            observeTranslations()
            controller.start(language = language, cameraEnabled = cameraEnabled)
        }
    }

    fun toggleTranslation() {
        _showTranslation.value = !_showTranslation.value
    }

    /**
     * Fill in subtitles for transcript lines that have settled.
     *
     * Live transcription arrives as fragments that merge into the line being
     * spoken, so a line's text changes several times a second while someone is
     * talking. The debounce means a line is only translated once it stops
     * growing — one request per conversational turn instead of one per
     * fragment. Only the tail is considered; older lines are already done, and
     * re-translating the scrollback on every change would be quadratic.
     */
    @OptIn(FlowPreview::class)
    private fun observeTranslations() {
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            state
                .map { it.transcript }
                .debounce(TRANSLATE_SETTLE_MS)
                .distinctUntilChanged()
                .collect { transcript ->
                    if (!_showTranslation.value) return@collect
                    val target = _subtitleLanguage.value
                    // The line being spoken is still growing, and a subtitle
                    // for half a sentence is thrown away when the rest of it
                    // arrives: `applyTranslation` refuses a translation whose
                    // source text no longer matches. Measured against the
                    // backend, one spoken turn was costing five to ten
                    // requests and keeping one a second in flight for the
                    // whole conversation, on a phone whose mobile link was
                    // already stalling for seconds at a time. The settled
                    // debounce was never enough on its own: speech keeps
                    // arriving, so the line keeps settling at a new length.
                    //
                    // So the last line waits until the assistant has stopped
                    // talking. Everything above it has finished growing by
                    // definition.
                    val growing = if (state.value.phase == AssistantPhase.SPEAKING) {
                        transcript.lastOrNull()?.id
                    } else {
                        null
                    }
                    transcript.takeLast(TRANSLATE_TAIL_LINES)
                        .filter { it.translation == null && it.text.isNotBlank() }
                        .filter { it.id != growing }
                        .forEach { line ->
                            when (val result = translationRepository.translate(line.text, target)) {
                                is AppResult.Ok ->
                                    controller.applyTranslation(line.id, line.text, result.value)
                                is AppResult.Err ->
                                    // A missing subtitle is cosmetic; the
                                    // conversation itself is unaffected.
                                    Log2.d(TAG, "subtitle unavailable: ${result.error.message}")
                            }
                        }
                }
        }
    }

    fun stop() {
        if (!_started.value) return
        _started.value = false
        translationJob?.cancel(); translationJob = null
        viewModelScope.launch { controller.stop() }
    }

    /** Sampled per animation frame by the avatar; see [FishingModeController.outputLevel]. */
    fun outputLevel(): Float = controller.outputLevel

    fun toggleMute() = controller.setMuted(!state.value.isMuted)

    /** Tap the avatar while it is talking: stop, and listen. */
    fun interrupt() = controller.interrupt()

    fun toggleHandsFree() = controller.setHandsFree(!state.value.handsFree)

    fun toggleCamera() = controller.setCameraEnabled(!state.value.cameraEnabled)

    fun switchCamera() = controller.switchCamera()

    fun sendLookAtThis() = controller.captureAndSendFrame()

    fun sendText(text: String) = controller.sendText(text)

    fun finishSession() {
        viewModelScope.launch {
            activeSession.value?.let { fishingRepository.finishSession(it.id, null) }
        }
    }

    override fun onCleared() {
        // Release the microphone, camera and socket deterministically rather
        // than waiting for GC — a held mic blocks every other app.
        viewModelScope.launch { controller.stop() }
        controller.release()
        cameraSource?.release()
    }

    private companion object {
        const val TAG = "FishingModeVM"

        /**
         * How long a transcript line must stop changing before it is
         * translated. Long enough to cover the gap between transcription
         * fragments mid-sentence, short enough that the subtitle still feels
         * like it belongs to what was just said.
         */
        const val TRANSLATE_SETTLE_MS = 900L

        /** Only the visible tail is worth translating. */
        const val TRANSLATE_TAIL_LINES = 6
    }
}
