package org.siloserver.silo.model.profile

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage

/** The ceiling Child fills in when no rating was chosen, as on iOS and the web. */
const val DEFAULT_CHILD_CONTENT_RATING = "PG"

/**
 * The limits the profile forms set on any profile: its content-rating ceiling
 * and the libraries it may see. Follows the web editor, where these don't
 * depend on Child: Child only fills in kid-safe defaults (PG, and libraries
 * restricted so the manager picks at least one) for the limits the user hasn't
 * set yet in this form, and turning it off clears them.
 *
 * [ratingTouched] and [librariesTouched] are form state, never sent: they
 * record a choice made in this form, which Child then leaves alone.
 */
data class ProfileAccessLimits(
    val isChild: Boolean = false,
    val maxContentRating: String? = null,
    val libraryRestrictionsEnabled: Boolean = false,
    val allowedLibraryIds: Set<Int> = emptySet(),
    val ratingTouched: Boolean = false,
    val librariesTouched: Boolean = false,
) {
    fun withChild(child: Boolean): ProfileAccessLimits {
        if (!child) return ProfileAccessLimits()
        val fillRating = !ratingTouched && maxContentRating == null
        val fillLibraries = !librariesTouched && !libraryRestrictionsEnabled
        return copy(
            isChild = true,
            maxContentRating = if (fillRating) DEFAULT_CHILD_CONTENT_RATING else maxContentRating,
            libraryRestrictionsEnabled = libraryRestrictionsEnabled || fillLibraries,
            allowedLibraryIds = if (fillLibraries) emptySet() else allowedLibraryIds,
        )
    }

    /** A null [rating] is no ceiling. */
    fun withContentRating(rating: String?): ProfileAccessLimits =
        copy(maxContentRating = rating, ratingTouched = true)

    fun withLibraryRestrictions(enabled: Boolean): ProfileAccessLimits =
        copy(libraryRestrictionsEnabled = enabled, librariesTouched = true)

    fun withLibrary(id: Int, allowed: Boolean): ProfileAccessLimits =
        copy(
            allowedLibraryIds = if (allowed) allowedLibraryIds + id else allowedLibraryIds - id,
            librariesTouched = true,
        )

    /**
     * Restricted to libraries with none chosen; the forms ask for one, as iOS
     * and the web do. Only [assignable] libraries count when that list is known,
     * since a save drops the rest (see [UpdateProfileRequest.withAccessLimits]).
     *
     * On an edit, pass the [loaded] limits: an allowlist the user didn't change
     * is left as stored, so it isn't checked.
     */
    fun needsLibrary(loaded: ProfileAccessLimits? = null, assignable: Set<Int>? = null): Boolean =
        libraryRestrictionsEnabled &&
            (loaded == null || allowlistChangedFrom(loaded)) &&
            allowedLibraryIds.none { assignable == null || it in assignable }

    internal fun allowlistChangedFrom(loaded: ProfileAccessLimits): Boolean =
        libraryRestrictionsEnabled != loaded.libraryRestrictionsEnabled ||
            (libraryRestrictionsEnabled && allowedLibraryIds != loaded.allowedLibraryIds)

    companion object {
        fun of(profile: Profile): ProfileAccessLimits = ProfileAccessLimits(
            isChild = profile.isChild,
            maxContentRating = profile.maxContentRating,
            libraryRestrictionsEnabled = profile.libraryRestrictionsEnabled,
            allowedLibraryIds = profile.allowedLibraryIds.orEmpty().toSet(),
        )
    }
}

/** Form error shown while [ProfileAccessLimits.needsLibrary] holds. */
const val CHOOSE_A_LIBRARY_MESSAGE = "Select at least one library for this profile."

/** A new profile's access members. Create omits what it doesn't set. */
fun CreateProfileRequest.withAccessLimits(limits: ProfileAccessLimits): CreateProfileRequest {
    val restricted = limits.libraryRestrictionsEnabled
    return copy(
        isChild = true.takeIf { limits.isChild },
        maxContentRating = limits.maxContentRating,
        libraryRestrictionsEnabled = true.takeIf { restricted },
        allowedLibraryIds = limits.allowedLibraryIds.sorted().takeIf { restricted },
    )
}

