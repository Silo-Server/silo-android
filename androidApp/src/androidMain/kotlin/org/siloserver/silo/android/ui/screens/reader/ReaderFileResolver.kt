package org.siloserver.silo.android.ui.screens.reader

import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import androidx.compose.runtime.Composable

internal enum class ReaderRequestKind {
    File,
    Content,
    Remote,
}

internal fun resolveReaderRequestUrl(url: String, serverUrl: String): String {
    val trimmed = url.trim()
    if (
        trimmed.startsWith("http://") ||
        trimmed.startsWith("https://") ||
        trimmed.startsWith("file://") ||
        trimmed.startsWith("content://")
    ) {
        return trimmed
    }
    val base = serverUrl.trim().trimEnd('/')
    val path = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return "$base$path"
}

internal fun readerRequestKind(url: String, serverUrl: String): ReaderRequestKind {
    val requestUrl = resolveReaderRequestUrl(url, serverUrl)
    return when {
        requestUrl.startsWith("file://") -> ReaderRequestKind.File
        requestUrl.startsWith("content://") -> ReaderRequestKind.Content
        else -> ReaderRequestKind.Remote
    }
}

@Composable
internal fun readerLoadErrorMessage(throwable: Throwable): String =
    throwable.message?.takeIf { it.isNotBlank() }?.let { stringResource(CommonR.string.reader_error_openFailedDetail, it) }
        ?: stringResource(CommonR.string.reader_error_openFailed)

internal inline fun <T> readerLoadResult(block: () -> T): Result<T> =
    runCatching(block)

internal inline fun <T : Any> requiredReaderLoadResult(
    missingMessage: String,
    block: () -> T?,
): Result<T> =
    runCatching { block() ?: error(missingMessage) }
