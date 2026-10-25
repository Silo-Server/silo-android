package org.siloserver.silo.common.cast

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.lan.SiloCastSealed

/**
 * The phone's side of the Roku receiver (silo-roku `docs/silocontrol.md`):
 * discovery records, the pairing link, and plain and `sealed` dialing against
 * a loopback stand-in that speaks the Roku's side.
 */
class SiloCastRokuTransportTest {
    private val key = hex("000102030405060708090a0b0c0d0e0f")
    private val tvHello =
        """{"type":"hello","v":2,"hello":{"role":"tv","deviceName":"Den Roku","deviceId":"roku-1","serverId":"c3J2","serverName":"Home","supportedVersions":[2],"features":["volume_mute_only"]}}"""

    @Test
    fun theServiceTypeAloneDecidesTheTransport() {
        val txt = mapOf("v" to "2", "id" to "roku-1", "name" to "Den Roku", "sec" to "plain")
        val roku = assertNotNull(record(txt, SiloCastProtocol.rokuServiceType + "."))
        assertEquals(SiloCastTransport.Plain, roku.transport)
        assertEquals("roku-1", roku.deviceId)
        // A TLS TV's TXT can never talk the phone into plain TCP.
        assertEquals(SiloCastTransport.TlsPsk, assertNotNull(record(txt, SiloCastProtocol.serviceType)).transport)

        val sealed = assertNotNull(record(txt + mapOf("sec" to "sealed", "kid" to "BE45CB26"), SiloCastProtocol.rokuServiceType))
        assertEquals(SiloCastTransport.Sealed, sealed.transport)
        assertEquals("be45cb26", sealed.keyId)
        // A level this build can't speak is left out.
        assertNull(record(txt + mapOf("sec" to "quantum"), SiloCastProtocol.rokuServiceType))
    }

    @Test
    fun readsThePairingLink() {
        val sealed = assertNotNull(
            SiloCastPairingLink.parse(
                "silo://cast?v=2&host=192.168.1.20&port=52817&id=roku-1&name=Den%20Roku&server=c3J2&security=sealed&key=AAAQEAYEAUDAOCAJBIFQYDIOB4&later=1",
            ),
        )
        assertEquals("192.168.1.20", sealed.target.host)
        assertEquals(52817, sealed.target.port)
        assertEquals("Den Roku", sealed.target.name)
        assertEquals("c3J2", sealed.target.serverId)
        assertEquals(SiloCastTransport.Sealed, sealed.target.transport)
        assertEquals("be45cb26", sealed.target.keyId)
        assertContentEquals(key, sealed.pairingKey)

        val plain = assertNotNull(SiloCastPairingLink.parse("silo://cast?v=2&host=10.0.0.5&port=52818&id=roku-2&security=plain"))
        assertEquals(SiloCastTransport.Plain, plain.target.transport)
        assertNull(plain.pairingKey)

        assertNull(SiloCastPairingLink.parse("silo://cast?v=1&host=10.0.0.5&port=52818&id=roku-2"))
        assertNull(SiloCastPairingLink.parse("silo://cast?v=2&host=roku.local&port=52818&id=roku-2"))
        assertNull(SiloCastPairingLink.parse("silo://cast?v=2&host=10.0.0.5&port=52818&id=roku-2&security=sealed"))
        assertNull(SiloCastPairingLink.parse("silo://play?v=2&host=10.0.0.5&port=52818&id=roku-2"))

        assertEquals("192.168.1.20" to 52817, SiloCastPairingLink.parseAddress(" 192.168.1.20:52817 "))
        assertEquals("192.168.1.20" to SiloCastPairingLink.DEFAULT_ROKU_PORT, SiloCastPairingLink.parseAddress("192.168.1.20"))
        assertNull(SiloCastPairingLink.parseAddress("192.168.1.300:52817"))
        assertNull(SiloCastPairingLink.parseAddress("192.168.1.20:0"))
    }

