package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.audioPreferenceUpdate
import org.siloserver.silo.model.playback.subtitlePreferenceUpdate
import org.siloserver.silo.model.playback.trackPreferenceKey
import org.siloserver.silo.network.*
import org.siloserver.silo.playback.TrackPreferenceWriter
import kotlin.test.*

/** The `/api/v2/{audio,subtitle}-prefs` contract as Silo Apple writes it. */
class TrackPreferencesV2ApiTest {
    private var active: AuthScopeSnapshot? = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val owner = active!!
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = active
    }

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        })) { install(ContentNegotiation) { json(SiloJson) } }

    private fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test fun anAudioPickSendsTheContractMembersUnderTheSeriesKey() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val c = client { sent += it; respond("", HttpStatusCode.NoContent) }
        val tracks = listOf(
            AudioTrack(codec = "aac", language = "en", title = "Stereo"),
            AudioTrack(codec = "eac3", language = "ja", title = "Japanese 5.1", embeddedTitle = "Surround", channels = 6, channelLayout = "5.1", isDefault = true),
        )

        val result = TrackPreferencesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            .putAudio(owner, "series/9", assertNotNull(audioPreferenceUpdate(tracks, 1)))

        assertIs<ApiResult.Success<Unit>>(result)
        val request = sent.single()
        assertEquals(HttpMethod.Put, request.method)
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
        val sent = mutableListOf<HttpRequestData>()
        val c = client { sent += it; respond("", HttpStatusCode.NoContent) }

        TrackPreferencesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            .putSubtitle(owner, "series-9", assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Off, emptyList())))

        val request = sent.single()
        assertEquals("/api/v2/subtitle-prefs/series-9", request.url.encodedPath)
        val body = request.json()
        assertEquals("-1", body.getValue("subtitle_track_index").jsonPrimitive.content)
        assertEquals("off", body.getValue("subtitle_mode").jsonPrimitive.content)
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

    @Test fun episodesKeyByTheirSeriesAndNeverByTheirOwnId() {
        assertEquals("movie-1", trackPreferenceKey("movie", "movie-1", null))
        assertEquals("series-1", trackPreferenceKey("episode", "episode-5", "series-1"))
        assertNull(trackPreferenceKey("episode", "episode-5", null))
        assertNull(trackPreferenceKey("episode", "episode-5", " "))
        assertNull(trackPreferenceKey("audiobook", "book-1", null))
    }

    @Test fun onlyASavedRowLetsTheServersResolvedIndexStand() = runTest {
        var saved = true
        val c = client {
            assertEquals("/api/v2/audio-prefs/series-9", it.url.encodedPath)
            if (saved) {
                respond(
                    """{"profile_id":"p","series_id":"series-9","audio_track_index":1,"audio_language":"ja","updated_at":"2026-10-24T10:00:00.000Z"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond("""{"status":404,"detail":"none"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            }
        }
        val api = TrackPreferencesV2Api(c, tokens, ApiV2Gate.Unrestricted)
        val version = FileVersion(
            fileId = 4,
            audioTracks = listOf(AudioTrack(language = "en"), AudioTrack(language = "ja")),
            effectiveAudioTrackIndex = 1,
        )

        assertEquals(1, api.savedAudioTrackIndex(owner, "series-9", version))
        saved = false
        // Without a row the effective index is only the server's default.
        assertNull(api.savedAudioTrackIndex(owner, "series-9", version))
    }

    @Test fun picksAreSentInOrderAndNeverUnderAnotherProfile() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val sends = Channel<Pair<String, String?>>(Channel.UNLIMITED)
        var count = 0
        val c = client {
            sends.send(it.json().getValue("subtitle_mode").jsonPrimitive.content to it.attributes[AuthScopeAttributeKey].profileId)
            if (++count == 1) releaseFirst.await()
            respond("", HttpStatusCode.NoContent)
        }
        val writer = TrackPreferenceWriter(TrackPreferencesV2Api(c, tokens, ApiV2Gate.Unrestricted), backgroundScope)
        val catalog = listOf(SubtitleTrack(index = 0, codec = "subrip", language = "en"))
        val pick = assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Embedded(0, SubtitleMediaIdentity()), catalog))
        val off = assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Off, catalog))

        writer.saveSubtitle(owner, "series-9", pick)
        writer.saveSubtitle(owner, "series-9", off)
        assertEquals("always" to "p", sends.receive())
        assertTrue(sends.tryReceive().isFailure, "the later Off waits for the earlier pick")
        releaseFirst.complete(Unit)
        assertEquals("off" to "p", sends.receive())

        // The viewer switches profile before the next queued pick is sent.
        val switched = owner.copy(profileId = "p2", identityGeneration = 2)
        active = switched
        writer.saveSubtitle(owner, "series-9", off)
        writer.saveSubtitle(switched, "series-9", pick)
        assertEquals("always" to "p2", sends.receive(), "only the pick made under the new profile was sent")
    }

    @Test fun aRequestStalledForOneProfileDoesNotHoldUpAnothersPicks() = runTest {
        val sends = Channel<String?>(Channel.UNLIMITED)
        val c = client {
            val profile = it.attributes[AuthScopeAttributeKey].profileId
            sends.send(profile)
            if (profile == "p") awaitCancellation()
            respond("", HttpStatusCode.NoContent)
        }
        val writer = TrackPreferenceWriter(TrackPreferencesV2Api(c, tokens, ApiV2Gate.Unrestricted), backgroundScope)
        val off = assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Off, emptyList()))

        writer.saveSubtitle(owner, "series-9", off)
        assertEquals("p", sends.receive())
        val switched = owner.copy(profileId = "p2", identityGeneration = 2)
        active = switched
        writer.saveSubtitle(switched, "series-9", off)
        assertEquals("p2", sends.receive(), "the new profile's pick is sent while the old profile's request hangs")
    }

    @Test fun aWriteCancelledFromInsideDoesNotStopLaterPicks() = runTest {
        val sends = Channel<String>(Channel.UNLIMITED)
        var count = 0
        val c = client {
            val mode = it.json().getValue("subtitle_mode").jsonPrimitive.content
            if (++count == 1) throw CancellationException("request cancelled")
            sends.send(mode)
            respond("", HttpStatusCode.NoContent)
        }
        val writer = TrackPreferenceWriter(TrackPreferencesV2Api(c, tokens, ApiV2Gate.Unrestricted), backgroundScope)
        val catalog = listOf(SubtitleTrack(index = 0, codec = "subrip", language = "en"))

        writer.saveSubtitle(owner, "series-9", assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Off, catalog)))
        writer.saveSubtitle(owner, "series-9", assertNotNull(subtitlePreferenceUpdate(SubtitleIdentity.Embedded(0, SubtitleMediaIdentity()), catalog)))
        assertEquals("always", sends.receive())
    }
}
