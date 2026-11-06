package org.siloserver.silo.model.request

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage

/**
 * Sorts a failed create or cancel into the two outcomes the UI
 * treats differently. All of them are `non_retryable`: the server keeps no
 * request identity, so a resend after an uncertain outcome can act twice.
 *
 * - **Definite:** the server answered with an error. Show it; the user may try again.
 * - **Uncertain:** the request may have reached the server but no usable answer
 *   came back. Never resend; hold the action until a fresh read shows what happened.
 */
object RequestMutationFailure {
    fun isUncertain(result: ApiResult<*>): Boolean = when (result) {
        is ApiResult.Success -> false
        // A received answer this client couldn't read: the server acted. A
        // gateway error without the server's own problem body came from a
        // proxy, which may have forwarded the call before giving up on it.
        is ApiResult.Error -> (result.code == 0 && result.error == "invalid_response") ||
            (result.code in GatewayErrors && result.error.isEmpty())
        // Refused before a byte left the device, or never connected: nothing happened.
        is ApiResult.NetworkError -> generateSequence(result.exception) { it.cause?.takeIf { cause -> cause !== it } }
            .none { it::class.simpleName in NeverConnected }
    }

    private val GatewayErrors = setOf(502, 503, 504)

    private val NeverConnected = setOf(
        "UnknownHostException",
        "ConnectException",
        "ConnectTimeoutException",
        "NoRouteToHostException",
        "UnresolvedAddressException",
    )
}

/** Copy for request mutations. */
object RequestActionCopy {
    const val UnconfirmedSubmit = "We couldn't confirm the request. Check My Requests before trying again."
    const val UnconfirmedCancel = "We couldn't confirm the cancellation. Refresh your requests to check it."
    const val UnconfirmedRemove = "We couldn't confirm the removal. Refresh your requests to check it."

    /** Removing a failed request (the web's copy). */
    const val Removed = "Request removed"
    const val RemoveConfirmTitle = "Remove this request?"
    const val RemoveConfirm = "Remove request"
    const val RemoveKeep = "Keep request"
    fun removeConfirmMessage(title: String): String =
        "Your failed request for \"$title\" will be deleted. You can request the title again later."

    /** A 409 on removal: an admin retried the request, or the library completed it, since the list loaded. */
    const val RemoveConflict = "This request changed since the list loaded, so it wasn't removed."

    /**
     * Copy for a definite failure. v2 folds "already requested", "already
     * available", and "invalid state" into one `conflict` problem, and the quota
     * message carries the counts, so the problem's detail is the most specific
     * copy there is.
     */
    fun failure(result: ApiResult<*>, fallback: String): String = when {
        result is ApiResult.Error && result.error == "validation_failed" -> "That request couldn't be submitted"
        result is ApiResult.Error && result.error in CapabilityOff -> "Requests are turned off"
        else -> result.errorMessage(fallback)
    }

    /** The server refused because the feature is off: the caller should re-read its capability. */
    fun isCapabilityOff(result: ApiResult<*>): Boolean = result is ApiResult.Error && result.error in CapabilityOff

    private val CapabilityOff = setOf("capability_disabled", "capability_not_configured", "capability_unsupported")

    /** Confirmations for following a title someone else requested (the web's toasts). */
    const val Followed = "We'll let you know when it's available"
    const val Unfollowed = "Notification turned off"
}

/**
 * A cancel sent without a usable answer. A timed-out
 * call can still be running on the server when the next read comes back, so an
 * unchanged request proves nothing: the hold lasts until the request changes or
 * leaves the list. It lapses after [Lifetime], read or not, so a call that never
 * arrived doesn't lock the row forever. Releasing it can't double an action: the
 * server applies each one only from the state it expects.
 */
class RequestActionHold(
    val requestId: String,
    val updatedAt: String,
    private val since: TimeMark,
    private val lifetime: Duration = Lifetime,
) {
    constructor(request: MediaRequest, timeSource: TimeSource = TimeSource.Monotonic, lifetime: Duration = Lifetime) :
        this(request.id, request.updatedAt, timeSource.markNow(), lifetime)

    /** Whether a complete read ([current] is the request's entry in it, or null when gone) shows what the held call did. */
    fun isSettled(current: MediaRequest?): Boolean {
        if (current == null || current.id != requestId) return true
        return current.updatedAt != updatedAt || since.elapsedNow() >= lifetime
    }

    companion object {
        val Lifetime: Duration = 60.seconds
    }
}

/**
 * The compact card state implied by a full request record, so every visible
 * card flips its status after a create or cancel without a refetch. Active
 * requests block re-requesting; cancelled ones re-open the affordance.
 */
fun MediaRequest.toRequestState(): RequestState = when (outcome) {
    RequestOutcome.Cancelled -> RequestState(requestable = true)
    // Re-requesting is allowed after a decline or failure, but the state stays
    // visible so the card explains itself.
    RequestOutcome.Declined, RequestOutcome.Failed -> RequestState(
        status = status,
        state = state,
        requestable = true,
        requestId = id,
    )
    else -> RequestState(
        status = status,
        state = state,
        requestable = false,
        requestId = id,
    )
}

/** This result with the request state implied by [record], when the record is for the same title. */
fun RequestMediaResult.applying(record: MediaRequest): RequestMediaResult =
    if (record.mediaType == mediaType && record.tmdbId == tmdbId) copy(request = record.toRequestState()) else this

fun List<RequestMediaResult>.applyingRequestUpdate(record: MediaRequest): List<RequestMediaResult> =
    map { it.applying(record) }

/** This state once the server deleted [record]: requestable again, unless it shows a different request. */
fun RequestState.without(record: MediaRequest): RequestState =
    if (requestId == record.id) RequestState(requestable = true) else this

/** These results once the server deleted [record]. */
fun List<RequestMediaResult>.applyingRemoval(record: MediaRequest): List<RequestMediaResult> =
    map { if (record.mediaType == it.mediaType && record.tmdbId == it.tmdbId) it.copy(request = it.request.without(record)) else it }

/**
 * The viewer started or stopped following one title. [state] is the server's
 * answer to a follow; an unfollow answers with no body, so it is null and only
 * [following] changes.
 */
data class RequestFollowEvent(
    val mediaType: String,
    val tmdbId: Int,
    val following: Boolean,
    val state: RequestState?,
    val sequence: Long,
)

/**
 * This state after the viewer's follow or unfollow: the server's answer to a
 * follow when there is one, keeping the request id it may leave out, or just
 * the flag for an unfollow, which answers with no body.
 */
fun RequestState.applyingFollow(event: RequestFollowEvent): RequestState =
    event.state?.let { it.copy(requestId = it.requestId ?: requestId) } ?: copy(following = event.following)

fun List<RequestMediaResult>.applyingFollowUpdate(event: RequestFollowEvent): List<RequestMediaResult> =
    map { if (it.mediaType == event.mediaType && it.tmdbId == event.tmdbId) it.copy(request = it.request.applyingFollow(event)) else it }
