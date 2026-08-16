package org.siloserver.silo.android.ui.screens.player

import android.content.Context

/** Bumped when the key shape or stored form changes, retiring old entries. */
private const val SCHEMA = "v1"

/** Entries kept before the oldest quarter is dropped. */
private const val MAX_ENTRIES = 400
private const val EVICT_TO = 300

/**
 * Remembers the encoded letterbox matte measured for a specific file, so the
 * next play of it starts expanded instead of growing into place a moment in.
 *
 * This is a latency cache, not a source of truth. Every hit is re-measured by
 * the live probe within the first second of playback and overwritten by what
 * that sees, so a wrong entry corrects itself on the next play rather than
 * persisting; and because the value it stores is a minimum (see
 * [LetterboxFillEstimator.observedMatte]), the direction it can be wrong in is
 * under-cropping.
 *
 * Keys name the exact bytes on screen, not the title: server, content, media
 * file and the coded frame size. A different cut, a different release, or the
 * same file arriving transcoded at another resolution all key differently, so
 * none of them can inherit a crop measured from another.
 */
/**
 * Names the exact bytes on screen. Null when the media cannot be identified
 * precisely enough to be worth remembering — no cache entry is far better than
 * one a different file could match.
 */
internal fun letterboxMatteCacheKey(
    serverUrl: String?,
    contentId: String?,
    mediaFileId: Int?,
    codedWidth: Int,
    codedHeight: Int,
): String? {
    if (contentId.isNullOrBlank() || mediaFileId == null) return null
    if (codedWidth <= 0 || codedHeight <= 0) return null
    return "$SCHEMA|${serverUrl.orEmpty()}|$contentId|$mediaFileId|${codedWidth}x$codedHeight"
}

class LetterboxMatteCache(context: Context) {

    private val prefs =
        context.getSharedPreferences("letterbox_matte", Context.MODE_PRIVATE)

    /** The remembered matte for [key], as a fraction of coded height. */
    fun read(key: String): Float? {
        val stored = prefs.getString(key, null) ?: return null
        val matte = stored.substringBefore('|').toFloatOrNull() ?: return null
        // A matte at or past half the frame is not a letterbox, so refuse it
        // rather than seeding a crop from a corrupt or hand-edited entry.
        return matte.takeIf { it > 0f && it < 0.5f }
    }

    fun write(key: String, matteFraction: Float) {
        if (matteFraction <= 0f || matteFraction >= 0.5f) return
        evictIfFull()
        prefs.edit()
            .putString(key, "$matteFraction|${System.currentTimeMillis()}")
            .apply()
    }

    /**
     * Drops the oldest entries once the file grows past [MAX_ENTRIES]. Age is
     * the only thing worth ranking on here — every entry is equally cheap to
     * re-measure, so evicting one costs a single second of sampling on the next
     * play of that file and nothing else.
     */
    private fun evictIfFull() {
        val all = prefs.all
        if (all.size < MAX_ENTRIES) return
        val byAge = all.entries
            .mapNotNull { entry ->
                val stamp = (entry.value as? String)
                    ?.substringAfter('|', "")
                    ?.toLongOrNull()
                    ?: 0L
                entry.key to stamp
            }
            .sortedBy { it.second }
        val editor = prefs.edit()
        byAge.take((all.size - EVICT_TO).coerceAtLeast(0)).forEach { editor.remove(it.first) }
        editor.apply()
    }
}
