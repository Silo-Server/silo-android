package org.siloserver.silo.network.apiv2

import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `GET /api/v2/playback/connection`, from the buffering-quality spec's *Reporting and display*. */
class PlaybackConnectionV2Test {
    @Test fun aConnectionOnlyBodyDecodes() {
        val decoded = SiloJson.decodeFromString(PlaybackConnectionV2.serializer(), """{"connection":"local"}""")
        assertEquals(PlaybackConnectionV2(connection = "local"), decoded)
        assertNull(decoded.probe)
        assertNull(decoded.autoBudgetKbps)
    }

    @Test fun aBodyWithAProbeAndBudgetIgnoresUnknownFields() {
        val body = """{
            "connection":"remote",
            "probe":{"kbps":48000,"measured_at":"2026-10-17T19:02:11Z","transport":"wifi","bytes":40000000},
            "learned_limit":{"kbps":44100,"observed_at":"2026-10-17T16:21:40Z","expires_at":"2026-10-24T16:21:40Z"},
            "auto_budget_kbps":35280,
            "connection_label":"Home"
        }"""
        assertEquals(
            PlaybackConnectionV2(
                connection = "remote",
                learnedLimit = PlaybackLearnedLimitV2(44100, "2026-10-17T16:21:40Z", "2026-10-24T16:21:40Z"),
                probe = PlaybackConnectionProbeV2(48000, "2026-10-17T19:02:11Z", "wifi"),
                autoBudgetKbps = 35280,
            ),
            SiloJson.decodeFromString(PlaybackConnectionV2.serializer(), body),
        )
    }

    @Test fun aBodyWithALowerBoundAndPlaybackSampleDecodes() {
        val body = """{
            "connection":"remote",
            "probe":{"kbps":115000,"lower_bound":true,"measured_at":"2026-10-17T19:02:11Z","transport":"wifi"},
            "playback_sample":{"kbps":51200,"measured_at":"2026-10-18T20:14:03Z"}
        }"""
        assertEquals(
            PlaybackConnectionV2(
                connection = "remote",
                probe = PlaybackConnectionProbeV2(115000, "2026-10-17T19:02:11Z", "wifi", lowerBound = true),
                playbackSample = PlaybackConnectionSampleV2(51200, "2026-10-18T20:14:03Z"),
            ),
            SiloJson.decodeFromString(PlaybackConnectionV2.serializer(), body),
        )
    }
}
