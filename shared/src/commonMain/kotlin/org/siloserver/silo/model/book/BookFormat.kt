package org.siloserver.silo.model.book

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

/**
 * Recognised ebook / comic / manga container formats. The renderer
 * dispatches on this to pick the right reader implementation:
 *
 * - EPUB: reflowable text. Renders via Readium navigator.
 * - PDF: fixed-layout pages. Renders via Readium / Android PdfRenderer.
 * - CBZ / CBR: image-per-page archives (manga + comics). Renders via
 *   a paginated image viewer.
 * - MOBI / AZW3: Kindle formats. Not natively supported; treated as
 *   [Unknown] until we add an importer.
 * - TXT: plaintext. Trivial WebView render or Compose Text.
 *
 * Wire strings match the file extension (lowercase, no dot) so the
 * server can return them unchanged when surfacing book metadata.
 */
enum class BookFormat(val wire: String, val displayName: UiText) {
    // Format names stay as written; the ones with words come from the catalog.
    Epub("epub", UiText.Raw("EPUB")),
    Pdf("pdf", UiText.Raw("PDF")),
    Cbz("cbz", UiText.Key(SharedString.BookFormatComicCbz)),
    Cbr("cbr", UiText.Key(SharedString.BookFormatComicCbr)),
    Mobi("mobi", UiText.Raw("MOBI")),
    Azw("azw", UiText.Key(SharedString.BookFormatKindleAzw)),
    Azw3("azw3", UiText.Key(SharedString.BookFormatKindleAzw3)),
    Txt("txt", UiText.Key(SharedString.BookFormatPlainText)),
    Markdown("md", UiText.Raw("Markdown")),
    Fb2("fb2", UiText.Raw("FictionBook")),
    Fbz("fbz", UiText.Raw("FictionBook ZIP")),
    Unknown("", UiText.Key(SharedString.BookFormatUnknown)),
    ;

    companion object {
        /** Tolerant decode: unknown extensions resolve to [Unknown] so
         *  the catalog can keep evolving without crashing the client. */
        fun fromWire(value: String?): BookFormat {
            val lower = value?.lowercase()?.trim()?.removePrefix(".") ?: return Unknown
            return entries.firstOrNull { it.wire == lower } ?: Unknown
        }

        /** Derives format from a filename or URL path; returns [Unknown]
         *  when no recognised extension is present. */
        fun fromPath(path: String?): BookFormat {
            if (path.isNullOrBlank()) return Unknown
            val ext = path.substringAfterLast('.', "").lowercase()
            return fromWire(ext)
        }
    }
}
