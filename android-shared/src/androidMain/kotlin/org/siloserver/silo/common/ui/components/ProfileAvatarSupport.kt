package org.siloserver.silo.common.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ServerRegistry
import org.koin.core.context.GlobalContext

private const val diceBearPresetPrefix = "preset:dicebear:"
private const val diceBearBaseUrl = "https://api.dicebear.com/9.x"

/**
 * Scheme the server uses for an avatar the user uploaded, e.g.
 * `upload:profile-avatars/1/<uuid>/original.webp`.
 *
 * This is an object-store *reference*, not a URL: the bytes live in a private
 * bucket, so the only fetchable form is the presigned `avatar_url` the server
 * returns alongside it. The client cannot sign R2 requests itself and must
 * never try to build a URL out of this ref.
 */
private const val uploadAvatarPrefix = "upload:"

private val imageAvatarPrefixes = listOf(
    "http://",
    "https://",
    "data:image/",
    "content://",
    "file://",
    "/",
)

private val imageAvatarExtensions = listOf(
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".svg",
    ".avif",
)

/**
 * The stored avatar reference plus the server's fetchable URL for it, kept in
 * one value so the two cannot be threaded apart.
 *
 * They are useless separately: the ref alone cannot be fetched for uploads, and
 * the URL alone cannot produce the initials/emoji fallback or a stable cache
 * key. Every avatar-rendering composable takes this instead of a bare
 * `avatar: String?` precisely so a screen cannot be half-migrated into showing
 * initials where another screen shows the picture.
 */
@Immutable
data class ProfileAvatarRef(
    /** Stored ref: `upload:…`, `preset:dicebear:…`, a path, an emoji, or null. */
    val avatar: String? = null,
    /** Server-supplied fetchable URL (presigned and short-lived for uploads). */
    val avatarUrl: String? = null,
) {
    companion object {
        /** No avatar at all — renders as initials. */
        val None = ProfileAvatarRef()
    }
}

/** This profile's avatar ref and server-resolved URL as one value. */
fun Profile.avatarRef(): ProfileAvatarRef = ProfileAvatarRef(avatar, avatarUrl)

/**
 * A fetchable avatar image: where to get it, and what to file it under.
 *
 * [cacheKey] is deliberately NOT the URL. A presigned upload URL carries
 * `X-Amz-Date` / `X-Amz-Signature` query parameters that are regenerated on
 * every `GET /profiles`, so keying Coil's memory and disk caches by the URL
 * would miss on every single refresh and re-download the same bytes forever.
 * Keying by the signature-free part makes the cache actually work — and, as a
 * useful side effect, an already-loaded avatar keeps rendering from cache even
 * after its signed URL has expired.
 */
@Immutable
data class ResolvedProfileAvatar(
    val url: String,
    /** Stable cache key, or null to let Coil key by [url] (fine for stable URLs). */
    val cacheKey: String? = null,
)

fun isImageAvatar(avatar: String?): Boolean {
    val value = avatar?.trim().orEmpty()
    if (value.isEmpty()) return false

    val lowercased = value.lowercase()
    return isDiceBearPresetAvatar(value)
        || isUploadAvatarRef(value)
        || imageAvatarPrefixes.any(lowercased::startsWith)
        || "/" in lowercased
        || imageAvatarExtensions.any(lowercased::contains)
}

/** True for the server's `upload:` object-store reference scheme. */
fun isUploadAvatarRef(avatar: String?): Boolean =
    avatar?.trim()?.lowercase()?.startsWith(uploadAvatarPrefix) == true

/**
 * Resolves what to actually draw for [avatar], or null when nothing is
 * fetchable (caller falls back to emoji/initials).
 *
 * Order matters:
 *  1. A server-supplied `avatar_url` wins whenever present. It is the only
 *     form that works for uploads and the server knows which variant to serve.
 *  2. An `upload:` ref with no URL resolves to **null**. Appending the ref to
 *     the server origin used to yield `https://server/upload:profile-avatars/…`,
 *     a guaranteed 404 that rendered as an empty circle; initials are strictly
 *     better than a broken image request.
 *  3. Everything else (DiceBear presets, absolute URLs, server-relative paths)
 *     keeps its existing behaviour.
 */
