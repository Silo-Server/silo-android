package org.siloserver.silo.common.audiobook

import org.siloserver.silo.common.store.ScopedJsonFileStore
import org.siloserver.silo.model.audiobook.AudiobookBookmark
import java.io.File

/**
 * Per-(serverId, profileId, contentId) on-disk bookmark store. Each
 * book gets its own JSON file at
 * `<filesDir>/audiobook_bookmarks/<serverId>/<profileId>/<contentId>.json`,
 * holding an ordered list of [AudiobookBookmark]s.
 *
 * Local-only for now — once the server exposes a /bookmarks endpoint
 * we'll add a merging sync layer; the [AudiobookBookmark.id] field is
 * stable so server round-tripping is straightforward.
 */
class AudiobookBookmarksStore(baseDir: File) {

    private val store = ScopedJsonFileStore(File(baseDir, "audiobook_bookmarks"), TAG)

    fun list(serverId: String, profileId: String, contentId: String): List<AudiobookBookmark> =
        store.read<List<AudiobookBookmark>>(store.fileFor(serverId, profileId, contentId)).orEmpty()

    /** Add a bookmark (deduped by id, existing entry wins), persisting
     *  the full updated list atomically. Returns the persisted list. */
    fun add(
        serverId: String,
        profileId: String,
        contentId: String,
        bookmark: AudiobookBookmark,
    ): List<AudiobookBookmark> {
        val target = store.fileFor(serverId, profileId, contentId)
        return store.withTargetLock(target) {
            val updated = (store.read<List<AudiobookBookmark>>(target).orEmpty() + bookmark)
                .distinctBy { it.id }
                .sortedBy { it.positionSeconds }
            store.write(target, updated)
            updated
        }
    }

    /** Remove a bookmark by id. No-op if missing. */
    fun remove(
        serverId: String,
        profileId: String,
        contentId: String,
        bookmarkId: String,
    ): List<AudiobookBookmark> {
        val target = store.fileFor(serverId, profileId, contentId)
        return store.withTargetLock(target) {
            val updated = store.read<List<AudiobookBookmark>>(target)
                .orEmpty()
                .filterNot { it.id == bookmarkId }
            store.write(target, updated)
            updated
        }
    }

    companion object { private const val TAG = "AudiobookBookmarksStore" }
}
