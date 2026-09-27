package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.network.api.CatalogApi
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchPeopleTest {
    private fun repository(peopleMediaScope: Boolean?, scopes: MutableList<String?>): Pair<HttpClient, CatalogRepository> {
        val capability = peopleMediaScope?.let { ""","people_media_scope":$it""" }.orEmpty()
        val client = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v2/catalog/search/capabilities" ->
                    """{"revision":"r","state":"available","provider":"postgres"$capability}"""
                "/api/v2/catalog/people" -> {
                    val scope = request.url.parameters["media_scope"]
                    scopes += scope
                    when (scope) {
                        "ebook" -> """{"items":[{"id":"3","name":"Nolan Author"},{"id":"1","name":"Christopher Nolan"}]}"""
                        "manga" -> """{"items":[{"id":"2","name":"Alan Nolan"},{"id":"3","name":"Nolan Author"}]}"""
                        else -> """{"items":[{"id":"1","name":"Christopher Nolan"},{"id":"2","name":"Alan Nolan"}]}"""
                    }
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return client to CatalogRepository(CatalogApi(client))
    }

    @Test
    fun serverWithoutScopedPeopleSearchOffersNoPeople() = runTest {
        for (advertised in listOf(null, false)) {
            val (client, repository) = repository(advertised, mutableListOf())
            try {
                assertEquals(emptyList(), repository.searchPeopleForQuery("Nolan", listOf("video")))
            } finally { client.close() }
        }
    }

    @Test
    fun scopedSearchSendsTheScopeAndKeepsServerOrder() = runTest {
        val scopes = mutableListOf<String?>()
        val (client, repository) = repository(true, scopes)
        try {
            val people = repository.searchPeopleForQuery(" Nolan ", listOf("video"))
            assertEquals(listOf("Christopher Nolan", "Alan Nolan"), people.map { it.name })
            assertEquals(listOf<String?>("video"), scopes)
        } finally { client.close() }
    }

    @Test
    fun severalScopesMergeWithoutDuplicatesExactNameFirst() = runTest {
        val scopes = mutableListOf<String?>()
        val (client, repository) = repository(true, scopes)
        try {
            val people = repository.searchPeopleForQuery("christopher nolan", listOf("ebook", "manga"))
            assertEquals(listOf(1L, 2L, 3L), people.map { it.id })
            assertEquals(setOf<String?>("ebook", "manga"), scopes.toSet())
        } finally { client.close() }
    }

    @Test
    fun mergeHonoursTheLimit() {
        val lists = listOf(
            listOf(Person(id = 5, name = "B")),
            listOf(Person(id = 4, name = "A"), Person(id = 6, name = "C")),
        )
        assertEquals(listOf(4L, 5L), mergePeopleSearchResults("x", lists, limit = 2).map { it.id })
    }
}
