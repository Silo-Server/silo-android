package org.siloserver.silo.common.settings

import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.LibraryPlaybackPref
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlaySchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerDrivenConfigRefresherTest {

    @Test
    fun `refreshIfStale skips when no authenticated profile is active`() = runTest {
        val overlay = FakeOverlayPrefsStore()
        val cards = FakeCardPresentationStore()
        val library = FakeLibraryPlaybackPrefsStore()
        val player = FakePlayerSettingsStore()
        val refresher = ServerDrivenConfigRefresher(
            overlayPrefsStore = overlay,
            cardPresentationStore = cards,
            libraryPlaybackPrefsStore = library,
            playerSettingsStore = player,
            hasAuthenticatedProfile = { false },
            nowMs = { 1_000L },
        )

        val refreshed = refresher.refreshIfStale()

        assertFalse(refreshed)
        assertEquals(0, overlay.refreshCalls)
        assertEquals(0, cards.refreshCalls)
        assertEquals(0, library.refreshCalls)
        assertEquals(0, player.refreshCalls)
    }

    @Test
    fun `refreshIfStale refreshes all server-driven stores on first foreground`() = runTest {
        val overlay = FakeOverlayPrefsStore()
        val cards = FakeCardPresentationStore()
        val library = FakeLibraryPlaybackPrefsStore()
        val player = FakePlayerSettingsStore()
        val refresher = ServerDrivenConfigRefresher(
            overlayPrefsStore = overlay,
            cardPresentationStore = cards,
            libraryPlaybackPrefsStore = library,
            playerSettingsStore = player,
            hasAuthenticatedProfile = { true },
            nowMs = { 1_000L },
        )

        val refreshed = refresher.refreshIfStale()

        assertTrue(refreshed)
        assertEquals(1, overlay.refreshCalls)
        assertEquals(1, cards.refreshCalls)
        assertEquals(1, library.refreshCalls)
        assertEquals(1, player.refreshCalls)
    }

    @Test
    fun `refreshIfStale throttles repeated foreground refreshes`() = runTest {
        var now = 1_000L
        val overlay = FakeOverlayPrefsStore()
        val cards = FakeCardPresentationStore()
        val library = FakeLibraryPlaybackPrefsStore()
        val player = FakePlayerSettingsStore()
        val refresher = ServerDrivenConfigRefresher(
            overlayPrefsStore = overlay,
            cardPresentationStore = cards,
            libraryPlaybackPrefsStore = library,
            playerSettingsStore = player,
            hasAuthenticatedProfile = { true },
            nowMs = { now },
        )

        assertTrue(refresher.refreshIfStale(minIntervalMs = 30_000L))
        now += 5_000L
        assertFalse(refresher.refreshIfStale(minIntervalMs = 30_000L))
        now += 30_000L
        assertTrue(refresher.refreshIfStale(minIntervalMs = 30_000L))

        assertEquals(2, overlay.refreshCalls)
        assertEquals(2, cards.refreshCalls)
        assertEquals(2, library.refreshCalls)
        assertEquals(2, player.refreshCalls)
    }

    @Test
    fun `forceRefresh bypasses throttle for reconnect edge`() = runTest {
        val overlay = FakeOverlayPrefsStore()
        val cards = FakeCardPresentationStore()
        val library = FakeLibraryPlaybackPrefsStore()
        val player = FakePlayerSettingsStore()
        val refresher = ServerDrivenConfigRefresher(
            overlayPrefsStore = overlay,
            cardPresentationStore = cards,
            libraryPlaybackPrefsStore = library,
            playerSettingsStore = player,
            hasAuthenticatedProfile = { true },
            nowMs = { 1_000L },
        )

        assertTrue(refresher.refreshIfStale())
        assertTrue(refresher.forceRefresh())

        assertEquals(2, overlay.refreshCalls)
        assertEquals(2, cards.refreshCalls)
        assertEquals(2, library.refreshCalls)
        assertEquals(2, player.refreshCalls)
    }
}

private class FakeOverlayPrefsStore : OverlayPrefsStore {
    override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val prefs: StateFlow<CardOverlayPrefs> = MutableStateFlow(OverlaySchema.buildDefaults())
    override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    override val hasUserOverride: Boolean = false
    var refreshCalls = 0

    override suspend fun hydrateIfNeeded() = Unit
    override suspend fun refresh() {
        refreshCalls++
    }
    override fun setPrefs(next: CardOverlayPrefs) = Unit
    override suspend fun resetToDefaults() = Unit
    override fun clear() = Unit
}

private class FakeCardPresentationStore : CardPresentationStore {
    override val state: StateFlow<CardPresentationUiState> = MutableStateFlow(CardPresentationUiState())
    override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    var refreshCalls = 0

    override suspend fun hydrateIfNeeded() = Unit
    override suspend fun refresh() {
        refreshCalls++
    }
    override fun set(
        presentation: org.siloserver.silo.model.settings.CardPresentation,
        deviceOnly: Boolean,
    ) = Unit
    override suspend fun clearDeviceOverride() = Unit
    override suspend fun useProfileDefault() = Unit
    override fun clear() = Unit
}

private class FakeLibraryPlaybackPrefsStore : LibraryPlaybackPrefsStore {
    override val libraryPrefs: StateFlow<Map<Int, LibraryPlaybackPref>> = MutableStateFlow(emptyMap())
    override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    var refreshCalls = 0

