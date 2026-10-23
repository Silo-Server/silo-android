package org.siloserver.silo.common.downloads

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStorePublicDownloadStoreCollisionTest {

    private lateinit var provider: RelativePathProvider
    private lateinit var store: MediaStorePublicDownloadStore

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(RelativePathProvider::class.java, "media")
        provider.put(ENCODED_QUESTION_PATH, displayName = "Question.epub", size = 13L)
        store = MediaStorePublicDownloadStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `raw reserved namespace cannot read encoded sibling`() {
        assertNull(store.locateByFileId("~Pw", "prof", 42))
        assertNotNull(store.locateByFileId("?", "prof", 42))
    }

    @Test
    fun `raw reserved namespace cannot count encoded sibling`() {
        assertEquals(0L, store.totalBytesUsed("~Pw", "prof"))
        assertEquals(13L, store.totalBytesUsed("?", "prof"))
    }

    @Test
    fun `raw reserved namespace cannot delete encoded sibling`() {
        assertFalse(store.delete("~Pw", "prof", 42, uriString = null))
        assertNotNull(store.locateByFileId("?", "prof", 42))
    }

    @Test
    fun `slot delete removes the slot's pending partials and leaves other slots alone`() {
        // Interrupted transfers leave IS_PENDING=1 items that a plain
        // collection query or delete does not see.
        provider.put(VIDEO_SLOT_PATH, displayName = ".pending-1-Movie.mp4", size = 72L, pending = true)
        provider.put(VIDEO_SLOT_PATH, displayName = ".pending-2-Movie.mp4", size = 39L, pending = true)
        provider.put(VIDEO_SIBLING_PATH, displayName = "Other.mp4", size = 10L)
        provider.put(VIDEO_SIBLING_PATH, displayName = ".pending-3-Other.mp4", size = 5L, pending = true)

        assertTrue(store.delete("srv", "prof", 4, uriString = null))

        assertEquals(emptyList(), provider.namesAt(VIDEO_SLOT_PATH))
        assertEquals(listOf("Other.mp4", ".pending-3-Other.mp4"), provider.namesAt(VIDEO_SIBLING_PATH))
    }

    class RelativePathProvider : ContentProvider() {
        private val rows = mutableListOf<Row>()

        fun put(path: String, displayName: String, size: Long, pending: Boolean = false) {
            rows += Row(path, displayName, size, pending)
        }

        fun namesAt(path: String): List<String> = rows.filter { it.path == path }.map { it.displayName }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns: Array<String> = projection?.map { it }?.toTypedArray() ?: emptyArray()
            val cursor = MatrixCursor(columns)
            matchingRows(selection, selectionArgs, includePending = false).forEachIndexed { index, row ->
                cursor.addRow(columns.map { column ->
                    when (column) {
                        MediaStore.MediaColumns._ID -> (index + 1).toLong()
                        MediaStore.MediaColumns.DISPLAY_NAME -> row.displayName
                        MediaStore.MediaColumns.SIZE -> row.size
                        else -> null
                    }
                })
            }
            return cursor
        }

        // MediaProvider leaves pending items out of a collection-wide match
        // unless the caller asks for them.
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            deleteMatching(selection, selectionArgs, includePending = false)

        override fun delete(uri: Uri, extras: Bundle?): Int = deleteMatching(
            extras?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            extras?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
            includePending = extras?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE,
        )

        private fun deleteMatching(selection: String?, selectionArgs: Array<out String>?, includePending: Boolean): Int {
            val matches = matchingRows(selection, selectionArgs, includePending)
            rows.removeAll(matches)
            return matches.size
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun getType(uri: Uri): String? = null

        private fun matchingRows(selection: String?, selectionArgs: Array<out String>?, includePending: Boolean): List<Row> {
            val argument = selectionArgs?.singleOrNull() ?: return emptyList()
            val visible = rows.filter { includePending || !it.pending }
            return if (selection?.contains(" LIKE ") == true) {
                val prefix = argument.removeSuffix("%")
                    .replace("\\_", "_")
                    .replace("\\%", "%")
                    .replace("\\\\", "\\")
                visible.filter { row -> row.path.startsWith(prefix) }
            } else {
                visible.filter { row -> row.path == argument }
            }
        }

        private data class Row(val path: String, val displayName: String, val size: Long, val pending: Boolean)
    }

    companion object {
        private const val ENCODED_QUESTION_PATH = "Download/Silo/~Pw/prof/42/"
        private const val VIDEO_SLOT_PATH = "Movies/Silo/srv/prof/4/"
        private const val VIDEO_SIBLING_PATH = "Movies/Silo/srv/prof/5/"
    }
}
