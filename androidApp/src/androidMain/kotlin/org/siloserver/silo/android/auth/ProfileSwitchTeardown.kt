package org.siloserver.silo.android.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.model.profile.ActiveProfileStore

/**
 * The phone's "Switch Profile", shared by the profile menu and Settings.
 *
 * Settings writes wait in the flusher's debounce for a moment, and the flusher
 * drops a write once its profile is no longer active. So the pending writes are
 * pushed first, while the profile they were made on is still the active one
 * (the TV switch does the same). Then the shell is left and the per-profile
 * caches are dropped, or the next profile keeps rendering, and writing back,
 * the previous one's values.
 */
class ProfileSwitchTeardown(
    private val playerSettingsStore: PlayerSettingsStore,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val activeProfileStore: ActiveProfileStore,
    private val cardPresentationStore: CardPresentationStore,
    private val seekIntervalStore: SeekIntervalStore,
    private val titleArtStore: TitleArtStore,
    private val episodeSpoilerStore: EpisodeSpoilerStore,
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
) {
    private val _switching = MutableStateFlow(false)

    /** True while a switch is pushing settings; the shell shows progress and blocks input. */
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /**
     * Push pending settings, then call [leaveShell] and drop per-profile state.
     *
     * The push is bounded: against an unreachable server each write can wait
     * out a connect timeout, and the switch must not hang on that. A write
     * still unsent when the bound passes stays queued under the old profile,
     * as it would have without this flush.
     *
     * A request made while a switch is already running is ignored, so a
     * second tap during the push cannot open a second profile picker.
     * Returns whether this call performed the switch.
     */
    suspend fun switchProfile(leaveShell: () -> Unit): Boolean {
        if (!_switching.compareAndSet(expect = false, update = true)) return false
        try {
            withTimeoutOrNull(flushTimeoutMs) { playerSettingsStore.flushPendingDeviceSettings() }
            // Navigate before clearing: clearing while the shell is still composed
            // repaints it with default cards behind the picker.
            leaveShell()
            overlayPrefsStore.clear()
            activeProfileStore.reset()
            cardPresentationStore.clear()
            seekIntervalStore.clear()
            titleArtStore.clear()
            episodeSpoilerStore.clear()
        } finally {
            _switching.value = false
        }
        return true
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
