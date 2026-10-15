package org.siloserver.silo.common.network

import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.siloserver.silo.network.HttpOrigin
import org.siloserver.silo.network.localroute.LocalRoute
import org.siloserver.silo.network.localroute.LocalRouteCheck
import org.siloserver.silo.network.localroute.LocalRouteHeaders
import org.siloserver.silo.network.localroute.LocalRouteOffer
import org.siloserver.silo.network.localroute.LocalRouteProbe
import org.siloserver.silo.network.localroute.LocalRouteState
import org.siloserver.silo.network.localroute.LocalRouteTls
import org.siloserver.silo.network.localroute.localRoute

/**
 * The local route's transport against real TLS: requests move only to a
 * candidate presenting the pinned key, report the saved address, and go again
 * to the saved address only when that is safe.
 */
class LocalRouteTransportTest {
    private val server = TestCertificate("silo-lan-server")
    private val impostor = TestCertificate("silo-lan-impostor")

    private lateinit var saved: MockWebServer
    private lateinit var lan: MockWebServer
    private val state = LocalRouteState()
    private val tls = LocalRouteTls(state)

    @BeforeTest
    fun setUp() {
        saved = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        lan = MockWebServer().apply { useHttps(server.serverSocketFactory(), false) }
        lan.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @AfterTest
    fun tearDown() {
        runCatching { saved.shutdown() }
        runCatching { lan.shutdown() }
    }

    @Test
    fun aRequestTakesTheRouteAndReportsTheSavedAddress() {
        activate(pin = server.pin)
        lan.enqueue(MockResponse().setBody("from lan"))

        client().newCall(get("/api/v2/home?limit=3").header("Authorization", "Bearer token").build()).execute().use { response ->
            assertEquals("from lan", response.body!!.string())
            assertEquals(savedUrl("/api/v2/home?limit=3"), response.request.url.toString())
        }
        val received = lan.takeRequest()
        assertEquals("/api/v2/home?limit=3", received.path)
        assertEquals("Bearer token", received.getHeader("Authorization"))
        assertEquals(0, saved.requestCount)
    }

    @Test
    fun aRefusedRouteGoesToTheSavedAddressAndIsDroppedAtOnce() {
        activate(pin = server.pin)
        lan.shutdown()
        saved.enqueue(MockResponse().setBody("from saved"))

        client().newCall(get("/api/v2/home").build()).execute().use { assertEquals("from saved", it.body!!.string()) }
        assertNull(state.active.value)
    }

    @Test
    fun aKeyOtherThanThePinNeverReceivesTheRequest() {
        lan.shutdown()
        lan = MockWebServer().apply { useHttps(impostor.serverSocketFactory(), false) }
        lan.start(InetAddress.getByName("127.0.0.1"), 0)
        activate(pin = server.pin)
        saved.enqueue(MockResponse().setBody("from saved"))

        client().newCall(get("/api/v2/home").header("Authorization", "Bearer token").build()).execute().use {
            assertEquals("from saved", it.body!!.string())
        }
        assertEquals(0, lan.requestCount)
        assertNull(state.active.value)
    }

    @Test
    fun aPooledConnectionIsNotReusedOnceItsKeyIsNoLongerThePin() {
        val route = activate(pin = server.pin)
        val client = client()
        lan.enqueue(MockResponse().setBody("first"))
        client.newCall(get("/api/v2/home").build()).execute().use { assertEquals("first", it.body!!.string()) }

        // The key rotated: the warm connection still presents the old one.
        state.trust(route.localOrigin.host, route.localOrigin.port, impostor.pin)
        state.publish(route.copy(generation = route.generation + 1))
        saved.enqueue(MockResponse().setBody("from saved"))
        client.newCall(get("/api/v2/home").build()).execute().use { assertEquals("from saved", it.body!!.string()) }
        assertEquals(1, lan.requestCount)
    }

    @Test
    fun aMutationThatMayHaveArrivedIsNotRepeated() {
        activate(pin = server.pin)
        lan.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val client = client { retryOnConnectionFailure(false) }

        assertFailsWith<IOException> { client.newCall(post("/api/v2/playback/session")).execute() }
        assertEquals(1, lan.requestCount)
        assertEquals(0, saved.requestCount)
        assertNull(state.active.value)
    }

    @Test
    fun aMutationThatCouldNotBeSentGoesToTheSavedAddress() {
        activate(pin = server.pin)
        lan.shutdown()
        saved.enqueue(MockResponse().setResponseCode(201))

        client().newCall(post("/api/v2/playback/session")).execute().use { assertEquals(201, it.code) }
        assertEquals("""{"a":1}""", saved.takeRequest().body.readUtf8())
    }

    @Test
    fun aLoadThatFailsBeforeItsBodyIsHandedOnLoadsFromTheSavedAddress() {
        activate(pin = server.pin)
        lan.enqueue(MockResponse().setBody("x".repeat(64 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        saved.enqueue(MockResponse().setBody("from saved"))

        client().newCall(get("/api/v2/artwork/1").build()).execute().use { assertEquals("from saved", it.body!!.string()) }
        assertNull(state.active.value)
    }

    @Test
    fun anInterruptedReadLeavesTheRouteInUse() {
        val route = activate(pin = server.pin)
        lan.enqueue(MockResponse().setBody("x".repeat(256 * 1024)).throttleBody(1024, 50, TimeUnit.MILLISECONDS))
        val response = client(media = true)
            .newCall(get("/api/v2/stream/s").header(LocalRouteHeaders.MEDIA, route.generation.toString()).build())
            .execute()
        var failure: Throwable? = null
        val reader = Thread {
            failure = runCatching { response.body!!.source().readByteString() }.exceptionOrNull()
        }
        reader.start()
        Thread.sleep(200)
        // Media3 abandons a load (a seek) by interrupting its loading thread.
        reader.interrupt()
        reader.join(5_000)
        response.close()

        assertTrue(failure is InterruptedIOException, "expected an interrupted read, got $failure")
        assertEquals(route, state.active.value)
    }

    @Test
    fun signInWebSocketsAndMarkedRequestsStayOnTheSavedAddress() {
        activate(pin = server.pin)
        val client = client()
        repeat(3) { saved.enqueue(MockResponse()) }

        client.newCall(post("/api/v2/auth/refresh")).execute().close()
        client.newCall(get("/api/v2/events").header("Upgrade", "websocket").build()).execute().close()
        client.newCall(get("/api/v2/downloads/1/file").header(LocalRouteHeaders.SAVED_ADDRESS, "1").build()).execute().close()

        assertEquals(3, saved.requestCount)
        assertEquals(0, lan.requestCount)
        repeat(2) { saved.takeRequest() }
        assertNull(saved.takeRequest().getHeader(LocalRouteHeaders.SAVED_ADDRESS))
    }

    @Test
    fun mediaTakesOnlyTheRouteItStartedOn() {
        val route = activate(pin = server.pin)
        val client = client(media = true)
        saved.enqueue(MockResponse().setBody("unmarked"))
        lan.enqueue(MockResponse().setBody("current"))
        saved.enqueue(MockResponse().setBody("older"))

        client.newCall(get("/api/v2/stream/s").build()).execute().use { assertEquals("unmarked", it.body!!.string()) }
        client.newCall(get("/api/v2/stream/s").header(LocalRouteHeaders.MEDIA, route.generation.toString()).build())
            .execute().use { assertEquals("current", it.body!!.string()) }
        client.newCall(get("/api/v2/stream/s").header(LocalRouteHeaders.MEDIA, (route.generation - 1).toString()).build())
            .execute().use { assertEquals("older", it.body!!.string()) }
        assertNull(lan.takeRequest().getHeader(LocalRouteHeaders.MEDIA))
    }

    @Test
    fun aCandidateIsConfirmedOnlyWithThePinnedKeyAndTheSavedServersIdentity() = runBlocking {
        val probe = LocalRouteProbe(probeTimeoutMs = 20_000)
        val origin = HttpOrigin("https", "127.0.0.1", lan.port)
        lan.enqueue(MockResponse().setBody("""{"server_id":"deployment-1"}"""))
        assertEquals(LocalRouteCheck.CONFIRMED, probe.confirms(origin, server.pin, "deployment-1"))

        lan.enqueue(MockResponse().setBody("""{"server_id":"deployment-2"}"""))
        assertEquals(LocalRouteCheck.UNCONFIRMED, probe.confirms(origin, server.pin, "deployment-1"))

        // Another key: the handshake fails and no request is sent.
        val count = lan.requestCount
        assertEquals(LocalRouteCheck.OTHER_KEY, probe.confirms(origin, impostor.pin, "deployment-1"))
        assertEquals(count, lan.requestCount)

        // A redirect is not followed.
        lan.enqueue(MockResponse().setResponseCode(302).setHeader("Location", savedUrl("/api/v2/system/identity")))
        assertEquals(LocalRouteCheck.UNCONFIRMED, probe.confirms(origin, server.pin, "deployment-1"))
        assertEquals(0, saved.requestCount)
    }

    @Test
    fun thePinIsLearnedOnlyFromTheSavedAddressWithoutRedirects() = runBlocking {
        val probe = LocalRouteProbe(probeTimeoutMs = 20_000)
        val elsewhere = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            saved.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/api/v2/system/connections")))
            assertEquals(LocalRouteOffer.Unreadable, probe.readOffer(savedUrl(""), "token"))
            assertEquals(0, elsewhere.requestCount)

            saved.enqueue(
                MockResponse().setBody(
                    """{"state":"available","server_id":"deployment-1","endpoints":[],""" +
                        """"local_route":{"spki_sha256":"${server.pin}","url":"http://192.168.1.20:8080"}}""",
                ),
            )
            assertEquals(
                LocalRouteOffer.Offered("deployment-1", server.pin, "http://192.168.1.20:8080"),
                probe.readOffer(savedUrl(""), "token"),
            )
            saved.takeRequest()
            assertEquals("Bearer token", saved.takeRequest().getHeader("Authorization"))
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test
    fun thePinIsTheHashOfTheSubjectPublicKeyInfo() {
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(server.certificate.publicKey.encoded),
        )
        assertEquals(expected, LocalRouteTls.spkiPin(server.certificate))
    }

    private fun activate(pin: String): LocalRoute {
        val route = LocalRoute(
            serverId = "server",
            savedOrigin = HttpOrigin("http", "127.0.0.1", saved.port),
            localOrigin = HttpOrigin("https", "127.0.0.1", lan.port),
            generation = 7,
        )
        state.trust(route.localOrigin.host, route.localOrigin.port, pin)
        state.publish(route)
        return route
    }

    private fun client(media: Boolean = false, configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .apply(configure)
            .localRoute(state, tls, media)
            .build()

    private fun savedUrl(path: String) = "http://127.0.0.1:${saved.port}$path"

    private fun get(path: String) = Request.Builder().url(savedUrl(path))

    private fun post(path: String) =
        Request.Builder().url(savedUrl(path)).post("""{"a":1}""".toRequestBody("application/json".toMediaType())).build()
}

/** A throwaway self-signed P-256 certificate, the kind of key the server derives. */
private class TestCertificate(commonName: String) {
    val keys: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    val certificate: X509Certificate = run {
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=$commonName")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.valueOf(System.nanoTime())))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(System.currentTimeMillis() - DAY_MS)))
            setEndDate(Time(Date(System.currentTimeMillis() + DAY_MS)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keys.public.encoded))
            setSignature(algorithm)
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(tbs.encoded)
            sign()
        }
        val der = DERSequence(arrayOf(tbs, algorithm, DERBitString(signature))).encoded
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    val pin: String = LocalRouteTls.spkiPin(certificate)!!

    fun serverSocketFactory(): SSLSocketFactory {
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("key", keys.private, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, password) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }.socketFactory
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
