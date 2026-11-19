package org.siloserver.silo.playback.trickplay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TrickplayPreviewsTest {
    private val target = TrickplayTarget(contentId = "movie:heat-1995", fileId = 42)

    /** Four sheets of 2 x 1 thumbnails, 10 seconds each: thumbnail i is on sheet i / 2. */
    private fun manifest(signature: String = "a", columns: Int = 2, expiresAt: Long? = Long.MAX_VALUE) = TrickplayManifest(
        fileId = 42, intervalMs = 10_000, thumbnailWidth = 20, thumbnailHeight = 20,
        columns = columns, rows = 1, count = 8,
        sheetUrls = List((8 + columns - 1) / columns) { "https://silo.example/s/$it.jpg?sig=$signature" },
        expiresAtEpochMs = expiresAt,
    )

    private class FakeManifests(var answer: ApiResult<TrickplayManifest>) : TrickplayManifestSource {
        val reads = mutableListOf<TrickplayTarget>()
        /** When set, reads wait for it before answering. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun manifest(target: TrickplayTarget): ApiResult<TrickplayManifest> {
            reads += target
            gate?.await()
            return answer
        }
    }

    private class FakeSheets : TrickplaySheetSource {
        val requests = mutableListOf<String>()
        var answer: (String) -> TrickplaySheet = { TrickplaySheet.Loaded(it.encodeToByteArray()) }
        /** When set, downloads wait for it before answering. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun sheet(url: String): TrickplaySheet {
            requests += url
            gate?.await()
            return answer(url)
        }
        fun sheetsRequested() = requests.map { it.substringAfter("/s/").substringBefore(".jpg").toInt() }
    }

    private var decodes = 0
    /** When set, cutting a thumbnail waits for it. */
    private var decodeGate: CompletableDeferred<Unit>? = null

    /** "sheet URL#x,y": which sheet a thumbnail was cut from, and where. */
    private val decoder = TrickplayTileDecoder { bytes, tile, _ ->
        decodes++
        decodeGate?.await()
        "${bytes.decodeToString()}#${tile.x},${tile.y}"
    }

    private fun TestScope.previews(manifests: FakeManifests, sheets: FakeSheets, sheetCacheBytes: Long = 1_000_000) =
        TrickplayPreviews(manifests, sheets, decoder, backgroundScope, now = { testScheduler.currentTime }, sheetCacheBytes = sheetCacheBytes)

    @Test
    fun aScrubShowsTheThumbnailCutFromItsSheet() = runTest {
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), FakeSheets())
        previews.bind(target)
        runCurrent()

        // 35s is thumbnail 3: sheet 1, second column.
        previews.show(35.0)
        runCurrent()

        assertEquals("https://silo.example/s/1.jpg?sig=a#20,0", previews.thumbnail.value)
    }

    @Test
    fun sheetsLoadOnlyAsAScrubReachesThemAndAreReused() = runTest {
        val sheets = FakeSheets()
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), sheets)
        previews.bind(target)
        runCurrent()
        assertEquals(emptyList(), sheets.requests, "binding fetches no sheet")

        for (seconds in listOf(35.0, 30.0, 25.0)) {
            previews.show(seconds)
            runCurrent()
        }
        // Sheet 1, and sheet 2 ahead of a forward scrub.
        assertEquals(listOf(1, 2), sheets.sheetsRequested())
        assertEquals("https://silo.example/s/1.jpg?sig=a#0,0", previews.thumbnail.value)

        previews.show(45.0)
        runCurrent()
        previews.show(5.0)
        runCurrent()
        assertEquals(listOf(1, 2, 3, 0), sheets.sheetsRequested())
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value)
    }

    @Test
    fun withoutAStreamedFileThereIsNothingToFetch() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest()))
        val sheets = FakeSheets()
        val previews = previews(manifests, sheets)
        previews.bind(null)
        previews.show(35.0)
        runCurrent()

        assertNull(previews.thumbnail.value)
        assertEquals(emptyList(), manifests.reads)
        assertEquals(emptyList(), sheets.requests)
    }

    @Test
    fun aFileWithoutPreviewsStaysTextOnly() = runTest {
        val manifests = FakeManifests(ApiResult.Error(404, "not_found", "This file has no seek-bar previews."))
        val sheets = FakeSheets()
        val previews = previews(manifests, sheets)
        previews.bind(target)
        runCurrent()

        repeat(3) {
            previews.show(35.0)
            runCurrent()
            advanceTimeBy(60_000)
        }

        assertNull(previews.thumbnail.value)
        assertEquals(1, manifests.reads.size, "a refusal is not asked again")
        assertEquals(emptyList(), sheets.requests)
    }

    @Test
    fun aManifestThatFailedTransientlyIsReadAgainOnALaterScrub() = runTest {
        val manifests = FakeManifests(ApiResult.Error(503, "unavailable", ""))
        val previews = previews(manifests, FakeSheets())
        previews.bind(target)
        runCurrent()

        previews.show(35.0)
        runCurrent()
        assertNull(previews.thumbnail.value)
        assertEquals(1, manifests.reads.size, "a scrub inside the backoff does not ask again")

        manifests.answer = ApiResult.Success(manifest())
        advanceTimeBy(30_001)
        previews.show(35.0)
        runCurrent()

        assertEquals(2, manifests.reads.size)
        assertEquals("https://silo.example/s/1.jpg?sig=a#20,0", previews.thumbnail.value, "the scrub that asked again shows its thumbnail")
    }

    @Test
    fun aFailingSheetShowsNoPictureAndIsNotRequestedForEveryScrub() = runTest {
        val sheets = FakeSheets()
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), sheets)
        previews.bind(target)
        runCurrent()
        previews.show(25.0)
        runCurrent()
        assertEquals("https://silo.example/s/1.jpg?sig=a#0,0", previews.thumbnail.value)

        sheets.answer = { url -> if ("/s/0." in url) TrickplaySheet.Failed else TrickplaySheet.Loaded(url.encodeToByteArray()) }
        for (seconds in listOf(0.0, 5.0, 12.0)) {
            previews.show(seconds)
            runCurrent()
            assertNull(previews.thumbnail.value, "no stale picture at ${seconds}s")
        }
        assertEquals(1, sheets.sheetsRequested().count { it == 0 })

        sheets.answer = { TrickplaySheet.Loaded(it.encodeToByteArray()) }
        advanceTimeBy(30_001)
        previews.show(5.0)
        runCurrent()
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value)
    }

    @Test
    fun heldSheetsStayWithinTheCacheBound() = runTest {
        val sheets = FakeSheets()
        // Each fake sheet is its 34-byte URL, so two fit.
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), sheets, sheetCacheBytes = 70)
        previews.bind(target)
        runCurrent()

        for (seconds in listOf(5.0, 45.0, 5.0)) {
            previews.show(seconds)
            runCurrent()
        }

        // Sheets 2 and 3 pushed out 0 and 1, so going back fetches them again.
        assertEquals(listOf(0, 1, 2, 3, 0, 1), sheets.sheetsRequested())
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value)
    }

    @Test
    fun aRefusedSheetRenewsTheManifestAndRetriesWithTheFreshUrl() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest(signature = "old")))
        val sheets = FakeSheets().apply {
            answer = { url -> if ("sig=old" in url) TrickplaySheet.Refused else TrickplaySheet.Loaded(url.encodeToByteArray()) }
        }
        val previews = previews(manifests, sheets)
        previews.bind(target)
        runCurrent()
        manifests.answer = ApiResult.Success(manifest(signature = "new"))

        previews.show(5.0)
        runCurrent()

        assertEquals("https://silo.example/s/0.jpg?sig=new#0,0", previews.thumbnail.value)
        assertEquals(2, manifests.reads.size)
        assertEquals(listOf("old", "new"), sheets.requests.filter { "/s/0." in it }.map { it.substringAfter("sig=") })
    }

    @Test
    fun urlsAboutToExpireAreRenewedFirstAndANewLayoutReplacesTheSheets() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest(signature = "old", expiresAt = 30_000)))
        val sheets = FakeSheets()
        val previews = previews(manifests, sheets)
        previews.bind(target)
        runCurrent()
        // The previews were made again with four thumbnails across: two sheets now.
        manifests.answer = ApiResult.Success(manifest(signature = "new", columns = 4))

        previews.show(25.0)
        runCurrent()

        // Thumbnail 2 is now on sheet 0, third column.
        assertEquals("https://silo.example/s/0.jpg?sig=new#40,0", previews.thumbnail.value)
        assertEquals(listOf("new", "new"), sheets.requests.map { it.substringAfter("sig=") })
        assertEquals(listOf(0, 1), sheets.sheetsRequested())
    }

    @Test
    fun aFileChangeMidScrubAnswersFromTheNewFileOnly() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest(signature = "a")))
        val sheets = FakeSheets().apply { gate = CompletableDeferred() }
        val previews = previews(manifests, sheets)
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()

        // A version switch while the old file's sheet is still downloading.
        manifests.answer = ApiResult.Success(manifest(signature = "b").copy(fileId = 43))
        previews.bind(TrickplayTarget(contentId = "movie:heat-1995", fileId = 43))
        runCurrent()
        sheets.gate!!.complete(Unit)
        runCurrent()

        assertEquals("https://silo.example/s/0.jpg?sig=b#0,0", previews.thumbnail.value)
        assertEquals(1, decodes, "only the new file's sheet was cut")
    }

    @Test
    fun scrubbingWithinOneThumbnailCutsItOnce() = runTest {
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), FakeSheets())
        previews.bind(target)
        runCurrent()

        for (seconds in listOf(30.0, 31.5, 33.0, 39.9)) {
            previews.show(seconds)
            runCurrent()
        }

        assertEquals(1, decodes)
        assertEquals("https://silo.example/s/1.jpg?sig=a#20,0", previews.thumbnail.value)
    }

    @Test
    fun aSlowSheetKeepsThePreviousThumbnailOnlyBriefly() = runTest {
        val sheets = FakeSheets()
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), sheets)
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value)

        // A jump to sheet 3 (thumbnail 6) on a slow link.
        sheets.gate = CompletableDeferred()
        previews.show(65.0)
        advanceTimeBy(500)
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value, "no flash while the next one loads")
        advanceTimeBy(600)
        assertNull(previews.thumbnail.value, "after a second the old picture no longer stands for the new time")

        sheets.gate!!.complete(Unit)
        runCurrent()
        assertEquals("https://silo.example/s/3.jpg?sig=a#0,0", previews.thumbnail.value)
    }

    @Test
    fun aSlowCutKeepsThePreviousThumbnailOnlyBriefly() = runTest {
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), FakeSheets())
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()

        // Sheet 1 is held (fetched ahead of a forward scrub); only cutting 25s is slow.
        decodeGate = CompletableDeferred()
        previews.show(25.0)
        advanceTimeBy(500)
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value, "no flash while the next one is cut")
        advanceTimeBy(600)
        assertNull(previews.thumbnail.value, "after a second the old picture no longer stands for the new time")

        decodeGate!!.complete(Unit)
        runCurrent()
        assertEquals("https://silo.example/s/1.jpg?sig=a#0,0", previews.thumbnail.value)
    }

    @Test
    fun anEarlierScrubToTheSameThumbnailDoesNotCutAPictureShort() = runTest {
        val sheets = FakeSheets()
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), sheets)
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()

        // Out to 65s on a slow sheet, over to 25s (held) at 100ms, back to 65s at 900ms.
        sheets.gate = CompletableDeferred()
        previews.show(65.0)
        advanceTimeBy(100)
        previews.show(25.0)
        runCurrent()
        assertEquals("https://silo.example/s/1.jpg?sig=a#0,0", previews.thumbnail.value)
        advanceTimeBy(800)
        previews.show(65.0)
        advanceTimeBy(150)
        assertEquals("https://silo.example/s/1.jpg?sig=a#0,0", previews.thumbnail.value, "25s has stood in for 65s only 150ms")
        advanceTimeBy(900)
        assertNull(previews.thumbnail.value)

        sheets.gate!!.complete(Unit)
        runCurrent()
        assertEquals("https://silo.example/s/3.jpg?sig=a#0,0", previews.thumbnail.value)
    }

    @Test
    fun aContinuousScrubOverASlowSheetStillDropsTheOldThumbnailAfterASecond() = runTest {
        val sheets = FakeSheets()
        val previews = previews(FakeManifests(ApiResult.Success(manifest())), sheets)
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()

        // An auto-seek crossing a new thumbnail every 400ms, into sheets still downloading.
        sheets.gate = CompletableDeferred()
        for (seconds in listOf(45.0, 55.0, 65.0, 75.0)) {
            previews.show(seconds)
            advanceTimeBy(400)
        }

        assertNull(previews.thumbnail.value, "1.6s of scrubbing past the old picture")
    }

    @Test
    fun aSlowUrlRenewalAlsoDropsTheOldThumbnailAfterASecond() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest(expiresAt = 120_000)))
        val previews = previews(manifests, FakeSheets())
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()
        assertEquals("https://silo.example/s/0.jpg?sig=a#0,0", previews.thumbnail.value)

        // The URLs now expire within the renewal margin, and the renewal hangs.
        advanceTimeBy(70_000)
        manifests.gate = CompletableDeferred()
        manifests.answer = ApiResult.Success(manifest(signature = "b"))
        previews.show(65.0)
        advanceTimeBy(1_100)
        assertNull(previews.thumbnail.value)

        manifests.gate!!.complete(Unit)
        runCurrent()
        assertEquals("https://silo.example/s/3.jpg?sig=b#0,0", previews.thumbnail.value)
    }

    @Test
    fun anEarlierTargetsSheetCannotReturnDuringASlowRenewal() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest(expiresAt = 120_000)))
        val sheets = FakeSheets()
        val previews = previews(manifests, sheets)
        previews.bind(target)
        runCurrent()
        previews.show(5.0)
        runCurrent()

        // Sheet 2 (not prefetched yet) is still downloading when the URLs start to expire.
        sheets.gate = CompletableDeferred()
        previews.show(45.0)
        runCurrent()
        advanceTimeBy(70_000)
        manifests.gate = CompletableDeferred()
        previews.show(65.0)
        advanceTimeBy(1_100)
        sheets.gate!!.complete(Unit)
        runCurrent()

        assertNull(previews.thumbnail.value, "sheet 2's thumbnail does not stand for 65s")
    }

    @Test
    fun aRefusedSheetWhosePreviewsWereRemadeShowsTheNewLayoutWithoutAnotherScrub() = runTest {
        val manifests = FakeManifests(ApiResult.Success(manifest(signature = "old")))
        val sheets = FakeSheets().apply {
            answer = { url -> if ("sig=old" in url) TrickplaySheet.Refused else TrickplaySheet.Loaded(url.encodeToByteArray()) }
        }
        val previews = previews(manifests, sheets)
        previews.bind(target)
        runCurrent()
        // The previews were made again with four thumbnails across.
        manifests.answer = ApiResult.Success(manifest(signature = "new", columns = 4))

        previews.show(25.0)
        runCurrent()

        // Thumbnail 2 is now on sheet 0, third column.
        assertEquals("https://silo.example/s/0.jpg?sig=new#40,0", previews.thumbnail.value)
    }
}
