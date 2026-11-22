package org.siloserver.silo.android.ui.screens.watchparty

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.security.MessageDigest
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.watchparty.watchPartyRuntimeText
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchPartyEndReason
import org.siloserver.silo.watchtogether.WatchPartyInvite
import org.siloserver.silo.watchtogether.parseWatchPartyInvite

/**
 * A `silo://watch-party?server=…&token=…` app link, validated by the shared
 * invitation parser. Anything else (including `silo://invite`, the account
 * invitation) is not a party link.
 */
internal fun watchPartyAppLinkOrNull(rawUri: String?): WatchPartyInvite.Link? {
    val text = rawUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!text.startsWith("silo://", ignoreCase = true)) return null
    return parseWatchPartyInvite(text) as? WatchPartyInvite.Link
}

/**
 * A stable, non-reversible id for an invitation link. It names the pending
 * invitation in the hub route and in the Activity's consumed-route marker, so
 * a task rebuilt after process death does not join again, while the token
 * itself stays out of every route and saved state.
 */
internal fun watchPartyInviteHandoffId(link: WatchPartyInvite.Link): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${link.serverUrl}\n${link.token}".toByteArray(Charsets.UTF_8))
    return digest.take(INVITE_ID_BYTES).joinToString("") { "%02x".format(it) }
}

private const val INVITE_ID_BYTES = 12

/** What the lobby and player say when this device's party ended. */
@StringRes
internal fun watchPartyEndedMessage(reason: String): Int = when (reason) {
    WatchPartyEndReason.Replaced -> CommonR.string.watchParty_endReason_replaced
    WatchPartyEndReason.ConnectionLost -> CommonR.string.watchParty_endReason_connectionLost
    WatchPartyEndReason.Unauthorized -> CommonR.string.watchParty_endReason_unauthorized
    else -> CommonR.string.watchParty_endReason_ended
}

/** The party itself goes on after these, so the hub offers Rejoin. */
internal fun watchPartyEndedOffersRejoin(reason: String): Boolean =
    reason == WatchPartyEndReason.Replaced || reason == WatchPartyEndReason.ConnectionLost

/**
 * English member name and name list for the player's notices
 * (`player/PlayerScreen.kt`, another area) and their tests. Screens here use
 * the catalog's `org.siloserver.silo.common.watchparty.watchPartyDisplayName`
 * and `watchPartyNameList`; drop these once the player moves to them.
 */
internal fun watchPartyMemberName(member: RoomMember): String =
    member.displayName.ifBlank { "Someone" }

/** "Alex", "Alex and Sam", "Alex, Sam, and Kim", "Alex, Sam, and 3 others". */
internal fun formatWatchPartyNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    3 -> "${names[0]}, ${names[1]}, and ${names[2]}"
    else -> "${names[0]}, ${names[1]}, and ${names.size - 2} others"
}

/** The other members the room is waiting for while it syncs playback. */
internal fun watchPartyWaitingNames(room: RoomSnapshot?): List<String> {
    if (room == null || room.phase != RoomPhase.Playing || room.playbackState != RoomPlaybackState.Waiting) {
        return emptyList()
    }
    return room.members.filter { it.isSyncing && !it.isSelf }.map(::watchPartyMemberName)
}

/**
 * Members the room went on without: they were syncing while the room waited
 * and are still not ready once it plays again at the same selection. The
 * viewer is left out; their own status is the catching-up notice. A member
 * who left the room is not "left behind".
 */
internal fun watchPartyLeftBehindNames(previous: RoomSnapshot?, next: RoomSnapshot?): List<String> {
    if (previous == null || next == null) return emptyList()
    if (previous.roomId != next.roomId || previous.selectionRevision != next.selectionRevision) return emptyList()
    if (previous.phase != RoomPhase.Playing || next.phase != RoomPhase.Playing) return emptyList()
    if (previous.playbackState != RoomPlaybackState.Waiting || next.playbackState != RoomPlaybackState.Playing) {
        return emptyList()
    }
    return previous.members
        .filter { it.isSyncing && !it.isSelf }
        .mapNotNull { before ->
            val after = next.members.firstOrNull { it.isSameMemberAs(before) } ?: return@mapNotNull null
            watchPartyMemberName(after).takeIf { !after.isReady }
        }
}

private fun RoomMember.isSameMemberAs(other: RoomMember): Boolean =
    userId.isNotBlank() && profileId.isNotBlank() && userId == other.userId && profileId == other.profileId

/** Share text: the host's invitation link with the code, or the code alone for guests. */
@Composable
internal fun watchPartyShareText(code: String, inviteUrl: String?): String =
    if (inviteUrl != null) {
        stringResource(CommonR.string.watchParty_invite_shareTextLink, inviteUrl, code)
    } else {
        stringResource(CommonR.string.watchParty_invite_shareTextCode, code)
    }

/** Quality chips for the staged version, for example "4K" and "HDR". */
@Composable
internal fun watchPartyQualityChips(version: FileVersion?): List<String> {
    version ?: return emptyList()
    val resolution = version.resolution?.lowercase().orEmpty()
    val uhd = stringResource(CommonR.string.watchParty_facts_uhd)
    val hdr = stringResource(CommonR.string.watchParty_facts_hdr)
    return listOfNotNull(
        uhd.takeIf { "2160" in resolution || "4k" in resolution },
        hdr.takeIf { version.hdr },
    )
}

/** "2h 35m" or "48m", as Apple's `WatchPartyFacts.runtime`. */
@Composable
internal fun watchPartyRuntime(minutes: Int?): String? {
    val value = minutes?.takeIf { it > 0 } ?: return null
    return watchPartyRuntimeText(value)
}
