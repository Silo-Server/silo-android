package org.siloserver.silo.tv.ui.screens.search

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    val startRecognition = {
        recognizer?.destroy()
        dialog?.dismiss()
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        val listeningDialog = AlertDialog.Builder(context)
            .setTitle("Voice search")
            .setMessage(prompt)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                speech.destroy()
                recognizer = null
            }
            .create()
        dialog = listeningDialog
        listeningDialog.setOnCancelListener {
            speech.destroy()
            recognizer = null
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i("TvVoiceSearch", "Recognition ready")
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let(listeningDialog::setMessage)
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                listeningDialog.dismiss()
                speech.destroy()
                recognizer = null
                if (spoken.isNotEmpty()) currentOnResult(spoken)
            }
            override fun onError(error: Int) {
                Log.w("TvVoiceSearch", "Recognition failed: $error")
                speech.destroy()
                recognizer = null
                listeningDialog.setMessage(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Didn't hear a title. Close and try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Couldn't connect to voice search. Check your connection and try again."
                    else -> "Voice search couldn't start. Close and try again."
                })
            }
        })
        listeningDialog.show()
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecognition() else currentOnUnavailable()
    }
    DisposableEffect(lifecycleOwner) {
        fun stopRecognition() {
            recognizer?.destroy()
            recognizer = null
            dialog?.dismiss()
            dialog = null
        }
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
        }
        true
    }, onUnavailable)
}
