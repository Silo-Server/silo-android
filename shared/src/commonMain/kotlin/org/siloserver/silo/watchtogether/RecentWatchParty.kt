package org.siloserver.silo.watchtogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.WatchPartyEndReason
import org.siloserver.silo.repository.WatchPartyEnded
import org.siloserver.silo.util.wallClockMillis

/**
 * The durable identity a recent party belongs to: the verified server, the
 * persisted login, and the profile. Re-unlocking a PIN profile keeps the same
 * owner.
 */
data class RecentPartyOwner(
    val serverId: String,
    val loginId: String,
    val profileId: String,
) {
    override fun toString(): String = "RecentPartyOwner(<redacted>)"
}

/**
 * The one party this identity may explicitly rejoin. Only the room code and
 * display metadata are kept; rejoining exchanges the code for fresh proof, so
 * no room token or join token is ever persisted.
 */
@Serializable
data class RecentWatchParty(
    @SerialName("room_id") val roomId: String,
    val code: String,
    val title: String? = null,
    @SerialName("was_host") val wasHost: Boolean = false,
    @SerialName("saved_at_ms") val savedAtEpochMs: Long,
)

@Serializable
private data class StoredRecentParty(
    @SerialName("server_id") val serverId: String,
    @SerialName("login_id") val loginId: String,
    @SerialName("profile_id") val profileId: String,
    val party: RecentWatchParty,
)

/** Platform storage for one encrypted value. */
interface RecentWatchPartyStorage {
    fun read(): String?
    fun write(value: String?)
}

/**
 * One recent party per device, shown only to the identity that saved it and
 * only for [lifetimeMs]. Expired or foreign entries are never returned.
 */
class RecentWatchParties(
    private val storage: RecentWatchPartyStorage,
    private val owner: suspend () -> RecentPartyOwner?,
    private val nowEpochMs: () -> Long = ::wallClockMillis,
    private val lifetimeMs: Long = 24 * 60 * 60_000L,
) {
    private val mutex = Mutex()

    suspend fun current(): RecentWatchParty? = mutex.withLock {
        val stored = readLocked() ?: return@withLock null
        if (nowEpochMs() - stored.party.savedAtEpochMs !in 0..lifetimeMs) {
            storage.write(null)
            return@withLock null
        }
        val current = owner() ?: return@withLock null
        stored.party.takeIf { stored.matches(current) }
    }

    suspend fun remember(roomId: String, code: String, title: String? = null, wasHost: Boolean) {
        if (roomId.isBlank() || code.isBlank()) return
        val current = owner() ?: return
        mutex.withLock {
            val previous = readLocked()?.takeIf { it.matches(current) && it.party.roomId == roomId }
            val party = RecentWatchParty(
                roomId = roomId,
                code = code,
                title = title ?: previous?.party?.title,
                wasHost = wasHost,
                savedAtEpochMs = nowEpochMs(),
            )
            val stored = StoredRecentParty(current.serverId, current.loginId, current.profileId, party)
            storage.write(SiloJson.encodeToString(StoredRecentParty.serializer(), stored))
        }
    }

    /** Forget the entry, or only [roomId]'s entry when given. */
    suspend fun forget(roomId: String? = null) = mutex.withLock {
        val stored = readLocked() ?: return@withLock
        if (roomId == null || stored.party.roomId == roomId) storage.write(null)
    }

    private fun readLocked(): StoredRecentParty? {
        val raw = storage.read() ?: return null
        return try {
            SiloJson.decodeFromString(StoredRecentParty.serializer(), raw)
        } catch (_: Exception) {
            storage.write(null)
            null
        }
    }

    private fun StoredRecentParty.matches(owner: RecentPartyOwner) =
        serverId == owner.serverId && loginId == owner.loginId && profileId == owner.profileId
}

/**
 * Keeps the recent party in step with the engagement: remembered while a
 * membership is live, kept after a replacement or lost connection so the user
 * can rejoin, and forgotten when the room itself ended. Sign-out, account
 * replacement, and server removal clear it before the identity changes.
 */
class WatchPartyRecentsRecorder(
    private val recents: RecentWatchParties,
    room: StateFlow<RoomSnapshot?>,
    ended: StateFlow<WatchPartyEnded?>,
    scope: CoroutineScope,
    identityTransitions: IdentityTransitionBarrier,
) {
    init {
        identityTransitions.installGate { transition ->
            if (transition.phase == IdentityTransitionPhase.WILL_CHANGE && transition.kind in CLEARING_KINDS) {
                recents.forget()
            }
        }
        scope.launch {
            // Snapshots arrive often during playback; persist only identity changes.
            val membership = room
                .map { snapshot ->
                    snapshot?.takeIf { it.code.isNotBlank() }
                        ?.let { Membership(it.roomId, it.code, it.selfRole == MemberRole.Host) }
                }
                .distinctUntilChanged()
            combine(membership, ended) { live, end -> live to end }.collect { (live, end) ->
                when {
                    live != null -> recents.remember(live.roomId, live.code, wasHost = live.isHost)
                    end != null && end.reason in ROOM_GONE -> recents.forget(end.roomId)
                }
            }
        }
    }

    private data class Membership(val roomId: String, val code: String, val isHost: Boolean)

    private companion object {
        val CLEARING_KINDS = setOf(
            IdentityTransitionKind.SIGN_OUT,
            IdentityTransitionKind.ACCOUNT_REPLACE,
            IdentityTransitionKind.SERVER_REMOVE,
        )
        val ROOM_GONE = setOf(WatchPartyEndReason.HostLeft, WatchPartyEndReason.NotFound, WatchPartyEndReason.Ended)
    }
}
