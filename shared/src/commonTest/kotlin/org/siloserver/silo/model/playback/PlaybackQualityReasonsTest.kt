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
    fun codesNeedTheirFigures() {
        val cases = listOf(
            PlaybackQualityReasonV3("network_estimate", kbps = 5_100) to Text.NetworkEstimate(5_100),
            PlaybackQualityReasonV3("device_resolution_limit", height = 1080) to Text.DeviceResolution("1080p"),
            PlaybackQualityReasonV3("compatibility_conversion") to Text.CompatibilityConversion,
            // An unknown token shows uppercased.
            PlaybackQualityReasonV3("video_codec_unsupported", from = "prores") to Text.VideoCodecUnsupported("PRORES"),
            PlaybackQualityReasonV3("audio_converted", from = "mp3", to = "aac") to Text.AudioConverted("MP3", "AAC"),
            // Fewer channels of the same codec is a downmix; a codec change stays a conversion.
            PlaybackQualityReasonV3("audio_converted", from = "aac", to = "aac", channels = 2) to Text.AudioDownmixed(2),
            PlaybackQualityReasonV3("audio_converted", from = "truehd", to = "aac", channels = 2) to
                Text.AudioConverted("TrueHD", "AAC"),
            PlaybackQualityReasonV3("audio_converted", from = "aac", to = "aac") to Text.AudioConverted("AAC", "AAC"),
            PlaybackQualityReasonV3("audio_converted", from = "aac", to = "aac", channels = 0) to
                Text.AudioConverted("AAC", "AAC"),
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
        val downmix = PlaybackQualityReasonV3("audio_converted", from = "eac3", to = "eac3", channels = 6)
        val conversion = PlaybackQualityReasonV3("compatibility_conversion")
        val cases = listOf(
            listOf(estimate, PlaybackQualityReasonV3("subtitle_burn_in")) to
                listOf(Text.NetworkEstimate(5_100), Text.SubtitleBurnIn),
            listOf(estimate, downmix) to listOf(Text.NetworkEstimate(5_100), Text.AudioDownmixed(6)),
            listOf(estimate, PlaybackQualityReasonV3("alternate_version")) to
                listOf(Text.NetworkEstimate(5_100)),
            // A conversion can lead the line but never joins it.
            listOf(estimate, conversion) to listOf(Text.NetworkEstimate(5_100)),
            listOf(conversion, downmix) to listOf(Text.CompatibilityConversion, Text.AudioDownmixed(6)),
            listOf(PlaybackQualityReasonV3("original"), PlaybackQualityReasonV3("container_unsupported")) to
                listOf(Text.Original),
            listOf(PlaybackQualityReasonV3("thermal_limit")) to listOf(Text.Unknown("thermal_limit")),
            emptyList<PlaybackQualityReasonV3>() to emptyList(),
        )
        for ((reasons, expected) in cases) assertEquals(expected, reasons.menuReasonTexts(), reasons.toString())
    }

    @Test
    fun theSecondReasonJoinsOnlyWithinSeventyTwoCharacters() {
        val primary = "a".repeat(40)
        // 40 + " · " (3) + 29 = 72 characters: joined.
        assertEquals("$primary · ${"b".repeat(29)}", playbackQualityReasonLine(primary, "b".repeat(29)))
        // 73: the primary alone.
        assertEquals(primary, playbackQualityReasonLine(primary, "b".repeat(30)))
        assertEquals(primary, playbackQualityReasonLine(primary, null))
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
    }

    @Test
    fun playingNamesTheDeliveredVideoElseTheSource() {
        val source = PlaybackSourceDescriptorV3(width = 3840, height = 2160, bitrateKbps = 40_000)
        val cases = listOf(
            // The recipe's frame counts its width: a scope 1080p encode.
            Triple(PlaybackEffectiveRecipeV3(width = 1920, height = 800, bitrateKbps = 5_950), source, "1080p · 6 Mbps"),
            Triple(null, PlaybackSourceDescriptorV3(width = 3840, height = 1600, bitrateKbps = 25_000), "4K · 25 Mbps"),
            // Each figure falls back on its own.
            Triple(PlaybackEffectiveRecipeV3(width = 1280, height = 720), source, "720p · 40 Mbps"),
            Triple(PlaybackEffectiveRecipeV3(bitrateKbps = 6_000), source, "4K · 6 Mbps"),
            // No rate anywhere: the resolution alone. No size anywhere: no line.
            Triple(null, PlaybackSourceDescriptorV3(width = 1920, height = 1080), "1080p"),
            Triple(PlaybackEffectiveRecipeV3(bitrateKbps = 6_000), PlaybackSourceDescriptorV3(bitrateKbps = 8_000), null),
        )
        for ((recipe, file, expected) in cases) {
            assertEquals(expected, playbackPlayingValue(recipe, file), "$recipe $file")
        }
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
                  {"code": "dynamic_range_converted", "from": "dolby_vision", "to": "hdr10", "note": "ignored"},
                  {"code": "audio_converted", "from": "aac", "to": "aac", "channels": 2}
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
                PlaybackQualityReasonV3("audio_converted", from = "aac", to = "aac", channels = 2),
            ),
            reported.qualityReasons,
        )

        // Without the capability the client shows no reason, whatever the plan carries.
        val unreported = assertIs<PlaybackV3Validation.Playable>(decision(neutral).validateForMedia3()).plan
        assertEquals(emptyList(), unreported.qualityReasons)
    }
}
