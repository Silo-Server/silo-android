package org.siloserver.silo.network.apiv2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.siloserver.silo.model.notifications.WsFrameEnvelope
import org.siloserver.silo.model.notifications.WsSubscribed
import org.siloserver.silo.network.GenericEventsClose
import org.siloserver.silo.network.SiloJson
import kotlin.time.TimeSource

/** Per-attempt bookkeeping, never shared across collections or identities. */
internal class GenericEventsAttempt(private val nowMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds }) {
    private var acceptedAt: Long? = null
    private var endedAt: Long? = null
    var retryAfterMs: Long? = null
    var accessChanged = false

    fun frame(raw: String) {
        if (acceptedAt != null) return
        val envelope = runCatching { SiloJson.decodeFromString(WsFrameEnvelope.serializer(), raw) }.getOrNull()
        if (envelope?.type != "subscribed") return
        val subscribed = runCatching { SiloJson.decodeFromString(WsSubscribed.serializer(), raw) }.getOrNull()
        if (subscribed?.type == "subscribed" && subscribed.channels.isNotEmpty()) acceptedAt = nowMs()
    }

    fun ended() { if (endedAt == null) endedAt = nowMs() }

    fun closed(): GenericEventsClose = GenericEventsClose(
        acceptedForMs = acceptedAt?.let { ((endedAt ?: nowMs()) - it).coerceAtLeast(0) },
        retryAfterMs = retryAfterMs,
        accessChanged = accessChanged,
    )

    private companion object { val origin = TimeSource.Monotonic.markNow() }
}

/** Both common consumers receive the same transport outcome, including ACK-free failures. */
internal fun <T : Any> EventsSocketV2Api.genericEventsConnection(
    channels: List<String>,
    decode: (String) -> T?,
    closed: (String?, GenericEventsClose) -> T,
): Flow<T> = flow {
    val attempt = newAttempt()
    var reason: String? = null
    try {
        frames(channels, attempt).collect { raw -> decode(raw)?.let { emit(it) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is EventsUpgradeFailure) attempt.retryAfterMs = e.retryAfterMs
        reason = if (e is EventsTicketFailure) "ticket_error_${e.code}" else "socket_error"
    }
    emit(closed(reason, attempt.closed()))
}
