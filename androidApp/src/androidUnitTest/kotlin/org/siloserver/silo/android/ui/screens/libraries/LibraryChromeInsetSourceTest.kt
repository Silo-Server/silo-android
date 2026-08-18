package org.siloserver.silo.android.ui.screens.libraries

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryChromeInsetSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private val libraries = source(
        "org/siloserver/silo/android/ui/screens/libraries/LibrariesScreen.kt",
    )
    private val catalogGrid = source(
        "org/siloserver/silo/android/ui/screens/browse/CatalogGrid.kt",
    )

    /**
     * The chrome floats over the viewport (composed after it, so it draws on
     * top and reads the viewport as its blur source) and every tab clears the
     * chrome's *measured* height rather than a hard-coded runway.
     */
    @Test
    fun sharedChromeOwnsReservedSpaceBeforeEveryLibraryTab() {
        val viewport = libraries.indexOf("LibraryContentViewport(")
        val chrome = libraries.indexOf("LibrariesFloatingChrome(", viewport)
        assertTrue(viewport >= 0)
        assertTrue(chrome > viewport)
        assertTrue(libraries.contains(".hazeSource(chromeHaze)"))
        assertTrue(libraries.contains(".clipToBounds()"))
        assertTrue(libraries.contains("onSizeChanged { chromeHeightPx = it.height }"))
        // Each subtab receives the measured inset.
        assertTrue(Regex("RecommendedTabContent\\([\\s\\S]*?topInset = topInset").containsMatchIn(libraries))
        assertTrue(Regex("BrowseTabContent\\([\\s\\S]*?topInset = topInset").containsMatchIn(libraries))
        assertTrue(Regex("CollectionsTabContent\\([\\s\\S]*?topInset = topInset").containsMatchIn(libraries))
    }

    @Test
    fun tabsDoNotCarryOverlayClearanceRunways() {
        assertFalse(libraries.contains("LibrariesChromeContentHeight"))
        assertFalse(libraries.contains("extraTopInset = 50.dp"))
        assertFalse(libraries.contains(".windowInsetsPadding(WindowInsets.statusBars)"))
    }

    @Test
    fun browseCatalogAndAlphabetRailReserveMeasuredBottomChromeInset() {
        assertTrue(libraries.contains("bottomContentInset = LocalBottomChromeInset.current"))
        assertTrue(libraries.contains("topContentInset = topInset"))
        assertTrue(catalogGrid.contains("bottomContentInset: Dp = 0.dp"))
        assertTrue(catalogGrid.contains("bottom = 8.dp + bottomContentInset"))
        // The letter index keeps clear of both the floating chrome and the
        // bottom pill.
        assertTrue(catalogGrid.contains(".padding(top = topContentInset + 8.dp, bottom = bottomContentInset + 8.dp)"))
    }
}
