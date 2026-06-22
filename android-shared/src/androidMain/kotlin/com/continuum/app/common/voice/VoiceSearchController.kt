package com.continuum.app.common.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Live, programmatic voice dictation over [android.speech.SpeechRecognizer].
 *
 * Lives in android-shared so the phone search can reuse it. Uses the streaming
 * [RecognitionListener] API (NOT `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
 * `startActivityForResult`) so partial transcripts arrive while the user is
 * still speaking — onTV the in-process listener is the only way to wire the mic
 * into the existing search field without bouncing to a system activity.
 *
 * [SpeechRecognizer] is main-thread affine: construct, [start], [cancel], and
 * [destroy] must all run on the main/UI thread. The Compose call sites already
 * satisfy this (composition + DisposableEffect run on the main thread).
 *
 * Observe [state] and [level] from Compose; they are backed by snapshot state.
 * Caller MUST invoke [destroy] when the controller is no longer needed.
 */
class VoiceSearchController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) {
    /** Coarse lifecycle the UI renders against. */
    sealed interface VoiceState {
        /** Not listening — idle, or quietly reset after a no-match/timeout. */
        data object Idle : VoiceState

        /** Actively capturing audio; [level] is meaningful while in this state. */
        data object Listening : VoiceState

        /** Terminal error the UI may surface; carries a coarse [reason]. */
        data class Error(val reason: Reason) : VoiceState
    }

    /** Coarse, UI-actionable error buckets mapped from the raw recognizer codes. */
    enum class Reason {
        /** Mic permission missing — the caller should re-prompt for RECORD_AUDIO. */
        PermissionRequired,

        /** Engine busy / network / audio / other recoverable problem. */
        Recoverable,
    }

    private var _state by mutableStateOf<VoiceState>(VoiceState.Idle)

    /** Observable lifecycle for the UI. */
    val state: State<VoiceState> = object : State<VoiceState> {
        override val value: VoiceState get() = _state
    }

    private var _level by mutableFloatStateOf(0f)

    /**
     * Normalised [0f, 1f] microphone level derived from the recognizer RMS dB
     * signal, for a simple "listening" amplitude indicator.
     */
    val level: State<Float> = object : State<Float> {
        override val value: Float get() = _level
    }

    /** Whether speech recognition is usable on this device at all. */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    // Single automatic retry budget for a transient RECOGNIZER_BUSY, reset on
    // each fresh start() so a later busy can still retry once.
    private var retriedBusy = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state = VoiceState.Listening
        }

        override fun onBeginningOfSpeech() {
            _state = VoiceState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer reports RMS roughly in [-2, 10] dB; clamp+scale to
            // a 0..1 amplitude for the indicator.
            _level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _level = 0f
        }

        override fun onError(error: Int) {
            _level = 0f
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Nothing heard — quiet reset, no error surfaced to the user.
                    _state = VoiceState.Idle
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _state = VoiceState.Error(Reason.PermissionRequired)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (!retriedBusy) {
                        retriedBusy = true
                        cancel()
                        start()
                    } else {
                        _state = VoiceState.Error(Reason.Recoverable)
                    }
                }

                else -> {
                    _state = VoiceState.Error(Reason.Recoverable)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            _level = 0f
            val text = results.firstTranscript()
            if (text != null) onFinal(text)
            _state = VoiceState.Idle
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstTranscript()
            if (text != null) onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Begin a dictation session. No-op when recognition is unavailable. Safe to
     * call repeatedly — an in-flight session is cancelled first.
     */
    fun start() {
        if (!isAvailable) {
            _state = VoiceState.Error(Reason.Recoverable)
            return
        }
        cancel()
        retriedBusy = false
        val sr = createRecognizer().also { recognizer = it }
        sr.setRecognitionListener(listener)
        sr.startListening(buildIntent())
        _state = VoiceState.Listening
    }

    /** Stop listening and discard any in-flight session, returning to Idle. */
    fun cancel() {
        _level = 0f
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        if (_state is VoiceState.Listening) _state = VoiceState.Idle
    }

    /** Release the recognizer. Call from the owning DisposableEffect's onDispose. */
    fun destroy() {
        _level = 0f
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        _state = VoiceState.Idle
    }

    private fun createRecognizer(): SpeechRecognizer =
        // isOnDeviceRecognitionAvailable / createOnDeviceSpeechRecognizer:
        // the availability probe is API 33+, so gate the whole on-device path
        // on TIRAMISU. Below 33 (e.g. the Shield's typical API 30) fall back to
        // the network recognizer, which the Shield's Google services provide.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun Bundle?.firstTranscript(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}
