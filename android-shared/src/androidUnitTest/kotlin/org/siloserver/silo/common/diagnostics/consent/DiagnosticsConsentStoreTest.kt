package org.siloserver.silo.common.diagnostics.consent

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsConsentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var baseDir: File
    private var now: Long = 5_000L

    private val binding = DiagnosticsBinding("srv_home_01", "user_a")

    @Before
    fun setUp() {
        baseDir = tmp.newFolder("diagnostics")
        now = 5_000L
    }

    private fun newStore(): DiagnosticsConsentStore = DiagnosticsConsentStore(baseDir) { now }

    // ---- consent records -------------------------------------------------

    @Test
    fun `record for unknown binding is ASK at the current notice`() {
        val record = newStore().record(binding, currentNoticeVersion = 4)
        assertEquals(ConsentChoice.ASK, record.mode)
        assertEquals(4, record.noticeVersion)
        assertEquals(now, record.updatedAtEpochMs)
    }

    @Test
    fun `always is demoted to ASK when the notice version bumps`() {
        val store = newStore()
        store.setMode(binding, ConsentChoice.ALWAYS, noticeVersion = 1)
        assertEquals(ConsentChoice.ALWAYS, store.record(binding, currentNoticeVersion = 1).mode)

        val demoted = store.record(binding, currentNoticeVersion = 2)
        assertEquals(ConsentChoice.ASK, demoted.mode)
        assertEquals(2, demoted.noticeVersion)

        // The demotion is persisted, not just computed.
        val stored = assertNotNull(newStore().storedRecord(binding))
        assertEquals(ConsentChoice.ASK, stored.mode)
        assertEquals(2, stored.noticeVersion)
    }

    @Test
    fun `never survives a notice bump without demotion`() {
        val store = newStore()
        store.setMode(binding, ConsentChoice.NEVER, noticeVersion = 1)
        val reconciled = store.record(binding, currentNoticeVersion = 2)
        assertEquals(ConsentChoice.NEVER, reconciled.mode)
        assertEquals(2, reconciled.noticeVersion)
    }

    @Test
    fun `setMode NEVER invokes onNeverSelected exactly once with the binding`() {
        val store = newStore()
        val invocations = mutableListOf<DiagnosticsBinding>()
        store.onNeverSelected = { invocations.add(it) }

        store.setMode(binding, ConsentChoice.NEVER, noticeVersion = 1)
        assertEquals(listOf(binding), invocations)

        store.setMode(binding, ConsentChoice.ALWAYS, noticeVersion = 1)
        assertEquals(listOf(binding), invocations, "non-NEVER modes must not trigger the purge cascade")
    }

    // ---- sent history ----------------------------------------------------

    @Test
    fun `sent history caps at 10 newest first`() {
        val store = newStore()
        for (i in 1..12) {
            now += 10
            store.recordSent(binding, "id-$i")
        }
        val history = store.sentHistory(binding)
        assertEquals(10, history.size)
        assertEquals((12 downTo 3).map { "id-$it" }, history.map { it.shortId })
        assertTrue(history.zipWithNext().all { (a, b) -> a.sentAtEpochMs >= b.sentAtEpochMs })
    }

    @Test
    fun `sent history dedupes short ids case-insensitively`() {
        val store = newStore()
        now += 10
        store.recordSent(binding, "AbC7xQ")
        now += 10
        store.recordSent(binding, "abc7xq")

        val history = newStore().sentHistory(binding)
        assertEquals(1, history.size)
        assertEquals("abc7xq", history.single().shortId)
        assertEquals(now, history.single().sentAtEpochMs, "resend keeps the newest timestamp")
    }

    // ---- server index + status cache -------------------------------------

    @Test
    fun `server instance index remembers and forgets round-trip`() {
        val store = newStore()
        assertNull(store.serverInstanceForLocalId("local-1"))

        store.rememberServerInstance("local-1", "srv_home_01")
        assertEquals("srv_home_01", store.serverInstanceForLocalId("local-1"))
        assertEquals("srv_home_01", newStore().serverInstanceForLocalId("local-1"))

        assertEquals("srv_home_01", store.forgetLocalServer("local-1"))
        assertNull(store.serverInstanceForLocalId("local-1"))
        assertNull(store.forgetLocalServer("local-1"), "second forget has nothing to return")
    }

    @Test
    fun `cachedStatus round-trips and is dropped on forget`() {
        val store = newStore()
        val status = DiagnosticsStatusResponse(
            status = "available",
            serverInstanceId = "srv_home_01",
            acceptedSchemaVersions = listOf(1),
            maxBundleBytes = 10_000_000,
            maxManifestBytes = 65_536,
            retentionDays = 30,
            consentNoticeVersion = 2,
        )
        store.rememberServerInstance("local-1", "srv_home_01")
        store.cacheStatus("local-1", status, accountUserId = "user_a")

        val cached = assertNotNull(newStore().cachedStatus("local-1"))
        assertEquals(status, cached.status)
        assertEquals("user_a", cached.accountUserId)
        assertEquals(now, cached.cachedAtEpochMs)

        store.forgetLocalServer("local-1")
        assertNull(store.cachedStatus("local-1"))
    }

    // ---- purge -----------------------------------------------------------

    @Test
    fun `purgeServer removes only that instance's records`() {
        val store = newStore()
        val otherServer = DiagnosticsBinding("srv_other_02", "user_a")
        store.setMode(binding, ConsentChoice.ALWAYS, noticeVersion = 1)
        store.setMode(otherServer, ConsentChoice.ALWAYS, noticeVersion = 1)
        store.recordSent(binding, "id-a")
        store.recordSent(otherServer, "id-b")

        store.purgeServer("srv_home_01")

        assertNull(store.storedRecord(binding))
        assertTrue(store.sentHistory(binding).isEmpty())
        assertNotNull(store.storedRecord(otherServer))
        assertEquals(listOf("id-b"), store.sentHistory(otherServer).map { it.shortId })
    }
}
