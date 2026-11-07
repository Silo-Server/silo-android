package org.siloserver.silo.common.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R
import org.siloserver.silo.watchtogether.WatchPartyPresence
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.isRightToLeftText

/*
 * Watch Party text both apps build the same way. Each is resolved in
 * composition, so it follows an in-place language change.
 */

/**
 * "Alex", "Alex and Sam", "Alex, Sam, and Kim", "Alex, Sam, and 3 others".
 * In a right-to-left interface each name of a list is wrapped in Unicode
 * isolates, as `stringResourceWithNames` does.
 */
@Composable
fun watchPartyNameList(names: List<String>): String {
    val rtl = isRightToLeftText()
    val shown = if (rtl && names.size > 1) names.map { "\u2068$it\u2069" } else names
    return when (shown.size) {
        0 -> ""
        1 -> shown[0]
        2 -> stringResource(R.string.watchParty_names_ofTwo, shown[0], shown[1])
        3 -> stringResource(R.string.watchParty_names_ofThree, shown[0], shown[1], shown[2])
        else -> pluralStringResource(R.plurals.watchParty_names_ofMore, shown.size - 2, shown[0], shown[1], shown.size - 2)
    }
}

/** A member's display name, or "Someone" when the server sent none. */
@Composable
fun watchPartyDisplayName(displayName: String): String =
    displayName.ifBlank { stringResource(R.string.watchParty_member_someone) }

/** "2h 35m" or "48m". */
@Composable
fun watchPartyRuntimeText(minutes: Int): String =
    if (minutes >= 60) {
        stringResource(R.string.catalog_runtimeHoursMinutes, LocaleFormat.integer(minutes / 60), LocaleFormat.integer(minutes % 60))
    } else {
        stringResource(R.string.overlays_runtimeMinutes, LocaleFormat.integer(minutes))
    }

/** "S1 · E2". */
@Composable
fun watchPartyEpisodeLine(season: Int, episode: Int): String =
    stringResource(R.string.watchParty_episodes_line, LocaleFormat.integer(season), LocaleFormat.integer(episode))

/** "S1:E2", the staged episode's short code. */
@Composable
fun watchPartyEpisodeCode(season: Int, episode: Int): String =
    stringResource(R.string.watchParty_facts_episodeCode, LocaleFormat.integer(season), LocaleFormat.integer(episode))

/** A member's watch state on the picker's confirmation page: "Watched", "In progress · Watchlist", "No viewing history". */
@Composable
fun watchPartyHistoryLabel(watched: Boolean?, inProgress: Boolean, onWatchlist: Boolean): String {
    watched ?: return stringResource(R.string.watchParty_whoSeen_noHistory)
    val label = stringResource(
        when {
            watched -> R.string.watchParty_whoSeen_watched
            inProgress -> R.string.watchParty_whoSeen_inProgress
            else -> R.string.watchParty_whoSeen_notWatched
        },
    )
    return if (onWatchlist) stringResource(R.string.watchParty_whoSeen_watchlist, label) else label
}

/** The lobby's "Here now" summary: "only you so far", "2 of 3 ready". */
@Composable
fun WatchPartyPresence.text(): String = when (this) {
    WatchPartyPresence.OnlyYou -> stringResource(R.string.watchParty_presence_onlyYou)
    WatchPartyPresence.Nobody -> stringResource(R.string.watchParty_presence_nobody)
    is WatchPartyPresence.Ready -> pluralStringResource(R.plurals.watchParty_presence_ready, here, LocaleFormat.integer(ready), here)
    is WatchPartyPresence.Watching -> pluralStringResource(R.plurals.watchParty_presence_watching, here, LocaleFormat.integer(watching), here)
}
