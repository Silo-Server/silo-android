package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackThroughputMeterTest {
    private var now = 0L
    private val meter = PlaybackThroughputMeter(clock = { now })
    private val spec = DataSpec(Uri.parse("https://silo.example/media"))

    private class Source : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    private fun bytes(source: DataSource, atMs: Long, count: Int, isNetwork: Boolean = true) {
        now = atMs
        meter.onBytesTransferred(source, spec, isNetwork, count)
    }

    private fun end(source: DataSource, atMs: Long) {
        now = atMs
        meter.onTransferEnd(source, spec, true)
    }

    @Test
    fun theFirstReadStartsTheClockAndDoesNotCount() {
        val segment = Source()
        bytes(segment, 1_000, 4_000_000)
        bytes(segment, 1_500, 1_000_000)
        bytes(segment, 3_000, 4_000_000)
        end(segment, 3_000)
        // 5 MB over the 2 s from the first byte: 20 Mbps.
        assertEquals(20_000, meter.takeKbps())
    }

    @Test
    fun overlappingRequestsCountTheirTimeOnce() {
        val video = Source()
        val audio = Source()
        bytes(video, 0, 1)
        bytes(audio, 500, 1)
        bytes(video, 1_000, 2_000_000)
        end(video, 1_000)
        bytes(audio, 1_500, 500_000)
        end(audio, 2_000)
        // 2.5 MB over 0 to 1,500 ms, the last byte.
        assertEquals(13_333, meter.takeKbps())
    }

    @Test
    fun idleTimeBetweenRequestsAndAfterTheLastByteDoesNotCount() {
        val first = Source()
        bytes(first, 0, 1)
        bytes(first, 1_000, 1_000_000)
        end(first, 9_000)
        val second = Source()
        bytes(second, 20_000, 1)
        bytes(second, 21_000, 1_000_000)
        end(second, 21_000)
        assertEquals(8_000, meter.takeKbps())
    }

    @Test
    fun eachTakeStartsOver() {
        val progressive = Source()
        bytes(progressive, 0, 1)
        bytes(progressive, 2_000, 2_000_000)
        assertEquals(8_000, meter.takeKbps())
        bytes(progressive, 3_000, 2_000_000)
        end(progressive, 3_000)
        assertEquals(16_000, meter.takeKbps())
        assertNull(meter.takeKbps())
    }

    @Test
    fun shortRequestsDoNotCountEvenWhenTheyAddUp() {
        // Three segments, each 4 MB in 500 ms: the burst rate, 64 Mbps.
        for (start in listOf(0L, 2_000L, 4_000L)) {
            val segment = Source()
            bytes(segment, start, 8)
            bytes(segment, start + 500, 4_000_000)
            end(segment, start + 500)
        }
        assertNull(meter.takeKbps())
    }

    @Test
    fun onlyLongRequestsCount() {
        val progressive = Source()
        bytes(progressive, 0, 1)
        bytes(progressive, 2_000, 2_000_000)
        end(progressive, 2_000)
        val segment = Source()
        bytes(segment, 1_500, 8)
        bytes(segment, 1_900, 10_000_000)
        end(segment, 1_900)
        // The segment's 10 MB and its time are left out: 2 MB over 2 s.
        assertEquals(8_000, meter.takeKbps())
    }

    @Test
    fun aRateUnderTheEstimatesFloorGivesNothing() {
        val progressive = Source()
        bytes(progressive, 0, 1)
        // 20 kB over 2 s: 80 kbps.
        bytes(progressive, 2_000, 20_000)
        end(progressive, 2_000)
        assertNull(meter.takeKbps())
    }

    @Test
    fun localFilesAndShortReceivingGiveNothing() {
        val file = Source()
        bytes(file, 0, 1, isNetwork = false)
        bytes(file, 5_000, 10_000_000, isNetwork = false)
        val segment = Source()
        bytes(segment, 10_000, 1)
        bytes(segment, 10_999, 1_000_000)
        end(segment, 10_999)
        assertNull(meter.takeKbps())
    }
}
