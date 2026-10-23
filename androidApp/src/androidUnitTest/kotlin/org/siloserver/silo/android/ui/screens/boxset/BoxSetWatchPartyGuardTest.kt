package org.siloserver.silo.android.ui.screens.boxset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D5 call-site guard: the box set's Play starts a movie solo, so while this
 * device is in a Watch Party it must ask to leave the party first, as a
 * movie's own page does. Comments are stripped so prose cannot satisfy it.
 */
class BoxSetWatchPartyGuardTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/boxset/BoxSetScreen.kt",
    ).readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""\s+"""), " ")

    @Test
    fun playGoesThroughTheWatchPartyGuard() {
        assertTrue("val soloGuard = rememberWatchPartySoloGuard()" in source)
        assertTrue("soloGuard.run { onPlayMovie(contentId, resumePositionSeconds) }" in source)
        assertTrue("WatchPartySoloGuardDialog(soloGuard)" in source)
        // The content gets the guarded Play, never the screen's raw callback.
        assertEquals(1, Regex("""onPlayMovie = guardedPlayMovie""").findAll(source).count())
        assertEquals(0, Regex("""onPlayMovie = onPlayMovie""").findAll(source).count())
    }
}
