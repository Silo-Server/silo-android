package com.continuum.app.android.ui.screens.reader.reflow

import com.continuum.app.android.ui.screens.reader.EpubBook
import com.continuum.app.android.ui.screens.reader.readerDirectoryBaseUrl

/**
 * Adapts an unpacked [EpubBook] into reflow sections. Each spine entry is
 * one section; HTML is served straight from the unpacked chapter file and
 * relative resources resolve against the on-disk unpacked root.
 */
internal class EpubReflowSource(private val book: EpubBook) : ReflowableSource {
    override val sections: List<ReflowSection> =
        book.spine.mapIndexed { i, href ->
            val html = book.readChapterHtml(href)?.let(::sanitizeEpubChapterHtml)
            val text = html?.htmlToReadableText().orEmpty()
            ReflowSection(
                index = i,
                title = html?.epubSectionTitle() ?: href.prettifyHrefTitle(),
                approxChars = text.length.coerceAtLeast(1),
            )
        }

    override val tableOfContents: List<ReflowTocEntry> =
        sections.map { ReflowTocEntry(it.title ?: "", it.index) }

    override suspend fun html(index: Int): String? =
        book.spine.getOrNull(index)?.let { book.readChapterHtml(it)?.let(::sanitizeEpubChapterHtml) }

    override fun baseUrl(index: Int): String =
        book.spine.getOrNull(index)?.let { book.chapterBaseUrl(it) }
            ?: readerDirectoryBaseUrl(book.opfDir)
}

private fun String.epubSectionTitle(): String? {
    val candidates = listOf(
        TITLE_REGEX.find(this)?.groupValues?.get(1),
        HEADING_REGEX.find(this)?.groupValues?.get(2),
    )
    return candidates
        .asSequence()
        .filterNotNull()
        .map { it.htmlToReadableText() }
        .firstOrNull { it.isNotBlank() }
}

private fun String.prettifyHrefTitle(): String =
    substringAfterLast('/')
        .substringBeforeLast('.')
        .replace(Regex("""[-_]+"""), " ")
        .trim()
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { c -> c.uppercase() }
        }

private fun String.htmlToReadableText(): String =
    replace(SCRIPT_OR_STYLE_REGEX, " ")
        .replace(TAG_REGEX, " ")
        .htmlDecodeBasicEntities()
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.htmlDecodeBasicEntities(): String =
    replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HEADING_REGEX = Regex("""<h([1-3])[^>]*>(.*?)</h\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SCRIPT_OR_STYLE_REGEX =
    Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG_REGEX = Regex("""<[^>]+>""")
