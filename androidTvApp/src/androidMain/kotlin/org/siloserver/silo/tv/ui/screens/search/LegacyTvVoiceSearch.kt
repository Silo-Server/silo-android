package org.siloserver.silo.tv.ui.screens.search

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.siloserver.silo.common.R as CommonR

/** Android 11 rejects the Google TV activity's in-process recognition request. */
@Composable
internal fun rememberLegacyTvVoiceSearch(
    prompt: String,
    onResult: (String) -> Unit,
    onUnavailable: () -> Unit,
): TvVoiceSearchController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var dialog by remember { mutableStateOf<AlertDialog?>(null) }
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var startupTimeout by remember { mutableStateOf<Runnable?>(null) }

    fun cancelStartupTimeout() {
        startupTimeout?.let(handler::removeCallbacks)
        startupTimeout = null
    }

    fun releaseRecognizer() {
        cancelStartupTimeout()
        val speech = recognizer
        recognizer = null
        try {
            speech?.destroy()
        } catch (e: IllegalArgumentException) {
            // A failed bind may leave no registered connection to unbind.
            Log.w("TvVoiceSearch", "Recognition service was not bound", e)
        }
    }

    fun stopRecognition() {
        releaseRecognizer()
        dialog?.dismiss()
        dialog = null
    }

    val startRecognition = {
        stopRecognition()
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        val listeningDialog = AlertDialog.Builder(context)
            .setTitle(CommonR.string.search_voiceDialogTitleTv)
            .setMessage(prompt)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                stopRecognition()
            }
            .create()
        dialog = listeningDialog
        listeningDialog.setOnCancelListener {
            stopRecognition()
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer !== speech) return
                cancelStartupTimeout()
                Log.i("TvVoiceSearch", "Recognition ready")
            }
            override fun onBeginningOfSpeech() {
                if (recognizer === speech) cancelStartupTimeout()
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== speech) return
                cancelStartupTimeout()
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let(listeningDialog::setMessage)
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                stopRecognition()
                if (spoken.isNotEmpty()) currentOnResult(spoken)
            }
            override fun onError(error: Int) {
                if (recognizer !== speech) return
                Log.w("TvVoiceSearch", "Recognition failed: $error")
                releaseRecognizer()
                listeningDialog.setMessage(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        context.getString(CommonR.string.search_voiceNoMatchTv)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        context.getString(CommonR.string.search_voiceNetworkErrorTv)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            context.getString(CommonR.string.search_voiceMicPermissionTv)
                        } else {
                            context.getString(CommonR.string.search_voiceServiceMicDeniedTv)
                        }
                    else -> context.getString(CommonR.string.search_voiceStartFailedTv)
                })
            }
        })
        listeningDialog.show()
        // Android 11 can drop binding errors before installing the listener.
        val timeout = Runnable {
            if (recognizer === speech) {
                stopRecognition()
                currentOnUnavailable()
            }
        }
        startupTimeout = timeout
        handler.postDelayed(timeout, 10_000L)
        try {
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
            true
        } catch (e: SecurityException) {
            Log.w("TvVoiceSearch", "Recognition service access denied", e)
            stopRecognition()
            false
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (!startRecognition()) currentOnUnavailable()
        } else {
            dialog = AlertDialog.Builder(context)
                .setTitle(CommonR.string.search_voiceDialogTitleTv)
                .setMessage(CommonR.string.search_voiceMicPermissionTv)
                .setPositiveButton(android.R.string.ok) { _, _ -> dialog = null }
                .setOnCancelListener { dialog = null }
                .show()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopRecognition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopRecognition()
        }
    }
    return TvVoiceSearchController(available, {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecognition()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            true
        }
    }, { currentOnUnavailable() })
}
