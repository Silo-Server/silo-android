package org.siloserver.silo.tv.testing

import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.SubtitleAppearance
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [PlayerSettingsStore] for TV unit tests: every flow is a
 * [MutableStateFlow] a test can read back, and every setter records its own
 * name in [setterCalls] so a test can assert which write path ran.
 *
 * Shared rather than re-declared per test class — the interface is wide enough
 * that a second copy drifts the moment a member is added.
 */
internal class FakePlayerSettingsStore : PlayerSettingsStore {
    val setterCalls = mutableListOf<String>()
    var flushCount = 0

    override val introSkipModeFlow = MutableStateFlow(IntroSkipMode.ASK)
    override val autoSkipCreditsFlow = MutableStateFlow(false)
    override val autoPlayNextFlow = MutableStateFlow(true)
    override val hdrEnabledFlow = MutableStateFlow(true)
    override val dvProfile7HDR10FallbackFlow = MutableStateFlow(false)
    override val dolbyVisionEnabledFlow = MutableStateFlow(true)
    override val matchContentFrameRateFlow = MutableStateFlow(false)
    override val subtitleMatchesDeviceFlow = MutableStateFlow(false)
    override val showAudiobooksFlow = MutableStateFlow(false)
    override val effectiveSubtitleAppearanceFlow =
        MutableStateFlow(org.siloserver.silo.model.settings.SubtitleAppearance.DEFAULT)
    override val pictureInPictureEnabledFlow = MutableStateFlow(true)
    override val downloadsWifiOnlyFlow = MutableStateFlow(true)
    override val keepWatchedDownloadsFlow = MutableStateFlow(false)
    override val defaultDownloadQualityFlow = MutableStateFlow("original")
    override val playbackSpeedFlow = MutableStateFlow(1.0)
    override val audioSyncMsFlow = MutableStateFlow(0)
    override val subtitleSyncMsFlow = MutableStateFlow(0)
    override val nextUpPromptSecondsFlow = MutableStateFlow(30)
    override val sleepTimerDefaultMinutesFlow = MutableStateFlow(0)
    override val resumeRewindSecondsFlow = MutableStateFlow(7)
    override val passOutThresholdFlow = MutableStateFlow(3)
    override val preferredQualityFlow = MutableStateFlow("auto")
    override val maxBitrateKbpsFlow = MutableStateFlow<Int?>(null)
    override val audioLanguageFlow = MutableStateFlow("")
    override val videoGravityFlow = MutableStateFlow("fit")
    override val orientationModeFlow = MutableStateFlow("auto")
    override val subtitleAppearanceFlow = MutableStateFlow(SubtitleAppearance.DEFAULT)
    override val subtitleUsesDeviceOverrideFlow = MutableStateFlow(false)

    override suspend fun setIntroSkipMode(value: IntroSkipMode) {
        setterCalls += "setIntroSkipMode"; introSkipModeFlow.value = value
    }
    override suspend fun setAutoSkipCredits(value: Boolean) {
        setterCalls += "setAutoSkipCredits"; autoSkipCreditsFlow.value = value
    }
    override suspend fun setAutoPlayNext(value: Boolean) {
        setterCalls += "setAutoPlayNext"; autoPlayNextFlow.value = value
    }
    override suspend fun setHdrEnabled(value: Boolean) {
        setterCalls += "setHdrEnabled"; hdrEnabledFlow.value = value
    }
    override suspend fun setDvProfile7HDR10Fallback(value: Boolean) {
        setterCalls += "setDvProfile7HDR10Fallback"; dvProfile7HDR10FallbackFlow.value = value
    }

    override suspend fun setDolbyVisionEnabled(value: Boolean) {
        setterCalls += "setDolbyVisionEnabled"; dolbyVisionEnabledFlow.value = value
    }

    override suspend fun setMatchContentFrameRate(value: Boolean) {
        setterCalls += "setMatchContentFrameRate"; matchContentFrameRateFlow.value = value
    }

    override suspend fun setSubtitleMatchesDevice(enabled: Boolean) {
        setterCalls += "setSubtitleMatchesDevice"; subtitleMatchesDeviceFlow.value = enabled
    }

    override suspend fun setShowAudiobooks(enabled: Boolean) {
        setterCalls += "setShowAudiobooks"; showAudiobooksFlow.value = enabled
    }
    override suspend fun setPictureInPictureEnabled(value: Boolean) {
        setterCalls += "setPictureInPictureEnabled"; pictureInPictureEnabledFlow.value = value
    }
    override suspend fun setDownloadsWifiOnly(value: Boolean) {
        setterCalls += "setDownloadsWifiOnly"; downloadsWifiOnlyFlow.value = value
    }
    override suspend fun setKeepWatchedDownloads(value: Boolean) {
        setterCalls += "setKeepWatchedDownloads"; keepWatchedDownloadsFlow.value = value
    }
    override suspend fun setDefaultDownloadQuality(value: String) {
        setterCalls += "setDefaultDownloadQuality"; defaultDownloadQualityFlow.value = value
    }
    override suspend fun setPlaybackSpeed(value: Double) {
        setterCalls += "setPlaybackSpeed"; playbackSpeedFlow.value = value
    }
    override suspend fun setAudioSyncMs(value: Int) {
        setterCalls += "setAudioSyncMs"; audioSyncMsFlow.value = value
    }
    override suspend fun setSubtitleSyncMs(value: Int) {
        setterCalls += "setSubtitleSyncMs"; subtitleSyncMsFlow.value = value
    }
    override suspend fun setNextUpPromptSeconds(value: Int) {
        setterCalls += "setNextUpPromptSeconds"; nextUpPromptSecondsFlow.value = value
    }
    override suspend fun setSleepTimerDefaultMinutes(value: Int) {
        setterCalls += "setSleepTimerDefaultMinutes"; sleepTimerDefaultMinutesFlow.value = value
    }
    override suspend fun setResumeRewindSeconds(value: Int) {
        setterCalls += "setResumeRewindSeconds"; resumeRewindSecondsFlow.value = value
    }
    override suspend fun setPassOutThreshold(value: Int) {
        setterCalls += "setPassOutThreshold"; passOutThresholdFlow.value = value
    }
    override suspend fun setPreferredQuality(value: String) {
        setterCalls += "setPreferredQuality"; preferredQualityFlow.value = value
    }
    override suspend fun setQuality(resolution: String, bitrateKbps: Int?) {
        setterCalls += "setQuality"
        preferredQualityFlow.value = resolution
        maxBitrateKbpsFlow.value = bitrateKbps
    }
    override suspend fun setAudioLanguage(value: String) {
        setterCalls += "setAudioLanguage"; audioLanguageFlow.value = value
    }
    override suspend fun setVideoGravity(value: String) {
        setterCalls += "setVideoGravity"; videoGravityFlow.value = value
    }
    override suspend fun setOrientationMode(value: String) {
        setterCalls += "setOrientationMode"; orientationModeFlow.value = value
    }
    override suspend fun setSubtitleAppearance(value: SubtitleAppearance) {
        setterCalls += "setSubtitleAppearance"; subtitleAppearanceFlow.value = value
    }
    override suspend fun flushProjectedSubtitleAppearance() {
        setterCalls += "flushProjectedSubtitleAppearance"
    }

    override suspend fun refreshFromServer() {}
    override suspend fun setSubtitleDeviceOverrideEnabled(enabled: Boolean) {}
    override suspend fun resetDeviceSetting(key: String) {}
    override suspend fun resetAllDeviceSettings() {}
    override suspend fun flushPendingDeviceSettings() {
        flushCount++
    }
}
