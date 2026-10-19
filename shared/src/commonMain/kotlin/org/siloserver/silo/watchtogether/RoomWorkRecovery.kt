package org.siloserver.silo.watchtogether

import org.siloserver.silo.network.*

/** Every coordinate must survive unchanged for an explicitly refused transport retry. */
data class RoomWorkIdentity(
    val engagement: Long,
    val roomId: String,
    val selectionRevision: Long,
    val generation: Long,
    val connectionEpoch: Long,
    val connectionId: Long,
    val sessionId: String?,
    val authority: Boolean,
    val commandId: String?,
    val role: String = "",
)

data class RoomWorkUpdate(
    val sequence: Long = 0,
    val kind: RoomWorkKind? = null,
    val requestId: String? = null,
    val refused: Boolean = false,
    val uncertain: Boolean = false,
    val uncertaintySequence: Long = 0,
    val uncertainKind: RoomWorkKind? = null,
    val discarded: Boolean = false,
    val discardedTransportSequence: Long = 0,
    val uncertainAction: String? = null,
    val discardedAction: String? = null,
    val bufferingRefusalSequence: Long = 0,
    val uncertainPlaybackIntent: RoomPlaybackIntent? = null,
    val discardedPlaybackIntent: RoomPlaybackIntent? = null,
)

/**
 * Correlation and class floors for one engagement. Playback desires are recomputed by
 * the binding, never queued as stale position reports. Only Lobby and explicitly
 * refused Transport can appear in [due]. All methods run under the repository mutex.
 */
