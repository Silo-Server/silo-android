package org.siloserver.silo.common.player

import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.SubtitleSyncState
import org.siloserver.silo.player.trackLanguageTag

/**
 * The subtitle's language for sync feedback, named in the interface language
 * when the notice is drawn ("English", "inglés"), or null when it has none.
 */
fun subtitleSyncName(tracks: List<PlayerSubtitleInfo>, subtitle: SubtitleSyncState): UiText? =
    trackLanguageTag(tracks.firstOrNull { it.syncKey == subtitle.key }?.language ?: subtitle.language)
        ?.let { UiText.LanguageName(it) }
