package org.siloserver.silo.model.section

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.catalog.scopeLibrarySection
import org.siloserver.silo.network.apiv2.decodeSectionsV2
import org.siloserver.silo.viewmodel.hydrateHomeSections
import org.siloserver.silo.viewmodel.removingFromProgressRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rows of collections (`collection_posters`): Silo-Server/silo-server#4410. */
class CollectionRowTest {

    @Test
    fun decodesCardsInOrderAndDropsOnesThisClientCannotOpen() {
        val sections = decodeSectionsV2(
            Json.parseToJsonElement(
                """
                {"sections":[{"id":"franchises","section_type":"collection_posters","title":"Franchises",
                  "featured":false,"item_limit":20,"total_count":5,"items":[],"collections":[
                    {"id":"01J9","source":"library","title":"Marvel","poster_url":"https://s3.example.test/marvel.jpg",
                     "poster_is_collage":false,"item_count":12,"library_id":"1",
                     "backdrop_url":"https://s3.example.test/marvel-1280.jpg","backdrop_thumbhash":"bd-hash"},
                    {"id":"c-ghibli","source":"user","title":"Ghibli nights","poster_url":"https://s3.example.test/collage.jpg",
                     "poster_is_collage":true,"item_count":5,"creator_profile_id":"p-owner"},
                    {"id":"no-library","source":"library","title":"Orphan","poster_url":"","poster_is_collage":false,"item_count":1},
                    {"id":"future","source":"smart","title":"Unknown","poster_url":"","poster_is_collage":false,"item_count":1},
                    {"source":"user","title":null,"poster_url":"","poster_is_collage":false,"item_count":1},
                    {"id":"bad-count","source":"user","title":"Bad count","poster_url":"","poster_is_collage":false,"item_count":"many"},
                    "not a card",
                    {"id":"01J9","source":"library","title":"Marvel again","poster_url":"","poster_is_collage":false,"item_count":1,"library_id":"2"}
                  ]}]}
                """.trimIndent(),
            ).jsonObject,
        )

        val row = sections.sections.single()
        assertTrue(row.isCollectionRow)
        assertTrue(row.hasCards)
        assertEquals(listOf("library:01J9", "user:c-ghibli"), row.collections.map { it.rowKey })
        val (library, personal) = row.collections
        assertEquals(1, library.libraryIdInt)
        assertEquals("https://s3.example.test/marvel-1280.jpg", library.backdropUrl)
        assertEquals("bd-hash", library.backdropThumbhash)
        assertEquals(null, personal.backdropUrl)
        assertFalse(library.posterIsCollage)
        assertTrue(personal.isUserCollection)
        assertTrue(personal.posterIsCollage)
        assertEquals("p-owner", personal.creatorProfileId)
        assertEquals("user_collections", personal.toLibraryCollection().kind)
    }

    @Test
    fun hydrationKeepsARowOfCollectionsWithoutRefetchingIt() = runTest {
        var fetches = 0
        val hydration = hydrateHomeSections(listOf(collectionRow(), collectionRow(id = "emptied", cards = emptyList()))) {
            fetches++
            error("a row of collections arrives whole")
        }

        assertEquals(0, fetches)
        assertTrue(hydration.fullyResolved)
        assertEquals(listOf("row"), hydration.sections.map { it.id })
    }

    @Test
    fun dismissingFromProgressRowsKeepsRowsOfCollections() {
        val progress = ResolvedSection(
            id = "cw", sectionType = "continue_watching", title = "Continue", totalCount = 1,
            items = listOf(SectionItem(contentId = "m1", type = "movie", title = "M1")),
        )

        val kept = listOf(progress, collectionRow()).removingFromProgressRows { it.contentId == "m1" }

        assertEquals(listOf("row"), kept.map { it.id })
    }

    @Test
    fun libraryMediaScopeLeavesARowOfCollectionsAlone() = runTest {
        val row = collectionRow()

        val scoped = scopeLibrarySection(row, mediaScope = "movie") { error("no titles to page") }

        assertEquals(row, scoped.section)
        assertFalse(scoped.incomplete)
    }

    @Test
    fun aRowOfCollectionsIsNeverTheHeroAndOffersNoExploreAll() {
        val row = collectionRow().copy(featured = true)

        assertEquals(null, listOf(row).splitFeatured().featured)
        assertFalse(collectionRow().offersExploreAll)
    }

    private fun collectionRow(
        id: String = "row",
        cards: List<SectionCollectionCard> = listOf(
            SectionCollectionCard(id = "c1", source = "library", title = "Marvel", itemCount = 3, libraryId = "1"),
        ),
    ) = ResolvedSection(
        id = id,
        sectionType = COLLECTION_POSTERS_SECTION_TYPE,
        title = id,
        totalCount = cards.size,
        collections = cards,
    )
}
