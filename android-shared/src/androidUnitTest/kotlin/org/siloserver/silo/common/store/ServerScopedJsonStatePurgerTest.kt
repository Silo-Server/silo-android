package org.siloserver.silo.common.store

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerScopedJsonStatePurgerTest {

    @Test
    fun `orphan symlink is removed without touching its target`() {
        val base = Files.createTempDirectory("silo-json-purge").toFile()
        val external = Files.createTempDirectory("silo-json-external")
        val sentinel = external.resolve("keep.txt")
        Files.write(sentinel, "keep".toByteArray())
        val root = base.toPath().resolve("ebook_state")
        Files.createDirectories(root)
        val link = root.resolve("orphan-server")
        Files.createSymbolicLink(link, external)

        ServerScopedJsonStatePurger(base).deleteJsonOnlyOrphans(
            registeredServerIds = emptySet(),
            protectedServerIds = emptySet(),
        )

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(sentinel), "purge must not follow a server-directory symlink")
    }

    @Test
    fun `orphan symlink to a registered sibling removes only the link`() {
        val base = Files.createTempDirectory("silo-json-sibling-link").toFile()
        val root = base.toPath().resolve("ebook_state")
        val kept = root.resolve("kept-server")
        Files.createDirectories(kept)
        val sentinel = kept.resolve("keep.txt")
        Files.write(sentinel, "keep".toByteArray())
        val removedLink = root.resolve("removed-server")
        Files.createSymbolicLink(removedLink, kept)

        ServerScopedJsonStatePurger(base).deleteAllForServer("removed-server")

        assertFalse(Files.exists(removedLink, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(sentinel), "exact purge must not canonicalize into a sibling")
    }

    @Test
    fun `symlinked store root is rejected without touching its target`() {
        val base = Files.createTempDirectory("silo-json-root-purge").toFile()
        val external = Files.createTempDirectory("silo-json-root-external")
        val sentinel = external.resolve("keep.txt")
        Files.write(sentinel, "keep".toByteArray())
        Files.createSymbolicLink(base.toPath().resolve("ebook_state"), external)

        ServerScopedJsonStatePurger(base).deleteJsonOnlyOrphans(
            registeredServerIds = emptySet(),
            protectedServerIds = emptySet(),
        )

        assertTrue(Files.exists(sentinel), "purge must reject a symlinked store root")
    }

    @Test
    fun `store root swapped to a symlink after construction is rejected`() {
        val base = Files.createTempDirectory("silo-json-root-swap").toFile()
        val root = base.toPath().resolve("ebook_state")
        Files.createDirectories(root)
        val purger = ServerScopedJsonStatePurger(base)
        Files.delete(root)
        val external = Files.createTempDirectory("silo-json-root-swap-external")
        val sentinel = external.resolve("keep.txt")
        Files.write(sentinel, "keep".toByteArray())
        Files.createSymbolicLink(root, external)

        purger.deleteJsonOnlyOrphans(emptySet(), emptySet())

        assertTrue(Files.exists(sentinel), "purge must revalidate the root on every pass")
    }

    @Test
    fun `purge waits for an in-flight writer and rejects stale writes afterward`() {
        val base = Files.createTempDirectory("silo-json-writer-race").toFile()
        val store = ScopedJsonFileStore(base.resolve("ebook_state"), "test")
        val target = store.fileFor("removed-server", null, "p1", "book")
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                ServerScopedJsonAccess.withWriter(store.rootDirectory(), safePathSegment("removed-server")) {
                    writerEntered.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS))
                    store.writeAtomic(target, """{"progress":0.5}""")
                }
            }
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS))

            val purge = executor.submit {
                ServerScopedJsonStatePurger(base).deleteAllForServer("removed-server")
            }
            releaseWriter.countDown()
            writer.get(5, TimeUnit.SECONDS)
            purge.get(5, TimeUnit.SECONDS)
            assertFalse(target.exists())

            store.writeAtomic(target, """{"progress":0.75}""")
            assertFalse(target.exists(), "a stale writer must not recreate a removed identity")
        } finally {
            executor.shutdownNow()
        }
    }
}
