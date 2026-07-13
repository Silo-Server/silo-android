package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DolbyVisionColorInfoExtractorsFactoryTest {
    @Test
    fun suppliesBt2020PqColorInfoBeforeDolbyVisionDecoderInitialization() {
        val source = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.06")
            .build()

        val repaired = source.withDolbyVisionHdrColorInfo()

        assertEquals(C.COLOR_SPACE_BT2020, repaired.colorInfo?.colorSpace)
        assertEquals(C.COLOR_TRANSFER_ST2084, repaired.colorInfo?.colorTransfer)
        assertEquals(C.COLOR_RANGE_LIMITED, repaired.colorInfo?.colorRange)
        assertEquals(25, repaired.colorInfo?.hdrStaticInfo?.size)
        assertEquals(0, repaired.colorInfo?.hdrStaticInfo?.first()?.toInt())
    }

    @Test
    fun leavesNonDolbyVisionFormatsUntouched() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()

        val result = source.withDolbyVisionHdrColorInfo()

        assertSame(source, result)
        assertNull(result.colorInfo)
    }
}
