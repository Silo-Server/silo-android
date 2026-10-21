package org.siloserver.silo.domain.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.PLAYBACK_START_CLIENT_FEATURES_V3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.QUALITY_CHANGE_V3_OPERATION
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays the server's buffering-quality conformance vectors
 * (`docs/design/schemas/buffering-quality/v1/vectors.json`, vendored unchanged)
 * against [BufferingQualityController] and the kept-estimate rule
 * ([withKeptStepDownEstimate]), composed the way the players and
 * `PlaybackSessionManager` compose them.
 *
 * A failing case is a divergence from web and Apple, which replay the same
 * file. Fix the controller, or fix the spec and the vectors on the server and
 * re-vendor; never edit the vendored copy.
 */
/** The vendored vectors, which [BandwidthProbeVectorsTest] replays too. */
internal fun bufferingQualityVectors(): JsonObject {
    val raw = checkNotNull(
        BufferingQualityVectorsTest::class.java.classLoader?.getResource("playback/buffering-quality/v1/vectors.json"),
    ) { "Missing test resource playback/buffering-quality/v1/vectors.json" }.readText()
    return Json.parseToJsonElement(raw).jsonObject
}

@OptIn(ExperimentalCoroutinesApi::class)
class BufferingQualityVectorsTest {

    private val vectors: JsonObject by lazy { bufferingQualityVectors() }

    @Test
    fun constantsMatchTheVendoredVectors() {
        val constants = vectors.getValue("constants").jsonObject
        fun long(name: String) = constants.getValue(name).jsonPrimitive.long
        fun int(name: String) = constants.getValue(name).jsonPrimitive.int
        with(BufferingQualityController) {
            assertEquals(STARTUP_GRACE_MS, long("STARTUP_GRACE_MS"))
            assertEquals(STALL_MIN_MS, long("STALL_MIN_MS"))
            assertEquals(STALLS_BEFORE_TRIGGER, int("STALLS_BEFORE_TRIGGER"))
            assertEquals(STALL_WINDOW_MS, long("STALL_WINDOW_MS"))
            assertEquals(LONG_STALL_MS, long("LONG_STALL_MS"))
            assertEquals(PROMPT_SECONDS, int("PROMPT_SECONDS"))
            assertEquals(REOFFER_MIN_MS, long("REOFFER_MIN_MS"))
            assertEquals(NO_LOWER_COOLDOWN_MS, long("NO_LOWER_COOLDOWN_MS"))
            assertEquals(REARM_AFTER_UNADOPTED_MS, long("REARM_AFTER_UNADOPTED_MS"))
            assertEquals(RENDERER_WAIT_BUFFER_MS, long("RENDERER_WAIT_BUFFER_MS"))
            assertEquals(STEP_DOWN_RATIO, constants.getValue("STEP_DOWN_RATIO").jsonPrimitive.double)
            assertEquals(MIN_ESTIMATE_KBPS, int("MIN_ESTIMATE_KBPS"))
        }
        assertEquals(
            BufferingQualityMode.Default.wireValue,
            vectors.getValue("defaults").jsonObject.getValue("mode").jsonPrimitive.content,
        )
    }

    @Test
    fun everyNoticeKeyHasVendoredCopy() {
        val copy = vectors.getValue("copy").jsonObject
        for (notice in BufferingQualityNotice.entries) {
            assertTrue(notice.key in copy, "copy has no \"${notice.key}\"")
        }
    }

