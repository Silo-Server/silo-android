package org.siloserver.silo.common.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SeriesSubtitleChoiceWriterTest {

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
    ) = DefaultSeriesSubtitleChoiceWriter(
        put = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
        delete = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
        captureOwner = { owner() },
        scope = this,
    )

    @Test
    fun `a pick is put and auto deletes, for the profile that made them`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder) { profileA }
        val off = SeriesSubtitleChoice.Off

        writer.save("series-1", off)
        advanceUntilIdle()
        writer.save("series-1", null)
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

        writer.save("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertEquals(2, recorder.calls.size, "the first attempt failed, the retry landed")
        assertTrue(writer.pendingSeries("profile-a").isEmpty())
    }

    @Test
    fun `a refused write is dropped rather than retried forever`() = runTest {
        val recorder = Recorder().apply { results += ApiResult.Error(400, "invalid", "bad mode") }
        val writer = writer(recorder) { profileA }

        writer.save("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertEquals(1, recorder.calls.size)
        assertTrue(writer.pendingSeries("profile-a").isEmpty())
    }

    @Test
    fun `a write keeps the profile it was made under after a switch`() = runTest {
        var active: AuthScopeSnapshot = profileA
        val recorder = Recorder().apply { results += ApiResult.Error(503, "unavailable", "busy") }
        val writer = writer(recorder) { active }

        writer.save("series-1", SeriesSubtitleChoice.Off)
        active = profileB
        writer.save("series-2", null)
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
        writer.save("series-1", spanish)
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
        writer.save("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertEquals(null, writer.pendingChoice("series-1"))
    }

    @Test
    fun `a pick that lands during the load's flush still applies to the detail read before it`() = runTest {
        // The first attempt failed; the load's flush sends the retry, and it
        // lands. The detail was read before that, so it still carries English.
        val recorder = Recorder().apply { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        val writer = writer(recorder) { profileA }
        writer.save("series-1", SeriesSubtitleChoice.Off)
        runCurrent()

        val stale = org.siloserver.silo.model.catalog.WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
            effectiveSubtitleLanguage = "en", effectiveSubtitleMode = "always",
        )
        val reconciled = writer.reconcile(stale)

        assertEquals(null, writer.pendingChoice("series-1"), "the retry landed")
        assertEquals("off", reconciled.effectiveSubtitleMode)
    }

    @Test
    fun `a flush cut short by the load timeout keeps the pick queued`() = runTest {
        var puts = 0
        val writer = DefaultSeriesSubtitleChoiceWriter(
            put = { _, _, _ ->
                puts++
                when (puts) {
                    1 -> ApiResult.NetworkError(IllegalStateException("offline"))
                    // The load's flush: slower than its 1.5 s budget.
                    2 -> { kotlinx.coroutines.delay(5_000); ApiResult.Success(Unit) }
                    else -> ApiResult.Success(Unit)
                }
            },
            delete = { _, _ -> ApiResult.Success(Unit) },
            captureOwner = { profileA },
            scope = this,
        )
        writer.save("series-1", SeriesSubtitleChoice.Off)
        runCurrent()

        val detail = org.siloserver.silo.model.catalog.WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
        )
        writer.reconcile(detail)
        assertEquals(setOf("series-1"), writer.pendingSeries("profile-a"), "the cut-short send is queued again")
        advanceUntilIdle()

        assertEquals(3, puts)
        assertEquals(null, writer.pendingChoice("series-1"))
    }

    @Test
    fun `a pending choice belongs to the profile that made it`() = runTest {
        var active: AuthScopeSnapshot = profileA
        val recorder = Recorder().apply {
            repeat(10) { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        }
        val writer = writer(recorder) { active }
        writer.save("series-1", SeriesSubtitleChoice.Off)
        runCurrent()

        active = profileB
        assertEquals(null, writer.pendingChoice("series-1"))
        active = profileA
        assertEquals(
            org.siloserver.silo.model.playback.PendingSeriesSubtitleChoice(SeriesSubtitleChoice.Off),
            writer.pendingChoice("series-1"),
        )
    }

    @Test
    fun `saving the same choice again while the first waits still settles once it lands`() = runTest {
        val recorder = Recorder().apply { results += ApiResult.NetworkError(IllegalStateException("offline")) }
        val writer = writer(recorder) { profileA }

        writer.save("series-1", SeriesSubtitleChoice.Off)
        runCurrent()
        // The first attempt failed; the viewer picks Off again before the retry.
        writer.save("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertTrue(writer.pendingSeries("profile-a").isEmpty())
        assertEquals(null, writer.pendingChoice("series-1"), "a landed choice must not keep overriding the server")
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
        val writer = DefaultSeriesSubtitleChoiceWriter(
            put = { key, choice, o -> recorder.calls += Call("PUT", key, choice, o); recorder.next() },
            delete = { key, o -> recorder.calls += Call("DELETE", key, null, o); recorder.next() },
            captureOwner = { active },
            scope = backgroundScope,
            identityChanges = transitions,
        )
        val seen = mutableListOf<Map<String, org.siloserver.silo.model.playback.PendingSeriesSubtitleChoice>>()
        backgroundScope.launch { writer.pendingChoices.collect { seen += it } }
        writer.save("series-1", SeriesSubtitleChoice.Off)
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

    @Test
    fun `nothing is sent without an active profile`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder) { null }

        writer.save("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertTrue(recorder.calls.isEmpty())
    }
}
