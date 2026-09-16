package org.siloserver.silo.repository

import kotlinx.coroutines.CancellationException
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
)

/** One shared coordinator covers local, cast, candidate and orphan cleanup callers. */
class SequencedPlayback(
    private val api: PlaybackV2Api,
    private val tokens: TokenManager,
    private val authorities: DurableLoginAuthorityProvider,
    private val store: PlaybackJournalStore,
    private val newId: () -> String,
) {
    private companion object {
        /** Settled attempts retained as tombstones. Bounds the journal's size. */
        const val SETTLED_TOMBSTONE_LIMIT = 8
    }

    private val mutex = Mutex()
    private var entries: List<PlaybackJournalEntry>? = null
    private val adopted = mutableSetOf<String>()
    // Read without the mutex by ProxyAuxiliaryRequestHeaders.isCurrent on a
    // Media3 loader thread; written only under the mutex as whole replacements.
    @kotlin.concurrent.Volatile private var auxiliaryGenerations: Map<String, Long> = emptyMap()
    @kotlin.concurrent.Volatile private var liveAttempts: Set<String> = emptySet()
    private val auxiliaryHeaders = mutableMapOf<String, ProxyAuxiliaryRequestHeaders>()
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
        liveAttempts = entries.orEmpty().filter { !it.terminal && it.stop == null }.map { it.attemptId }.toSet()
        _sessions.value = entries.orEmpty().mapNotNull { it.sessionId }.toSet()
        _pending.value = entries.orEmpty().filter { it.needsRecovery() }
            .map { it.attemptId }
    }
    private suspend fun save(entry: PlaybackJournalEntry) {
        val next = compactSettled(load().filterNot { it.attemptId == entry.attemptId } + entry)
        store.write(next)
        entries = next
        if (entry.terminal || entry.stop != null) auxiliaryHeaders.remove(entry.attemptId)
        publish()
    }

    /**
     * A settled attempt keeps a tombstone, not its history.
     *
     * The whole journal is serialized on every write, and progress writes land
     * every ten seconds for as long as something is playing. Carrying each
     * finished playback's replan decisions and route telemetry forward would
     * make every one of those writes larger than the last, with no end: the
     * retained replan responses alone hold full subtitle inventories.
     *
     * The tombstone still fences attempt-id reuse and still answers [owns] for
     * its session, which the player consults immediately after a stop. Only the
     * most recent [SETTLED_TOMBSTONE_LIMIT] are kept — a replayed attempt
     * arrives while it is still the current one, not hundreds of playbacks
     * later. Nothing that still needs recovery is settled, so nothing here can
     * drop an attempt the server may still know about.
     */
    private fun compactSettled(next: List<PlaybackJournalEntry>): List<PlaybackJournalEntry> {
        if (next.count { it.terminal } <= SETTLED_TOMBSTONE_LIMIT && next.none { it.terminal && it.hasHistory() }) return next
        var settled = 0
        // Newest first: save() appends, so a later entry settled later.
        return next.asReversed().mapNotNull { entry ->
            if (!entry.terminal) return@mapNotNull entry
            settled += 1
            if (settled > SETTLED_TOMBSTONE_LIMIT) null
            else entry.copy(progress = null, stop = null, replans = emptyList(), routeEvents = emptyList())
        }.reversed()
    }

    private fun PlaybackJournalEntry.hasHistory(): Boolean =
        progress != null || stop != null || replans.isNotEmpty() || routeEvents.isNotEmpty()
    private fun failure(code: String, message: String) = ApiResult.Error(0, code, message)
    private fun authorityChanged() = failure("identity_changed", "Playback authority changed.")
    private suspend fun scope(entry: PlaybackJournalEntry): AuthScopeSnapshot? {
        val live = authorities.snapshotDurableLoginAuthority() ?: return null
        val captured = scopes[entry.attemptId] ?: return null // Restart requires explicit recovery.
        return live.scope.takeIf {
            live.loginId == entry.loginId && it.serverId == entry.serverId &&
                it.serverUrl == entry.origin && it.profileId == entry.profileId &&
                captured.isSameIdentityAs(it) && it.credentialGenerationId == null
        }
    }

    private class IdentityChanged : RuntimeException()

    /**
     * Runs [block] with a `guard` that re-checks the metadata owner (and, once an
     * entry exists, its captured scope) after every suspension the block reports.
     * A change anywhere settles the call as `identity_changed`.
     */
    private suspend inline fun <T> withStableIdentity(
        entry: PlaybackJournalEntry?, expectedOwner: AuthScopeSnapshot?, profileId: String,
        block: (guard: suspend () -> Unit) -> ApiResult<T>,
    ): ApiResult<T> {
        val guard: suspend () -> Unit = {
            if (!tokens.acceptsMetadataOwner(expectedOwner, profileId) || (entry != null && scope(entry) == null)) throw IdentityChanged()
        }
        return try { guard(); block(guard) }
        catch (_: IdentityChanged) { failure("identity_changed", "The metadata viewer changed before playback admission.") }
    }

    /** Admission requires v2; unavailable capabilities never select a legacy transport. */
    suspend fun start(request: PlaybackStartRequestV3, expectedMetadataOwner: AuthScopeSnapshot? = null): ApiResult<PlaybackDecisionResponseV3> = mutex.withLock {
        withStableIdentity(null, expectedMetadataOwner, request.profileId) { guard ->
            val current = tokens.snapshotCurrentScope()
                ?: return@withStableIdentity failure("identity_unavailable", "Playback needs an authenticated profile.")
            // Probe availability before admission; retained intents still fence new attempts.
            val capabilityResult = api.capabilities(current)
            guard()
            val capability = when (capabilityResult) {
                is ApiResult.Success -> capabilityResult.data
                is ApiResult.Error -> if (capabilityResult.code == 404) null else return@withStableIdentity capabilityResult
                is ApiResult.NetworkError -> return@withStableIdentity capabilityResult
            }
            if (!current.isSameIdentityAs(tokens.snapshotCurrentScope())) throw IdentityChanged()
            val live = authorities.snapshotDurableLoginAuthority()
            guard()
            suspend fun unresolved() = load().any { it.needsRecovery() && it.loginId == live?.loginId &&
                it.serverId == current.serverId && it.profileId == current.profileId }
            guard()
            if (unresolved()) {
                // Settle the earlier attempt first (replay an uncertain start, then stop it) so a
                // crashed or rejected session never needs a manual recovery step before playing again.
                val settled = try { recoverLocked() }
                    catch (e: CancellationException) { throw e }
                    catch (e: IdentityChanged) { throw e }
                    catch (_: Exception) { failure("playback_storage", "Playback recovery storage is unavailable.") }
                guard()
                if (unresolved()) return@withStableIdentity when (settled) {
                    is ApiResult.Success -> failure("playback_pending", "A previous playback request needs recovery before starting again.")
                    is ApiResult.Error -> settled
                    is ApiResult.NetworkError -> settled
                }
            }
            if (capability == null) return@withStableIdentity failure("server_update_required", "Update the server to use v2 playback.")
            if (SEQUENCED_PROGRESS_FEATURE !in capability.features) return@withStableIdentity failure("playback_unavailable", "V2 playback is unavailable on this server.")
            if (!capability.allowed || capability.state != PlaybackCapabilityStateV2.AVAILABLE || capability.installationId.isNullOrBlank() ||
                3 !in capability.protocolVersions)
                return@withStableIdentity failure("playback_unavailable", "Sequenced playback is unavailable for this profile.")
            if (live == null || !current.isSameIdentityAs(live.scope) || live.scope.credentialGenerationId != null ||
                current.profileId != request.profileId)
                return@withStableIdentity failure("identity_unavailable", "Sequenced playback needs a saved login and matching profile.")
            val account = when (val result = api.account(current)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> return@withStableIdentity result
                is ApiResult.NetworkError -> return@withStableIdentity result
            }
            guard()
            if (!current.isSameIdentityAs(tokens.snapshotCurrentScope())) throw IdentityChanged()
            if (load().any { it.attemptId == request.playbackAttemptId })
                return@withStableIdentity failure("attempt_exists", "This playback attempt is already recorded. Use recovery.")
            guard()
            val entry = PlaybackJournalEntry(current.serverId, current.serverUrl, live.loginId, account.id,
                request.profileId, capability.installationId, request.playbackAttemptId, request.v2Body(capability.installationId))
            save(entry)
            scopes[entry.attemptId] = current
            // A saved attempt is uncertainty, even if its owner changes before send.
            guard()
            sendStart(entry, current, expectedMetadataOwner = expectedMetadataOwner)
        }
    }

    private suspend fun sendStart(entry: PlaybackJournalEntry, captured: AuthScopeSnapshot,
        adoptForPlayer: Boolean = true, expectedMetadataOwner: AuthScopeSnapshot? = null): ApiResult<PlaybackDecisionResponseV3> =
        withStableIdentity(entry, expectedMetadataOwner, entry.profileId) { guard ->
            var sentHeaders: Map<String, String> = emptyMap()
            when (val result = api.start(captured, entry.start) { sentHeaders = it }) {
                is ApiResult.Success -> {
                    // Persist the session before decoding a renderer plan, so invalid plans can still be stopped.
                    val session = result.data["session_id"]?.jsonPrimitive?.contentOrNull
                    if (session.isNullOrBlank()) {
                        // A terminal decision without a session allocated nothing on the server. Settle the
                        // attempt so it never fences later starts, and hand the server's own reason to the
                        // caller instead of a generic decode failure.
                        val outcome = result.data["outcome"]?.jsonPrimitive?.contentOrNull
                        if (outcome == "adaptation_unavailable" && result.data["terminal"] is JsonObject) {
                            save(entry.copy(terminal = true))
                            publish()
                            return@withStableIdentity try { ApiResult.Success(decodePlaybackDecisionV2(result.data)) }
                                catch (e: IdentityChanged) { throw e } catch (e: Exception) { ApiResult.NetworkError(e) }
                        }
                        return@withStableIdentity failure("invalid_decision", "Playback returned no recoverable session.")
                    }
                    save(entry.copy(sessionId = session))
                    try {
                        // The sequenced-progress contract is negotiated on /playback/capabilities;
                        // the decision's server_features lists only protocol-v3 plan features.
                        val decision = decodePlaybackDecisionV2(result.data)
                        guard()
                        val ready = if (adoptForPlayer) withAuxiliaryAuthority(decision, entry, captured, sentHeaders) else decision
                        if (adoptForPlayer) adopted += entry.attemptId
                        publish()
                        ApiResult.Success(ready)
                    } catch (e: IdentityChanged) { throw e } catch (e: Exception) { ApiResult.NetworkError(e) }
                }
                // Preserve uncertain starts, including validation responses until pre-admission is proven.
                is ApiResult.Error -> result
                is ApiResult.NetworkError -> result
            }
        }

    private fun withAuxiliaryAuthority(
        decision: PlaybackDecisionResponseV3, entry: PlaybackJournalEntry,
        captured: AuthScopeSnapshot, sentHeaders: Map<String, String>,
    ): PlaybackDecisionResponseV3 {
        val generation = (auxiliaryGenerations[entry.attemptId] ?: 0) + 1
        auxiliaryGenerations = auxiliaryGenerations + (entry.attemptId to generation)
        auxiliaryHeaders.remove(entry.attemptId)
        val plan = decision.playbackPlan ?: return decision
        val references = (plan.subtitle.inventory.mapNotNull { it.url } + listOfNotNull(plan.subtitle.artifact?.url))
            .filter(::isProxyAuxiliaryUrl).toSet()
        if (references.isEmpty()) return decision
        val headers = capturedProxyAuxiliaryHeaders(sentHeaders)
        require(headers["X-Profile-Id"] == entry.profileId)
        val sessionId = requireNotNull(decision.sessionId)
        require(plan.sessionId == sessionId)
        val ephemeral = ProxyAuxiliaryRequestHeaders(plan.stream.url, sessionId, references, headers) {
            // Lock-free: a playback request in flight under the mutex must not stall subtitle loads.
            val live = authorities.snapshotDurableLoginAuthority()
            live?.loginId == entry.loginId && live.scope == captured &&
                auxiliaryGenerations[entry.attemptId] == generation && entry.attemptId in liveAttempts
        }
        auxiliaryHeaders[entry.attemptId] = ephemeral
        return decision.copy(playbackPlan = plan.copy(stream = plan.stream.copy(auxiliaryRequestHeaders = ephemeral)))
    }

    /** Socket authority comes only from the admitted session, never from a fresh capability probe. */
    suspend fun controlOwner(sessionId: String): Pair<AuthScopeSnapshot, String>? = mutex.withLock {
        val entry = load().find { it.sessionId == sessionId && !it.terminal && it.stop == null && it.attemptId in adopted } ?: return@withLock null
        val captured = scope(entry) ?: return@withLock null
        captured to entry.installationId
    }

    suspend fun replan(sessionId: String, request: PlaybackReplanRequestV3): ApiResult<PlaybackDecisionResponseV3> = mutex.withLock {
        var entry = load().find { it.sessionId == sessionId }
            ?: return@withLock failure("playback_unavailable", "This session has no v2 playback authority. Start playback again.")
        val captured = scope(entry) ?: return@withLock authorityChanged()
        if (entry.terminal || entry.stop != null) return@withLock failure("playback_stopping", "Playback is stopping.")
        if (request.playbackAttemptId != entry.attemptId) return@withLock failure("identity_changed", "The playback attempt changed.")
        val body = request.v2Body(entry.installationId)
        val previous = entry.replans.find { it.body["replan_request_id"] == body["replan_request_id"] }
        if (previous != null) {
            if (previous.body != body) return@withLock failure("replan_conflict", "A replan identity cannot be reused with a different request.")
            previous.response?.let {
                if (previous !== entry.replans.last()) return@withLock failure("stale_replan", "A newer replan superseded this decision.")
                val decision = decodePlaybackDecisionV2(it)
                val plan = decision.playbackPlan
                val hasProxyAuxiliary = plan?.subtitle?.let { subtitle ->
                    (subtitle.inventory.mapNotNull { row -> row.url } + listOfNotNull(subtitle.artifact?.url)).any(::isProxyAuxiliaryUrl)
                } == true
                if (hasProxyAuxiliary && plan != null) {
                    val headers = auxiliaryHeaders[entry.attemptId]
                        ?: return@withLock failure("playback_unavailable", "The retained plan has no live auxiliary request authority.")
                    return@withLock ApiResult.Success(decision.copy(playbackPlan = plan.copy(stream = plan.stream.copy(auxiliaryRequestHeaders = headers))))
                }
                return@withLock ApiResult.Success(decision)
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
        if (scope(entry) == null) return@withLock authorityChanged()
        var sentHeaders: Map<String, String> = emptyMap()
        when (val result = api.replan(captured, sessionId, body) { sentHeaders = it }) {
            is ApiResult.Success -> {
                val decision = decodePlaybackDecisionV2(result.data)
                if (decision.sessionId != sessionId || (decision.playbackPlan?.sessionId?.let { it != sessionId } == true))
                    return@withLock failure("invalid_decision", "The replacement plan belongs to another session.")
                save(entry.copy(replans = entry.replans.dropLast(1) + intent.copy(response = result.data)))
                if (scope(entry) == null) return@withLock authorityChanged()
                ApiResult.Success(withAuxiliaryAuthority(decision, entry, captured, sentHeaders))
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
        val captured = scope(entry) ?: return@withLock authorityChanged()
        if (entry.routeEvents.size >= 128) return@withLock failure("telemetry_pending", "Pending route telemetry is full.")
        val eventId = newId()
        val body = request.v2Body(entry.installationId, eventId)
        val saved = entry.copy(routeEvents = entry.routeEvents + body)
        save(saved)
        if (scope(saved) == null) return@withLock authorityChanged()
        when (val result = api.routeEvent(captured, body)) {
            is ApiResult.Success -> {
                save(saved.copy(routeEvents = entry.routeEvents))
                result
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
        val captured = scope(entry) ?: return@withLock authorityChanged()
        if (entry.terminal || entry.stop != null) return@withLock failure("playback_stopping", "Playback is stopping.")
        if (!position.isFinite() || position < 0) return@withLock failure("invalid_position", "Invalid playback position.")
        // A lost reply is retried verbatim before allocating the next logical sample.
        if (entry.progress != null) {
            val retry = sendProgress(entry, captured)
            if (retry !is ApiResult.Success) return@withLock retry
            entry = load().first { it.attemptId == entry.attemptId }
        }
        if (entry.sequence == Long.MAX_VALUE) return@withLock failure("sequence_exhausted", "Playback sample sequence exhausted.")
        val sample = PlaybackProgressV2(entry.installationId, entry.sequence + 1, position, paused)
        entry = entry.copy(sequence = sample.sequence, progress = sample)
        save(entry)
        sendProgress(entry, captured)
    }
    private suspend fun sendProgress(entry: PlaybackJournalEntry, captured: AuthScopeSnapshot): ApiResult<Unit> {
        if (scope(entry) == null) return authorityChanged()
        return when (val result = api.progress(captured, requireNotNull(entry.sessionId), requireNotNull(entry.progress))) {
            is ApiResult.Success -> {
                val accepted = result.data.accepted
                val sent = entry.progress
                if (result.data.outcome !in PlaybackMutationOutcomeV2.PROGRESS || accepted == null ||
                    accepted.sequence < sent.sequence ||
                    (accepted.sequence == sent.sequence && (accepted.position != sent.position || accepted.isPaused != sent.isPaused)))
                    return failure("invalid_progress_receipt", "Playback returned an inconsistent progress receipt.")
                save(entry.copy(progress = null, sequence = maxOf(entry.sequence, accepted.sequence)))
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
            entry = entry.copy(stop = PlaybackStopV2(entry.installationId, newId(), sample?.sequence, sample?.position, sample?.isPaused))
            save(entry)
        }
        fun stopPending() = failure("identity_changed", "Playback authority changed; stop remains pending.")
        val captured = scope(entry) ?: return stopPending()
        repeat(3) { attempt ->
            if (scope(entry) == null) return stopPending()
            when (val result = api.stop(captured, requireNotNull(entry.sessionId), requireNotNull(entry.stop))) {
                is ApiResult.Success -> {
                    save(entry.copy(terminal = true, progress = null))
                    return ApiResult.Success(Unit)
                }
                // 404: the server has already expired and forgotten the session, so there is nothing left to stop.
                is ApiResult.Error -> if (result.code == 404) {
                    save(entry.copy(terminal = true, progress = null))
                    return ApiResult.Success(Unit)
                } else if (result.code != 503) return result
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
    suspend fun recover(): ApiResult<Unit> = mutex.withLock { recoverLocked() }

    private suspend fun recoverLocked(): ApiResult<Unit> {
        val live = authorities.snapshotDurableLoginAuthority()
            ?: return failure("identity_unavailable", "Sign in to recover playback.")
        val capability = when (val result = api.capabilities(live.scope)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result
            is ApiResult.NetworkError -> return result
        }
        val account = when (val result = api.account(live.scope)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result
            is ApiResult.NetworkError -> return result
        }
        if (live != authorities.snapshotDurableLoginAuthority()) return failure("identity_changed", "The active viewer changed.")
        for (entry in load().filter { it.needsRecovery() }) {
            if (entry.loginId != live.loginId || entry.serverId != live.scope.serverId || entry.origin != live.scope.serverUrl ||
                entry.profileId != live.scope.profileId || entry.accountId != account.id || entry.installationId != capability.installationId) continue
            // Existing process attempts remain fenced after any identity transition.
            val oldScope = scopes[entry.attemptId]
            if (oldScope != null && !oldScope.isSameIdentityAs(live.scope)) continue
            scopes[entry.attemptId] = live.scope
            if (entry.sessionId == null) {
                when (val startResult = sendStart(entry, live.scope, adoptForPlayer = false)) {
                    is ApiResult.Success -> Unit
                    is ApiResult.Error -> return startResult
                    is ApiResult.NetworkError -> return startResult
                }
            }
            val result = stopEntry(load().first { it.attemptId == entry.attemptId })
            if (result !is ApiResult.Success) return result
        }
        return ApiResult.Success(Unit)
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
    fun validateSubtitleUrl(element: JsonElement?) {
        validateUrl(element)
        val raw = (element as? JsonPrimitive)?.contentOrNull ?: return
        if (isProxyAuxiliaryUrl(raw)) {
            val stream = requireNotNull(plan["stream"] as? JsonObject)
            val session = requireNotNull((body["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
            require(plan["session_id"] == JsonPrimitive(session)) { "Auxiliary plan and response sessions differ" }
            validateProxySubtitleUrl(raw, stream.getValue("url").jsonPrimitive.content, session)
        }
    }
    plan["subtitle"]?.jsonObject?.let { subtitle ->
        subtitle["artifact"]?.takeIf { it is JsonObject }?.jsonObject?.get("url")?.let(::validateSubtitleUrl)
        (subtitle["inventory"] as? JsonArray)?.forEach { row -> validateSubtitleUrl(row.jsonObject["url"]) }
    }
    val fields = plan.toMutableMap()
    for (key in listOf("requested_media_file_id", "effective_media_file_id")) fields[key]?.let { fields[key] = rendererId(it) }
    fields["source"]?.jsonObject?.let { source ->
        fields["source"] = JsonObject(source.toMutableMap().apply { this["media_file_id"]?.let { this["media_file_id"] = rendererId(it) } })
    }
    return SiloJson.decodeFromJsonElement<PlaybackDecisionResponseV3>(JsonObject(body + ("playback_plan" to JsonObject(fields))))
        .also { require(it.playbackPlan != null) { "Invalid playback v2 plan" } }
}
