package org.siloserver.silo.tv.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvUsabilityGuardTest {
    private val tvSourceRoot = File("src/androidMain/kotlin/org/siloserver/silo/tv")

    @Test
    fun tvDoesNotExposeReadingSurfacesOrEbooks() {
        val forbiddenSurfacePattern = Regex("""\b(Ebooks?|Reading|Reader)\b""")
        val allowedFiles = setOf(
            "ui/util/TvMediaTypeFilters.kt",
            "ui/navigation/TvRoute.kt",
        )
        val offenders = tvSourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                val relative = file.relativeTo(tvSourceRoot).invariantSeparatorsPath
                relative in allowedFiles ||
                    relative.startsWith("ui/screens/admin/") ||
                    relative.startsWith("ui/screens/auth/")
            }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbiddenSurfacePattern.containsMatchIn(line)) {
                        "${file.relativeTo(tvSourceRoot).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "TV should not expose reading/ebook UI surfaces:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun secondaryUtilityScreensStayOutOfTheTopTabBar() {
        val destinations = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMediaDestinations.kt",
        ).readText()

        // Skyline content-type-first roots: Home, per-type tabs, For You,
        // Calendar (tvOS and Roku parity). Search sits left of the tabs, and
        // Requests and the other utility surfaces live in the profile menu.
        assertTrue(destinations.contains("TvRootDestination.Home"))
        assertTrue(destinations.contains("TvRootDestination.LibraryType"))
        assertTrue(destinations.contains("TvRootDestination.ForYou"))
        assertTrue(destinations.contains("TvRootDestination.Calendar"))
        assertFalse(destinations.contains("TvRootDestination.Requests"))
        assertFalse(destinations.contains("TvRootDestination.Search"))
        assertFalse(destinations.contains("TvRootDestination.Settings"))

        val shell = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
        ).readText()
        assertTrue(shell.contains("ProfileDropdownRow(label = \"Requests\""))
    }

    @Test
    fun profileMenuIsModalAndDismissible() {
        val shell = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
        ).readText()

        // The TvProfileDropdown is "modal" via a focus trap (arrows can't leak
        // out; only Back closes) on its own frosted panel chrome — no longer a
        // full-screen scrim — and is Back/Escape dismissible with a Sign Out row.
        assertTrue(shell.contains("tvSkylinePanelChrome()"))
        assertTrue(shell.contains("exit = { FocusRequester.Cancel }"))
        assertTrue(shell.contains(".zIndex(2f)"))
        assertTrue(shell.contains("ev.key == Key.Back || ev.key == Key.Escape"))
        assertTrue(shell.contains("label = \"Sign Out\""))
    }
}
