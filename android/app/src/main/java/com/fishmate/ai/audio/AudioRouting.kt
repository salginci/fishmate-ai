package com.fishmate.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import com.fishmate.ai.BuildConfig
import com.fishmate.ai.core.Log2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio focus and output routing for Fishing Mode.
 *
 * Two realities drive this class:
 *
 * 1. The angler is wearing Bluetooth earbuds, and may take them out, run them
 *    flat, or walk out of range mid-session. Routing must follow without the
 *    session dying.
 * 2. Other apps (navigation, music, a phone call) will interrupt. We request
 *    focus so the system tells us, and we surface it rather than talking over
 *    a call.
 */
class AudioRouting(
    private val context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _route = MutableStateFlow(AudioRoute.UNKNOWN)
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private val _focusLost = MutableStateFlow(false)

    /** True while another app holds audio focus (e.g. an incoming call). */
    val focusLost: StateFlow<Boolean> = _focusLost.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null

    /**
     * The mode the device was in before this session took it over, or null
     * while we have not taken it. Also the re-entry guard: route changes call
     * into here repeatedly and must not stack up saved modes.
     */
    private var previousMode: Int? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                Log2.i(TAG, "audio focus lost change=$change")
                _focusLost.value = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log2.i(TAG, "audio focus regained")
                _focusLost.value = false
            }
        }
    }

    /** Request focus for a two-way voice session. Returns true when granted. */
    fun acquireFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(false)
            // The assistant is useless if it is ducked to inaudible on a windy
            // shore, so we ask others to pause instead.
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        val granted =
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        _focusLost.value = !granted
        Log2.i(TAG, "audio focus granted=$granted")
        return granted
    }

    fun releaseFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        _focusLost.value = false
    }

    /**
     * Put the device in its communication mode, which is what actually arms
     * the hardware echo canceller.
     *
     * The platform AEC lives in the audio HAL's voice path and its reference
     * signal is whatever that path is playing. Recording from
     * `VOICE_COMMUNICATION` and playing with `USAGE_VOICE_COMMUNICATION` is
     * not enough on its own: while the device mode stays `MODE_NORMAL` the
     * output is mixed on the **media** path, so the canceller has nothing to
     * subtract — and `AcousticEchoCanceler.enabled` still reports `true`,
     * which is why our own diagnostics claimed the AEC was on through every
     * measurement of a leak it should have removed. Everything built on top of
     * that measurement — the echo floor, the per-turn peak, the duck — was
     * compensating for a canceller that was never running.
     *
     * Called before the track and the recorder are created, because the HAL
     * picks a path when they open: entering the mode afterwards leaves both of
     * them on the path they already have.
     */
    fun enterCommunicationMode() {
        if (previousMode != null) return
        if (!communicationModeEnabled) {
            Log2.w(TAG, "communication mode disabled by debug flag")
            return
        }
        val before = audioManager.mode
        val applied = runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }.isSuccess
        if (!applied) {
            Log2.w(TAG, "could not enter communication mode; the AEC has no reference")
            return
        }
        previousMode = before
        // The volume is logged because communication mode moves the
        // loudspeaker onto STREAM_VOICE_CALL, which carries its own level: a
        // phone whose in-call volume sits low makes the assistant faint, and
        // that has to be legible in a field log as "quiet", not as "broken".
        Log2.i(
            TAG,
            "audio mode ${modeLabel(before)} -> ${modeLabel(audioManager.mode)} " +
                "voiceCallVolume=${volumeOf(AudioManager.STREAM_VOICE_CALL)}",
        )
    }

    /** Hand the mode back, so a session cannot leave the phone in a call. */
    fun leaveCommunicationMode() {
        val restore = previousMode ?: return
        previousMode = null
        runCatching { audioManager.mode = restore }
        Log2.i(TAG, "audio mode restored to ${modeLabel(restore)}")
    }

    /** Begin tracking output devices; call when entering Fishing Mode. */
    fun startRouteMonitoring() {
        refreshRoute()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                Log2.i(TAG, "audio devices added=${added?.size ?: 0}")
                refreshRoute()
                // Earbuds just connected — follow them without dropping the
                // session.
                applyHandsFreeRouting()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                Log2.i(TAG, "audio devices removed=${removed?.size ?: 0}")
                refreshRoute()
                // Earbuds pulled out: fall back to the loudspeaker, still
                // hands-free, rather than silently reverting to the earpiece.
                applyHandsFreeRouting()
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    fun stopRouteMonitoring() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    private fun refreshRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = outputs.map { it.type }.toSet()
        _route.value = when {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types ||
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types -> AudioRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET in types ||
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types ||
                AudioDeviceInfo.TYPE_USB_HEADSET in types -> AudioRoute.WIRED_HEADSET
            else -> AudioRoute.SPEAKER
        }
        Log2.i(TAG, "audio route=${_route.value}")
    }

    /**
     * Put the session on the right output device, hands-free.
     *
     * `USAGE_VOICE_COMMUNICATION` routes like a phone call, which means the
     * **earpiece** by default — so without this the angler has to hold the
     * phone to their ear, which defeats the entire product.
     *
     * Preference order: Bluetooth headset → wired headset → built-in
     * loudspeaker. The first two are what the angler actually uses on the
     * shore; the loudspeaker is the hands-free fallback.
     *
     * `setCommunicationDevice` (API 31+) is the supported way to do this.
     * `isSpeakerphoneOn` is deprecated and largely ignored on modern Android,
     * so it is only used as the pre-31 fallback.
     */
    fun applyHandsFreeRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val candidates = runCatching { audioManager.availableCommunicationDevices }
                .getOrDefault(emptyList())
            val preferred = PREFERRED_OUTPUT_TYPES.firstNotNullOfOrNull { type ->
                candidates.firstOrNull { it.type == type }
            }
            if (preferred == null) {
                Log2.w(TAG, "no communication device available; leaving default routing")
                return
            }
            val applied = runCatching { audioManager.setCommunicationDevice(preferred) }
                .getOrDefault(false)
            Log2.i(
                TAG,
                "hands-free routing -> ${deviceLabel(preferred.type)} (applied=$applied)",
            )
        } else {
            // API 29-30: the old switch is all there is.
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = _route.value == AudioRoute.SPEAKER
            }
            Log2.i(TAG, "hands-free routing (legacy speakerphone) route=${_route.value}")
        }
    }

    /** Hand routing back to the system when Fishing Mode ends. */
    fun clearRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    /**
     * Diagnostic switches, debug builds only, flipped with
     * `adb shell settings put global fishmate_comm_mode 0`.
     *
     * The audio path can only be characterised on a real handset -- the
     * emulator has no loudspeaker leak and no vendor voice processing -- and
     * each combination otherwise costs a rebuild and a reinstall. Release
     * builds never read these.
     */
    val communicationModeEnabled: Boolean get() = debugFlag("fishmate_comm_mode")

    /**
     * The main microphone, when we want to override the HAL's choice.
     *
     * In communication mode this device binds capture to
     * `AUDIO_DEVICE_IN_BACK_MIC` -- the mic furthest from the loudspeaker,
     * which is the sensible pick for echo and a dead one in practice here.
     */
    fun builtinMic(): AudioDeviceInfo? {
        if (!debugFlag("fishmate_preferred_mic")) return null
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }.getOrNull()
    }

    /** Whether we attach AEC/NS ourselves, or leave the stream to the HAL. */
    val manualEffectsEnabled: Boolean get() = debugFlag("fishmate_manual_aec")

    /** 0 = voice communication (default), 1 = mic, 2 = voice recognition. */
    fun captureSourceOverride(): Int? {
        if (!BuildConfig.DEBUG) return null
        val choice = runCatching {
            Settings.Global.getInt(context.contentResolver, "fishmate_mic_source", 0)
        }.getOrDefault(0)
        return when (choice) {
            1 -> MediaRecorder.AudioSource.MIC
            2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> null
        }
    }

    private fun debugFlag(name: String): Boolean {
        if (!BuildConfig.DEBUG) return true
        return runCatching {
            Settings.Global.getInt(context.contentResolver, name, 1) == 1
        }.getOrDefault(true)
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in-call"
        AudioManager.MODE_IN_COMMUNICATION -> "in-communication"
        else -> "mode-$mode"
    }

    private fun volumeOf(stream: Int): String = runCatching {
        "${audioManager.getStreamVolume(stream)}/${audioManager.getStreamMaxVolume(stream)}"
    }.getOrDefault("unknown")

    private fun deviceLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "loudspeaker"
        else -> "type-$type"
    }

    companion object {
        private const val TAG = "AudioRouting"

        /**
         * Output preference, best first. The loudspeaker is last but is still
         * hands-free — the earpiece is deliberately never chosen.
         *
         * `TYPE_BLE_HEADSET` is API 31 and inlines to a plain int. On API 29
         * and 30 no device ever reports that type, so the entry simply never
         * matches — which is the desired behaviour, not a latent crash.
         */
        @Suppress("InlinedApi")
        private val PREFERRED_OUTPUT_TYPES = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }
}

enum class AudioRoute { UNKNOWN, SPEAKER, WIRED_HEADSET, BLUETOOTH }
