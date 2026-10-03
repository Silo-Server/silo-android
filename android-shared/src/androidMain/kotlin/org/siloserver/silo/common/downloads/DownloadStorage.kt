package org.siloserver.silo.common.downloads

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.siloserver.silo.common.store.containedLegacyChild
import org.siloserver.silo.common.store.containedSafeChild
import org.siloserver.silo.common.store.safePathSegment
import org.siloserver.silo.model.download.DownloadMediaType
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
        val displayName = localMediaFileName(fileId, fileName, container)
        return publicStore.create(
            serverId = serverId,
            profileId = profileId,
            fileId = fileId,
            displayName = displayName,
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
    fun delete(serverId: String, profileId: String, fileId: Int): Boolean {
        containedSafeChild(offlineAssetsRoot, serverId, profileId, fileId.toString())?.deleteRecursively()
        return publicStore.delete(serverId, profileId, fileId, uriString = null)
    }

    fun completeWrite(uriString: String): String =
        publicStore.complete(uriString)

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
    fun deleteAllForProfile(serverId: String, profileId: String): Boolean {
        containedSafeChild(offlineAssetsRoot, serverId, profileId)?.deleteRecursively()
        return publicStore.deleteAllForProfile(serverId, profileId)
    }

    /** Wipes every downloaded byte under (serverId). Used on server delete / re-bind. */
    fun deleteAllForServer(serverId: String): Boolean {
        containedSafeChild(offlineAssetsRoot, serverId)?.deleteRecursively()
        return publicStore.deleteAllForServer(serverId)
    }

    /**
     * Private directory for the offline subtitle sidecars of one download.
     * Kept in app-internal storage rather than beside the (public, possibly
     * MediaStore-owned) media file: they are only meaningful to this app, and
     * a scoped directory is removed with the download by [delete].
     */
    fun offlineSubtitleDirectory(serverId: String, profileId: String, fileId: Int): File? =
        containedSafeChild(offlineAssetsRoot, serverId, profileId, fileId.toString(), SUBTITLES_DIR)

    /**
     * Private directory for the artwork saved with one download. Separate from
     * [offlineSubtitleDirectory], which the track capture clears on every run,
     * and removed with the download by [delete].
     */
    fun offlineArtworkDirectory(serverId: String, profileId: String, fileId: Int): File? =
        containedSafeChild(offlineAssetsRoot, serverId, profileId, fileId.toString(), ARTWORK_DIR)

    private val offlineAssetsRoot: File get() = File(baseDir, OFFLINE_ASSETS_DIR)

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

    private companion object {
        const val OFFLINE_ASSETS_DIR = "download-assets"
        const val SUBTITLES_DIR = "subtitles"
        const val ARTWORK_DIR = "artwork"
    }
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
    fun complete(uriString: String): String = uriString

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
    private val roots: List<File> = rootsByCollection.values
        .map { file -> runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile) }
        .distinctBy { file -> file.path }
    private val pendingReplacedDeletes = ConcurrentHashMap<String, List<File>>()
    private val pendingWrites = ConcurrentHashMap<String, StagedDownload>()

    override fun create(
        serverId: String,
        profileId: String,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget {
        val collection = PublicDownloadCollection.forMediaType(mediaType)
        val root = collectionRoot(collection)
        val dir = requireNotNull(containedSafeChild(root, serverId, profileId, fileId.toString())) {
            "Download path is outside its collection root"
        }
        check(dir.mkdirs() || dir.isDirectory) { "Could not create download directory" }
        val file = requireNotNull(directDisplayNameChild(dir, displayName)) {
            "Download display name is outside its file directory"
        }
        val replaced = PublicDownloadCollection.entries
            .flatMap { candidateCollection ->
                val candidateRoot = collectionRoot(candidateCollection)
                downloadCandidates(candidateRoot, serverId, profileId, fileId).flatMap { candidate ->
                    if (candidate.canonicalPath == dir.canonicalPath) {
                        candidate.listFiles()
                            .orEmpty()
                            .filter { child ->
                                child.name != file.name &&
                                    !child.name.startsWith(STAGED_FILE_PREFIX) &&
                                    isRegularContainedFile(candidateRoot, child)
                            }
                    } else {
                        listOf(candidate)
                    }
                }
            }
            .filter { candidate -> candidate.exists() }
            .distinctBy { candidate -> candidate.canonicalPath }
        val staged = createStagedDownload(dir, file, displayName)
        val uriString = staged.uriString
        abortPendingWithin(dir)
        pendingWrites[uriString] = staged
        if (replaced.isNotEmpty()) pendingReplacedDeletes[uriString] = replaced
        return DownloadTarget(
            uriString = uriString,
            displayName = displayName,
            openOutputStream = staged::claimOutputStream,
            sizeBytes = { staged.tempFile.length() },
        )
    }

    override fun locate(uriString: String): DownloadLocation? {
        if (!uriString.startsWith("file:", ignoreCase = true)) return null
        val reference = parseFileReference(uriString) ?: return null
        val file = reference.file.canonicalFile
        if (!file.isFile || roots.none { file.isContainedBy(it) }) return null
        return FileDownloadLocation(uriString, reference.displayName ?: file.name, file.length(), file)
    }

    override fun locateByFileId(serverId: String, profileId: String, fileId: Int): DownloadLocation? {
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            downloadCandidates(root, serverId, profileId, fileId).forEach { dir ->
                val file = firstContainedFile(root, dir)
                if (file != null) {
                    val staged = pendingWrites.values.firstOrNull { pending ->
                        pending.tempFile.absolutePath == file.absolutePath
                    }
                    return FileDownloadLocation(
                        staged?.uriString ?: fileUriString(file),
                        staged?.displayName ?: file.name,
                        file.length(),
                        file,
                    )
                }
            }
        }
        return null
    }

    override fun delete(serverId: String, profileId: String, fileId: Int, uriString: String?): Boolean {
        uriString?.let { pendingUri ->
            pendingWrites.remove(pendingUri)?.let { staged ->
                pendingReplacedDeletes.remove(pendingUri)
                return staged.abort()
            }
        }
        val fromUri = uriString?.let { locate(it) as? FileDownloadLocation }?.file
        if (fromUri != null) {
            val root = roots.firstOrNull { fromUri.isContainedBy(it) } ?: return false
            return deleteContainedRecursively(root, fromUri)
        }
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            downloadCandidates(root, serverId, profileId, fileId).forEach { target ->
                abortPendingWithin(target)
                deleted = deleteContainedRecursively(root, target) || deleted
            }
        }
        return deleted
    }

    override fun complete(uriString: String): String {
        val staged = pendingWrites.remove(uriString)
        try {
            val reference = parseFileReference(uriString)
                ?: throw IOException("Invalid completed-download URI")
            val tempFile = staged?.tempFile ?: reference.file
            val displayName = staged?.displayName ?: reference.displayName
            if (displayName == null) {
                val existing = (locate(uriString) as? FileDownloadLocation)?.file ?: return uriString
                onFileCompleted(existing)
                return fileUriString(existing)
            }
            staged?.closeBeforePublish()
            val root = roots.firstOrNull { tempFile.isContainedBy(it) }
                ?: throw IOException("Staged download escaped its collection root")
            if (!isRegularContainedFile(root, tempFile)) {
                throw IOException("Staged download is missing or is not a regular file")
            }
            val finalFile = staged?.finalFile
                ?: tempFile.parentFile?.let { parent -> directDisplayNameChild(parent, displayName) }
                ?: throw IOException("Invalid completed-download display name")
            if (staged != null) {
                staged.publish()
            } else {
                moveReplacingWithoutFollowing(tempFile, finalFile)
            }
            onFileCompleted(finalFile)
            pendingReplacedDeletes.remove(uriString).orEmpty().forEach { replaced ->
                roots.firstOrNull { replaced.isContainedBy(it) }?.let { replacementRoot ->
                    deleteContainedRecursively(replacementRoot, replaced)
                }
            }
            return fileUriString(finalFile)
        } catch (failure: Throwable) {
            staged?.closeForRetry()
            throw failure
        }
    }

    override fun openAppend(uriString: String): OutputStream? {
        val file = (locate(uriString) as? FileDownloadLocation)?.file ?: return null
        return runCatching {
            Channels.newOutputStream(
                FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
        }.getOrNull()
    }

    override fun partialSize(uriString: String): Long =
        (locate(uriString) as? FileDownloadLocation)?.file?.takeIf { it.isFile }?.length() ?: 0L

    override fun deleteAllForProfile(serverId: String, profileId: String): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            scopedCandidates(root, serverId, profileId).forEach { target ->
                abortPendingWithin(target)
                deleted = deleteContainedRecursively(root, target) || deleted
            }
        }
        return deleted
    }

    override fun deleteAllForServer(serverId: String): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            scopedCandidates(root, serverId).forEach { target ->
                abortPendingWithin(target)
                deleted = deleteContainedRecursively(root, target) || deleted
            }
        }
        return deleted
    }

    override fun totalBytesUsed(): Long {
        var total = 0L
        roots.forEach { root ->
            total += containedBytes(root, root)
        }
        return total
    }

    override fun totalBytesUsed(serverId: String, profileId: String): Long {
        var total = 0L
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            scopedCandidates(root, serverId, profileId).forEach { dir ->
                total += containedBytes(root, dir)
            }
        }
        return total
    }

    private fun collectionRoot(collection: PublicDownloadCollection): File =
        (rootsByCollection[collection] ?: error("No public download root for $collection")).canonicalFile

    private fun fileUriString(file: File): String =
        URI("file", "", file.absolutePath, null).toASCIIString()

    private fun downloadCandidates(root: File, serverId: String, profileId: String, fileId: Int): List<File> =
        distinctCandidates(
            containedSafeChild(root, serverId, profileId, fileId.toString()),
            containedLegacyChild(root, serverId, profileId, fileId.toString()),
        )

    private fun scopedCandidates(root: File, vararg segments: String): List<File> =
        distinctCandidates(
            containedSafeChild(root, *segments),
            containedLegacyChild(root, *segments),
        )

    private fun distinctCandidates(vararg candidates: File?): List<File> =
        candidates.filterNotNull().distinctBy { it.canonicalPath }

    private fun directDisplayNameChild(directory: File, displayName: String): File? {
        if (displayName.isEmpty() || displayName == "." || displayName == "..") return null
        if ('/' in displayName || '\\' in displayName) return null
        return File(directory, displayName).absoluteFile.takeIf { candidate ->
            candidate.parentFile == directory.absoluteFile
        }
    }

    private fun firstContainedFile(root: File, directory: File): File? {
        if (!directory.isContainedBy(root) || !directory.isDirectory) return null
        return directory.listFiles()
            ?.asSequence()
            ?.filter { child -> isRegularContainedFile(root, child) }
            ?.map { it.canonicalFile }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    /**
     * Walks without `FOLLOW_LINKS`, so a symlink is deleted as an entry and its
     * target is never visited. Android does not guarantee
     * [java.nio.file.SecureDirectoryStream] for every filesystem provider, so
     * parent namespace replacement between the containment check and the walk
     * remains a platform limitation; managed roots are app-owned/private where
     * applicable, and each write target uses an unguessable no-follow stage.
     */
    private fun deleteContainedRecursively(root: File, target: File): Boolean {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        val canonicalParent = runCatching { target.parentFile?.canonicalFile }.getOrNull() ?: return false
        if (!canonicalParent.isContainedBy(root, allowRoot = true)) return false
        var deleted = false
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                deleted = Files.deleteIfExists(file) || deleted
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                deleted = Files.deleteIfExists(directory) || deleted
                return FileVisitResult.CONTINUE
            }
        })
        return deleted
    }

    private fun containedBytes(root: File, target: File): Long {
        if (!target.exists()) return 0L
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return 0L
        if (target.absoluteFile.path != canonicalTarget.path) return 0L
        if (!canonicalTarget.isContainedBy(root, allowRoot = true)) return 0L
        if (canonicalTarget.isFile) return canonicalTarget.length()
        if (!canonicalTarget.isDirectory) return 0L
        return canonicalTarget.listFiles().orEmpty().sumOf { child ->
            containedBytes(root, child)
        }
    }

    private fun File.isContainedBy(root: File, allowRoot: Boolean = false): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val canonicalTarget = runCatching { canonicalFile }.getOrNull() ?: return false
        return (allowRoot && canonicalTarget.path == canonicalRoot.path) ||
            canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
    }

    private fun createStagedDownload(directory: File, finalFile: File, displayName: String): StagedDownload {
        repeat(MAX_TEMP_ATTEMPTS) {
            val tempFile = File(directory, "$STAGED_FILE_PREFIX${UUID.randomUUID()}.tmp")
            var directoryStream: java.nio.file.DirectoryStream<Path>? = null
            try {
                val uriString = pendingFileUriString(tempFile, displayName)
                directoryStream = Files.newDirectoryStream(directory.toPath())
                if (directoryStream is SecureDirectoryStream<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val secure = directoryStream as SecureDirectoryStream<Path>
                    val relative = directory.toPath().fileSystem.getPath(tempFile.name)
                    val channel = secure.newByteChannel(relative, CREATE_NEW_NOFOLLOW)
                    return StagedDownload(
                        tempFile,
                        finalFile,
                        displayName,
                        uriString,
                        channel,
                        secure,
                        relative,
                    )
                }
                directoryStream.close()
                directoryStream = null
                return StagedDownload(
                    tempFile,
                    finalFile,
                    displayName,
                    uriString,
                    FileChannel.open(tempFile.toPath(), *CREATE_NEW_NOFOLLOW.toTypedArray()),
                    secureDirectory = null,
                    relativePath = null,
                )
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                directoryStream?.close()
                // Retry with a new unguessable name.
            } catch (failure: Throwable) {
                directoryStream?.close()
                throw failure
            }
        }
        error("Could not create staged download")
    }

    private fun pendingFileUriString(file: File, displayName: String): String =
        URI("file", "", file.absolutePath, null, displayName).toASCIIString()

    private fun parseFileReference(uriString: String): FileReference? =
        runCatching {
            val uri = URI(uriString)
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            val pathOnly = URI(uri.scheme, uri.authority, uri.path, null, null)
            FileReference(File(pathOnly), uri.fragment)
        }.getOrNull()

    private fun isRegularContainedFile(root: File, file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return file.absoluteFile.path == canonical.path &&
            canonical.isContainedBy(root) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun abortPendingWithin(directory: File) {
        val directoryPath = runCatching { directory.canonicalPath + File.separator }.getOrNull() ?: return
        pendingWrites.entries.toList().forEach { (uri, staged) ->
            if (staged.tempFile.absolutePath.startsWith(directoryPath) && pendingWrites.remove(uri, staged)) {
                pendingReplacedDeletes.remove(uri)
                staged.abort()
            }
        }
    }

    /**
     * Same-directory atomic replacement changes the directory entry itself, so
     * an attacker-created destination symlink is replaced rather than followed.
     * The fallback remains no-follow but loses crash atomicity on providers that
     * do not implement `ATOMIC_MOVE`.
     */
    private fun moveReplacingWithoutFollowing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class FileReference(val file: File, val displayName: String?)

    private inner class StagedDownload(
        val tempFile: File,
        val finalFile: File,
        val displayName: String,
        val uriString: String,
        private val channel: SeekableByteChannel,
        private val secureDirectory: SecureDirectoryStream<Path>?,
        private val relativePath: Path?,
    ) {
        private val claimed = AtomicBoolean(false)

        fun claimOutputStream(): OutputStream {
            check(claimed.compareAndSet(false, true)) { "Download output stream already opened" }
            return Channels.newOutputStream(channel)
        }

        fun closeBeforePublish() {
            if (channel.isOpen) {
                (channel as? FileChannel)?.force(true)
                channel.close()
            }
        }

        fun closeForRetry() {
            runCatching { closeBeforePublish() }
            runCatching { secureDirectory?.close() }
        }

        fun publish() {
            val secure = secureDirectory
            val source = relativePath
            if (secure != null && source != null) {
                try {
                    secure.move(source, secure, source.fileSystem.getPath(finalFile.name))
                } finally {
                    secure.close()
                }
            } else {
                moveReplacingWithoutFollowing(tempFile, finalFile)
            }
        }

        fun abort(): Boolean {
            closeForRetry()
            return runCatching { Files.deleteIfExists(tempFile.toPath()) }.getOrDefault(false)
        }
    }

    companion object {
        private const val STAGED_FILE_PREFIX = ".silo-stage-"
        private const val MAX_TEMP_ATTEMPTS = 8
        private val CREATE_NEW_NOFOLLOW: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
}

class MediaStorePublicDownloadStore(context: Context) : PublicDownloadStore {
    private val resolver: ContentResolver = context.contentResolver
    private val pendingReplacedDeletes = ConcurrentHashMap<String, MediaStoreScope>()

    override fun create(
        serverId: String,
        profileId: String,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget {
        val collection = PublicDownloadCollection.forMediaType(mediaType)
        deleteByExactRelativePath(collection, mediaStoreRelativePath(collection, serverId, profileId, fileId))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForDownloadName(displayName, container))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelativePath(collection, serverId, profileId, fileId))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection.contentUri(), values) ?: error("Could not create MediaStore download")
        pendingReplacedDeletes[uri.toString()] = MediaStoreScope(collection, serverId, profileId, fileId)
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
            mediaStorePathCandidates(collection, serverId, profileId, fileId).forEach { path ->
                resolver.query(
                    collection.contentUri(),
                    projection,
                    selection,
                    arrayOf(path),
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
            var deleted = false
            mediaStorePathCandidates(collection, serverId, profileId, fileId).forEach { path ->
                deleted = deleteByExactRelativePath(collection, path) || deleted
            }
            deleted
        }
    }

    override fun deleteAllForProfile(serverId: String, profileId: String): Boolean =
        deleteAcrossCollections { collection ->
            var deleted = false
            mediaStorePrefixCandidates(collection, serverId, profileId).forEach { pattern ->
                deleted = deleteByRelativePathPrefix(collection, pattern) || deleted
            }
            deleted
        }

    override fun deleteAllForServer(serverId: String): Boolean =
        deleteAcrossCollections { collection ->
            var deleted = false
            mediaStorePrefixCandidates(collection, serverId).forEach { pattern ->
                deleted = deleteByRelativePathPrefix(collection, pattern) || deleted
            }
            deleted
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
            mediaStorePrefixCandidates(collection, serverId, profileId).forEach { pattern ->
                resolver.query(
                    collection.contentUri(),
                    projection,
                    selection,
                    arrayOf(pattern),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) total += cursor.getLong(0)
                }
            }
        }
        return total
    }

    override fun complete(uriString: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uriString
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(Uri.parse(uriString), values, null, null) <= 0) return uriString
        pendingReplacedDeletes.remove(uriString)?.let { scope ->
            val newPath = mediaStoreRelativePath(scope.collection, scope.serverId, scope.profileId, scope.fileId)
            PublicDownloadCollection.entries.forEach { collection ->
                mediaStorePathCandidates(collection, scope.serverId, scope.profileId, scope.fileId)
                    .filterNot { path -> collection == scope.collection && path == newPath }
                    .forEach { path -> deleteByExactRelativePath(collection, path) }
            }
        }
        return uriString
    }

    /** Exact-path delete (no wildcards). Used for single-file deletes. */
    private fun deleteByExactRelativePath(collection: PublicDownloadCollection, path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return resolver.delete(collection.contentUri(), selection, arrayOf(path)) > 0
    }

    /** Prefix delete (`.../%`). Caller must pre-escape dynamic segments via [escapeMediaStoreLike]. */
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
    private fun deleteAcrossCollections(block: (PublicDownloadCollection) -> Boolean): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            deleted = block(collection) || deleted
        }
        return deleted
    }

    private data class MediaStoreScope(
        val collection: PublicDownloadCollection,
        val serverId: String,
        val profileId: String,
        val fileId: Int,
    )
}

