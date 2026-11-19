package org.siloserver.silo.tv.ui.screens.boxset

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D5 call-site guard: the box set's Play starts a movie solo, so while this TV
 * is in a Watch Party it must ask to leave the party first, as a movie's own
 * page does. Comments are stripped so prose cannot satisfy it.
 */
class TvBoxSetWatchPartyGuardTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/boxset/TvBoxSetScreen.kt",
    ).readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""\s+"""), " ")

    @Test
    fun playGoesThroughTheWatchPartyGuard() {
        assertTrue("val watchPartyPlayGuard = rememberTvWatchPartyPlayGuard()" in source)
        assertTrue("watchPartyPlayGuard.requestPlay { onPlayMovie(contentId, resumePositionSeconds) }" in source)
        assertTrue("TvWatchPartyPlayGuardDialog(watchPartyPlayGuard)" in source)
        // The content gets the guarded Play, never the screen's raw callback.
        assertTrue("BoxSetContent(detail, viewModel, guardedPlayMovie, onOpenMovie)" in source)
    }
}
