package org.siloserver.silo.model.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.ApiErrorBody
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class AttemptKeyFixtureV3(
    val name: String,
    @SerialName("server_plan_attempt_key") val serverPlanAttemptKey: String,
    @SerialName("replan_echo") val replanEcho: String,
    @SerialName("attempted_plan_keys") val attemptedPlanKeys: List<String>,
    @SerialName("expected_server_action") val expectedServerAction: String,
)

@Serializable
private data class ConformanceMatrixFixtureV3(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("planner_scenarios") val plannerScenarios: List<PlannerScenarioFixtureV3>,
    @SerialName("replan_scenarios") val replanScenarios: List<ReplanScenarioFixtureV3>,
    @SerialName("protocol_scenarios") val protocolScenarios: List<ProtocolScenarioFixtureV3>,
)

@Serializable
private data class PlannerScenarioFixtureV3(
    val name: String,
    val category: String,
    val request: PlaybackStartRequestV3,
    val source: SourceDescriptorFixtureV3,
    @SerialName("attempted_plan_keys") val attemptedPlanKeys: List<String> = emptyList(),
    val expected: PlannerExpectationFixtureV3,
)

@Serializable
private data class PlannerExpectationFixtureV3(
    val outcome: PlaybackDecisionOutcome,
    val delivery: PlaybackDelivery? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_attempt_key") val planAttemptKey: String? = null,
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracksV3? = null,
    val subtitle: PlaybackSubtitleDecisionV3? = null,
    val claims: PlaybackValidationClaims? = null,
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("available_qualities") val availableQualities: List<PlaybackAvailableQualityV3> = emptyList(),
    @SerialName("terminal_reason") val terminalReason: String? = null,
)

@Serializable
private data class SourceDescriptorFixtureV3(
    @SerialName("media_file_id") val mediaFileId: Int,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    val container: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("video_profile") val videoProfile: String? = null,
    @SerialName("video_level") val videoLevel: Int = 0,
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("color_range") val colorRange: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("frame_rate") val frameRate: Double = 0.0,
    @SerialName("bitrate_kbps") val bitrateKbps: Int = 0,
    @SerialName("dynamic_range") val dynamicRange: String? = null,
    @SerialName("hdr10_plus") val hdr10Plus: Boolean = false,
    @SerialName("dolby_vision_profile") val dolbyVisionProfile: Int = 0,
    @SerialName("dv_bl_compat_id") val dolbyVisionBaseLayerCompatibilityId: Int = 0,
    @SerialName("dv_enhancement_layer") val dolbyVisionEnhancementLayer: String,
    @SerialName("audio_codec") val audioCodec: String? = null,
    @SerialName("audio_channels") val audioChannels: Int = 0,
    @SerialName("audio_layout") val audioLayout: String? = null,
    @SerialName("video_copy_unsafe") val videoCopyUnsafe: Boolean = false,
)

@Serializable
private data class ReplanScenarioFixtureV3(
    val name: String,
    val category: String,
    val request: PlaybackReplanRequestV3,
    val expected: ReplanExpectationFixtureV3,
)

@Serializable
private data class ReplanExpectationFixtureV3(
    @SerialName("http_status") val httpStatus: Int? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("position_preserved") val positionPreserved: Boolean? = null,
    @SerialName("preserve_unmodified_tracks") val preserveUnmodifiedTracks: Boolean? = null,
    @SerialName("selected_quality") val selectedQuality: String? = null,
    @SerialName("same_request_and_body_status") val sameRequestAndBodyStatus: Int? = null,
    @SerialName("response_replayed_verbatim") val responseReplayedVerbatim: Boolean? = null,
    @SerialName("changed_body_status") val changedBodyStatus: Int? = null,
    @SerialName("changed_body_error") val changedBodyError: String? = null,
    @SerialName("while_first_lease_active_status") val whileFirstLeaseActiveStatus: Int? = null,
    @SerialName("concurrent_error") val concurrentError: String? = null,
    @SerialName("after_completion_status") val afterCompletionStatus: Int? = null,
)

@Serializable
private data class ProtocolScenarioFixtureV3(
    val name: String,
    val category: String,
    val input: ProtocolScenarioInputFixtureV3,
    val expected: ProtocolExpectationFixtureV3,
)

