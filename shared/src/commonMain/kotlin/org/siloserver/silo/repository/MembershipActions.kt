package org.siloserver.silo.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.port.MembershipPort

/** Shared per-field intent ownership. No transport bypasses the durable port. */
class MembershipActions(private val port: MembershipPort?, private val identity: IdentityTransitionBarrier) {
    data class Key(val itemId: String, val kind: MembershipPort.Kind)
    data class Intent(val key: Key, val present: Boolean, val generation: Long, val revision: Long)
    data class Action(val intent: Intent, val command: MembershipPort.Command? = null,
        val completion: MembershipPort.Completion? = null, val busy: Boolean = false,
        val baseline: Intent? = null) {
        val confirmed: Boolean get() = completion?.mayPublish == true && completion.disposition in setOf(
            MembershipPort.Disposition.ACKNOWLEDGED, MembershipPort.Disposition.RECONCILED)
    }
    private val mutableActions = MutableStateFlow<Map<Key, Action>>(emptyMap())
    val actions = mutableActions.asStateFlow()
    val generation get() = identity.generation
    private val mutableFailures = MutableSharedFlow<Key>(extraBufferCapacity = 8)
    /** Changes still unconfirmed after automatic retries; the UI briefly reports them. */
    val failures = mutableFailures.asSharedFlow()
    private val mutableLegacyNotice = MutableStateFlow(false)
    val legacyNotice = mutableLegacyNotice.asStateFlow()

    init {
        identity.installGate { mutableActions.value = emptyMap(); mutableLegacyNotice.value = false }
    }

    fun begin(itemId: String, kind: MembershipPort.Kind, present: Boolean): Intent {
        val key = Key(itemId, kind)
        val previous = mutableActions.value[key]
        // Repeating an unresolved choice retries it, which reads before resending.
        if (previous != null && current(previous.intent) && !previous.confirmed && previous.completion?.disposition != MembershipPort.Disposition.PAUSED && previous.intent.present == present) {
            return previous.intent
        }
        val intent = Intent(key, present, generation.value, kotlin.random.Random.nextLong())
        mutableActions.update { it + (key to Action(intent, baseline = it[key]?.baseline)) }
        return intent
    }

    fun current(intent: Intent): Boolean = generation.value == intent.generation &&
        mutableActions.value[intent.key]?.intent == intent

    fun readWitnesses(): Set<Intent> = mutableActions.value.values.mapNotNull { it.baseline }.toSet()

    fun confirmed(intent: Intent): Boolean = current(intent) && mutableActions.value[intent.key]?.confirmed == true

    suspend fun perform(intent: Intent) {
        if (!current(intent)) return
        val prior = mutableActions.value[intent.key] ?: return
        if (prior.busy || prior.confirmed) return
        update(intent) { it.copy(busy = true) }
        var command = prior.command
        try {
            val activePort = checkNotNull(port) { "Membership storage is unavailable" }
            val authority = checkNotNull(activePort.captureAuthority()) { "Sign in to change membership" }
            if (!current(intent) || authority.scope.identityGeneration != intent.generation) return
            val recorded = command ?: withContext(NonCancellable) {
                // Preserve the exact durable witness if cancellation arrives after Room commits.
                activePort.record(authority, intent.key.itemId, intent.key.kind, intent.present).also { recorded ->
                    command = recorded
                    update(intent) { it.copy(command = recorded) }
                }
            }
            coroutineContext.ensureActive()
            complete(intent, if (prior.command == null) activePort.send(recorded) else activePort.retry(recorded))
        } catch (cancelled: CancellationException) {
            complete(intent, UNCONFIRMED)
            throw cancelled
        } catch (_: Throwable) {
            complete(intent, UNCONFIRMED)
        } finally {
            // Finish the change even if the screen that started it closes.
            withContext(NonCancellable) {
                command?.let { recover(intent, it) }
                update(intent) { it.copy(busy = false) }
                if (needsRetry(intent)) mutableFailures.tryEmit(intent.key)
            }
        }
    }

    /** Retries an unconfirmed command after each delay until it is confirmed or settled otherwise. */
    private suspend fun recover(intent: Intent, command: MembershipPort.Command) {
        val activePort = port ?: return
        for (delayMs in RETRY_DELAYS_MS) {
            if (!needsRetry(intent)) return
            delay(delayMs)
            if (!current(intent)) return
            complete(intent, try { activePort.retry(command) } catch (_: Exception) { UNCONFIRMED })
        }
    }

    private fun needsRetry(intent: Intent) = current(intent) &&
        mutableActions.value[intent.key]?.completion?.disposition == MembershipPort.Disposition.NEEDS_RECONCILIATION

    private fun complete(intent: Intent, completion: MembershipPort.Completion) =
        update(intent) { it.copy(completion = completion) }

