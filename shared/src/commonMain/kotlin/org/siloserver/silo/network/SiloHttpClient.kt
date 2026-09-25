package org.siloserver.silo.network

import io.ktor.client.*

/**
 * Factory for creating the Ktor HttpClient.
 * Implementation provided by Agent 2 in SiloHttpClientImpl.kt.
 *
 * [retryOnConnectionFailure] false stops the engine from silently resending a
 * request whose connection failed after it was sent. Operations that must
 * never be replayed (Watch Party start, select, promote) use such a client.
 */
expect fun createPlatformHttpClient(retryOnConnectionFailure: Boolean = true): HttpClient
