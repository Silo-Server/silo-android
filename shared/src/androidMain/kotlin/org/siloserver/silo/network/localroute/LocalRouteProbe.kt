package org.siloserver.silo.network.localroute

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.siloserver.silo.network.HttpOrigin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.DefaultServerIdentityApi
import org.siloserver.silo.network.api.ServerIdentityV2

/**
 * The two reads the local route rests on, each on a client of its own that
 * follows no redirect: the answer has to come from the address asked.
 *
 * - [readOffer]: the connections document from the saved HTTPS address, with
 *   system trust. This is where the pin is learned, so a redirect to another
 *   address, or to plain HTTP, would let whoever answers there hand over their
 *   own pin.
 * - [confirms]: one candidate check. TLS that presents exactly the pinned key
 *   (no other certificate, publicly valid or not), then the saved server's
 *   identity at `GET /api/v2/system/identity`, sent without credentials.
 */
class LocalRouteProbe(
    client: OkHttpClient = OkHttpClient(),
    /** A LAN candidate answers in milliseconds; one that has not in this long is not the route. */
    private val probeTimeoutMs: Long = 3_000L,
) {
    private val base = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    private val savedClient = this.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun readOffer(savedUrl: String, bearer: String): LocalRouteOffer {
        val url = (savedUrl.trimEnd('/') + DefaultServerIdentityApi.CONNECTIONS_PATH).toHttpUrlOrNull()
            ?: return LocalRouteOffer.Unreadable
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearer")
            .build()
        return try {
            savedClient.newCall(request).await().use { response ->
                if (response.code != 200) return LocalRouteOffer.Unreadable
                decodeLocalRouteOffer(withContext(Dispatchers.IO) { response.body?.string().orEmpty() })
            }
        } catch (_: IOException) {
            LocalRouteOffer.Unreadable
        }
    }

    suspend fun confirms(origin: HttpOrigin, pin: String, identity: String): Boolean {
        val trust = PinOnlyTrustManager(pin)
        val client = base.newBuilder()
            .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory, trust)
            .hostnameVerifier { _, session ->
                LocalRouteTls.spkiPin(runCatching { session.peerCertificates.firstOrNull() }.getOrNull()) == pin
            }
            .connectionPool(probeConnections)
            .connectTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(probeTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
            .build()
        val url = (LocalRoute.format(origin) + DefaultServerIdentityApi.IDENTITY_PATH).toHttpUrlOrNull() ?: return false
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).await().use { response ->
                if (response.code != 200) return false
                val body = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
                val served = runCatching { SiloJson.decodeFromString(ServerIdentityV2.serializer(), body).serverId.trim() }
                    .getOrNull()
                served == identity
            }
        } catch (_: IOException) {
            false
        }
    }

    /** The private IPv4 addresses [host] resolves to on the current network. */
    suspend fun privateIPv4Addresses(host: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter(LocalRoute::isPrivateIPv4)
            .distinct()
    }

    private companion object {
        /** Probe connections are never kept for later requests. */
        val probeConnections = ConnectionPool(0, 1, TimeUnit.SECONDS)
    }
}

/** Accepts exactly the leaf key [pin] names. */
private class PinOnlyTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (LocalRouteTls.spkiPin(chain.firstOrNull()) != pin) {
            throw CertificateException("The candidate presented a key other than the pin")
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("No client certificates")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
}