fun resolveProfileAvatar(serverUrl: String, avatar: ProfileAvatarRef): ResolvedProfileAvatar? {
    val trimmedRef = avatar.avatar?.trim().orEmpty()
    val trimmedUrl = avatar.avatarUrl?.trim().orEmpty()

    if (trimmedUrl.isNotEmpty()) {
        return ResolvedProfileAvatar(
            url = trimmedUrl,
            // Only uploads get an override: their signature rotates. DiceBear
            // and other query-bearing URLs must keep the query in their key —
            // stripping it would collapse every preset onto one cache entry.
            cacheKey = if (isUploadAvatarRef(trimmedRef)) {
                stableUploadCacheKey(trimmedRef, trimmedUrl)
            } else {
                null
            },
        )
    }

    if (isUploadAvatarRef(trimmedRef)) return null

    return resolveAvatarUrl(serverUrl, trimmedRef)?.let { ResolvedProfileAvatar(it) }
}

/**
 * Legacy single-string resolution, kept for refs that carry no server URL.
 *
 * Returns null for `upload:` refs — see [resolveProfileAvatar]. Prefer that
 * function anywhere a [Profile] (and therefore an `avatar_url`) is in hand.
 */
fun resolveAvatarUrl(serverUrl: String, avatar: String): String? {
    val trimmedAvatar = avatar.trim()
    if (trimmedAvatar.isEmpty()) return null

    resolveDiceBearPresetUrl(trimmedAvatar)?.let { return it }

    // An upload ref is not a path. Never fabricate an origin-relative URL from
    // it; the caller wants null so it can fall back to initials.
    if (isUploadAvatarRef(trimmedAvatar)) return null

    val normalizedServerUrl = serverUrl.trim().trimEnd('/')
    val lowercasedAvatar = trimmedAvatar.lowercase()
    val isAbsoluteAvatar = imageAvatarPrefixes
        .dropLast(1)
        .any(lowercasedAvatar::startsWith)

    if (isAbsoluteAvatar) {
        return trimmedAvatar
    }

    if (!isImageAvatar(trimmedAvatar) || normalizedServerUrl.isEmpty()) {
        return null
    }

    return if (trimmedAvatar.startsWith("/")) {
        normalizedServerUrl + trimmedAvatar
    } else {
        "$normalizedServerUrl/${trimmedAvatar.trimStart('/')}"
    }
}

/**
 * The signature-free identity of a presigned upload URL: everything up to the
 * first `?` or `#`. That prefix (`https://<host>/<bucket>/…/<uuid>/w256.webp`)
 * is stable across re-signings but still distinguishes one profile's upload —
 * and one rendition of it — from another. Falls back to the ref if the URL has
 * no usable prefix.
 */
private fun stableUploadCacheKey(avatarRef: String, url: String): String {
    val queryStart = url.indexOfFirst { it == '?' || it == '#' }
    val withoutQuery = if (queryStart >= 0) url.substring(0, queryStart) else url
    return withoutQuery.ifBlank { avatarRef }
}

fun profileAvatarDisplayText(avatar: String?, name: String): String {
    val trimmedAvatar = avatar?.trim().orEmpty()
    return if (trimmedAvatar.isNotEmpty() && !isImageAvatar(trimmedAvatar)) {
        trimmedAvatar
    } else {
        name.profileInitials()
    }
}

/** [profileAvatarDisplayText] for call sites that already hold a [ProfileAvatarRef]. */
fun profileAvatarDisplayText(avatar: ProfileAvatarRef, name: String): String =
    profileAvatarDisplayText(avatar.avatar, name)

/**
 * True when the avatar is a literal glyph (emoji) rather than an image ref, so
 * a caller can size that glyph differently from initials.
 */
fun isEmojiAvatar(avatar: ProfileAvatarRef): Boolean =
    !avatar.avatar.isNullOrBlank() && !isImageAvatar(avatar.avatar)