    @Test
    fun everyCaseReplays() {
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty(), "vectors.json has no cases")
        val defaultMode = vectors.getValue("defaults").jsonObject.getValue("mode").jsonPrimitive.content
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            try {
                replay(case, defaultMode)
            } catch (e: Throwable) {
                failures += "$name: ${e.message ?: e::class.simpleName}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n\n"))
        }
    }

    private fun replay(case: JsonObject, defaultMode: String) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = { testScheduler.currentTime }
        val outputs = mutableListOf<JsonElement>()
        val summaries = mutableListOf<BufferingPlanSummary>()
        val replans = ClientReplans()
        val controller = BufferingQualityController(
            scope = scope,
            clock = clock,
            onReplan = { outputs += replanOutput(clock(), replans.forController(it)) },
            onNotice = { notice ->
                outputs += buildJsonObject {
                    put("t", clock())
                    put("type", "notice")
                    put("key", notice.key)
                }
            },
            onPlanSummary = { summaries += it },
        )
        val modeWire = case["mode"]?.jsonPrimitive?.content ?: defaultMode
        controller.setMode(
            checkNotNull(BufferingQualityMode.fromWire(modeWire)) { "unknown mode $modeWire" },
        )

        var previous: BufferingQualityPill = BufferingQualityPill.Hidden
        scope.launch {
            controller.pill.collect { pill ->
                val shown = when {
                    pill is BufferingQualityPill.Prompt && previous !is BufferingQualityPill.Prompt -> "prompt_shown"
                    pill is BufferingQualityPill.Undo && previous !is BufferingQualityPill.Undo -> "undo_shown"
                    else -> null
                }
                if (shown != null) {
                    outputs += buildJsonObject {
                        put("t", clock())
                        put("type", shown)
                    }
                }
                previous = pill
            }
        }

        try {
            for (element in case.getValue("events").jsonArray) {
                val event = element.jsonObject
                advanceTo(event.getValue("t").jsonPrimitive.long)
                apply(controller, replans, event)?.let { outputs += replanOutput(clock(), it) }
            }
            advanceTo(case.getValue("until").jsonPrimitive.long)

            val expected = case.getValue("expect").jsonArray.toList()
            assertEquals(expected, outputs.toList(), "outputs\n  expected: $expected\n  actual:   $outputs\n")

            val actions = case["buffering_action"]?.jsonObject
            if (actions != null) {
                controller.onPlaybackStopped()
                for ((planId, action) in actions) {
                    val summary = summaries.lastOrNull { it.planId == planId }
                        ?: fail("no plan_summary for $planId (got ${summaries.map { it.planId }})")
                    assertEquals(
                        action.jsonPrimitive.content,
                        summary.action.wireValue,
                        "buffering_action for $planId",
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    /** Runs every timer due at or before [t], leaving the clock at [t]. */
    private fun TestScope.advanceTo(t: Long) {
        val delta = t - testScheduler.currentTime
        require(delta >= 0) { "event at $t is before the clock (${testScheduler.currentTime})" }
        advanceTimeBy(delta)
        runCurrent()
    }

    /**
     * Applies one event, returning the replan it makes the client send for its
     * own reasons, if any. An adoption without `controllerInitiated` answers
     * the controller's pending replan, if it has one.
     */
    private fun apply(
        controller: BufferingQualityController,
        replans: ClientReplans,
        event: JsonObject,
    ): PlaybackReplanRequestV3? {
        when (val type = event.getValue("type").jsonPrimitive.content) {
            "plan_adopted" -> {
                val plan = planOf(event.getValue("plan").jsonObject)
                replans.adoptedQuality = plan.quality
                controller.onPlanAdopted(
                    plan,
                    controllerInitiated = event["controllerInitiated"]?.jsonPrimitive?.boolean ?: true,
                )
            }
            "client_replan" -> {
                val operation = event.getValue("operation").jsonPrimitive.content
                if (operation !in CLIENT_REPLAN_OPERATIONS) fail("unknown client_replan operation $operation")
                return replans.forClient(operation)
            }
            "quality_changed" -> {
                // The viewer's pick, as PlayerViewModel.onSelectQuality and
                // the TV equivalent make it: the controller hears of it, and
                // the pick goes out as a quality_change the controller did not
                // send. Its replan is not one of the vectors' outputs.
                controller.onViewerQualityChange()
                replans.forClient(QUALITY_CHANGE_V3_OPERATION)
            }
            "new_attempt" -> {
                // A same-title attempt the viewer did not choose, as the
                // players' renewal and Retry paths make it: the plan ends and
                // the kept estimate goes; the title's state stays.
                controller.onNewAttempt()
                replans.newAttempt()
            }
            "playing" -> controller.onPlaying()
            "waiting" -> controller.onWaiting(causeOf(event["cause"]?.jsonPrimitive?.content))
            "pause" -> controller.onPause()
            "seek" -> controller.onSeek()
            "transport_error_start" -> controller.onTransportErrorStart()
            "transport_error_end" -> controller.onTransportErrorEnd()
            "replan_failed" -> controller.onReplanFailed()
            "select_lower" -> controller.selectLower()
            "select_keep" -> controller.selectKeep()
            "select_restore" -> controller.selectRestore()
            "back" -> controller.back()
            "pill_obscured" -> controller.setOtherPillVisible(true)
            "pill_unobscured" -> controller.setOtherPillVisible(false)
            else -> fail("unknown event type $type")
        }
        return null
    }

    private fun causeOf(cause: String?): BufferingWaitCause = when (cause) {
        null -> BufferingWaitCause.NETWORK
        "seek" -> BufferingWaitCause.SEEK
        "pause" -> BufferingWaitCause.PAUSE
        "reload" -> BufferingWaitCause.RELOAD
        "renderer" -> BufferingWaitCause.RENDERER
        else -> fail("unknown waiting cause $cause")
    }

    /** A vector's `plan_id` label is an adoption identity; missing `delivered_kbps` is encoded as 0. */
    private fun planOf(plan: JsonObject) = BufferingQualityPlan(
        planId = plan.getValue("plan_id").jsonPrimitive.content,
        deliveredKbps = plan["delivered_kbps"]?.jsonPrimitive?.intOrNull,
        quality = plan.getValue("quality").jsonPrimitive.content,
        availableQualities = plan["available_qualities"]?.jsonArray.orEmpty().map {
            val rung = it.jsonObject
            BufferingQualityRung(
                label = rung.getValue("label").jsonPrimitive.content,
                bitrateKbps = rung["bitrate_kbps"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        },
        qualityReductionUnavailable = plan["degradation_warnings"]?.jsonArray.orEmpty()
            .any { it.jsonPrimitive.content == "quality_reduction_unavailable" },
    )

    /** A sent replan as a vector output: its body is the wire request, cut to the fields the vectors cover. */
    private fun replanOutput(t: Long, request: PlaybackReplanRequestV3) = buildJsonObject {
        put("t", t)
        put("type", "replan")
        val wire = SiloJson.encodeToJsonElement(request).jsonObject
        put("body", JsonObject(wire.filterKeys { it in REPLAN_BODY_KEYS }))
    }

    /**
     * The replans one playback sends, built as `PlaybackSessionManager` builds
     * them and passed through the kept-estimate rule, with the kept estimate
     * held between requests as the manager holds it.
     */
    private class ClientReplans {
        private var attempt = 1
        private var kept: KeptStepDownEstimate? = null

        /** The playing plan's quality, which the client's own replans ask for. */
        var adoptedQuality: String = PLAYBACK_QUALITY_AUTO

        /** The controller's replan: always a `quality_change` carrying its fields. */
        fun forController(replan: BufferingQualityReplan): PlaybackReplanRequestV3 = send(
            request(QUALITY_CHANGE_V3_OPERATION, replan.qualityPreference).copy(
                bandwidthEstimateKbps = replan.bandwidthEstimateKbps,
                bandwidthEstimateSource = replan.bandwidthEstimateSource,
                learnedLimit = replan.learnedLimit,
            ),
            replan,
        )

        /** A replan the client sends for its own reasons, at the playing plan's quality. */
        fun forClient(operation: String): PlaybackReplanRequestV3 =
            send(request(operation, adoptedQuality), bufferingQuality = null)

        fun newAttempt() {
            attempt++
        }

        private fun send(request: PlaybackReplanRequestV3, bufferingQuality: BufferingQualityReplan?): PlaybackReplanRequestV3 {
            val sent = request.withKeptStepDownEstimate(kept, bufferingQuality)
            kept = sent.kept
            return sent.request
        }

        private fun request(operation: String, quality: String) = PlaybackReplanRequestV3(
            clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
            operation = operation,
            playbackAttemptId = "attempt-$attempt",
            replanRequestId = "replan",
            failedPlanId = "plan",
            planAttemptId = "plan-attempt",
            planAttemptKey = "key",
            attemptedPlanKeys = emptyList(),
            attemptCount = 1,
            qualityPreference = quality,
            positionSeconds = 0.0,
            selectedTracks = SelectedPlaybackTracksV3(),
            capabilities = ClientCodecCapabilities(),
            clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test"),
        )
    }

    private companion object {
        /** The operations a vector's `client_replan` may name. */
        val CLIENT_REPLAN_OPERATIONS = setOf(
            "track_change",
            "seek_reanchor",
            "seek_failure_recovery",
            "failure_recovery",
            "output_change",
        )

        val REPLAN_BODY_KEYS = setOf(
            "operation",
            "quality_preference",
            "bandwidth_estimate_kbps",
            "bandwidth_estimate_source",
            "learned_limit",
        )
    }
}
