package org.siloserver.silo.common.pairing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingMessageCodec
import org.siloserver.silo.pairing.PairingReceiverState

class CompanionPairingNsdBrowserTest {
    @Test
    fun pairingServiceTypeAcceptsAndroidTrailingDotForm() {
        assertTrue(isPairingServiceType("_silopair._tcp"))
        assertTrue(isPairingServiceType("_silopair._tcp."))
        assertTrue(isPairingServiceType("_silopair-roku._tcp."))
        assertEquals("_silopair-roku._tcp", pairingServiceTypeOf("_silopair-roku._tcp."))
        assertFalse(isPairingServiceType("_silocast._tcp."))
        assertFalse(isPairingServiceType("_silocast-roku._tcp."))
    }

    @Test
    fun aRokuRecordReadsTheSameTxtKeysAndIsMarkedRoku() {
        val txt = mapOf("v" to "1", "name" to "Den Roku", "id" to "roku-1", "sid" to "s-1", "st" to "login", "srv" to "S-HOME")
        val roku = assertNotNull(pairingTargetFromRecord("192.168.1.20", 52827, "Silo-0123abcd", txt, isRoku = true))
        assertEquals("roku-1", roku.deviceId)
        assertEquals("Den Roku", roku.name)
        assertEquals("s-1", roku.sessionId)
        assertEquals("S-HOME", roku.serverIdentity)
        assertTrue(roku.isSignedOutTv)
        assertTrue(roku.isOfferable)
        assertTrue(roku.isRoku)
        assertTrue(roku.keepsOneServer)
        assertEquals("Roku", roku.deviceNoun)

        val tv = assertNotNull(pairingTargetFromRecord("192.168.1.21", 4711, "Living Room", txt, isRoku = false))
        assertFalse(tv.isRoku)
        assertFalse(tv.keepsOneServer)
        assertNull(pairingTargetFromRecord("192.168.1.21", 0, "Living Room", txt, isRoku = false))
    }

    @Test
    fun theTransportFollowsTheBrowsedType() {
        val tv = CompanionPairingTarget("tv-1", "Living Room", "127.0.0.1", 4711)
        assertEquals(CompanionPairingTransportKind.TlsPsk, CompanionPairingTransportKind.of(tv))
        assertEquals(CompanionPairingTransportKind.Plain, CompanionPairingTransportKind.of(tv.copy(isRoku = true)))
    }

    /** A Roku's pairing frames over plain TCP: hello first, then the phone's push. */
    @Test
    fun aRokuIsReachedOverPlainTcp() = runBlocking {
        val listener = ServerSocket(0)
        val pushed = CompletableFuture<String>()
        val worker = thread(isDaemon = true, name = "silopair-roku-test") {
            runCatching {
                listener.accept().use { socket ->
                    val hello = PairingMessageCodec.encode(
                        PairingMessage.Hello(
                            tvName = "Den Roku",
                            tvDeviceId = "roku-1",
                            state = PairingReceiverState.Setup,
                            supportedVersions = listOf(1),
                        ),
                    ).toByteArray()
                    DataOutputStream(socket.getOutputStream()).apply {
                        writeInt(hello.size)
                        write(hello)
                        flush()
                    }
                    val input = DataInputStream(socket.getInputStream())
                    pushed.complete(String(ByteArray(input.readInt()).also(input::readFully)))
                }
            }.onFailure(pushed::completeExceptionally)
        }
        val roku = CompanionPairingTarget("roku-1", "Den Roku", "127.0.0.1", listener.localPort, isRoku = true)
        val transport = CompanionPairingTransports.connect(roku)
        // Collected as the coordinator does: the read blocks until the transport closes.
        val inbox = Channel<PairingMessage>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { transport.incoming.collect { inbox.send(it) } }
        try {
            assertIs<PlainPairingClientTransport>(transport)
            val hello = assertIs<PairingMessage.Hello>(withTimeout(5_000) { inbox.receive() })
            assertEquals("Den Roku", hello.tvName)
            transport.send(PairingMessage.PushServer(serverURL = "https://home.example", serverName = "Home"))
            val push = assertIs<PairingMessage.PushServer>(PairingMessageCodec.decode(pushed.get(5, TimeUnit.SECONDS)))
            assertEquals("https://home.example", push.serverURL)
        } finally {
            transport.close()
            collector.join()
            listener.close()
            worker.join(5_000)
        }
    }
}
