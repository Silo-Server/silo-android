package com.continuum.app.common.downloads

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.continuum.app.model.download.DownloadMediaType
import java.io.File
import java.io.OutputStream
import java.net.URI

/**
 * Storage coordinator for downloads. Sidecars live under private [baseDir];
 * downloaded media bytes live in [publicStore] so other Android apps can
 * discover/open the original files.
 */
class DownloadStorage(
    private val baseDir: File,
    private val publicStore: PublicDownloadStore = FileBackedPublicDownloadStore(File(baseDir, "public-downloads")),
) {

    constructor(context: Context) : this(
        baseDir = context.filesDir,
        publicStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStorePublicDownloadStore(context.applicationContext)
        } else {
            FileBackedPublicDownloadStore(
                collectionRoots = legacyPublicCollectionRoots(),
                onFileCompleted = { file ->
                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(file.absolutePath),
                        null,
                        null,
                    )
                },
            )
        },
    )

    // Byte resolution is fileId-based (no sidecar read) so it stays synchronous —
    // the player surface (Compose) and worker call these on non-suspend paths.
    // Download metadata now lives in Room (suspend); only the bytes are file/MediaStore.
    fun locateLocalMedia(serverId: String, profileId: String, fileId: Int): DownloadLocation? =
        publicStore.locateByFileId(serverId, profileId, fileId)

    fun locateLocalFile(serverId: String, profileId: String, fileId: Int): File? =
        (locateLocalMedia(serverId, profileId, fileId) as? FileDownloadLocation)?.file

    /**
     * Ensures the parent directory exists and returns the target file.
     * Caller is responsible for the actual write (typically via the
     * DownloadWorker's streaming copy).
     */
    fun prepareWrite(
        serverId: String,
        profileId: String,
        fileId: Int,
        fileName: String? = null,
        container: String? = null,
        mediaType: String? = null,
    ): DownloadTarget {
        publicStore.delete(serverId, profileId, fileId, uriString = null)
        return publicStore.create(
            serverId = serverId,
            profileId = profileId,
            fileId = fileId,
            displayName = localMediaFileName(fileId, fileName, container),
            container = container,
            mediaType = mediaType,
        )
    }

    fun exists(serverId: String, profileId: String, fileId: Int): Boolean =
        locateLocalMedia(serverId, profileId, fileId) != null

    /**
     * Deletes the local file for this triple. Returns true if a file was
     * actually removed (false if nothing existed to delete). Empty parent
     * directories are left in place; cleanup at higher granularity goes
     * through [deleteAllForProfile] or [deleteAllForServer].
     */
    fun delete(serverId: String, profileId: String, fileId: Int): Boolean =
        publicStore.delete(serverId, profileId, fileId, uriString = null)

    fun completeWrite(uriString: String) {
        publicStore.complete(uriString)
    }

    /** Actual on-disk bytes of a partial download at [uriString], or 0 if it's
     *  gone/unreadable. Used to resume an interrupted download via HTTP Range.
     *  Reads the real file length (fd stat), not the MediaStore SIZE column,
     *  which is stale for an in-progress (pending) item. */
    fun partialSize(uriString: String): Long =
        publicStore.partialSize(uriString)

    /** Opens the existing partial at [uriString] for APPEND so a resumed transfer
     *  continues where it left off. Returns null if append isn't supported/possible
     *  (caller falls back to a fresh download). */
    fun openAppend(uriString: String): OutputStream? =
        publicStore.openAppend(uriString)

    fun totalBytesUsed(serverId: String, profileId: String): Long =
        publicStore.totalBytesUsed(serverId, profileId)

    fun deleteUri(uriString: String): Boolean =
        publicStore.delete("", "", -1, uriString)

    /** Wipes every downloaded byte under (serverId, profileId). Used on sign-out
     *  and profile switch. Metadata rows are cleared via [DownloadMetadataStore]. */
    fun deleteAllForProfile(serverId: String, profileId: String): Boolean =
        publicStore.deleteAllForProfile(serverId, profileId)

    /** Wipes every downloaded byte under (serverId). Used on server delete / re-bind. */
    fun deleteAllForServer(serverId: String): Boolean =
        publicStore.deleteAllForServer(serverId)

    /** Sum of bytes across every downloaded file under this storage. */
    fun totalBytesUsed(): Long = publicStore.totalBytesUsed()

    /** Reports the filesystem's remaining usable space for the base dir,
     *  useful for the Downloads header storage card. */
    fun usableSpaceBytes(): Long = baseDir.usableSpace

    private fun localMediaFileName(fileId: Int, fileName: String?, container: String?): String {
        val originalName = fileName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::sanitizeBasename)
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        if (originalName != null) return originalName

        val extension = container
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Original download filename or container is required")
        return "$fileId.$extension"
    }

    private fun sanitizeBasename(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_")

}

