package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Repairs the incomplete Dolby Vision [Format] emitted by Media3's Matroska
 * extractor for streams whose bitstream is a BT.2020/PQ HDR base layer.
 *
 * The extractor correctly identifies `video/dolby-vision`, but can leave
 * [Format.colorInfo] null. Android configures MediaCodec before the decoder
 * parses the first frame, so the Shield then opens its DOVI decoder without
 * SMPTE-2084 output signaling and HDMI remains SDR. Supplying the color fields
 * at the TrackOutput boundary keeps the compressed video bytes untouched while
 * giving MediaCodec the information that is already present in the source.
 */
@UnstableApi
internal class DolbyVisionColorInfoExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val transformMode: DolbyVisionTransformMode = DolbyVisionTransformMode.DISABLED,
    private val converter: DolbyVisionRpuConverter = NativeDolbyVisionRpuConverter,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = delegate.createExtractors(uri, responseHeaders)
        .map(::wrap)
        .toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        ColorInfoExtractor(extractor, transformMode, converter)

    private class ColorInfoExtractor(
        private val delegate: Extractor,
        private val transformMode: DolbyVisionTransformMode,
        private val converter: DolbyVisionRpuConverter,
    ) : Extractor by delegate {
        private var output: ColorInfoExtractorOutput? = null

        override fun init(output: ExtractorOutput) {
            val wrapped = ColorInfoExtractorOutput(output, transformMode, converter)
            this.output = wrapped
            delegate.init(wrapped)
        }

        override fun seek(position: Long, timeUs: Long) {
            output?.reset()
            delegate.seek(position, timeUs)
        }

        override fun release() {
            output?.reset()
            output = null
            delegate.release()
        }
    }

    private class ColorInfoExtractorOutput(
        private val delegate: ExtractorOutput,
        private val transformMode: DolbyVisionTransformMode,
        private val converter: DolbyVisionRpuConverter,
    ) : ExtractorOutput {
        private val tracks = mutableMapOf<Int, TrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
            val output = delegate.track(id, type)
            if (type == C.TRACK_TYPE_VIDEO && transformMode != DolbyVisionTransformMode.DISABLED) {
                DolbyVisionTransformingTrackOutput(output, transformMode, converter)
            } else {
                ColorInfoTrackOutput(output)
            }
        }

        override fun endTracks() = delegate.endTracks()

        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)

        fun reset() {
            tracks.values.filterIsInstance<DolbyVisionTransformingTrackOutput>().forEach { it.reset() }
        }
    }

    private class ColorInfoTrackOutput(
        private val delegate: TrackOutput,
    ) : TrackOutput {
        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun format(format: Format) {
            delegate.format(format.withDolbyVisionHdrColorInfo())
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) = delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}

@UnstableApi
internal fun Format.withDolbyVisionHdrColorInfo(): Format {
    if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return this
    val current = colorInfo
    // Do not rewrite a valid non-PQ base layer (notably Profile 8.4 HLG).
    // This repair exists only for containers where Media3 emitted no usable
    // color signaling before MediaCodec configuration.
    if (current != null &&
        current.colorTransfer != -1 &&
        current.colorTransfer != C.COLOR_TRANSFER_ST2084
    ) return this
    if (current != null &&
        current.colorSpace == C.COLOR_SPACE_BT2020 &&
        current.colorTransfer == C.COLOR_TRANSFER_ST2084 &&
        current.colorRange == C.COLOR_RANGE_LIMITED
    ) return this

    val repaired = (current?.buildUpon() ?: androidx.media3.common.ColorInfo.Builder())
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setHdrStaticInfo(current?.hdrStaticInfo ?: defaultDolbyVisionHdrStaticInfo())
        .build()
    return buildUpon().setColorInfo(repaired).build()
}

/**
 * CTA-861.3 static metadata descriptor used only when Matroska did not expose
 * the frame's mastering metadata before codec setup. Coordinates are the
 * standard P3-D65 mastering display. The conservative 1000/400 light levels
 * are used only as a codec-configuration fallback; the Dolby Vision RPU still
 * carries the frame-by-frame presentation metadata consumed by the decoder.
 */
internal fun defaultDolbyVisionHdrStaticInfo(): ByteArray =
    ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0.toByte()) // Static Metadata Type 1 descriptor ID.
        putShort(34_000.toShort()) // R x = 0.68000
        putShort(16_000.toShort()) // R y = 0.32000
        putShort(13_250.toShort()) // G x = 0.26500
        putShort(34_500.toShort()) // G y = 0.69000
        putShort(7_500.toShort()) // B x = 0.15000
        putShort(3_000.toShort()) // B y = 0.06000
        putShort(15_635.toShort()) // D65 white x = 0.31270
        putShort(16_450.toShort()) // D65 white y = 0.32900
        putShort(1_000.toShort()) // Mastering max = 1000 cd/m2
        putShort(50.toShort()) // Mastering min = 0.0050 cd/m2
        putShort(1_000.toShort()) // Conservative MaxCLL fallback.
        putShort(400.toShort()) // Conservative MaxFALL fallback.
    }.array()
