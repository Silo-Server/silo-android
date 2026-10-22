package org.siloserver.silo.tv.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siloserver.silo.model.catalog.ItemDetail

/** The same cases as silo-apple's SiloControlNowPlayingMetadataTests. */
@OptIn(ExperimentalCoroutinesApi::class)
class SiloCastNowPlayingMetadataTest {

    // Choosing artwork

    @Test
    fun episodeUsesSeriesPosterAndItsOwnOverview() = runTest {
        val details = mapOf(
            "ep" to item("ep", "episode", poster = "https://silo.example/still.webp", overview = "Episode plot", seriesId = "show"),
            "show" to item("show", "series", poster = "https://silo.example/series.webp", overview = "Series plot"),
        )
        val metadata = SiloCastNowPlayingMetadataResolver.loadFromCatalog("ep") { details[it] }
        assertEquals("https://silo.example/series.webp", metadata?.posterUrl)
        assertEquals("Episode plot", metadata?.overview)
    }

    /** The episode's own poster is its still, which may be a spoiler. */
    @Test
    fun episodeNeverSendsItsStill() {
        val episode = item("ep", "episode", poster = "https://silo.example/still.webp", overview = " ", seriesId = "show")
        val metadata = SiloCastNowPlayingMetadata.from(episode, item("show", "series", poster = null, overview = "Series plot"))
        assertNull(metadata.posterUrl)
        assertEquals("Series plot", metadata.overview)
        assertNull(SiloCastNowPlayingMetadata.from(episode, null).posterUrl)
    }

    @Test
    fun failedSeriesLookupFailsTheLoadSoItIsRetried() = runTest {
        val episode = item("ep", "episode", poster = "https://silo.example/still.webp", overview = "Plot", seriesId = "show")
        assertNull(SiloCastNowPlayingMetadataResolver.loadFromCatalog("ep") { if (it == "ep") episode else null })
    }

    @Test
    fun movieUsesItsOwnPosterAndSkipsTheSeriesLookup() = runTest {
        val requested = mutableListOf<String>()
        val metadata = SiloCastNowPlayingMetadataResolver.loadFromCatalog("m") {
            requested += it
            item("m", "movie", poster = "https://silo.example/movie.webp", overview = "Plot")
        }
        assertEquals("https://silo.example/movie.webp", metadata?.posterUrl)
        assertEquals(listOf("m"), requested)
    }

    // Expiry

    @Test
    fun readsSiloAndS3Expiry() {
        assertEquals(
            1_700_000_000_000L,
            SiloCastArtworkExpiry.expiryEpochMs("https://silo.example/api/v2/artwork/p.webp?exp=1700000000&sig=x"),
        )
        assertEquals(
            (1_700_000_000L + 3600) * 1000,
            SiloCastArtworkExpiry.expiryEpochMs(
                "https://s3.example/p.webp?X-Amz-Date=20231114T221320Z&X-Amz-Expires=3600&X-Amz-Signature=x",
            ),
        )
        assertNull(SiloCastArtworkExpiry.expiryEpochMs("https://cdn.example/p.webp"))
    }

    @Test
    fun refreshesAheadOfExpiry() {
        val now = 1_000_000_000L
        val url = "https://silo.example/p.webp?exp=${now / 1000 + 4 * 3600}&sig=x"
        assertEquals(4 * 3600_000L - SiloCastArtworkExpiry.REFRESH_MARGIN_MS, SiloCastArtworkExpiry.refreshDelayMs(url, now))
        val stale = "https://silo.example/p.webp?exp=${now / 1000 - 10}&sig=x"
        assertEquals(SiloCastArtworkExpiry.MINIMUM_REFRESH_MS, SiloCastArtworkExpiry.refreshDelayMs(stale, now))
        assertEquals(
            SiloCastArtworkExpiry.UNKNOWN_EXPIRY_REFRESH_MS,
            SiloCastArtworkExpiry.refreshDelayMs("https://cdn.example/p.webp", now),
        )
        assertNull(SiloCastArtworkExpiry.refreshDelayMs(null, now))
    }

