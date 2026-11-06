package org.siloserver.silo.domain

/** The wording of the viewer's own star rating, shared by the phone and TV title pages. */
object UserStarRating {
    val choices = 1..5
    const val CLEAR_TITLE = "Clear Rating"

    fun title(stars: Int) = if (stars == 1) "1 Star" else "$stars Stars"

    /** The control's short caption: the current stars, or the action. */
    fun caption(rating: Int?) = rating?.let(::title) ?: "Rate"

    fun accessibilityLabel(rating: Int?) = rating?.let { "Your rating: ${title(it)}" } ?: "Rate this title"
}
