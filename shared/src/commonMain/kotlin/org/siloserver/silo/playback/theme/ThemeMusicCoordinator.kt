package org.siloserver.silo.playback.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.ThemeSelection
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Decides when detail pages play theme music, for one app-wide
 * [ThemeMusicPlayer] shared by every page.
 *
 * A page calls [show] while it is on screen with its loaded selection and
 * [hide] when it leaves or the app goes to the background. Leaving fades the
 * theme out and pauses it; it stops after [RELEASE_GRACE_MILLIS] unless a page
 * shows the same theme again (a season that inherits its series' set, or the
 * app returning to the foreground), which continues it in place.
 *
 * Nothing plays unless the theme music setting is on and the server reports
 * the capability available (read again once [CAPABILITY_TTL_MILLIS] old), and
 * a theme never starts or resumes over other audio playing on the device.
 * Confined to the main thread.
 */
class ThemeMusicCoordinator(
    private val scope: CoroutineScope,
    private val player: ThemeMusicPlayer,
    /** Whether the active server serves themes to this profile; null when the read failed. */
    private val capability: suspend () -> Boolean?,
    /**
     * Whether the device is playing audio (another app, an audiobook, a video).
     * Asked only while no theme is audible, so the theme itself never counts.
     */
    private val otherAudioActive: () -> Boolean = { false },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var enabled = false
    private var loop = false
    private var available: Boolean? = null
    private var availableAt: TimeMark? = null
    private var capabilityRead: Job? = null

    /** Bumped by [reset]; a capability answer for an earlier identity is dropped. */
    private var identity = 0
    private var page: Any? = null
    private var selection: ThemeSelection? = null
    private var release: Job? = null

    fun setPreferences(enabled: Boolean, loop: Boolean) {
        if (this.enabled == enabled && this.loop == loop) return
        this.enabled = enabled
        this.loop = loop
        apply()
    }

    /** [owner] is on screen; [selection] is its themes, null until its detail loads. */
    fun show(owner: Any, selection: ThemeSelection?) {
        release?.cancel()
        release = null
        page = owner
        this.selection = selection
        apply()
    }

    /** [owner] left the screen. Ignored when another page has shown since. */
    fun hide(owner: Any) {
        if (page !== owner) return
        page = null
        selection = null
        player.suspend()
        release?.cancel()
        release = scope.launch {
            delay(RELEASE_GRACE_MILLIS)
            if (page == null) stop(immediate = true)
        }
    }

    /**
     * Playback is starting: end the theme at once, so backing out of the
     * player starts it over instead of resuming it.
     */
    fun interrupt() = stop(immediate = true)

    /** The account, profile or server changed: stop, and ask the next identity again. */
    fun reset() {
        identity += 1
        capabilityRead?.cancel()
        capabilityRead = null
        available = null
        availableAt = null
        stop(immediate = true)
    }

    private fun apply() {
        val current = selection
        if (!enabled || page == null) return stop(immediate = false)
        val answer = available
        val stale = availableAt?.let { it.elapsedNow() >= CAPABILITY_TTL_MILLIS.milliseconds } ?: true
        if (stale) readCapability()
        if (answer != true) return stop(immediate = false)
        if (current?.items.isNullOrEmpty()) return stop(immediate = false)
        if (!player.isAudible && otherAudioActive()) return stop(immediate = false)
        player.select(current, loop)
    }

    private fun readCapability() {
        if (capabilityRead?.isActive == true) return
        val asked = identity
        capabilityRead = scope.launch {
            val answer = capability() ?: return@launch
            if (asked != identity) return@launch
            available = answer
            availableAt = timeSource.markNow()
            apply()
        }
    }

    private fun stop(immediate: Boolean) {
        release?.cancel()
        release = null
        player.stop(immediate)
    }

    companion object {
        /** Long enough to cover a page transition or a quick trip to another app. */
        const val RELEASE_GRACE_MILLIS = 2_000L

        /** How long a capability answer stands, as the web app's query does. */
        const val CAPABILITY_TTL_MILLIS = 60_000L
    }
}
