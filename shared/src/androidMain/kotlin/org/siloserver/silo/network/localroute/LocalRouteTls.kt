package org.siloserver.silo.network.localroute

import android.net.http.X509TrustManagerExtensions
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import okio.ByteString.Companion.toByteString

/**
 * TLS for clients that may reach the local route. A pinned address (one the
 * controller is checking or using) is accepted only when it presents its
 * pinned key, whatever its certificate's names and dates, and its handshake
 * gets [HANDSHAKE_TIMEOUT_MS]; no other certificate, publicly valid or not, is
 * ever accepted there. Every other peer gets the platform's own trust manager
 * and OkHttp's hostname verifier, unchanged, so Network Security Config and
 * certificate transparency still apply.
 */
class LocalRouteTls(
    private val state: LocalRouteState,
    platform: X509TrustManager = platformTrustManager(),
) {
    private val trustManager: X509TrustManager = PinningTrustManager(platform, state)
    private val hostnameVerifier: HostnameVerifier = PinningHostnameVerifier(state)
    private val sslSocketFactory: SSLSocketFactory = HandshakeTimeoutSocketFactory(
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory,
        state,
    )

    /** Applies this TLS to [builder]. One instance per process keeps pooled connections shareable. */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(sslSocketFactory, trustManager).hostnameVerifier(hostnameVerifier)

    companion object {
        /** A LAN handshake takes milliseconds; one that has not finished in this long is not worth waiting for. */
        internal const val HANDSHAKE_TIMEOUT_MS = 3_000

        /**
         * Base64 SHA-256 of [certificate]'s SubjectPublicKeyInfo, the form
         * `local_route.spki_sha256` carries.
         */
        fun spkiPin(certificate: Certificate?): String? {
            val encoded = (certificate as? X509Certificate)?.publicKey?.encoded ?: return null
            return encoded.toByteString().sha256().base64()
        }

        internal fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/** Internal (public to the JVM) so `X509TrustManagerExtensions` can call its hostname-aware check. */
internal class PinningTrustManager(
    private val platform: X509TrustManager,
    private val state: LocalRouteState,
) : X509ExtendedTrustManager() {

    private val platformExtensions by lazy { X509TrustManagerExtensions(platform) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val peer = socket?.peer()
        val pin = peer?.let { state.pinFor(it.first, it.second) }
        if (peer != null && pin != null) return checkPin(chain, pin, peer)
        if (platform is X509ExtendedTrustManager) platform.checkServerTrusted(chain, authType, socket)
        else platform.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        val peer = engine?.peerHost?.let { host -> host to engine.peerPort }
        val pin = peer?.let { state.pinFor(it.first, it.second) }
        if (peer != null && pin != null) return checkPin(chain, pin, peer)
        if (platform is X509ExtendedTrustManager) platform.checkServerTrusted(chain, authType, engine)
        else platform.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        platform.checkServerTrusted(chain, authType)
    }

    /**
     * Android's hostname-aware check, which `X509TrustManagerExtensions` (and
     * OkHttp's Android certificate chain cleaner) look up by name. A chain
     * whose leaf carries a pin of [host] is already verified; any other goes
     * to the platform.
     */
    @Suppress("unused")
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> {
        val leafPin = LocalRouteTls.spkiPin(chain.firstOrNull())
        if (leafPin != null && leafPin in state.pinsForHost(host)) return chain.toList()
        return platformExtensions.checkServerTrusted(chain, authType, host)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        if (platform is X509ExtendedTrustManager) platform.checkClientTrusted(chain, authType, socket)
        else platform.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        if (platform is X509ExtendedTrustManager) platform.checkClientTrusted(chain, authType, engine)
        else platform.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        platform.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    private fun checkPin(chain: Array<X509Certificate>, pin: String, peer: Pair<String, Int>) {
        if (LocalRouteTls.spkiPin(chain.firstOrNull()) != pin) {
            state.reportKeyMismatch(peer.first, peer.second)
            throw CertificateException("The local address presented a key other than its pin")
        }
    }
}

/** The host and port a TLS socket was opened for: the URL's, not a proxy's. */
private fun Socket.peer(): Pair<String, Int>? {
    val session = (this as? SSLSocket)?.handshakeSession
    val host = session?.peerHost ?: inetAddress?.hostAddress ?: return null
    val port = session?.peerPort?.takeIf { it > 0 } ?: port
    return host to port
}

/**
 * Checks the pin again from the session, so a resumed session (which skips the
 * trust manager) cannot carry a key the pin no longer names.
 */
private class PinningHostnameVerifier(private val state: LocalRouteState) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val pin = state.pinFor(hostname, session.peerPort) ?: return OkHostnameVerifier.verify(hostname, session)
        val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
        val matches = LocalRouteTls.spkiPin(leaf) == pin
        if (!matches) state.reportKeyMismatch(hostname, session.peerPort)
        return matches
    }
}

/**
 * Gives the handshake with a pinned address [LocalRouteTls.HANDSHAKE_TIMEOUT_MS]
 * instead of the client's read timeout, so a LAN address that accepts the
 * connection but never completes TLS falls back quickly.
 */
private class HandshakeTimeoutSocketFactory(
    private val delegate: SSLSocketFactory,
    private val state: LocalRouteState,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(socket, host, port, autoClose).also { created ->
            if (host == null || state.pinFor(host, port) == null) return@also
            val ssl = created as? SSLSocket ?: return@also
            val previous = ssl.soTimeout
            ssl.soTimeout = LocalRouteTls.HANDSHAKE_TIMEOUT_MS
            ssl.addHandshakeCompletedListener { runCatching { ssl.soTimeout = previous } }
        }

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)
    override fun createSocket(): Socket = delegate.createSocket()
}
