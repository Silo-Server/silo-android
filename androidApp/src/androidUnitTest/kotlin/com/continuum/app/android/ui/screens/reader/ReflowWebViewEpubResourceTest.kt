package com.continuum.app.android.ui.screens.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ReflowWebViewEpubResourceTest {
    private val webView = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/reader/reflow/ReflowWebView.kt",
    ).readText()
    private val paginator = File("src/androidMain/assets/reader/reflow/paginator.js").readText()

    @Test
    fun reflowWebViewAllowsInjectedEpubHtmlToLoadExtractedResources() {
        assertTrue(
            paginator.contains("document.querySelector('base')") &&
                paginator.contains("b.href = baseUrl"),
            "The reflow page must install the EPUB directory as the document base URL.",
        )
        assertTrue(
            webView.contains("settings.allowFileAccessFromFileURLs = true") &&
                webView.contains("settings.allowUniversalAccessFromFileURLs = false"),
            "The asset-backed reflow WebView may load EPUB CSS/images from the app cache, but must not grant universal file-origin access.",
        )
    }
}