data class DownloadTarget(
    val uriString: String,
    val displayName: String,
    val openOutputStream: () -> OutputStream,
    val sizeBytes: () -> Long,
)

open class DownloadLocation(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
)

class FileDownloadLocation(
    uriString: String,
    displayName: String,
    sizeBytes: Long,
    val file: File,
) : DownloadLocation(uriString, displayName, sizeBytes)

interface PublicDownloadStore {
    fun create(
        serverId: String,
        profileId: String,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget
    fun locate(uriString: String): DownloadLocation?
    fun locateByFileId(serverId: String, profileId: String, fileId: Int): DownloadLocation?
    fun delete(serverId: String, profileId: String, fileId: Int, uriString: String?): Boolean
    fun complete(uriString: String) = Unit

    /** Open an existing partial for append (resume). Null = unsupported/unavailable. */
    fun openAppend(uriString: String): OutputStream? = null

    /** Real on-disk byte count of a partial at [uriString] (fd stat, not a stale
     *  metadata SIZE column). 0 = gone/unreadable. */
    fun partialSize(uriString: String): Long = 0L
    fun deleteAllForProfile(serverId: String, profileId: String): Boolean
    fun deleteAllForServer(serverId: String): Boolean
    fun totalBytesUsed(): Long
    fun totalBytesUsed(serverId: String, profileId: String): Long
}

class FileBackedPublicDownloadStore(
    root: File? = null,
    collectionRoots: Map<PublicDownloadCollection, File>? = null,
    private val onFileCompleted: (File) -> Unit = {},
) : PublicDownloadStore {
    private val rootsByCollection: Map<PublicDownloadCollection, File> =
        collectionRoots ?: PublicDownloadCollection.entries.associateWith { collection ->
            File(requireNotNull(root) { "root or collectionRoots is required" }, collection.directoryName)
        }
    private val roots: List<File> = rootsByCollection.values.distinctBy { file ->
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }

    override fun create(
        serverId: String,
        profileId: String,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget {
        val collection = PublicDownloadCollection.forMediaType(mediaType)
        val dir = File(collectionRoot(collection), "$serverId/$profileId/$fileId").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, displayName)
        return DownloadTarget(
            uriString = fileUriString(file),
            displayName = displayName,
            openOutputStream = { file.outputStream() },
            sizeBytes = { file.length() },
        )
    }

    override fun locate(uriString: String): DownloadLocation? {
        if (!uriString.startsWith("file:", ignoreCase = true)) return null
        val file = runCatching { File(URI(uriString)) }.getOrElse {
            File(uriString.removePrefix("file://").removePrefix("file:"))
        }
        if (!file.isFile) return null
        return FileDownloadLocation(uriString, file.name, file.length(), file)
    }

    override fun locateByFileId(serverId: String, profileId: String, fileId: Int): DownloadLocation? {
        PublicDownloadCollection.entries.forEach { collection ->
            val dir = File(collectionRoot(collection), "$serverId/$profileId/$fileId")
            val file = dir.listFiles { f -> f.isFile }?.sortedBy { it.name }?.firstOrNull()
            if (file != null) {
                return FileDownloadLocation(fileUriString(file), file.name, file.length(), file)
            }
        }
        return null
    }

    override fun delete(serverId: String, profileId: String, fileId: Int, uriString: String?): Boolean {
        val fromUri = uriString?.let { locate(it) as? FileDownloadLocation }?.file
        if (fromUri != null) return fromUri.delete()
        return PublicDownloadCollection.entries.any { collection ->
            val target = File(collectionRoot(collection), "$serverId/$profileId/$fileId")
            target.exists() && target.deleteRecursively()
        }
    }

    override fun complete(uriString: String) {
        val file = (locate(uriString) as? FileDownloadLocation)?.file ?: return
        onFileCompleted(file)
    }

    override fun openAppend(uriString: String): OutputStream? {
        val file = (locate(uriString) as? FileDownloadLocation)?.file ?: return null
        return java.io.FileOutputStream(file, /* append = */ true)
    }

    override fun partialSize(uriString: String): Long =
        (locate(uriString) as? FileDownloadLocation)?.file?.takeIf { it.isFile }?.length() ?: 0L

    override fun deleteAllForProfile(serverId: String, profileId: String): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val target = File(collectionRoot(collection), "$serverId/$profileId")
            deleted = (target.exists() && target.deleteRecursively()) || deleted
        }
        return deleted
    }

    override fun deleteAllForServer(serverId: String): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val target = File(collectionRoot(collection), serverId)
            deleted = (target.exists() && target.deleteRecursively()) || deleted
        }
        return deleted
    }

    override fun totalBytesUsed(): Long {
        var total = 0L
        roots.forEach { root ->
            if (root.exists()) root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        return total
    }

    override fun totalBytesUsed(serverId: String, profileId: String): Long {
        var total = 0L
        PublicDownloadCollection.entries.forEach { collection ->
            val dir = File(collectionRoot(collection), "$serverId/$profileId")
            if (dir.exists()) dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        return total
    }

    private fun collectionRoot(collection: PublicDownloadCollection): File =
        rootsByCollection[collection] ?: error("No public download root for $collection")

    private fun fileUriString(file: File): String =
        URI("file", "", file.absolutePath, null).toASCIIString()
}

class MediaStorePublicDownloadStore(context: Context) : PublicDownloadStore {
    private val resolver: ContentResolver = context.contentResolver

    override fun create(
        serverId: String,
        profileId: String,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget {
        delete(serverId, profileId, fileId, uriString = null)
        val collection = PublicDownloadCollection.forMediaType(mediaType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForDownloadName(displayName, container))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelativePath(collection, serverId, profileId, fileId))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection.contentUri(), values) ?: error("Could not create MediaStore download")
        return DownloadTarget(
            uriString = uri.toString(),
            displayName = displayName,
            openOutputStream = { resolver.openOutputStream(uri, "w") ?: error("Could not open $uri") },
            sizeBytes = { locate(uri.toString())?.sizeBytes ?: 0L },
        )
    }

    override fun locate(uriString: String): DownloadLocation? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.getString(0) ?: uri.lastPathSegment.orEmpty()
            val size = cursor.getLong(1)
            return DownloadLocation(uri.toString(), name, size)
        }
        return null
    }

    override fun locateByFileId(serverId: String, profileId: String, fileId: Int): DownloadLocation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        PublicDownloadCollection.entries.forEach { collection ->
            resolver.query(
                collection.contentUri(),
                projection,
                selection,
                arrayOf(mediaStoreRelativePath(collection, serverId, profileId, fileId)),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: fileId.toString()
                    val size = cursor.getLong(2)
                    val uri = ContentUris.withAppendedId(collection.contentUri(), id)
                    return DownloadLocation(uri.toString(), name, size)
                }
            }
        }
        return null
    }

    override fun openAppend(uriString: String): OutputStream? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        // "wa" = write + append on a pending item we own (no include-pending needed
        // for a direct item-uri open). Continues a resumed transfer in place.
        return runCatching { resolver.openOutputStream(uri, "wa") }.getOrNull()
    }

    override fun partialSize(uriString: String): Long {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return 0L
        // The MediaStore SIZE column is stale/0 while IS_PENDING=1; read the real
        // bytes from the file descriptor instead.
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.statSize.coerceAtLeast(0L) }
        }.getOrNull() ?: 0L
    }

    override fun delete(serverId: String, profileId: String, fileId: Int, uriString: String?): Boolean {
        uriString?.let { uri ->
            return runCatching { resolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
        }
        // No URI (sidecar-independent path): delete by the fileId's relative path.
        // Exact match (mirrors locateByFileId) — never LIKE, so base64url ids
        // containing `_` can't wildcard-match a sibling scope's same-fileId row.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return deleteAcrossCollections { collection ->
            deleteByExactRelativePath(collection, mediaStoreRelativePath(collection, serverId, profileId, fileId))
        }
    }

    override fun deleteAllForProfile(serverId: String, profileId: String): Boolean =
        deleteAcrossCollections { collection ->
            deleteByRelativePathPrefix(collection, "${collection.relativeRoot}/Silo/${escapeLike(serverId)}/${escapeLike(profileId)}/%")
        }

    override fun deleteAllForServer(serverId: String): Boolean =
        deleteAcrossCollections { collection ->
            deleteByRelativePathPrefix(collection, "${collection.relativeRoot}/Silo/${escapeLike(serverId)}/%")
        }

    override fun totalBytesUsed(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        var total = 0L
        PublicDownloadCollection.entries.forEach { collection ->
            resolver.query(collection.contentUri(), projection, selection, arrayOf("${collection.relativeRoot}/Silo/%"), null)?.use { cursor ->
                while (cursor.moveToNext()) total += cursor.getLong(0)
            }
        }
        return total
    }

    override fun totalBytesUsed(serverId: String, profileId: String): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        var total = 0L
        PublicDownloadCollection.entries.forEach { collection ->
            resolver.query(
                collection.contentUri(),
                projection,
                selection,
                arrayOf("${collection.relativeRoot}/Silo/${escapeLike(serverId)}/${escapeLike(profileId)}/%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) total += cursor.getLong(0)
            }
        }
        return total
    }

    override fun complete(uriString: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(Uri.parse(uriString), values, null, null)
    }

    /** Exact-path delete (no wildcards). Used for single-file deletes. */
    private fun deleteByExactRelativePath(collection: PublicDownloadCollection, path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return resolver.delete(collection.contentUri(), selection, arrayOf(path)) > 0
    }

    /** Prefix delete (`.../%`). Caller must pre-escape dynamic segments via [escapeLike]. */
    private fun deleteByRelativePathPrefix(collection: PublicDownloadCollection, pattern: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        return resolver.delete(collection.contentUri(), selection, arrayOf(pattern)) > 0
    }

    /**
     * Escapes SQL LIKE metacharacters so a base64url scope id (whose alphabet
     * includes `_`) matches literally instead of as a single-char wildcard —
     * otherwise a scoped delete/scan could match a sibling scope. Pair with
     * `ESCAPE '\'`.
     */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun deleteAcrossCollections(block: (PublicDownloadCollection) -> Boolean): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            deleted = block(collection) || deleted
        }
        return deleted
    }
}

fun mediaStoreRelativePath(
    collection: PublicDownloadCollection,
    serverId: String,
    profileId: String,
    fileId: Int,
): String = "${collection.relativeRoot}/Silo/$serverId/$profileId/$fileId/"

fun legacyPublicCollectionRoots(): Map<PublicDownloadCollection, File> =
    mapOf(
        PublicDownloadCollection.Downloads to File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Silo",
        ),
        PublicDownloadCollection.Video to File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Silo",
        ),
        PublicDownloadCollection.Audio to File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Silo",
        ),
    )

enum class PublicDownloadCollection(val directoryName: String, val relativeRoot: String) {
    Video("Movies", "Movies"),
    Audio("Music", "Music"),
    Downloads("Downloads", "Downloads"),
    ;

    fun contentUri(): Uri = when (this) {
        Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Downloads -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    companion object {
        fun forMediaType(mediaType: String?): PublicDownloadCollection =
            when (DownloadMediaType.fromWire(mediaType)) {
                DownloadMediaType.Movie, DownloadMediaType.TvShow -> Video
                DownloadMediaType.Audiobook -> Audio
                DownloadMediaType.Ebook, DownloadMediaType.Unknown -> Downloads
            }
    }
}