/**
 * An edited profile's access members. `is_child` is always sent. The rating
 * ceiling and the library allowlist are sent only when they differ from
 * [loaded], so a value this form can't show (a rating from another client's
 * list, a library since deleted) stays as stored.
 *
 * A changed allowlist keeps only [assignable] libraries when that list is
 * known, so a library deleted since the profile was saved doesn't fail the save.
 */
fun UpdateProfileRequest.withAccessLimits(
    limits: ProfileAccessLimits,
    loaded: ProfileAccessLimits,
    assignable: Set<Int>? = null,
): UpdateProfileRequest {
    val ratingChanged = limits.maxContentRating != loaded.maxContentRating
    val restrictionsChanged = limits.libraryRestrictionsEnabled != loaded.libraryRestrictionsEnabled
    val allowlistChanged = limits.allowlistChangedFrom(loaded)
    val allowlist = if (limits.libraryRestrictionsEnabled) {
        limits.allowedLibraryIds.filter { assignable == null || it in assignable }.sorted()
    } else {
        emptyList()
    }
    return copy(
        isChild = limits.isChild,
        // "" is the clearing form: the PATCH sends null.
        maxContentRating = if (ratingChanged) limits.maxContentRating ?: "" else null,
        libraryRestrictionsEnabled = limits.libraryRestrictionsEnabled.takeIf { restrictionsChanged },
        allowedLibraryIds = allowlist.takeIf { allowlistChanged },
    )
}

/**
 * [known] ratings, with an unrecognized [current] one first, so the picker
 * shows the stored value instead of saving over it.
 */
fun contentRatingOptions(known: List<String>, current: String?): List<String> =
    if (current == null || current in known) known else listOf(current) + known

/** The rating ladder's last step: no ceiling (a null rating). */
const val ANY_RATING_LABEL = "Any"

/**
 * Whether [rating] sits under [ceiling] on the [known] scale, so the rating
 * ladder shows it as allowed too. With no ceiling every known rating is
 * allowed. A rating off the scale (another client's value) is never under or
 * over anything.
 */
fun ratingIsUnderCeiling(rating: String, ceiling: String?, known: List<String>): Boolean {
    val position = known.indexOf(rating)
    if (position < 0) return false
    return ceiling == null || position < known.indexOf(ceiling)
}

/** The line under the rating ladder that says what the ceiling allows. */
fun ratingCeilingCaption(ceiling: String?): String =
    if (ceiling == null) {
        "Can watch titles of any rating."
    } else {
        "Can watch titles rated $ceiling and below."
    }

/** The libraries a profile form can assign, as the form shows them. */
sealed interface AssignableLibraries {
    data object Loading : AssignableLibraries

    /** Sorted by the server's library order. */
    data class Loaded(val libraries: List<UserLibrary>) : AssignableLibraries

    /**
     * A failed read, kept apart from an empty list so the form never says there
     * is nothing to assign when it couldn't ask. [retrying] keeps the failure
     * (and its focused Try Again) on screen while a retry runs.
     */
    data class Failed(val message: String, val retrying: Boolean = false) : AssignableLibraries {
        val text: String get() = "Couldn't load libraries. $message".trim()
    }

    /** The state to show while a (re)load runs. */
    fun loading(): AssignableLibraries = if (this is Failed) copy(retrying = true) else Loading

    /** Ids of the loaded libraries, or null while they aren't known. */
    val ids: Set<Int>? get() = (this as? Loaded)?.libraries?.mapTo(HashSet()) { it.id }

    companion object {
        fun from(result: ApiResult<List<UserLibrary>>): AssignableLibraries = when (result) {
            is ApiResult.Success -> Loaded(result.data.sortedBy { it.sortOrder })
            else -> Failed(result.errorMessage(""))
        }
    }
}
