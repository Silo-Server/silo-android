package org.siloserver.silo.common.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class DirectPlayRetryWindowTest {
    private val original = Uri.parse("https://silo.test/original")
    private var now = 0L
    private val policy = SiloMediaLoadErrorHandlingPolicy({ it == original }, { now })

    private val source = policy.trackDirectPlayReads(DataSource.Factory {
        object : DataSource by PlaceholderDataSource.INSTANCE {
            override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
        }
    }).createDataSource()

    private var lastTotalBytes = 0L
    private val buffer = ByteArray(64 * 1024)

    private fun readBytes(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            remaining -= source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        }
    }

    private fun fail(
        atMs: Long,
        position: Long,
        totalBytes: Long,
        count: Int,
        uri: Uri = original,
        firstReadMs: Long? = null,
        lastReadMs: Long = atMs,
        bytesBeforeSeek: Long = 0,
    ): Long {
        val dataSpec = DataSpec.Builder().setUri(uri).setPosition(position).build()
        now = firstReadMs ?: lastReadMs
        if (bytesBeforeSeek > 0) {
            source.open(DataSpec(uri))
            readBytes(bytesBeforeSeek)
            source.close()
        }
        source.open(dataSpec)
        val bytesThisOpen = totalBytes - lastTotalBytes - bytesBeforeSeek
        readBytes(bytesThisOpen / 2)
        now = lastReadMs
        readBytes(bytesThisOpen - bytesThisOpen / 2)
        lastTotalBytes = totalBytes
        source.close()
        now = atMs
        val event = LoadEventInfo.Builder(1L, dataSpec, now).setBytesLoaded(totalBytes).build()
        return policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(event, MediaLoadData(C.DATA_TYPE_MEDIA), SocketTimeoutException(), count),
        )
    }

    @Test
    fun laterOutageGetsFreshWindowAfterSustainedProgress() {
        assertEquals(1_000L, fail(0, 0, 1_000_000, 1))
        assertEquals(1_000L, fail(660_000, 1_000_000, 1_001_000_000, 2, firstReadMs = 1_000))
        assertEquals(2_000L, fail(690_000, 1_001_000_000, 1_001_000_000, 3))
        assertEquals(C.TIME_UNSET, fail(750_000, 1_001_000_000, 1_001_000_000, 4))
    }

    @Test
    fun rereadingSameBytesAfterFailedReopenDoesNotRenewWindow() {
        assertEquals(1_000L, fail(0, 0, 2_000_000, 1))
        assertEquals(2_000L, fail(60_000, 0, 2_000_000, 2))
        assertEquals(C.TIME_UNSET, fail(100_000, 0, 4_000_000, 3, firstReadMs = 61_000))
    }

    @Test
    fun extractorSeekDoesNotAddEarlierReadsToTheCutOffset() {
        assertEquals(1_000L, fail(0, 10_000_000, 2_000_000, 1, bytesBeforeSeek = 1_000_000))
        assertEquals(1_000L, fail(660_000, 11_000_000, 3_000_000, 2, firstReadMs = 10_000))
    }

    @Test
    fun burstsSeparatedByReadTimeoutsExhaustTheOriginalWindow() {
        for (i in 0..2) {
            val burst = i * 31_000L
            fail(
                atMs = burst + 30_000, position = i * 1_000_000L, totalBytes = (i + 1) * 1_000_000L,
                count = 2, firstReadMs = burst, lastReadMs = burst + 10,
            )
        }
        assertEquals(
            C.TIME_UNSET,
            fail(123_000, 3_000_000, 4_000_000, 2, firstReadMs = 93_000, lastReadMs = 93_010),
        )
    }

    @Test
    fun frequentCutsExhaustWindowEvenWhenTheyDeliverData() {
        for (i in 0..8) {
            fail(i * 10_000L, i * 2_000_000L, (i + 1) * 2_000_000L, 2)
        }
        assertEquals(C.TIME_UNSET, fail(90_000, 18_000_000, 20_000_000, 2))
    }

    @Test
    fun fewBytesOrLargeNonProgressiveLoadDoNotRenewWindow() {
        assertEquals(1_000L, fail(0, 0, 2_000_000, 1))
        assertEquals(C.TIME_UNSET, fail(100_000, 2_000_000, 2_004_096, 2, firstReadMs = 5_000))
        policy.onLoadTaskConcluded(1L)
        lastTotalBytes = 0L
        val segment = Uri.parse("https://silo.test/segment.ts")
        assertEquals(1_000L, fail(200_000, 0, 2_000_000, 1, segment))
        assertEquals(C.TIME_UNSET, fail(300_000, 2_000_000, 8_000_000, 2, segment, firstReadMs = 220_000))
    }
}
