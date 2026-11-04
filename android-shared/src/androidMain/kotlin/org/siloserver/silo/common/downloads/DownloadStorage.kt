package org.siloserver.silo.common.downloads

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
 *
 * Layout: `<server>/acct-<account>/<profile>/<fileId>/` (silo-apple's `acct-`
 * layout), because two accounts on one server can share a profile id. Files
 * written before accounts existed stay at `<server>/<profile>/<fileId>/` and
 * belong to the account that adopted the server's old data
 * ([adoptLegacyFiles]); that account's lookups and deletes look there too.
 * Leaving them in place keeps every recorded path valid (a row's `localUri`,
 * the saved subtitle and artwork paths) without a move that could fail half
 * way. An empty account id is that pre-account layout.
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

    private val legacyOwners = LegacyDownloadOwners(File(baseDir, LEGACY_OWNERS_DIR))

    // Byte resolution is fileId-based (no sidecar read) so it stays synchronous —
    // the player surface (Compose) and worker call these on non-suspend paths.
    // Download metadata now lives in Room (suspend); only the bytes are file/MediaStore.
    fun locateLocalMedia(serverId: String, accountId: String, profileId: String, fileId: Int): DownloadLocation? =
        publicStore.locateByFileId(readScopes(serverId, accountId, profileId), fileId)

    fun locateLocalFile(serverId: String, accountId: String, profileId: String, fileId: Int): File? =
        (locateLocalMedia(serverId, accountId, profileId, fileId) as? FileDownloadLocation)?.file

    /**
     * Ensures the parent directory exists and returns the target file.
     * Caller is responsible for the actual write (typically via the
     * DownloadWorker's streaming copy).
     */
    fun prepareWrite(
        serverId: String,
        accountId: String,
        profileId: String,
        fileId: Int,
        fileName: String? = null,
        container: String? = null,
        mediaType: String? = null,
    ): DownloadTarget {
        val displayName = localMediaFileName(fileId, fileName, container)
        return publicStore.create(
            scopes = readScopes(serverId, accountId, profileId),
            fileId = fileId,
            displayName = displayName,
            container = container,
            mediaType = mediaType,
        )
    }

    fun exists(serverId: String, accountId: String, profileId: String, fileId: Int): Boolean =
        locateLocalMedia(serverId, accountId, profileId, fileId) != null

    /**
     * Deletes the local file for this slot. Returns true if a file was
     * actually removed (false if nothing existed to delete). Empty parent
     * directories are left in place; cleanup at higher granularity goes
     * through [deleteAllForProfile], [deleteAllForAccount] or [deleteAllForServer].
     */
    fun delete(serverId: String, accountId: String, profileId: String, fileId: Int): Boolean {
        val scopes = readScopes(serverId, accountId, profileId)
        scopes.forEach { scope -> containedSafeChild(offlineAssetsRoot, *scope.toTypedArray(), fileId.toString())?.deleteRecursively() }
        return publicStore.delete(scopes, fileId, uriString = null)
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

    fun totalBytesUsed(serverId: String, accountId: String, profileId: String): Long =
        readScopes(serverId, accountId, profileId).sumOf { publicStore.totalBytesUsed(it) }

    fun deleteUri(uriString: String): Boolean =
        publicStore.delete(emptyList(), -1, uriString)

    /** Wipes every downloaded byte under (serverId, accountId, profileId). Used
     *  on profile removal. Metadata rows are cleared via [DownloadMetadataStore]. */
    fun deleteAllForProfile(serverId: String, accountId: String, profileId: String): Boolean {
        var deleted = false
        readScopes(serverId, accountId, profileId).forEach { scope ->
            containedSafeChild(offlineAssetsRoot, *scope.toTypedArray())?.deleteRecursively()
            deleted = publicStore.deleteAll(scope) || deleted
        }
        return deleted
    }

    /**
     * Wipes every downloaded byte of one account on [serverId]: all of its
     * profiles, plus the pre-account files when it adopted them. Used when the
     * account is signed out. Never called with an empty [accountId]; files
     * written before accounts existed go with their server or their adopter.
     */
    fun deleteAllForAccount(serverId: String, accountId: String): Boolean {
        require(accountId.isNotEmpty()) { "Pre-account files are deleted with their server or adopter" }
        val accountScope = listOf(serverId, accountPathSegment(accountId))
        containedSafeChild(offlineAssetsRoot, *accountScope.toTypedArray())?.deleteRecursively()
        var deleted = publicStore.deleteAll(accountScope)
        if (legacyOwners.ownerOf(serverId) == accountId) {
            containedSafeChild(offlineAssetsRoot, serverId)?.listFiles().orEmpty()
                .filterNot { it.name.startsWith(ACCOUNT_SEGMENT_PREFIX) }
                .forEach { it.deleteRecursively() }
            deleted = publicStore.deleteLegacyScopes(serverId) || deleted
            legacyOwners.clear(serverId)
        }
        return deleted
    }

    /** Wipes every downloaded byte under (serverId). Used on server delete / re-bind. */
    fun deleteAllForServer(serverId: String): Boolean {
        containedSafeChild(offlineAssetsRoot, serverId)?.deleteRecursively()
        val deleted = publicStore.deleteAll(listOf(serverId))
        legacyOwners.clear(serverId)
        return deleted
    }

    /**
     * Gives [serverId]'s pre-account files to [accountId] (the account with
     * `adoptsLegacyData`). Offline and idempotent: it records the owner and
     * moves nothing. The first adopter keeps them; returns whether [accountId]
     * owns them now.
     */
    fun adoptLegacyFiles(serverId: String, accountId: String): Boolean {
        require(accountId.isNotEmpty())
        return legacyOwners.adopt(serverId, accountId)
    }

    /** The account that adopted [serverId]'s pre-account files, or null. */
    fun legacyFilesOwner(serverId: String): String? = legacyOwners.ownerOf(serverId)

    /**
     * Private directory for the offline subtitle sidecars of one download.
     * Kept in app-internal storage rather than beside the (public, possibly
     * MediaStore-owned) media file: they are only meaningful to this app, and
     * a scoped directory is removed with the download by [delete].
     */
    fun offlineSubtitleDirectory(serverId: String, accountId: String, profileId: String, fileId: Int): File? =
        containedSafeChild(offlineAssetsRoot, *writeScope(serverId, accountId, profileId).toTypedArray(), fileId.toString(), SUBTITLES_DIR)

    /**
     * Private directory for the artwork saved with one download. Separate from
     * [offlineSubtitleDirectory], which the track capture clears on every run,
     * and removed with the download by [delete].
     */
    fun offlineArtworkDirectory(serverId: String, accountId: String, profileId: String, fileId: Int): File? =
        containedSafeChild(offlineAssetsRoot, *writeScope(serverId, accountId, profileId).toTypedArray(), fileId.toString(), ARTWORK_DIR)

    private val offlineAssetsRoot: File get() = File(baseDir, OFFLINE_ASSETS_DIR)

    /** Where a slot's new files go. */
    private fun writeScope(serverId: String, accountId: String, profileId: String): List<String> =
        if (accountId.isEmpty()) listOf(serverId, profileId)
        else listOf(serverId, accountPathSegment(accountId), profileId)

    /** Where a slot's files may be: [writeScope], then the pre-account layout for its adopter. */
    private fun readScopes(serverId: String, accountId: String, profileId: String): List<List<String>> {
        val primary = writeScope(serverId, accountId, profileId)
        return if (accountId.isNotEmpty() && legacyOwners.ownerOf(serverId) == accountId) {
            listOf(primary, listOf(serverId, profileId))
        } else {
            listOf(primary)
        }
    }

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
        const val LEGACY_OWNERS_DIR = "download-legacy-owners"
        const val SUBTITLES_DIR = "subtitles"
        const val ARTWORK_DIR = "artwork"
    }
}

