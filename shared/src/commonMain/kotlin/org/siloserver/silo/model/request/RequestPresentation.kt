package org.siloserver.silo.model.request

/**
 * Presentation policy shared by the mobile and TV request surfaces:
 * TMDB image URL building, badge/chip status precedence, status
 * labelling, cancellability, and the per-target summary line.
 */

fun requestPosterUrl(path: String?): String? = requestImageUrl(path, "w500")

fun requestBackdropUrl(path: String?): String? = requestImageUrl(path, "w780")

private fun requestImageUrl(path: String?, size: String): String? {
    val value = path?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("http://") || value.startsWith("https://") -> value
        value.startsWith("/") -> "https://image.tmdb.org/t/p/$size$value"
        else -> value
    }
}

/**
 * Raw status token for a discover/search card badge, in precedence
 * order: in-library beats request status beats requestability beats
 * the server-provided reason. Render with [requestDisplayLabel].
 */
fun RequestMediaResult.badgeStatus(): String = when {
    availability == RequestAvailability.Available -> RequestAvailability.Available
    request.status?.isNotBlank() == true -> request.status.orEmpty()
    request.requestable -> "request"
    request.reason.isNotBlank() -> request.reason
    else -> RequestAvailability.Missing
}

/** Human label for a request status/outcome/availability/media-type token. */
fun String.requestDisplayLabel(): String = when (lowercase()) {
    RequestMediaType.Movie -> "Movie"
    RequestMediaType.Series -> "Series"
    RequestMediaType.Audiobook -> "Audiobook"
    RequestMediaType.Ebook -> "Ebook"
    RequestMediaType.All -> "All"
    RequestAvailability.Available -> "In Library"
    RequestAvailability.Missing -> "Missing"
    "request" -> "Request"
    else -> split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
        .ifBlank { this }
}

/**
 * A sentence saying why this title cannot be requested, or null when there is
 * nothing worth showing. A title that already has a request gets null: its
 * request status already says where it stands. A code this client does not
 * know also gets null, so a raw code never reaches the screen, while reason
 * text that is not a code is shown as the server wrote it.
 */
fun RequestState.reasonMessage(): String? = when (reason) {
    RequestReason.AlreadyRequested -> null
    RequestReason.AlreadyAvailable -> "This title is already in your library."
    RequestReason.RequestsDisabled -> "Requests are disabled on this server."
    RequestReason.Blocked -> "Your account is blocked from making requests."
    RequestReason.QuotaExceeded -> "You've reached your request limit."
    else -> reason.takeUnless { it.isBlank() || ReasonCodePattern.matches(it) }
}

/** What a server reason code looks like, as opposed to readable text. */
private val ReasonCodePattern = Regex("[a-z0-9_]+")

fun MediaRequest.canCancel(): Boolean =
    outcome == RequestOutcome.Active && status == RequestStatus.Pending

fun MediaRequest.targetSummary(): String? {
    if (targets.isEmpty()) return null
    return targets.joinToString(limit = 2, truncated = "…") { target ->
        listOf(target.instanceName, target.quality, target.status, target.externalStatus, target.lastError)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }
}
