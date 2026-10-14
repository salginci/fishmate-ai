package com.fishmate.ai.audio

import com.fishmate.ai.core.Log2
import kotlin.math.sqrt

/**
 * Decides which captured frames reach the model while the assistant is
 * speaking on the loudspeaker.
 *
 * On a speaker the microphone mostly hears the assistant. Forwarding that
 * makes server-side VAD report a barge-in, so the model stops, restarts, hears
 * itself again, and the conversation collapses — measured on a Galaxy A35 at
 * one interruption roughly every three seconds. The same echo, fed back as
 * input audio, is also what made the model language-detect its own distorted
 * speech and wander into other languages.
 *
 * Muting capture for each spoken turn would fix both and remove barge-in,
 * which is the point of a hands-free assistant: an angler with a fish on the
 * line has to be able to cut in. So the microphone stays open and each frame
 * is judged.
 *
 * ## Why the previous rule could not work
 *
 * It estimated the echo from the microphone alone and demanded a frame be
 * [BARGE_IN_FACTOR]× louder than that estimate. The estimate tracks the peak
 * of the assistant's own speech — around 3000 RMS on this device — so the bar
 * sat near 10 500 RMS. A person talking into a phone at arm's length lands
 * well below that once the platform canceller has done its work, so the gate
 * was a mute with extra steps: nothing a human could say would open it.
 *
 * ## What it does instead
 *
 * The one thing the microphone cannot tell you is what the speaker is
 * playing — but we know exactly, frame-aligned, from
 * [AudioPlayer.outputLevel]. With that reference two cases separate cleanly:
 *
 * - **The reference is quiet.** Speech has gaps: between words, between
 *   sentences, and before the model answers at all. Energy at the microphone
 *   with nothing coming out of the speaker is the angler, and it is admitted
 *   on an absolute threshold alone. This is the fast path, and it is the one
 *   that makes interruption feel immediate.
 * - **The reference is loud.** Now microphone energy is ambiguous, so it must
 *   clear the echo we would *expect* for that reference level — a gain learned
 *   from this room, this volume and this phone position — by a margin.
 *
 * Learning the gain from loud reference frames is what keeps this honest: it
 * is calibrated against the leak itself rather than against a guess, so a
 * louder speaker raises the bar and a quieter one lowers it.
 *
 * Kept free of Android types so the thresholds can be exercised in unit tests
 * — they are the kind of tuning that is easy to break silently.
 */
