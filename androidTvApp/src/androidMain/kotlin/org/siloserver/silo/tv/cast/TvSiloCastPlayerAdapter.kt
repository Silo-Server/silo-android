package org.siloserver.silo.tv.cast

import org.siloserver.silo.cast.SiloCastControlCommand

class TvSiloCastPlayerAdapter(
    private val play: () -> Unit,
    private val pause: () -> Unit,
    private val playPause: () -> Unit,
    private val seek: (Double) -> Unit,
    private val stop: () -> Unit,
    private val selectAudio: (Long) -> Unit,
    private val selectSubtitle: (Long?) -> Unit,
    private val setPlaybackSpeed: (Double) -> Unit,
    private val setQuality: (String) -> Unit,
    private val setVideoGravity: (String) -> Unit,
    private val setSubtitleSyncMs: (Int) -> Unit,
    private val setSubtitlePosition: (String) -> Unit,
    private val setVolume: (Double) -> Unit,
    private val setMuted: (Boolean) -> Unit,
    private val playNext: () -> Unit,
) {
    fun handle(command: SiloCastControlCommand) {
        // Field mapping follows Apple's SiloControlCommand exactly: seek uses
        // `seconds`, quality/gravity/subtitle-position share `value`, subtitle
        // delay is `milliseconds`, mute rides `enabled`.
        when (command.name) {
            SiloCastControlCommand.Play -> play()
            SiloCastControlCommand.Pause -> pause()
            SiloCastControlCommand.PlayPause -> playPause()
            SiloCastControlCommand.Stop -> stop()
            SiloCastControlCommand.Seek -> command.seconds?.let(seek)
            SiloCastControlCommand.SelectAudioTrack -> command.trackId?.let(selectAudio)
            SiloCastControlCommand.SelectSubtitleTrack -> selectSubtitle(command.trackId)
            SiloCastControlCommand.SetQuality -> command.value?.let(setQuality)
            SiloCastControlCommand.SetPlaybackSpeed -> command.speed?.let(setPlaybackSpeed)
            SiloCastControlCommand.SetVideoGravity -> command.value?.let(setVideoGravity)
            SiloCastControlCommand.SetSubtitleSyncMs -> command.milliseconds?.let(setSubtitleSyncMs)
            SiloCastControlCommand.SetSubtitlePosition -> command.value?.let(setSubtitlePosition)
            SiloCastControlCommand.SetVolume -> command.volume?.let(setVolume)
            SiloCastControlCommand.SetMuted -> command.enabled?.let(setMuted)
            SiloCastControlCommand.PlayNext -> playNext()
            else -> Unit
        }
    }
}