internal class RoomWorkRecovery(
    private val nowMs: () -> Long,
    private val backoff: RealtimeBackoff,
) {
    data class Pending(
        val request: RoomWorkRequest,
        val identity: RoomWorkIdentity,
        val createdAt: Long,
        var retryAt: Long? = null,
        var retried: Boolean = false,
        var sentAt: Long = createdAt,
        var awaitingResponse: Boolean = true,
        var selected: Boolean = false,
    )
    private var sequence = 0L
    private val pending = mutableMapOf<String, Pending>()
    // Cancelled desires retain only a bounded, short-lived class correlation.
    private val recent = linkedMapOf<String, Pending>()
    private var recoveryFloor = 0L
    private var actionFloor = 0L
    var update = RoomWorkUpdate()
        private set

    fun nextId(): String = "room-${++sequence}"
    fun permitted(kind: RoomWorkKind): Boolean = nowMs() >= if (kind.actionClass) actionFloor else recoveryFloor

    fun resetFloors() {
        disconnect()
        recoveryFloor = 0
        actionFloor = 0
    }

    fun recoveryDelayMs(): Long = (recoveryFloor - nowMs()).coerceAtLeast(0)

    private fun remember(entry: Pending) {
        recent.entries.removeAll { nowMs() >= it.value.createdAt + 5_000 }
        recent[entry.request.requestId] = entry
        while (recent.size > 128) recent.remove(recent.keys.first())
    }

    fun sent(request: RoomWorkRequest, identity: RoomWorkIdentity) {
        if (request.kind == RoomWorkKind.Lobby) cancel(request.kind)
        else if (request.kind != RoomWorkKind.Transport) {
            // Keep recent correlations so a delayed refusal still sets the class floor.
            // They never queue retries; only the binding's current observation may send.
            pending.values.filter { it.request.kind == request.kind && it.identity != identity }.toList().forEach {
                pending.remove(it.request.requestId)
                remember(it)
            }
        } else if (request.action == "seek") supersedeSeek()
        pending[request.requestId] = Pending(request, identity, nowMs())
    }

    fun supersedeSeek() {
        pending.values.filter { it.request.kind == RoomWorkKind.Transport && it.request.action == "seek" }.toList().forEach {
            pending.remove(it.request.requestId) // a new user seek supersedes the old target
            remember(it)
        }
    }

    fun deferLobby(request: RoomWorkRequest, identity: RoomWorkIdentity) {
        cancel(RoomWorkKind.Lobby)
        pending[request.requestId] = Pending(request, identity, nowMs(), retryAt = maxOf(nowMs(), actionFloor), awaitingResponse = false)
    }

    fun echoLobby(ready: Boolean, current: RoomWorkIdentity?) {
        val entry = pending.values.firstOrNull { it.request.kind == RoomWorkKind.Lobby && it.identity == current && it.request.isReady == ready } ?: return
        pending.remove(entry.request.requestId)
        backoff.useful()
        publish(entry)
    }

    fun failed(id: String) {
        val entry = pending.values.firstOrNull { it.request.requestId == id } ?: return
        pending.remove(entry.request.requestId)
        publish(entry, uncertain = entry.awaitingResponse && (entry.request.kind == RoomWorkKind.Transport || entry.request.kind == RoomWorkKind.Lobby))
    }

    fun refused(refusal: RealtimeRefusal): Boolean {
        recent.entries.removeAll { nowMs() >= it.value.createdAt + 5_000 }
        val entry = pending[refusal.requestId] ?: recent[refusal.requestId] ?: return false
        if (!refusal.preWork) { failed(entry.request.requestId); return false }
        val at = nowMs() + backoff.refused(refusal.code, refusal.retryAfterMs!!)
        if (entry.request.kind.actionClass) actionFloor = maxOf(actionFloor, at)
        else recoveryFloor = maxOf(recoveryFloor, at)
        publish(entry, refused = true)
        entry.awaitingResponse = false
        if (pending[entry.request.requestId] !== entry) { recent.remove(entry.request.requestId); return true }
        when (entry.request.kind) {
            RoomWorkKind.Transport -> {
                if (!entry.retried && at <= entry.createdAt + 5_000) entry.retryAt = at
                else { pending.remove(entry.request.requestId); publish(entry, discarded = true) }
            }
            RoomWorkKind.Lobby -> entry.retryAt = at
            else -> pending.remove(entry.request.requestId) // binding recomputes current desire after the class floor
        }
        return true
    }

    fun applied(id: String?, action: String, currentLobbyIdentity: RoomWorkIdentity? = null): Boolean {
        val kind = when (action) { "transport_request" -> RoomWorkKind.Transport; "lobby_ready" -> RoomWorkKind.Lobby; else -> return false }
        val entry = pending[id]?.takeIf { it.request.kind == kind } ?: return false
        if (kind == RoomWorkKind.Lobby && entry.identity != currentLobbyIdentity) return false
        pending.remove(entry.request.requestId)
        backoff.useful()
        publish(entry)
        return true
    }

    fun cancel(kind: RoomWorkKind, uncertain: Boolean = false) {
        pending.values.filter { it.request.kind == kind }.toList().forEach {
            pending.remove(it.request.requestId)
            remember(it)
            publish(it, uncertain = uncertain && it.awaitingResponse,
                discarded = it.request.kind == RoomWorkKind.Transport && !it.awaitingResponse)
        }
    }

    fun disconnect() {
        pending.values.toList().forEach {
            publish(it, uncertain = it.awaitingResponse && (it.request.kind == RoomWorkKind.Transport || it.request.kind == RoomWorkKind.Lobby),
                discarded = it.request.kind == RoomWorkKind.Transport && !it.awaitingResponse)
        }
        pending.clear()
        recent.clear()
    }

    /** Called after current identity/authority/phase checks, on the same physical connection. */
    fun due(current: (RoomWorkKind) -> RoomWorkIdentity?): List<RoomWorkRequest> {
        val now = nowMs()
        recent.entries.removeAll { now >= it.value.createdAt + 5_000 }
        val result = mutableListOf<RoomWorkRequest>()
        for (entry in pending.values.toList()) {
            val kind = entry.request.kind
            if (kind != RoomWorkKind.Transport && kind != RoomWorkKind.Lobby) {
                if (now >= entry.createdAt + 5_000) pending.remove(entry.request.requestId)
                continue
            }
            val at = entry.retryAt
            if ((kind == RoomWorkKind.Transport && now >= entry.createdAt + 5_000) ||
                (at == null && now >= entry.sentAt + 5_000)) {
                pending.remove(entry.request.requestId); publish(entry, uncertain = entry.awaitingResponse, discarded = !entry.awaitingResponse); continue
            }
            if (at == null) continue // No acknowledgement is uncertainty, never retry permission.
            if (current(kind) != entry.identity || entry.request.playbackIntent?.isCurrent == false) {
                pending.remove(entry.request.requestId); publish(entry, discarded = true); continue
            }
            if (now < at || !permitted(kind)) continue
            entry.retryAt = null
            entry.retried = true
            entry.selected = true
            result += entry.request
        }
        return result
    }

    fun retryStillCurrent(request: RoomWorkRequest, current: RoomWorkIdentity?): Boolean {
        val entry = pending[request.requestId] ?: return false
        if ((request.kind == RoomWorkKind.Transport && nowMs() >= entry.createdAt + 5_000) ||
            request.playbackIntent?.isCurrent == false) {
            pending.remove(request.requestId)
            publish(entry, uncertain = entry.awaitingResponse, discarded = !entry.awaitingResponse)
            return false
        }
        if (entry.identity != current && entry.selected && entry.request == request) {
            pending.remove(request.requestId)
            publish(entry, uncertain = entry.awaitingResponse, discarded = !entry.awaitingResponse)
            return false
        }
        return permitted(request.kind) && entry.identity == current && entry.request == request && entry.selected
    }

    /** Selection is not dispatch. Call only immediately before handing this frame to the client. */
    fun retryDispatched(request: RoomWorkRequest) {
        pending[request.requestId]?.let {
            it.selected = false
            it.awaitingResponse = true
            it.sentAt = nowMs()
        }
    }

    fun nextWakeMs(): Long? = pending.values.mapNotNull {
        if (it.request.kind != RoomWorkKind.Transport && it.request.kind != RoomWorkKind.Lobby) it.createdAt + 5_000
        else listOfNotNull(it.retryAt?.let { at -> maxOf(at, actionFloor) }, if (it.retryAt == null) it.sentAt + 5_000
            else if (it.request.kind == RoomWorkKind.Transport) it.createdAt + 5_000 else null).minOrNull()
    }.minOrNull()?.let { (it - nowMs()).coerceAtLeast(1) }

    private fun publish(entry: Pending, refused: Boolean = false, uncertain: Boolean = false, discarded: Boolean = false) {
        update = RoomWorkUpdate(update.sequence + 1, entry.request.kind, entry.request.requestId, refused, uncertain,
            uncertaintySequence = update.uncertaintySequence + if (uncertain) 1 else 0,
            uncertainKind = if (uncertain) entry.request.kind else update.uncertainKind,
            discarded = discarded,
            discardedTransportSequence = update.discardedTransportSequence +
                if (discarded && entry.request.kind == RoomWorkKind.Transport) 1 else 0,
            uncertainAction = if (uncertain) entry.request.action else update.uncertainAction,
            discardedAction = if (discarded) entry.request.action else update.discardedAction,
            bufferingRefusalSequence = update.bufferingRefusalSequence +
                if (refused && entry.request.kind == RoomWorkKind.Buffering) 1 else 0,
            uncertainPlaybackIntent = if (uncertain) entry.request.playbackIntent else update.uncertainPlaybackIntent,
            discardedPlaybackIntent = if (discarded) entry.request.playbackIntent else update.discardedPlaybackIntent)
    }
}
