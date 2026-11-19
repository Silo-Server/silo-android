package org.siloserver.silo.common.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The waits before each retry after an outage or a lost response; the last
 * repeats. An outage never ends playback by itself, so callers keep retrying
 * until their work is cancelled.
 */
private val NETWORK_RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

/**
 * Waits before retry [attempt] (from 0): for [network] to report a connected
 * network, at most the longest backoff (a provider that cannot tell stops
 * waiting at once), then for the attempt's backoff.
 */
suspend fun awaitNetworkRetry(network: Flow<PlaybackNetworkSnapshot>, attempt: Int) {
    withTimeoutOrNull(NETWORK_RETRY_BACKOFF_MS.last()) { network.firstOrNull { it.connected } }
    delay(NETWORK_RETRY_BACKOFF_MS[minOf(attempt, NETWORK_RETRY_BACKOFF_MS.lastIndex)])
}
