package org.siloserver.silo.common.downloads

import android.util.Log
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.LegacyImportEntity
import org.siloserver.silo.model.download.DownloadSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * One-time migration of the legacy on-disk `.record.json` download sidecar tree
 * into the Room [org.siloserver.silo.common.data.db.entity.DownloadEntity] table.
 *
 * The download metadata path is now Room-backed ([DownloadMetadataStore]); the old
 * sidecars are no longer written by [DownloadEnqueuer]/[DownloadWorker]. Without
 * this import, downloads created before the cutover would still have their bytes on
 * disk/MediaStore but no metadata row — so they'd vanish from the Downloads tab and
 * the offline player. This walks the frozen sidecar tree once, upserts each into
 * Room, records the import in `legacy_imports` for audit/idempotency, and removes the
 * migrated sidecar so the tree drains.
 *
 * Layout (unchanged from the old [DownloadStorage]):
 *   `<filesDir>/downloads/<serverId>/<profileId>/<fileId>.record.json`
 * with scope recovered directly from the directory names.
 *
 * Precedence: Room is the source of truth. If a row already exists for a
 * `(serverId, profileId, fileId)` it is treated as newer than the frozen sidecar
 * and left untouched (the stale sidecar is still removed).
 */
class LegacyDownloadImporter(
    private val baseDir: File,
    db: SiloDatabase,
) {
    private val downloadDao = db.downloadDao()
    private val legacyImportDao = db.legacyImportDao()
    private val json = Json { ignoreUnknownKeys = true }

    private val importMutex = Mutex()
    @Volatile private var completed = false

    /**
     * Runs the import at most once per process and blocks until Room reflects
     * the legacy tree. Safe to call from every bootstrap path (app start, the
     * Downloads tab, the offline player) — the first caller does the work on
     * [Dispatchers.IO]; the rest return immediately. This closes the cold-start
     * race where a consumer reads Room before the import finishes.
     */
    suspend fun awaitImport(nowMs: Long) {
        if (completed) return
        importMutex.withLock {
            if (completed) return
            withContext(Dispatchers.IO) { importIfNeeded(nowMs) }
            completed = true
        }
    }

    suspend fun importIfNeeded(nowMs: Long) {
        val root = File(baseDir, DOWNLOADS_DIR_NAME)
        if (!root.exists()) return

        val sidecarFiles = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(SIDECAR_SUFFIX) }
            .toList()
        if (sidecarFiles.isEmpty()) {
            pruneEmptyDirs(root)
            return
        }

        var imported = 0
        var skipped = 0
        var failed = 0
        for (file in sidecarFiles) {
            when (importOne(file, root, nowMs)) {
                Result.IMPORTED -> imported++
                Result.SKIPPED -> skipped++
                Result.FAILED -> failed++
            }
        }
        pruneEmptyDirs(root)
        Log.i(TAG, "Legacy download import: $imported imported, $skipped skipped (Room newer), $failed failed")
    }

    private enum class Result { IMPORTED, SKIPPED, FAILED }

    private suspend fun importOne(file: File, root: File, nowMs: Long): Result {
        val relPath = file.relativeTo(root).path

        // Strict layout: exactly <server>/<profile>/<fileId>.record.json. Anything
        // deeper/shallower is foreign — record it failed and LEAVE it in place so a
        // misplaced file is never silently deleted by the migration.
        val profileDir = file.parentFile
        val serverDir = profileDir?.parentFile
        if (profileDir == null || serverDir == null || serverDir.parentFile != root) {
            recordFailed(relPath, file, nowMs, error = "unexpected_path_depth")
            Log.w(TAG, "Skipping sidecar with unexpected path: $relPath")
            return Result.FAILED
        }
        val serverId = serverDir.name
        val profileId = profileDir.name
        val nameId = file.name.removeSuffix(SIDECAR_SUFFIX).toIntOrNull()

        val text = runCatching { file.readText() }.getOrNull()
        val sidecar = text?.let {
            runCatching { json.decodeFromString<DownloadSidecar>(it) }.getOrNull()
        }
        if (sidecar == null) {
            recordFailed(relPath, file, nowMs, error = "decode_failed", sourceHash = text?.let(::sha256) ?: "")
            Log.w(TAG, "Failed to decode legacy sidecar: $relPath")
            return Result.FAILED
        }

        val fileId = sidecar.record.mediaFileId
        // The filename id must match the sidecar's own fileId, or the file is
        // misnamed/corrupt — don't import or delete it.
        if (nameId == null || nameId != fileId) {
            recordFailed(relPath, file, nowMs, error = "file_id_mismatch", sourceHash = sha256(text))
            Log.w(TAG, "Sidecar filename id ($nameId) != record.mediaFileId ($fileId): $relPath")
            return Result.FAILED
        }

        // Room wins atomically: INSERT OR IGNORE never overwrites an existing row
        // (PK or unique recordId). rowId == -1 means a row already existed.
        // Sidecars predate accounts: imported as '' and adopted with the rest of
        // the server's pre-account data (OrphanedServerDataPurger).
        val inserted = downloadDao.insertIfAbsent(sidecar.toEntity(serverId, "", profileId))
        val roomWon = inserted == -1L
        recordImport(
            relPath = relPath,
            sourceHash = sha256(text),
            mtime = file.lastModified(),
            nowMs = nowMs,
            status = if (roomWon) "skipped" else "imported",
            error = null,
        )
        // Drain the migrated sidecar so the legacy tree empties out.
        file.delete()
        return if (roomWon) Result.SKIPPED else Result.IMPORTED
    }

    private suspend fun recordFailed(
        relPath: String,
        file: File,
        nowMs: Long,
        error: String,
        sourceHash: String = "",
    ) = recordImport(relPath, sourceHash, file.lastModified(), nowMs, status = "failed", error = error)

    private suspend fun recordImport(
        relPath: String,
        sourceHash: String,
        mtime: Long,
        nowMs: Long,
        status: String,
        error: String?,
    ) {
        runCatching {
            legacyImportDao.upsert(
                LegacyImportEntity(
                    sourceKind = SOURCE_KIND,
                    sourcePath = relPath,
                    sourceHash = sourceHash,
                    sourceMtimeMs = mtime,
                    importedAtMs = nowMs,
                    importVersion = IMPORT_VERSION,
                    status = status,
                    error = error,
                ),
            )
        }.onFailure { Log.w(TAG, "Failed to record legacy import for $relPath", it) }
    }

    /** Removes now-empty `<server>/<profile>` scope dirs (and the root) left after draining. */
    private fun pruneEmptyDirs(root: File) {
        root.walkBottomUp()
            .filter { it.isDirectory && it != root }
            .forEach { dir -> if (dir.listFiles()?.isEmpty() == true) dir.delete() }
        if (root.listFiles()?.isEmpty() == true) root.delete()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "LegacyDownloadImport"
        private const val DOWNLOADS_DIR_NAME = "downloads"
        private const val SIDECAR_SUFFIX = ".record.json"
        private const val SOURCE_KIND = "download_sidecar"
        private const val IMPORT_VERSION = 1
    }
}
