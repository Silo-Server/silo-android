package com.continuum.app.android.ui.screens.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/** Server marks a failed Kindle->EPUB conversion with this header on the raw
 *  fallback body, so the reader must not cache it as the converted format. */
private const val EBOOK_CONVERSION_HEADER = "X-Silo-Ebook-Conversion"
private const val EBOOK_CONVERSION_FAILED = "failed"

/** SHA-1 cache key for a reader URL — the one shared copy of the helper
 *  the readers previously duplicated five times. */
internal fun readerCacheKey(url: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun readerCacheFileName(url: String, serverUrl: String, extension: String): String =
    "${readerCacheKey(resolveReaderRequestUrl(url, serverUrl))}.$extension"

/**
 * Fill `<cacheDir>/<fileName>` atomically. [fetch] writes into a `.tmp`
 * sibling which is renamed to the final name only when it completes
 * without throwing, so a truncated transfer can never satisfy the
 * `exists() && length() > 0` cache-hit check and get served forever.
 * The tmp file is deleted on any failure. An existing non-empty target
 * short-circuits without invoking [fetch].
 */
internal fun cacheReaderFile(
    cacheDir: File,
    fileName: String,
    validate: ((File) -> Boolean)? = null,
    fetch: (OutputStream) -> Unit,
): File {
    cacheDir.mkdirs()
    val target = File(cacheDir, fileName)
    if (target.exists() && target.length() > 0) {
        if (validate == null || validate(target)) return target
        target.delete()
    }
    val tmp = File.createTempFile("$fileName.", ".tmp", cacheDir)
    try {
        FileOutputStream(tmp).use(fetch)
    } catch (throwable: Throwable) {
        tmp.delete()
        throw throwable
    }
    if (!tmp.renameTo(target)) {
        try {
            tmp.copyTo(target, overwrite = true)
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        } finally {
            tmp.delete()
        }
    }
    if (validate != null && !validate(target)) {
        target.delete()
        throw IOException("Cached reader file failed validation")
    }
    return target
}

/**
 * Resolve a reader URL to a local [File]:
 *   - `file://`    → used as-is (no copy).
 *   - `content://` → copied once into `<cacheDir>/readers/`.
 *   - http(s) or server-relative → fetched via [okHttp] (auth comes
 *     from the injected client's interceptors, same as before) into
 *     `<cacheDir>/readers/<sha1(url)>.<extension>`.
 * Downloads go through [cacheReaderFile], so failures never poison the
 * cache.
 */
internal suspend fun resolveReaderFile(
    context: Context,
    okHttp: OkHttpClient,
    url: String,
    serverUrl: String,
    extension: String,
): File = withContext(Dispatchers.IO) {
    val requestUrl = resolveReaderRequestUrl(url, serverUrl)
    when (readerRequestKind(url, serverUrl)) {
        ReaderRequestKind.File -> return@withContext readerFileFromFileUrl(requestUrl)
        ReaderRequestKind.Content,
        ReaderRequestKind.Remote -> Unit
    }
    val cacheDir = File(context.cacheDir, "readers")
    val fileName = readerCacheFileName(url, serverUrl, extension)
    val validate = readerCacheValidatorFor(extension)
    if (requestUrl.startsWith("content://")) {
        return@withContext cacheReaderFile(cacheDir, fileName, validate) { out ->
            context.contentResolver.openInputStream(Uri.parse(requestUrl))?.use { input ->
                input.copyTo(out)
            } ?: error("Could not open content reader file")
        }
    }
    cacheReaderFile(cacheDir, fileName, validate) { out ->
        val req = Request.Builder().url(requestUrl).build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching reader file")
            // Kindle->EPUB conversion serves the raw original with this header on
            // failure. Never cache that body as the expected (EPUB) format.
            if (resp.header(EBOOK_CONVERSION_HEADER) == EBOOK_CONVERSION_FAILED) {
                error("Server could not convert this book for in-app reading")
            }
            val body = resp.body ?: error("Empty body fetching reader file")
            body.byteStream().copyTo(out)
        }
    }
}

private fun readerCacheValidatorFor(extension: String): ((File) -> Boolean)? =
    when (extension.trim().lowercase().removePrefix(".")) {
        "epub" -> ::hasZipMagic
        else -> null
    }

private fun hasZipMagic(file: File): Boolean =
    runCatching {
        file.inputStream().use { input ->
            input.read() == 'P'.code && input.read() == 'K'.code
        }
    }.getOrDefault(false)

internal fun readerFileFromFileUrl(fileUrl: String): File =
    runCatching { File(URI(fileUrl)) }.getOrElse {
        File(fileUrl.removePrefix("file://").removePrefix("file:"))
    }