/** Directory segment prefix of an account's downloads, as on Apple (`acct-<account>`). */
internal const val ACCOUNT_SEGMENT_PREFIX = "acct-"

internal fun accountPathSegment(accountId: String): String = ACCOUNT_SEGMENT_PREFIX + accountId

/**
 * Which account owns each server's pre-account download files: one small file
 * per server holding the account id. Survives the account's sign-out until
 * its files are deleted, so a later purge still knows to remove them.
 */
private class LegacyDownloadOwners(private val dir: File) {
    private val cache = ConcurrentHashMap<String, String>()
    private val lock = Any()

    fun ownerOf(serverId: String): String? {
        cache[serverId]?.let { return it.ifEmpty { null } }
        val owner = markerFor(serverId)?.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            .orEmpty()
        cache[serverId] = owner
        return owner.ifEmpty { null }
    }

    fun adopt(serverId: String, accountId: String): Boolean = synchronized(lock) {
        ownerOf(serverId)?.let { return it == accountId }
        val marker = markerFor(serverId) ?: return false
        val written = runCatching {
            check(dir.mkdirs() || dir.isDirectory) { "Could not create the legacy owner directory" }
            val temp = File(dir, "${marker.name}.tmp")
            temp.writeText(accountId)
            check(temp.renameTo(marker)) { "Could not record the legacy owner" }
        }.isSuccess
        if (written) cache[serverId] = accountId
        written
    }

