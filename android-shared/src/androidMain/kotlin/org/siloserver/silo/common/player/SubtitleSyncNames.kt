package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.SubtitleSyncState
import org.siloserver.silo.player.trackLanguageDisplayName

/** The subtitle's language name for sync feedback ("English"), or null when it has none. */
fun subtitleSyncName(tracks: List<PlayerSubtitleInfo>, subtitle: SubtitleSyncState): String? =
    trackLanguageDisplayName(tracks.firstOrNull { it.syncKey == subtitle.key }?.language ?: subtitle.language)
