package org.siloserver.silo.common.store

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Cleans local JSON state whose only identity index is its directory name.
 *
 * Directory names are compared in their encoded storage form because long
 * identity values intentionally use a one-way digest and cannot be decoded.
 * Tree deletion never follows symlinks.
 */
class ServerScopedJsonStatePurger(baseDir: File) {
    private val canonicalBase = baseDir.canonicalFile
    private val roots = listOf(
        File(canonicalBase, "ebook_state"),
        File(canonicalBase, "audiobook_bookmarks"),
    )

    fun allowRegistered(serverIds: Set<String>) {
        roots.mapNotNull(::validatedRoot).forEach { root ->
            serverIds.forEach { serverId ->
                ServerScopedJsonAccess.allow(root, safePathSegment(serverId))
            }
        }
    }

    fun deleteAllForServer(serverId: String) {
        roots.mapNotNull(::validatedRoot).forEach { root ->
            val safeKey = safePathSegment(serverId)
            ServerScopedJsonAccess.invalidateAndRun(root, safeKey) {
                // Keep the leaf lexical: canonicalising it would follow an
                // in-root symlink to a registered sibling and delete that
                // sibling's real directory.
                deleteTree(File(root, safeKey))
            }
            legacyDirectChild(root, serverId)?.let { legacy ->
                if (legacy.name != safeKey) {
                    ServerScopedJsonAccess.invalidateAndRun(root, legacy.name) {
                        deleteTree(legacy)
                    }
                }
            }
        }
    }

    private fun legacyDirectChild(root: File, serverId: String): File? =
        serverId.takeIf {
            it != "." && it != ".." && !it.startsWith("~") &&
                '/' !in it && '\\' !in it
        }?.let { File(root, it) }

    fun deleteJsonOnlyOrphans(
        registeredServerIds: Set<String>,
        protectedServerIds: Set<String>,
    ) {
        val retainedIds = registeredServerIds + protectedServerIds
        roots.mapNotNull(::validatedRoot).forEach { root ->
            registeredServerIds.forEach { serverId ->
                ServerScopedJsonAccess.allow(root, safePathSegment(serverId))
            }
            val retainedTopLevelNames = retainedIds.flatMapTo(mutableSetOf()) { serverId ->
                buildList {
                    add(safePathSegment(serverId))
                    containedLegacyChild(root, serverId)?.let { legacy ->
                        val relative = root.toPath().relativize(legacy.toPath())
                        relative.firstOrNull()?.toString()?.let(::add)
                    }
                }
            }
            root.listFiles().orEmpty()
                .filterNot { it.name in retainedTopLevelNames }
                .forEach { orphan ->
                    ServerScopedJsonAccess.invalidateAndRun(root, orphan.name) {
                        deleteTree(orphan)
                    }
                }
        }
    }

    /**
     * Moves [serverId]'s pre-account profile directories under [accountId]
     * (`<server>/<profile>/` → `<server>/@<account>/<profile>/`), the account
     * that adopts the server's old data. A file the account already has wins;
     * the old copy is then dropped. Idempotent and offline.
     */
    fun adoptLegacy(serverId: String, accountId: String) {
        if (!AccountScopedKeys.isAccountId(accountId)) return
        roots.mapNotNull(::validatedRoot).forEach { root ->
            val safeKey = safePathSegment(serverId)
            ServerScopedJsonAccess.withWriter(root, safeKey) {
                val accountDir = File(File(root, safeKey), AccountScopedKeys.token(accountId))
                val sources = listOfNotNull(File(root, safeKey), legacyDirectChild(root, serverId))
                    .distinctBy { it.name }
                    .filter(::isRealDirectory)
                sources.forEach { serverDir ->
                    serverDir.listFiles().orEmpty()
                        .filter { !it.name.startsWith("@") && isRealDirectory(it) }
                        .forEach { profileDir ->
                            // Encoded names are already in storage form; a raw
                            // pre-encoding name is encoded as fileFor would.
                            val name = if (profileDir.name.startsWith("~")) profileDir.name else safePathSegment(profileDir.name)
                            moveIfAbsent(profileDir, File(accountDir, name))
                        }
                }
            }
        }
    }

    /** Deletes the directories of every account not in [liveAccountIds]. */
    fun deleteAccountsExcept(liveAccountIds: Set<String>) {
        roots.mapNotNull(::validatedRoot).forEach { root ->
            root.listFiles().orEmpty().filter(::isRealDirectory).forEach { serverDir ->
                serverDir.listFiles().orEmpty()
                    .filter { child ->
                        val id = AccountScopedKeys.accountIdsIn(child.name).singleOrNull()
                        id != null && child.name == AccountScopedKeys.token(id) && id !in liveAccountIds
                    }
                    .forEach { gone ->
                        ServerScopedJsonAccess.withWriter(root, serverDir.name) { deleteTree(gone) }
                    }
            }
        }
    }

    private fun isRealDirectory(file: File): Boolean =
        Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    private fun moveIfAbsent(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!target.exists()) {
            runCatching { Files.move(source.toPath(), target.toPath()) }.onSuccess { return }
        }
        // The account already has some of this profile's files: keep its own,
        // take the rest, then drop what is left of the old directory.
        target.mkdirs()
        source.listFiles().orEmpty().forEach { file ->
            val destination = File(target, file.name)
            if (!destination.exists()) runCatching { Files.move(file.toPath(), destination.toPath()) }
        }
        deleteTree(source)
    }

    private fun validatedRoot(root: File): File? = runCatching {
        val path = root.toPath()
        if (Files.isSymbolicLink(path)) return@runCatching null
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        ) {
            return@runCatching null
        }
        root.canonicalFile.takeIf { canonical ->
            canonical.parentFile == canonicalBase &&
                canonical.path.startsWith(canonicalBase.path + File.separator)
        }
    }.getOrNull()

    private fun deleteTree(candidate: File) {
        val path = candidate.toPath()
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    // A dangling or unreadable symlink is still safe to remove as
                    // a directory entry; Files.delete does not follow it.
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
