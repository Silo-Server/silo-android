package org.siloserver.silo.playback.trickplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Thumbnail i shows `[i*interval, (i+1)*interval)` on sheet `i / (columns*rows)`, row-major. */
class TrickplayTileTest {
    private val manifest = TrickplayManifest(
        fileId = 42, intervalMs = 10_000, thumbnailWidth = 300, thumbnailHeight = 126,
        columns = 10, rows = 10, count = 1020,
        sheetUrls = List(11) { "https://silo.example/s/$it.jpg" }, expiresAtEpochMs = null,
    )

    @Test
    fun aTimeMapsToTheThumbnailCoveringIt() {
        assertEquals(TrickplayTile(sheet = 0, x = 0, y = 0, width = 300, height = 126), manifest.tileAt(0.0))
        assertEquals(0, manifest.tileAt(9.999)?.x)
        assertEquals(TrickplayTile(sheet = 0, x = 300, y = 0, width = 300, height = 126), manifest.tileAt(10.0))
        // Thumbnail 123: sheet 1, cell 23, so column 3 and row 2.
        assertEquals(TrickplayTile(sheet = 1, x = 900, y = 252, width = 300, height = 126), manifest.tileAt(1_235.0))
    }

    @Test
    fun aTimePastTheEndShowsTheLastThumbnail() {
        // Thumbnail 1019: sheet 10, cell 19, so column 9 and row 1.
        val last = TrickplayTile(sheet = 10, x = 2_700, y = 126, width = 300, height = 126)
        assertEquals(last, manifest.tileAt(10_195.0))
        assertEquals(last, manifest.tileAt(99_999.0))
        assertEquals(last, manifest.tileAt(1e300))
    }

    @Test
    fun aTimeOutsideTheFileHasNoThumbnail() {
        assertNull(manifest.tileAt(-0.5))
        assertNull(manifest.tileAt(Double.NaN))
        assertNull(manifest.tileAt(Double.POSITIVE_INFINITY))
    }
}
