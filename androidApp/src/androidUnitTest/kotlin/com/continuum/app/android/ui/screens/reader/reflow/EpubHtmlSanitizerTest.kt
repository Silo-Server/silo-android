package com.continuum.app.android.ui.screens.reader.reflow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubHtmlSanitizerTest {
    @Test
    fun sanitizerRemovesExecutableHtmlButKeepsRelativeResources() {
        val html = """
            <html><body onload="AndroidReflow.onEvent('x')">
              <script>AndroidReflow.onEvent('owned')</script>
              <p onclick="alert(1)" style="background:url(javascript:alert(1))">Text</p>
              <a href="https://example.invalid/track">remote</a>
              <img src="javascript:alert(1)" onerror="alert(1)" />
              <img src="../images/cover.jpg" alt="cover" />
            </body></html>
        """.trimIndent()

        val sanitized = sanitizeEpubChapterHtml(html)

        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("AndroidReflow", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("onerror", ignoreCase = true))
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
        assertFalse(sanitized.contains("https://example.invalid", ignoreCase = true))
        assertFalse(sanitized.contains("style=", ignoreCase = true))
        assertTrue(sanitized.contains("../images/cover.jpg"))
    }
}
