package org.siloserver.silo.model.download

import org.siloserver.silo.model.catalog.PlaybackMarkerSegment
import org.siloserver.silo.model.catalog.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadManifestTest {
    @Test
    fun absentMarkerInventoryRemainsUnknown() {
        val manifest = DownloadManifest(downloadId = "download-1", mediaFileId = 7)

        assertNull(manifest.effectiveMarkerSegments())
    }

    @Test
    fun explicitEmptyInventorySuppressesLegacyMarkers() {
        val manifest = DownloadManifest(
            downloadId = "download-1",
            mediaFileId = 7,
            intro = TimeRange(10.0, 30.0),
            credits = TimeRange(100.0, 120.0),
            markerSegments = emptyList(),
        )

        assertEquals(emptyList(), manifest.effectiveMarkerSegments())
    }

    @Test
    fun explicitInventoryPreservesEveryOccurrenceOfAllSupportedKinds() {
        val segments = listOf(
            PlaybackMarkerSegment("recap", 0.0, 10.0),
            PlaybackMarkerSegment("recap", 12.0, 20.0),
            PlaybackMarkerSegment("intro", 30.0, 40.0),
            PlaybackMarkerSegment("intro", 45.0, 50.0),
            PlaybackMarkerSegment("credits", 100.0, 110.0),
            PlaybackMarkerSegment("preview", 120.0, 130.0),
            PlaybackMarkerSegment("credits", 140.0, 150.0),
            PlaybackMarkerSegment("preview", 160.0, 170.0),
        )
        val manifest = DownloadManifest(
            downloadId = "download-1",
            mediaFileId = 7,
            intro = TimeRange(1.0, 2.0),
            markerSegments = segments.reversed(),
        )

        assertEquals(segments, manifest.effectiveMarkerSegments())
    }

    @Test
    fun explicitInventoryFiltersInvalidRangesAndUnknownKinds() {
        val valid = PlaybackMarkerSegment("intro", 0.0, 10.0)
        val manifest = DownloadManifest(
            downloadId = "download-1",
            mediaFileId = 7,
            markerSegments = listOf(
                valid,
                PlaybackMarkerSegment("recap", -1.0, 5.0),
                PlaybackMarkerSegment("intro", 10.0, 10.0),
                PlaybackMarkerSegment("credits", 20.0, 10.0),
                PlaybackMarkerSegment("preview", Double.NaN, 30.0),
                PlaybackMarkerSegment("credits", 30.0, Double.NaN),
                PlaybackMarkerSegment("recap", Double.NEGATIVE_INFINITY, 10.0),
                PlaybackMarkerSegment("preview", 30.0, Double.POSITIVE_INFINITY),
                PlaybackMarkerSegment("unknown", 40.0, 50.0),
                valid,
            ),
        )

        assertEquals(listOf(valid), manifest.effectiveMarkerSegments())
    }

    @Test
    fun invalidExplicitInventoryDoesNotRestoreLegacyMarkers() {
        val manifest = DownloadManifest(
            downloadId = "download-1",
            mediaFileId = 7,
            intro = TimeRange(10.0, 30.0),
            markerSegments = listOf(PlaybackMarkerSegment("intro", 50.0, 40.0)),
        )

        assertEquals(emptyList(), manifest.effectiveMarkerSegments())
    }

    @Test
    fun absentInventoryUsesEverySuppliedLegacyKind() {
        val manifest = DownloadManifest(
            downloadId = "download-1",
            mediaFileId = 7,
            intro = TimeRange(10.0, 30.0),
            credits = TimeRange(100.0, 120.0),
            recap = TimeRange(0.0, 5.0),
            preview = TimeRange(130.0, 140.0),
        )

        assertEquals(
            listOf(
                PlaybackMarkerSegment("recap", 0.0, 5.0),
                PlaybackMarkerSegment("intro", 10.0, 30.0),
                PlaybackMarkerSegment("credits", 100.0, 120.0),
                PlaybackMarkerSegment("preview", 130.0, 140.0),
            ),
            manifest.effectiveMarkerSegments(),
        )
    }

    @Test
    fun suppliedInvalidLegacyRangesProduceAnEmptyInventory() {
        val manifest = DownloadManifest(
            downloadId = "download-1",
            mediaFileId = 7,
            intro = TimeRange(30.0, 10.0),
            credits = TimeRange(100.0, Double.POSITIVE_INFINITY),
        )

        assertEquals(emptyList(), manifest.effectiveMarkerSegments())
    }
}
