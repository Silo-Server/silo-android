package org.siloserver.silo.model.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin runner for the server's golden playback-v3 wire fixtures — this
 * client's drift gate on the neutral protocol contract.
 *
 * The fixtures under `playback/v3/` are vendored byte-identically from the
 * server repo, generated there from the live Go contract types; see the SOURCE
 * file beside them. Authority runs one way: the server defines the protocol and
 * this client proves it can read and write it. Nothing here recomputes an
 * expected value — every assertion compares against what the server produced.
 *
 * That matters most for `attempt_keys.json`. Attempt keys are server-minted
 * under the neutral contract: the client stores one, echoes it back, and has no
 * hash function of its own to check them with. Deleting the client-side FNV
 * implementation is what makes "echo it verbatim" the only assertion available
 * here, and the right one.
 *
 * The gate catches three kinds of drift:
 *
 * 1. A field the server emits that this client's models silently drop. Caught
 *    by re-encoding what was decoded and diffing against the fixture — see
 *    [assertClientReadsEveryFieldExcept], which fails naming the lost path.
 * 2. A field this client emits under a name the server's request fixture does
 *    not use, or a required one it omits.
 * 3. An enum member the server uses that does not decode here, which is how a
 *    new delivery class or subtitle mode announces itself.
 */
class PlaybackProtocolV3ConformanceTest {

    /**
     * Unknown keys are tolerated at the decoder and caught by the round-trip
     * diff instead. Doing it the other way — a strict decoder — would fail on
     * the source facts this client has deliberately chosen not to model, and
     * the failure would say only "unknown key", with no way to distinguish a
     * known omission from a field that went missing.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun decisionResponseDecodesAndRoundTripsToTheGoldenWireShape() {
        val raw = fixture("decision_response.json")

        val decoded = json.decodeFromString(PlaybackDecisionResponseV3.serializer(), raw)

        assertEquals(PLAYBACK_PROTOCOL_V3, decoded.protocolVersion)
        assertEquals(PlaybackDecisionOutcome.PLAYABLE, decoded.outcome)
        val plan = assertNotNull(decoded.playbackPlan, "the golden response must decode to a playable plan")
        assertEquals(PlaybackDelivery.ORIGINAL_HTTP, plan.delivery)
        assertEquals(PlaybackStreamProtocol.HTTP_PROGRESSIVE, plan.stream.protocol)
        assertEquals("validated_original_playback", plan.decisionReason)
        assertEquals(7265.5, plan.source.durationSeconds)
        assertTrue(plan.planAttemptKey.startsWith("v3:"), "plan_attempt_key must arrive server-minted")

        assertClientReadsEveryFieldExcept(
            raw,
            json.encodeToJsonElement(PlaybackDecisionResponseV3.serializer(), decoded),
            UNMODELLED_SOURCE_DETAIL,
        )
    }

    /**
     * A tolerant plan decoder is load-bearing — [TolerantPlaybackPlanV3Serializer]
     * turns an unreadable plan into a null the negotiation layer can gate on,
     * rather than a transport error — but it also means a plan that failed to
     * decode looks exactly like a plan the server never sent. So the golden plan
     * has to be proven decodable through the production path too, not only
     * through this test's own decoder.
     */
    @Test
    fun theProductionTolerantDecoderAcceptsTheGoldenPlan() {
        val decoded = SiloJson.decodeFromString(
            PlaybackDecisionResponseV3.serializer(),
            fixture("decision_response.json"),
        )

        val validation = decoded.validateForMedia3()

        assertTrue(
            validation is PlaybackV3Validation.Playable,
            "the golden plan must survive the tolerant decoder; got $validation",
        )
        assertEquals("11111111-1111-4111-8111-111111111111", validation.sessionId)
    }

    /**
     * The quality menu and the subtitle inventory are both server-authoritative.
     * What is checked here is that the client renders what it was sent rather
     * than deriving rungs from the source resolution or renumbering ordinals.
     */
    @Test
    fun planCarriesTheServersQualityMenuAndSubtitleInventory() {
        val plan = assertNotNull(
            json.decodeFromString(
                PlaybackDecisionResponseV3.serializer(),
                fixture("decision_response.json"),
            ).playbackPlan,
        )

        assertEquals(
            listOf("original", "720p", "480p"),
            plan.availableQualities.map { it.label },
            "the quality menu keeps the server's order",
        )
        assertTrue(plan.availableQualities.first().preservesSource)

        assertEquals(
            List(5) { it },
            plan.subtitle.inventory.map { it.combinedIndex },
            "combined ordinals are dense and gap-free",
        )
        val burnInOnly = plan.subtitle.inventory.single { it.delivery == "burn_in_only" }
        assertEquals("file:42:subtitle:3", burnInOnly.trackId)
        assertEquals(3, burnInOnly.combinedIndex, "a burn-in-only track still holds its ordinal")
        assertNull(burnInOnly.url, "…and carries no sidecar URL")
        val styled = plan.subtitle.inventory.single { it.codec == "ass" }
        assertNotNull(styled.fontBundleUrl, "styled tracks publish their font bundle")
    }

