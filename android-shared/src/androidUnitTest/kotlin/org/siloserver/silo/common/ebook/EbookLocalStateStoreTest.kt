package org.siloserver.silo.common.ebook

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EbookLocalStateStoreTest {

    @Test
    fun `create identities and delete intent survive reopen`() {
        val root = tmp.newFolder("journal")
        val store = EbookLocalStateStore(root)
        val first = store.addBookmark("srv", "prof", "book", "page:3", 10, "login", "https://reader.example")
        val second = store.addBookmark("srv", "prof", "book", "page:4", 10, "login", "https://reader.example")
        kotlin.test.assertNotEquals(first.id, second.id)
        assertEquals(first, EbookLocalStateStore(root).listBookmarks("srv", "prof", "book").first())
        val tombstone = store.markBookmarkDelete("srv", "prof", "book", first.id, first.location,
            "login", "https://reader.example", "\"original-validator\"")
        val reopened = EbookLocalStateStore(root).listBookmarks("srv", "prof", "book")
        assertEquals(tombstone, reopened.first { it.id == first.id })
        assertEquals("\"original-validator\"", tombstone.deleteETag)
        assertEquals("login", tombstone.loginId)
        assertEquals(second, reopened.first { it.id == second.id })
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `progress round trips by server profile and content`() {
        val store = EbookLocalStateStore(tmp.newFolder("filesDir"))
        val snapshot = EbookLocalStateStore.ProgressSnapshot(
            fileId = 7,
            location = "page:12",
            progress = 0.25,
            updatedAtMs = 42L,
        )

        store.writeProgress("srv", "prof", "book", snapshot)

        assertEquals(snapshot, store.readProgress("srv", "prof", "book"))
        assertNull(store.readProgress("srv", "other", "book"))
    }

    @Test
    fun `bookmark appends and lists in created order`() {
        val store = EbookLocalStateStore(tmp.newFolder("filesDir"))

        store.addBookmark("srv", "prof", "book", "page:3", createdAtMs = 30L)
        store.addBookmark("srv", "prof", "book", "page:1", createdAtMs = 10L)

        assertEquals(
            listOf("page:1", "page:3"),
            store.listBookmarks("srv", "prof", "book").map { it.location },
        )
    }

    @Test
    fun `bookmark can be removed without touching others`() {
        val store = EbookLocalStateStore(tmp.newFolder("filesDir"))

        val keep = store.addBookmark("srv", "prof", "book", "page:1", createdAtMs = 10L)
        val remove = store.addBookmark("srv", "prof", "book", "page:2", createdAtMs = 20L)

        store.removeBookmark("srv", "prof", "book", remove.id)

        assertEquals(listOf(keep.id), store.listBookmarks("srv", "prof", "book").map { it.id })
    }

    @Test
    fun `bookmark with duplicate id is not added twice`() {
        val store = EbookLocalStateStore(tmp.newFolder("filesDir"))

        store.addBookmark("srv", "prof", "book", "page:3", createdAtMs = 30L)
        store.addBookmark("srv", "prof", "book", "page:9", createdAtMs = 30L) // same id: "local-30"

        assertEquals(listOf("page:3"), store.listBookmarks("srv", "prof", "book").map { it.location })
    }

    @Test
    fun `display settings round trip by server and profile`() {
        val store = EbookLocalStateStore(tmp.newFolder("filesDir"))
        val settings = ReaderDisplaySettings(
            theme = ReaderTheme.Sepia,
            textScale = 1.35f,
            marginScale = 1.2f,
        )

        store.writeDisplaySettings("srv", "prof", settings)

        assertEquals(settings, store.readDisplaySettings("srv", "prof"))
        assertNull(store.readDisplaySettings("srv", "other"))
    }
}
