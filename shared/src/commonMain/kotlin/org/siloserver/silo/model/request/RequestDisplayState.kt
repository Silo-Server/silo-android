package org.siloserver.silo.model.request

/**
 * The five user-facing request states, shared by every request surface on
 * phone and TV (and matching the Apple clients' `RequestDisplayState`), so a
 * card, a list row, the detail page, and My Requests bucketing can never
 * disagree about what a given server state looks like.
 */
sealed interface RequestDisplayState {
    /** Submitted, awaiting approval. Cancelable by the requester. */
    data object Pending : RequestDisplayState

    /**
     * Approved, queued, or downloading, including a finished download the
     * library hasn't picked up yet.
     */
    data object OnTheWay : RequestDisplayState

    /** The title is in the library (or, from a server without `state`, the request completed). */
    data object InLibrary : RequestDisplayState

    /** Declined or failed; the detail page says which, and why. */
    data class NeedsAttention(val attention: RequestAttention, val reason: String?) : RequestDisplayState

    /**
     * Not requestable and no request exists (limit reached, requests off,
     * blocked), or the request was cancelled. The request affordance simply
     * doesn't render.
     */
    data class Unavailable(val reason: String?) : RequestDisplayState

    val label: String
        get() = when (this) {
            Pending -> "Pending"
            OnTheWay -> "On the way"
            InLibrary -> "In library"
            is NeedsAttention -> "Needs attention"
            is Unavailable -> "Unavailable"
        }

    val tint: RequestStatusTint
        get() = when (this) {
            Pending -> RequestStatusTint.Amber
            OnTheWay -> RequestStatusTint.Sky
            InLibrary -> RequestStatusTint.Emerald
            is NeedsAttention -> RequestStatusTint.Rose
            is Unavailable -> RequestStatusTint.Neutral
        }

    /** The owner may cancel only while the request waits for approval. */
    val isCancelable: Boolean
        get() = this == Pending

    /** The detail page's status line, which has room to say more than a caption. */
    val detailTitle: String
        get() = when (this) {
            Pending -> "Requested · Pending"
            OnTheWay -> "On the way"
            InLibrary -> "In your library"
            is NeedsAttention -> requestReasonCopy(reason)?.let { "${attention.title} · $it" } ?: attention.title
            is Unavailable -> requestReasonCopy(reason) ?: "Unavailable"
        }

    /**
     * The catalog item to open instead of the request, when there is nothing
     * about the request left to show: only a state that reads "In library",
     * with a known item. A title in the library that still has an active
     * request (missing seasons, a failure) opens the request, as its status says.
     */
    fun libraryItemToOpen(contentId: String?): String? =
        contentId?.takeIf { this == InLibrary && it.isNotBlank() }

    companion object {
        /**
         * From a full request record: My Requests, the hub strip, admin rows.
         * A decline or withdrawal explains itself in `outcome_reason`; a failure
         * in `last_error` (sent to admins only).
         */
        fun of(record: MediaRequest): RequestDisplayState {
            val closedByDecision = record.state == RequestUserState.Declined || record.state == RequestUserState.Cancelled ||
                (record.state == null && (record.outcome == RequestOutcome.Declined || record.outcome == RequestOutcome.Cancelled))
            val reason = if (closedByDecision) record.outcomeReason else record.lastError.ifBlank { record.outcomeReason }
            return of(state = record.state, status = record.status, outcome = record.outcome, reason = reason)
        }

        /** The server's `state` decides when present and recognized; `status` and `outcome` otherwise. */
        fun of(state: String?, status: String, outcome: String, reason: String? = null): RequestDisplayState =
            fromUserState(state, reason) ?: fromStatus(status, outcome, reason)

        /**
         * From a search/discover/detail card's compact annotation. Null when the
         * card has no state to show (requestable, never requested): that's the
         * Request affordance, not a status.
         *
         * An active request's `state` comes before availability: a series in the
         * library can have a request for its missing seasons, and the card must
         * agree with My Requests about it. `request.reason` says why the title
         * can't be requested, not why a request failed, so only a title with no
         * request shows it.
         */
        fun of(availability: String, request: RequestState): RequestDisplayState? {
            fromUserState(request.state, reason = null)?.let { return it }
            if (availability == RequestAvailability.Available) return InLibrary
            request.status?.takeIf { it.isNotBlank() }?.let {
                return fromStatus(it, RequestOutcome.Active, reason = null)
            }
            if (request.requestable) return null
            return Unavailable(request.reason.takeIf { it.isNotBlank() })
        }

        /** Null for an absent or unknown state, so a newer server's state falls back to `status`. */
        private fun fromUserState(state: String?, reason: String?): RequestDisplayState? = when (state) {
            RequestUserState.Pending -> Pending
            // Some requested seasons are in, the rest are still coming:
            // "In library" would file it as landed before it has.
            RequestUserState.Approved, RequestUserState.Processing, RequestUserState.PartiallyAvailable -> OnTheWay
            RequestUserState.Available -> InLibrary
            RequestUserState.Declined -> NeedsAttention(RequestAttention.Declined, reason.nonBlank())
            RequestUserState.Failed -> NeedsAttention(RequestAttention.Failed, reason.nonBlank())
            RequestUserState.Cancelled -> Unavailable(reason.nonBlank())
            else -> null
        }

        /**
         * The mapping for servers without `state`, which can't tell a finished
         * download from a title in the library: `completed` reads as in library.
         * `outcome` wins over `status` for terminal states.
         */
        private fun fromStatus(status: String, outcome: String, reason: String?): RequestDisplayState {
            when (outcome) {
                RequestOutcome.Declined -> return NeedsAttention(RequestAttention.Declined, reason.nonBlank())
                RequestOutcome.Failed -> return NeedsAttention(RequestAttention.Failed, reason.nonBlank())
                RequestOutcome.Cancelled -> return Unavailable(reason.nonBlank())
            }
            return when (status) {
                RequestStatus.Pending -> Pending
                RequestStatus.Completed -> InLibrary
                RequestStatus.Failed -> NeedsAttention(RequestAttention.Failed, reason.nonBlank())
                // An unknown status reads as in flight rather than broken: a newer
                // server's added pipeline stage shouldn't read as an error.
                else -> OnTheWay
            }
        }

        private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
    }
}