@Composable
fun rememberProfileServerUrl(): String {
    val serverRegistry = remember { GlobalContext.get().get<ServerRegistry>() }
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val serverUrl = activeEntry?.url.orEmpty()
    return remember(serverUrl) { serverUrl.trim().trimEnd('/') }
}

/** A resolved avatar image plus the failure hook that retires it. */
@Stable
class ProfileAvatarImage internal constructor(
    val url: String,
    val cacheKey: String?,
    /** Report a load failure; the owning composable then falls back to text. */
    val onLoadFailed: () -> Unit,
)

/**
 * Resolves [avatar] against the active server, or null when there is nothing
 * to draw and the caller should render emoji/initials instead.
 *
 * Handles presigned-URL expiry. The server signs upload URLs for 900 seconds,
 * so a screen held open longer than that (TV profile selection left idling, the
 * always-composed shell avatar) can be holding a URL that now 403s. Two things
 * keep that from showing as an empty circle:
 *
 *  - the stable cache key means an avatar that loaded once keeps rendering
 *    from Coil's memory/disk cache regardless of the URL's age, so expiry only
 *    bites for an image that was never fetched while the URL was valid; and
 *  - if the fetch does fail, [ProfileAvatarImage.onLoadFailed] retires this
 *    URL and the caller falls back to initials until a fresh one arrives.
 *
 * Deliberately no timer-based pre-emptive expiry: dropping the image the
 * instant the signature ages out would discard a perfectly good cached bitmap.
 * A fresh URL arrives with the next `GET /profiles` — on screen re-entry, a
 * profile switch, or relaunch — and clears the failure flag automatically.
 *
 * A failure is also not assumed permanent. A DiceBear/CDN blip or a dropped
 * connection retires a URL that is otherwise perfectly good, and a stable URL
 * on an always-composed surface (the TV shell avatar) would otherwise never be
 * requested again for the life of the process. Failures are therefore retried
 * on the [avatarRetryDelaysMs] backoff before the avatar settles into initials.
 */
@Composable
fun rememberProfileAvatarImage(avatar: ProfileAvatarRef): ProfileAvatarImage? {
    val serverUrl = rememberProfileServerUrl()
    val resolved = remember(avatar, serverUrl) { resolveProfileAvatar(serverUrl, avatar) }
    // Keyed on `resolved`, so any newly-signed URL starts trusted again.
    var failureCount by remember(resolved) { mutableIntStateOf(0) }
    var loadFailed by remember(resolved) { mutableStateOf(false) }

    val retryDelayMs = avatarRetryDelaysMs.getOrNull(failureCount - 1)
    if (loadFailed && retryDelayMs != null) {
        LaunchedEffect(resolved, failureCount) {
            delay(retryDelayMs)
            loadFailed = false
        }
    }

    return remember(resolved, loadFailed) {
        resolved
            ?.takeUnless { loadFailed }
            ?.let {
                ProfileAvatarImage(it.url, it.cacheKey) {
                    failureCount++
                    loadFailed = true
                }
            }
    }
}

/**
 * How long to wait before re-requesting an avatar that failed to load, per
 * attempt. Bounded on purpose: a genuinely broken ref settles into initials
 * after the last entry instead of re-requesting forever, while one that failed
 * during an outage recovers on its own once the network is back.
 */
private val avatarRetryDelaysMs = listOf(5_000L, 20_000L, 60_000L)

fun String.profileInitials(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "?"

    val parts = trimmed.split("\\s+".toRegex())
    return when {
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        else -> "${trimmed.first().uppercaseChar()}"
    }
}

private fun isDiceBearPresetAvatar(avatar: String): Boolean =
    avatar.trim().lowercase().startsWith(diceBearPresetPrefix)

private fun resolveDiceBearPresetUrl(avatar: String): String? {
    if (!isDiceBearPresetAvatar(avatar)) return null

    val parts = avatar.trim().split(':', limit = 4)
    if (parts.size < 4) return null

    val style = parts[2].trim()
    val seed = parts[3].trim()
    if (style.isEmpty() || seed.isEmpty()) return null

    return "$diceBearBaseUrl/${Uri.encode(style)}/png?seed=${Uri.encode(seed)}&size=256"
}
