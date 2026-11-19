package org.siloserver.silo.common.store

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared plumbing for the per-(serverId, accountId, profileId, contentId)
 * JSON file stores (ebook reading state, audiobook positions/bookmarks):
 * scoped path resolution under a root directory
 * (`<server>/@<account>/<profile>/<content>`), one lenient [Json]
 * instance, safe reads (missing/corrupt files log and return null),
 * and atomic writes (tmp + fsync + rename) so a crash mid-write can't
 * leave a half-written file that fails to decode next launch.
 */
internal class ScopedJsonFileStore(
    private val root: File,
    internal val tag: String,
) {
    private val canonicalRoot: File = root.canonicalFile
    private val legacyByTarget = ConcurrentHashMap<String, File>()
    private val targetLockStripes = Array(TARGET_LOCK_STRIPES) { Any() }

    internal fun rootDirectory(): File = canonicalRoot

    internal fun <T> withTargetLock(target: File, block: () -> T): T {
        val hash = target.canonicalPath.hashCode() and Int.MAX_VALUE
        val lock = targetLockStripes[hash % TARGET_LOCK_STRIPES]
        return synchronized(lock, block)
    }

    /**
     * [accountId] null is the pre-account layout (`<server>/<profile>/…`),
     * which only the account that adopts the server's old data ever had;
     * [AccountLocalData] moves it under that account's directory.
     */
    internal fun fileFor(
        serverId: String,
        accountId: String?,
        profileId: String,
        contentId: String,
        suffix: String = ".json",
    ): File {
        val directory = requireNotNull(profileDirectory(serverId, accountId, profileId)) {
            "Scoped JSON directory is outside its root"
        }
        val target = requireNotNull(containedFile(directory, safePathSegment(contentId) + suffix)) {
            "Scoped JSON filename is outside its directory"
        }
        // A pre-encoding file keeps its raw content-id name, also after
        // adoption moved its profile directory under the account.
        val legacy = if (accountId == null) {
            containedLegacyChild(canonicalRoot, serverId, profileId, contentId + suffix)
        } else {
            containedLegacyChild(directory, contentId + suffix)?.takeIf { isContained(it) }
        }
        legacy
            ?.takeIf { it.canonicalPath != target.canonicalPath }
            ?.let { legacyByTarget[target.canonicalPath] = it }
        return target
    }

    private fun profileDirectory(serverId: String, accountId: String?, profileId: String): File? {
        if (accountId == null) return containedSafeChild(canonicalRoot, serverId, profileId)
        val serverDirectory = containedSafeChild(canonicalRoot, serverId) ?: return null
        // A local account id is a UUID, so its token is already a safe segment.
        // Anything else (never written by the registry) is encoded instead.
        val token = AccountScopedKeys.token(accountId)
        val accountSegment = if (AccountScopedKeys.isAccountId(accountId)) token else safePathSegment(token)
        return containedSafeChild(File(serverDirectory, accountSegment), profileId)
            ?.takeIf { isContained(it) }
    }

    internal inline fun <reified T> read(file: File): T? {
        val source = containedReadCandidate(file) ?: return null
        return runCatching { json.decodeFromString<T>(source.readText()) }
            .onFailure { Log.w(tag, "read failed for ${source.path}", it) }
            .getOrNull()
    }

    internal inline fun <reified T> write(target: File, value: T) {
        writeAtomic(target, json.encodeToString(value))
    }

    internal fun writeAtomic(target: File, text: String) {
        val serverStorageKey = runCatching {
            canonicalRoot.toPath().relativize(target.canonicalFile.toPath()).first().toString()
        }.getOrNull() ?: run {
            Log.w(tag, "write rejected without a server scope")
            return
        }
        val written = ServerScopedJsonAccess.withWriter(canonicalRoot, serverStorageKey) {
            writeAtomicUnlocked(target, text)
        }
        if (written == null) Log.w(tag, "write rejected for removed server scope")
    }

    private fun writeAtomicUnlocked(target: File, text: String) {
        if (!isContained(target)) {
            Log.w(tag, "write rejected outside store root")
            return
        }
        target.parentFile?.mkdirs()
        val parent = target.parentFile?.canonicalFile
        if (parent == null || !isContained(parent)) {
            Log.w(tag, "write rejected after parent changed")
            return
        }
        val staged = createExclusiveTemp(parent) ?: run {
            Log.w(tag, "could not create atomic temp file")
            return
        }
        val tmp = staged.file
        val bytes = text.toByteArray(Charsets.UTF_8)
        val published = runCatching {
            staged.channel.use { channel ->
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                (channel as? FileChannel)?.force(true)
            }
            staged.publishTo(target)
            true
        }.getOrElse {
            Log.w(tag, "atomic rename failed for ${target.path}")
            false
        }
        if (!published) {
            Files.deleteIfExists(tmp.toPath())
            return
        }
        legacyByTarget.remove(target.canonicalPath)
            ?.takeIf(::isContained)
            ?.let { legacy -> Files.deleteIfExists(legacy.toPath()) }
    }

    internal fun containedReadCandidate(file: File): File? {
        if (!isContained(file)) return null
        if (file.isFile) return file
        return legacyByTarget[file.canonicalPath]
            ?.takeIf(::isContained)
            ?.takeIf { it.isFile }
    }

    private fun isContained(file: File): Boolean =
        runCatching {
            file.canonicalPath.startsWith(canonicalRoot.path + File.separator)
        }.getOrDefault(false)

    private fun containedFile(directory: File, fileName: String): File? =
        runCatching {
            File(directory, fileName).canonicalFile.takeIf { target ->
                target.parentFile?.canonicalFile == directory.canonicalFile &&
                    isContained(target)
            }
        }.getOrNull()

    private fun createExclusiveTemp(parent: File): ExclusiveTemp? {
        repeat(MAX_TEMP_ATTEMPTS) {
            val candidate = File(parent, ".silo-${UUID.randomUUID()}.tmp")
            var directoryStream: java.nio.file.DirectoryStream<Path>? = null
            try {
                directoryStream = Files.newDirectoryStream(parent.toPath())
                if (directoryStream is SecureDirectoryStream<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val secure = directoryStream as SecureDirectoryStream<Path>
                    val relative = parent.toPath().fileSystem.getPath(candidate.name)
                    val channel = secure.newByteChannel(relative, CREATE_NEW_NOFOLLOW)
                    return ExclusiveTemp(candidate, channel, secure, relative)
                }
                directoryStream.close()
                directoryStream = null
                return ExclusiveTemp(
                    candidate,
                    FileChannel.open(candidate.toPath(), *CREATE_NEW_NOFOLLOW.toTypedArray()),
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
        return null
    }

    private inner class ExclusiveTemp(
        val file: File,
        val channel: SeekableByteChannel,
        val secureDirectory: SecureDirectoryStream<Path>?,
        val relativePath: Path?,
    ) {
        fun publishTo(target: File) {
            val secure = secureDirectory
            val source = relativePath
            if (secure != null && source != null) {
                try {
                    secure.move(source, secure, source.fileSystem.getPath(target.name))
                } finally {
                    secure.close()
                }
            } else {
                moveReplacingWithoutFollowing(file, target)
            }
        }
    }

    /**
     * `Files.move` replaces a destination symlink as a directory entry; it does
     * not follow the link. `ATOMIC_MOVE` is used on the same-directory fast path.
     * Providers without atomic-move support retain no-follow replacement but
     * cannot provide crash-atomic publication.
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

    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
        private const val MAX_TEMP_ATTEMPTS = 8
        private const val TARGET_LOCK_STRIPES = 64
        private val CREATE_NEW_NOFOLLOW: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
}
