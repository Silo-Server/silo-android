package org.siloserver.silo.android.ui.screens.reader.reflow

import org.siloserver.silo.common.ebook.ReaderDisplaySettings
import org.siloserver.silo.common.ebook.ReaderFontFamily
import org.siloserver.silo.common.ebook.ReaderTheme
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReflowStyleTest {
    @Test fun `system theme resolves to dark when device is dark`() {
        val css = ReaderDisplaySettings(theme = ReaderTheme.System)
            .toReflowStyle(systemDark = true).toCss()
        assertTrue(css.contains("#1c1b1f"))
    }
    @Test fun `system theme resolves to light when device is light`() {
        val css = ReaderDisplaySettings(theme = ReaderTheme.System)
            .toReflowStyle(systemDark = false).toCss()
        assertTrue(css.contains("#fffbfe"))
    }
    @Test fun `explicit sepia ignores system dark`() {
        val css = ReaderDisplaySettings(theme = ReaderTheme.Sepia)
            .toReflowStyle(systemDark = true).toCss()
        assertTrue(css.contains("#f4ecd8"))
    }
    @Test fun `text scale becomes font-size percent`() {
        val css = ReaderDisplaySettings(textScale = 1.5f).toReflowStyle(false).toCss()
        assertTrue(css.contains("font-size: 150%"))
    }

    @Test fun `reader margins inset content without shrinking the page surface`() {
        val css = ReaderDisplaySettings(marginScale = 1f).toReflowStyle(false).toCss()

        assertTrue(css.contains("padding:1.2em"))
        assertTrue(css.contains("box-sizing:border-box"))
        assertFalse(Regex("""#reflow-root\{[^}]*margin:1\.2em""").containsMatchIn(css))
    }

    @Test fun `stylesheet targets book typography beyond the root container`() {
        val css = ReaderDisplaySettings().toReflowStyle(false).toCss()

        assertTrue(css.contains("#reflow-root{"))
        assertTrue(css.contains("#reflow-root p{"))
        assertTrue(css.contains("#reflow-root h1"))
        assertTrue(css.contains("#reflow-root img"))
        assertTrue(css.contains("overflow-wrap:break-word"))
        assertTrue(css.contains("break-inside:avoid"))
        assertFalse(css.trim().startsWith("color:"))
    }

    @Test fun `paginator applies generated stylesheet directly`() {
        val paginator = File("src/androidMain/assets/reader/reflow/paginator.js").readText()

        assertTrue(paginator.contains("styleEl.textContent = css"))
        assertFalse(paginator.contains("'#reflow-root{'+css+'}'"))
    }

    @Test
    fun `paginator advances by scrolling columns instead of translating clipped root`() {
        val paginator = File("src/androidMain/assets/reader/reflow/paginator.js").readText()

        assertTrue(paginator.contains("root.scrollLeft = page * viewW()"))
        assertFalse(paginator.contains("root.style.transform"))
        assertFalse(paginator.contains("translateX"))
    }

    @Test
    fun `paginator counts partial trailing columns as readable pages`() {
        val paginator = File("src/androidMain/assets/reader/reflow/paginator.js").readText()

        assertTrue(paginator.contains("Math.ceil(root.scrollWidth / width)"))
        assertFalse(paginator.contains("Math.round(root.scrollWidth / viewW())"))
    }

    @Test
    fun `paginator remeasures after embedded images finish loading`() {
        val paginator = File("src/androidMain/assets/reader/reflow/paginator.js").readText()

        assertTrue(paginator.contains("root.querySelectorAll('img')"))
        assertTrue(paginator.contains("img.addEventListener('load', onLoad)"))
        assertTrue(paginator.contains("img.removeEventListener('load', onLoad)"))
    }

    @Test
    fun `paginator aligns column width and gap to reader gutters`() {
        val paginator = File("src/androidMain/assets/reader/reflow/paginator.js").readText()
        val readerHtml = File("src/androidMain/assets/reader/reflow/reader.html").readText()

        assertTrue(paginator.contains("window.getComputedStyle(root)"))
        assertTrue(paginator.contains("horizontalPadding"))
        assertTrue(paginator.contains("root.style.columnGap = horizontalPadding + 'px'"))
        assertTrue(paginator.contains("root.style.columnWidth = Math.max(1, viewW() - horizontalPadding) + 'px'"))
        assertFalse(readerHtml.contains("will-change:transform"))
    }

    @Test fun `sans-serif font family maps to a sans stack`() {
        val css = ReaderDisplaySettings(fontFamily = ReaderFontFamily.SansSerif)
            .toReflowStyle(systemDark = false).toCss()
        assertTrue(css.contains("font-family:"))
        assertTrue(css.contains("sans-serif"))
        assertFalse(css.contains("Georgia"))
    }

    @Test fun `line height flows from settings`() {
        val css = ReaderDisplaySettings(lineHeight = 1.9f)
            .toReflowStyle(systemDark = false).toCss()
        assertTrue(css.contains("line-height:1.9"))
    }

    @Test fun `line height stays a CSS number in a comma-decimal language`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val css = ReaderDisplaySettings(lineHeight = 1.5f)
                .toReflowStyle(systemDark = false).toCss()
            assertTrue(css.contains("line-height:1.5;"), css)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
