package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DolbyVisionProfile7TransformerTest {
    @Test
    fun profile81ConvertsRpuAndDropsEnhancementLayer() {
        val converted = nal(62, payload = byteArrayOf(9, 8, 7))
        val transformer = DolbyVisionAnnexBTransformer(
            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81,
            DolbyVisionRpuConverter { converted },
        )
        val base = nal(19, payload = byteArrayOf(1, 2, 3))
        val input = accessUnit(base, nal(62), nal(63), nal(1, layer = 1))

        val output = transformer.transform(input)

        assertContentEquals(accessUnit(base, converted), output)
        assertEquals(1, transformer.convertedRpuCount)
        assertEquals(2, transformer.droppedEnhancementNalCount)
    }

    @Test
    fun hdr10DropsRpuAndEnhancementLayer() {
        val base = nal(19, payload = byteArrayOf(1, 2, 3))
        val output = DolbyVisionAnnexBTransformer(
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { error("not called") },
        ).transform(accessUnit(base, nal(62), nal(63)))

        assertContentEquals(accessUnit(base), output)
    }

    @Test
    fun malformedOrConversionFailureFailsClosed() {
        assertEquals(
            "dv7_transform_malformed",
            assertFailsWith<DolbyVisionTransformException> {
                DolbyVisionAnnexBTransformer(
                    DolbyVisionTransformMode.PROFILE7_TO_HDR10,
                    DolbyVisionRpuConverter { it },
                ).transform(byteArrayOf(1, 2, 3, 4, 5))
            }.classification,
        )
        assertEquals("dv7_rpu_conversion_failed", assertFailsWith<DolbyVisionTransformException> {
            DolbyVisionAnnexBTransformer(
                DolbyVisionTransformMode.PROFILE7_TO_PROFILE81,
                DolbyVisionRpuConverter { throw IllegalArgumentException("bad RPU") },
            ).transform(accessUnit(nal(19), nal(62)))
        }.classification)
    }

    @Test
    fun trackOutputRetainsLookaheadOffsetForNextSample() {
        val delegate = RecordingTrackOutput()
        val output = DolbyVisionTransformingTrackOutput(
            delegate = delegate,
            mode = DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            converter = DolbyVisionRpuConverter { it },
        )
        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .build(),
        )
        val first = accessUnit(nal(19, payload = byteArrayOf(1)))
        val second = accessUnit(nal(20, payload = byteArrayOf(2)))
        output.sampleData(ParsableByteArray(first + second, first.size + second.size), first.size + second.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(1, C.BUFFER_FLAG_KEY_FRAME, first.size, second.size, null)
        output.sampleMetadata(2, C.BUFFER_FLAG_KEY_FRAME, second.size, 0, null)

        assertEquals(2, delegate.samples.size)
        assertContentEquals(first, delegate.samples[0])
        assertContentEquals(second, delegate.samples[1])
        assertEquals(listOf(first.size, second.size), delegate.metadataSizes)
    }

    @Test
    fun matroskaSupplementalFramingPrefixIsRewrittenForTransformedSize() {
        val delegate = RecordingTrackOutput()
        val converted = nal(62, payload = byteArrayOf(1, 2, 3, 4))
        val output = DolbyVisionTransformingTrackOutput(
            delegate,
            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81,
            DolbyVisionRpuConverter { converted },
        )
        output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.06").build())
        val base = nal(19, payload = byteArrayOf(8, 9))
        val sample = accessUnit(base, nal(62), nal(1, layer = 1))
        val transformed = accessUnit(base, converted)

        output.sampleData(
            ParsableByteArray(sizePrefix(sample.size)),
            4,
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
        )
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(
            1,
            C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
            sample.size + 4,
            0,
            null,
        )

        assertEquals(TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL, delegate.dataParts[0].first)
        assertContentEquals(sizePrefix(transformed.size), delegate.dataParts[0].second)
        assertEquals(TrackOutput.SAMPLE_DATA_PART_MAIN, delegate.dataParts[1].first)
        assertContentEquals(transformed, delegate.dataParts[1].second)
        assertContentEquals(sizePrefix(transformed.size) + transformed, delegate.samples.single())
        assertEquals(listOf(transformed.size + 4), delegate.metadataSizes)
    }

    @Test
    fun matroskaLacedSupplementalSamplesRetainMainAndPrefixLookahead() {
        val delegate = RecordingTrackOutput()
        val converted = nal(62, payload = byteArrayOf(3, 4, 5, 6))
        val output = DolbyVisionTransformingTrackOutput(
            delegate,
            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81,
            DolbyVisionRpuConverter { converted },
        )
        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .build(),
        )
        val firstBase = nal(19, payload = byteArrayOf(1))
        val secondBase = nal(20, payload = byteArrayOf(2))
        val first = accessUnit(firstBase, nal(62))
        val second = accessUnit(secondBase, nal(62))
        val transformedFirst = accessUnit(firstBase, converted)
        val transformedSecond = accessUnit(secondBase, converted)
        output.sampleData(
            ParsableByteArray(sizePrefix(first.size)),
            4,
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
        )
        output.sampleData(
            ParsableByteArray(first),
            first.size,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        output.sampleData(
            ParsableByteArray(sizePrefix(second.size)),
            4,
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
        )
        output.sampleData(
            ParsableByteArray(second),
            second.size,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )

        output.sampleMetadata(
            1,
            C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
            first.size + 4,
            second.size + 4,
            null,
        )
        output.sampleMetadata(
            2,
            C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
            second.size + 4,
            0,
            null,
        )

        assertContentEquals(sizePrefix(transformedFirst.size) + transformedFirst, delegate.samples[0])
        assertContentEquals(sizePrefix(transformedSecond.size) + transformedSecond, delegate.samples[1])
        assertEquals(
            listOf(transformedFirst.size + 4, transformedSecond.size + 4),
            delegate.metadataSizes,
        )
    }

    @Test
    fun encryptedAndBlockAdditionalPayloadSamplesAreRejected() {
        val output = DolbyVisionTransformingTrackOutput(
            RecordingTrackOutput(),
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )
        output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvh1.07.06").build())
        val sample = accessUnit(nal(19))
        assertEquals(
            "dv7_transform_encrypted",
            assertFailsWith<DolbyVisionTransformException> {
                output.sampleData(ParsableByteArray(sample, sample.size), sample.size, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
            }.classification,
        )

        val supplementalOutput = DolbyVisionTransformingTrackOutput(
            RecordingTrackOutput(),
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )
        supplementalOutput.format(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvh1.07.06").build(),
        )
        val supplemental = sizePrefix(sample.size) + byteArrayOf(1)
        supplementalOutput.sampleData(
            ParsableByteArray(supplemental),
            supplemental.size,
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
        )
        supplementalOutput.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals(
            "dv7_transform_supplemental_unsupported",
            assertFailsWith<DolbyVisionTransformException> {
                supplementalOutput.sampleMetadata(
                    1,
                    C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    sample.size + supplemental.size,
                    0,
                    null,
                )
            }.classification,
        )
    }

    @Test
    fun failedTransformCannotForwardRawProfile7AfterReset() {
        val delegate = RecordingTrackOutput()
        val output = DolbyVisionTransformingTrackOutput(
            delegate,
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )
        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .build(),
        )
        val sample = accessUnit(nal(19))
        assertFailsWith<DolbyVisionTransformException> {
            output.sampleData(
                ParsableByteArray(sample, sample.size),
                sample.size,
                TrackOutput.SAMPLE_DATA_PART_ENCRYPTION,
            )
        }

        output.reset()

        assertEquals(
            "dv7_transform_failed",
            assertFailsWith<DolbyVisionTransformException> {
                output.sampleData(
                    ParsableByteArray(sample, sample.size),
                    sample.size,
                    TrackOutput.SAMPLE_DATA_PART_MAIN,
                )
            }.classification,
        )
        assertTrue(delegate.samples.isEmpty())
        assertTrue(delegate.dataParts.isEmpty())
    }

    @Test
    fun seekResetClearsPartialDataAndKeepsValidatedTransformActive() {
        val delegate = RecordingTrackOutput()
        val output = DolbyVisionTransformingTrackOutput(
            delegate,
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )
        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .build(),
        )
        val abandoned = accessUnit(nal(19, payload = byteArrayOf(1)))
        output.sampleData(
            ParsableByteArray(abandoned, abandoned.size),
            abandoned.size,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )

        output.reset()

        val afterSeek = accessUnit(nal(20, payload = byteArrayOf(2)))
        output.sampleData(
            ParsableByteArray(afterSeek, afterSeek.size),
            afterSeek.size,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        output.sampleMetadata(2, C.BUFFER_FLAG_KEY_FRAME, afterSeek.size, 0, null)

        assertContentEquals(afterSeek, delegate.samples.single())
    }

    @Test
    fun formatRewritePreservesProfile8LevelAndHdrColor() {
        val delegate = RecordingTrackOutput()
        val output = DolbyVisionTransformingTrackOutput(
            delegate,
            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81,
            DolbyVisionRpuConverter { it },
        )
        output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.09").build())

        assertEquals("dvhe.08.09", delegate.format?.codecs)
        assertEquals(C.COLOR_TRANSFER_ST2084, delegate.format?.colorInfo?.colorTransfer)
        assertEquals(C.COLOR_SPACE_BT2020, delegate.format?.colorInfo?.colorSpace)
        val config = delegate.format?.initializationData?.last()
        assertEquals(24, config?.size)
        assertEquals(8, ((config?.get(2)?.toInt() ?: 0) ushr 1) and 0x7f)
        assertEquals(1, ((config?.get(4)?.toInt() ?: 0) ushr 4) and 0x0f)
    }

    @Test
    fun serverDirectedTransformRejectsUnverifiedHevcMime() {
        val output = DolbyVisionTransformingTrackOutput(
            RecordingTrackOutput(),
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )

        assertEquals(
            "dv7_transform_source_mismatch",
            assertFailsWith<DolbyVisionTransformException> {
                output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())
            }.classification,
        )
    }

    @Test
    fun serverDirectedTransformRejectsKnownNonProfile7Source() {
        val output = DolbyVisionTransformingTrackOutput(
            RecordingTrackOutput(),
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )

        assertEquals(
            "dv7_transform_source_mismatch",
            assertFailsWith<DolbyVisionTransformException> {
                output.format(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                        .setCodecs("dvhe.08.06")
                        .build(),
                )
            }.classification,
        )
    }

    @Test
    fun profile81FiltersEnhancementConfigAndReplacesProfile7Record() {
        val delegate = RecordingTrackOutput()
        val output = DolbyVisionTransformingTrackOutput(
            delegate,
            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81,
            DolbyVisionRpuConverter { it },
        )
        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .setInitializationData(
                    listOf(
                        hvcC(nal(32), nal(32, layer = 1)),
                        dolbyVisionConfig(profile = 7, level = 6),
                    ),
                )
                .build(),
        )

        val rewritten = requireNotNull(delegate.format)
        assertEquals(2, rewritten.initializationData.size)
        assertContentEquals(hvcC(nal(32)), rewritten.initializationData[0])
        assertContentEquals(buildDolbyVisionProfile81Config(6), rewritten.initializationData[1])
    }

    @Test
    fun unknownInitializationDataFailsClosed() {
        val output = DolbyVisionTransformingTrackOutput(
            RecordingTrackOutput(),
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )

        assertEquals(
            "dv7_transform_malformed",
            assertFailsWith<DolbyVisionTransformException> {
                output.format(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                        .setCodecs("dvhe.07.06")
                        .setInitializationData(listOf(byteArrayOf(1, 2, 3)))
                        .build(),
                )
            }.classification,
        )
    }

    @Test
    fun enhancementOnlyHvcCFailsClosed() {
        val output = DolbyVisionTransformingTrackOutput(
            RecordingTrackOutput(),
            DolbyVisionTransformMode.PROFILE7_TO_HDR10,
            DolbyVisionRpuConverter { it },
        )

        assertEquals(
            "dv7_transform_malformed",
            assertFailsWith<DolbyVisionTransformException> {
                output.format(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                        .setCodecs("dvhe.07.06")
                        .setInitializationData(listOf(hvcC(nal(32, layer = 1))))
                        .build(),
                )
            }.classification,
        )
    }

    private class RecordingTrackOutput : TrackOutput {
        var format: Format? = null
        private var pending = ByteArray(0)
        val samples = mutableListOf<ByteArray>()
        val metadataSizes = mutableListOf<Int>()
        val dataParts = mutableListOf<Pair<Int, ByteArray>>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            if (read > 0) {
                val copied = bytes.copyOf(read)
                pending += copied
                dataParts += sampleDataPart to copied
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending += bytes
            dataParts += sampleDataPart to bytes
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertTrue(pending.size >= size + offset)
            samples += pending.copyOfRange(pending.size - size - offset, pending.size - offset)
            metadataSizes += size
            pending = if (offset == 0) ByteArray(0) else pending.copyOfRange(pending.size - offset, pending.size)
        }
    }

    private fun accessUnit(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { result, nal ->
        result + byteArrayOf(0, 0, 0, 1) + nal
    }

    private fun sizePrefix(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun nal(type: Int, layer: Int = 0, payload: ByteArray = byteArrayOf(0x55)): ByteArray {
        val byte0 = ((type and 0x3f) shl 1) or ((layer ushr 5) and 1)
        val byte1 = ((layer and 0x1f) shl 3) or 1
        return byteArrayOf(byte0.toByte(), byte1.toByte()) + payload
    }

    private fun hvcC(vararg nals: ByteArray): ByteArray {
        val header = ByteArray(23).also {
            it[0] = 1
            it[21] = 3 // four-byte NAL length fields
            it[22] = 1 // one parameter-set array
        }
        val array = byteArrayOf(0xa0.toByte(), 0, nals.size.toByte())
        return nals.fold(header + array) { result, nal ->
            result + byteArrayOf((nal.size ushr 8).toByte(), nal.size.toByte()) + nal
        }
    }

    private fun dolbyVisionConfig(profile: Int, level: Int): ByteArray = ByteArray(24).also {
        it[0] = 1
        it[2] = ((profile shl 1) or ((level ushr 5) and 1)).toByte()
        it[3] = (((level and 0x1f) shl 3) or 0x07).toByte()
        it[4] = (1 shl 4).toByte()
    }
}