    /**
     * The inventory is published twice — inside the plan and as its own fixture
     * — from the same server code. If the two ever disagree, this client is
     * reading one of them wrong.
     */
    @Test
    fun standaloneSubtitleInventoryMatchesTheOneInThePlan() {
        val standalone = SiloJson.parseToJsonElement(fixture("subtitle_inventory.json"))
            .jsonObject.getValue("inventory")
        val fromPlan = SiloJson.parseToJsonElement(fixture("decision_response.json"))
            .jsonObject.getValue("playback_plan")
            .jsonObject.getValue("subtitle")
            .jsonObject.getValue("inventory")

        assertEquals(standalone, fromPlan)
    }

    @Test
    fun startRequestRoundTripsToTheGoldenWireShape() {
        val raw = fixture("start_request.json")

        val decoded = json.decodeFromString(PlaybackStartRequestV3.serializer(), raw)

        assertEquals(PLAYBACK_PROTOCOL_V3, decoded.protocolVersion)
        assertEquals(42, decoded.fileId)
        assertEquals(QUALITY_ORIGINAL_V3, decoded.qualityPreference)
        assertEquals(SubtitleFidelityPreference.COMPATIBLE, decoded.subtitleFidelityPreference)
        assertEquals(CAPABILITY_EVIDENCE_EXACT, decoded.capabilities.videoEvidence)
        assertEquals(CAPABILITY_EVIDENCE_EXACT, decoded.capabilities.audioEvidence)

        assertClientReadsEveryFieldExcept(
            raw,
            json.encodeToJsonElement(PlaybackStartRequestV3.serializer(), decoded),
        )
    }

    @Test
    fun replanRequestRoundTripsToTheGoldenWireShape() {
        val raw = fixture("replan_request.json")

        val decoded = json.decodeFromString(PlaybackReplanRequestV3.serializer(), raw)

        assertEquals(FAILURE_RECOVERY_V3_OPERATION, decoded.operation)
        assertEquals(listOf(decoded.planAttemptKey), decoded.attemptedPlanKeys)
        assertNotNull(decoded.failure, "a recovery replan states what went wrong")

        assertClientReadsEveryFieldExcept(
            raw,
            json.encodeToJsonElement(PlaybackReplanRequestV3.serializer(), decoded),
        )
    }

    /**
     * Output identity is nested under the playback context in the neutral
     * contract; there is no top-level output field on either request. Both are
     * checked because the two shapes used to disagree.
     */
    @Test
    fun outputIdentityTravelsNestedUnderThePlaybackContextOnBothRequests() {
        val start = json.decodeFromString(PlaybackStartRequestV3.serializer(), fixture("start_request.json"))
        val replan = json.decodeFromString(PlaybackReplanRequestV3.serializer(), fixture("replan_request.json"))

        listOf(start.clientPlaybackContext, replan.clientPlaybackContext).forEach { context ->
            assertEquals("7", context.output.outputContextId)
            assertEquals(PLAYBACK_PROTOCOL_V3, context.protocolVersion)
        }
        listOf("start_request.json", "replan_request.json").forEach { name ->
            val body = SiloJson.parseToJsonElement(fixture(name)).jsonObject
            assertNull(body["output"], "$name: output must not reappear as a top-level field")
            assertNull(body["output_route_generation"], "$name: the platform-shaped generation is gone")
        }
    }

    /**
     * Delivery classes replaced the engine self-description. The server
     * negotiates against transports, so a context still describing a Media3
     * engine — or omitting deliveries entirely — would be unroutable.
     */
    @Test
    fun contextAdvertisesDeliveryClassesRatherThanEngines() {
        val raw = fixture("start_request.json")
        val context = json.decodeFromString(PlaybackStartRequestV3.serializer(), raw).clientPlaybackContext

        val delivery = assertNotNull(context.deliveries[DELIVERY_CLASS_ORIGINAL_HTTP])
        assertTrue(delivery.enabled && delivery.supportedOnDevice)
        assertEquals(listOf("h264"), delivery.videoCodecs)
        assertTrue(delivery.authHeaderRefresh)

        val contextJson = SiloJson.parseToJsonElement(raw)
            .jsonObject.getValue("client_playback_context").jsonObject
        assertNull(contextJson["engines"], "engine self-description is gone from the contract")
        assertNull(contextJson["features"], "feature advertisement lives in top-level client_features")
        assertEquals(
            emptySet(),
            contextJson.getValue("deliveries").jsonObject.keys - DELIVERY_CLASSES,
            "deliveries are keyed by delivery class",
        )
    }

