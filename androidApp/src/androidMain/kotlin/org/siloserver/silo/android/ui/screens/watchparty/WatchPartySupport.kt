package org.siloserver.silo.android.ui.screens.watchparty

import java.net.URI
import java.security.MessageDigest
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchPartyEndReason
import org.siloserver.silo.watchtogether.WatchPartyInvite
import org.siloserver.silo.watchtogether.parseWatchPartyInvite

/**
 * Whether an invitation's server is the server this app is signed in to:
 * the same scheme, host (ignoring case), port (an omitted default port equals
 * the explicit one), and path once a trailing slash is trimmed. A LAN address
 * and a public address for the same server do not match.
 */
internal fun watchPartyServerMatches(linkServerUrl: String, activeServerUrl: String?): Boolean {
    val link = comparableServer(linkServerUrl) ?: return false
    val active = activeServerUrl?.let(::comparableServer) ?: return false
    return link == active
}

private data class ComparableServer(val scheme: String, val host: String, val port: Int, val path: String)

private fun comparableServer(url: String): ComparableServer? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.isOpaque) return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val port = when {
        uri.port != -1 -> uri.port
        scheme == "https" -> 443
        else -> 80
    }
    return ComparableServer(scheme, host, port, uri.rawPath.orEmpty().trimEnd('/'))
}

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
internal fun watchPartyEndedMessage(reason: String): String = when (reason) {
    WatchPartyEndReason.Replaced -> "This profile joined the Watch Party on another device."
    WatchPartyEndReason.ConnectionLost -> "Lost connection to the party."
    WatchPartyEndReason.Unauthorized -> "Sign in again to use Watch Party."
    else -> "The Watch Party has ended."
}

/** The party itself goes on after these, so the hub offers Rejoin. */
internal fun watchPartyEndedOffersRejoin(reason: String): Boolean =
    reason == WatchPartyEndReason.Replaced || reason == WatchPartyEndReason.ConnectionLost

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
internal fun watchPartyShareText(code: String, inviteUrl: String?): String =
    if (inviteUrl != null) {
        "Join my Watch Party on Silo: $inviteUrl\nOr enter the code $code."
    } else {
        "Join my Watch Party on Silo with the code $code."
    }

/** Quality chips for the staged version, for example "4K" and "HDR". */
internal fun watchPartyQualityChips(version: FileVersion?): List<String> {
    version ?: return emptyList()
    val resolution = version.resolution?.lowercase().orEmpty()
    return listOfNotNull(
        "4K".takeIf { "2160" in resolution || "4k" in resolution },
        "HDR".takeIf { version.hdr },
    )
}

/** "2h 35m" or "48m", as Apple's `WatchPartyFacts.runtime`. */
internal fun watchPartyRuntime(minutes: Int?): String? {
    val value = minutes?.takeIf { it > 0 } ?: return null
    return if (value >= 60) "${value / 60}h ${value % 60}m" else "${value}m"
}
