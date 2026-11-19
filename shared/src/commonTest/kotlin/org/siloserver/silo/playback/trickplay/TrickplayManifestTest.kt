package org.siloserver.silo.playback.trickplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.siloserver.silo.network.apiv2.ApiV2Fixtures
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.with
import org.siloserver.silo.network.apiv2.WatchTrickplayV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The server's seek-preview manifest, validated into the shape the players cut thumbnails from. */
class TrickplayManifestTest {
    private val base = "https://silo.example:8090/api/v2/watch/movie:heat-1995/trickplay?file_id=42"

    private fun fixture() = ApiV2Fixtures.bodyObject("get_watch_trickplay_ok")

    private fun parse(body: kotlinx.serialization.json.JsonObject = fixture()): TrickplayManifest? =
        TrickplayManifest.from(ApiV2Fixtures.decode<WatchTrickplayV2>(body), base)

    @Test
    fun theFixtureParsesWithSheetsResolvedAgainstTheServer() {
        val manifest = assertNotNull(parse())
        assertEquals(42, manifest.fileId)
        assertEquals(10_000L, manifest.intervalMs)
        assertEquals(300, manifest.thumbnailWidth)
        assertEquals(126, manifest.thumbnailHeight)
        assertEquals(10, manifest.columns)
        assertEquals(10, manifest.rows)
        assertEquals(1020, manifest.count)
        assertEquals(11, manifest.sheetUrls.size)
        assertEquals("https://silo.example:8090/api/v2/artwork/trickplay/42/9913/0.9913.jpg?exp=1767409445&sig=a", manifest.sheetUrls.first())
        assertEquals("https://silo.example:8090/api/v2/artwork/trickplay/42/9913/10.9913.jpg?exp=1767409445&sig=k", manifest.sheetUrls.last())
        // 2026-01-03T03:04:05Z
        assertEquals(1_767_409_445_000L, manifest.expiresAtEpochMs)
    }

    private fun sheets(vararg entries: Pair<Int, String>) = "sheets" to JsonArray(
        entries.map { (index, url) -> buildJsonObject { put("index", index); put("url", url) } },
    )

    private fun elevenSheets(): Array<Pair<Int, String>> = Array(11) { it to "/s/$it.jpg?sig=$it" }

    @Test
    fun sheetsArriveInIndexOrderWhateverOrderTheyAreListed() {
        val body = with(fixture(), sheets(*elevenSheets().reversedArray()))
        assertEquals(
            (0..10).map { "https://silo.example:8090/s/$it.jpg?sig=$it" },
            assertNotNull(parse(body)).sheetUrls,
        )
    }

    @Test
    fun anAbsoluteSheetUrlIsKept() {
        val presigned = "https://bucket.s3.example/trickplay/42/0.jpg?X-Amz-Signature=abc"
        val urls = elevenSheets().also { it[0] = 0 to presigned }
        assertEquals(presigned, assertNotNull(parse(with(fixture(), sheets(*urls)))).sheetUrls.first())
    }

    @Test
    fun aManifestThatCannotBeCutSafelyIsRefused() {
        val refused = mapOf(
            "too few sheets for every thumbnail" to with(fixture(), "thumbnail_count" to JsonPrimitive(1101)),
            "more sheets than thumbnails" to with(fixture(), "thumbnail_count" to JsonPrimitive(1000)),
            "a repeated sheet index" to with(fixture(), sheets(*elevenSheets().also { it[10] = 9 to "/dup" })),
            "a sheet that is not http" to with(fixture(), sheets(*elevenSheets().also { it[3] = 3 to "ftp://x/3.jpg" })),
            "no thumbnails" to with(fixture(), "thumbnail_count" to JsonPrimitive(0), sheets()),
            "a zero interval" to with(fixture(), "interval_ms" to JsonPrimitive(0)),
            "a negative width" to with(fixture(), "thumbnail_width" to JsonPrimitive(-300)),
            "a zero row count" to with(fixture(), "tile_rows" to JsonPrimitive(0)),
            "geometry that overflows" to with(fixture(), "tile_columns" to JsonPrimitive(Long.MAX_VALUE)),
            "a sheet too large to decode" to with(fixture(), "thumbnail_width" to JsonPrimitive(100_000)),
            "a file id that is not a v2 id" to with(fixture(), "file_id" to JsonPrimitive("0")),
        )
        refused.forEach { (why, body) -> assertNull(parse(body), why) }
    }
}
