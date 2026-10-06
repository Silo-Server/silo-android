package org.siloserver.silo.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Literal frames shared with silo-apple (`iosApp/Tests/PairingProtocolTests.swift`,
 * `testLiteralWireFramesDecodeAndEncodeExactly`). A round trip passes even when
 * a key is renamed on both sides of one codec, which would strand the other
 * platform's phones and TVs; these literals fail instead. Frames compare as
 * JSON objects because the two encoders order keys differently.
 *
 * Keep the two lists identical. When a frame changes here, change it in
 * silo-apple in the same release.
 */
class PairingProtocolGoldenFramesTest {
    private val url = "https://media.example.com"

    /** Copied verbatim from silo-apple. */
    private val appleFrames: List<Pair<String, PairingMessage>> = listOf(
        """{"type":"hello","v":1,"tvName":"Living Room","tvDeviceId":"ABC-123","state":"setup","supportedVersions":[1]}""" to
            PairingMessage.Hello("Living Room", "ABC-123", PairingReceiverState.Setup, listOf(1)),
        """{"type":"pushServer","v":1,"serverURL":"https://media.example.com","serverName":"Home"}""" to
            PairingMessage.PushServer(serverURL = url, serverName = "Home"),
        """{"type":"deviceStarted","v":1,"serverURL":"https://media.example.com","userCode":"WXYZ-12","matchCode":"brave-otter"}""" to
            PairingMessage.DeviceStarted(serverURL = url, userCode = "WXYZ-12", matchCode = "brave-otter"),
        """{"type":"serverResult","v":1,"serverURL":"https://media.example.com","status":"signedIn"}""" to
            PairingMessage.ServerResult(serverURL = url, status = PairingServerStatus.SignedIn, error = null),
        """{"type":"serverResult","v":1,"serverURL":"https://media.example.com","status":"failed","error":"unreachable"}""" to
            PairingMessage.ServerResult(
                serverURL = url,
                status = PairingServerStatus.Failed,
                error = PairingFailureCode.Unreachable.wire,
            ),
        """{"type":"done","v":1}""" to PairingMessage.Done,
        """{"type":"cancel","v":1,"reason":"user_declined"}""" to PairingMessage.Cancel(reason = "user_declined"),
    )

    /**
     * Frames Android adds for the Phase 1 sign-in work. The pushServer frame
     * is the shape silo-apple's encoder writes for `pushServer(serverIdentity:
     * endpoints:)`; the hello frame is a signed-out TV (`st=login`). Send these
     * to silo-apple for the same literal check.
     */
    private val androidFrames: List<Pair<String, PairingMessage>> = listOf(
        """{"type":"hello","v":1,"tvName":"Den","tvDeviceId":"id-9","state":"login","supportedVersions":[1]}""" to
            PairingMessage.Hello("Den", "id-9", PairingReceiverState.Login, listOf(1)),
        (
            """{"type":"pushServer","v":1,"serverURL":"https://media.example.com","serverName":"Home",""" +
                """"serverIdentity":"96c1bd08-b839-4d47-980e-57d4e7a44cfa","endpoints":[""" +
                """{"url":"https://media.example.com","kind":"public"},""" +
                """{"url":"https://media.overlay.example","kind":"provider","provider":"tailscale","displayName":"Tailscale"}]}"""
            ) to PairingMessage.PushServer(
            serverURL = url,
            serverName = "Home",
            serverIdentity = "96c1bd08-b839-4d47-980e-57d4e7a44cfa",
            endpoints = listOf(
                PairingEndpoint.of(url, PairingEndpoint.Kind.Public),
                PairingEndpoint.of(
                    "https://media.overlay.example",
                    PairingEndpoint.Kind.Provider,
                    provider = "tailscale",
                    displayName = "Tailscale",
                ),
            ),
        ),
        """{"type":"serverResult","v":1,"serverURL":"https://media.example.com","status":"failed","error":"identity_mismatch"}""" to
            PairingMessage.ServerResult(
                serverURL = url,
                status = PairingServerStatus.Failed,
                error = PairingFailureCode.IdentityMismatch.wire,
            ),
    )

    @Test
    fun literalFramesDecodeAndEncodeExactly() {
        for ((frame, message) in appleFrames + androidFrames) {
            assertEquals(message, PairingMessageCodec.decode(frame), frame)
            assertEquals(json(frame), json(PairingMessageCodec.encode(message)), frame)
        }
    }

    @Test
    fun pushServerIdentityFieldsAreOptionalOnTheWire() {
        val legacy = PairingMessageCodec.decode(
            """{"type":"pushServer","v":1,"serverURL":"https://media.example.com","serverName":"Home"}""",
        ) as PairingMessage.PushServer
        assertNull(legacy.serverIdentity)
        assertNull(legacy.endpoints)

        val encoded = json(PairingMessageCodec.encode(PairingMessage.PushServer(url, null)))
        assertNull(encoded["serverIdentity"])
        assertNull(encoded["endpoints"])

        // Endpoint URLs cross the wire without a trailing slash, as silo-apple normalizes them.
        val full = json(
            PairingMessageCodec.encode(
                PairingMessage.PushServer(
                    serverURL = url,
                    serverName = "Home",
                    serverIdentity = "S",
                    endpoints = listOf(
                        PairingEndpoint.of(
                            "https://media.overlay.example/",
                            PairingEndpoint.Kind.Provider,
                            provider = "tailscale",
                            displayName = "Tailscale",
                        ),
                    ),
                ),
            ),
        )
        val endpoint = (full["endpoints"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("\"https://media.overlay.example\"", endpoint["url"].toString())
        assertEquals("\"provider\"", endpoint["kind"].toString())
    }

    @Test
    fun explicitNullOptionalFieldsDecodeAsAbsent() {
        val push = PairingMessageCodec.decode(
            """{"type":"pushServer","v":1,"serverURL":"https://media.example.com","serverName":null,"serverIdentity":null}""",
        ) as PairingMessage.PushServer
        assertNull(push.serverName)
        assertNull(push.serverIdentity)
    }

    @Test
    fun failureCodesReadUnknownAsGenericFailure() {
        assertEquals(PairingFailureCode.Unreachable, PairingFailureCode.fromWire("unreachable"))
        assertEquals(PairingFailureCode.IdentityMismatch, PairingFailureCode.fromWire("identity_mismatch"))
        assertEquals(PairingFailureCode.AuthFailed, PairingFailureCode.fromWire(null))
        assertEquals(PairingFailureCode.AuthFailed, PairingFailureCode.fromWire("something_new"))
        // Older Android TVs sent human text here; it reads as the generic failure.
        assertEquals(PairingFailureCode.AuthFailed, PairingFailureCode.fromWire("Sign-in was denied on the other device."))
    }

    @Test
    fun unknownTypeFailsToDecode() {
        assertFailsWith<PairingMessageException> { PairingMessageCodec.decode("""{"type":"bogus","v":1}""") }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
