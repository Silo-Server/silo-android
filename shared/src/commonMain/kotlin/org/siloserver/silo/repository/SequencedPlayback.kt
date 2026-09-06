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
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*

/** Atomic durable writes must complete before returning; failure prevents dispatch. No credentials. */
interface PlaybackJournalStore {
    suspend fun read(): List<PlaybackJournalEntry>
    suspend fun write(entries: List<PlaybackJournalEntry>)
}

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
    private val scopes = mutableMapOf<String, AuthScopeSnapshot>()
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending = _pending.asStateFlow()
    private val _sessions = MutableStateFlow<Set<String>>(emptySet())
    fun owns(sessionId: String): Boolean = sessionId in _sessions.value

    private suspend fun load(): List<PlaybackJournalEntry> {
        if (entries == null) { entries = store.read(); publish() }
        return requireNotNull(entries)
    }
    private fun publish() {
        _sessions.value = entries.orEmpty().mapNotNull { it.sessionId }.toSet()
        _pending.value = entries.orEmpty().filter { !it.terminal && (it.stop != null || it.attemptId !in adopted) }
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

    /** Null alone means no advertised feature: caller may use the unchanged legacy transport. */
    suspend fun start(request: PlaybackStartRequestV3): ApiResult<PlaybackDecisionResponseV3>? = mutex.withLock {
        val current = tokens.snapshotCurrentScope()
            ?: return@withLock failure("identity_unavailable", "Playback needs an authenticated profile.")
        // Probe before requiring durable credentials so feature-absent temporary playback stays legacy.
        val capabilityResult = api.capabilities(current)
        val capability = when (capabilityResult) {
            is ApiResult.Success -> capabilityResult.data
            is ApiResult.Error -> if (capabilityResult.code == 404) null else return@withLock capabilityResult
            is ApiResult.NetworkError -> return@withLock capabilityResult
        }
        if (!current.isSameIdentityAs(tokens.snapshotCurrentScope()))
            return@withLock failure("identity_changed", "The active viewer changed.")
        val live = authorities.snapshotDurableLoginAuthority()
        val unresolved = load().any { !it.terminal && it.loginId == live?.loginId &&
            it.serverId == current.serverId && it.profileId == current.profileId && (it.attemptId !in adopted || it.stop != null) }
        if (unresolved) return@withLock failure("playback_pending", "A previous playback request needs recovery before starting again.")
        if (capability == null || SEQUENCED_PROGRESS_FEATURE !in capability.features) return@withLock null
        if (!capability.allowed || capability.state != "available" || capability.installationId.isNullOrBlank() ||
            3 !in capability.protocolVersions)
            return@withLock failure("playback_unavailable", "Sequenced playback is unavailable for this profile.")
        if (live == null || !current.isSameIdentityAs(live.scope) || live.scope.credentialGenerationId != null ||
            current.profileId != request.profileId)
            return@withLock failure("identity_unavailable", "Sequenced playback needs a saved login and matching profile.")
        val account = when (val result = api.account(current)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return@withLock result
            is ApiResult.NetworkError -> return@withLock result
        }
        if (!current.isSameIdentityAs(tokens.snapshotCurrentScope()))
            return@withLock failure("identity_changed", "The active viewer changed.")
        if (load().any { it.attemptId == request.playbackAttemptId })
            return@withLock failure("attempt_exists", "This playback attempt is already recorded. Use recovery.")
        val entry = PlaybackJournalEntry(current.serverId, current.serverUrl, live.loginId, account.id,
            request.profileId, capability.installationId, request.playbackAttemptId, request.v2Body(capability.installationId))
        save(entry)
        scopes[entry.attemptId] = current
        sendStart(entry, current)
    }

    private suspend fun sendStart(entry: PlaybackJournalEntry, captured: AuthScopeSnapshot,
        adoptForPlayer: Boolean = true): ApiResult<PlaybackDecisionResponseV3> {
        if (scope(entry) == null) return failure("identity_changed", "The active viewer changed.")
        return when (val result = api.start(captured, entry.start)) {
            is ApiResult.Success -> {
                // Persist the session before decoding a renderer plan, so invalid plans can still be stopped.
                val session = result.data["session_id"]?.jsonPrimitive?.contentOrNull
                if (session.isNullOrBlank()) return failure("invalid_decision", "Playback returned no recoverable session.")
                save(entry.copy(sessionId = session))
                try {
                    val decision = decodePlaybackDecisionV2(result.data)
                    if (SEQUENCED_PROGRESS_FEATURE !in decision.serverFeatures)
                        return failure("invalid_decision", "Playback omitted the negotiated progress feature.")
                    if (scope(entry) == null) return failure("identity_changed", "The active viewer changed.")
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
        val sample = PlaybackProgressV2(entry.installationId, entry.sequence + 1, position, paused)
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
            entry = entry.copy(stop = PlaybackStopV2(entry.installationId, newId(), sample?.sequence,
                sample?.position, sample?.isPaused))
            save(entry)
        }
        val captured = scope(entry) ?: return failure("identity_changed", "Playback authority changed; stop remains pending.")
        repeat(3) { attempt ->
            if (scope(entry) == null) return failure("identity_changed", "Playback authority changed; stop remains pending.")
            when (val result = api.stop(captured, requireNotNull(entry.sessionId), requireNotNull(entry.stop))) {
                is ApiResult.Success -> if (result.data.terminal) {
                    save(entry.copy(terminal = true, progress = null))
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
        load().count { !it.terminal && (it.stop != null || it.attemptId !in adopted) &&
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
        for (entry in load().filter { !it.terminal && (it.stop != null || it.attemptId !in adopted) }) {
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
    val fields = plan.toMutableMap()
    for (key in listOf("requested_media_file_id", "effective_media_file_id")) fields[key]?.let { fields[key] = rendererId(it) }
    fields["source"]?.jsonObject?.let { source ->
        fields["source"] = JsonObject(source.toMutableMap().apply { this["media_file_id"]?.let { this["media_file_id"] = rendererId(it) } })
    }
    return SiloJson.decodeFromJsonElement<PlaybackDecisionResponseV3>(JsonObject(body + ("playback_plan" to JsonObject(fields))))
        .also { require(it.playbackPlan != null) { "Invalid playback v2 plan" } }
}