class EchoGate(
    private val bargeInFactor: Double = BARGE_IN_FACTOR,
    private val minBargeInRms: Double = MIN_BARGE_IN_RMS,
    private val bargeInFrames: Int = BARGE_IN_FRAMES,
    private val quietReference: Float = QUIET_REFERENCE,
    private val gainDecay: Double = ECHO_GAIN_DECAY,
    private val minSpeechRatio: Double = MIN_SPEECH_RATIO,
    sampleRateHz: Int = 16_000,
) {
    /**
     * One-pole high-pass coefficient for [HIGH_PASS_HZ].
     *
     * `a = RC / (RC + dt)`, the textbook first-order form. Computed rather
     * than hard-coded so a capture rate change cannot silently move the
     * corner frequency.
     */
    private val highPassAlpha: Double = run {
        val rc = 1.0 / (2.0 * Math.PI * HIGH_PASS_HZ)
        val dt = 1.0 / sampleRateHz
        rc / (rc + dt)
    }
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0
    /** Learned microphone RMS per unit of playback level. */
    private var echoGain = 0.0
    private var referenceEnvelope = 0.0
    private var calibratedFrames = 0
    private var framesThisTurn = 0
    private var idleFrames = 0

    /** Measured noise of this room, in speech-band RMS. Survives turn resets. */
    private var roomFloor = 0.0

    /** Loudest leak measured during the current spoken turn, in speech-band RMS. */
    private var turnEchoPeak = 0.0
    private var loudFrames = 0
    private var bargeInOpen = false
    private var suspectFrames = 0

    /** Current echo estimate for the last reference seen, for logs and tests. */
    var estimatedEchoRms: Double = 0.0
        private set

    /** Why the last frame was rejected, for diagnostics. */
    var lastRejection: String = ""
        private set

    /**
     * The leak this turn has been measured at, which is what the bar is
     * built from. Exposed because a test that cannot see it can only assert
     * side effects, and one written that way passed against the very bug it
     * was added for.
     */
    val measuredEchoPeak: Double get() = turnEchoPeak

    /** True once this turn's barge-in has been accepted. */
    val isBargeInOpen: Boolean get() = bargeInOpen

    /**
     * Someone may be talking, and the speaker is in the way of finding out.
     *
     * Deliberately weaker evidence than [isBargeInOpen]: a frame only has to
     * be speech-shaped and louder than the leak predicted for this instant,
     * for [SUSPECT_FRAMES] in a row. That is far too little to interrupt the
     * assistant on — early in a turn the prediction runs low and its own echo
     * clears this easily, which is exactly what happened at 15:32:01 on the
     * device, where the leak read 1888 against a prediction of 818.
     *
     * It is enough to *duck* on. Dropping the output for a moment costs
     * nothing if this was echo — the leak drops with it and the gate goes back
     * to rejecting — and it is the only thing that makes the real case
     * decidable: the angler's voice does not drop when the speaker does. The
     * measurement that forced this: a question asked over the assistant read
     * 5585 at the microphone with the prediction at 470, and was rejected
     * anyway because the bar still carried the loudest moment of that turn.
     * The server never heard the question, so it never answered.
     */
    val isSuspectingSpeech: Boolean get() = !bargeInOpen && suspectFrames >= SUSPECT_FRAMES

    /**
     * @param speaking whether the assistant's audio is going out of the
     *   built-in speaker right now. False means there is no acoustic path
     *   (headset, or silence), so capture runs wide open.
     * @param reference how loud the speaker is at this instant, 0..1, from
     *   [AudioPlayer.outputLevel]. Zero makes this a plain absolute-threshold
     *   gate, which is the correct degradation when no reference is wired in.
     */
    fun accepts(
        buffer: ByteArray,
        length: Int,
        speaking: Boolean,
        reference: Float = 0f,
        outputScale: Float = 1f,
    ): Boolean {
        if (!speaking) {
            // Measure even here, where nothing is being judged. Without it
            // the only levels on record are ones taken while the speaker was
            // running, so there is no way to tell a voice apart from the room
            // — the two overlapped completely in the first capture, and a
            // threshold cannot be chosen from that.
            val idleRaw = rmsOf(buffer, length)
            val idleBand = speechBandRmsOf(buffer, length)
            observeRoom(idleBand)
            if (++idleFrames % DIAGNOSTIC_EVERY_FRAMES == 0) {
                Log2.d(
                    TAG,
                    "idle voice=${idleBand.toInt()} raw=${idleRaw.toInt()} " +
                        "ratio=${"%.2f".format(if (idleRaw <= 1.0) 0.0 else idleBand / idleRaw)} " +
                        "room=${roomFloor.toInt()} bar=${noiseBar().toInt()}",
                )
            }
            // Reset, so the next turn starts from this room's actual echo
            // level rather than a stale one from a different volume or
            // phone position.
            reset()
            return true
        }
        // Latched: the server flushes playback a moment after accepting a
        // barge-in, and reassessing mid-sentence would clip the first words.
        if (bargeInOpen) return true

        val raw = rmsOf(buffer, length)
        // Energy left after a 300 Hz high-pass: the band speech actually
        // lives in. Wind across a microphone, a hand brushing the case and a
        // boat engine are all dominated by what this removes, and they are
        // loud enough to clear any level threshold you could set — which is
        // why judging on total loudness alone cuts the assistant off on a
        // breezy shore.
        val rms = speechBandRmsOf(buffer, length)
        // How much of the frame is in that band. A voice keeps most of its
        // energy there; wind keeps almost none, however loud it is.
        val speechRatio = if (raw <= 1.0) 0.0 else rms / raw

        // Instant attack, slow release. The microphone hears the speaker
        // late — the acoustic path plus capture buffering — so the frame
        // being judged now carries sound emitted around a tenth of a second
        // ago. Comparing it against the *instantaneous* playback level made
        // every gap between words look like silence while the room was still
        // ringing with the word before it, the threshold collapsed to the
        // absolute floor, and the assistant's own echo was forwarded as a
        // barge-in. Speech is mostly gaps, so that repeated for as long as it
        // kept talking.
        //
        // The envelope keeps the reference up across those gaps and only
        // falls once playback has genuinely stopped.
        val liveReference = reference.coerceIn(0f, 1f).toDouble()
        referenceEnvelope = maxOf(liveReference, referenceEnvelope * REFERENCE_RELEASE)
        val ref = referenceEnvelope
        val loudReference = ref >= quietReference

        // Evidence and judgement read different references, and conflating
        // them is a bug in both directions.
        //
        // The *bar* is built on the envelope, because a gap between words is
        // not silence: the microphone is still carrying the word before it.
        //
        // *Evidence* about the leak may only be taken from an instant when the
        // speaker is honestly loud. A frame in a gap, with the envelope still
        // held high, is exactly where the angler's own voice lands — and three
        // separate measurements show what happens when it is counted as echo:
        // a turn peak of 4075 learned from a voice whose echo peaked at 3096;
        // a bar that rose from 2558 to 3430 during a reference dip, locking
        // out the person who spoke; and a quiet-phone case where one shouted
        // frame in a gap set the predicted leak to the level of the shout and
        // blocked the rest of it. The envelope is a memory of loudness, which
        // is the right thing to defend with and the wrong thing to learn from.
        val calibratable = liveReference >= CALIBRATION_REFERENCE
        // Measure the leak for a moment before judging anything against it.
        // Not conditional on the reference: the reference is what proved
        // unreliable at frame granularity — it reads ~0 while the microphone
        // is still carrying the tail of the word just played, and every false
        // interruption on the device landed exactly there.
        if (calibratedFrames < WARMUP_FRAMES) {
            calibratedFrames++
            turnEchoPeak = maxOf(turnEchoPeak, rms)
            loudFrames = 0
            suspectFrames = 0
            return false
        }

        if (calibratable && calibratedFrames < CALIBRATION_FRAMES + WARMUP_FRAMES) {
            // Nothing can be judged until we know what this room's leak looks
            // like, so the first loud-reference frames of a turn only
            // measure. They are not forwarded: at this point an echo frame
            // and a shout are indistinguishable.
            //
            // Counted on loud-reference frames alone, so it costs nothing in
            // the gaps — a barge-in during a pause is still immediate.
            calibratedFrames++
            // Divided by the live reference, not the envelope: the gain is
            // what this frame says about the leak, and this frame was taken
            // at the live level. The envelope is the higher of the two, so
            // using it would quietly understate the leak.
            if (speechRatio >= minSpeechRatio) echoGain = maxOf(rms / liveReference, echoGain)
            estimatedEchoRms = echoGain * ref
            loudFrames = 0
            return false
        }

        estimatedEchoRms = echoGain * ref

        // Two independent estimates of what the assistant is leaking into the
        // microphone, and the bar respects whichever is higher:
        //
        //  - the loudest leak seen so far *this turn*, which survives the gaps
        //    between words where the reference reads silent while the room is
        //    still ringing. Measured on the device: at those moments the
        //    microphone carried 1500-3300 of pure echo in a room that reads 1
        //    when genuinely idle, so a bar that follows the reference down is
        //    a bar that collapses constantly.
        //  - the leak predicted for *this instant* from the measured gain,
        //    which is the one that catches a passage louder than anything
        //    measured so far. On the A35 at 14:34:58 a frame of 4433 was
        //    accepted against a bar of 3842 while the gate's own prediction
        //    for that frame was 5915 — it forwarded the assistant's voice as a
        //    barge-in because nothing consulted the prediction.
        //
        // Both are per turn: the gate resets when playback stops, so a quieter
        // reply or a phone moved further away re-measures from scratch rather
        // than inheriting a stale ceiling.
        //
        // Until a loud-reference frame has taught the gate the leak, the only
        // measurement on record is the warmup window — 0.3 s taken at whatever
        // level the utterance *opened* at, which is its quietest part. The leak
        // then grows with the sentence: on the device a turn that warmed up at
        // 1359 was leaking 2985 four seconds later, and the 1.8x margin over
        // the opening let that through 1.2 s into the greeting. So an unmeasured
        // turn gets the wider margin, which is the one thing that distinguishes
        // "quiet because it has not started properly yet" from "quiet".
        val measured = echoGain > 0.0
        val margin = if (measured) bargeInFactor else UNCALIBRATED_FACTOR
        // The peak was measured at whatever the speaker was doing then, so it
        // is scaled by what the speaker is doing now. Only an *externally
        // known* attenuation counts here — the duck — never an inference from
        // the reference, which cannot tell a ducked speaker from a quiet
        // passage and would let the leak in the gaps between words through.
        val scale = outputScale.coerceIn(0f, 1f).toDouble()
        val threshold = maxOf(
            noiseBar(),
            turnEchoPeak * scale * margin,
            estimatedEchoRms * bargeInFactor,
        )

        val voiceLike = speechRatio >= minSpeechRatio

        // Suspicion, which is not a decision. See [isSuspectingSpeech].
        if (voiceLike && rms > maxOf(noiseBar(), estimatedEchoRms * SUSPECT_FACTOR)) {
            suspectFrames++
        } else {
            suspectFrames = 0
        }

        // A periodic line while the assistant is speaking, so this can be
        // tuned from a real shore instead of from a guess. Debug builds only,
        // one line a second, and it carries levels — never audio.
        if (++framesThisTurn % DIAGNOSTIC_EVERY_FRAMES == 0) {
            Log2.d(
                TAG,
                "gate voice=${rms.toInt()} raw=${raw.toInt()} " +
                    "ratio=${"%.2f".format(speechRatio)} ref=${"%.2f".format(ref)} " +
                    "echo=${estimatedEchoRms.toInt()} threshold=${threshold.toInt()} " +
                    "loud=$loudFrames",
            )
        }

        if (rms < threshold || !voiceLike) {
            // Learn *after* classifying, and only from frames judged to be
            // echo. Updating before would let the angler's own voice raise
            // the bar above itself — the gain chases the speech and barge-in
            // welds shut, which is exactly how the rule this replaced failed.
            // Evidence about the leak only when the speaker is *certainly*
            // the dominant source. Learning from every rejected frame let the
            // angler's own voice — rejected precisely because the bar was too
            // high — raise the bar further: measured on the device as a peak
            // of 4075 when the real echo peaked at 3096, the difference being
            // their voice teaching the gate to ignore them. The same trap as
            // the echo gain, in a second place.
            // No decay inside a turn. The peak means "the loudest leak this
            // turn" and the turn is already bounded — [reset] drops it to zero
            // the moment playback stops, which is what lets a quieter reply
            // re-measure. Decaying it as well took 40 % off the bar every
            // second at 50 frames a second, so a bar built during a loud
            // passage was gone a second and a half later: that is how 14:34:58
            // ended up judging a 5915 leak against a bar of 3842.
            // Nothing is learned while the speaker is ducked: these frames
            // describe a leak at a sixth of the volume, and carrying that
            // number into the rest of the turn would leave the assistant
            // unprotected the moment the volume comes back up.
            val atFullOutput = scale >= 1.0
            if (atFullOutput && calibratable && plausiblyEcho(rms)) {
                turnEchoPeak = maxOf(rms, turnEchoPeak)
            }
            if (atFullOutput && calibratable && voiceLike && plausiblyEcho(rms)) {
                echoGain = maxOf(rms / liveReference, echoGain * gainDecay)
            }
            loudFrames = 0
            lastRejection = if (!voiceLike) "not-voice" else "below-threshold"
            return false
        }

        loudFrames++
        // A ducked speaker needs the sound to last, not just to be loud.
        //
        // Dropping the output 16 dB in one step costs the platform's echo
        // canceller its model of the room, and for about 200 ms afterwards it
        // cancels almost nothing: the microphone carries *more* echo than
        // before the duck, not less. Measured on the device — 7108 of
        // speech-band energy where that room's leak at full volume had never
        // exceeded 4440 — and it lands exactly where the bar is lowest,
        // because the reference has already fallen. Four interruptions the
        // angler did not make came from that transient, every one of them
        // 107-182 ms after the duck.
        //
        // Level cannot separate a collapsing canceller from a voice; duration
        // can. The transient is over inside 300 ms and a sentence is not, so
        // while the speaker is held down the gate wants the frames to keep
        // coming for [DUCKED_BARGE_IN_FRAMES]. It costs a third of a second of
        // detection latency on a path that had none at all before.
        val needed = if (scale < 1.0) DUCKED_BARGE_IN_FRAMES else bargeInFrames
        if (loudFrames < needed) return false

        bargeInOpen = true
        Log2.i(
            TAG,
            "barge-in accepted voice=${rms.toInt()} raw=${raw.toInt()} " +
                "ratio=${"%.2f".format(speechRatio)} ref=${"%.2f".format(ref)} " +
                "echo=${estimatedEchoRms.toInt()} threshold=${threshold.toInt()}",
        )
        return true
    }

    fun reset() {
        echoGain = 0.0
        turnEchoPeak = 0.0
        referenceEnvelope = 0.0
        hpPrevIn = 0.0
        hpPrevOut = 0.0
        calibratedFrames = 0
        estimatedEchoRms = 0.0
        loudFrames = 0
        suspectFrames = 0
        bargeInOpen = false
    }

    /**
     * RMS of the frame after a first-order high-pass at [HIGH_PASS_HZ].
     *
     * Filter state carries across frames, so the corner behaves the same at
     * a frame boundary as inside one.
     */
    private fun speechBandRmsOf(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var i = 0
        var samples = 0
        while (i + 1 < length) {
            val x = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                .toDouble()
            val y = highPassAlpha * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = y
            sum += y * y
            samples++
            i += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    /**
     * The level a frame must clear to be a voice rather than this room.
     *
     * Relative, because an absolute number cannot be right on more than one
     * phone: microphone sensitivity and input gain differ by more between
     * devices than a quiet room differs from a noisy one. [minBargeInRms] is
     * only a floor under the floor, for a room so silent that a whisper of
     * handling noise would otherwise qualify.
     */
    private fun noiseBar(): Double = maxOf(minBargeInRms, roomFloor * NOISE_MARGIN)

    /**
     * Whether this frame can be believed as evidence about the leak.
     *
     * A frame well above the leak predicted for this instant is not a louder
     * echo, it is a second source — the only other thing in the room being the
     * person the gate exists to hear. Learning from it is how the bar climbs
     * above the voice that raised it, and this is the third place that trap
     * turned up: the turn peak, the echo gain, and then the gain again by way
     * of suspicion, where a question asked over the assistant taught the gate
     * to predict a leak the size of the question and so to stop suspecting it.
     *
     * Before calibration there is no prediction, and the first loud frames of
     * a turn have to be allowed to make one.
     */
    private fun plausiblyEcho(rms: Double): Boolean =
        estimatedEchoRms <= 0.0 || rms <= estimatedEchoRms * SUSPECT_FACTOR

    /**
     * Track the room's own noise while the assistant is silent.
     *
     * Falls fast and rises slowly: the quietest recent moment is the best
     * estimate of the room, and tracking upward quickly would let the
     * angler's own voice raise the bar above itself — the same trap the echo
     * gain fell into.
     */
    private fun observeRoom(bandRms: Double) {
        roomFloor = if (bandRms < roomFloor || roomFloor == 0.0) {
            bandRms
        } else {
            roomFloor + (bandRms - roomFloor) * ROOM_RISE
        }
    }

    /** RMS over the frame's little-endian 16-bit samples. */
    private fun rmsOf(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val samples = length / 2
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    companion object {
        private const val TAG = "EchoGate"

        /**
         * Margin over the measured leak.
         *
         * Briefly 1.25, from "the echo peaks at 3100 and a voice reaches
         * 4133". Both numbers were maxima of once-a-second samples across a
         * turn of some six hundred frames, which is not a peak — it is a
         * sample that happened to be the largest of twelve. The real leak is
         * higher, the bar went under it, and the assistant started cutting
         * its own greeting short again.
         *
         * Back to the value under which self-interruption had actually
         * stopped. It is deliberately conservative: a missed barge-in costs
         * one repeated sentence, a false one costs the conversation. The way
         * to interrupt reliably on a loudspeaker is the tap, not a smaller
         * number here.
         */
        const val BARGE_IN_FACTOR = 1.8

        /**
         * Margin over a leak that has only been *glimpsed*.
         *
         * Applies until a loud-reference frame has let the gate measure the
         * room properly. All it has then is the warmup window: 0.3 s of the
         * utterance's opening, which is its quietest part. The device log of a
         * cut-off greeting gives the ratio directly — the warmup measured 1359
         * and the same turn leaked 2985 a few seconds later, so the opening
         * understated the leak by 2.2x. Three is above that and still finite:
         * a shout over a greeting that has barely started is a rare way to
         * interrupt, and the tap is always there.
         */
        const val UNCALIBRATED_FACTOR = 3.0

        /**
         * Margin over the predicted leak before the gate gets suspicious
         * enough to duck the speaker.
         *
         * Everything that protects the assistant from its own voice sits in
         * the accept test; this number only decides when to take the speaker
         * out of the microphone's way so that test can be answered honestly.
         *
         * It was 1.2, just above the prediction, on the grounds that a duck is
         * cheap. It is cheap but it is not free: at 1.2 the assistant's own
         * leak tripped it roughly every two seconds — measured ratios of 1.45
         * to 2.3 against the prediction — and a greeting delivered with a dip
         * in it every two seconds is heard as pulsing. The real cases measured
         * 4.0 and 11.9 times their prediction, so two leaves them intact and
         * removes most of the rest.
         */
        const val SUSPECT_FACTOR = 2.0

        /** Consecutive suspect frames before ducking. 100 ms at 20 ms a frame. */
        const val SUSPECT_FRAMES = 5

        /**
         * Consecutive qualifying frames before a barge-in is accepted *while
         * the speaker is ducked*: 400 ms, which is longer than the echo
         * canceller's recovery and far shorter than a spoken question.
         */
        const val DUCKED_BARGE_IN_FRAMES = 20

        /**
         * Absolute floor. Everything must clear this, so room tone, wind and
         * a rod tapping the rail cannot open the gate however quiet the
         * speaker is.
         *
         * Only a floor under [NOISE_MARGIN]'s floor, for a room so silent
         * that handling noise would otherwise qualify as a voice.
         *
         * It was briefly 4500, set from one room on one Galaxy A35 — which
         * is not a number that can be right on a second phone. Microphone
         * sensitivity and input gain differ between handsets by more than
         * rooms differ between quiet and noisy, so an absolute level tuned
         * on one device is tuned against every other. The bar is now a
         * multiple of the room this phone is actually in, and this constant
         * is back to being a safety minimum rather than the decision.
         */
        const val MIN_BARGE_IN_RMS = 1200.0

        /**
         * 20 ms frames, so six is 120 ms of sustained sound. Shorter than the
         * ten it replaces because the reference now does most of the
         * discriminating: the length was carrying that job alone before, and
         * 200 ms of delay is audible when you are trying to cut in.
         */
        const val BARGE_IN_FRAMES = 6

        /**
         * Below this playback level the speaker is effectively silent, so
         * microphone energy is attributed to the room. Gaps between words sit
         * here, which is what makes interruption prompt.
         */
        const val QUIET_REFERENCE = 0.08f

        /**
         * How fast the reference envelope falls, per 20 ms frame.
         *
         * 0.80 takes about eleven frames — 220 ms — to drop tenfold, which
         * covers the microphone's lag behind the speaker and the room's
         * ring-out. Lower and gaps between words reopen the hole this closes;
         * much higher and a genuine pause takes too long to be usable for
         * interruption.
         */
        const val REFERENCE_RELEASE = 0.80

        /**
         * Corner of the high-pass that defines "the speech band".
         *
         * 300 Hz is the bottom of the telephone band and sits above where
         * wind noise, handling rumble and engine drone keep their energy. A
         * voice loses little across it; those lose most of what makes them
         * loud.
         */
        const val HIGH_PASS_HZ = 300.0

        /**
         * How much of a frame must survive that filter to be treated as a
         * voice.
         *
         * Wind across a microphone is overwhelmingly low frequency and lands
         * far below this; speech, even a quiet one, keeps most of its energy
         * above the corner. This is the test that stops a gust interrupting
         * the assistant, which no level threshold can do — a gust is simply
         * loud.
         */
        const val MIN_SPEECH_RATIO = 0.45

        /**
         * How far above the room's own noise a voice must sit.
         *
         * The one number that has to hold across devices, and a ratio can:
         * it says "clearly louder than this room", which means the same
         * thing on every phone, where "4500" does not.
         */
        const val NOISE_MARGIN = 2.2

        /** How fast the room estimate climbs. Slow, so a voice cannot lift it. */
        const val ROOM_RISE = 0.002

        /** One diagnostic line a second at 20 ms frames. */
        private const val DIAGNOSTIC_EVERY_FRAMES = 50

        /**
         * The reference must be at least this loud before `rms / ref` is
         * used to learn the leak.
         *
         * Higher than [QUIET_REFERENCE], which is a *decision* threshold, and
         * for a different reason: this one is numerical. Learning at a
         * reference of 0.08 divides by a small number, and a single frame of
         * ordinary room noise then taught a gain nine times too high —
         * measured on a Galaxy A35 as an expected echo of 20 001 against a
         * microphone actually reading 2 234, which put the bar at 36 002 and
         * made barge-in impossible for anyone.
         */
        const val CALIBRATION_REFERENCE = 0.30f

        /**
         * How fast the learned gain drifts down, per 20 ms frame.
         *
         * 0.99 halves it in about 70 frames, 1.4 s. The 0.999 it replaces
         * took twenty seconds to forget a bad estimate, which is most of a
         * conversation.
         */
        const val ECHO_GAIN_DECAY = 0.99

        /**
         * Loud-reference frames spent measuring the leak before anything can
         * be judged against it: 200 ms of actual playback.
         *
         * Unlike the wall-clock warm-up it replaces, this does not run during
         * the quiet lead-in of a turn, so it never delays an interruption
         * made in a pause.
         */
        const val CALIBRATION_FRAMES = 10

        /**
         * Frames at the start of a spoken turn spent measuring the leak and
         * admitting nothing: 300 ms.
         *
         * This existed, was removed in favour of the playback reference, and
         * is back because the device said so — the reference is accurate
         * about what the speaker is doing and silent about what the
         * microphone is still hearing, and the gap between those is where
         * every false interruption lived. The cost is a barge-in in the first
         * 300 ms of a reply, and the angler simply speaks again.
         */
        const val WARMUP_FRAMES = 15
    }
}
