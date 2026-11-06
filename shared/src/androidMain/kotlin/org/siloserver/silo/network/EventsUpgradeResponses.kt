package org.siloserver.silo.network

import okhttp3.OkHttpClient
import org.siloserver.silo.network.apiv2.EVENTS_PROTOCOL
import org.siloserver.silo.network.apiv2.EventsUpgradeFailure

/**
 * Ktor's OkHttp listener discards non-401 failed-handshake responses before
 * HttpStatement.execute runs. Preserve only events refusal metadata while the
 * response is still owned here; never retain its request, body or credentials.
 */
internal fun OkHttpClient.Builder.eventsUpgradeResponses(): OkHttpClient.Builder = addInterceptor { chain ->
    val request = chain.request()
    val response = chain.proceed(request)
    if (request.url.encodedPath == "/api/v2/events/ws" &&
        request.header("Sec-WebSocket-Protocol")?.split(',')?.any { it.trim() == EVENTS_PROTOCOL } == true &&
        response.code != 101
    ) {
        response.use {
            throw EventsUpgradeFailure(response.code, genericEventsRetryAfter(response.header("Retry-After")))
        }
    }
    response
}
