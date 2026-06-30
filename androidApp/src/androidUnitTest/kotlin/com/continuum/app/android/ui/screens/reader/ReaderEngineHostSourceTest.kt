package com.continuum.app.android.ui.screens.reader

import com.continuum.app.common.ebook.ReaderCapabilities
import com.continuum.app.model.book.BookFormat
import com.continuum.app.model.ebook.EbookReadMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderEngineHostSourceTest {
    private val sourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/ReaderEngineHost.kt",
    )
    private val reflowSourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/reflow/ReflowableReader.kt",
    )
    private val reflowWebViewSourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/reflow/ReflowWebView.kt",
    )
    private val pdfSourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/PdfReader.kt",
    )
    private val comicSourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/ComicReader.kt",
    )
    private val epubReaderSourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/EpubReader.kt",
    )

    @Test
    fun hostDispatchesEveryReaderEngineKind() {
        val source = sourceFile.readText()

        assertTrue(source.contains("ReaderEngineKind.Reflowable"))
        assertTrue(source.contains("ReaderEngineKind.FixedDocument"))
        assertTrue(source.contains("ReaderEngineKind.ComicManga"))
        assertTrue(source.contains("ReaderEngineKind.External"))
    }

    @Test
    fun externalPanelUsesPublicOriginalOpenPath() {
        val source = sourceFile.readText()

        assertTrue(source.contains("DownloadOpenTarget.from("))
        assertTrue(source.contains("openDownloadTargetInExternalApp("))
        assertTrue(source.contains("Open with another reader"))
    }

    @Test
    fun externalPanelRoutingRequiresExternalOnlyReadMode() {
        val unsupportedExternalEngineState = ReaderUiState(
            isLoading = false,
            readMode = EbookReadMode.Unsupported,
            capabilities = ReaderCapabilities.forFormat(BookFormat.Unknown),
        )

        assertFalse(shouldShowExternalReadingPanel(unsupportedExternalEngineState))
        assertTrue(
            shouldShowExternalReadingPanel(
                unsupportedExternalEngineState.copy(readMode = EbookReadMode.ExternalOnly),
            ),
        )
        assertFalse(
            shouldShowExternalReadingPanel(
                unsupportedExternalEngineState.copy(
                    readMode = EbookReadMode.ExternalOnly,
                    capabilities = ReaderCapabilities.forFormat(BookFormat.Epub),
                ),
            ),
        )
    }

    @Test
    fun sourceGuardsExternalPanelByReadMode() {
        val source = sourceFile.readText()

        assertTrue(source.contains("state.readMode == EbookReadMode.ExternalOnly"))
        assertTrue(source.contains("shouldShowExternalReadingPanel(state)"))
    }

    @Test
    fun hostPassesShellChromeToggleToEveryReaderSurface() {
        val source = sourceFile.readText()

        assertTrue(source.contains("onToggleChrome: () -> Unit"))
        assertTrue(source.contains("ExternalReadingPanel(state = state, onToggleChrome = onToggleChrome)"))
        assertTrue(source.contains("CenteredReaderMessage(\"Loading book...\", onToggleChrome = onToggleChrome)"))
        assertTrue(source.contains("onToggleChrome = onToggleChrome"))
    }

    @Test
    fun readerEnginesExposeTapPathToToggleShellChrome() {
        val reflowSource = reflowSourceFile.readText()
        val pdfSource = pdfSourceFile.readText()
        val comicSource = comicSourceFile.readText()

        assertTrue(reflowSource.contains("onToggleChrome: () -> Unit"))
        assertTrue(reflowSource.contains("else -> onToggleChrome()"))
        assertTrue(pdfSource.contains("onToggleChrome: () -> Unit"))
        assertTrue(pdfSource.contains("else -> onToggleChrome()"))
        assertTrue(comicSource.contains("onToggleChrome: () -> Unit"))
        // Comic tap routing is config-driven; ToggleChrome is an explicit named branch.
        assertTrue(comicSource.contains("ComicTapAction.ToggleChrome -> onToggleChrome()"))
    }

    @Test
    fun fixedLayoutReadersUseTapZonesForPageTurns() {
        val pdfSource = pdfSourceFile.readText()
        val comicSource = comicSourceFile.readText()

        assertTrue(pdfSource.contains("xFraction < 1f / 3f"))
        assertTrue(pdfSource.contains("xFraction > 2f / 3f"))
        assertTrue(pdfSource.contains("animateScrollToPage(pagerState.currentPage - 1)"))
        assertTrue(pdfSource.contains("animateScrollToPage(pagerState.currentPage + 1)"))

        // Comic tap zones are delegated through config.tapAction(); Previous/Next
        // branches drive the scroll, ToggleChrome is an explicit named dispatch.
        assertTrue(comicSource.contains("config.tapAction("))
        assertTrue(comicSource.contains("ComicTapAction.Previous ->"))
        assertTrue(comicSource.contains("ComicTapAction.Next ->"))
        assertTrue(comicSource.contains("animateScrollToPage(pagerState.currentPage - 1)"))
        assertTrue(comicSource.contains("animateScrollToPage(pagerState.currentPage + 1)"))
    }

    @Test
    fun reflowLoadingAndErrorSurfacesToggleShellChrome() {
        val source = reflowSourceFile.readText()

        assertTrue(source.contains("private fun Modifier.toggleChromeOnTap(onToggleChrome: () -> Unit)"))
        assertReaderBranchUsesToggle(source, "if (result == null)")
        assertReaderBranchUsesToggle(source, "result.exceptionOrNull()?.let { throwable ->")
    }

    @Test
    fun reflowWebViewOwnsTapZonesBecauseAndroidViewConsumesComposePointers() {
        val readerSource = reflowSourceFile.readText()
        val webViewSource = reflowWebViewSourceFile.readText()

        assertTrue(webViewSource.contains("onTap: (Float) -> Unit"))
        assertTrue(webViewSource.contains("onScale: (Float) -> Unit"))
        assertTrue(webViewSource.contains("GestureDetector"))
        assertTrue(webViewSource.contains("ScaleGestureDetector"))
        assertTrue(webViewSource.contains("setOnTouchListener"))
        assertTrue(readerSource.contains("onTap = { xFraction ->"))
        assertTrue(readerSource.contains("onScale = onTextScaleNudge"))
        assertTrue(readerSource.contains("xFraction < 1f / 3f -> prevPage()"))
        assertTrue(readerSource.contains("xFraction > 2f / 3f -> nextPage()"))
        assertTrue(readerSource.contains("else -> onToggleChrome()"))
    }

    @Test
    fun reflowWebViewKeepsBridgeCallbacksFreshAcrossRecomposition() {
        val webViewSource = reflowWebViewSourceFile.readText()
        val readerSource = reflowSourceFile.readText()

        assertTrue(webViewSource.contains("val currentOnEvent by rememberUpdatedState(onEvent)"))
        assertTrue(webViewSource.contains("val currentOnCrash by rememberUpdatedState(onCrash)"))
        assertTrue(webViewSource.contains("val currentOnReady by rememberUpdatedState(onReady)"))
        assertTrue(webViewSource.contains("currentOnReady(ReflowController(webView))"))
        assertTrue(webViewSource.contains("currentOnCrash()"))
        assertTrue(webViewSource.contains("currentOnEvent(event)"))
        assertTrue(readerSource.contains("var webViewResetKey by remember(source)"))
        assertTrue(readerSource.contains("key(webViewResetKey)"))
        assertTrue(readerSource.contains("webViewResetKey += 1"))
    }

    @Test
    fun epubReaderUsesEncodedDirectoryBaseUrlForWebViewResources() {
        val source = epubReaderSourceFile.readText()

        assertTrue(source.contains("readerDirectoryBaseUrl(book.opfDir)"))
        assertFalse(source.contains(""""file://${'$'}{book.unpackedRoot.absolutePath}/""""))
    }

    @Test
    fun pdfLoadingErrorAndEmptySurfacesToggleShellChrome() {
        val source = pdfSourceFile.readText()

        assertTrue(source.contains("private fun Modifier.toggleChromeOnTap(onToggleChrome: () -> Unit)"))
        assertReaderBranchUsesToggle(source, "if (result == null)")
        assertReaderBranchUsesToggle(source, "result.exceptionOrNull()?.let { throwable ->")
        assertReaderBranchUsesToggle(source, "if (handle.pageCount == 0)")
    }

    @Test
    fun `comic reader uses config-driven tap actions and reverse layout`() {
        val src = File("src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/ComicReader.kt").readText()
        assertTrue(src.contains("ComicReaderConfig"), "ComicReader must use ComicReaderConfig")
        assertTrue(src.contains("tapAction("), "tap handling must route through config.tapAction")
        assertTrue(src.contains("reverseLayout ="), "pager must honor RTL via reverseLayout")
    }

    @Test
    fun comicLoadingErrorAndEmptySurfacesToggleShellChrome() {
        val source = comicSourceFile.readText()

        assertTrue(source.contains("private fun Modifier.toggleChromeOnTap(onToggleChrome: () -> Unit)"))
        assertReaderBranchUsesToggle(source, "if (fileResult == null)")
        assertReaderBranchUsesToggle(source, "fileResult.exceptionOrNull()?.let { throwable ->")
        assertReaderBranchUsesToggle(source, "null -> {")
        assertReaderBranchUsesToggle(source, "is ComicArchiveLoadResult.Error ->")
        assertReaderBranchUsesToggle(source, "ComicArchiveLoadResult.Empty ->")
    }

    @Test
    fun pdfPageHasPinchAndDoubleTapZoom() {
        val source = pdfSourceFile.readText()

        assertTrue(source.contains("clampPdfZoom("), "PdfReader must call clampPdfZoom")
        assertTrue(source.contains("transformable("), "PdfReader must use transformable for pinch-zoom")
        assertTrue(source.contains("onDoubleTap"), "PdfReader must wire onDoubleTap for double-tap zoom")
    }

    private fun assertReaderBranchUsesToggle(source: String, branchStart: String) {
        val startIndex = source.indexOf(branchStart)
        assertTrue(startIndex >= 0, "Missing reader branch: $branchStart")

        val branchSource = source.substring(startIndex, (startIndex + 500).coerceAtMost(source.length))
        assertTrue(
            branchSource.contains(".toggleChromeOnTap(onToggleChrome)"),
            "Reader branch must toggle chrome: $branchStart",
        )
    }
}
