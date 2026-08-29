package org.siloserver.silo.common.settings

import android.os.SystemClock

/**
 * Best-effort refresh coordinator for server-owned client configuration.
 *
 * Android screens mostly consume cached flows, so foregrounding the app needs
 * one central refresh edge to pick up changes made by the web UI or another
 * client. This mirrors the Apple-side foreground/reconnect refresh pattern
 * without making every screen remember to call its own store.
 */
class ServerDrivenConfigRefresher(
    private val overlayPrefsStore: OverlayPrefsStore,
    private val cardPresentationStore: CardPresentationStore,
    private val libraryPlaybackPrefsStore: LibraryPlaybackPrefsStore,
    private val playerSettingsStore: PlayerSettingsStore,
    private val hasAuthenticatedProfile: suspend () -> Boolean,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    @Volatile
    private var lastRefreshAtMs: Long? = null

    suspend fun refreshIfStale(
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    ): Boolean = refresh(force = false, minIntervalMs = minIntervalMs)

    suspend fun forceRefresh(): Boolean =
        refresh(force = true, minIntervalMs = DEFAULT_MIN_INTERVAL_MS)

    private suspend fun refresh(force: Boolean, minIntervalMs: Long): Boolean {
        if (!hasAuthenticatedProfile()) return false
        val now = nowMs()
        val last = lastRefreshAtMs
        if (!force && last != null && now - last < minIntervalMs) return false

        overlayPrefsStore.refresh()
        cardPresentationStore.refresh()
        libraryPlaybackPrefsStore.refresh()
        playerSettingsStore.refreshFromServer()
        lastRefreshAtMs = now
        return true
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS: Long = 30_000L
    }
}
