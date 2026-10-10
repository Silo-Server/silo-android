package org.siloserver.silo.model.shuffle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.section.SectionItem

/** What a shuffle draws its random picks from. Wire values of `ShuffleScopeRequest.kind`. */
enum class ShuffleScopeKind(val wire: String) {
    LIBRARY("library"),
    SERIES("series"),
    SEASON("season"),
    LIBRARY_COLLECTION("library_collection"),
    USER_COLLECTION("user_collection"),
}

/**
 * `GET /api/v2/shuffles/capabilities`. Shuffle is offered only when [state]
 * is `available` and the profile is [allowed]; [scopeKinds] lists the scopes
 * `createShuffle` accepts.
 */
@Serializable
data class ShuffleCapability(
    val state: String = "",
    val allowed: Boolean = false,
    @SerialName("scope_kinds") val scopeKinds: List<String> = emptyList(),
    val revision: String = "",
) {
    val isAvailable: Boolean get() = allowed && state == STATE_AVAILABLE

    fun supports(kind: ShuffleScopeKind): Boolean = isAvailable && kind.wire in scopeKinds

    companion object {
        const val STATE_AVAILABLE = "available"
    }
}

/** The scope a running shuffle plays from, named as it was when the shuffle started. */
@Serializable
data class ShuffleScope(
    val kind: String,
    val id: String,
    val title: String,
    /** A season scope's series title; absent for other scopes. */
    @SerialName("parent_title") val parentTitle: String? = null,
)

/**
 * A running shuffle. [current] plays now and [next] after it; both are catalog
 * item cards (movies or episodes). [next] equals [current] only when one item
 * in the scope can play.
 */
@Serializable
data class Shuffle(
    val id: String,
    val scope: ShuffleScope,
    val current: SectionItem,
    val next: SectionItem,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

private val SHUFFLE_LIBRARY_TYPES = setOf("movie", "movies", "series", "show", "shows", "tv", "mixed")

/** Whether a library of [type] holds movies or episodes: movie, TV, and mixed libraries shuffle. */
fun isShuffleLibraryType(type: String?): Boolean = type?.trim()?.lowercase() in SHUFFLE_LIBRARY_TYPES

/** A season shuffles only when it has two or more playable episodes. */
fun canShuffleSeason(episodes: List<org.siloserver.silo.model.catalog.EpisodeListItem>): Boolean =
    episodes.count { it.files.isNotEmpty() } > 1