    /**
     * Platform-specific device facts belong in the opaque `platform_details`
     * map rather than in fields of their own — that is what keeps the contract
     * from growing an Android-shaped hole.
     */
    @Test
    fun androidBuildFactsTravelAsOpaquePlatformDetails() {
        val device = json.decodeFromString(
            PlaybackStartRequestV3.serializer(),
            fixture("start_request.json"),
        ).clientPlaybackContext.device

        assertEquals("android", device.platform)
        assertEquals(mapOf("abis" to "arm64-v8a", "sdk_int" to "35"), device.platformDetails)
    }

    /**
     * Attempt keys are opaque here. The client cannot recompute the server's
     * hashes and deliberately no longer tries, so what is asserted is the
     * contract it actually depends on: `v3:`-prefixed, distinct per plan, and
     * echoed back byte for byte.
     */
    @Test
    fun serverMintedAttemptKeysAreOpaqueDistinctAndEchoedVerbatim() {
        val cases = SiloJson.parseToJsonElement(fixture("attempt_keys.json")).jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 3, "the fixture must keep covering several distinct routes")

        val keys = cases.map { it.getValue("expected").jsonPrimitive.content }
        keys.forEach { key ->
            assertTrue(key.startsWith("v3:"), "attempt keys are v3-prefixed opaque tokens: $key")
            assertTrue(key.length > "v3:".length, "an attempt key must carry a digest: $key")
        }
        assertEquals(keys.size, keys.toSet().size, "plans differing in delivery or route must not share a key")