    fun clear(serverId: String) = synchronized(lock) {
        markerFor(serverId)?.delete()
        cache[serverId] = ""
    }

    private fun markerFor(serverId: String): File? = containedSafeChild(dir, serverId)
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

/**
 * Media bytes, addressed by a scope (the path segments above the file id:
 * `[server, acct-<account>, profile]`, or `[server, profile]` for files written
 * before accounts existed) and a file id. Where a list of scopes is taken, the
 * first is where a new file goes and the rest are older places a slot's file
 * may still be.
 */
interface PublicDownloadStore {
    fun create(
        scopes: List<List<String>>,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget
    fun locate(uriString: String): DownloadLocation?
    fun locateByFileId(scopes: List<List<String>>, fileId: Int): DownloadLocation?
    fun delete(scopes: List<List<String>>, fileId: Int, uriString: String?): Boolean
    fun complete(uriString: String): String = uriString

    /** Open an existing partial for append (resume). Null = unsupported/unavailable. */
    fun openAppend(uriString: String): OutputStream? = null

    /** Real on-disk byte count of a partial at [uriString] (fd stat, not a stale
     *  metadata SIZE column). 0 = gone/unreadable. */
    fun partialSize(uriString: String): Long = 0L

    /** Deletes everything under the scope prefix [scope] (a server, an account, or a profile). */
    fun deleteAll(scope: List<String>): Boolean

    /** Deletes [serverId]'s pre-account files: everything under it but the `acct-` directories. */
    fun deleteLegacyScopes(serverId: String): Boolean
    fun totalBytesUsed(): Long
    fun totalBytesUsed(scope: List<String>): Long
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
        scopes: List<List<String>>,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget {
        val collection = PublicDownloadCollection.forMediaType(mediaType)
        val root = collectionRoot(collection)
        val dir = requireNotNull(containedSafeChild(root, *scopes.first().toTypedArray(), fileId.toString())) {
            "Download path is outside its collection root"
        }
        check(dir.mkdirs() || dir.isDirectory) { "Could not create download directory" }
        val file = requireNotNull(directDisplayNameChild(dir, displayName)) {
            "Download display name is outside its file directory"
        }
        val replaced = PublicDownloadCollection.entries
            .flatMap { candidateCollection ->
                val candidateRoot = collectionRoot(candidateCollection)
                downloadCandidates(candidateRoot, scopes, fileId).flatMap { candidate ->
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

    override fun locateByFileId(scopes: List<List<String>>, fileId: Int): DownloadLocation? {
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            downloadCandidates(root, scopes, fileId).forEach { dir ->
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

    override fun delete(scopes: List<List<String>>, fileId: Int, uriString: String?): Boolean {
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
            downloadCandidates(root, scopes, fileId).forEach { target ->
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

    override fun deleteAll(scope: List<String>): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            scopedCandidates(root, *scope.toTypedArray()).forEach { target ->
                abortPendingWithin(target)
                deleted = deleteContainedRecursively(root, target) || deleted
            }
        }
        return deleted
    }

    override fun deleteLegacyScopes(serverId: String): Boolean {
        var deleted = false
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            scopedCandidates(root, serverId).forEach { serverDir ->
                serverDir.listFiles().orEmpty()
                    .filterNot { it.name.startsWith(ACCOUNT_SEGMENT_PREFIX) }
                    .forEach { target ->
                        abortPendingWithin(target)
                        deleted = deleteContainedRecursively(root, target) || deleted
                    }
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

    override fun totalBytesUsed(scope: List<String>): Long {
        var total = 0L
        PublicDownloadCollection.entries.forEach { collection ->
            val root = collectionRoot(collection)
            scopedCandidates(root, *scope.toTypedArray()).forEach { dir ->
                total += containedBytes(root, dir)
            }
        }
        return total
    }

    private fun collectionRoot(collection: PublicDownloadCollection): File =
        (rootsByCollection[collection] ?: error("No public download root for $collection")).canonicalFile

    private fun fileUriString(file: File): String =
        URI("file", "", file.absolutePath, null).toASCIIString()

    private fun downloadCandidates(root: File, scopes: List<List<String>>, fileId: Int): List<File> =
        distinctCandidates(
            *scopes.flatMap { scope ->
                val segments = (scope + fileId.toString()).toTypedArray()
                listOf(containedSafeChild(root, *segments), containedLegacyChild(root, *segments))
            }.toTypedArray(),
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
        scopes: List<List<String>>,
        fileId: Int,
        displayName: String,
        container: String?,
        mediaType: String?,
    ): DownloadTarget {
        val collection = PublicDownloadCollection.forMediaType(mediaType)
        val path = mediaStoreRelativePath(collection, scopes.first(), fileId)
        deleteByExactRelativePath(collection, path)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForDownloadName(displayName, container))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection.contentUri(), values) ?: error("Could not create MediaStore download")
        pendingReplacedDeletes[uri.toString()] = MediaStoreScope(collection, scopes, fileId)
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

    override fun locateByFileId(scopes: List<List<String>>, fileId: Int): DownloadLocation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        PublicDownloadCollection.entries.forEach { collection ->
            mediaStorePathCandidates(collection, scopes, fileId).forEach { path ->
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

    override fun delete(scopes: List<List<String>>, fileId: Int, uriString: String?): Boolean {
        uriString?.let { uri ->
            return runCatching { resolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
        }
        // No URI (sidecar-independent path): delete by the fileId's relative path.
        // Exact match (mirrors locateByFileId) — never LIKE, so base64url ids
        // containing `_` can't wildcard-match a sibling scope's same-fileId row.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return deleteAcrossCollections { collection ->
            var deleted = false
            mediaStorePathCandidates(collection, scopes, fileId).forEach { path ->
                deleted = deleteByExactRelativePath(collection, path) || deleted
            }
            deleted
        }
    }

    override fun deleteAll(scope: List<String>): Boolean =
        deleteAcrossCollections { collection ->
            var deleted = false
            mediaStorePrefixCandidates(collection, scope).forEach { pattern ->
                deleted = deleteByRelativePathPrefix(collection, pattern) || deleted
            }
            deleted
        }

    override fun deleteLegacyScopes(serverId: String): Boolean =
        deleteAcrossCollections { collection ->
            var deleted = false
            mediaStorePrefixCandidates(collection, listOf(serverId)).forEach { pattern ->
                // The server prefix without its trailing `%`, then `acct-%`.
                val accounts = pattern.removeSuffix("%") + escapeMediaStoreLike(ACCOUNT_SEGMENT_PREFIX) + "%"
                deleted = deleteIncludingPending(
                    collection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ? ESCAPE '\\'",
                    pattern,
                    accounts,
                ) || deleted
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

    override fun totalBytesUsed(scope: List<String>): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        var total = 0L
        PublicDownloadCollection.entries.forEach { collection ->
            mediaStorePrefixCandidates(collection, scope).forEach { pattern ->
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
            val newPath = mediaStoreRelativePath(scope.collection, scope.scopes.first(), scope.fileId)
            PublicDownloadCollection.entries.forEach { collection ->
                mediaStorePathCandidates(collection, scope.scopes, scope.fileId)
                    .filterNot { path -> collection == scope.collection && path == newPath }
                    .forEach { path -> deleteByExactRelativePath(collection, path) }
            }
        }
        return uriString
    }

    /** Exact-path delete (no wildcards). Used for single-file deletes. */
    private fun deleteByExactRelativePath(collection: PublicDownloadCollection, path: String): Boolean =
        deleteIncludingPending(collection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", path)

    /** Prefix delete (`.../%`). Caller must pre-escape dynamic segments via [escapeMediaStoreLike]. */
    private fun deleteByRelativePathPrefix(collection: PublicDownloadCollection, pattern: String): Boolean =
        deleteIncludingPending(collection, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'", pattern)

    /**
     * A collection-wide MediaStore match leaves out IS_PENDING items unless the
     * caller opts in, and an interrupted transfer's partial stays pending. Without
     * the opt-in a slot delete kept every partial (hidden `.pending-…` files the
     * OS only expires after about a week). A worker deletes its own slot only
     * after its transfer ends, and other callers wait for the slot's worker to
     * stop (cancelAndAwait) before deleting.
     */
    private fun deleteIncludingPending(collection: PublicDownloadCollection, selection: String, vararg arguments: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val deleted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.delete(
                collection.contentUri(),
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(*arguments))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                },
            )
        } else {
            @Suppress("DEPRECATION")
            resolver.delete(MediaStore.setIncludePending(collection.contentUri()), selection, arrayOf(*arguments))
        }
        return deleted > 0
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
        val scopes: List<List<String>>,
        val fileId: Int,
    )
}

fun mediaStoreRelativePath(
    collection: PublicDownloadCollection,
    scope: List<String>,
    fileId: Int,
): String = "${collection.relativeRoot}/Silo/" +
    (scope + fileId.toString()).joinToString(separator = "/", postfix = "/") { safePathSegment(it) }

internal fun mediaStoreRelativePathPrefix(
    collection: PublicDownloadCollection,
    scope: List<String>,
): String = buildString {
    append(collection.relativeRoot)
    append("/Silo/")
    scope.forEach { segment ->
        append(escapeMediaStoreLike(safePathSegment(segment)))
        append('/')
    }
    append('%')
}

internal fun escapeMediaStoreLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun legacyMediaStoreRelativePath(
    collection: PublicDownloadCollection,
    scope: List<String>,
    fileId: Int,
): String? {
    if (!scope.all(::isLegacyMediaStoreSegment)) return null
    return "${collection.relativeRoot}/Silo/${scope.joinToString("/")}/$fileId/"
}

private fun mediaStorePathCandidates(
    collection: PublicDownloadCollection,
    scopes: List<List<String>>,
    fileId: Int,
): List<String> =
    scopes.flatMap { scope ->
        listOfNotNull(
            mediaStoreRelativePath(collection, scope, fileId),
            legacyMediaStoreRelativePath(collection, scope, fileId),
        )
    }.distinct()

private fun mediaStorePrefixCandidates(
    collection: PublicDownloadCollection,
    scope: List<String>,
): List<String> {
    val encoded = mediaStoreRelativePathPrefix(collection, scope)
    val legacy = if (scope.all(::isLegacyMediaStoreSegment)) {
        buildString {
            append(collection.relativeRoot)
            append("/Silo/")
            scope.forEach { segment ->
                append(escapeMediaStoreLike(segment))
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
