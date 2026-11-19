package org.siloserver.silo.playback.theme

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
class ThemeMusicCoordinatorTest {

    private class Audio(var loop: Boolean) : ThemeAudio {
        override var volume = 0f
        var playing = false
        var released = false
        override fun setLooping(loop: Boolean) { this.loop = loop }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun release() { released = true; playing = false }
    }

    private class Harness(scope: TestScope) {
        val grants = mutableListOf<String>()
        val audios = mutableListOf<Audio>()
        var available: Boolean? = true
        var capabilityReads = 0
        var otherAudio = false
        var capabilityGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val coordinator = ThemeMusicCoordinator(
            scope = scope.backgroundScope,
            player = ThemeMusicPlayer(
                scope = scope.backgroundScope,
                grant = { owner, theme -> grants += "$owner/$theme"; ThemeGrant("https://server/$theme", converted = false) },
                createAudio = { _, loop, _ -> Audio(loop).also { audios += it } },
            ),
            capability = {
                capabilityReads += 1
                // A read that does not observe cancellation, as a blocking client might.
                capabilityGate?.let { gate -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() } }
                available
            },
            otherAudioActive = { otherAudio },
            timeSource = scope.testScheduler.timeSource,
        )
        val playing get() = audios.lastOrNull()?.let { it.playing && !it.released } ?: false
    }

    private val heat = ThemeSelection("movie:heat", listOf(ThemeTrack("7")))
    private val series = ThemeSelection("series:1", listOf(ThemeTrack("3")))

    @Test
    fun playsOnlyWhileEnabled() = runTest {
        val h = Harness(this)
        val page = Any()
        h.coordinator.show(page, heat)
        settle()
        assertTrue(h.grants.isEmpty(), "off by default")
        assertEquals(0, h.capabilityReads, "a disabled setting asks the server nothing")

        h.coordinator.setPreferences(enabled = true, loop = false)
        runCurrent()
        assertEquals(listOf("movie:heat/7"), h.grants)
        assertTrue(h.playing)

        h.coordinator.setPreferences(enabled = false, loop = false)
        settle()
        assertTrue(h.audios.last().released)
    }

    @Test
    fun anUnavailableCapabilityPlaysNothing() = runTest {
        val h = Harness(this)
        h.available = false
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        settle()
        assertTrue(h.grants.isEmpty())
        h.coordinator.show(Any(), series)
        settle()
        assertEquals(1, h.capabilityReads, "a definitive answer is kept for the session")
    }

    @Test
    fun aFailedCapabilityReadIsRetriedOnTheNextPage() = runTest {
        val h = Harness(this)
        h.available = null
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        settle()
        assertTrue(h.grants.isEmpty())
        h.available = true
        h.coordinator.show(Any(), heat)
        runCurrent()
        assertEquals(2, h.capabilityReads)
        assertEquals(listOf("movie:heat/7"), h.grants)
    }

    @Test
    fun theLoopPreferenceAppliesWhilePlaying() = runTest {
        val h = Harness(this)
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        runCurrent()
        assertFalse(h.audios.last().loop)
        h.coordinator.setPreferences(enabled = true, loop = true)
        assertTrue(h.audios.last().loop)
        assertEquals(1, h.grants.size)
    }

    @Test
    fun leavingAPageFadesOutAndStopsAfterTheGracePeriod() = runTest {
        val h = Harness(this)
        val page = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(page, heat)
        runCurrent()
        h.coordinator.hide(page)
        advanceTimeBy(ThemeMusicCoordinator.RELEASE_GRACE_MILLIS / 2)
        assertFalse(h.audios.last().playing, "paused at once")
        assertFalse(h.audios.last().released)
        settle()
        assertTrue(h.audios.last().released)
    }

    @Test
    fun theNextPageWithTheSameThemeContinuesIt() = runTest {
        val h = Harness(this)
        val seriesPage = Any()
        val seasonPage = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(seriesPage, series)
        runCurrent()
        // The season inherits its series' set.
        h.coordinator.show(seasonPage, series)
        h.coordinator.hide(seriesPage)
        settle()
        assertEquals(1, h.grants.size)
        assertTrue(h.playing)
    }

    @Test
    fun returningWithinTheGracePeriodResumesInPlace() = runTest {
        val h = Harness(this)
        val page = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(page, heat)
        runCurrent()
        h.coordinator.hide(page)
        runCurrent()
        h.coordinator.show(page, heat)
        settle()
        assertEquals(1, h.grants.size)
        assertTrue(h.playing)
    }

    @Test
    fun otherAudioKeepsAThemeFromStarting() = runTest {
        val h = Harness(this)
        h.otherAudio = true
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        settle()
        assertTrue(h.grants.isEmpty())
    }

    @Test
    fun ourOwnThemeDoesNotCountAsOtherAudio() = runTest {
        val h = Harness(this)
        val page = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(page, heat)
        runCurrent()
        h.otherAudio = true // the device now reports our theme as active music
        h.coordinator.show(page, heat)
        h.coordinator.setPreferences(enabled = true, loop = true)
        settle()
        assertTrue(h.playing)
    }

    @Test
    fun playbackStartingEndsAPausedThemeSoReturningStartsOver() = runTest {
        val h = Harness(this)
        val page = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(page, heat)
        runCurrent()
        h.coordinator.hide(page) // the player route covers the page
        advanceTimeBy(500)
        h.coordinator.interrupt() // the video session starts
        assertTrue(h.audios.last().released)
        h.coordinator.show(page, heat) // back out of the player at once
        runCurrent()
        assertEquals(2, h.grants.size)
    }

    @Test
    fun ourOwnFadingThemeDoesNotBlockTheNextOne() = runTest {
        val h = Harness(this)
        val moviePage = Any()
        val seriesPage = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(moviePage, heat)
        runCurrent()
        h.otherAudio = true // the device reports the movie's theme, still fading out
        h.coordinator.hide(moviePage)
        h.coordinator.show(seriesPage, series)
        settle()
        assertEquals(listOf("movie:heat/7", "series:1/3"), h.grants)
        assertTrue(h.playing)
    }

    @Test
    fun aPausedThemeDoesNotResumeOverMusicStartedMeanwhile() = runTest {
        val h = Harness(this)
        val page = Any()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(page, heat)
        runCurrent()
        h.coordinator.hide(page)
        advanceTimeBy(ThemeMusicCoordinator.RELEASE_GRACE_MILLIS / 2)
        h.otherAudio = true // the user started music from the notification shade
        h.coordinator.show(page, heat)
        settle()
        assertFalse(h.playing)
    }

    @Test
    fun theCapabilityIsReadAgainOnceStale() = runTest {
        val h = Harness(this)
        h.available = false
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        settle()
        assertTrue(h.grants.isEmpty())
        h.available = true // the admin turned themes on
        advanceTimeBy(ThemeMusicCoordinator.CAPABILITY_TTL_MILLIS)
        h.coordinator.show(Any(), heat)
        settle()
        assertEquals(2, h.capabilityReads)
        assertEquals(listOf("movie:heat/7"), h.grants)
    }

    @Test
    fun aCapabilityAnswerForThePreviousIdentityIsDiscarded() = runTest {
        val h = Harness(this)
        h.capabilityGate = kotlinx.coroutines.CompletableDeferred()
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        runCurrent()
        h.coordinator.reset() // the profile changed while the read was in flight
        h.capabilityGate!!.complete(Unit)
        h.capabilityGate = null
        runCurrent()
        assertTrue(h.grants.isEmpty(), "the previous identity's answer must not start a theme")
        h.coordinator.show(Any(), heat)
        runCurrent()
        assertEquals(2, h.capabilityReads, "the new identity asks again")
    }

    @Test
    fun resetStopsAndAsksTheNextIdentityAgain() = runTest {
        val h = Harness(this)
        h.coordinator.setPreferences(enabled = true, loop = false)
        h.coordinator.show(Any(), heat)
        runCurrent()
        h.coordinator.reset()
        assertTrue(h.audios.last().released)
        h.coordinator.show(Any(), heat)
        runCurrent()
        assertEquals(2, h.capabilityReads)
    }
}

/** Runs every fade, grant and grace delay, including the player's background work. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(10_000)
    runCurrent()
}
