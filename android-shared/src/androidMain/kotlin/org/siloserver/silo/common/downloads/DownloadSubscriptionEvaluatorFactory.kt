package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadSubscription
import org.siloserver.silo.model.download.DownloadSubscriptionTargetType
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository

class DownloadSubscriptionEvaluatorFactory internal constructor(
    private val catalogRepository: CatalogRepository,
    private val existingFileIds: suspend (DownloadSubscription) -> Set<Int>,
    private val enqueue: suspend (DownloadSubscriptionCandidate, DownloadQuality) -> Unit,
) {
    constructor(
        catalogRepository: CatalogRepository,
        metadataStore: DownloadMetadataStore,
        downloadEnqueuer: DownloadEnqueuer,
    ) : this(catalogRepository,
        existingFileIds = { subscription ->
            metadataStore.listSidecars(subscription.serverId, subscription.profileId)
                .map { it.record.mediaFileId }.toSet()
        },
        enqueue = { candidate, quality -> enqueueCandidate(downloadEnqueuer, candidate, quality) },
    )

    fun create(): DownloadSubscriptionEvaluator = DownloadSubscriptionEvaluator(
        candidateProvider = CatalogDownloadSubscriptionCandidateProvider(catalogRepository),
        existingFileIds = existingFileIds,
        enqueue = enqueue,
    )
}

private suspend fun enqueueCandidate(
    downloadEnqueuer: DownloadEnqueuer,
    candidate: DownloadSubscriptionCandidate,
    quality: DownloadQuality,
) {
    val result = if (
        candidate.seriesContentId != null &&
        candidate.seasonNumber != null &&
        candidate.episodeNumber != null
    ) {
        downloadEnqueuer.startEpisode(
            seriesContentId = candidate.seriesContentId,
            episodeContentId = candidate.contentId,
            fileId = candidate.mediaFileId,
            seriesTitle = candidate.seriesTitle ?: candidate.title,
            seasonNumber = candidate.seasonNumber,
            episodeNumber = candidate.episodeNumber,
            episodeTitle = candidate.title,
            posterUrl = candidate.posterUrl,
            downloadQualityOverride = quality,
        )
    } else {
        downloadEnqueuer.start(
            contentId = candidate.contentId,
            fileId = candidate.mediaFileId,
            displayTitle = candidate.title,
            downloadQualityOverride = quality,
        )
    }

    when (result) {
        is ApiResult.Success -> Unit
        is ApiResult.Error -> error(result.message ?: result.error ?: "Download enqueue failed (${result.code})")
        is ApiResult.NetworkError -> throw result.exception
    }
}

private class CatalogDownloadSubscriptionCandidateProvider(
    private val catalogRepository: CatalogRepository,
) : DownloadSubscriptionCandidateProvider {
    override suspend fun candidatesFor(subscription: DownloadSubscription): List<DownloadSubscriptionCandidate> =
        when (subscription.targetType) {
            DownloadSubscriptionTargetType.Series -> seriesCandidates(subscription)
            DownloadSubscriptionTargetType.Season -> seasonCandidates(subscription)
            DownloadSubscriptionTargetType.AudiobookSeries,
            DownloadSubscriptionTargetType.Author,
            DownloadSubscriptionTargetType.Collection,
            -> emptyList()
        }

    private suspend fun seriesCandidates(subscription: DownloadSubscription): List<DownloadSubscriptionCandidate> {
        val seasons = when (val result = catalogRepository.getSeasons(subscription.targetId)) {
            is ApiResult.Success -> result.data.seasons
            is ApiResult.Error -> error(result.message ?: result.error ?: "Could not load seasons")
            is ApiResult.NetworkError -> throw result.exception
        }

        return seasons.flatMap { season ->
            val episodes = when (val result = catalogRepository.getEpisodes(subscription.targetId, season.seasonNumber)) {
                is ApiResult.Success -> result.data.episodes
                is ApiResult.Error -> error(result.message ?: result.error ?: "Could not load episodes")
                is ApiResult.NetworkError -> throw result.exception
            }
            episodes.mapNotNull { episode ->
                val file = episode.files.firstOrNull() ?: return@mapNotNull null
                DownloadSubscriptionCandidate(
                    contentId = episode.contentId,
                    mediaFileId = file.fileId,
                    title = episode.title?.takeIf { it.isNotBlank() }
                        ?: "Episode ${episode.episodeNumber}",
                    completed = episode.userData?.played == true,
                    seriesContentId = subscription.targetId,
                    seriesTitle = subscription.displayTitle,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                )
            }
        }
    }

    private suspend fun seasonCandidates(subscription: DownloadSubscription): List<DownloadSubscriptionCandidate> {
        val episodes = when (val result = catalogRepository.getItemEpisodes(subscription.targetId)) {
            is ApiResult.Success -> result.data.episodes
            is ApiResult.Error -> error(result.message ?: result.error ?: "Could not load season episodes")
            is ApiResult.NetworkError -> throw result.exception
        }
        return episodes.mapNotNull { episode ->
            val file = episode.files.firstOrNull() ?: return@mapNotNull null
            DownloadSubscriptionCandidate(
                contentId = episode.contentId,
                mediaFileId = file.fileId,
                title = episode.title?.takeIf { it.isNotBlank() } ?: "Episode ${episode.episodeNumber}",
                completed = episode.userData?.played == true,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
            )
        }
    }
}
