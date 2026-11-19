package org.siloserver.silo.common.store

import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Payload(val name: String, val count: Int)

class ScopedJsonFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `write then read round trips and creates scoped directories`() {
        val store = ScopedJsonFileStore(tmp.newFolder("root"), tag = "Test")
        val file = store.fileFor("srv", null, "prof", "content")

        store.write(file, Payload(name = "a", count = 1))

        assertTrue(file.path.endsWith("srv/prof/content.json"))
        assertEquals(Payload(name = "a", count = 1), store.read<Payload>(file))
    }

    @Test
    fun `read returns null for missing or corrupt files`() {
        val store = ScopedJsonFileStore(tmp.newFolder("root"), tag = "Test")
        val file = store.fileFor("srv", null, "prof", "content")

        assertNull(store.read<Payload>(file))

        file.parentFile?.mkdirs()
        file.writeText("{ not json")
        assertNull(store.read<Payload>(file))
    }

    @Test
    fun `atomic write replaces existing content and leaves no tmp file`() {
        val store = ScopedJsonFileStore(tmp.newFolder("root"), tag = "Test")
        val file = store.fileFor("srv", null, "prof", "content")

        store.write(file, Payload(name = "a", count = 1))
        store.write(file, Payload(name = "b", count = 2))

        assertEquals(Payload(name = "b", count = 2), store.read<Payload>(file))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `unsafe scope values resolve to an encoded contained target`() {
        val root = tmp.newFolder("root").canonicalFile
        val store = ScopedJsonFileStore(root, tag = "Test")

        val file = store.fileFor("../server", null, "profile/name", "content id")

        assertTrue(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertFalse(file.path.contains("../server"))
        assertFalse(file.path.contains("profile/name"))
        assertFalse(file.path.contains("content id"))
    }

    @Test
    fun `contained legacy value is read then migrated on the next successful write`() {
        val root = tmp.newFolder("root")
        val legacy = File(root, "server id/profile%id/content id.json").apply {
            parentFile?.mkdirs()
            writeText("""{"name":"legacy","count":1}""")
        }
        val store = ScopedJsonFileStore(root, tag = "Test")
        val target = store.fileFor("server id", null, "profile%id", "content id")

        assertFalse(target.exists())
        assertEquals(Payload(name = "legacy", count = 1), store.read<Payload>(target))
        assertTrue(legacy.isFile)

        store.write(target, Payload(name = "updated", count = 2))

        assertFalse(legacy.exists())
        assertEquals(Payload(name = "updated", count = 2), store.read<Payload>(target))
    }

    @Test
    fun `read and write reject a target outside the store root`() {
        val root = tmp.newFolder("root")
        val sentinel = File(root.parentFile, "sentinel.json").apply {
            writeText("""{"name":"keep","count":7}""")
        }
        val store = ScopedJsonFileStore(root, tag = "Test")

        assertNull(store.read<Payload>(sentinel))
        store.writeAtomic(sentinel, """{"name":"changed","count":8}""")

        assertEquals("""{"name":"keep","count":7}""", sentinel.readText())
    }

    @Test
    fun `raw reserved namespace identity cannot read another identity encoded target`() {
        val store = ScopedJsonFileStore(tmp.newFolder("root"), tag = "Test")
        val encodedQuestion = store.fileFor("?", null, "prof", "content")
        store.write(encodedQuestion, Payload(name = "question", count = 1))

        val rawReserved = store.fileFor("~Pw", null, "prof", "content")

        assertNull(store.read<Payload>(rawReserved))
        assertEquals(Payload(name = "question", count = 1), store.read<Payload>(encodedQuestion))
    }

    @Test
    fun `atomic write does not follow predictable temp symlink outside root`() {
        val root = tmp.newFolder("root")
        val store = ScopedJsonFileStore(root, tag = "Test")
        val target = store.fileFor("server", null, "profile", "content")
        target.parentFile?.mkdirs()
        val sentinel = File(root.parentFile, "sentinel.json").apply { writeText("keep") }
        val predictableTmp = File(target.parentFile, "${target.name}.tmp")
        Files.createSymbolicLink(predictableTmp.toPath(), sentinel.toPath())

        store.write(target, Payload(name = "safe", count = 2))

        assertEquals("keep", sentinel.readText())
        assertEquals(Payload(name = "safe", count = 2), store.read<Payload>(target))
    }
}
