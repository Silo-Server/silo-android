package org.siloserver.silo.common.player.video

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class DolbyVisionHdr10PlusSanitizerTest {
    @Test
    fun compactsStandaloneHdr10PlusNalInPlace() {
        val first = nal(type = 32, payload = byteArrayOf(1, 2, 3))
        val hdr10Plus = seiNal(byteArrayOf(0xB5.toByte(), 0, 0x3C, 0, 1, 4, 1, 9))
        val last = nal(type = 1, payload = byteArrayOf(7, 8, 9))
        val buffer = ByteBuffer.allocateDirect(first.size + hdr10Plus.size + last.size)
            .put(first + hdr10Plus + last)
            .apply { flip() }

        kotlin.test.assertTrue(DolbyVisionHdr10PlusSanitizer.sanitize(buffer))
        val actual = ByteArray(buffer.remaining()).also(buffer::get)
        assertContentEquals(first + last, actual)
    }

    @Test
    fun removesOnlyHdr10PlusSeiNal() {
        val vps = nal(type = 32, payload = byteArrayOf(1, 2, 3))
        val hdr10Plus = seiNal(byteArrayOf(0xB5.toByte(), 0, 0x3C, 0, 1, 4, 1, 9))
        val slice = nal(type = 1, payload = byteArrayOf(7, 8, 9))
        val source = vps + hdr10Plus + slice

        assertContentEquals(vps + slice, DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(source))
    }

    @Test
    fun preservesUnrelatedRegisteredUserData() {
        val source = seiNal(byteArrayOf(0xB5.toByte(), 0, 0x31, 0, 1, 4))
        assertNull(DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(source))
    }

    @Test
    fun preservesUnknownSt2094ApplicationVersion() {
        val source = seiNal(byteArrayOf(0xB5.toByte(), 0, 0x3C, 0, 1, 4, 2))
        assertNull(DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(source))
    }

    @Test
    fun preservesOtherMessagesInTheSameSeiNal() {
        val hdrPayload = byteArrayOf(0xB5.toByte(), 0, 0x3C, 0, 1, 4, 1)
        val unrelatedMessage = byteArrayOf(5, 3, 7, 8, 9)
        val source = nal(
            type = 39,
            payload = byteArrayOf(4, hdrPayload.size.toByte()) + hdrPayload +
                unrelatedMessage + byteArrayOf(0x80.toByte()),
        )
        val expected = nal(type = 39, payload = unrelatedMessage + byteArrayOf(0x80.toByte()))

        assertContentEquals(expected, DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(source))
    }

    @Test
    fun leavesLengthPrefixedAndMalformedSamplesUntouched() {
        val lengthPrefixed = byteArrayOf(0, 0, 0, 4, 0x4e, 1, 4, 0)
        val truncatedSei = nal(type = 39, payload = byteArrayOf(4, 20, 0xB5.toByte()))

        assertNull(DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(lengthPrefixed))
        assertNull(DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(truncatedSei))
    }

    @Test
    fun recognizesEscapedHdr10PlusPayload() {
        val escaped = byteArrayOf(0xB5.toByte(), 0, 0x3C, 0, 1, 4, 0, 0, 3, 1)
        val source = seiNalRaw(payloadSize = 9, payload = escaped)

        assertContentEquals(byteArrayOf(), DolbyVisionHdr10PlusSanitizer.sanitizeAnnexB(source))
    }

    private fun seiNal(payload: ByteArray): ByteArray = seiNalRaw(payload.size, payload)

    private fun seiNalRaw(payloadSize: Int, payload: ByteArray): ByteArray =
        nal(type = 39, payload = byteArrayOf(4, payloadSize.toByte()) + payload + byteArrayOf(0x80.toByte()))

    private fun nal(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1) + payload
}
