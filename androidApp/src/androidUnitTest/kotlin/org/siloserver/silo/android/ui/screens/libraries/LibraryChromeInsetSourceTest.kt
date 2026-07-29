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
    private val carousel = source(
        "org/siloserver/silo/android/ui/screens/home/FeaturedCarousel.kt",
    )
    private val catalogGrid = source(
        "org/siloserver/silo/android/ui/screens/browse/CatalogGrid.kt",
    )

    @Test
    fun sharedChromeOwnsReservedSpaceBeforeEveryLibraryTab() {
        val chrome = libraries.indexOf("LibrariesFloatingChrome(")
        val viewport = libraries.indexOf("LibraryContentViewport(")
        assertTrue(chrome >= 0)
        assertTrue(viewport > chrome)
        assertTrue(libraries.contains("Modifier.weight(1f).clipToBounds()"))
    }

    @Test
    fun tabsDoNotCarryOverlayClearanceRunways() {
        assertFalse(libraries.contains("LibrariesChromeContentHeight"))
        assertFalse(libraries.contains("extraTopInset = 50.dp"))
        assertFalse(libraries.contains(".windowInsetsPadding(WindowInsets.statusBars)"))
        assertFalse(carousel.contains("WindowInsets.statusBars"))
        assertTrue(carousel.contains("topInset: androidx.compose.ui.unit.Dp = 16.dp"))
    }

    @Test
    fun browseCatalogAndAlphabetRailReserveMeasuredBottomChromeInset() {
        assertTrue(libraries.contains("bottomContentInset = LocalBottomChromeInset.current"))
        assertTrue(catalogGrid.contains("bottomContentInset: Dp = 0.dp"))
        assertTrue(catalogGrid.contains("bottom = 8.dp + bottomContentInset"))
        assertTrue(catalogGrid.contains(".padding(bottom = bottomContentInset)"))
    }
}
