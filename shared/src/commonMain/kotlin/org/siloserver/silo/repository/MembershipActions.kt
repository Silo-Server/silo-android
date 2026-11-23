package org.siloserver.silo.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.port.MembershipPort

/** Shared per-field intent ownership. No transport bypasses the durable port. */
class MembershipActions(
    private val port: MembershipPort?,
    private val identity: IdentityTransitionBarrier,
    /** Measures [ATTEMPT_BUDGET]; tests pass their scheduler's clock and [timer]. */
    private val clock: TimeSource = TimeSource.Monotonic,
    private val timer: CoroutineContext = Dispatchers.Default,
) {
    data class Key(val itemId: String, val kind: MembershipPort.Kind)
    data class Intent(val key: Key, val present: Boolean, val generation: Long, val revision: Long)
    /** [restored]: the latest attempt was the startup retry of a restored command, and no tap has taken it over. */
    data class Action(val intent: Intent, val command: MembershipPort.Command? = null,
        val completion: MembershipPort.Completion? = null, val busy: Boolean = false,
        val baseline: Intent? = null, val restored: Boolean = false) {
        val confirmed: Boolean get() = completion?.mayPublish == true && completion.disposition in setOf(
            MembershipPort.Disposition.ACKNOWLEDGED, MembershipPort.Disposition.RECONCILED)
    }
    private val mutableActions = MutableStateFlow<Map<Key, Action>>(emptyMap())
    val actions = mutableActions.asStateFlow()
    val generation get() = identity.generation
    private val mutableLegacyNotice = MutableStateFlow(false)
    val legacyNotice = mutableLegacyNotice.asStateFlow()
    private val mutableUnconfirmedAttempts = MutableSharedFlow<Intent>(extraBufferCapacity = 16)

    /**
     * The viewer's own attempts ([perform]) still unconfirmed after their automatic
     * retries, including one whose caller was cancelled. A command restored by
     * [restorePending] never appears by itself, so a notice raised from this flow
     * answers something the viewer just did. NOT_CLAIMED is left out: another sender
     * owns the command and its result arrives through [restorePending].
     */
    val unconfirmedAttempts: SharedFlow<Intent> = mutableUnconfirmedAttempts.asSharedFlow()

    /** Restored commands whose one automatic retry hasn't started yet; a cancelled restore resumes them. */
    private val unretriedRestores = MutableStateFlow<Set<Long>>(emptySet())

    init {
        identity.installGate { mutableActions.value = emptyMap(); unretriedRestores.value = emptySet(); mutableLegacyNotice.value = false }
    }

    fun begin(itemId: String, kind: MembershipPort.Kind, present: Boolean): Intent {
        val key = Key(itemId, kind)
        val previous = mutableActions.value[key]
        // Repeating an unresolved choice retries it, which reads before resending. A choice
        // already retrying (a startup restore included) is joined, never recorded twice.
        if (previous != null && current(previous.intent) && !previous.confirmed && previous.intent.present == present &&
            (previous.busy || previous.completion?.disposition != MembershipPort.Disposition.PAUSED)) {
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
        val started = clock.markNow()
        var prior = mutableActions.value[intent.key] ?: return
        if (prior.busy) {
            // The viewer repeated a choice that is already being sent: wait for that attempt, so the
            // caller sees its outcome. An earlier tap's attempt reports its own failure. A restored
            // command's startup retry reports nothing, so the first tap waiting on it takes over when
            // it ends unconfirmed, even if that tap's caller is cancelled meanwhile.
            withContext(NonCancellable) {
                mutableActions.first { states -> !current(intent) || states[intent.key]?.busy != true }
            }
            prior = mutableActions.value[intent.key]?.takeIf { current(intent) && it.restored && !it.busy } ?: return
        }
        if (prior.confirmed) return
        update(intent) { it.copy(busy = true, restored = false) }
        val resending = prior.command != null
        var command = prior.command
        // This attempt retries the command itself, so a later restore pass must not retry it again.
        command?.let { owned -> unretriedRestores.update { it - owned.id } }
        var stale = false
        try {
            val activePort = checkNotNull(port) { "Membership storage is unavailable" }
            val authority = checkNotNull(activePort.captureAuthority()) { "Sign in to change membership" }
            if (!current(intent) || authority.scope.identityGeneration != intent.generation) { stale = true; return }
            val recorded = command ?: withContext(NonCancellable) {
                // Preserve the exact durable witness if cancellation arrives after Room commits.
                activePort.record(authority, intent.key.itemId, intent.key.kind, intent.present).also { recorded ->
                    command = recorded
                    update(intent) { it.copy(command = recorded) }
                }
            }
            coroutineContext.ensureActive()
            complete(intent, withinBudget(ATTEMPT_BUDGET - started.elapsedNow()) {
                if (resending) activePort.retry(recorded) else activePort.send(recorded)
            } ?: UNCONFIRMED)
        } catch (cancelled: CancellationException) {
            complete(intent, UNCONFIRMED)
            throw cancelled
        } catch (_: Throwable) {
            complete(intent, UNCONFIRMED)
        } finally {
            // Finish the change even if the screen that started it closes. A stale attempt,
            // for an identity that is already changing, neither retries nor reports.
            withContext(NonCancellable) {
                if (!stale) command?.let { recover(intent, it, started) }
                update(intent) { it.copy(busy = false) }
                if (!stale) reportIfUnconfirmed(intent)
            }
        }
    }

    /**
     * Retries an unconfirmed command after each delay until it is confirmed or settled otherwise.
     * The whole attempt stays within [ATTEMPT_BUDGET], so a server that never answers can't hold
     * the viewer's notice back for minutes of request timeouts.
     */
    private suspend fun recover(intent: Intent, command: MembershipPort.Command, started: TimeMark) {
        val activePort = port ?: return
        for (wait in RETRY_DELAYS) {
            if (!needsRetry(intent)) return
            val remaining = ATTEMPT_BUDGET - started.elapsedNow() - wait
            if (remaining <= Duration.ZERO) return
            delay(wait)
            if (!current(intent)) return
            complete(intent, try { withinBudget(remaining) { activePort.retry(command) } ?: UNCONFIRMED } catch (_: Exception) { UNCONFIRMED })
        }
    }

    /**
     * Runs [block] on the caller's dispatcher, or gives up after [budget], measured on [timer]. Under
     * a test scheduler, a timeout on the caller's dispatcher would expire at once while [block] waits
     * on real I/O (Room, HTTP). The deadline isn't a child, so finishing never waits on [timer].
     */
    private suspend fun <T> withinBudget(budget: Duration, block: suspend () -> T): T? {
        if (budget <= Duration.ZERO) return null
        return coroutineScope {
            val work = async { block() }
            val deadline = CoroutineScope(timer).launch { delay(budget); work.cancel() }
            try {
                work.await()
            } catch (cancelled: CancellationException) {
                coroutineContext.ensureActive() // The caller was cancelled, not the budget.
                null
            } finally { deadline.cancel() }
        }
    }

    private fun reportIfUnconfirmed(intent: Intent) {
        val action = mutableActions.value[intent.key]
        if (current(intent) && action != null && !action.busy && !action.confirmed &&
            action.completion?.disposition != MembershipPort.Disposition.NOT_CLAIMED) {
            mutableUnconfirmedAttempts.tryEmit(intent)
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
        val unresolved = mutableListOf<MembershipPort.Command>()
        for (command in pending) {
            val key = Key(command.itemId, command.kind)
            val projection = activePort.projection(authority, command.itemId, command.kind) ?: continue
            if (projection.commandId != command.id || generation.value != authority.scope.identityGeneration) continue
            val intent = Intent(key, command.present, generation.value, kotlin.random.Random.nextLong())
            mutableActions.update { states ->
                if (states.containsKey(key)) {
                    val existing = states.getValue(key)
                    if (existing.command == null && !existing.busy && existing.completion != null &&
                        existing.intent.present == command.present && existing.intent.generation == authority.scope.identityGeneration) {
                        states + (key to existing.copy(command = command))
                    } else states
                } else states + (key to Action(intent, command,
                    MembershipPort.Completion(projection.disposition ?: MembershipPort.Disposition.NEEDS_RECONCILIATION, false)))
            }
            // READY commands belong to the sync worker; earlier unconfirmed ones get one retry here.
            if (projection.disposition == null) continue
            if (mutableActions.value[key]?.intent == intent) unretriedRestores.update { it + command.id }
            // A command an earlier, cancelled pass restored but never retried gets its retry now.
            if (command.id in unretriedRestores.value) unresolved += command
        }
        for (command in unresolved) {
            // A restored witness is busy only while its retry runs. Until then, the viewer repeating
            // the choice takes it over through perform, which removes it from unretriedRestores.
            val witness = mutableActions.value[Key(command.itemId, command.kind)]?.takeIf {
                it.command?.id == command.id && current(it.intent) && !it.confirmed && !it.busy
            } ?: continue
            if (command.id !in unretriedRestores.value) continue
            val intent = witness.intent
            unretriedRestores.update { it - command.id }
            update(intent) { it.copy(busy = true, restored = true) }
            try {
                complete(intent, withinBudget(ATTEMPT_BUDGET) { activePort.restore(command) } ?: UNCONFIRMED)
            } catch (cancelled: CancellationException) {
                // The retry may not have run; a later pass in this process gets it again.
                unretriedRestores.update { it + command.id }
                throw cancelled
            } catch (_: Exception) {
                complete(intent, UNCONFIRMED)
            } finally { update(intent) { it.copy(busy = false) } }
        }
        val retained = activePort.hasLegacyQuarantine()
        if (generation.value == authority.scope.identityGeneration) mutableLegacyNotice.value = retained
    }

    fun dismissLegacyNotice() { mutableLegacyNotice.value = false }

    private companion object {
        val UNCONFIRMED = MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false)
        val RETRY_DELAYS = listOf(2.seconds, 5.seconds, 15.seconds)
        /** From the viewer's choice to its last automatic retry; the notice opens by then. */
        val ATTEMPT_BUDGET = 40.seconds
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
