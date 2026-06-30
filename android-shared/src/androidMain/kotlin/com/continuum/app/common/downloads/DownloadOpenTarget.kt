package com.continuum.app.common.downloads

import android.webkit.MimeTypeMap

data class DownloadOpenTarget(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
) {
    companion object {
        fun from(
            isComplete: Boolean,
            localUri: String?,
            displayName: String?,
            container: String?,
        ): DownloadOpenTarget? {
            if (!isComplete || localUri.isNullOrBlank()) return null
            val extension = normalizedDownloadExtension(container)
            val safeName = displayName
                ?.takeIf { it.isNotBlank() }
                ?: extension.takeIf { it.isNotBlank() }?.let { "download.$it" }
                ?: return null
            return DownloadOpenTarget(
                uriString = localUri,
                displayName = safeName,
                mimeType = mimeTypeForDownloadName(safeName, container),
            )
        }
    }
}

fun mimeTypeForDownloadName(displayName: String, container: String?): String {
    val extension = normalizedDownloadExtension(
        displayName.substringAfterLast('.', missingDelimiterValue = "")
            .ifBlank { container.orEmpty() },
    )
    return when (extension) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "cbz" -> "application/vnd.comicbook+zip"
        "cbr" -> "application/vnd.comicbook-rar"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw", "azw3" -> "application/vnd.amazon.ebook"
        "fb2" -> "application/x-fictionbook+xml"
        "fbz" -> "application/zip"
        "md", "markdown" -> "text/markdown"
        "m4b", "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        else -> MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}

private fun normalizedDownloadExtension(value: String?): String = value.orEmpty()
    .trim()
    .removePrefix(".")
    .trim()
    .lowercase()