        keys.forEach { key ->
            val encoded = SiloJson.encodeToJsonElement(
                PlaybackReplanRequestV3.serializer(),
                replanRequestEchoing(key),
            ).jsonObject
            assertEquals(key, encoded.getValue("plan_attempt_key").jsonPrimitive.content)
            assertEquals(
                listOf(key),
                encoded.getValue("attempted_plan_keys").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    /**
     * The client reports its local mutations and the server folds them into the
     * next key. The fixture's tokens are the vocabulary both sides agreed on, so
     * renaming one has to be a coordinated change.
     */
    @Test
    fun localMutationsAreReportedNotHashedByTheClient() {
        val withMutations = SiloJson.parseToJsonElement(fixture("attempt_keys.json")).jsonArray
            .map { it.jsonObject }
            .first { it.getValue("local_mutations").jsonArray.isNotEmpty() }
        val mutations = withMutations.getValue("local_mutations").jsonArray.map { it.jsonPrimitive.content }

        assertTrue("transport_reopen" in mutations, "a reopened transport is reported, not hashed locally")
        assertTrue(mutations.any { it.startsWith("pcm:") }, "PCM retries report as pcm:<codec>:<channels>")

        val encoded = SiloJson.encodeToJsonElement(
            PlaybackReplanRequestV3.serializer(),
            replanRequestEchoing(
                withMutations.getValue("expected").jsonPrimitive.content,
                localMutations = mutations,
            ),
        ).jsonObject
        assertEquals(mutations, encoded.getValue("local_mutations").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun routeEventRoundTripsToTheGoldenWireShape() {
        val raw = fixture("route_event.json")

        val decoded = json.decodeFromString(PlaybackRouteEventV3.serializer(), raw)

        assertEquals("first_frame", decoded.event)
        assertEquals("7", decoded.outputContextId)
        assertTrue(decoded.diagnostics.isNotEmpty(), "route diagnostics travel as opaque string pairs")

        assertClientReadsEveryFieldExcept(
            raw,
            json.encodeToJsonElement(PlaybackRouteEventV3.serializer(), decoded),
        )
    }

    /**
     * Feature detection reads the capability endpoint rather than sniffing a
     * version. There is no Kotlin model for this response — the client reads it
     * as raw JSON — so the gate is that the advertised protocol version is one
     * this client speaks and every advertised delivery is one it can name.
     */
    @Test
    fun capabilityResponseAdvertisesOnlyProtocolThreeAndNameableDeliveries() {
        val capability = SiloJson.parseToJsonElement(fixture("capability_response.json")).jsonObject

        assertEquals(JsonPrimitive(true), capability.getValue("enabled"))
        assertEquals(
            listOf(PLAYBACK_PROTOCOL_V3),
            capability.getValue("protocol_versions").jsonArray.map { it.jsonPrimitive.content.toInt() },
            "the legacy protocol is gone; v3 is the only one offered",
        )

        val features = capability.getValue("features").jsonArray.map { it.jsonPrimitive.content }
        listOf(
            PLAYBACK_PLAN_V3_FEATURE,
            LAYOUT_AWARE_PASSTHROUGH_FEATURE,
            DEVICE_QUIRKS_V3_FEATURE,
            SEEK_REANCHOR_V3_FEATURE,
            DIRECT_STREAM_RESUME_V1_FEATURE,
        ).forEach { assertTrue(it in features, "the server must keep advertising $it") }

        // An advertised delivery this client cannot decode means the server can
        // route it somewhere the client has no way to represent.
        capability.getValue("deliveries").jsonArray.forEach { delivery ->
            json.decodeFromJsonElement(PlaybackDelivery.serializer(), delivery)
        }
    }

    private fun replanRequestEchoing(
        planAttemptKey: String,
        localMutations: List<String> = emptyList(),
    ): PlaybackReplanRequestV3 = PlaybackReplanRequestV3(
        playbackAttemptId = "attempt-golden-0001",
        replanRequestId = "replan-golden-0001",
        failedPlanId = "plan:golden-0001",
        planAttemptId = "plan-attempt-golden-0001",
        planAttemptKey = planAttemptKey,
        attemptedPlanKeys = listOf(planAttemptKey),
        localMutations = localMutations,
        attemptCount = 1,
        positionSeconds = 42.5,
        selectedTracks = SelectedPlaybackTracksV3(),
        failure = PlaybackFailureV3(classification = "network_degraded"),
        capabilities = ClientCodecCapabilities(),
        clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "3.0-test"),
    )

    /**
     * Fails if the client lost anything the server sent.
     *
     * Only the fixture → re-encoded direction is checked: `encodeDefaults` means
     * the client legitimately writes back fields the server omitted at their
     * default value. The other direction — a path present in the fixture that is
     * absent or different after a decode/encode round trip — is always drift,
     * except for the paths in [allowedMissing], which this client has a stated
     * reason not to model.
     */
    private fun assertClientReadsEveryFieldExcept(
        rawFixture: String,
        reencoded: JsonElement,
        allowedMissing: Set<String> = emptySet(),
    ) {
        val lost = mutableListOf<String>()
        collectLostPaths(SiloJson.parseToJsonElement(rawFixture), reencoded, "$", lost)

        assertEquals(
            allowedMissing.sorted(),
            lost.sorted(),
            "fields the server sent that this client does not round-trip",
        )
    }

    private fun collectLostPaths(
        expected: JsonElement,
        actual: JsonElement?,
        path: String,
        lost: MutableList<String>,
    ) {
        if (actual == null) {
            lost += path
            return
        }
        when (expected) {
            is JsonObject -> {
                val actualObject = actual as? JsonObject
                if (actualObject == null) {
                    lost += path
                    return
                }
                expected.forEach { (key, value) -> collectLostPaths(value, actualObject[key], "$path.$key", lost) }
            }
            is JsonArray -> {
                val actualArray = actual as? JsonArray
                if (actualArray == null || actualArray.size != expected.size) {
                    lost += path
                    return
                }
                expected.forEachIndexed { index, value ->
                    collectLostPaths(value, actualArray[index], "$path[$index]", lost)
                }
            }
            is JsonPrimitive -> if (!primitivesMatch(expected, actual as? JsonPrimitive)) lost += path
        }
    }

    /**
     * Numbers compare by value, not by spelling: the server writes an integral
     * `max_frame_rate` as `60` where the client's `Double` re-encodes it as
     * `60.0`, and that is the same frame rate.
     */
    private fun primitivesMatch(expected: JsonPrimitive, actual: JsonPrimitive?): Boolean {
        if (actual == null) return false
        if (expected.isString || actual.isString) return expected == actual
        val expectedNumber = expected.content.toDoubleOrNull()
        val actualNumber = actual.content.toDoubleOrNull()
        return if (expectedNumber != null && actualNumber != null) {
            expectedNumber == actualNumber
        } else {
            expected == actual
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("playback/v3/$name")) {
            "Missing vendored playback fixture playback/v3/$name"
        }.readText()

    private companion object {
        val DELIVERY_CLASSES = setOf(
            DELIVERY_CLASS_ORIGINAL_HTTP,
            DELIVERY_CLASS_PROGRESSIVE,
            DELIVERY_CLASS_HLS,
        )

        /**
         * Source facts the server publishes that this client deliberately does
         * not model. They inform the server's own routing decisions and clients
         * with a technical-details panel; Media3 learns the same things from the
         * container it is handed. Shrinking this set is always welcome — growing
         * it means a new server field went unread, so add an entry only with a
         * reason.
         */
        val UNMODELLED_SOURCE_DETAIL = setOf(
            "$.playback_plan.source.video_profile",
            "$.playback_plan.source.video_level",
            "$.playback_plan.source.bit_depth",
            "$.playback_plan.source.frame_rate",
            "$.playback_plan.source.bitrate_kbps",
            "$.playback_plan.source.hdr10_plus",
            "$.playback_plan.source.dv_enhancement_layer",
            "$.playback_plan.source.audio_channels",
            "$.playback_plan.source.audio_layout",
        )
    }
}