@Serializable
private data class ProtocolScenarioInputFixtureV3(
    @SerialName("start_request") val startRequest: PlaybackStartRequestV3? = null,
    @SerialName("replan_request") val replanRequest: PlaybackReplanRequestV3? = null,
    @SerialName("route_event") val routeEvent: PlaybackRouteEventV3? = null,
    @SerialName("persisted_decision") val persistedDecision: PlaybackDecisionResponseV3? = null,
    val body: LegacyStartBodyFixtureV3? = null,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("first_output_context_id") val firstOutputContextId: String? = null,
    @SerialName("second_output_context_id") val secondOutputContextId: String? = null,
    @SerialName("first_plan_attempt_key") val firstPlanAttemptKey: String? = null,
    @SerialName("second_plan_attempt_key") val secondPlanAttemptKey: String? = null,
    @SerialName("server_plan_attempt_key") val serverPlanAttemptKey: String? = null,
    @SerialName("replan_echo") val replanEcho: String? = null,
    @SerialName("attempted_plan_keys") val attemptedPlanKeys: List<String> = emptyList(),
    val restarted: Boolean = false,
    @SerialName("capacity_available") val capacityAvailable: Boolean? = null,
)

@Serializable
private data class LegacyStartBodyFixtureV3(
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    @SerialName("file_id") val fileId: Int,
    @SerialName("client_capabilities") val clientCapabilities: ClientCodecCapabilities? = null,
)

