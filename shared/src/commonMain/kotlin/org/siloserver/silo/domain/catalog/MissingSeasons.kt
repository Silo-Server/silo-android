package org.siloserver.silo.domain.catalog

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.siloserver.silo.model.catalog.MissingEpisodes
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RequestsRepository
import org.siloserver.silo.repository.SettingsRepository

/** What a series page offers for the seasons the library lacks. */
data class MissingSeasonRequests(
    /** A missing season can be requested from the series page. */
    val canRequest: Boolean = false,
    /** Seasons a request or a download server already covers. */
    val requestedSeasons: Set<Int> = emptySet(),
    /** The request detail a season request is built from. */
    val media: RequestMediaDetail? = null,
)

/**
 * The series page's view of what the library lacks, shared by the phone and
 * TV apps.
 *
 * [scope] turns the profile's `catalog.show_missing_episodes` into the scope
 * the page asks its season and episode lists for: with it on, aired episodes
 * the library lacks are listed, and episodes still to air join them only
 * while a download server takes series requests, since only then can the page
 * say whether they are already coming. The switch lives in the web client's
 * Title Pages settings and follows the profile; a server older than it
 * answers [MissingEpisodes.NONE].
 */
class MissingSeasons(
    private val settings: SettingsRepository,
    private val requests: RequestsRepository,
) {
    suspend fun scope(): MissingEpisodes {
        val values = settings.getEffectiveValues(listOf(SettingKeys.CATALOG_SHOW_MISSING_EPISODES))
        val enabled = (values as? ApiResult.Success)?.data
            ?.get(SettingKeys.CATALOG_SHOW_MISSING_EPISODES)?.value
            ?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        if (!enabled) return MissingEpisodes.NONE
        val status = (requests.status() as? ApiResult.Success)?.data
        return if (status?.isAvailable == true && status.seriesServerConnected) MissingEpisodes.ALL else MissingEpisodes.AIRED
    }

    /**
     * Request state for a series page listing [missing]: requests are offered
     * only while the feature is on for this profile and a download server
     * takes series requests.
     */
    suspend fun requests(tmdbId: Int?, missing: MissingEpisodes): MissingSeasonRequests {
        if (missing == MissingEpisodes.NONE || tmdbId == null || tmdbId <= 0) return MissingSeasonRequests()
        val status = (requests.status() as? ApiResult.Success)?.data
        // A download server that cannot fetch single seasons would add the whole series again.
        if (status?.isAvailable != true || !status.seriesServerConnected || !status.missingSeasonsRequestable) {
            return MissingSeasonRequests()
        }
        val media = (requests.detail(RequestMediaType.Series, tmdbId) as? ApiResult.Success)?.data
            ?: return MissingSeasonRequests()
        return MissingSeasonRequests(
            canRequest = true,
            requestedSeasons = media.seasons.filter { it.requested }.map { it.seasonNumber }.toSet(),
            media = media,
        )
    }

    /** Requests one season; reports whether the server took it. */
    suspend fun request(media: RequestMediaDetail, season: Int): Boolean {
        val result = requests.create(
            CreateMediaRequest(
                mediaType = RequestMediaType.Series,
                tmdbId = media.tmdbId,
                tvdbId = media.tvdbId,
                imdbId = media.imdbId,
                title = media.title,
                year = media.year,
                overview = media.overview,
                posterPath = media.posterPath,
                backdropPath = media.backdropPath,
                seasons = listOf(season),
            ),
        )
        return result is ApiResult.Success
    }
}
