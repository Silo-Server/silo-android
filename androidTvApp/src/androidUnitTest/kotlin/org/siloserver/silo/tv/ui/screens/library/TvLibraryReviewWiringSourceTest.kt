package org.siloserver.silo.tv.ui.screens.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvLibraryReviewWiringSourceTest {
    private val detailScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/library/TvLibraryDetailScreen.kt",
    ).readText()
    private val mainShell = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()

    @Test
    fun alphabetAndCalendarForwardContentUpFallbackToTheShell() {
        val alphabetTab = extractBetween(
            source = detailScreen,
            startAnchor = "TvLibraryTab.Alphabet -> LibraryTab(",
            endAnchor = "TvLibraryTab.RecentlyAdded ->",
        )
        val calendarScreen = extractBetween(
            source = mainShell,
            startAnchor = "TvCalendarScreen(",
            endAnchor = "shellComposable(TvMainRoute.Browse.route)",
        )

        assertTrue(alphabetTab.contains("onContentUpFallbackChanged = onContentUpFallbackChanged"))
        assertTrue(calendarScreen.contains("onContentUpFallbackChanged = onContentUpFallback"))
    }

    private fun extractBetween(source: String, startAnchor: String, endAnchor: String): String {
        val start = source.indexOf(startAnchor)
        assertTrue(start >= 0, "Missing start anchor: $startAnchor")

        val contentStart = start + startAnchor.length
        val end = source.indexOf(endAnchor, contentStart)
        assertTrue(end >= 0, "Missing end anchor: $endAnchor")

        return source.substring(contentStart, end)
    }
}
