package org.siloserver.silo.common.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R

/**
 * A settings change the server didn't save, shown under its setting until the
 * next save. [text] words it in the app's language; a [detail] is the
 * server's or the network's own words and is shown as given.
 */
sealed interface SettingsSaveError {
    /** Title art didn't save. [identityChanged]: the account or profile changed while it was saving. */
    data class TitleArt(val detail: String?, val identityChanged: Boolean = false) : SettingsSaveError

    /** A spoiler switch didn't save; [detail] is null when the failure gave no words. */
    data class EpisodeSpoilers(val detail: String?) : SettingsSaveError
}

/** "Couldn't save title art: …", "Couldn't save spoiler settings: …". */
@Composable
fun SettingsSaveError.text(): String = when (this) {
    is SettingsSaveError.TitleArt -> {
        val shown = if (identityChanged) {
            stringResource(R.string.settings_titleArt_identityChanged)
        } else {
            detail?.takeIf { it.isNotBlank() }
        }
        shown?.let { stringResource(R.string.settings_titleArt_saveFailedDetail, it) }
            ?: stringResource(R.string.settings_titleArt_saveFailed)
    }
    is SettingsSaveError.EpisodeSpoilers -> stringResource(
        R.string.settings_spoilers_saveFailed,
        detail ?: stringResource(R.string.settings_spoilers_saveFailedDetail),
    )
}
