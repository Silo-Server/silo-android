package org.siloserver.silo.common.audiobook

import org.siloserver.silo.model.audiobook.AudiobookBookmark
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiobookBookmarksStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `add dedupes by id keeping the existing bookmark and returns position order`() {
        val store = AudiobookBookmarksStore(tmp.newFolder("filesDir"))
        val late = bookmark(id = "b1", positionSeconds = 120.0)
        val early = bookmark(id = "b2", positionSeconds = 30.0)

        store.add("srv", "acct", "prof", "book", late)
        store.add("srv", "acct", "prof", "book", early)
        val result = store.add("srv", "acct", "prof", "book", late.copy(note = "duplicate"))

        assertEquals(listOf("b2", "b1"), result.map { it.id })
        assertNull(result.first { it.id == "b1" }.note)
        assertEquals(listOf("b2", "b1"), store.list("srv", "acct", "prof", "book").map { it.id })
    }

    @Test
    fun `remove deletes only the matching id`() {
        val store = AudiobookBookmarksStore(tmp.newFolder("filesDir"))
        store.add("srv", "acct", "prof", "book", bookmark(id = "b1", positionSeconds = 10.0))
        store.add("srv", "acct", "prof", "book", bookmark(id = "b2", positionSeconds = 20.0))

        val result = store.remove("srv", "acct", "prof", "book", "b1")

        assertEquals(listOf("b2"), result.map { it.id })
    }

    private fun bookmark(id: String, positionSeconds: Double): AudiobookBookmark =
        AudiobookBookmark(id = id, positionSeconds = positionSeconds, createdAtMs = 1L)
}
