package org.siloserver.silo.tv.ui.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvCascadeInteractionSourceTest {
    private val topMenuSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvTopMenuBar.kt",
    ).readText()

    private val shellSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()

    private val cascadeSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvCascadeSelector.kt",
    ).readText()

    @Test
    fun libraryTabDownEntersCascadeWhileCenterOnlySelectsRoot() {
        assertTrue(topMenuSource.contains("event.key == Key.DirectionDown"))
        assertTrue(topMenuSource.contains("val panel = panelForFocus(focus)"))
        assertTrue(topMenuSource.contains("onEnterPanel(panel)"))
        // A library-type tab's center/click commits to that type's content via
        // onSelectTab (→ Recommended), NOT enter-panel; Home/Calendar use
        // onSelectRoot. Either way the tab click never opens the cascade — only
        // d-pad-down does (asserted above).
        assertTrue(topMenuSource.contains("onClick = { onSelectTab(type) }"))
        assertFalse(topMenuSource.contains("onClick = { onEnterPanel("))
    }

    @Test
    fun cascadeCommitsLibraryScopeAndSectionLikeTvos() {
        assertTrue(
            shellSource.contains(
                "onCommitLibrary = { lib -> commitScope(dest.type, lib, TvLibraryPill.Recommended) }",
            ),
        )
        assertTrue(shellSource.contains("onCommitSection = { lib, pill -> commitScope(dest.type, lib, pill) }"))
        assertTrue(shellSource.contains("scopeSelections[type] = library.id"))
        assertTrue(shellSource.contains("pillSelections[type] = pill"))
        assertTrue(shellSource.contains("sectionRequestNonces[type] = (sectionRequestNonces[type] ?: 0) + 1"))
        assertTrue(shellSource.contains("if (route != currentRoute)"))
        assertTrue(shellSource.contains("closePanel(false)"))
        assertTrue(shellSource.contains("moveFocusToContent(route)"))
    }

    @Test
    fun cascadeEntryFocusLandsOnCurrentScopeLibraryRow() {
        assertTrue(cascadeSource.contains("val target = currentScopeId ?: libraries.firstOrNull()?.id"))
        assertTrue(cascadeSource.contains("anchorId = id"))
        assertTrue(cascadeSource.contains("lazyListState.scrollToItem(index.coerceAtLeast(0))"))
        assertTrue(cascadeSource.contains("withFrameNanos { }"))
        assertTrue(cascadeSource.contains("libraryRequesters[id]?.requestFocus()"))
        assertTrue(cascadeSource.contains("Key.DirectionRight"))
        assertTrue(cascadeSource.contains("focusFirstPillToken++"))
        assertTrue(cascadeSource.contains("onCommitSection(anchorLibrary, pill)"))
    }

    @Test
    fun cascadeFooterDescriptionUsesReadableTvCaptionToken() {
        assertTrue(cascadeSource.contains("internal val CascadeFooterTextSize = 10.5.sp"))
        assertTrue(cascadeSource.contains("private val CascadeFooterLineHeight = 13.sp"))
        assertTrue(cascadeSource.contains("fontSize = CascadeFooterTextSize"))
        assertTrue(cascadeSource.contains("lineHeight = CascadeFooterLineHeight"))
        assertTrue(cascadeSource.contains("SiloOnSurface.copy(alpha = 0.52f)"))
        assertTrue(cascadeSource.contains("maxLines = 3"))
        assertFalse(cascadeSource.contains("fontSize = CascadePanelHeaderSize,\n            fontWeight = FontWeight.Medium,\n            letterSpacing = CascadeFooterTracking"))
    }
}
