package org.siloserver.silo.common.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `nothing is sent without an active profile`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder) { null }

        writer.save("series-1", SeriesSubtitleChoice.Off)
        advanceUntilIdle()

        assertTrue(recorder.calls.isEmpty())
    }
}
