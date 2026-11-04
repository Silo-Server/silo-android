package org.siloserver.silo.common.downloads

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadSubscription
import org.siloserver.silo.model.download.DownloadSubscriptionMediaKind
import org.siloserver.silo.model.download.DownloadSubscriptionTargetType
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSubscriptionEvaluatorTest {
    @Test
    fun evaluatorEnqueuesMissingUnwatchedCandidatesOnly() = runTest {
        val provider = FakeProvider(
            candidates = listOf(
                DownloadSubscriptionCandidate("c1", 1, "Episode 1", completed = false),
                DownloadSubscriptionCandidate("c2", 2, "Episode 2", completed = true),
                DownloadSubscriptionCandidate("c3", 3, "Episode 3", completed = false),
            ),
        )
        val enqueued = mutableListOf<DownloadSubscriptionCandidate>()
        val evaluator = DownloadSubscriptionEvaluator(
            candidateProvider = provider,
            existingFileIds = { _ -> setOf(3) },
            enqueue = { candidate, quality ->
                enqueued += candidate
                assertEquals(DownloadQuality.Mbps10, quality)
            },
        )

        evaluator.evaluate(subscription(quality = DownloadQuality.Mbps10))

        assertEquals(listOf(1), enqueued.map { it.mediaFileId })
    }

    @Test
    fun evaluatorSkipsReadingCandidatesWhenTvSurfaceIsRequested() = runTest {
        val provider = FakeProvider(listOf(DownloadSubscriptionCandidate("ebook", 9, "Book", completed = false)))
        val enqueued = mutableListOf<DownloadSubscriptionCandidate>()
        val evaluator = DownloadSubscriptionEvaluator(
            candidateProvider = provider,
            existingFileIds = { _ -> emptySet() },
            enqueue = { candidate, _ -> enqueued += candidate },
            allowReading = false,
        )

        evaluator.evaluate(subscription(mediaKind = DownloadSubscriptionMediaKind.Reading))

        assertEquals(emptyList(), enqueued)
    }

    @Test
    fun evaluatorHonorsKeepUnwatchedLimit() = runTest {
        val provider = FakeProvider(
            listOf(
                DownloadSubscriptionCandidate("c1", 1, "Episode 1", completed = false),
                DownloadSubscriptionCandidate("c2", 2, "Episode 2", completed = false),
                DownloadSubscriptionCandidate("c3", 3, "Episode 3", completed = false),
            ),
        )
        val enqueued = mutableListOf<DownloadSubscriptionCandidate>()
        val evaluator = DownloadSubscriptionEvaluator(
            candidateProvider = provider,
            existingFileIds = { _ -> emptySet() },
            enqueue = { candidate, _ -> enqueued += candidate },
        )

        val queued = evaluator.evaluate(subscription(keepUnwatchedLimit = 2))

        assertEquals(2, queued)
        assertEquals(listOf(1, 2), enqueued.map { it.mediaFileId })
    }

    private fun subscription(
        quality: DownloadQuality = DownloadQuality.Original,
        mediaKind: DownloadSubscriptionMediaKind = DownloadSubscriptionMediaKind.Video,
        keepUnwatchedLimit: Int = 3,
    ) = DownloadSubscription(
        id = "sub",
        serverId = "server",
        accountId = "account",
        profileId = "profile",
        targetType = DownloadSubscriptionTargetType.Series,
        targetId = "series",
        displayTitle = "Series",
        mediaKind = mediaKind,
        quality = quality,
        keepUnwatchedLimit = keepUnwatchedLimit,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeProvider(
        private val candidates: List<DownloadSubscriptionCandidate>,
    ) : DownloadSubscriptionCandidateProvider {
        override suspend fun candidatesFor(subscription: DownloadSubscription): List<DownloadSubscriptionCandidate> =
            candidates
    }
}