@Serializable
private data class ProtocolExpectationFixtureV3(
    @SerialName("http_status") val httpStatus: Int? = null,
    val error: String? = null,
    val outcome: PlaybackDecisionOutcome? = null,
    @SerialName("terminal_reason") val terminalReason: String? = null,
    @SerialName("plan_id_unchanged") val planIdUnchanged: Boolean? = null,
    @SerialName("plan_attempt_key_changed") val planAttemptKeyChanged: Boolean? = null,
    @SerialName("selection_preserved") val selectionPreserved: Boolean? = null,
    @SerialName("position_preserved") val positionPreserved: Boolean? = null,
    @SerialName("response_replayed_verbatim") val responseReplayedVerbatim: Boolean? = null,
    @SerialName("capacity_delta") val capacityDelta: Int? = null,
    @SerialName("cleanup_complete") val cleanupComplete: Boolean? = null,
    val action: String? = null,
)

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
        assertTrue(NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE in decoded.serverFeatures)
        val plan = assertNotNull(decoded.playbackPlan, "the golden response must decode to a playable plan")
        assertEquals(PlaybackDelivery.ORIGINAL_HTTP, plan.delivery)
        assertEquals(PlaybackStreamProtocol.HTTP_PROGRESSIVE, plan.stream.protocol)
        assertEquals("validated_original_playback", plan.decisionReason)
        assertEquals(7200.0, plan.source.durationSeconds)
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
            listOf("original"),
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
        assertEquals(ProgressPersistenceV3.CLIENT, decoded.progressPersistence)
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
        val decisionPlan = assertNotNull(
            json.decodeFromString(
                PlaybackDecisionResponseV3.serializer(),
                fixture("decision_response.json"),
            ).playbackPlan,
        )

        assertEquals(FAILURE_RECOVERY_V3_OPERATION, decoded.operation)
        assertEquals(decisionPlan.planId, decoded.failedPlanId)
        assertEquals(decisionPlan.planAttemptKey, decoded.planAttemptKey)
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
     * The build behind the marketing version, and how the build was
     * distributed. Both are optional on the wire, so the round-trip check alone
     * would stay green if the client emitted them under names the server does
     * not read — assert the encoded keys directly instead.
     */
    @Test
    fun contextNamesTheBuildAndChannelBehindTheAppVersion() {
        listOf("start_request.json", "replan_request.json").forEach { name ->
            val context = SiloJson.parseToJsonElement(fixture(name))
                .jsonObject["client_playback_context"]!!.jsonObject

            assertEquals("5", context["app_build"]?.jsonPrimitive?.content, "$name: app_build")
            assertEquals("production", context["app_channel"]?.jsonPrimitive?.content, "$name: app_channel")
        }

        val encoded = json.encodeToJsonElement(
            ClientPlaybackContext.serializer(),
            ClientPlaybackContext(
                formFactor = "tv",
                appVersion = "3.0-test",
                appBuild = "5",
                appChannel = "production",
            ),
        ).jsonObject

        assertEquals("5", encoded["app_build"]?.jsonPrimitive?.content)
        assertEquals("production", encoded["app_channel"]?.jsonPrimitive?.content)
    }

    /**
     * An unstamped local build has no build counter to report. It must vanish
     * from the body rather than travel as an explicit null or a literal "0",
     * which the server — treating the field as opaque — would render verbatim.
     */
    @Test
    fun anAbsentBuildIsOmittedFromTheBodyRatherThanSentAsNull() {
        val encoded = json.encodeToJsonElement(
            ClientPlaybackContext.serializer(),
            ClientPlaybackContext(formFactor = "tv", appVersion = "3.0-test"),
        ).jsonObject

        assertNull(encoded["app_build"], "an unstamped build must not appear on the wire")
        assertNull(encoded["app_channel"], "an unreported channel must not appear on the wire")
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
        val cases = json.decodeFromString<List<AttemptKeyFixtureV3>>(fixture("attempt_keys.json"))
        assertTrue(cases.size >= 3, "the fixture must keep covering several distinct routes")

        val keys = cases.map { it.serverPlanAttemptKey }
        keys.forEach { key ->
            assertTrue(key.startsWith("v3:"), "attempt keys are v3-prefixed opaque tokens: $key")
            assertTrue(key.length > "v3:".length, "an attempt key must carry a digest: $key")
        }
        assertEquals(keys.size, keys.toSet().size, "plans differing in delivery or route must not share a key")

        cases.forEach { case ->
            assertEquals(case.serverPlanAttemptKey, case.replanEcho)
            assertEquals(listOf(case.serverPlanAttemptKey), case.attemptedPlanKeys)
            assertEquals("reject_already_attempted_plan", case.expectedServerAction)

            val encoded = SiloJson.encodeToJsonElement(
                PlaybackReplanRequestV3.serializer(),
                replanRequestEchoing(case.serverPlanAttemptKey),
            ).jsonObject
            assertEquals(case.serverPlanAttemptKey, encoded.getValue("plan_attempt_key").jsonPrimitive.content)
            assertEquals(
                case.attemptedPlanKeys,
                encoded.getValue("attempted_plan_keys").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    /**
     * Attempt-key fixtures intentionally contain no route recipe or hash input.
     * If either reappears, the neutral corpus has regressed toward teaching the
     * client how the server derives its private identity.
     */
    @Test
    fun attemptKeyCorpusContainsOnlyOpaqueEchoContractFields() {
        SiloJson.parseToJsonElement(fixture("attempt_keys.json")).jsonArray.forEach { element ->
            assertEquals(
                setOf(
                    "name",
                    "server_plan_attempt_key",
                    "replan_echo",
                    "attempted_plan_keys",
                    "expected_server_action",
                ),
                element.jsonObject.keys,
            )
        }
    }

    @Test
    fun routeEventRoundTripsToTheGoldenWireShape() {
        val raw = fixture("route_event.json")

        val decoded = json.decodeFromString(PlaybackRouteEventV3.serializer(), raw)
        val decisionPlan = assertNotNull(
            json.decodeFromString(
                PlaybackDecisionResponseV3.serializer(),
                fixture("decision_response.json"),
            ).playbackPlan,
        )

        assertEquals("first_frame", decoded.event)
        assertEquals(decisionPlan.planId, decoded.planId)
        assertEquals(decisionPlan.planAttemptKey, decoded.planAttemptKey)
        assertEquals("7", decoded.outputContextId)
        assertTrue(decoded.diagnostics.isNotEmpty(), "route diagnostics travel as opaque string pairs")

        assertClientReadsEveryFieldExcept(
            raw,
            json.encodeToJsonElement(PlaybackRouteEventV3.serializer(), decoded),
        )
    }

    @Test
    fun protocolErrorEnvelopeDecodesThroughTheProductionApiErrorType() {
        val raw = fixture("error_response.json")
        val decoded = json.decodeFromString(ApiErrorBody.serializer(), raw)

        assertEquals("client_upgrade_required", decoded.error)
        assertTrue(decoded.message.isNotBlank())
        assertClientReadsEveryFieldExcept(raw, json.encodeToJsonElement(ApiErrorBody.serializer(), decoded))
    }

    @Test
    fun conformanceMatrixDecodesAndRoundTripsEveryGeneratedScenario() {
        val raw = fixture("conformance_matrix.json")
        val matrix = json.decodeFromString(ConformanceMatrixFixtureV3.serializer(), raw)

        assertEquals(1, matrix.schemaVersion)
        assertEquals(17, matrix.plannerScenarios.size)
        assertEquals(9, matrix.replanScenarios.size)
        assertEquals(8, matrix.protocolScenarios.size)
        assertClientReadsEveryFieldExcept(
            raw,
            json.encodeToJsonElement(ConformanceMatrixFixtureV3.serializer(), matrix),
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
            NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
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
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
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
