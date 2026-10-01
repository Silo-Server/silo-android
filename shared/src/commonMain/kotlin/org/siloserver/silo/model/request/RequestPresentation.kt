package org.siloserver.silo.model.request

/**
 * Presentation policy shared by the mobile and TV request surfaces: TMDB
 * image URL building and card routing. Status copy lives with
 * [RequestDisplayState] and [RequestProgress].
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
 * The one routing rule for request cards: a card whose status reads "In
 * library" opens the real item; everything else opens the request detail, so
 * the user can always see state and reason. That includes a title in the
 * library with an active request, such as a series with missing seasons coming.
 */
fun RequestMediaResult.libraryItemToOpen(): String? =
    RequestDisplayState.of(availability, request)?.libraryItemToOpen(libraryContentId)

/** Same rule for the user's own request records. */
fun MediaRequest.libraryItemToOpen(): String? =
    RequestDisplayState.of(this).libraryItemToOpen(libraryContentId)
