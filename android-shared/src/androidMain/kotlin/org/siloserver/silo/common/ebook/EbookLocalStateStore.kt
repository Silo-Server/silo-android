package org.siloserver.silo.common.ebook

import org.siloserver.silo.common.store.ScopedJsonFileStore
import kotlinx.serialization.Serializable
import java.io.File

class EbookLocalStateStore(baseDir: File) {

    private val store = ScopedJsonFileStore(File(baseDir, "ebook_state"), TAG)

    @Serializable
    data class ProgressSnapshot(
        val fileId: Int,
        val location: String,
        val progress: Double,
        val updatedAtMs: Long,
    )

    @Serializable
    data class BookmarkSnapshot(
        val id: String,
        val location: String,
        val createdAtMs: Long,
        val loginId: String? = null,
        val origin: String? = null,
        val deleteETag: String? = null,
    )

    private fun progressFile(serverId: String, profileId: String, contentId: String): File =
        store.fileFor(serverId, profileId, contentId, suffix = ".progress.json")

    private fun bookmarksFile(serverId: String, profileId: String, contentId: String): File =
        store.fileFor(serverId, profileId, contentId, suffix = ".bookmarks.json")

    private fun displaySettingsFile(serverId: String, profileId: String): File =
        store.fileFor(serverId, profileId, "reader-settings")

    fun readProgress(serverId: String, profileId: String, contentId: String): ProgressSnapshot? =
        store.read<ProgressSnapshot>(progressFile(serverId, profileId, contentId))

    fun writeProgress(
        serverId: String,
        profileId: String,
        contentId: String,
        snapshot: ProgressSnapshot,
    ) {
        store.write(progressFile(serverId, profileId, contentId), snapshot)
    }


    fun listBookmarks(serverId: String, profileId: String, contentId: String): List<BookmarkSnapshot> =
        store.read<List<BookmarkSnapshot>>(bookmarksFile(serverId, profileId, contentId))
            .orEmpty()
            .sortedBy { it.createdAtMs }

    fun addBookmark(
        serverId: String,
        profileId: String,
        contentId: String,
        location: String,
        createdAtMs: Long = System.currentTimeMillis(),
        loginId: String? = null,
        origin: String? = null,
    ): BookmarkSnapshot {
        val target = bookmarksFile(serverId, profileId, contentId)
        return store.withTargetLock(target) {
            val bookmark = BookmarkSnapshot(
                id = if (loginId == null) "local-$createdAtMs" else "local-${java.util.UUID.randomUUID()}",
                location = location,
                createdAtMs = createdAtMs,
                loginId = loginId,
                origin = origin,
            )
            val updated = (store.read<List<BookmarkSnapshot>>(target).orEmpty() + bookmark)
                .distinctBy { it.id }
                .sortedBy { it.createdAtMs }
            store.write(target, updated)
            check(store.read<List<BookmarkSnapshot>>(target)?.any { if (loginId == null) it.id == bookmark.id else it == bookmark } == true) { "Bookmark could not be saved locally" }
            bookmark
        }
    }

    /** Persist before DELETE; recovery must never replay a create for this identity. */
    fun markBookmarkDelete(serverId: String, profileId: String, contentId: String,
        id: String, location: String, loginId: String, origin: String, etag: String): BookmarkSnapshot {
        require(etag.isNotBlank())
        val target = bookmarksFile(serverId, profileId, contentId)
        return store.withTargetLock(target) {
            val current = store.read<List<BookmarkSnapshot>>(target).orEmpty()
            val previous = current.find { it.id == id }
            val deletion = BookmarkSnapshot(id, location, previous?.createdAtMs ?: System.currentTimeMillis(), loginId, origin, etag)
            store.write(target, current.filterNot { it.id == id } + deletion)
            check(store.read<List<BookmarkSnapshot>>(target)?.contains(deletion) == true) { "Bookmark deletion could not be saved locally" }
            deletion
        }
    }

    fun removeBookmark(
        serverId: String,
        profileId: String,
        contentId: String,
        bookmarkId: String,
    ): List<BookmarkSnapshot> {
        val target = bookmarksFile(serverId, profileId, contentId)
        return store.withTargetLock(target) {
            val updated = store.read<List<BookmarkSnapshot>>(target)
                .orEmpty()
                .filterNot { it.id == bookmarkId }
            store.write(target, updated)
            updated
        }
    }

    fun readDisplaySettings(serverId: String, profileId: String): ReaderDisplaySettings? =
        store.read<ReaderDisplaySettings>(displaySettingsFile(serverId, profileId))?.normalized()

    fun writeDisplaySettings(
        serverId: String,
        profileId: String,
        settings: ReaderDisplaySettings,
    ) {
        store.write(displaySettingsFile(serverId, profileId), settings.normalized())
    }

    companion object { private const val TAG = "EbookLocalStateStore" }
}
