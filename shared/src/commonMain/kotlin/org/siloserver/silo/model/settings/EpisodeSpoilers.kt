package org.siloserver.silo.model.settings

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.section.SectionItem

/**
 * Spoiler protection for episodes the profile has not started (settings
 * contract revision 16). The profile settings
 * [SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES] and
 * [SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS] choose what to hide;
 * this object owns the rule for which episodes they apply to, shared by every
 * phone and TV surface. The server never changes catalog responses; clients
 * apply the settings.
 */
object EpisodeSpoilers {
    /** Contract revision that introduced both keys. */
    const val MIN_CONTRACT_REVISION = 16

    /** Settings protocol version these keys are defined against. */
    const val SETTINGS_API_VERSION = 1

    val KEYS: List<String> = listOf(
        SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES,
        SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS,
    )

    fun isSupported(capabilities: SettingsContractCapabilities): Boolean =
        capabilities.apiVersion == SETTINGS_API_VERSION &&
            capabilities.manifestRevision >= MIN_CONTRACT_REVISION &&
            capabilities.supportsBatchedEffective

    /**
     * An episode is unwatched while it is neither played nor in progress.
     * Missing watch state counts as unwatched, so an episode is never
     * revealed just because its payload left the state out. Matches the web
     * client's `isEpisodeUnwatched`.
     */
    fun isUnwatched(
        played: Boolean?,
        isInProgress: Boolean?,
        positionSeconds: Double?,
    ): Boolean {
        if (played == true || isInProgress == true) return false
        return (positionSeconds ?: 0.0) <= 0.0
    }

    fun isUnwatched(userData: LeafItemUserData?): Boolean =
        userData == null ||
            isUnwatched(userData.played, userData.isInProgress, userData.positionSeconds)

    /** Browse cards use the live played flag returned by their action state. */
    fun hidesBrowseArtwork(
        item: BrowseItem, prefs: EpisodeSpoilerPrefs, played: Boolean? = item.userState?.played,
        selectBackdrop: Boolean = false, positionSeconds: Double? = item.positionSeconds,
    ): Boolean {
        val provenance = if (selectBackdrop && !item.backdropUrl.isNullOrBlank()) item.backdropIsEpisodeStill
            else item.posterIsEpisodeStill
        return item.type.equals("episode", ignoreCase = true) &&
            prefs.hidesImage(isUnwatched(played, isInProgress = null, positionSeconds = positionSeconds), provenance)
    }

    /** Provenance follows the image selected for the wide card; null preserves older-server protection. */
    fun selectedImageIsStill(item: SectionItem): Boolean? =
        if (!item.backdropUrl.isNullOrBlank()) item.backdropIsEpisodeStill else item.posterIsEpisodeStill

    /** Section rows carry no in-progress flag; played and position decide. */
    fun isUnwatched(item: SectionItem): Boolean =
        isUnwatched(item.userState?.played, isInProgress = null, positionSeconds = item.positionSeconds)
}

/** What the profile hides for an unwatched episode. Both off by default. */
data class EpisodeSpoilerPrefs(
    /** Blur the still of an episode the profile has not started. */
    val hideImages: Boolean = false,
    /** Hide the description of an episode the profile has not started. */
    val hideOverviews: Boolean = false,
) {
    fun hidesImage(unwatched: Boolean, isEpisodeStill: Boolean? = null): Boolean =
        hideImages && unwatched && isEpisodeStill != false

    fun hidesOverview(unwatched: Boolean): Boolean = hideOverviews && unwatched

    companion object {
        val NONE = EpisodeSpoilerPrefs()
    }
}