fun mediaStoreRelativePath(
    collection: PublicDownloadCollection,
    serverId: String,
    profileId: String,
    fileId: Int,
): String = "${collection.relativeRoot}/Silo/${safePathSegment(serverId)}/${safePathSegment(profileId)}/${safePathSegment(fileId.toString())}/"

internal fun mediaStoreRelativePathPrefix(
    collection: PublicDownloadCollection,
    serverId: String,
    profileId: String? = null,
): String = buildString {
    append(collection.relativeRoot)
    append("/Silo/")
    append(escapeMediaStoreLike(safePathSegment(serverId)))
    append('/')
    if (profileId != null) {
        append(escapeMediaStoreLike(safePathSegment(profileId)))
        append('/')
    }
    append('%')
}

internal fun escapeMediaStoreLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun legacyMediaStoreRelativePath(
    collection: PublicDownloadCollection,
    serverId: String,
    profileId: String,
    fileId: Int,
): String? {
    if (!isLegacyMediaStoreSegment(serverId) || !isLegacyMediaStoreSegment(profileId)) return null
    return "${collection.relativeRoot}/Silo/$serverId/$profileId/$fileId/"
}

private fun mediaStorePathCandidates(
    collection: PublicDownloadCollection,
    serverId: String,
    profileId: String,
    fileId: Int,
): List<String> =
    listOfNotNull(
        mediaStoreRelativePath(collection, serverId, profileId, fileId),
        legacyMediaStoreRelativePath(collection, serverId, profileId, fileId),
    ).distinct()

