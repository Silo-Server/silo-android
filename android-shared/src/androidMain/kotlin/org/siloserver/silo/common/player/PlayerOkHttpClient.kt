package org.siloserver.silo.common.player

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Pooled OkHttp transport used by Media3 and authenticated reader requests.
 *
 * Lives as a Koin singleton so the underlying connection pool, dispatcher,
 * and HTTP/2 session survive across playback sessions. Keep it separate from
 * the app-level Ktor client — this one only serves media; the auth/refresh
 * chain on the Ktor client must not see media traffic. Media3 auth lives above
 * this transport in [RefreshingHttpDataSource].
 */
internal fun buildPlayerOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES))
        .dispatcher(Dispatcher())
        .build()