/** Why a request needs attention. A declined request and a failed one need different things from the user. */
enum class RequestAttention(val title: String) {
    Declined("Declined"),
    Failed("Request failed"),
}

/** The status color; the UI layer maps each to its theme color. */
enum class RequestStatusTint { Amber, Sky, Emerald, Rose, Neutral }

/** The four steps every request walks through, drawn as the stage track. */
enum class RequestStep(val title: String) {
    Requested("Requested"),
    Approval("Approved"),
    Download("Downloading"),
    Library("In your library"),
}

/**
 * Where a request sits on the stage track, plus the finer labels the five
 * display states can't express ("Queued" vs "Downloading" vs "Adding to
 * library"). Derived from [RequestDisplayState], so the track, the dot color,
 * and My Requests bucketing always agree.
 */
data class RequestProgress(
    val display: RequestDisplayState,
    /** Steps fully behind the request (0..4). Drawn solid. */
    val completedSteps: Int,
    /** The step the request is on, drawn in [tint]. Null once every step is done, or off the track. */
    val currentStep: RequestStep?,
    /** One or two words for card captions. */
    val shortLabel: String,
    /** A plain-language phrase for list rows and the TV preview panel. */
    val longLabel: String,
) {
    val tint: RequestStatusTint get() = display.tint

    companion object {
        /** From a full request record. */
        fun of(record: MediaRequest): RequestProgress =
            of(RequestDisplayState.of(record), state = record.state, status = record.status)

        /** From a card's compact annotation; null when there is no state to show. */
        fun of(availability: String, request: RequestState): RequestProgress? =
            RequestDisplayState.of(availability, request)?.let { of(it, state = request.state, status = request.status) }

        /** The display state decides the bucket and color; `state` and `status` only place an in-flight request. */
        fun of(display: RequestDisplayState, state: String?, status: String?): RequestProgress = when (display) {
            RequestDisplayState.Pending ->
                RequestProgress(display, 1, RequestStep.Approval, "Pending", "Waiting for approval")
            RequestDisplayState.OnTheWay -> {
                val (step, short, long) = inFlightPhase(state, status)
                RequestProgress(display, step.ordinal, step, short, long)
            }
            RequestDisplayState.InLibrary ->
                RequestProgress(display, RequestStep.entries.size, null, "In library", "In your library")
            is RequestDisplayState.NeedsAttention -> when (display.attention) {
                RequestAttention.Declined -> RequestProgress(display, 1, RequestStep.Approval, "Declined", "Declined")
                RequestAttention.Failed -> RequestProgress(display, 2, RequestStep.Download, "Failed", "Request failed")
            }
            is RequestDisplayState.Unavailable ->
                RequestProgress(display, 0, null, display.label, display.detailTitle)
        }

        /**
         * Where an approved-but-unfinished request is. `partially_available` and
         * a finished download the library hasn't picked up yet are both on the
         * last step; everything else is queued or downloading.
         */
        private fun inFlightPhase(state: String?, status: String?): Triple<RequestStep, String, String> {
            if (state == RequestUserState.PartiallyAvailable) {
                return Triple(RequestStep.Library, "Partly in library", "Partly in your library")
            }
            return when (status) {
                RequestStatus.Downloading -> Triple(RequestStep.Download, "Downloading", "Downloading")
                RequestStatus.Completed -> Triple(RequestStep.Library, "Adding to library", "Adding to your library")
                RequestStatus.Approved, RequestStatus.Queued -> Triple(RequestStep.Download, "Queued", "Queued for download")
                else -> Triple(RequestStep.Download, "On the way", "On the way")
            }
        }
    }
}

