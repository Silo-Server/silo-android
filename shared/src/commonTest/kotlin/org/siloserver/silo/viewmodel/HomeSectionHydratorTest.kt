package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSectionHydratorTest {

    @Test
    fun inlineSectionsRequireNoFallbackRequests() = runTest {
        var calls = 0

        val result = hydrateHomeSections(
            sections = listOf(section("inline", total = 1, items = listOf(item("a")))),
        ) {
            calls += 1
            error("fallback must not run")
        }

        assertEquals(0, calls)
        assertEquals(listOf("a"), result.sections.single().items.map { it.contentId })
        assertTrue(result.fullyResolved)
    }

    @Test
    fun topLevelFallbackItemsHydrateTheOriginalSection() = runTest {
        val result = hydrateHomeSections(listOf(section("missing", total = 1))) {
            ApiResult.Success(HomeSectionItemsResponse(items = listOf(item("b"))))
        }

        assertEquals("missing", result.sections.single().id)
        assertEquals(listOf("b"), result.sections.single().items.map { it.contentId })
        assertTrue(result.fullyResolved)
    }

    @Test
    fun nestedFallbackSectionWinsWhenItContainsItems() = runTest {
        val result = hydrateHomeSections(listOf(section("missing", total = 1))) {
            ApiResult.Success(
                HomeSectionItemsResponse(
                    section = section("server", total = 1, items = listOf(item("nested"))),
                    items = listOf(item("top-level")),
                ),
            )
        }

        assertEquals("server", result.sections.single().id)
        assertEquals(listOf("nested"), result.sections.single().items.map { it.contentId })
        assertTrue(result.fullyResolved)
    }

    @Test
    fun failedFallbackMarksSnapshotPartialAndOmitsEmptySection() = runTest {
        val result = hydrateHomeSections(listOf(section("missing", total = 1))) {
            ApiResult.NetworkError(IllegalStateException("offline"))
        }

        assertTrue(result.sections.isEmpty())
        assertFalse(result.fullyResolved)
    }

    @Test
    fun fallbackHydrationNeverExceedsFourConcurrentRequests() = runTest {
        val started = Channel<String>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        var active = 0
        var maximum = 0
        val ids = (1..12).map { "section-$it" }

        val hydration = async {
            hydrateHomeSections(ids.map { section(it, total = 1) }) { id ->
                active += 1
                maximum = maxOf(maximum, active)
                started.send(id)
                release.receive()
                active -= 1
                ApiResult.Success(HomeSectionItemsResponse(items = listOf(item("item-$id"))))
            }
        }

        val observedStarts = mutableListOf<String>()
        repeat(3) {
            repeat(4) { observedStarts += started.receive() }
            assertNull(withTimeoutOrNull(1) { started.receive() })
            repeat(4) { release.send(Unit) }
        }

        val result = hydration.await()
        assertEquals(4, maximum)
        assertEquals(ids.toSet(), observedStarts.toSet())
        assertEquals(ids, result.sections.map { it.id })
        assertTrue(result.fullyResolved)
    }

    private fun section(
        id: String,
        total: Int,
        items: List<SectionItem> = emptyList(),
    ) = ResolvedSection(
        id = id,
        sectionType = "test",
        title = id,
        totalCount = total,
        items = items,
    )

    private fun item(id: String) = SectionItem(
        contentId = id,
        type = "movie",
        title = id,
    )
}
