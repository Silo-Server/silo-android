package org.siloserver.silo.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class IdentityTransitionPhase { WILL_CHANGE, DID_CHANGE }

enum class IdentityTransitionKind {
    SIGN_IN,
    ACCOUNT_REPLACE,
    SIGN_OUT,
    SERVER_SWITCH,
    SERVER_REMOVE,
    PROFILE_SWITCH,
    TEMPORARY_SCOPE_BEGIN,
    TEMPORARY_SCOPE_END,
}

data class IdentityTransition(
    val phase: IdentityTransitionPhase,
    val kind: IdentityTransitionKind,
    val generation: Long,
    val targetServerId: String? = null,
    val affectsCurrentIdentity: Boolean = true,
    val purgesPersistentIdentity: Boolean = true,
)

data class IdentityTransitionTarget(
    val serverId: String? = null,
    val affectsCurrentIdentity: Boolean = true,
    val purgesPersistentIdentity: Boolean = true,
)

interface IdentityTransitionBarrier {
    val transitions: SharedFlow<IdentityTransition>
    val generation: StateFlow<Long>

    /** Installs the inline privacy gate, which must complete before identity mutation. */
    fun installGate(listener: suspend (IdentityTransition) -> Unit)

    /**
     * Runs [block] only while [expectedGeneration] is still current, serializing it
     * with identity mutation. A null result means the generation already changed.
     *
     * Keep the guarded block as narrow as possible: callers may suspend an account
     * transition until it completes.
     */
    suspend fun <T : Any> withCurrentGeneration(
        expectedGeneration: Long,
        block: suspend () -> T,
    ): T?

    suspend fun <T> changing(
        kind: IdentityTransitionKind,
        target: suspend () -> IdentityTransitionTarget = { IdentityTransitionTarget() },
        block: suspend () -> T,
    ): T
}

class DefaultIdentityTransitionBarrier : IdentityTransitionBarrier {
    private val mutationMutex = Mutex()
    private val _transitions = MutableSharedFlow<IdentityTransition>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _generation = MutableStateFlow(0L)

    @Volatile
    private var gates: List<suspend (IdentityTransition) -> Unit> = emptyList()

    @Volatile
    private var testObserver: ((IdentityTransition) -> Unit)? = null

    override val transitions: SharedFlow<IdentityTransition> = _transitions.asSharedFlow()
    override val generation: StateFlow<Long> = _generation.asStateFlow()

    override fun installGate(listener: suspend (IdentityTransition) -> Unit) {
        // Gates are installed during sequential application startup. Publish an
        // immutable snapshot so mutations can iterate safely without holding a
        // lock across suspending privacy cleanup.
        gates = gates + listener
    }

    override suspend fun <T : Any> withCurrentGeneration(
        expectedGeneration: Long,
        block: suspend () -> T,
    ): T? = mutationMutex.withLock {
        if (_generation.value != expectedGeneration) null else block()
    }

    override suspend fun <T> changing(
        kind: IdentityTransitionKind,
        target: suspend () -> IdentityTransitionTarget,
        block: suspend () -> T,
    ): T =
        mutationMutex.withLock {
            val resolvedTarget = target()
            val nextGeneration = _generation.value + 1
            val willChange = IdentityTransition(
                phase = IdentityTransitionPhase.WILL_CHANGE,
                kind = kind,
                generation = nextGeneration,
                targetServerId = resolvedTarget.serverId,
                affectsCurrentIdentity = resolvedTarget.affectsCurrentIdentity,
                purgesPersistentIdentity = resolvedTarget.purgesPersistentIdentity,
            )
            // This callback is the privacy boundary. It runs inline before new identity is visible.
            gates.forEach { gate -> gate(willChange) }
            _generation.value = nextGeneration
            publish(willChange)
            try {
                block()
            } finally {
                publish(
                    IdentityTransition(
                        phase = IdentityTransitionPhase.DID_CHANGE,
                        kind = kind,
                        generation = nextGeneration,
                        targetServerId = resolvedTarget.serverId,
                        affectsCurrentIdentity = resolvedTarget.affectsCurrentIdentity,
                        purgesPersistentIdentity = resolvedTarget.purgesPersistentIdentity,
                    ),
                )
            }
        }

    internal fun installObserverForTests(observer: (IdentityTransition) -> Unit) {
        testObserver = observer
    }

    private fun publish(transition: IdentityTransition) {
        _transitions.tryEmit(transition)
        testObserver?.invoke(transition)
    }
}