/**
 * The My Requests sections (and filters), derived from [RequestDisplayState].
 * Requests that need the user sit above finished ones. Cancelled requests drop
 * off the list.
 */
enum class MyRequestsBucket(val title: String) {
    InMotion("In progress"),
    NeedsAttention("Needs you"),
    Landed("Available"),
    ;

    companion object {
        fun of(state: RequestDisplayState): MyRequestsBucket? = when (state) {
            RequestDisplayState.Pending, RequestDisplayState.OnTheWay -> InMotion
            RequestDisplayState.InLibrary -> Landed
            is RequestDisplayState.NeedsAttention -> NeedsAttention
            is RequestDisplayState.Unavailable -> null
        }

        /** Ordered, non-empty buckets, newest first within each. */
        fun bucket(requests: List<MediaRequest>): List<Pair<MyRequestsBucket, List<MediaRequest>>> {
            val grouped = requests.groupBy { of(RequestDisplayState.of(it)) }
            return entries.mapNotNull { bucket ->
                grouped[bucket]?.takeIf { it.isNotEmpty() }?.let { bucket to it.sortedByDescending { r -> r.createdAt.requestInstantKey() } }
            }
        }
    }
}

/** Per-quality target copy. */
object RequestTargetSummary {
    /**
     * "1080p downloading · 4K queued" for a request fanned out to more than
     * one quality. Null for single-target requests, whose one state is the
     * request's own.
     */
    fun text(targets: List<RequestTarget>): String? {
        if (targets.size <= 1) return null
        val parts = targets.mapNotNull { target ->
            val quality = target.quality.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val word = when (target.status) {
                RequestStatus.Completed -> "done"
                RequestStatus.Downloading -> "downloading"
                RequestStatus.Queued, RequestStatus.Approved -> "queued"
                RequestStatus.Pending -> "pending"
                RequestStatus.Failed -> "failed"
                else -> return@mapNotNull null
            }
            "$quality $word"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The requested qualities, for rows that aren't in flight: "1080p · 4K". */
    fun qualities(targets: List<RequestTarget>): String? =
        targets.map { it.quality }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Copy for a request reason: a server token (`quota_exceeded`, …) becomes a
 * phrase; readable text from the server (a `last_error` sentence) shows as
 * written; an unknown code shows nothing rather than a raw code.
 */
fun requestReasonCopy(token: String?): String? {
    val value = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (value) {
        RequestReason.AlreadyRequested -> "Already requested"
        RequestReason.AlreadyAvailable -> "Already in your library"
        RequestReason.QuotaExceeded, "limit_reached" -> "Request limit reached"
        RequestReason.RequestsDisabled -> "Requests are turned off"
        RequestReason.Blocked, "requesting_blocked" -> "You can't request media right now"
        "validation_failed" -> "That request couldn't be submitted"
        "invalid_state" -> "This request can no longer be changed"
        "not_found" -> "This title is no longer available"
        RequestUnconfirmedToken -> "Not confirmed yet"
        // Server sentences can arrive lowercase ("one or more fulfillment
        // targets failed"); they read as their own line.
        else -> value.takeUnless { RequestReasonCodePattern.matches(it) }?.replaceFirstChar { it.uppercase() }
    }
}

/** Local reason for a create whose outcome is not known yet; shown on the held primary action. */
const val RequestUnconfirmedToken = "request_unconfirmed"

private val RequestReasonCodePattern = Regex("[a-z0-9_]+")

/**
 * A sort key for a server timestamp: RFC 3339 instants compare as text once the
 * fraction is padded, so sorting needs no date library. Unparseable values sort
 * as written.
 */
internal fun String.requestInstantKey(): String {
    val match = RequestInstantPattern.matchEntire(this) ?: return this
    val (head, fraction, zone) = match.destructured
    return if (zone == "Z") head + "." + fraction.padEnd(9, '0') + "Z" else this
}

private val RequestInstantPattern = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})""")