    suspend fun read(itemId: String, kind: MembershipPort.Kind): ApiResult<Boolean> {
        val activePort = port ?: return ApiResult.Error(0, "unavailable", "Membership storage is unavailable")
        val key = Key(itemId, kind)
        val witness = mutableActions.value[key]?.intent
        val baselineWitness = mutableActions.value[key]?.baseline
        val authority = activePort.captureAuthority() ?: return ApiResult.Error(0, "identity_changed", "Sign in to read membership")
        val result = activePort.read(authority, itemId, kind)
        if (generation.value != authority.scope.identityGeneration || mutableActions.value[key]?.intent != witness ||
            mutableActions.value[key]?.baseline != baselineWitness) {
            return ApiResult.Error(0, "state_changed", "Membership changed while loading")
        }
        if (result is ApiResult.Success && witness != null && baselineWitness != null) {
            // Publish newer server truth to every observer of this field (including
            // detail, card menus and episode rows), without changing pending intent.
            update(witness) { action ->
                if (action.baseline != baselineWitness) action else action.copy(baseline = baselineWitness.copy(
                    present = result.data, revision = kotlin.random.Random.nextLong()))
            }
        }
        return result
    }

    suspend fun observeChanges() {
        port?.changes?.collect { restorePending() }
    }

    /** Restore and retry a bounded set of unresolved witnesses; no old login is assigned to a command. */
    suspend fun restorePending() {
        val activePort = port ?: return
        val authority = activePort.captureAuthority() ?: return
        // Room invalidations include worker acknowledgements, so existing UI witnesses
        // can complete even when another sender won the exact claim.
        for (action in mutableActions.value.values.toList()) {
            if (!current(action.intent) || action.busy || action.confirmed) continue
            val projection = activePort.projection(authority, action.intent.key.itemId, action.intent.key.kind) ?: continue
            val command = action.command
            if (command != null && command.id == projection.commandId) {
                val disposition = projection.disposition ?: continue
                update(action.intent) { it.copy(completion = MembershipPort.Completion(disposition,
                    disposition in setOf(MembershipPort.Disposition.ACKNOWLEDGED, MembershipPort.Disposition.RECONCILED))) }
            }
        }
        val pending = activePort.pending(authority)
        if (generation.value != authority.scope.identityGeneration) return
        val unresolved = mutableListOf<Pair<Intent, MembershipPort.Command>>()
        for (command in pending) {
            val key = Key(command.itemId, command.kind)
            val projection = activePort.projection(authority, command.itemId, command.kind) ?: continue
            if (projection.commandId != command.id || generation.value != authority.scope.identityGeneration) continue
            val intent = Intent(key, command.present, generation.value, kotlin.random.Random.nextLong())
            // READY commands belong to the sync worker; earlier unconfirmed ones get one retry here.
            // The restored witness stays busy until that retry ends so perform can't run beside it.
            val retry = projection.disposition != null
            mutableActions.update { states ->
                if (states.containsKey(key)) {
                    val existing = states.getValue(key)
                    if (existing.command == null && !existing.busy && existing.completion != null &&
                        existing.intent.present == command.present && existing.intent.generation == authority.scope.identityGeneration) {
                        states + (key to existing.copy(command = command))
                    } else states
                } else states + (key to Action(intent, command,
                    MembershipPort.Completion(projection.disposition ?: MembershipPort.Disposition.NEEDS_RECONCILIATION, false), busy = retry))
            }
            if (retry && mutableActions.value[key]?.intent == intent) unresolved += intent to command
        }
        var next = 0
        try {
            while (next < unresolved.size) {
                val (intent, command) = unresolved[next++]
                if (!current(intent)) continue
                try {
                    complete(intent, activePort.retry(command))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    complete(intent, UNCONFIRMED)
                } finally { update(intent) { it.copy(busy = false) } }
            }
        } finally {
            // Release restored witnesses a cancelled pass never reached.
            for (index in next until unresolved.size) update(unresolved[index].first) { it.copy(busy = false) }
        }
        val retained = activePort.hasLegacyQuarantine()
        if (generation.value == authority.scope.identityGeneration) mutableLegacyNotice.value = retained
    }

    fun dismissLegacyNotice() { mutableLegacyNotice.value = false }

    private companion object {
        val UNCONFIRMED = MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false)
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L)
    }

    private fun update(intent: Intent, transform: (Action) -> Action) {
        mutableActions.update { states ->
            val action = states[intent.key]
            if (generation.value != intent.generation || action?.intent != intent) states
            else {
                val changed = transform(action)
                states + (intent.key to if (changed.confirmed && !action.confirmed) changed.copy(baseline = changed.intent) else changed)
            }
        }
    }
}
