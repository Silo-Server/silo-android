package org.siloserver.silo.common.diagnostics.logging

import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogRingTest {

    private fun line(text: String): ByteArray = text.encodeToByteArray()

    @Test
    fun `empty ring snapshots empty with zero drops`() {
        val snapshot = LogRing(8).snapshot()
        assertTrue(snapshot.lines.isEmpty())
        assertEquals(0L, snapshot.droppedCount)
    }

    @Test
    fun `filling beyond capacity retains newest entries in order`() {
        val ring = LogRing(8)
        repeat(20) { ring.append(line("line-$it")) }

        val snapshot = ring.snapshot()
        assertEquals((12..19).map { "line-$it" }, snapshot.lines.map { it.decodeToString() })
        assertEquals(12L, snapshot.droppedCount)
    }

    @Test
    fun `snapshotNewest returns newest N oldest-first`() {
        val ring = LogRing(8)
        repeat(20) { ring.append(line("line-$it")) }

        val snapshot = ring.snapshotNewest(3)
        assertEquals(listOf("line-17", "line-18", "line-19"), snapshot.lines.map { it.decodeToString() })
    }

    @Test
    fun `snapshotNewest of underfilled ring returns everything`() {
        val ring = LogRing(8)
        repeat(2) { ring.append(line("line-$it")) }
        assertEquals(listOf("line-0", "line-1"), ring.snapshotNewest(5).lines.map { it.decodeToString() })
    }

    @Test
    fun `concurrent appends and snapshots never tear an entry`() {
        val threads = 8
        val perThread = 5000
        val ring = LogRing(64)

        val valid = HashSet<String>(threads * perThread)
        for (t in 0 until threads) {
            for (i in 0 until perThread) valid.add("t$t-$i")
        }

        val start = CountDownLatch(1)
        val writers = (0 until threads).map { t ->
            Thread {
                start.await()
                for (i in 0 until perThread) ring.append(line("t$t-$i"))
            }.apply { start() }
        }

        start.countDown()
        // Snapshot repeatedly while writers run; every returned entry must be
        // one of the appended arrays — a torn slot would decode to garbage.
        while (writers.any { it.isAlive }) {
            for (entry in ring.snapshot().lines) {
                val text = entry.decodeToString()
                assertTrue(text in valid, "torn or foreign ring entry: '$text'")
            }
        }
        writers.forEach { it.join() }

        val final = ring.snapshot()
        assertEquals(64, final.lines.size)
        for (entry in final.lines) {
            assertTrue(entry.decodeToString() in valid)
        }
        assertEquals((threads * perThread - 64).toLong(), final.droppedCount)
    }
}
