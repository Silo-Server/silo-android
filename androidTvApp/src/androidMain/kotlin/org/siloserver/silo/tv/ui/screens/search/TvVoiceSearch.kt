package org.siloserver.silo.tv.ui.screens.search

import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Voice search on TV, spoken into the remote.
 *
 * This deliberately hands off to the system recogniser rather than recording
 * anything itself. On a Shield the recogniser listens through the remote's
 * microphone, which is the hardware the viewer expects to be talking into, and
 * because the recording happens in that app rather than this one Silo needs no
 * RECORD_AUDIO permission at all — nothing here can listen, only ask.
 *
 * The remote's own mic BUTTON cannot be used to start this: Android TV binds it
 * to the system assistant before any app sees it. An on-screen affordance is
 * the only way an app can offer voice, which is why the mic lives beside the
 * search field.
 */
internal class TvVoiceSearchController(
    /**
     * False when no recogniser is installed, which is ordinary on a bare AOSP
     * TV box. Callers hide the affordance rather than offering a button that
     * cannot do anything.
     */
    val isAvailable: Boolean,
    private val launch: () -> Boolean,
    private val onUnavailable: () -> Unit,
) {
    fun start() {
        // Availability was resolved earlier and can be wrong by now — the
        // recogniser may have been disabled or uninstalled since. Say so
        // instead of doing nothing: a visible mic that silently ignores a
        // press is the worst outcome for someone who does not know what an
        // intent is.
        if (!isAvailable || !launch()) onUnavailable()
    }
}

@Composable
internal fun rememberTvVoiceSearch(
    prompt: String,
    onResult: (String) -> Unit,
    onUnavailable: () -> Unit,
): TvVoiceSearchController {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)

    // Resolved once. Installing a recogniser mid-session is not a case worth
    // recomposing for, and re-querying the package manager on every frame is.
    val isAvailable = remember(context) { isTvSpeechRecognitionAvailable(context) }
    val recognizerPackage = remember(context) { preferredRecognizerPackage(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            // The list is ordered by confidence, so the first entry is the
            // recogniser's own best guess. Silo has no better way to choose
            // between alternates than the engine that produced them.
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        // A cancelled or empty recognition must not wipe a query the viewer
        // already typed.
        if (spoken.isNotEmpty()) currentOnResult(spoken)
    }

    return remember(isAvailable, prompt, recognizerPackage, launcher) {
        TvVoiceSearchController(
            isAvailable = isAvailable,
            launch = {
                // Narrow, and reported. A blanket runCatching here swallowed
                // every reason a launch could fail and left the caller unable
                // to tell success from silence.
                try {
                    launcher.launch(tvSpeechRecognizerIntent(prompt, recognizerPackage))
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TvVoiceSearchTag, "No activity accepted the speech recognition intent", e)
                    false
                }
            },
            onUnavailable = { currentOnUnavailable() },
        )
    }
}

private const val TvVoiceSearchTag = "TvVoiceSearch"

/**
 * Which package should service the recognition request, or null to leave it to
 * the system.
 *
 * More than one activity commonly claims this intent — a Google TV Streamer
 * offers both the TV search app and the text-to-speech package — and with no
 * default the launch becomes a disambiguation chooser. Asking someone to pick
 * an app with a remote before they can say a film title is not voice search.
 *
 * The order matters and is not the obvious one. The device's configured
 * VOICE_RECOGNITION_SERVICE names a service for programmatic recognition, not
 * necessarily the best ACTIVITY to show someone: on a Streamer it points at the
 * text-to-speech package, whose activity is not the ten-foot voice UI anyone
 * wants. The voice-interaction/assistant package is the system's designated
 * spoken front end, and on a TV that is the one with the microphone UI built
 * for a remote. So it is asked first, and the recognition service only after.
 *
 * When nothing matches, null leaves the intent implicit and the system shows
 * its chooser — worse, but honest, and better than silently picking whichever
 * handler happened to be listed first.
 */
private fun preferredRecognizerPackage(context: Context): String? {
    val candidates = context.packageManager.queryIntentActivities(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
        PackageManager.MATCH_DEFAULT_ONLY,
    )
    if (candidates.size <= 1) {
        return candidates.firstOrNull()?.activityInfo?.packageName
    }
    val resolver = context.contentResolver
    val preferred = listOf(
        "voice_interaction_service",
        "assistant",
        // Read by key: the constant is not public API.
        "voice_recognition_service",
    ).mapNotNull { key ->
        Settings.Secure.getString(resolver, key)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
    }
    return preferred.firstOrNull { pkg ->
        candidates.any { it.activityInfo?.packageName == pkg }
    }
}

private fun tvSpeechRecognizerIntent(prompt: String, recognizerPackage: String?): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        recognizerPackage?.let(::setPackage)
        // Free-form rather than web search: these are film, series and book
        // titles, not queries, and the web-search model rewrites them toward
        // whatever it thinks you meant to google.
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        // EXTRA_MAX_RESULTS is deliberately unset. Only the first result is
        // used either way, and leaving the cap off asks nothing unusual of a
        // third-party recogniser.
        //
        // EXTRA_LANGUAGE is deliberately unset too. Unset means the device's
        // own speech locale, which is what a household actually configured;
        // pinning the app's UI locale would make an English UI work and break
        // a family that speaks Dutch.
    }

/**
 * Whether anything on this device can handle a recognition request.
 *
 * Needs the matching `<queries>` element in the manifest — from Android 11 an
 * app cannot see packages it has not declared an interest in, so without it
 * this returns false on every modern device and the mic silently never appears.
 */
private fun isTvSpeechRecognitionAvailable(context: Context): Boolean =
    // resolveActivity, not queryIntentActivities(intent, 0). The latter also
    // returns handlers whose filter lacks CATEGORY_DEFAULT, which
    // startActivityForResult will not launch — so the mic could appear for a
    // recogniser that cannot actually be started.
    //
    // SpeechRecognizer.isRecognitionAvailable is not the check either: it
    // reports a recognition SERVICE, and what this needs is an exported
    // ACTIVITY. A device can have one without the other.
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .resolveActivity(context.packageManager) != null
