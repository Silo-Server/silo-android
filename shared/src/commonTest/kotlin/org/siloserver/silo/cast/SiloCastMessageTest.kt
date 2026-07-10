package org.siloserver.silo.cast

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Wire-parity tests against silo-apple's SiloControlMessage encoder
 * (iosApp/Control/SiloControlProtocol.swift). Apple cannot change, so every
 * golden fixture here is the byte-semantics Apple actually produces/expects:
 * camelCase payload fields, the `{type, v, <kind>}` envelope, payloadless
 * ping/pong/close, Int64 track ids, and the Name enum's exact strings.
 * Fixtures are compared as parsed JSON so key order is irrelevant.
 */
class SiloCastMessageTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private fun assertWireEquals(expected: String, message: SiloCastMessage) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(json.encodeToString(SiloCastMessage.serializer(), message)),
        )
    }

    @Test
    fun helloMatchesAppleEnvelopeAndFields() {
        assertWireEquals(
            """
            {"type":"hello","v":2,"hello":{
                "role":"phone",
                "deviceName":"Pixel 9",
                "deviceId":"android-abc",
                "serverId":"srv-1",
                "serverName":"Home",
                "supportedVersions":[1,2]
            }}
            """,
            SiloCastMessage.Hello(
                SiloCastHello(
                    role = SiloCastPeerRole.Phone,
                    deviceName = "Pixel 9",
                    deviceId = "android-abc",
                    serverId = "srv-1",
                    serverName = "Home",
                    supportedVersions = SiloCastProtocol.supportedVersions,
                ),
            ),
        )
    }

    @Test
    fun decodesAppleTvHello() {
        val decoded = json.decodeFromString(
            SiloCastMessage.serializer(),
            """{"type":"hello","v":1,"hello":{"role":"tv","deviceName":"Living Room",""" +
                """"deviceId":"atv-1","serverId":"srv-1","serverName":"Home","supportedVersions":[1]}}""",
        )
        val hello = assertIs<SiloCastMessage.Hello>(decoded).hello
        assertEquals(SiloCastPeerRole.Tv, hello.role)
        assertEquals("srv-1", hello.serverId)
    }

    @Test
    fun launchNestsPlaybackRequestLikeApple() {
        assertWireEquals(
            """
            {"type":"launch","v":2,"launch":{
                "serverId":"srv-1",
                "playback":{
                    "contentId":"movie-42",
                    "fileId":7,
                    "audioTrackIndex":1,
                    "startFromBeginning":false,
                    "resumePosition":120.5
                }
            }}
            """,
            SiloCastMessage.Launch(
                SiloCastLaunchRequest(
                    serverId = "srv-1",
                    playback = SiloCastPlaybackRequest(
                        contentId = "movie-42",
                        fileId = 7,
                        audioTrackIndex = 1,
                        subtitleTrackIndex = null,
                        startFromBeginning = false,
                        resumePosition = 120.5,
                    ),
                ),
            ),
        )
    }

    @Test
    fun controlCommandsUseAppleNamesAndFields() {
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"play_pause"}}""",
            SiloCastMessage.Control(SiloCastControlCommand.playPause()),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"set_quality","value":"hd-1080"}}""",
            SiloCastMessage.Control(SiloCastControlCommand.setQuality("hd-1080")),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"select_audio_track","trackId":3}}""",
            SiloCastMessage.Control(SiloCastControlCommand.selectAudioTrack(3L)),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"set_subtitle_sync_ms","milliseconds":-250}}""",
            SiloCastMessage.Control(SiloCastControlCommand.setSubtitleSyncMs(-250)),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"play_next"}}""",
            SiloCastMessage.Control(SiloCastControlCommand.playNext()),
        )
    }

    @Test
    fun subtitleOffOmitsTrackIdLikeApple() {
        val message = SiloCastMessage.Control(SiloCastControlCommand.selectSubtitleTrack(null))
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"select_subtitle_track"}}""",
            message,
        )
        val decoded = json.decodeFromString(
            SiloCastMessage.serializer(),
            """{"type":"control","v":1,"control":{"name":"select_subtitle_track"}}""",
        )
        assertNull(assertIs<SiloCastMessage.Control>(decoded).control.trackId)
    }

    @Test
    fun pingPongCloseCarryNoPayload() {
        assertWireEquals("""{"type":"ping","v":2}""", SiloCastMessage.Ping())
        assertWireEquals("""{"type":"pong","v":2}""", SiloCastMessage.Pong())
        assertWireEquals("""{"type":"close","v":2}""", SiloCastMessage.Close())
        // Apple's decoder reads only `type` for these kinds.
        assertIs<SiloCastMessage.Ping>(
            json.decodeFromString(SiloCastMessage.serializer(), """{"type":"ping","v":1}"""),
        )
    }

    @Test
    fun decodesApplePlaybackState() {
        val decoded = json.decodeFromString(
            SiloCastMessage.serializer(),
            """
            {"type":"state","v":1,"state":{
                "contentId":"ep-9","title":"S01E09","isPlaying":true,"isLoading":false,
                "isBuffering":false,"currentTime":42.0,"duration":2700.0,
                "audioTracks":[{"kind":"audio","trackId":1,"title":"English","detail":"EAC3 5.1"}],
                "subtitleTracks":[],
                "selectedAudioTrackId":1,
                "qualityOptions":[{"id":"auto","label":"Auto"}],
                "activeQualityId":"auto","isQualitySwitching":false,
                "playbackSpeed":1.0,"videoGravity":"fit","hdrEnabled":true,
                "supportsVideoGravity":true,"supportsHDRToggle":false,
                "subtitlePosition":"standard",
                "volume":1.0,"isMuted":false,"hasNextEpisode":true,
                "nextEpisodeTitle":"S01E10"
            }}
            """,
        )
        val state = assertIs<SiloCastMessage.State>(decoded).state
        assertEquals("ep-9", state.contentId)
        assertEquals(1L, state.audioTracks.single().trackId)
        assertEquals("EAC3 5.1", state.audioTracks.single().detail)
        assertEquals("auto", state.activeQualityId)
        assertEquals("standard", state.subtitlePosition)
        assertNull(state.subtitleSyncMs)
    }

    @Test
    fun errorMatchesAppleShape() {
        assertWireEquals(
            """{"type":"error","v":2,"error":{"code":"server_mismatch","message":"wrong server"}}""",
            SiloCastMessage.Error(SiloCastError(code = "server_mismatch", message = "wrong server")),
        )
    }

    @Test
    fun protocolConstantsMatchApple() {
        assertEquals(2, SiloCastProtocol.version)
        assertEquals(listOf(1, 2), SiloCastProtocol.supportedVersions)
        assertEquals(2, SiloCastProtocol.negotiatedVersion(listOf(1, 2)))
        assertEquals(1, SiloCastProtocol.negotiatedVersion(listOf(1)))
        assertNull(SiloCastProtocol.negotiatedVersion(listOf(3)))
        assertEquals("_silocast._tcp", SiloCastProtocol.serviceType)
    }

    @Test
    fun handoffMessagesMatchAppleV2WireShape() {
        assertWireEquals(
            """{"type":"handoff_offer","v":2,"handoffOffer":{"requestId":"r1","serverId":"s1","serverURL":"https://silo.example","serverName":"Home","profileId":"p1","profileName":"Alex"}}""",
            SiloCastMessage.HandoffOffer(
                SiloCastHandoffOffer("r1", "s1", "https://silo.example", "Home", "p1", "Alex"),
            ),
        )
        assertWireEquals(
            """{"type":"handoff_challenge","v":2,"handoffChallenge":{"requestId":"r1","userCode":"ABCD-EFGH","matchCode":"WXYZ","expiresAt":"2026-07-09T20:00:00Z"}}""",
            SiloCastMessage.HandoffChallenge(
                SiloCastHandoffChallenge("r1", "ABCD-EFGH", "WXYZ", "2026-07-09T20:00:00Z"),
            ),
        )
        assertWireEquals(
            """{"type":"handoff_ready","v":2,"handoffReady":{"requestId":"r1","serverId":"s1","profileId":"p1","sessionExpiresAt":"2026-07-10T20:00:00Z","reused":false}}""",
            SiloCastMessage.HandoffReady(
                SiloCastHandoffReady("r1", "s1", "p1", "2026-07-10T20:00:00Z", false),
            ),
        )
        assertWireEquals(
            """{"type":"handoff_cancel","v":2,"handoffCancel":{"requestId":"r1","reason":"denied","message":"No"}}""",
            SiloCastMessage.HandoffCancel(SiloCastHandoffCancel("r1", "denied", "No")),
        )

        val legacyOffer = json.decodeFromString(
            SiloCastMessage.serializer(),
            """{"type":"handoff_offer","v":2,"handoffOffer":{"requestId":"r2","serverId":"s1","serverURL":"https://silo.example","profileId":"p1"}}""",
        )
        assertNull(assertIs<SiloCastMessage.HandoffOffer>(legacyOffer).handoffOffer.profileName)
    }
}
