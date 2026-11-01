package org.siloserver.silo.common.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.MediaSourceTestRunner
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class FullMediaItemSourceTest {
    private val sidecar = MediaItem.SubtitleConfiguration.Builder(Uri.parse("https://silo.test/subtitles/9.vtt"))
        .setMimeType(MimeTypes.TEXT_VTT)
        .setId("silo-subtitle:9")
        .build()
    private val full = MediaItem.Builder()
        .setUri("https://silo.test/playback/master.m3u8")
        .setSubtitleConfigurations(listOf(sidecar))
        .build()

    /** The content child is built from a copy without sidecars, as the media source factory does. */
    private fun content(): FakeMediaSource {
        val copy = full.buildUpon().setSubtitleConfigurations(emptyList()).build()
        val window = FakeTimeline.TimelineWindowDefinition(
            1, Any(), true, false, false, false,
            60_000_000L, 0L, 0L, AdPlaybackState.NONE, copy,
        )
        return FakeMediaSource(FakeTimeline(window))
    }

    @Test
    fun `the player sees the item with its sidecars, so a rebuild from it keeps them`() {
        val merged = MergingMediaSource(content(), FakeMediaSource())
        // Without the wrapper the merged source names the copy.
        assertEquals(emptyList(), merged.mediaItem.localConfiguration!!.subtitleConfigurations)

        val source = FullMediaItemSource(merged, full)
        val timeline = MediaSourceTestRunner(source).prepareSource()

        assertEquals(full, source.mediaItem)
        assertEquals(full, timeline.getWindow(0, Timeline.Window()).mediaItem)
        assertEquals(listOf(sidecar), timeline.getWindow(0, Timeline.Window()).mediaItem.localConfiguration!!.subtitleConfigurations)
        assertEquals(60_000_000L, timeline.getWindow(0, Timeline.Window()).durationUs.takeIf { it != C.TIME_UNSET })
        assertFalse(source.canUpdateMediaItem(full))
    }
}
