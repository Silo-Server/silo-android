package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.audioPreferenceUpdate
import org.siloserver.silo.model.playback.subtitlePreferenceUpdate
import org.siloserver.silo.network.*
import org.siloserver.silo.playback.InMemoryTrackPreferenceStore
import org.siloserver.silo.playback.TrackPreferenceWriter
import org.siloserver.silo.util.formatEpochMillisRfc3339
import kotlin.test.*

/**
 * The `/api/v2/{audio,subtitle}-prefs` contract as Silo Apple writes it, and
 * how picks reach it: in pick order, under the profile they were made in, and,
 * for a pick made offline, only when it is newer than what the server holds.
 */
class TrackPreferencesV2ApiTest {
    private var active: AuthScopeSnapshot? = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val owner = active!!
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = active
    }

    /** A server holding rows by path, stamping writes with its own clock, [serverAheadMs] ahead of the device's. */
    private inner class FakeServer(private val scope: TestScope) {
        var reachable = true
        var refuseWrites = false
        /** How many more writes to answer with 429. */
        var rateLimitedWrites = 0
        var deviceNowMs = 1_800_000_000_000L
        var serverAheadMs = 0L
        val rows = mutableMapOf<String, JsonObject>()
        val sent = mutableListOf<HttpRequestData>()
        val puts get() = sent.filter { it.method == HttpMethod.Put }

        fun serverNowMs() = deviceNowMs + serverAheadMs

        fun write(path: String, body: String, atServerMs: Long = serverNowMs()) {
            val row = Json.parseToJsonElement(body).jsonObject + ("updated_at" to JsonPrimitive(formatEpochMillisRfc3339(atServerMs)))
            rows[path] = JsonObject(row)
        }

        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
                if (!reachable) throw IOException("offline")
                sent += request
                val date = GMTDate(serverNowMs()).toHttpDate()
                val path = request.url.encodedPath
                val row = rows[path]
                when {
                    request.method == HttpMethod.Put && rateLimitedWrites > 0 -> {
                        rateLimitedWrites--
                        respond("""{"status":429,"detail":"slow down"}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.Date, date))
                    }
                    request.method == HttpMethod.Put && refuseWrites ->
                        respond("""{"status":503,"detail":"later"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.Date, date))
                    request.method == HttpMethod.Put -> {
                        write(path, (request.body as TextContent).text)
                        respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.Date, date))
                    }
                    row != null -> respond(
                        row.toString(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.Date to listOf(date)),
                    )
                    else -> respond("""{"status":404,"detail":"none"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.Date, date))
                }
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }

        val api = TrackPreferencesV2Api(client, tokens, ApiV2Gate.Unrestricted)

        fun writer() = TrackPreferenceWriter(api, tokens, scope.backgroundScope, InMemoryTrackPreferenceStore()) { deviceNowMs }
    }

    /** Returns once everything queued before it has been handled: the writer works in order. */
    private suspend fun TrackPreferenceWriter.settle() = refresh(owner, "barrier", timeoutMs = 60_000)

    private fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun JsonObject?.mode() = this?.get("subtitle_mode")?.jsonPrimitive?.content

    private val catalog = listOf(SubtitleTrack(index = 0, codec = "subrip", language = "en", title = "English"))
    private val pick = subtitlePreferenceUpdate(SubtitleIdentity.Embedded(0, SubtitleMediaIdentity()), catalog)!!
    private val off = subtitlePreferenceUpdate(SubtitleIdentity.Off, catalog)!!
    private val offRow = """{"subtitle_track_index":-1,"subtitle_language":"","subtitle_mode":"off"}"""
    private val subtitlePath = "/api/v2/subtitle-prefs/series-9"

    @Test fun anAudioPickSendsTheContractMembersUnderTheSeriesKey() = runTest {
        val server = FakeServer(this)
        val writer = server.writer()
        val tracks = listOf(
            AudioTrack(codec = "aac", language = "en", title = "Stereo"),
            AudioTrack(codec = "eac3", language = "ja", title = "Japanese 5.1", embeddedTitle = "Surround", channels = 6, channelLayout = "5.1", isDefault = true),
        )

        writer.saveAudio(owner, "series/9", audioPreferenceUpdate(tracks, 1)!!)
        writer.settle()

        val request = server.puts.single()
        assertEquals("/api/v2/audio-prefs/series%2F9", request.url.encodedPath)
        assertEquals(owner, request.attributes[AuthScopeAttributeKey])
        val body = request.json()
        assertEquals(setOf("audio_track_index", "audio_language", "track_signature"), body.keys)
        assertEquals("1", body.getValue("audio_track_index").jsonPrimitive.content)
        assertEquals("ja", body.getValue("audio_language").jsonPrimitive.content)
        assertEquals(
            setOf("language", "title", "embedded_title", "codec", "layout", "channels", "default"),
            body.getValue("track_signature").jsonObject.keys,
        )
    }

    @Test fun offSendsMinusOneAndNeitherASignatureNorTheForcedOverride() = runTest {
        val server = FakeServer(this)
        val writer = server.writer()

        writer.saveSubtitle(owner, "series-9", off)
        writer.settle()

        val request = server.puts.single()
        assertEquals(subtitlePath, request.url.encodedPath)
        val body = request.json()
        assertEquals("-1", body.getValue("subtitle_track_index").jsonPrimitive.content)
        assertEquals("off", body.mode())
        assertNull(body["track_signature"], "Off is not a track")
        // Omitted keeps the forced override the server stores; a pick must not copy an inherited one.
        assertNull(body["show_forced_subtitles"])
    }

    @Test fun aSubtitlePickIsDescribedByTheSourceTrackOrOnlyByItsFlags() {
        val catalog = listOf(
            SubtitleTrack(index = 3, codec = "subrip", language = "en", title = "English (SDH)", hearingImpaired = true),
            SubtitleTrack(index = 0, codec = "srt", language = "fr", title = "French", external = true, externalPath = "Show.fr.srt"),
        )

        // A server track keeps its combined index (externals first).
        val external = assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.ServerSidecar(0), catalog))
        assertEquals(0, external.subtitleTrackIndex)
        assertEquals("external", external.trackSignature?.source)
        assertEquals("Show.fr.srt", external.externalSubtitlePath)
        val embedded = assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Embedded(1, SubtitleMediaIdentity()), catalog))
        assertEquals(true, embedded.trackSignature?.hearingImpaired)

        // A downloaded subtitle's id is not a server index.
        val downloaded = assertNotNull(
            subtitlePreferenceUpdate(
                SubtitleIdentity.Downloaded(77, SubtitleMediaIdentity(language = "de", label = "German", forced = true)),
                catalog,
            ),
        )
        assertEquals(-1, downloaded.subtitleTrackIndex)
        assertEquals("de", downloaded.subtitleLanguage)
        assertEquals(true, downloaded.trackSignature?.forced)
    }

    @Test fun onlyASavedRowLetsTheServersResolvedIndexStand() = runTest {
        val server = FakeServer(this)
        val version = FileVersion(
            fileId = 4,
            audioTracks = listOf(AudioTrack(language = "en"), AudioTrack(language = "ja")),
            effectiveAudioTrackIndex = 1,
        )

        // Without a row the effective index is only the server's default.
        assertNull(server.api.savedAudioTrackIndex(owner, "series-9", version))
        server.write("/api/v2/audio-prefs/series-9", """{"audio_track_index":1,"audio_language":"ja"}""")
        assertEquals(1, server.api.savedAudioTrackIndex(owner, "series-9", version))
    }

    @Test fun aPickMadeOfflineReachesTheServerOnceItAnswers() = runTest {
        val server = FakeServer(this)
        val writer = server.writer()
        server.reachable = false

        writer.saveSubtitle(owner, "series-9", pick)
        writer.settle()
        assertEquals("always", writer.rememberedSubtitle("s", "p", "series-9")?.subtitleMode, "the download plays with it meanwhile")

        server.reachable = true
        writer.flush()
        writer.settle()
        assertEquals("always", server.rows[subtitlePath].mode())
    }

    @Test fun anOfflinePickLosesToALaterPickFromAnotherDeviceAndWinsOverAnEarlierOne() = runTest {
        for ((otherPickAfterMs, expectedMode) in listOf(60_000L to "off", -60_000L to "always")) {
            val server = FakeServer(this)
            // The device's clock is an hour slow: only the server's Date makes the two times comparable.
            server.serverAheadMs = 3_600_000L
            server.write(subtitlePath, offRow, atServerMs = server.serverNowMs() - 600_000)
            val writer = server.writer()
            writer.refresh(owner, "series-9", timeoutMs = 60_000)
            server.reachable = false
            val pickedAtServerMs = server.serverNowMs()
            writer.saveSubtitle(owner, "series-9", pick)
            writer.settle()

            // Another device changes the row while this one is offline.
            server.write(subtitlePath, offRow, atServerMs = pickedAtServerMs + otherPickAfterMs)
            server.deviceNowMs += 120_000
            server.reachable = true
            writer.flush()
            writer.settle()

            assertEquals(expectedMode, server.rows[subtitlePath].mode(), "other pick ${otherPickAfterMs}ms after")
            assertEquals(expectedMode, writer.rememberedSubtitle("s", "p", "series-9")?.subtitleMode)
        }
    }

    @Test fun aRefreshNeverReplacesAPickNotYetSent() = runTest {
        val server = FakeServer(this)
        val writer = server.writer()
        server.write(subtitlePath, offRow, atServerMs = server.serverNowMs() - 600_000)
        server.reachable = false
        writer.saveSubtitle(owner, "series-9", pick)
        writer.settle()

        // The server answers reads again but refuses the write for now.
        server.reachable = true
        server.refuseWrites = true
        writer.refresh(owner, "series-9", timeoutMs = 60_000)
        writer.settle()

        assertEquals("off", server.rows[subtitlePath].mode())
        assertEquals("always", writer.rememberedSubtitle("s", "p", "series-9")?.subtitleMode)
    }

    @Test fun aRateLimitedPickIsKeptAndSentAgainLater() = runTest {
        val server = FakeServer(this)
        val writer = server.writer()
        server.rateLimitedWrites = 1

        writer.saveSubtitle(owner, "series-9", pick)
        writer.settle()
        assertNull(server.rows[subtitlePath], "the server has not applied it")
        assertEquals("always", writer.rememberedSubtitle("s", "p", "series-9")?.subtitleMode, "the pick is still pending")

        advanceTimeBy(5 * 60_000L)
        writer.settle()
        assertEquals(2, server.puts.size)
        assertEquals("always", server.rows[subtitlePath].mode())
    }

    @Test fun picksAreSentInOrderAndOnlyUnderTheProfileTheyWereMadeIn() = runTest {
        val server = FakeServer(this)
        val writer = server.writer()

        writer.saveSubtitle(owner, "series-9", pick)
        writer.saveSubtitle(owner, "series-9", off)
        writer.settle()
        assertEquals(listOf("always", "off"), server.puts.map { it.json().mode() })

        // Offline, then the viewer switches profile: the pick waits for its own profile.
        server.reachable = false
        writer.saveSubtitle(owner, "series-9", pick)
        writer.settle()
        server.reachable = true
        active = owner.copy(profileId = "p2", identityGeneration = 2)
        writer.flush()
        writer.settle()
        assertEquals(2, server.puts.size, "nothing is sent under another profile")

        val back = owner.copy(identityGeneration = 3)
        active = back
        writer.flush()
        writer.refresh(back, "barrier", timeoutMs = 60_000)
        assertEquals(back, server.puts.last().attributes[AuthScopeAttributeKey])
        assertEquals("always", server.puts.last().json().mode())
    }
}
