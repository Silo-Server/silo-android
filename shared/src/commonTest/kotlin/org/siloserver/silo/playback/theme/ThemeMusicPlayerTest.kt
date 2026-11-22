package org.siloserver.silo.playback.theme

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.ThemeSelection
import org.siloserver.silo.model.catalog.ThemeTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeMusicPlayerTest {

    private class FakeAudio(val url: String, var loop: Boolean) : ThemeAudio {
        override var volume: Float = 1f
        var listener: ThemeAudio.Listener? = null
        var playing = false
        var released = false
        override fun setLooping(loop: Boolean) { this.loop = loop }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun release() { released = true; playing = false }
        fun started() = listener!!.onPlaying()
        fun ended() = listener!!.onEnded()
        fun failed() = listener!!.onError()
        fun interrupted() = listener!!.onInterrupted()
    }

    private class Harness(scope: TestScope) {
        val grants = mutableListOf<Pair<String, String>>()
        val audios = mutableListOf<FakeAudio>()
        var converted = false
        var refuse = false
        var gate: CompletableDeferred<Unit>? = null
        val player = ThemeMusicPlayer(
            scope = scope.backgroundScope,
            grant = { owner, theme ->
                grants += owner to theme
                gate?.await()
                if (refuse) null else ThemeGrant("https://server/$owner/$theme?token=${grants.size}", converted)
            },
            createAudio = { url, loop, listener ->
                FakeAudio(url, loop).also { it.listener = listener; audios += it }
            },
        )
        val audio get() = audios.last()
    }

    private fun selection(owner: String, vararg themes: String) =
        ThemeSelection(owner, themes.map { ThemeTrack(id = it) })

    @Test
    fun playsTheFirstThemeAndFadesIn() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7", "8"), loop = false)
        runCurrent()
        assertEquals(listOf("movie:1" to "7"), h.grants)
        assertTrue(h.audio.playing)
        assertEquals(0f, h.audio.volume)
        h.audio.started()
        settle()
        assertEquals(ThemeMusicPlayer.TARGET_VOLUME, h.audio.volume, 0.001f)
    }

    @Test
    fun anEmptySetPlaysNothing() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1"), loop = false)
        h.player.select(null, loop = false)
        settle()
        assertTrue(h.grants.isEmpty())
    }

    @Test
    fun loopRepeatsAnOriginalInPlace() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = true)
        runCurrent()
        assertTrue(h.audio.loop)
        h.player.select(selection("movie:1", "7"), loop = false)
        assertFalse(h.audio.loop)
        assertEquals(1, h.grants.size)
    }

    @Test
    fun aConvertedLoopReplaysWithAFreshGrant() = runTest {
        val h = Harness(this)
        h.converted = true
        h.player.select(selection("movie:1", "7"), loop = true)
        runCurrent()
        val first = h.audio
        assertFalse(first.loop, "a converted stream cannot seek back, so it never loops in place")
        first.started()
        first.ended()
        runCurrent()
        assertEquals(2, h.grants.size)
        assertTrue(first.released)
        assertTrue(h.audio !== first && h.audio.playing)
        assertTrue(h.audio.url.endsWith("token=2"))
    }

    @Test
    fun aConvertedThemeWithoutLoopStopsAtItsEnd() = runTest {
        val h = Harness(this)
        h.converted = true
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.audio.started()
        h.audio.ended()
        runCurrent()
        assertEquals(1, h.grants.size)
    }

    @Test
    fun anErrorRetriesOnceWithANewGrant() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.audio.failed()
        runCurrent()
        assertEquals(2, h.grants.size)
        h.audio.failed()
        runCurrent()
        assertEquals(2, h.grants.size, "a second failure before anything played gives up")
    }

    @Test
    fun aConvertedLoopThatNeverPlaysCannotReplayForever() = runTest {
        val h = Harness(this)
        h.converted = true
        h.player.select(selection("movie:1", "7"), loop = true)
        runCurrent()
        h.audio.ended()
        runCurrent()
        h.audio.ended()
        runCurrent()
        assertEquals(2, h.grants.size)
    }

    @Test
    fun theSameThemeOnAnotherPageKeepsPlaying() = runTest {
        val h = Harness(this)
        h.player.select(selection("series:1", "7"), loop = false)
        runCurrent()
        val audio = h.audio
        h.player.select(selection("series:1", "7"), loop = false)
        runCurrent()
        assertEquals(1, h.grants.size)
        assertTrue(audio.playing && !audio.released)
    }

    @Test
    fun anotherOwnerReplacesTheTheme() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        val first = h.audio
        first.started()
        settle()
        h.player.select(selection("movie:2", "9"), loop = false)
        settle()
        assertTrue(first.released)
        assertEquals("movie:2" to "9", h.grants.last())
    }

    @Test
    fun stopFadesOutAndReleases() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.audio.started()
        settle()
        h.player.stop()
        runCurrent()
        assertFalse(h.audio.released, "fades out first")
        settle()
        assertTrue(h.audio.released)
        assertEquals(0f, h.audio.volume)
    }

    @Test
    fun anImmediateStopAlsoReleasesAThemeStillFadingOut() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.audio.started()
        settle()
        h.player.stop() // fading out
        runCurrent()
        assertFalse(h.audio.released)
        h.player.stop(immediate = true) // playback starts during the fade
        runCurrent()
        assertTrue(h.audio.released)
        assertFalse(h.player.isAudible)
    }

    @Test
    fun anImmediateStopReleasesAtOnce() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.player.stop(immediate = true)
        assertTrue(h.audio.released)
    }

    @Test
    fun aGrantThatLandsAfterStopIsDropped() = runTest {
        val h = Harness(this)
        h.gate = CompletableDeferred()
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.player.stop()
        h.gate!!.complete(Unit)
        settle()
        assertTrue(h.audios.isEmpty())
    }

    @Test
    fun aRefusedGrantPlaysNothing() = runTest {
        val h = Harness(this)
        h.refuse = true
        h.player.select(selection("movie:1", "7"), loop = false)
        settle()
        assertTrue(h.audios.isEmpty())
    }

    @Test
    fun suspendPausesAndTheSameSelectionResumesInPlace() = runTest {
        val h = Harness(this)
        h.player.select(selection("series:1", "7"), loop = false)
        runCurrent()
        val audio = h.audio
        audio.started()
        settle()
        h.player.suspend()
        settle()
        assertFalse(audio.playing)
        assertFalse(audio.released)
        h.player.select(selection("series:1", "7"), loop = false)
        runCurrent()
        assertTrue(audio.playing)
        audio.started()
        settle()
        assertEquals(1, h.grants.size)
        assertEquals(ThemeMusicPlayer.TARGET_VOLUME, audio.volume, 0.001f)
    }

    @Test
    fun selectingAgainDuringASuspendFadeFadesBackIn() = runTest {
        val h = Harness(this)
        h.player.select(selection("series:1", "7"), loop = false)
        runCurrent()
        h.audio.started()
        settle()
        h.player.suspend()
        runCurrent()
        h.player.select(selection("series:1", "7"), loop = false)
        settle()
        assertTrue(h.audio.playing)
        assertEquals(ThemeMusicPlayer.TARGET_VOLUME, h.audio.volume, 0.001f)
    }

    @Test
    fun aGrantThatLandsWhileSuspendedWaitsForTheNextSelect() = runTest {
        val h = Harness(this)
        h.gate = CompletableDeferred()
        h.player.select(selection("series:1", "7"), loop = false)
        runCurrent()
        h.player.suspend()
        h.gate!!.complete(Unit)
        settle()
        assertFalse(h.audios.lastOrNull()?.playing ?: false)
        h.player.select(selection("series:1", "7"), loop = false)
        settle()
        assertTrue(h.audio.playing)
    }

    @Test
    fun losingAudioFocusStopsAtOnceAndTheNextSelectStartsOver() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.audio.started()
        h.audio.interrupted() // a video or another app took audio focus
        assertTrue(h.audio.released)
        assertFalse(h.player.isAudible)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        assertEquals(2, h.grants.size)
    }

    @Test
    fun aFadingOutThemeStillCountsAsAudible() = runTest {
        val h = Harness(this)
        h.player.select(selection("movie:1", "7"), loop = false)
        runCurrent()
        h.audio.started()
        settle()
        h.player.stop()
        runCurrent()
        assertTrue(h.player.isAudible)
        settle()
        assertFalse(h.player.isAudible)
    }

    @Test
    fun grantsNeverAppearInToString() {
        assertFalse("secret" in ThemeGrant("https://x/?token=secret", converted = false).toString())
    }
}

/** Runs every fade, grant and grace delay, including the player's background work. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(10_000)
    runCurrent()
}