    // Resolver

    @Test
    fun resolvesRefreshesAndClears() = runTest {
        val now = 1_000_000_000L
        val loads = mutableListOf<Pair<String, Boolean>>()
        val sleeps = mutableListOf<Long>()
        var release = CompletableDeferred<Unit>()
        var changes = 0
        var signature = 0
        val resolver = SiloCastNowPlayingMetadataResolver(
            scope = this,
            load = { contentId, allowCached ->
                loads += contentId to allowCached
                signature += 1
                SiloCastNowPlayingMetadata(
                    posterUrl = "https://silo.example/$contentId.webp?exp=${now / 1000 + 3600}&sig=$signature",
                    overview = "$contentId plot",
                )
            },
            nowEpochMs = { now },
            onChange = { changes += 1 },
            sleep = { sleeps += it; release.await() },
        )

        resolver.track("a")
        assertNull("nothing until the load finishes", resolver.metadata("a"))
        runCurrent()
        assertEquals(1, changes)
        assertEquals("a plot", resolver.metadata("a")?.overview)
        assertTrue(resolver.metadata("a")?.posterUrl!!.endsWith("sig=1"))
        assertNull("never reported for another title", resolver.metadata("b"))
        assertEquals(listOf(3600_000L - SiloCastArtworkExpiry.REFRESH_MARGIN_MS), sleeps)

        // The refresh skips the cache and swaps in the re-signed URL.
        release.complete(Unit).also { release = CompletableDeferred() }
        runCurrent()
        assertEquals(2, changes)
        assertEquals("a" to false, loads.last())
        assertTrue(resolver.metadata("a")?.posterUrl!!.endsWith("sig=2"))

        // A new title drops the old metadata at once.
        resolver.track("b")
        assertNull(resolver.metadata("a"))
        assertNull(resolver.metadata("b"))
        runCurrent()
        assertEquals("b plot", resolver.metadata("b")?.overview)

        // Playback stopping clears it.
        resolver.track(null)
        assertNull(resolver.metadata("b"))
        runCurrent()
    }

    @Test
    fun expiredPosterIsWithheldUntilReSigned() = runTest {
        var now = 1_000_000_000L
        val resolver = SiloCastNowPlayingMetadataResolver(
            scope = this,
            load = { _, _ ->
                SiloCastNowPlayingMetadata("https://silo.example/p.webp?exp=${now / 1000 + 60}&sig=x", "Plot")
            },
            nowEpochMs = { now },
            sleep = { CompletableDeferred<Unit>().await() },
        )
        resolver.track("a")
        runCurrent()
        assertNotNull(resolver.metadata("a")?.posterUrl)

        now += 120_000
        assertNull(resolver.metadata("a")?.posterUrl)
        assertEquals("Plot", resolver.metadata("a")?.overview)
        resolver.track(null)
        runCurrent()
    }

    @Test
    fun cancelledLoadCannotPublishAfterTheTitleComesBack() = runTest {
        val firstLoad = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = SiloCastNowPlayingMetadataResolver(
            scope = this,
            load = { _, _ ->
                calls += 1
                if (calls == 1) {
                    withContext(NonCancellable) { firstLoad.await() }
                    SiloCastNowPlayingMetadata("https://silo.example/old.webp", "old")
                } else {
                    SiloCastNowPlayingMetadata("https://silo.example/new.webp", "new")
                }
            },
            nowEpochMs = { 0L },
            sleep = { CompletableDeferred<Unit>().await() },
        )
        resolver.track("a")
        runCurrent()
        resolver.track("b")
        resolver.track("a")
        runCurrent()
        assertEquals("new", resolver.metadata("a")?.overview)

        firstLoad.complete(Unit)
        runCurrent()
        assertEquals("new", resolver.metadata("a")?.overview)
        resolver.track(null)
        runCurrent()
    }

    private fun item(
        id: String,
        type: String,
        poster: String?,
        overview: String?,
        seriesId: String? = null,
    ) = ItemDetail(contentId = id, type = type, title = id, overview = overview, posterUrl = poster, seriesId = seriesId)
}
