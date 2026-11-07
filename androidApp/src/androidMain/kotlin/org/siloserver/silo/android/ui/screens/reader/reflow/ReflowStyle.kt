package org.siloserver.silo.android.ui.screens.reader.reflow

import org.siloserver.silo.common.ebook.ReaderDisplaySettings
import org.siloserver.silo.common.ebook.ReaderFontFamily
import org.siloserver.silo.common.ebook.ReaderTheme
import java.util.Locale

enum class ReflowTheme(val color: String, val background: String) {
    Light("#1c1b1f", "#fffbfe"),
    Sepia("#2b2118", "#f4ecd8"),
    Dark("#e6e1e5", "#1c1b1f"),
}

private const val STYLE_TAIL = """
#reflow-root p{
  margin:0 0 1em 0;
  widows:2;
  orphans:2;
}
#reflow-root h1,#reflow-root h2,#reflow-root h3{
  line-height:1.18;
  margin:1.2em 0 0.65em 0;
  font-weight:700;
  break-after:avoid;
}
#reflow-root img,#reflow-root svg,#reflow-root table{
  max-width:100%;
  height:auto;
  break-inside:avoid;
}
#reflow-root blockquote{
  margin:1em 1.5em;
}
#reflow-root a{
  color:inherit;
}"""

data class ReflowStyle(
    val theme: ReflowTheme,
    val fontScalePercent: Int,
    val marginEm: Double,
    val lineHeight: Double = 1.5,
    val fontStack: String = "Georgia,\"Times New Roman\",serif",
) {
    private fun formatLineHeight(): String {
        // CSS needs a dot whatever the app language: "1,50" is not a number there.
        val formatted = String.format(Locale.ROOT, "%.2f", lineHeight)
        // Trim trailing zeros after decimal, but keep at least one decimal digit
        return formatted.trimEnd('0').trimEnd('.')
            .let { if ('.' !in it) "$it.0" else it }
    }

    fun toCss(): String =
        """
        #reflow-root{
          color:${theme.color};
          background:${theme.background};
          font-family:$fontStack;
          font-size: $fontScalePercent%;
          padding:${marginEm}em;
          box-sizing:border-box;
          line-height:${formatLineHeight()};
          overflow-wrap:break-word;
          word-break:normal;
          text-rendering:optimizeLegibility;
          -webkit-font-smoothing:antialiased;
        }
        """.trimIndent() + STYLE_TAIL
}

private fun ReaderFontFamily.toCssStack(): String = when (this) {
    ReaderFontFamily.Serif -> "Georgia,\"Times New Roman\",serif"
    ReaderFontFamily.SansSerif -> "system-ui,\"Roboto\",\"Helvetica Neue\",Arial,sans-serif"
    ReaderFontFamily.Slab -> "\"Roboto Slab\",Georgia,serif"
    ReaderFontFamily.Dyslexic -> "\"OpenDyslexic\",Verdana,sans-serif"
}

fun ReaderDisplaySettings.toReflowStyle(systemDark: Boolean): ReflowStyle {
    val n = normalized()
    val theme = when (n.theme) {
        ReaderTheme.System -> if (systemDark) ReflowTheme.Dark else ReflowTheme.Light
        ReaderTheme.Light -> ReflowTheme.Light
        ReaderTheme.Dark -> ReflowTheme.Dark
        ReaderTheme.Sepia -> ReflowTheme.Sepia
    }
    return ReflowStyle(
        theme = theme,
        fontScalePercent = (n.textScale * 100).toInt(),
        marginEm = (n.marginScale * 1.2),
        lineHeight = n.lineHeight.toDouble(),
        fontStack = n.fontFamily.toCssStack(),
    )
}
