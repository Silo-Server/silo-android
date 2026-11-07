package org.siloserver.silo.common.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.playback.SubtitleSyncNotice

/**
 * The sync card's title with its first letter capitalized: a subtitle's
 * language name may open it ("English subtitles synced") and may arrive in
 * lower case.
 */
@Composable
fun SubtitleSyncNotice.titleText(): String {
    val locale = LocalConfiguration.current.locales[0]
    return title.text().replaceFirstChar { it.titlecase(locale) }
}
