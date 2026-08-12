package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.sync.Mutex

/**
 * Orders diagnostics transport against user-visible privacy revocations.
 *
 * A transport that wins this lease may finish before Turn Off, Delete, or a
 * destination/identity change returns. A revocation that wins first commits
 * its policy and erasure work before a later transport can revalidate, so no
 * new request can begin after the revocation has completed.
 */
class DiagnosticsPrivacyBarrier {
    private val mutex = Mutex()

    suspend fun <T> withTransport(block: suspend () -> T): T = withLease(block)

    suspend fun <T> withRevocation(block: suspend () -> T): T = withLease(block)

    private suspend fun <T> withLease(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
