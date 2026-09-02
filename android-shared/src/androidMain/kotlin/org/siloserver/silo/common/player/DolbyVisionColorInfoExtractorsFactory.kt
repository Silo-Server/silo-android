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
 * Repairs incomplete HDR [Format] metadata emitted by Media3's Matroska
 * extractor. Dolby Vision is repaired from its codec identity; HLG is repaired
 * only when the validated server recipe explicitly identifies the output as
 * HLG and the container format did not provide conflicting color metadata.
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
    private val expectedDynamicRange: String? = null,
    private val expectedColorRange: String? = null,
    /**
     * The plan authorised the Profile 8 base-layer route. The Dolby Vision
     * colour repair then follows [expectedDynamicRange] (the promised base
     * range) instead of assuming a PQ base layer.
     */
    private val dolbyVisionBaseLayerRoute: Boolean = false,
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
        ColorInfoExtractor(
            extractor,
            transformMode,
            converter,
            expectedDynamicRange,
            expectedColorRange,
            dolbyVisionBaseLayerRoute,
        )

    private class ColorInfoExtractor(
        private val delegate: Extractor,
        private val transformMode: DolbyVisionTransformMode,
        private val converter: DolbyVisionRpuConverter,
        private val expectedDynamicRange: String?,
        private val expectedColorRange: String?,
        private val dolbyVisionBaseLayerRoute: Boolean,
    ) : Extractor by delegate {
        private var output: ColorInfoExtractorOutput? = null

        override fun init(output: ExtractorOutput) {
            val wrapped = ColorInfoExtractorOutput(
                output,
                transformMode,
                converter,
                expectedDynamicRange,
                expectedColorRange,
                dolbyVisionBaseLayerRoute,
            )
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
        private val expectedDynamicRange: String?,
        private val expectedColorRange: String?,
        private val dolbyVisionBaseLayerRoute: Boolean,
    ) : ExtractorOutput {
        private val tracks = mutableMapOf<Int, TrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
            val output = delegate.track(id, type)
            if (type == C.TRACK_TYPE_VIDEO) {
                val colorInfoOutput = ColorInfoTrackOutput(
                    output,
                    expectedDynamicRange,
                    expectedColorRange,
                    dolbyVisionBaseLayerRoute,
                )
                if (transformMode != DolbyVisionTransformMode.DISABLED) {
                    DolbyVisionTransformingTrackOutput(colorInfoOutput, transformMode, converter)
                } else {
                    colorInfoOutput
                }
            } else {
                output
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
        private val expectedDynamicRange: String?,
        private val expectedColorRange: String?,
        private val dolbyVisionBaseLayerRoute: Boolean,
    ) : TrackOutput {
        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun format(format: Format) {
            delegate.format(
                format
                    .withValidatedColorRange(expectedColorRange)
                    .withValidatedDynamicRangeColorInfo(expectedDynamicRange)
                    .withDolbyVisionHdrColorInfo(
                        baseLayerRange = expectedDynamicRange.takeIf { dolbyVisionBaseLayerRoute },
                    ),
            )
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
internal fun Format.withValidatedColorRange(expectedColorRange: String?): Format {
    if (!MimeTypes.isVideo(sampleMimeType)) return this
    val expected = when (expectedColorRange?.trim()?.lowercase()) {
        "tv" -> C.COLOR_RANGE_LIMITED
        "pc" -> C.COLOR_RANGE_FULL
        else -> return this
    }
    val current = colorInfo
    if (current != null && current.colorRange != -1) return this
    val repaired = (current?.buildUpon() ?: androidx.media3.common.ColorInfo.Builder())
        .setColorRange(expected)
        .build()
    return buildUpon().setColorInfo(repaired).build()
}

@UnstableApi
internal fun Format.withValidatedDynamicRangeColorInfo(expectedDynamicRange: String?): Format {
    if (!expectedDynamicRange.equals("hlg", ignoreCase = true) || !MimeTypes.isVideo(sampleMimeType)) {
        return this
    }
    val current = colorInfo
    if (current != null && (
        (current.colorSpace != -1 && current.colorSpace != C.COLOR_SPACE_BT2020) ||
            (current.colorTransfer != -1 && current.colorTransfer != C.COLOR_TRANSFER_HLG)
        )
    ) {
        return this
    }
    if (current != null &&
        current.colorSpace == C.COLOR_SPACE_BT2020 &&
        current.colorTransfer == C.COLOR_TRANSFER_HLG &&
        current.colorRange != -1
    ) {
        return this
    }

    val repaired = (current?.buildUpon() ?: androidx.media3.common.ColorInfo.Builder())
        .apply {
            if (current == null || current.colorSpace == -1) {
                setColorSpace(C.COLOR_SPACE_BT2020)
            }
            if (current == null || current.colorTransfer == -1) {
                setColorTransfer(C.COLOR_TRANSFER_HLG)
            }
            if (current == null || current.colorRange == -1) {
                setColorRange(C.COLOR_RANGE_LIMITED)
            }
        }
        .build()
    return buildUpon().setColorInfo(repaired).build()
}

/**
 * @param baseLayerRange when the plan authorised the Profile 8 base-layer
 * route, the promised base range (`hdr10`, `hlg`, `sdr`). A PQ repair is then
 * applied only for `hdr10`; an HLG or SDR base layer is left to the HLG
 * repair above or to the container's own signalling, never rewritten to PQ.
 */
@UnstableApi
internal fun Format.withDolbyVisionHdrColorInfo(baseLayerRange: String? = null): Format {
    if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return this
    if (baseLayerRange != null && !baseLayerRange.equals("hdr10", ignoreCase = true)) return this
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
