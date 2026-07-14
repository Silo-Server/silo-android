package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SiloPlayerFactorySubtitleParserTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlayerFactory.kt",
    ).readText()

    @Test
    fun sidecarSubtitleMediaSourcesUseNormalizingParserFactory() {
        assertTrue(
            source.contains("delegate = libassBridge.parserFactory"),
            "SiloPlayerFactory should compose the libass parser with Silo's offset parser.",
        )
        assertTrue(
            source.contains(".setSubtitleParserFactory(subtitleParserFactory)"),
            "DefaultMediaSourceFactory must use the normalizing parser for sidecar subtitles.",
        )
    }

    @Test
    fun libassUsesTheSharedParserExtractorRendererAndLifecycle() {
        assertTrue(source.contains("libassBridge.wrapExtractors("))
        assertTrue(source.contains("libassBridge.wrapRenderers("))
        assertTrue(source.contains("subtitleOffsetHolder::getOffsetUs"))
        assertTrue(source.contains("builder.build().also(libassBridge::initialize)"))
    }

    @Test
    fun nowPlayingMetadataCarriesSecondaryTextAndDuration() {
        assertTrue(
            source.contains("durationMs: Long? = null"),
            "buildMediaItem should accept the normalized runtime so MediaSession queue metadata is not duration=0",
        )
        assertTrue(
            !source.contains("durationSeconds\n            .takeIf"),
            "duration conversion should live in VideoPlayerMediaSpec, not be duplicated in the factory",
        )
        assertTrue(
            source.contains("metadataBuilder.setArtist(it)"),
            "Android media controls compare artist, not subtitle, for the secondary now-playing line",
        )
        assertTrue(
            source.contains("metadataBuilder.setDurationMs(it)"),
            "MediaItem metadata must carry duration to keep MediaSession queue and current metadata in sync",
        )
    }

    @Test
    fun directPlaybackMediaItemsUseContainerMimeHints() {
        assertTrue(
            source.contains("container: String? = null"),
            "buildMediaItem should accept the selected file container so extensionless stream URLs do not rely on sniffing.",
        )
        assertTrue(
            source.contains("videoContainerMimeType(container)"),
            "direct/remux MediaItems should set a MIME hint from the selected file container.",
        )
    }

    @Test
    fun hlsMediaSourcesKeepTsPayloadFlagsWithoutDroppingSidecarSubtitleMerging() {
        assertTrue(
            source.contains("DefaultHlsExtractorFactory(") &&
                source.contains("DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS"),
            "HLS must get the same DTS TS payload-reader flag as progressive TS streams.",
        )
        assertTrue(
            source.contains("HlsMediaSource.Factory(dataSourceFactory)") &&
                source.contains(".setExtractorFactory(hlsExtractorFactory)"),
            "HLS playback must use the configured HLS extractor factory.",
        )
        assertTrue(
            source.contains("return MergingMediaSource(*sources.toTypedArray())") &&
                source.contains("SubtitleExtractor(") &&
                source.contains("subtitleParserFactory.create(outputFormat)"),
            "HLS sidecars must be merged without bypassing the configured DTS extractor or subtitle parser.",
        )
    }
}
