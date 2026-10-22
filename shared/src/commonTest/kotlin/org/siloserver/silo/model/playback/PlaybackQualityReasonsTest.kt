package org.siloserver.silo.model.playback

import kotlinx.serialization.decodeFromString
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.siloserver.silo.model.playback.PlaybackQualityReasonText as Text

class PlaybackQualityReasonsTest {
    @Test
    fun eachCodeMapsWithItsFigures() {
        val cases = listOf(
            PlaybackQualityReasonV3("original") to Text.Original,
            PlaybackQualityReasonV3("user_selected") to Text.UserSelected,
            PlaybackQualityReasonV3("network_learned_limit", kbps = 4_000, expiresAt = "2026-10-23T00:00:00Z") to
                Text.LearnedLimit(4_000),
            PlaybackQualityReasonV3("network_estimate", kbps = 5_100) to Text.NetworkEstimate(5_100),
            PlaybackQualityReasonV3("client_cap", kbps = 3_000) to Text.ClientCap(3_000),
            PlaybackQualityReasonV3("server_cap", kbps = 8_000) to Text.ServerCap(8_000),
            PlaybackQualityReasonV3("device_resolution_limit", height = 1080) to Text.DeviceResolution("1080p"),
            PlaybackQualityReasonV3("alternate_version") to Text.AlternateVersion,
            PlaybackQualityReasonV3("video_codec_unsupported", from = "hevc") to Text.VideoCodecUnsupported("HEVC"),
            PlaybackQualityReasonV3("video_codec_unsupported", from = "vc1") to Text.VideoCodecUnsupported("VC-1"),
            PlaybackQualityReasonV3("video_codec_unsupported", from = "prores") to Text.VideoCodecUnsupported("PRORES"),
            PlaybackQualityReasonV3("dynamic_range_converted", from = "dolby_vision", to = "hdr10") to
                Text.DynamicRangeConverted("Dolby Vision", "HDR10"),
            PlaybackQualityReasonV3("dynamic_range_converted", from = "hdr10_plus", to = "sdr") to
                Text.DynamicRangeConverted("HDR10+", "SDR"),
            PlaybackQualityReasonV3("audio_converted", from = "truehd", to = "eac3") to
                Text.AudioConverted("TrueHD", "Dolby Digital Plus"),
            PlaybackQualityReasonV3("audio_converted", from = "mp3", to = "aac") to Text.AudioConverted("MP3", "AAC"),
            PlaybackQualityReasonV3("subtitle_burn_in") to Text.SubtitleBurnIn,
            PlaybackQualityReasonV3("container_unsupported") to Text.ContainerUnsupported,
            // Codes this client doesn't know, and known codes missing the figure their words need.
            PlaybackQualityReasonV3("thermal_limit", kbps = 2_000) to Text.Unknown("thermal_limit"),
            PlaybackQualityReasonV3("network_estimate") to Text.Unknown("network_estimate"),
            PlaybackQualityReasonV3("server_cap", kbps = 0) to Text.Unknown("server_cap"),
            PlaybackQualityReasonV3("device_resolution_limit") to Text.Unknown("device_resolution_limit"),
            PlaybackQualityReasonV3("dynamic_range_converted", from = "dolby_vision") to
                Text.Unknown("dynamic_range_converted"),
        )
        for ((reason, expected) in cases) assertEquals(expected, reason.text(), reason.toString())
    }

    @Test
    fun theMenuLineAddsOnlyACompatibilityChange() {
        val estimate = PlaybackQualityReasonV3("network_estimate", kbps = 5_100)
        val cases = listOf(
            listOf(estimate, PlaybackQualityReasonV3("dynamic_range_converted", from = "dolby_vision", to = "hdr10")) to
                listOf(Text.NetworkEstimate(5_100), Text.DynamicRangeConverted("Dolby Vision", "HDR10")),
            listOf(estimate, PlaybackQualityReasonV3("subtitle_burn_in")) to
                listOf(Text.NetworkEstimate(5_100), Text.SubtitleBurnIn),
            listOf(estimate, PlaybackQualityReasonV3("alternate_version")) to
                listOf(Text.NetworkEstimate(5_100)),
            listOf(PlaybackQualityReasonV3("original"), PlaybackQualityReasonV3("container_unsupported")) to
                listOf(Text.Original),
            listOf(PlaybackQualityReasonV3("thermal_limit")) to listOf(Text.Unknown("thermal_limit")),
            emptyList<PlaybackQualityReasonV3>() to emptyList(),
        )
        for ((reasons, expected) in cases) assertEquals(expected, reasons.menuReasonTexts(), reasons.toString())
    }

    @Test
    fun resolutionLabelsByFrame() {
        val cases = listOf(
            (3840 to 2160) to "4K",
            (3840 to 1600) to "4K",
            (1920 to 1080) to "1080p",
            (1920 to 800) to "1080p",
            (1440 to 1080) to "1080p",
            (1280 to 720) to "720p",
            (1280 to 536) to "720p",
            (854 to 480) to "480p",
            (640 to 360) to "360p",
        )
        for ((frame, expected) in cases) assertEquals(expected, playbackResolutionLabel(frame.first, frame.second), "$frame")
        assertEquals("720p", playbackResolutionLabel(height = 720))
        assertNull(playbackResolutionLabel(1920, 0))
        assertEquals("Auto · 1080p", playbackAutoQualityLabel("1080p"))
        assertEquals("Auto", playbackAutoQualityLabel(null))
    }

    @Test
    fun reasonsReachThePlanOnlyWhenTheDecisionAdvertisesThem() {
        fun decision(features: String) = SiloJson.decodeFromString<PlaybackDecisionResponseV3>(
            """
            {
              "protocol_version": 3,
              "server_features": [$features],
              "outcome": "playable",
              "session_id": "session-1",
              "playback_plan": {
                "plan_id": "plan-1",
                "plan_attempt_key": "v3:0000000000000001",
                "delivery": "server_transcode_hls",
                "stream": {"url": "/stream/session-1/master.m3u8", "protocol": "hls"},
                "quality_reasons": [
                  {"code": "network_estimate", "kbps": 5100},
                  {"code": "alternate_version", "media_file_id": "mf_42"},
                  {"code": "dynamic_range_converted", "from": "dolby_vision", "to": "hdr10", "note": "ignored"}
                ],
                "decision_reason": "bandwidth_cap"
              }
            }
            """.trimIndent(),
        )
        val neutral = "\"$PLAYBACK_PLAN_V3_FEATURE\", \"$NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE\""

        val reported = assertIs<PlaybackV3Validation.Playable>(
            decision("$neutral, \"$PLAN_QUALITY_REASONS_V1_FEATURE\"").validateForMedia3(),
        ).plan
        assertEquals(
            listOf(
                PlaybackQualityReasonV3("network_estimate", kbps = 5_100),
                PlaybackQualityReasonV3("alternate_version"),
                PlaybackQualityReasonV3("dynamic_range_converted", from = "dolby_vision", to = "hdr10"),
            ),
            reported.qualityReasons,
        )

        // Without the capability the client shows no reason, whatever the plan carries.
        val unreported = assertIs<PlaybackV3Validation.Playable>(decision(neutral).validateForMedia3()).plan
        assertEquals(emptyList(), unreported.qualityReasons)
    }
}
