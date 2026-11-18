package org.siloserver.silo.common.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.SocketException
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

    private fun fail(atMs: Long, position: Long, totalBytes: Long, count: Int, uri: Uri = original): Long {
        now = atMs
        val dataSpec = DataSpec.Builder().setUri(uri).setPosition(position).build()
        val event = LoadEventInfo.Builder(1L, dataSpec, now).setBytesLoaded(totalBytes).build()
        return policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(event, MediaLoadData(C.DATA_TYPE_MEDIA), SocketException(), count),
        )
    }

    @Test
    fun laterOutageGetsFreshWindowAfterSustainedProgress() {
        assertEquals(1_000L, fail(0, 0, 1_000_000, 1))
        assertEquals(1_000L, fail(660_000, 1_000_000, 1_001_000_000, 2))
        assertEquals(2_000L, fail(690_000, 1_001_000_000, 1_001_000_000, 3))
        assertEquals(C.TIME_UNSET, fail(750_000, 1_001_000_000, 1_001_000_000, 4))
    }

    @Test
    fun rereadingSameBytesAfterFailedReopenDoesNotRenewWindow() {
        assertEquals(1_000L, fail(0, 0, 2_000_000, 1))
        assertEquals(2_000L, fail(60_000, 0, 2_000_000, 2))
        assertEquals(C.TIME_UNSET, fail(100_000, 0, 4_000_000, 3))
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
        assertEquals(C.TIME_UNSET, fail(100_000, 2_000_000, 2_004_096, 2))
        policy.onLoadTaskConcluded(1L)
        val segment = Uri.parse("https://silo.test/segment.ts")
        assertEquals(1_000L, fail(200_000, 0, 2_000_000, 1, segment))
        assertEquals(C.TIME_UNSET, fail(300_000, 2_000_000, 8_000_000, 2, segment))
    }
}
