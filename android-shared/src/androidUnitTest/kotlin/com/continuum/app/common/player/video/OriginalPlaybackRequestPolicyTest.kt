package com.continuum.app.common.player.video

import com.continuum.app.model.catalog.AudioTrack
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.playback.ClientPlaybackContext
import com.continuum.app.model.playback.EngineCapabilityEnvelope
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OriginalPlaybackRequestPolicyTest {
    @Test
    fun requestsDirectForMkvWhenMpvCanMountTheHardContainer() {
        val method = FileVersion(
            fileId = 1,
            container = "mkv",
            codecVideo = "H.264",
            codecAudio = "E-AC-3",
        ).requestedOriginalPlaybackMethod(
            context(
                mpv = engine(
                    containers = listOf("mkv", "matroska"),
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("eac3"),
                ),
            ),
        )

        assertEquals(PlayMethod.DIRECT, method)
    }

    @Test
    fun requestsDirectForMpvOnlyOriginalContainersWhenMpvCanMountThem() {
        listOf("avi", "mov", "qt", "ts", "mpegts", "mpeg-ts", "m2ts", "mts").forEach { container ->
            val method = FileVersion(
                fileId = 1,
                container = container,
                codecVideo = "h264",
                codecAudio = "aac",
            ).requestedOriginalPlaybackMethod(
                context(
                    mpv = engine(
                        containers = mpvOriginalPlaybackContainers,
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                    ),
                ),
            )

            assertEquals(PlayMethod.DIRECT, method, "container=$container")
        }
    }

    @Test
    fun requestsDirectForMp4WhenMedia3CanMountTheOriginal() {
        val method = FileVersion(
            fileId = 1,
            container = "mp4",
            codecVideo = "avc1.640028",
            codecAudio = "mp4a.40.2",
        ).requestedOriginalPlaybackMethod(
            context(
                media3 = engine(
                    containers = listOf("mp4", "m4v"),
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                ),
            ),
        )

        assertEquals(PlayMethod.DIRECT, method)
    }

    @Test
    fun requestsDirectForMp4ThroughMpvWhenMedia3CannotPlaySelectedAudio() {
        val method = FileVersion(
            fileId = 1,
            container = "mp4",
            codecVideo = "h264",
            codecAudio = "truehd",
        ).requestedOriginalPlaybackMethod(
            context(
                media3 = engine(
                    containers = listOf("mp4"),
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                ),
                mpv = engine(
                    containers = mpvOriginalPlaybackContainers,
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("truehd"),
                ),
            ),
        )

        assertEquals(PlayMethod.DIRECT, method)
    }

    @Test
    fun requestsDirectForWebmWhenMedia3CanMountTheOriginal() {
        val method = FileVersion(
            fileId = 1,
            container = ".WEBM",
            codecVideo = "vp09.00.51.08",
            codecAudio = "opus",
        ).requestedOriginalPlaybackMethod(
            context(
                media3 = engine(
                    containers = listOf("webm"),
                    videoCodecs = listOf("vp9"),
                    audioCodecs = listOf("opus"),
                ),
            ),
        )

        assertEquals(PlayMethod.DIRECT, method)
    }

    @Test
    fun selectedAudioTrackMustBeDirectPlayable() {
        val version = FileVersion(
            fileId = 1,
            container = "mp4",
            codecVideo = "h264",
            codecAudio = "aac",
            audioTracks = listOf(
                AudioTrack(index = 0, codec = "aac", isDefault = true),
                AudioTrack(index = 1, codec = "truehd"),
            ),
        )
        val context = context(
            media3 = engine(
                containers = listOf("mp4"),
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
            ),
        )

        assertEquals(PlayMethod.DIRECT, version.requestedOriginalPlaybackMethod(context, audioTrackIndex = 0))
        assertNull(version.requestedOriginalPlaybackMethod(context, audioTrackIndex = 1))
    }

    @Test
    fun leavesServerDecisionWhenNoMountedEngineCanDirectPlayTheOriginal() {
        assertNull(
            FileVersion(
                fileId = 1,
                container = "mp4",
                codecVideo = "hevc",
                codecAudio = "aac",
            ).requestedOriginalPlaybackMethod(
                context(
                    media3 = engine(
                        containers = listOf("mp4"),
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                    ),
                ),
            ),
            "Unsupported video codec must not be forced to DIRECT",
        )
    }

    private fun context(
        media3: EngineCapabilityEnvelope? = null,
        mpv: EngineCapabilityEnvelope? = null,
    ) = ClientPlaybackContext(
        formFactor = "tv",
        appVersion = "test",
        engines = buildMap {
            if (media3 != null) put(PlaybackEngineKind.MEDIA3_DIRECT, media3)
            if (mpv != null) put(PlaybackEngineKind.MPV_DIRECT, mpv)
        },
    )

    private fun engine(
        containers: List<String>,
        videoCodecs: List<String>,
        audioCodecs: List<String>,
        enabled: Boolean = true,
        supportedOnDevice: Boolean = true,
    ) = EngineCapabilityEnvelope(
        enabled = enabled,
        supportedOnDevice = supportedOnDevice,
        containers = containers,
        videoCodecs = videoCodecs,
        audioDecodeCodecs = audioCodecs,
    )
}
