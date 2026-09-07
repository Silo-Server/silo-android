package org.siloserver.silo.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*

/** Atomic durable writes must complete before returning; failure prevents dispatch. No credentials. */
interface PlaybackJournalStore {
    suspend fun read(): List<PlaybackJournalEntry>
    suspend fun write(entries: List<PlaybackJournalEntry>)
}

@Serializable
data class PlaybackReplanIntent(
    val body: JsonObject,
    val response: JsonObject? = null,
    val rejectedCode: Int? = null,
    val rejectedMessage: String? = null,
)

@Serializable
data class PlaybackJournalEntry(
    val serverId: String,
    val origin: String,
    val loginId: String,
    val accountId: String,
    val profileId: String,
    val installationId: String,
    val attemptId: String,
    val start: JsonObject,
    val sessionId: String? = null,
    val sequence: Long = 0,
    val progress: PlaybackProgressV2? = null,
    val stop: PlaybackStopV2? = null,
    val terminal: Boolean = false,
    val replans: List<PlaybackReplanIntent> = emptyList(),
    val routeEvents: List<JsonObject> = emptyList(),
    val manifest: PlaybackManifestV2? = null,
    val acceptedBoundSample: PlaybackSampleV2? = null,
)

/** One shared coordinator covers local, cast, candidate and orphan cleanup callers. */
class SequencedPlayback(
    private val api: PlaybackV2Api,
    private val tokens: TokenManager,
    private val authorities: DurableLoginAuthorityProvider,
    private val store: PlaybackJournalStore,
    private val newId: () -> String,
) {
    private val mutex = Mutex()
    private var entries: List<PlaybackJournalEntry>? = null
    private val adopted = mutableSetOf<String>()
    private val discovered = mutableMapOf<String, CapturedPlaybackManifest>()
    private val scopes = mutableMapOf<String, AuthScopeSnapshot>()
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending = _pending.asStateFlow()
    private val _sessions = MutableStateFlow<Set<String>>(emptySet())
    fun owns(sessionId: String): Boolean = sessionId in _sessions.value

    private suspend fun load(): List<PlaybackJournalEntry> {
        if (entries == null) { entries = store.read(); publish() }
        return requireNotNull(entries)
    }
    private fun PlaybackJournalEntry.needsRecovery(): Boolean = !terminal &&
        (stop != null || attemptId !in adopted || replans.any { it.response == null && it.rejectedCode == null })

    private fun publish() {
        _sessions.value = entries.orEmpty().mapNotNull { it.sessionId }.toSet()
        _pending.value = entries.orEmpty().filter { it.needsRecovery() }
            .map { it.attemptId }
    }
    private suspend fun save(entry: PlaybackJournalEntry) {
        val next = load().filterNot { it.attemptId == entry.attemptId } + entry
        store.write(next)
        entries = next
        publish()
    }
    private fun failure(code: String, message: String) = ApiResult.Error(0, code, message)
    private suspend fun scope(entry: PlaybackJournalEntry): AuthScopeSnapshot? {
        val live = authorities.snapshotDurableLoginAuthority() ?: return null
        val captured = scopes[entry.attemptId] ?: return null // Restart requires explicit recovery.
        return live.scope.takeIf {
            live.loginId == entry.loginId && it.serverId == entry.serverId &&
                it.serverUrl == entry.origin && it.profileId == entry.profileId &&
                captured.isSameIdentityAs(it) && it.credentialGenerationId == null
        }
    }

    suspend fun discoverTimeline(fileId: Int, itemId: String, expectedOwner: AuthScopeSnapshot): ApiResult<CapturedPlaybackManifest> = mutex.withLock {
        val owner = authorities.snapshotDurableLoginAuthority()
            ?: return@withLock failure("identity_unavailable", "Audiobook playback needs a saved account.")
        if (owner.scope != expectedOwner) return@withLock failure("identity_changed", "The audiobook metadata viewer changed.")
        if (fileId <= 0 || itemId.isBlank() || owner.scope.profileId.isNullOrBlank() || owner.scope.credentialGenerationId != null)
            return@withLock failure("identity_unavailable", "Audiobook playback needs a selected file and profile.")
        val capability = when (val result = api.capabilities(owner.scope)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return@withLock result
            is ApiResult.NetworkError -> return@withLock result
        }
        if (!capability.allowed || capability.state != "available" || capability.installationId.isNullOrBlank() ||
            BOUND_CLIENT_TIMELINE_FEATURE !in capability.features || SEQUENCED_PROGRESS_FEATURE !in capability.features)
            return@withLock failure("playback_unavailable", "This server does not support bound audiobook progress.")
        if (owner != authorities.snapshotDurableLoginAuthority()) return@withLock failure("identity_changed", "The audiobook viewer changed.")
        when (val result = api.timeline(owner.scope, fileId, capability.installationId)) {
            is ApiResult.Success -> {
                if (owner != authorities.snapshotDurableLoginAuthority()) return@withLock failure("identity_changed", "The audiobook viewer changed.")
                try {
                    val manifest = result.data
                    manifest.validate()
                    require(manifest.installationId == capability.installationId && manifest.mediaItemId == itemId)
                    manifest.select(fileId)
                    val captured = CapturedPlaybackManifest(manifest, owner)
                    discovered[manifest.timelineId] = captured
                    ApiResult.Success(captured)
                } catch (_: IllegalArgumentException) { failure("invalid_timeline", "The server returned an invalid audiobook manifest.") }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun boundResume(captured: CapturedPlaybackManifest): Double? = mutex.withLock {
        if (captured.authority != authorities.snapshotDurableLoginAuthority()) return@withLock null
        load().lastOrNull { it.loginId == captured.authority.loginId && it.serverId == captured.authority.scope.serverId &&
            it.origin == captured.authority.scope.serverUrl && it.profileId == captured.authority.scope.profileId &&
            it.installationId == captured.manifest.installationId && it.manifest?.timelineId == captured.manifest.timelineId &&
            it.acceptedBoundSample != null }?.acceptedBoundSample?.itemPosition
    }

    private fun boundTimeline(entry: PlaybackJournalEntry): PlaybackProgressTimelineV2? =
        entry.manifest?.select(entry.start.getValue("file_id").jsonPrimitive.content.toInt())

    /** Admission requires v2; unavailable capabilities never select a legacy transport. */
    suspend fun start(request: PlaybackStartRequestV3, expectedMetadataOwner: AuthScopeSnapshot? = null): ApiResult<PlaybackDecisionResponseV3>? = mutex.withLock {
        suspend fun accepted() = tokens.acceptsMetadataOwner(expectedMetadataOwner, request.profileId)
        fun changed() = failure("identity_changed", "The metadata viewer changed before playback admission.")
        if (!accepted()) return@withLock changed()
        val current = tokens.snapshotCurrentScope()
            ?: return@withLock failure("identity_unavailable", "Playback needs an authenticated profile.")
        if (expectedMetadataOwner != null && !expectedMetadataOwner.matchesMetadataOwner(current)) return@withLock changed()
        // Probe availability before admission; retained intents still fence new attempts.
        val capabilityResult = api.capabilities(current)
        if (!accepted()) return@withLock changed()
        val capability = when (capabilityResult) {
            is ApiResult.Success -> capabilityResult.data
            is ApiResult.Error -> if (capabilityResult.code == 404) null else return@withLock capabilityResult
            is ApiResult.NetworkError -> return@withLock capabilityResult
        }
        if (!current.isSameIdentityAs(tokens.snapshotCurrentScope()))
            return@withLock failure("identity_changed", "The active viewer changed.")
        val live = authorities.snapshotDurableLoginAuthority()
        if (!accepted()) return@withLock changed()
        val unresolved = load().any { it.needsRecovery() && it.loginId == live?.loginId &&
            it.serverId == current.serverId && it.profileId == current.profileId }
        if (!accepted()) return@withLock changed()
        if (unresolved) return@withLock failure("playback_pending", "A previous playback request needs recovery before starting again.")
        if (capability == null) return@withLock failure("server_update_required", "Update the server to use v2 playback.")
        if (SEQUENCED_PROGRESS_FEATURE !in capability.features) return@withLock failure("playback_unavailable", "V2 playback is unavailable on this server.")
        if (!capability.allowed || capability.state != "available" || capability.installationId.isNullOrBlank() ||
            3 !in capability.protocolVersions)
            return@withLock failure("playback_unavailable", "Sequenced playback is unavailable for this profile.")
        if (live == null || !current.isSameIdentityAs(live.scope) || live.scope.credentialGenerationId != null ||
            current.profileId != request.profileId)
            return@withLock failure("identity_unavailable", "Sequenced playback needs a saved login and matching profile.")
        val bound = if (request.progressPersistence == org.siloserver.silo.model.playback.ProgressPersistenceV3.CLIENT_BOUND) {
            val snapshot = discovered[request.timelineId]
                ?: return@withLock failure("timeline_unavailable", "Discover a trusted audiobook timeline before starting.")
            if (snapshot.authority != live || snapshot.manifest.installationId != capability.installationId ||
                BOUND_CLIENT_TIMELINE_FEATURE !in capability.features || BOUND_CLIENT_TIMELINE_FEATURE !in request.clientFeatures)
                return@withLock failure("timeline_unavailable", "The captured audiobook timeline is no longer available for this viewer.")
            val part = snapshot.manifest.parts.singleOrNull { it.fileId == request.fileId.toString() }
                ?: return@withLock failure("invalid_timeline", "The selected part is outside the captured manifest.")
            if (request.startPosition?.let { it.isFinite() && it in 0.0..part.durationSeconds } != true)
                return@withLock failure("invalid_position", "Bound playback needs an explicit part-local start position.")
            snapshot.manifest
        } else {
            if (request.timelineId != null) return@withLock failure("invalid_timeline", "Unbound playback cannot carry timeline authority.")
            null
        }
        val account = when (val result = api.account(current)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return@withLock result
            is ApiResult.NetworkError -> return@withLock result
        }
        if (!accepted() || !current.isSameIdentityAs(tokens.snapshotCurrentScope()))
            return@withLock failure("identity_changed", "The active viewer changed.")
        if (load().any { it.attemptId == request.playbackAttemptId })
            return@withLock failure("attempt_exists", "This playback attempt is already recorded. Use recovery.")
        if (!accepted()) return@withLock changed()
        val entry = PlaybackJournalEntry(current.serverId, current.serverUrl, live.loginId, account.id,
            request.profileId, capability.installationId, request.playbackAttemptId, request.v2Body(capability.installationId), manifest = bound)
        save(entry)
        scopes[entry.attemptId] = current
        // A saved attempt is uncertainty, even if its owner changes before send.
        if (!accepted()) return@withLock changed()
        sendStart(entry, current, expectedMetadataOwner = expectedMetadataOwner)
    }

    private suspend fun sendStart(entry: PlaybackJournalEntry, captured: AuthScopeSnapshot,
        adoptForPlayer: Boolean = true, expectedMetadataOwner: AuthScopeSnapshot? = null): ApiResult<PlaybackDecisionResponseV3> {
        if (scope(entry) == null || !tokens.acceptsMetadataOwner(expectedMetadataOwner, entry.profileId))
            return failure("identity_changed", "The active viewer changed.")
        return when (val result = api.start(captured, entry.start)) {
            is ApiResult.Success -> {
                // Persist the session before decoding a renderer plan, so invalid plans can still be stopped.
                val session = result.data["session_id"]?.jsonPrimitive?.contentOrNull
                if (session.isNullOrBlank()) return failure("invalid_decision", "Playback returned no recoverable session.")
                save(entry.copy(sessionId = session))
                try {
                    val decision = decodePlaybackDecisionV2(result.data)
                    val expectedTimeline = boundTimeline(entry)
                    if (decision.progressTimeline != expectedTimeline || (expectedTimeline != null &&
                        decision.playbackPlan?.effectiveMediaFileId?.toString() != expectedTimeline.fileId))
                        return failure("invalid_timeline", "Playback did not retain the captured audiobook part mapping.")
                    if (SEQUENCED_PROGRESS_FEATURE !in decision.serverFeatures)
                        return failure("invalid_decision", "Playback omitted the negotiated progress feature.")
                    if (scope(entry) == null) return failure("identity_changed", "The active viewer changed.")
                    if (!tokens.acceptsMetadataOwner(expectedMetadataOwner, entry.profileId))
                        return failure("identity_changed", "The metadata viewer changed after playback admission.")
                    if (adoptForPlayer) adopted += entry.attemptId
                    publish()
                    ApiResult.Success(decision)
                } catch (e: Exception) { ApiResult.NetworkError(e) }
            }
            // Preserve uncertain starts, including validation responses until pre-admission is proven.
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /** Socket authority comes only from the admitted session, never from a fresh capability probe. */
    suspend fun controlOwner(sessionId: String): Pair<AuthScopeSnapshot, String>? = mutex.withLock {
        val entry = load().find { it.sessionId == sessionId && !it.terminal && it.stop == null } ?: return@withLock null
        val captured = scope(entry) ?: return@withLock null
        captured to entry.installationId
    }

    suspend fun replan(sessionId: String, request: PlaybackReplanRequestV3): ApiResult<PlaybackDecisionResponseV3> = mutex.withLock {
        var entry = load().find { it.sessionId == sessionId }
            ?: return@withLock failure("playback_unavailable", "This session has no v2 playback authority. Start playback again.")
        val captured = scope(entry) ?: return@withLock failure("identity_changed", "Playback authority changed.")
        if (entry.terminal || entry.stop != null) return@withLock failure("playback_stopping", "Playback is stopping.")
        if (request.playbackAttemptId != entry.attemptId) return@withLock failure("identity_changed", "The playback attempt changed.")
        val body = request.v2Body(entry.installationId)
        val previous = entry.replans.find { it.body["replan_request_id"] == body["replan_request_id"] }
        if (previous != null) {
            if (previous.body != body) return@withLock failure("replan_conflict", "A replan identity cannot be reused with a different request.")
            previous.response?.let {
                if (previous !== entry.replans.last()) return@withLock failure("stale_replan", "A newer replan superseded this decision.")
                return@withLock ApiResult.Success(decodePlaybackDecisionV2(it))
            }
            previous.rejectedCode?.let { return@withLock ApiResult.Error(it, "replan_rejected", previous.rejectedMessage ?: "Replan is unavailable.") }
            return@withLock failure("replan_pending", "The previous replan outcome is uncertain. Stop this session before starting again.")
        }
        if (entry.replans.any { it.response == null && it.rejectedCode == null })
            return@withLock failure("replan_pending", "The previous replan outcome is uncertain. Stop this session before starting again.")
        // Settled history fences old request IDs; it is not a lifetime reanchor budget.
        val intent = PlaybackReplanIntent(body)
        entry = entry.copy(replans = entry.replans + intent)
        save(entry) // A crash or lost response leaves this exact intent pending; never rebase it.
        if (scope(entry) == null) return@withLock failure("identity_changed", "Playback authority changed.")
        when (val result = api.replan(captured, sessionId, body)) {
            is ApiResult.Success -> {
                val decision = decodePlaybackDecisionV2(result.data)
                if (decision.sessionId != sessionId || (decision.playbackPlan?.sessionId?.let { it != sessionId } == true))
                    return@withLock failure("invalid_decision", "The replacement plan belongs to another session.")
                val expectedTimeline = boundTimeline(entry)
                if (expectedTimeline != null && (decision.progressTimeline != expectedTimeline ||
                    decision.playbackPlan?.effectiveMediaFileId?.toString() != expectedTimeline.fileId))
                    return@withLock failure("invalid_timeline", "A replan cannot change the captured audiobook part.")
                save(entry.copy(replans = entry.replans.dropLast(1) + intent.copy(response = result.data)))
                if (scope(entry) == null) return@withLock failure("identity_changed", "Playback authority changed.")
                ApiResult.Success(decision)
            }
            is ApiResult.Error -> {
                // A documented unsupported operation is pre-admission. Other errors remain uncertain.
                if (result.code == 501) save(entry.copy(replans = entry.replans.dropLast(1) +
                    intent.copy(rejectedCode = result.code, rejectedMessage = result.message)))
                result
            }
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun routeEvent(request: PlaybackRouteEventV3): ApiResult<Unit> = mutex.withLock {
        val entry = load().find { it.attemptId == request.playbackAttemptId }
            ?: return@withLock failure("playback_unavailable", "Route telemetry has no v2 playback authority.")
        if (request.sessionId != null && request.sessionId != entry.sessionId)
            return@withLock failure("identity_changed", "The route event belongs to another session.")
        val captured = scope(entry) ?: return@withLock failure("identity_changed", "Playback authority changed.")
        if (entry.routeEvents.size >= 128) return@withLock failure("telemetry_pending", "Pending route telemetry is full.")
        val eventId = newId()
        val body = request.v2Body(entry.installationId, eventId)
        val saved = entry.copy(routeEvents = entry.routeEvents + body)
        save(saved)
        if (scope(saved) == null) return@withLock failure("identity_changed", "Playback authority changed.")
        when (val result = api.routeEvent(captured, body)) {
            is ApiResult.Success -> {
                if (result.data.eventId != eventId || result.data.outcome != "accepted")
                    return@withLock failure("invalid_receipt", "The route event receipt did not match the request.")
                save(saved.copy(routeEvents = entry.routeEvents))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                if (result.code == 429) save(saved.copy(routeEvents = entry.routeEvents)) // Contract says drop.
                result
            }
            is ApiResult.NetworkError -> result // Retained, never automatically replayed with new authority.
        }
    }

    suspend fun progress(sessionId: String, position: Double, paused: Boolean): ApiResult<Unit>? = mutex.withLock {
        var entry = load().find { it.sessionId == sessionId } ?: return@withLock null
        val captured = scope(entry) ?: return@withLock failure("identity_changed", "Playback authority changed.")
        if (entry.terminal || entry.stop != null) return@withLock failure("playback_stopping", "Playback is stopping.")
        if (!position.isFinite() || position < 0) return@withLock failure("invalid_position", "Invalid playback position.")
        // A lost reply is retried verbatim before allocating the next logical sample.
        if (entry.progress != null) {
            val retry = sendProgress(entry, captured)
            if (retry !is ApiResult.Success) return@withLock retry
            entry = load().first { it.attemptId == entry.attemptId }
        }
        if (entry.sequence == Long.MAX_VALUE) return@withLock failure("sequence_exhausted", "Playback sample sequence exhausted.")
        val timeline = boundTimeline(entry)
        if (timeline != null && position !in 0.0..timeline.partDurationSeconds)
            return@withLock failure("invalid_position", "Progress must be within the captured part.")
        val sample = PlaybackProgressV2(entry.installationId, entry.sequence + 1, position, paused, timeline?.timelineId)
        entry = entry.copy(sequence = sample.sequence, progress = sample)
        save(entry)
        sendProgress(entry, captured)
    }
    private suspend fun sendProgress(entry: PlaybackJournalEntry, captured: AuthScopeSnapshot): ApiResult<Unit> {
        if (scope(entry) == null) return failure("identity_changed", "Playback authority changed.")
        return when (val result = api.progress(captured, requireNotNull(entry.sessionId), requireNotNull(entry.progress))) {
            is ApiResult.Success -> {
                val accepted = result.data.accepted
                val sent = entry.progress
                if (result.data.outcome !in setOf("applied", "replayed", "stale_sample") || accepted == null ||
                    accepted.sequence < sent.sequence ||
                    (accepted.sequence == sent.sequence && (accepted.position != sent.position || accepted.isPaused != sent.isPaused)))
                    return failure("invalid_progress_receipt", "Playback returned an inconsistent progress receipt.")
                val timeline = boundTimeline(entry)
                if ((timeline != null && !timeline.accepts(accepted)) || (timeline == null && (accepted.timelineId != null || accepted.itemPosition != null)))
                    return failure("invalid_progress_receipt", "Progress did not match the captured timeline clocks.")
                save(entry.copy(progress = null, sequence = maxOf(entry.sequence, accepted.sequence),
                    acceptedBoundSample = if (timeline != null) accepted else entry.acceptedBoundSample))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    suspend fun stop(sessionId: String): ApiResult<Unit>? = mutex.withLock {
        val entry = load().find { it.sessionId == sessionId } ?: return@withLock null
        stopEntry(entry)
    }
    private suspend fun stopEntry(original: PlaybackJournalEntry): ApiResult<Unit> {
        if (original.terminal) return ApiResult.Success(Unit)
        var entry = original
        if (entry.stop == null) {
            val sample = entry.progress
            entry = entry.copy(stop = PlaybackStopV2(entry.installationId, newId(), sample?.sequence,
                sample?.position, sample?.isPaused, boundTimeline(entry)?.timelineId))
            save(entry)
        }
        val captured = scope(entry) ?: return failure("identity_changed", "Playback authority changed; stop remains pending.")
        repeat(3) { attempt ->
            if (scope(entry) == null) return failure("identity_changed", "Playback authority changed; stop remains pending.")
            when (val result = api.stop(captured, requireNotNull(entry.sessionId), requireNotNull(entry.stop))) {
                is ApiResult.Success -> if (result.data.terminal) {
                    val timeline = boundTimeline(entry)
                    val accepted = result.data.receipt.accepted
                    if (timeline != null && ((entry.stop?.sequence != null && accepted == null) ||
                        (accepted != null && (!timeline.accepts(accepted) || accepted.sequence < (entry.stop?.sequence ?: entry.acceptedBoundSample?.sequence ?: 0) ||
                            (accepted.sequence == entry.stop?.sequence && (accepted.position != entry.stop.position || accepted.isPaused != entry.stop.isPaused))))))
                        return failure("invalid_stop_receipt", "Stop did not confirm the captured timeline sample.")
                    save(entry.copy(terminal = true, progress = null,
                        acceptedBoundSample = if (timeline != null) accepted ?: entry.acceptedBoundSample else entry.acceptedBoundSample))
                    return ApiResult.Success(Unit)
                }
                is ApiResult.Error -> if (result.code != 503) return result
                is ApiResult.NetworkError -> Unit
            }
            if (attempt < 2) delay(250L * (attempt + 1))
        }
        return failure("stop_pending", "Playback stop is pending. Retry from playback recovery.")
    }

    suspend fun pendingForCurrentViewer(): Int = mutex.withLock {
        val live = authorities.snapshotDurableLoginAuthority() ?: return@withLock 0
        load().count { it.needsRecovery() &&
            it.loginId == live.loginId && it.serverId == live.scope.serverId &&
            it.origin == live.scope.serverUrl && it.profileId == live.scope.profileId }
    }

    /** Explicit recovery revalidates installation, canonical account, saved login and profile. Never autoplay. */
    suspend fun recover(): ApiResult<Unit> = mutex.withLock {
        val live = authorities.snapshotDurableLoginAuthority()
            ?: return@withLock failure("identity_unavailable", "Sign in to recover playback.")
        val capability = when (val result = api.capabilities(live.scope)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return@withLock result
            is ApiResult.NetworkError -> return@withLock result
        }
        val account = when (val result = api.account(live.scope)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return@withLock result
            is ApiResult.NetworkError -> return@withLock result
        }
        if (live != authorities.snapshotDurableLoginAuthority()) return@withLock failure("identity_changed", "The active viewer changed.")
        for (entry in load().filter { it.needsRecovery() }) {
            if (entry.loginId != live.loginId || entry.serverId != live.scope.serverId || entry.origin != live.scope.serverUrl ||
                entry.profileId != live.scope.profileId || entry.accountId != account.id || entry.installationId != capability.installationId) continue
            // Existing process attempts remain fenced after any identity transition.
            val oldScope = scopes[entry.attemptId]
            if (oldScope != null && !oldScope.isSameIdentityAs(live.scope)) continue
            scopes[entry.attemptId] = live.scope
            if (entry.sessionId == null) {
                val startResult = sendStart(entry, live.scope, adoptForPlayer = false)
                if (startResult !is ApiResult.Success) return@withLock when (startResult) {
                    is ApiResult.Error -> startResult
                    is ApiResult.NetworkError -> startResult
                    else -> error("Unreachable")
                }
            }
            val result = stopEntry(load().first { it.attemptId == entry.attemptId })
            if (result !is ApiResult.Success) return@withLock result
        }
        ApiResult.Success(Unit)
    }
}

/** Wire identities remain strings. Only the existing renderer boundary accepts representable numeric IDs. */
internal fun decodePlaybackDecisionV2(body: JsonObject): PlaybackDecisionResponseV3 {
    fun rendererId(value: JsonElement): JsonPrimitive {
        val primitive = value.jsonPrimitive
        require(primitive.isString) { "Playback v2 file identity must be a string" }
        val id = primitive.content.toIntOrNull()
        require(id != null && id > 0 && id.toString() == primitive.content) { "Unsupported renderer file identity" }
        return JsonPrimitive(id)
    }
    val plan = body["playback_plan"]?.jsonObject ?: return SiloJson.decodeFromJsonElement(body)
    fun validateUrl(element: JsonElement?) {
        val url = (element as? JsonPrimitive)?.contentOrNull ?: return
        if (url.isEmpty()) return
        require(!url.startsWith("/api/v1/") && !url.contains("/api/v1/")) { "V2 decision contains a legacy delivery URL" }
        require(url.startsWith("/api/v2/") || url.startsWith("https://") || url.startsWith("http://")) {
            "V2 decision omitted the delivery URL mount"
        }
    }
    plan["stream"]?.jsonObject?.get("url")?.let(::validateUrl)
    plan["subtitle"]?.jsonObject?.let { subtitle ->
        subtitle["artifact"]?.takeIf { it is JsonObject }?.jsonObject?.get("url")?.let(::validateUrl)
        (subtitle["inventory"] as? JsonArray)?.forEach { row -> validateUrl(row.jsonObject["url"]) }
    }
    val fields = plan.toMutableMap()
    for (key in listOf("requested_media_file_id", "effective_media_file_id")) fields[key]?.let { fields[key] = rendererId(it) }
    fields["source"]?.jsonObject?.let { source ->
        fields["source"] = JsonObject(source.toMutableMap().apply { this["media_file_id"]?.let { this["media_file_id"] = rendererId(it) } })
    }
    return SiloJson.decodeFromJsonElement<PlaybackDecisionResponseV3>(JsonObject(body + ("playback_plan" to JsonObject(fields))))
        .also { require(it.playbackPlan != null) { "Invalid playback v2 plan" } }
}
