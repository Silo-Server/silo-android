package org.siloserver.silo.repository

import kotlinx.serialization.json.*
import org.siloserver.silo.network.SiloJson
import kotlin.test.*

class ProxyAuxiliaryDecisionTest {
    private val auxiliary = "https://proxy.example/stream/v3/session-1/subtitles/0.ass?file_id=42&external_subtitle_key=part%2Fsidecar"
    private fun body(primary: String, auxiliaryUrl: String = auxiliary, responseSession: String = "session-1", planSession: String = "session-1"): JsonObject =
        SiloJson.parseToJsonElement("""{"protocol_version":3,"outcome":"playable","session_id":"$responseSession","playback_plan":{"session_id":"$planSession","plan_id":"plan-1","delivery":"original_http","stream":{"url":"$primary","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"42","effective_media_file_id":"42","source":{"media_file_id":"42"},"subtitle":{"mode":"render","artifact":{"url":"$auxiliaryUrl","format":"ass","mime_type":"text/x-ssa"}}}}""").jsonObject

    @Test fun signedPrimaryWithExplicitSessionAcceptsIssuedAuxiliaryWithoutRewritingEitherReference() {
        for (primary in listOf("https://proxy.example/stream/direct/opaque-signed-reference", "https://proxy.example/stream/transcode/opaque-signed-reference/master.m3u8")) {
            val decision = decodePlaybackDecisionV2(body(primary))
            assertEquals(primary, decision.playbackPlan!!.stream.url)
            assertEquals(auxiliary, decision.playbackPlan!!.subtitle.artifact!!.url)
            assertEquals("session-1", decision.sessionId)
        }
    }

    @Test fun auxiliaryRequiresMatchingExplicitSessionOriginAndPermittedQuery() {
        val primary = "https://proxy.example/stream/direct/opaque-signed-reference"
        for (bad in listOf(auxiliary.replace("session-1", "foreign-session"), auxiliary.replace("proxy.example", "foreign.example"), auxiliary + "&access_token=forbidden", auxiliary + "&st=forbidden", auxiliary.replace("file_id", "source_file_id"))) {
            assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(body(primary, bad)) }
        }
        assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(body(primary, responseSession = "foreign-session")) }
        assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(body(primary, planSession = "foreign-session")) }
        assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(JsonObject(body(primary) - "session_id")) }
    }
}