    override suspend fun hydrateIfNeeded() = Unit
    override suspend fun refresh() {
        refreshCalls++
    }
    override fun pref(libraryId: Int): LibraryPlaybackPref? = null
    override suspend fun setPref(
        libraryId: Int,
        audioLanguage: String?,
        subtitleLanguage: String?,
        subtitleMode: String?,
        showForcedSubtitles: Boolean?,
    ): ApiResult<Unit> = ApiResult.Success(Unit)
    override suspend fun deletePref(libraryId: Int): ApiResult<Unit> = ApiResult.Success(Unit)
    override fun clear() = Unit
}

private class FakePlayerSettingsStore : PlayerSettingsStore {
    override val introSkipModeFlow: Flow<IntroSkipMode> = flowOf(IntroSkipMode.ASK)
    override val autoSkipCreditsFlow: Flow<Boolean> = flowOf(false)
    override val autoPlayNextFlow: Flow<Boolean> = flowOf(true)
    override val hdrEnabledFlow: Flow<Boolean> = flowOf(true)
    override val dvProfile7HDR10FallbackFlow: Flow<Boolean> = flowOf(true)
    override val dolbyVisionEnabledFlow: Flow<Boolean> = flowOf(true)
    override val matchContentFrameRateFlow: Flow<Boolean> = flowOf(false)
    override val subtitleMatchesDeviceFlow: Flow<Boolean> = flowOf(false)
    override val showAudiobooksFlow: Flow<Boolean> = flowOf(false)
    override val effectiveSubtitleAppearanceFlow: Flow<org.siloserver.silo.model.settings.SubtitleAppearance> =
        flowOf(org.siloserver.silo.model.settings.SubtitleAppearance.DEFAULT)
    override val pictureInPictureEnabledFlow: Flow<Boolean> = flowOf(true)
    override val downloadsWifiOnlyFlow: Flow<Boolean> = flowOf(true)
    override val keepWatchedDownloadsFlow: Flow<Boolean> = flowOf(false)
    override val defaultDownloadQualityFlow: Flow<String> = flowOf("original")
    override val playbackSpeedFlow: Flow<Double> = flowOf(1.0)
    override val audioSyncMsFlow: Flow<Int> = flowOf(0)
    override val subtitleSyncMsFlow: Flow<Int> = flowOf(0)
    override val nextUpPromptSecondsFlow: Flow<Int> = flowOf(30)
    override val sleepTimerDefaultMinutesFlow: Flow<Int> = flowOf(30)
    override val resumeRewindSecondsFlow: Flow<Int> = flowOf(7)
    override val passOutThresholdFlow: Flow<Int> = flowOf(3)
    override val preferredQualityFlow: Flow<String> = flowOf("auto")
    override val maxBitrateKbpsFlow: Flow<Int?> = flowOf(null)
    override val audioLanguageFlow: Flow<String> = flowOf("")
    override val videoGravityFlow: Flow<String> = flowOf("fit")
    override val orientationModeFlow: Flow<String> = flowOf("auto")
    override val subtitleAppearanceFlow: Flow<SubtitleAppearance> = flowOf(SubtitleAppearance.DEFAULT)
    override val subtitleUsesDeviceOverrideFlow: Flow<Boolean> = flowOf(false)
    var refreshCalls = 0

    override suspend fun setIntroSkipMode(value: IntroSkipMode) = Unit
    override suspend fun setAutoSkipCredits(value: Boolean) = Unit
    override suspend fun setAutoPlayNext(value: Boolean) = Unit
    override suspend fun setHdrEnabled(value: Boolean) = Unit
    override suspend fun setDvProfile7HDR10Fallback(value: Boolean) = Unit
    override suspend fun setDolbyVisionEnabled(value: Boolean) = Unit
    override suspend fun setMatchContentFrameRate(value: Boolean) = Unit
    override suspend fun setSubtitleMatchesDevice(enabled: Boolean) = Unit
    override suspend fun setShowAudiobooks(enabled: Boolean) = Unit
    override suspend fun setPictureInPictureEnabled(value: Boolean) = Unit
    override suspend fun setDownloadsWifiOnly(value: Boolean) = Unit
    override suspend fun setKeepWatchedDownloads(value: Boolean) = Unit
    override suspend fun setDefaultDownloadQuality(value: String) = Unit
    override suspend fun setPlaybackSpeed(value: Double) = Unit
    override suspend fun setAudioSyncMs(value: Int) = Unit
    override suspend fun setSubtitleSyncMs(value: Int) = Unit
    override suspend fun setNextUpPromptSeconds(value: Int) = Unit
    override suspend fun setSleepTimerDefaultMinutes(value: Int) = Unit
    override suspend fun setResumeRewindSeconds(value: Int) = Unit
    override suspend fun setPassOutThreshold(value: Int) = Unit
    override suspend fun setPreferredQuality(value: String) = Unit
    override suspend fun setQuality(resolution: String, bitrateKbps: Int?) = Unit
    override suspend fun setAudioLanguage(value: String) = Unit
    override suspend fun setVideoGravity(value: String) = Unit
    override suspend fun setOrientationMode(value: String) = Unit
    override suspend fun setSubtitleAppearance(value: SubtitleAppearance) = Unit
    override suspend fun flushProjectedSubtitleAppearance() = Unit
    override suspend fun refreshFromServer() {
        refreshCalls++
    }
    override suspend fun setSubtitleDeviceOverrideEnabled(enabled: Boolean) = Unit
    override suspend fun resetDeviceSetting(key: String) = Unit
    override suspend fun resetAllDeviceSettings() = Unit
    override suspend fun flushPendingDeviceSettings() = Unit
}
