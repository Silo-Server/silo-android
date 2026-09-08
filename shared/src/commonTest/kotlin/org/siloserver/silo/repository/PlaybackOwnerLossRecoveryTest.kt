package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*
import kotlin.test.*

class PlaybackOwnerLossRecoveryTest {
    private class Store(initial: PlaybackJournalEntry) : PlaybackJournalStore {
        var entries = listOf(initial)
        override suspend fun read() = entries
        override suspend fun write(entries: List<PlaybackJournalEntry>) {
            this.entries = SiloJson.decodeFromString(SiloJson.encodeToString(entries))
        }
    }
    private class Identity : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
        var scope = AuthScopeSnapshot("server", "profile", "https://fixture.example", "proof",
            identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
        var login = "login"
        override suspend fun snapshotCurrentScope() = scope
        override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority(login, scope)
    }
    private val session = "33333333-3333-4333-8333-333333333333"
    private val recoveryId = "44444444-4444-4444-8444-444444444444"
    private val manifest = PlaybackManifestV2("installation", "a".repeat(64), "item", "edition", 100.0,
        listOf(PlaybackManifestPartV2("41", 0.0, 48.0), PlaybackManifestPartV2("42", 48.0, 52.0)))
    private fun request(attempt: String = "attempt") = PlaybackStartRequestV3(
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3, fileId = 42, profileId = "profile", playbackAttemptId = attempt,
        subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE, capabilities = ClientCodecCapabilities(),
        clientPlaybackContext = ClientPlaybackContext(formFactor = "phone", appVersion = "test"))
    private fun entry(start: Boolean = false, bound: Boolean = false): PlaybackJournalEntry {
        val request = if (bound) request().copy(progressPersistence = ProgressPersistenceV3.CLIENT_BOUND, timelineId = manifest.timelineId) else request()
        val progress = PlaybackProgressV2("installation", 9, 35.0, true, if (bound) manifest.timelineId else null)
        return PlaybackJournalEntry("server", "https://fixture.example", "login", "account", "profile", "installation",
            "attempt", request.v2Body("installation"), sessionId = if (start) null else session,
            sequence = if (start) 0 else 9, progress = if (start) null else progress,
            stop = if (start) null else PlaybackStopV2("installation", "original-stop", 9, 35.0, true, progress.timelineId),
            manifest = if (bound) manifest else null)
    }
    private fun recovery(state: String, accepted: JsonObject? = null) = buildJsonObject {
        put("recovery_id", recoveryId); put("playback_attempt_id", "attempt"); put("session_id", session)
        put("state", state); put("reason", "owner_lost"); accepted?.let { put("accepted", it) }
    }
    private fun sample(bound: Boolean = false) = buildJsonObject {
        put("sequence", 7); put("position", 9.0); put("is_paused", false)
        if (bound) { put("timeline_id", manifest.timelineId); put("item_position", 57.0) }
    }
    private fun response(start: Boolean, pending: Boolean = false, accepted: JsonObject? = null): JsonObject = buildJsonObject {
        put("outcome", if (pending) "draining" else if (start) "adaptation_unavailable" else "aborted")
        put("recovery", recovery(if (pending) "draining" else "aborted", accepted))
        if (start && !pending) {
            put("protocol_version", 3); put("server_features", JsonArray(listOf(JsonPrimitive(SEQUENCED_PROGRESS_FEATURE))))
            put("terminal", buildJsonObject { put("reason", "playback_owner_lost"); put("message", "Playback ended."); put("retryable", false) })
        }
    }
    private class Harness(val store: Store, val identity: Identity = Identity()) {
        var installation = "installation"
        var account = "account"
        val bodies = mutableListOf<String>()
        val methods = mutableListOf<HttpMethod>()
        var exchange: suspend (Int) -> Pair<HttpStatusCode, String> = { error("Unexpected mutation") }
        val client = HttpClient(MockEngine { req ->
            val body = when (req.url.encodedPath) {
                "/api/v2/playback/capabilities" -> HttpStatusCode.OK to """{"installation_id":"$installation","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":["sequenced_progress_v1"],"deliveries":["direct"]}"""
                "/api/v2/account/me" -> HttpStatusCode.OK to """{"id":"$account","username":"test","email":"","role":"user"}"""
                else -> {
                    assertEquals(identity.scope, req.attributes[AuthScopeAttributeKey])
                    assertTrue(req.attributes[SingleAttemptAttributeKey])
                    assertTrue(req.url.encodedPath == "/api/v2/playback/start" || req.url.encodedPath == "/api/v2/playback/${store.entries.first().sessionId}")
                    bodies += req.body.toByteArray().decodeToString(); methods += req.method
                    exchange(bodies.size)
                }
            }
            respond(body.second, body.first, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        fun runtime() = SequencedPlayback(PlaybackV2Api(client), identity, identity, store) { "new-stop" }
    }

    @Test fun lostStartReplyDrainsAcrossReloadThenAbandonsWithoutStopOrAutoplay() = runTest {
        val original = entry(start = true); val h = Harness(Store(original))
        val pending = " \n" + response(start = true, pending = true).toString() + "\n"
        val terminal = " \n" + response(start = true).toString() + "\n"
        h.exchange = { n -> when (n) {
            1 -> throw IllegalStateException("lost response")
            2 -> HttpStatusCode.Accepted to pending
            else -> HttpStatusCode.Created to terminal
        } }
        try {
            assertIs<ApiResult.NetworkError>(h.runtime().recover()); assertEquals(original, h.store.entries.single())
            val runtime = h.runtime()
            assertIs<ApiResult.Error>(runtime.recover())
            assertNull(h.store.entries.single().sessionId)
            assertEquals(session, h.store.entries.single().ownerLoss?.sessionId)
            assertEquals(pending, h.store.entries.single().ownerLossPendingResponse)
            assertEquals(1, runtime.pendingForCurrentViewer())
            assertIs<ApiResult.Error>(runtime.start(request("new")))
            val restored = h.runtime()
            assertIs<ApiResult.Success<*>>(restored.recover())
            val saved = h.store.entries.single()
            assertTrue(saved.terminal); assertEquals("aborted", saved.ownerLoss?.state)
            assertNull(saved.ownerLoss?.accepted); assertNull(saved.stop); assertNull(saved.sessionId)
            assertEquals(original.start, saved.start); assertEquals(terminal, saved.ownerLossTerminalResponse)
            assertEquals(pending, saved.ownerLossPendingResponse)
            assertEquals(0, restored.pendingForCurrentViewer()); assertNull(restored.controlOwner(session))
            assertEquals(List(3) { original.start.toString() }, h.bodies)
            assertEquals(List(3) { HttpMethod.Post }, h.methods)
            assertIs<ApiResult.Success<*>>(restored.recover()); assertEquals(3, h.bodies.size)
            // Only a subsequent explicit user intent allocates another request.
            h.exchange = { HttpStatusCode.ServiceUnavailable to "{}" }
            assertIs<ApiResult.Error>(restored.start(request("new")))
            assertEquals(4, h.bodies.size)
            assertEquals("new", SiloJson.parseToJsonElement(h.bodies.last()).jsonObject["playback_attempt_id"]?.jsonPrimitive?.content)
        } finally { h.client.close() }
    }

    @Test fun stopAbandonmentPersistsOnlyServerLastAndNeverAcknowledgesQueuedFinalSample() = runTest {
        for (accepted in listOf(sample(true), null)) {
            val original = entry(bound = true); val h = Harness(Store(original))
            var complete = false
            h.exchange = { if (!complete) HttpStatusCode.Accepted to response(false, pending = true).toString()
                else HttpStatusCode.OK to response(false, accepted = accepted).toString() }
            try {
                assertIs<ApiResult.Error>(h.runtime().recover())
                assertEquals("draining", h.store.entries.single().ownerLoss?.state)
                complete = true
                val restored = h.runtime()
                assertIs<ApiResult.Success<*>>(restored.recover())
                val saved = h.store.entries.single()
                assertTrue(saved.terminal); assertEquals(original.stop, saved.stop); assertEquals(original.progress, saved.progress)
                assertEquals(original.sequence, saved.sequence); assertEquals(original.start, saved.start)
                assertEquals(if (accepted == null) null else 7L, saved.ownerLoss?.accepted?.sequence)
                assertEquals(if (accepted == null) null else 57.0, saved.acceptedBoundSample?.itemPosition)
                assertEquals(PLAYBACK_OWNER_LOST, assertIs<ApiResult.Error>(restored.stop(session)).error)
                assertEquals(PLAYBACK_OWNER_LOST, assertIs<ApiResult.Error>(h.runtime().stop(session)).error)
                assertEquals(4, h.bodies.size)
                assertTrue(h.bodies.all { it == SiloJson.encodeToString(original.stop) })
                assertNull(restored.controlOwner(session)); assertEquals(0, restored.pendingForCurrentViewer())
            } finally { h.client.close() }
        }
    }

    @Test fun pendingBoundStartUsesSavedManifestForServerLastWithoutPlayablePlan() = runTest {
        for (accepted in listOf(sample(true), null)) {
            val original = entry(start = true, bound = true); val h = Harness(Store(original))
            h.exchange = { HttpStatusCode.Created to response(true, accepted = accepted).toString() }
            try {
                val runtime = h.runtime()
                assertIs<ApiResult.Success<*>>(runtime.recover())
                val retained = h.store.entries.single()
                assertTrue(retained.terminal); assertNull(retained.sessionId); assertNull(retained.stop)
                assertEquals(original.start, retained.start); assertEquals(original.manifest, retained.manifest)
                assertEquals(if (accepted == null) null else 57.0, retained.acceptedBoundSample?.itemPosition)
                assertNull(runtime.controlOwner(session)); assertEquals(listOf(HttpMethod.Post), h.methods)
            } finally { h.client.close() }
        }
    }

    @Test fun changedRecoveryIdentityCannotSettleAfterDrainingReload() = runTest {
        for (start in listOf(true, false)) for (field in listOf("recovery_id", "session_id", "playback_attempt_id")) {
            val h = Harness(Store(entry(start)))
            h.exchange = { HttpStatusCode.Accepted to response(start, pending = true).toString() }
            try {
                assertIs<ApiResult.Error>(h.runtime().recover())
                val retained = h.store.entries.single()
                val changed = JsonObject(recovery("aborted") + (field to JsonPrimitive("55555555-5555-4555-8555-555555555555")))
                h.exchange = { (if (start) HttpStatusCode.Created else HttpStatusCode.OK) to JsonObject(response(start) + ("recovery" to changed)).toString() }
                assertIs<ApiResult.Error>(h.runtime().recover())
                assertEquals(retained, h.store.entries.single())
            } finally { h.client.close() }
        }
    }

    @Test fun malformedStatusAndMixedUnionsNeverReleaseOriginalIntent() = runTest {
        for (start in listOf(true, false)) {
            val base = response(start)
            val success = if (start) HttpStatusCode.Created else HttpStatusCode.OK
            val variants = mutableListOf<Pair<HttpStatusCode, JsonObject>>()
            for (field in listOf("recovery_id", "playback_attempt_id", "session_id", "state", "reason"))
                variants += success to JsonObject(base + ("recovery" to JsonObject(recovery("aborted") - field)))
            for (field in listOf("playback_plan", "progress_timeline", "session_id", "stop_id", "accepted", "history_id"))
                variants += success to JsonObject(base + (field to JsonNull))
            for (status in listOf(HttpStatusCode.Conflict, HttpStatusCode.ServiceUnavailable, HttpStatusCode.Accepted,
                if (start) HttpStatusCode.OK else HttpStatusCode.Created)) variants += status to base
            variants += HttpStatusCode.Accepted to response(start, pending = true, accepted = sample())
            variants += success to JsonObject(base + ("recovery" to JsonObject(recovery("unknown"))))
            variants += success to JsonObject(base + ("recovery" to JsonObject(recovery("aborted") + ("reason" to JsonPrimitive("unknown")))))
            for ((status, body) in variants) {
                val original = entry(start); val h = Harness(Store(original))
                h.exchange = { status to body.toString() }
                try {
                    assertFalse(h.runtime().recover() is ApiResult.Success, "$status $body")
                    assertEquals(original, h.store.entries.single(), "$status $body")
                } finally { h.client.close() }
            }
        }
    }

    @Test fun invalidAcceptedOrMissingBoundAuthorityCannotSettle() = runTest {
        val valid = sample(true)
        val invalid = listOf(
            JsonObject(valid - "sequence"), JsonObject(valid - "is_paused"), JsonObject(valid - "timeline_id"),
            JsonObject(valid + ("sequence" to JsonPrimitive(0))), JsonObject(valid + ("sequence" to JsonPrimitive("7"))),
            JsonObject(valid + ("position" to JsonPrimitive(-1))), JsonObject(valid + ("position" to JsonPrimitive("NaN"))),
            JsonObject(valid + ("item_position" to JsonPrimitive(99))), JsonObject(valid + ("timeline_id" to JsonPrimitive("b".repeat(64)))),
            JsonObject(valid + ("position" to JsonPrimitive(60))), JsonObject(valid + ("is_paused" to JsonPrimitive("false"))))
        for (accepted in invalid) {
            val original = entry(bound = true); val h = Harness(Store(original))
            h.exchange = { HttpStatusCode.OK to response(false, accepted = accepted).toString() }
            try { assertFalse(h.runtime().recover() is ApiResult.Success); assertEquals(original, h.store.entries.single()) }
            finally { h.client.close() }
        }
        for (original in listOf(entry(bound = true).copy(manifest = null), entry(bound = false))) {
            val h = Harness(Store(original)); h.exchange = { HttpStatusCode.OK to response(false, accepted = valid).toString() }
            try { assertFalse(h.runtime().recover() is ApiResult.Success); assertEquals(original, h.store.entries.single()) }
            finally { h.client.close() }
        }
    }

    @Test fun ordinaryOriginalStopReceiptWinsOnlyBeforeRecoveryObservation() = runTest {
        val original = entry(); val h = Harness(Store(original))
        h.exchange = { HttpStatusCode.OK to """{"outcome":"replayed","stop_id":"original-stop"}""" }
        try {
            val runtime = h.runtime(); assertIs<ApiResult.Success<*>>(runtime.recover())
            assertTrue(h.store.entries.single().terminal); assertNull(h.store.entries.single().ownerLoss)
            assertNull(h.store.entries.single().progress); assertIs<ApiResult.Success<*>>(runtime.stop(session))
        } finally { h.client.close() }
    }

    @Test fun observedRecoveryRejectsOrdinaryStopInSameExchangeAndAfterReload() = runTest {
        for (reload in listOf(false, true)) for (outcome in listOf("stopped", "replayed", "draining")) {
            val original = entry(bound = true).copy(acceptedBoundSample = SiloJson.decodeFromJsonElement(sample(true)))
            val h = Harness(Store(original))
            val pending = response(false, pending = true).toString()
            var retained: PlaybackJournalEntry? = null
            h.exchange = { n ->
                if (n == 1 || (reload && n <= 3)) HttpStatusCode.Accepted to pending
                else {
                    retained = retained ?: h.store.entries.single()
                    (if (outcome == "draining") HttpStatusCode.Accepted else HttpStatusCode.OK) to
                        """{"outcome":"$outcome","stop_id":"original-stop","accepted":{"sequence":9,"position":35.0,"is_paused":true,"timeline_id":"${manifest.timelineId}","item_position":83.0}}"""
                }
            }
            try {
                val runtime = h.runtime()
                assertIs<ApiResult.Error>(runtime.recover())
                if (reload) {
                    retained = h.store.entries.single()
                    assertIs<ApiResult.Error>(h.runtime().recover())
                }
                assertNotNull(retained)
                assertEquals(retained, h.store.entries.single())
                assertEquals("draining", h.store.entries.single().ownerLoss?.state)
                assertFalse(h.store.entries.single().terminal)
                assertEquals(original.acceptedBoundSample, h.store.entries.single().acceptedBoundSample)
                assertTrue(h.bodies.all { it == SiloJson.encodeToString(original.stop) })
                assertEquals(1, h.runtime().pendingForCurrentViewer())
            } finally { h.client.close() }
        }
    }

    @Test fun ownerLossReasonWithoutProofCannotAdoptOrChangeBoundOrUnboundJournal() = runTest {
        val negotiatedFeatures = JsonArray(listOf(PLAYBACK_PLAN_V3_FEATURE, NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE, SEQUENCED_PROGRESS_FEATURE).map(::JsonPrimitive))
        val otherwiseValidTerminal = JsonObject(response(true) + ("server_features" to negotiatedFeatures))
        for (bound in listOf(false, true)) for (attachedSession in listOf(false, true)) for (proof in listOf<JsonElement?>(null, JsonNull, buildJsonObject {})) {
            val original = entry(start = true, bound = bound); val h = Harness(Store(original))
            val malformed = JsonObject((otherwiseValidTerminal - "recovery") + buildMap {
                if (attachedSession) put("session_id", JsonPrimitive(session))
                if (proof != null) put("recovery", proof)
            })
            h.exchange = { HttpStatusCode.Created to malformed.toString() }
            try {
                val runtime = h.runtime()
                assertFalse(runtime.recover() is ApiResult.Success)
                assertEquals(original, h.store.entries.single())
                assertFalse(runtime.owns(session)); assertNull(runtime.controlOwner(session))
                assertIs<ApiResult.Error>(runtime.start(request("new")))
                assertEquals(1, h.bodies.size)
                assertEquals(1, runtime.pendingForCurrentViewer())
            } finally { h.client.close() }
        }
        // Fresh unbound START exercises the normal adoptForPlayer=true path too.
        val h = Harness(Store(entry(start = true)).apply { entries = emptyList() })
        val malformed = JsonObject((otherwiseValidTerminal - "recovery") + ("session_id" to JsonPrimitive(session)))
        h.exchange = { HttpStatusCode.Created to malformed.toString() }
        try {
            val runtime = h.runtime()
            assertFalse(runtime.start(request()) is ApiResult.Success)
            assertNull(h.store.entries.single().sessionId); assertNull(h.store.entries.single().ownerLoss)
            assertFalse(runtime.owns(session)); assertNull(runtime.controlOwner(session))
            assertIs<ApiResult.Error>(runtime.start(request("new"))); assertEquals(1, h.bodies.size)
        } finally { h.client.close() }
    }

    @Test fun authorityMismatchAndResponseTimeIdentityChangesPreserveIntent() = runTest {
        for (change in listOf("account", "installation", "profile", "login", "token")) {
            val original = entry(start = true); val h = Harness(Store(original))
            h.exchange = { HttpStatusCode.Accepted to response(true, pending = true).toString() }
            try {
                val runtime = h.runtime(); assertIs<ApiResult.Error>(runtime.recover())
                val retained = h.store.entries.single(); val count = h.bodies.size
                when (change) {
                    "account" -> h.account = "other"
                    "installation" -> h.installation = "other"
                    "profile" -> h.identity.scope = h.identity.scope.copy(profileId = "other")
                    "login" -> h.identity.login = "other"
                    "token" -> h.identity.scope = h.identity.scope.copy(credentialEpoch = 2)
                }
                runtime.recover(); assertEquals(count, h.bodies.size); assertEquals(retained, h.store.entries.single())
            } finally { h.client.close() }
        }
        val original = entry(start = true); val h = Harness(Store(original))
        h.exchange = { h.identity.scope = h.identity.scope.copy(credentialEpoch = 2); HttpStatusCode.Created to response(true).toString() }
        try { assertIs<ApiResult.Error>(h.runtime().recover()); assertEquals(original, h.store.entries.single()) }
        finally { h.client.close() }
    }
}
