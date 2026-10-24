package org.siloserver.silo.common.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.SeriesSubtitleChoice
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTrackChoiceWriterTest {

    private val profileA = AuthScopeSnapshot("server", "profile-a", "https://one.example", "token-a")
    private val profileB = AuthScopeSnapshot("server", "profile-b", "https://one.example", "token-b")

    private data class Call(val verb: String, val key: String, val choice: SeriesSubtitleChoice?, val owner: AuthScopeSnapshot)

    private class Recorder {
        val calls = mutableListOf<Call>()
        val results = ArrayDeque<ApiResult<Unit>>()
        fun next(): ApiResult<Unit> = results.removeFirstOrNull() ?: ApiResult.Success(Unit)
    }

    private fun kotlinx.coroutines.test.TestScope.writer(
        recorder: Recorder,
        owner: () -> AuthScopeSnapshot?,
    ) = DefaultSeriesTrackChoiceWriter(
        putSubtitle = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
        deleteSubtitle = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
        captureOwner = { owner() },
        scope = this,
    )

    @Test
    fun `a pick is put and auto deletes, for the profile that made them`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder) { profileA }
        val off = SeriesSubtitleChoice.Off

        writer.saveSubtitle("series-1", off)
        advanceUntilIdle()
        writer.saveSubtitle("series-1", null)
        advanceUntilIdle()

        assertEquals(
            listOf(Call("PUT", "series-1", off, profileA), Call("DELETE", "series-1", null, profileA)),
            recorder.calls,
        )
    }

    @Test
    fun `a failed write stays queued and is retried`() = runTest {
        val recorder = Recorder().apply { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        val writer = writer(recorder) { profileA }

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertEquals(2, recorder.calls.size, "the first attempt failed, the retry landed")
        assertTrue(writer.pendingSeries("profile-a").isEmpty())
    }

    @Test
    fun `a refused write is dropped rather than retried forever`() = runTest {
        val recorder = Recorder().apply { results += ApiResult.Error(400, "invalid", "bad mode") }
        val writer = writer(recorder) { profileA }

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertEquals(1, recorder.calls.size)
        assertTrue(writer.pendingSeries("profile-a").isEmpty())
    }

    @Test
    fun `a write keeps the profile it was made under after a switch`() = runTest {
        var active: AuthScopeSnapshot = profileA
        val recorder = Recorder().apply { results += ApiResult.Error(503, "unavailable", "busy") }
        val writer = writer(recorder) { active }

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        active = profileB
        writer.saveSubtitle("series-2", null)
        advanceUntilIdle()

        assertTrue(recorder.calls.filter { it.key == "series-1" }.all { it.owner == profileA })
        assertTrue(recorder.calls.filter { it.key == "series-2" }.all { it.owner == profileB })
    }

    @Test
    fun `a pick waiting to retry outranks the server's older choice when the item is reopened`() = runTest {
        // The server still answers with the previous choice (English); the
        // viewer's Spanish pick failed and waits for its retry.
        val recorder = Recorder().apply {
            repeat(10) { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        }
        val writer = writer(recorder) { profileA }
        val spanish = SeriesSubtitleChoice.of(
            org.siloserver.silo.model.playback.PlayerSubtitleInfo(
                index = 1, language = "es", codec = "subrip", label = "Spanish SRT", source = "embedded", url = "/s/1.vtt",
            ),
        )
        writer.saveSubtitle("series-1", spanish)
        runCurrent()

        val stale = org.siloserver.silo.model.catalog.WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
            effectiveSubtitleLanguage = "en", effectiveSubtitleMode = "always",
            effectiveSubtitleTrackSignature = org.siloserver.silo.model.playback.SubtitleTrackSignature(language = "en"),
        )
        val reconciled = writer.reconcile(stale)

        assertEquals("es", reconciled.effectiveSubtitleLanguage)
        assertEquals("always", reconciled.effectiveSubtitleMode)
        assertEquals(spanish.trackSignature, reconciled.effectiveSubtitleTrackSignature)
        // Another series is untouched.
        assertEquals(stale.copy(seriesId = "series-2"), writer.reconcile(stale.copy(seriesId = "series-2")))
    }

    @Test
    fun `a landed pick no longer overrides the server`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder) { profileA }
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertEquals(null, writer.pendingSubtitle("series-1"))
    }

    @Test
    fun `a pick that lands during the load's flush still applies to the detail read before it`() = runTest {
        // The first attempt failed; the load's flush sends the retry, and it
        // lands. The detail was read before that, so it still carries English.
        val recorder = Recorder().apply { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        val writer = writer(recorder) { profileA }
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        runCurrent()

        val stale = org.siloserver.silo.model.catalog.WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
            effectiveSubtitleLanguage = "en", effectiveSubtitleMode = "always",
        )
        val reconciled = writer.reconcile(stale)

        assertEquals(null, writer.pendingSubtitle("series-1"), "the retry landed")
        assertEquals("off", reconciled.effectiveSubtitleMode)
    }

    @Test
    fun `a flush that stops waiting lets the request in flight land`() = runTest {
        var puts = 0
        val writer = DefaultSeriesTrackChoiceWriter(
            putSubtitle = { _, _, _ ->
                puts++
                when (puts) {
                    1 -> ApiResult.NetworkError(IllegalStateException("offline"))
                    // The load's flush: slower than its 1.5 s budget.
                    2 -> { kotlinx.coroutines.delay(5_000); ApiResult.Success(Unit) }
                    else -> ApiResult.Success(Unit)
                }
            },
            deleteSubtitle = { _, _ -> ApiResult.Success(Unit) },
            captureOwner = { profileA },
            scope = this,
        )
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        runCurrent()

        val detail = org.siloserver.silo.model.catalog.WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
        )
        assertEquals("off", writer.reconcile(detail).effectiveSubtitleMode)
        assertEquals(SeriesSubtitleChoice.Off, writer.pendingSubtitle("series-1")?.choice, "still in flight")
        advanceUntilIdle()

        assertEquals(2, puts, "the slow request was not cancelled and sent again")
        assertEquals(null, writer.pendingSubtitle("series-1"))
    }

    @Test
    fun `two quick picks land in the order they were made`() = runTest {
        val recorder = Recorder()
        var slowOwnerLookups = 1
        val writer = DefaultSeriesTrackChoiceWriter(
            putSubtitle = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
            deleteSubtitle = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
            // The first pick's owner lookup is slow, so it resumes after the second.
            captureOwner = {
                if (slowOwnerLookups-- > 0) kotlinx.coroutines.delay(100)
                profileA
            },
            scope = this,
        )
        val english = SeriesSubtitleChoice(subtitleLanguage = "en", subtitleTrackIndex = 2, subtitleMode = "always")

        launch { writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off) }
        launch { writer.saveSubtitle("series-1", english) }
        advanceUntilIdle()

        assertEquals(listOf(english), recorder.calls.map { it.choice })
        assertEquals(null, writer.pendingSubtitle("series-1"))
    }

    @Test
    fun `a rotated profile token sends with the new one, and another profile waits`() = runTest {
        var active: AuthScopeSnapshot = profileA
        val recorder = Recorder()
        // A channel the test closes, so the writer's collector ends with it.
        val signals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val writer = DefaultSeriesTrackChoiceWriter(
            putSubtitle = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
            deleteSubtitle = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
            captureOwner = { active },
            scope = this,
            resumeSignals = signals.receiveAsFlow(),
        )
        val unlocked = profileA.copy(profileToken = "token-a2")

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off, owner = profileA)
        active = unlocked
        advanceUntilIdle()
        assertEquals(listOf(unlocked), recorder.calls.map { it.owner })

        writer.saveSubtitle("series-2", SeriesSubtitleChoice.Off, owner = unlocked)
        active = profileB
        advanceUntilIdle()
        assertEquals(1, recorder.calls.size, "nothing goes out under profile B")

        active = unlocked
        signals.send(Unit)
        advanceUntilIdle()
        assertEquals(listOf("series-1", "series-2"), recorder.calls.map { it.key })
        signals.close()
    }

    @Test
    fun `a pick out of automatic retries is sent again when the server is back`() = runTest {
        val recorder = Recorder().apply { repeat(6) { results += ApiResult.NetworkError(IllegalStateException("offline")) } }
        // A channel the test closes, so the writer's collector ends with it.
        val signals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val writer = DefaultSeriesTrackChoiceWriter(
            putSubtitle = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
            deleteSubtitle = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
            captureOwner = { profileA },
            scope = this,
            now = { testScheduler.currentTime },
            resumeSignals = signals.receiveAsFlow(),
        )
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()
        assertEquals(6, recorder.calls.size, "the first try and five retries failed")

        signals.send(Unit)
        advanceUntilIdle()

        assertEquals(7, recorder.calls.size)
        assertEquals(null, writer.pendingSubtitle("series-1"))
        signals.close()
    }

    @Test
    fun `a pending choice belongs to the profile that made it`() = runTest {
        var active: AuthScopeSnapshot = profileA
        val recorder = Recorder().apply {
            repeat(10) { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        }
        val writer = writer(recorder) { active }
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        runCurrent()

        active = profileB
        assertEquals(null, writer.pendingSubtitle("series-1"))
        active = profileA
        assertEquals(
            org.siloserver.silo.model.playback.PendingSeriesSubtitleChoice(SeriesSubtitleChoice.Off),
            writer.pendingSubtitle("series-1"),
        )
    }

    @Test
    fun `saving the same choice again while the first waits still settles once it lands`() = runTest {
        val recorder = Recorder().apply { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        val writer = writer(recorder) { profileA }

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        runCurrent()
        // The first attempt failed; the viewer picks Off again before the retry.
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertTrue(writer.pendingSeries("profile-a").isEmpty())
        assertEquals(null, writer.pendingSubtitle("series-1"), "a landed choice must not keep overriding the server")
    }

    @Test
    fun `pending choices follow a profile switch with no queue change`() = runTest {
        var active: AuthScopeSnapshot = profileA
        val transitions = kotlinx.coroutines.flow.MutableSharedFlow<org.siloserver.silo.network.IdentityTransition>(
            extraBufferCapacity = 8,
        )
        val recorder = Recorder().apply {
            repeat(10) { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        }
        val writer = DefaultSeriesTrackChoiceWriter(
            putSubtitle = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
            deleteSubtitle = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
            captureOwner = { active },
            scope = backgroundScope,
            identityChanges = transitions,
        )
        val seen = mutableListOf<Map<String, org.siloserver.silo.model.playback.PendingSeriesSubtitleChoice>>()
        backgroundScope.launch { writer.pendingSubtitleChoices.collect { seen += it } }
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        runCurrent()
        assertEquals(setOf("series-1"), seen.last().keys)

        fun transition(phase: org.siloserver.silo.network.IdentityTransitionPhase) =
            org.siloserver.silo.network.IdentityTransition(
                phase = phase,
                kind = org.siloserver.silo.network.IdentityTransitionKind.PROFILE_SWITCH,
                generation = 1,
            )
        transitions.emit(transition(org.siloserver.silo.network.IdentityTransitionPhase.WILL_CHANGE))
        runCurrent()
        assertTrue(seen.last().isEmpty(), "profile A's choice is withdrawn while switching")
        active = profileB
        transitions.emit(transition(org.siloserver.silo.network.IdentityTransitionPhase.DID_CHANGE))
        runCurrent()
        assertTrue(seen.last().isEmpty(), "profile B sees none of profile A's choices")
    }

    private data class AudioCall(val verb: String, val key: String, val choice: org.siloserver.silo.model.playback.SeriesAudioChoice?)

    private fun kotlinx.coroutines.test.TestScope.audioWriter(
        subtitles: Recorder,
        audio: MutableList<AudioCall>,
        audioResults: ArrayDeque<ApiResult<Unit>> = ArrayDeque(),
        owner: () -> AuthScopeSnapshot?,
    ) = DefaultSeriesTrackChoiceWriter(
        putSubtitle = { key, choice, o -> subtitles.calls += Call("PUT", key, choice, o); subtitles.next() },
        deleteSubtitle = { key, o -> subtitles.calls += Call("DELETE", key, null, o); subtitles.next() },
        putAudio = { key, choice, _ -> audio += AudioCall("PUT", key, choice); audioResults.removeFirstOrNull() ?: ApiResult.Success(Unit) },
        deleteAudio = { key, _ -> audio += AudioCall("DELETE", key, null); audioResults.removeFirstOrNull() ?: ApiResult.Success(Unit) },
        captureOwner = { owner() },
        scope = this,
        now = { testScheduler.currentTime },
    )

    private val japanese = org.siloserver.silo.model.catalog.AudioTrack(language = "jpn", codec = "aac", channels = 2)

    @Test
    fun `audio and subtitle choices for one series are separate, each latest-wins`() = runTest {
        val subtitles = Recorder().apply { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        val audio = mutableListOf<AudioCall>()
        val writer = audioWriter(subtitles, audio) { profileA }
        val japaneseChoice = org.siloserver.silo.model.playback.SeriesAudioChoice.of(japanese, 1)

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        writer.saveAudio("series-1", japaneseChoice)
        writer.saveAudio("series-1", null)
        advanceUntilIdle()

        // Audio Auto replaced the queued Japanese pick; the subtitle is a
        // separate write with its own retry.
        assertEquals(listOf(AudioCall("DELETE", "series-1", null)), audio)
        assertEquals(listOf("PUT", "PUT"), subtitles.calls.map { it.verb }, "the subtitle failed once, then landed")
        assertEquals(null, writer.pendingAudio("series-1"))
    }

    @Test
    fun `a pending pick survives the server's address changing`() = runTest {
        // Home network to remote address: same server id, new URL.
        var active = profileA
        val audio = mutableListOf<AudioCall>()
        val results = ArrayDeque<ApiResult<Unit>>().apply { repeat(10) { add(ApiResult.NetworkError(IllegalStateException("offline"))) } }
        val writer = audioWriter(Recorder(), audio, results) { active }
        val choice = org.siloserver.silo.model.playback.SeriesAudioChoice.of(japanese, 1)
        writer.saveAudio("series-1", choice)
        runCurrent()

        active = profileA.copy(serverUrl = "https://remote.example")
        assertEquals(org.siloserver.silo.model.playback.PendingSeriesAudioChoice(choice), writer.pendingAudio("series-1"))
        active = profileA.copy(serverId = "other-server")
        assertEquals(null, writer.pendingAudio("series-1"))
    }

    @Test
    fun `a pick made under another profile or server is not saved`() = runTest {
        // A download playing for profile B while profile A is active.
        val audio = mutableListOf<AudioCall>()
        val subtitles = Recorder()
        val writer = audioWriter(subtitles, audio) { profileA }

        writer.saveAudio("series-1", org.siloserver.silo.model.playback.SeriesAudioChoice.of(japanese, 1), owner = profileB)
        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off, owner = profileA.copy(serverId = "other"))
        writer.saveSubtitle("series-2", SeriesSubtitleChoice.Off, owner = profileA.copy(serverUrl = "https://remote.example"))
        advanceUntilIdle()

        assertTrue(audio.isEmpty())
        assertEquals(listOf("series-2"), subtitles.calls.map { it.key }, "only the playback owned by the active profile saves")
    }

    @Test
    fun `a pick that never landed expires instead of overwriting a newer choice later`() = runTest {
        val audio = mutableListOf<AudioCall>()
        val results = ArrayDeque<ApiResult<Unit>>().apply { repeat(20) { add(ApiResult.NetworkError(IllegalStateException("offline"))) } }
        val writer = audioWriter(Recorder(), audio, results) { profileA }
        writer.saveAudio("series-1", org.siloserver.silo.model.playback.SeriesAudioChoice.of(japanese, 1))
        advanceUntilIdle()
        val attempts = audio.size
        assertTrue(writer.pendingAudio("series-1") != null, "still waiting after the automatic retries")

        // Long after, another device has chosen differently; reopening an
        // item flushes the queue, and the stale pick must not go out.
        testScheduler.advanceTimeBy(11 * 60_000L)
        results.clear()
        writer.flushNow()

        assertEquals(attempts, audio.size)
        assertEquals(null, writer.pendingAudio("series-1"))
        assertTrue(writer.pendingSeries("profile-a").isEmpty())
    }

    @Test
    fun `an unsent audio pick is laid over the effective track on reconcile`() = runTest {
        val audio = mutableListOf<AudioCall>()
        val results = ArrayDeque<ApiResult<Unit>>().apply { repeat(10) { add(ApiResult.NetworkError(IllegalStateException("offline"))) } }
        val writer = audioWriter(Recorder(), audio, results) { profileA }
        writer.saveAudio("series-1", org.siloserver.silo.model.playback.SeriesAudioChoice.of(japanese, 1))
        runCurrent()

        val english = org.siloserver.silo.model.catalog.AudioTrack(language = "eng", codec = "eac3", channels = 6)
        val stale = org.siloserver.silo.model.catalog.WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
            versions = listOf(
                org.siloserver.silo.model.catalog.FileVersion(fileId = 9, audioTracks = listOf(japanese, english), effectiveAudioTrackIndex = 1),
            ),
        )
        assertEquals(0, writer.reconcile(stale).versions.single().effectiveAudioTrackIndex)
    }

    @Test
    fun `auto set on this device is remembered until the next subtitle pick`() = runTest {
        val resets = SeriesAutoResetStore.InMemory()
        val recorder = Recorder()
        val writer = DefaultSeriesTrackChoiceWriter(
            putSubtitle = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
            deleteSubtitle = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
            captureOwner = { profileA },
            scope = this,
            autoResets = resets,
        )
        writer.saveSubtitle("series-1", null)
        advanceUntilIdle()
        assertTrue(writer.subtitleResetToAuto("series-1"))
        assertEquals(false, writer.subtitleResetToAuto("series-2"))

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()
        assertEquals(false, writer.subtitleResetToAuto("series-1"))
    }

    @Test
    fun `nothing is sent without an active profile`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder) { null }

        writer.saveSubtitle("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertTrue(recorder.calls.isEmpty())
    }
}