private fun mediaStorePrefixCandidates(
    collection: PublicDownloadCollection,
    serverId: String,
    profileId: String? = null,
): List<String> {
    val encoded = mediaStoreRelativePathPrefix(collection, serverId, profileId)
    val legacy = if (
        isLegacyMediaStoreSegment(serverId) &&
        (profileId == null || isLegacyMediaStoreSegment(profileId))
    ) {
        buildString {
            append(collection.relativeRoot)
            append("/Silo/")
            append(escapeMediaStoreLike(serverId))
            append('/')
            if (profileId != null) {
                append(escapeMediaStoreLike(profileId))
                append('/')
            }
            append('%')
        }
    } else {
        null
    }
    return listOfNotNull(encoded, legacy).distinct()
}

private fun isLegacyMediaStoreSegment(value: String): Boolean =
    value.isNotEmpty() &&
        !value.startsWith("~") &&
        value != "." &&
        value != ".." &&
        '/' !in value &&
        '\\' !in value

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
    // MediaProvider validates RELATIVE_PATH against its own allow-list, which
    // holds the SINGULAR "Download" (Environment.DIRECTORY_DOWNLOADS). The
    // plural spelling is rejected with IllegalArgumentException — not an
    // IOException — so DownloadWorker failed the download permanently and every
    // retry failed identically; ebooks could never be downloaded at all.
    //
    // These are written as literals rather than Environment constants because
    // this enum is constructed in plain JVM unit tests, where android.os
    // statics are unavailable and would fail class initialisation. The values
    // are fixed platform API and are asserted against the real constants in
    // publicDownloadRelativeRootsMatchPlatformConstants().
    //
    // directoryName stays plural: it drives the file-backed layout, not the
    // MediaStore insert.
    Downloads("Downloads", "Download"),
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
