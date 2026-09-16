package org.siloserver.silo.android.ui.screens.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.siloserver.silo.common.io.checkedLimitedByteCount
import org.siloserver.silo.common.io.copyToLimited
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/** Server marks a failed Kindle->EPUB conversion with this header on the raw
 *  fallback body, so the reader must not cache it as the converted format. */
private const val EBOOK_CONVERSION_HEADER = "X-Silo-Ebook-Conversion"
private const val EBOOK_CONVERSION_FAILED = "failed"
internal const val MAX_READER_INPUT_BYTES = 2L * 1024 * 1024 * 1024

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
    if (validate != null) {
        try {
            if (!validate(target)) {
                throw IOException("Cached reader file failed validation")
            }
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        }
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
    authority: org.siloserver.silo.network.DurableLoginAuthority? = null,
    tokenManager: org.siloserver.silo.network.TokenManager? = null,
): File = withContext(Dispatchers.IO) {
    val scope = authority?.scope
    val requestUrl = resolveReaderRequestUrl(url, scope?.serverUrl ?: serverUrl)
    val remote = readerRequestKind(url, serverUrl) == ReaderRequestKind.Remote
    suspend fun checkScope() {
        if (remote && (scope == null || !scope.isSameIdentityAs(tokenManager?.snapshotCurrentScope())))
            throw IOException("The reader's account or profile changed")
    }
    checkScope()
    when (readerRequestKind(url, serverUrl)) {
        ReaderRequestKind.File -> {
            val file = readerFileFromFileUrl(requestUrl)
            validateReaderDeclaredLength(file.length())
            return@withContext file
        }
        ReaderRequestKind.Content,
        ReaderRequestKind.Remote -> Unit
    }
    val cacheDir = File(context.cacheDir, "readers")
    val fileName = if (remote) readerRemoteCacheFileName(requestUrl, requireNotNull(authority), extension)
        else readerCacheFileName(url, serverUrl, extension)
    val formatValidator = readerCacheValidatorFor(extension)
    val validate: (File) -> Boolean = { file ->
        file.length() <= MAX_READER_INPUT_BYTES &&
            (formatValidator == null || formatValidator(file))
    }
    if (requestUrl.startsWith("content://")) {
        val declaredLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(requestUrl), "r")?.use { descriptor ->
                descriptor.length
            }
        }.getOrNull()
        if (declaredLength != null) validateReaderDeclaredLength(declaredLength)
        return@withContext cacheReaderFile(cacheDir, fileName, validate) { out ->
            context.contentResolver.openInputStream(Uri.parse(requestUrl))?.use { input ->
                input.copyReaderInputTo(out)
            } ?: error("Could not open content reader file")
        }
    }
    val file = cacheReaderFile(cacheDir, fileName, validate) { out ->
        val req = Request.Builder().url(requestUrl)
            .tag(org.siloserver.silo.network.AuthScopeSnapshot::class.java, scope).build()
        okHttp.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(req).execute().use { resp ->
            // This loader requests a complete representation, never a byte range.
            if (resp.code != 200) error("HTTP ${resp.code} fetching reader file")
            // Kindle->EPUB conversion serves the raw original with this header on
            // failure. Never cache that body as the expected (EPUB) format.
            if (resp.header(EBOOK_CONVERSION_HEADER) == EBOOK_CONVERSION_FAILED) {
                error("Server could not convert this book for in-app reading")
            }
            val body = resp.body ?: error("Empty body fetching reader file")
            validateReaderDeclaredLength(body.contentLength())
            body.byteStream().use { input -> input.copyReaderInputTo(out) }
        }
    }
    checkScope()
    file
}

internal fun readerRemoteCacheFileName(url: String,
    authority: org.siloserver.silo.network.DurableLoginAuthority, extension: String): String {
    val scope = authority.scope
    val identity = "$url|${scope.serverId}|${scope.profileId}|${authority.loginId}"
    return "${readerCacheKey(identity)}.$extension"
}

internal fun InputStream.copyReaderInputTo(
    output: OutputStream,
    maxBytes: Long = MAX_READER_INPUT_BYTES,
): Long = copyToLimited(output, maxBytes)

internal fun validateReaderDeclaredLength(
    length: Long,
    maxBytes: Long = MAX_READER_INPUT_BYTES,
) {
    if (length >= 0) {
        checkedLimitedByteCount(
            currentBytes = 0,
            additionalBytes = length,
            maxBytes = maxBytes,
            limitName = "reader input",
        )
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
