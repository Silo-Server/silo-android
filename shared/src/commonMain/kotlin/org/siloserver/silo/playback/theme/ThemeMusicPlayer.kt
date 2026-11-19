package org.siloserver.silo.playback.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.ThemeSelection

/**
 * Where to play one theme. [url] is a short-lived credential: never log or
 * persist it, so [toString] leaves it out. A [converted] theme is a progressive
 * stream with no byte ranges, so it cannot loop by seeking back to its start.
 */
class ThemeGrant(val url: String, val converted: Boolean) {
    override fun toString(): String = "ThemeGrant(converted=$converted)"
}

/** One theme's audio output, already pointed at its grant URL. */
interface ThemeAudio {
    var volume: Float
    fun setLooping(loop: Boolean)
    fun play()
    fun pause()
    fun release()

    interface Listener {
        /** Audio became audible: first started, or resumed after a pause. */
        fun onPlaying()
        fun onEnded()
        fun onError()

        /** Something else took the audio output for good (a video, another app's music). */
        fun onInterrupted()
    }
}

/**
 * Owns the theme audio of the detail page on screen, the way the web app's
 * `ThemeMusic` class does: plays the first theme of a selection, fades in and
 * out, keeps playing (or resumes in place) when the next selection names the
 * same theme, loops an original in place, and replays a converted theme with
 * a fresh grant. Signed URLs live only in the [ThemeAudio] it creates.
 *
 * Confined to one thread (the main thread on Android); [scope] runs the grant
 * requests and fades.
 */
class ThemeMusicPlayer(
    private val scope: CoroutineScope,
    /** Null when the server refused or the request failed; themes are optional. */
    private val grant: suspend (ownerId: String, themeId: String) -> ThemeGrant?,
    private val createAudio: (url: String, loop: Boolean, listener: ThemeAudio.Listener) -> ThemeAudio,
) {
    private var audio: ThemeAudio? = null
    private var audioPlaying = false
    private var request: Job? = null
    private var fade: Job? = null
    private var generation = 0
    private var owner = ""
    private var theme = ""
    private var loop = false
    private var converted = false
    private var retry = 0
    private var suspended = false
    /** Themes still fading out after a stop; an immediate stop ends them too. */
    private val retiring = mutableListOf<Job>()

    /** Whether theme audio may be coming out of the device now, a fade-out included. */
    val isAudible: Boolean get() = audioPlaying || retiring.isNotEmpty()

    fun select(selection: ThemeSelection?, loop: Boolean) {
        this.loop = loop
        audio?.setLooping(loop && !converted)
        val song = selection?.items?.firstOrNull()
        if (selection == null || song == null) {
            stop()
            return
        }
        if (owner == selection.ownerId && theme == song.id) {
            if (suspended) {
                suspended = false
                val current = audio
                if (current != null) play(current, generation) else load()
            }
            return
        }
        stop()
        owner = selection.ownerId
        theme = song.id
        retry = 0
        load()
    }

    /** Fades out and pauses, keeping the position for a [select] of the same theme. */
    fun suspend() {
        suspended = true
        val current = audio
        if (current != null) {
            fadeVolume(current, 0f) { if (audio === current && suspended) pause(current) }
        } else {
            stop(immediate = true)
        }
    }

    /** Ends the theme: faded out, or at once when [immediate] (playback is starting). */
    fun stop(immediate: Boolean = false) {
        suspended = false
        generation += 1
        request?.cancel()
        request = null
        owner = ""
        theme = ""
        if (immediate) retiring.toList().forEach { it.cancel() }
        val current = audio
        if (!immediate && current != null && audioPlaying) {
            // Detach it and fade it out on its own job, so the next theme's
            // audio neither cancels this fade nor is what it releases.
            audio = null
            audioPlaying = false
            fade?.cancel()
            fade = null
            lateinit var job: Job
            job = scope.launch {
                try {
                    rampVolume(current, 0f)
                } finally {
                    current.release()
                    retiring.remove(job)
                }
            }
            retiring += job
        } else {
            clearAudio()
        }
    }

    private fun load() {
        val startGeneration = ++generation
        request?.cancel()
        val ownerId = owner
        val themeId = theme
        request = scope.launch {
            val granted = grant(ownerId, themeId) ?: return@launch
            if (startGeneration != generation) return@launch
            clearAudio()
            converted = granted.converted
            var played = false
            lateinit var created: ThemeAudio
            created = createAudio(granted.url, loop && !converted, object : ThemeAudio.Listener {
                override fun onPlaying() {
                    if (startGeneration != generation || audio !== created) return
                    played = true
                    retry = 0
                    if (!suspended) fadeVolume(created, TARGET_VOLUME)
                }

                override fun onEnded() {
                    // A converted stream cannot seek back, and its URL may have
                    // expired, so a loop replays it with a fresh grant. A stream
                    // that ended without playing counts against the same single
                    // retry as an error, so a conversion that fails cleanly
                    // cannot replay forever.
                    if (startGeneration != generation || audio !== created) return
                    audioPlaying = false
                    if (!converted || !loop || suspended) return
                    if (played || retry++ == 0) load()
                }

                override fun onError() {
                    if (startGeneration != generation || audio !== created) return
                    clearAudio()
                    if (!suspended && retry++ == 0) load()
                }

                override fun onInterrupted() {
                    // Playback started or the user chose other audio: the theme
                    // ends here, and a later select starts it over.
                    if (startGeneration != generation || audio !== created) return
                    stop(immediate = true)
                }
            })
            created.volume = 0f
            audio = created
            play(created, startGeneration)
        }
    }

    private fun play(target: ThemeAudio, startGeneration: Int) {
        if (startGeneration != generation || suspended) return
        val wasPlaying = audioPlaying
        target.play()
        audioPlaying = true
        // Still playing (a suspend's fade-out had not paused it yet): fade back
        // in now. Otherwise onPlaying fades in once the audio is audible.
        if (wasPlaying) fadeVolume(target, TARGET_VOLUME)
    }

    private fun pause(target: ThemeAudio) {
        target.pause()
        audioPlaying = false
    }

    private fun fadeVolume(target: ThemeAudio, to: Float, done: (() -> Unit)? = null) {
        fade?.cancel()
        fade = scope.launch {
            rampVolume(target, to)
            done?.invoke()
        }
    }

    private suspend fun rampVolume(target: ThemeAudio, to: Float) {
        val from = target.volume
        for (step in 1..FADE_STEPS) {
            delay(FADE_STEP_MILLIS)
            target.volume = (from + (to - from) * step / FADE_STEPS).coerceIn(0f, 1f)
        }
    }

    private fun clearAudio() {
        fade?.cancel()
        fade = null
        audio?.release()
        audio = null
        audioPlaying = false
    }

    companion object {
        /** Theme music sits under the page, as on the web. */
        const val TARGET_VOLUME = 0.35f
        private const val FADE_STEPS = 12
        private const val FADE_STEP_MILLIS = 25L
    }
}