    @Test
    fun dialsAPlainRoku() = withRoku({ socket ->
        frame(socket, tvHello.encodeToByteArray())
        read(socket).decodeToString()
    }) { port, result ->
        val connection = SiloCastDialer.dial(roku(port, SiloCastTransport.Plain), pairingKey = null, connectTimeoutMs = 5_000)
        try {
            assertEquals(tvHello, connection.readFrame()?.decodeToString())
            connection.writeFrame("""{"type":"pong","v":2}""".encodeToByteArray())
            assertEquals("""{"type":"pong","v":2}""", result.get(5, TimeUnit.SECONDS))
        } finally {
            connection.close()
        }
    }

    @Test
    fun dialsASealedRoku() = withRoku({ socket ->
        val phone = assertNotNull(SiloCastSealed.readHello(read(socket).decodeToString()))
        assertEquals("be45cb26", phone.keyId)
        val tvNonce = hex("202122232425262728292a2b2c2d2e2f")
        frame(socket, SiloCastSealed.helloText("be45cb26", tvNonce).encodeToByteArray())
        val keys = SiloCastSealed.sessionKeys(key, phone.nonce, tvNonce)
        frame(socket, SiloCastSealed.seal(keys, SiloCastSealed.FROM_TV, 0, tvHello.encodeToByteArray()))
        val first = SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 0, read(socket))
        val second = SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 1, read(socket))
        "${first?.decodeToString()} ${second?.decodeToString()}"
    }) { port, result ->
        val connection = SiloCastDialer.dial(roku(port, SiloCastTransport.Sealed), pairingKey = key, connectTimeoutMs = 5_000)
        try {
            assertEquals(tvHello, connection.readFrame()?.decodeToString())
            connection.writeFrame("a".encodeToByteArray())
            connection.writeFrame("b".encodeToByteArray())
            assertEquals("a b", result.get(5, TimeUnit.SECONDS))
        } finally {
            connection.close()
        }
    }

    @Test
    fun aRokuThatRefusesTheKeyIsReportedAsSuch() = withRoku({ socket ->
        read(socket)
        frame(socket, """{"type":"close","v":2,"reason":"unauthorized"}""".encodeToByteArray())
        ""
    }) { port, _ ->
        assertFailsWith<SiloCastPairingRefusedException> {
            SiloCastDialer.dial(roku(port, SiloCastTransport.Sealed), pairingKey = key, connectTimeoutMs = 5_000)
        }
    }

    @Test
    fun probesATypedAddressThroughTheRokusHello() = withRoku({ socket ->
        frame(socket, tvHello.encodeToByteArray())
        ""
    }) { port, _ ->
        val target = assertNotNull(SiloCastDialer.probeRoku("127.0.0.1", port, 5_000))
        assertEquals("roku-1", target.deviceId)
        assertEquals("Den Roku", target.name)
        assertEquals("c3J2", target.serverId)
        assertEquals(SiloCastTransport.Plain, target.transport)
        assertEquals(SiloCastProtocol.version, target.version)
    }

    private fun record(txt: Map<String, String>, type: String) =
        SiloCastNsdBrowser.targetFromRecord(host = "10.0.0.5", port = 52817, serviceName = "Silo-0123abcd", txt = txt, serviceType = type)

    private fun roku(port: Int, transport: SiloCastTransport) = SiloCastTarget(
        deviceId = "roku-1",
        name = "Den Roku",
        host = "127.0.0.1",
        port = port,
        version = 2,
        transport = transport,
    )

    private fun withRoku(side: (Socket) -> String, body: (Int, CompletableFuture<String>) -> Unit) {
        val listener = ServerSocket(0)
        val result = CompletableFuture<String>()
        val worker = thread(isDaemon = true, name = "silocast-roku-test") {
            runCatching { listener.accept().use { socket -> result.complete(side(socket)) } }
                .onFailure(result::completeExceptionally)
        }
        try {
            body(listener.localPort, result)
        } finally {
            listener.close()
            worker.join(5_000)
        }
    }

    private fun frame(socket: Socket, payload: ByteArray) {
        DataOutputStream(socket.getOutputStream()).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    private fun read(socket: Socket): ByteArray {
        val input = DataInputStream(socket.getInputStream())
        return ByteArray(input.readInt()).also(input::readFully)
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
