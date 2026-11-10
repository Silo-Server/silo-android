package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.PASSWORD_CHANGE_REQUIRED
import org.siloserver.silo.model.auth.PASSWORD_CHANGE_REQUIRED_MESSAGE
import org.siloserver.silo.network.*
import kotlin.test.*

/** Server text the catalog can translate: section title keys and problem details. */
class ServerTextV2Test {
    private val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }

    private suspend fun <T> withServer(body: String, status: HttpStatusCode, call: suspend (HomeSectionsV2Api) -> T): T {
        val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })
        try { return call(HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)) } finally { client.close() }
    }

    @AfterTest fun reset() { ProblemText.localize = null }

    @Test fun sectionCarriesItsTitleKeyAndArguments() = runTest {
        val keyed = """{"id":"a","section_type":"recently_added","title":"Recently Added in Films",""" +
            """"title_key":"home.section.recentlyAddedIn","title_args":{"library":"Films"},"items":[]}"""
        val section = withServer(keyed, HttpStatusCode.OK) { it.section("a", owner) }.getOrThrow().section!!
        assertEquals("home.section.recentlyAddedIn", section.titleKey)
        assertEquals(mapOf("library" to "Films"), section.titleArgs)

        // A title someone typed, or an older server: no key, the title stands alone.
        val typed = """{"id":"a","section_type":"recently_added","title":"Keep Watching","items":[]}"""
        val plain = withServer(typed, HttpStatusCode.OK) { it.section("a", owner) }.getOrThrow().section!!
        assertNull(plain.titleKey)
        assertNull(plain.titleArgs)
    }

    private val problem = """{"type":"https://siloserver.org/docs/api/v2/problems/validation_failed","title":"t","status":422,""" +
        """"detail":"Playback session not found","errors":[{"location":"query.x","code":"invalid_enum","detail":"d"}]}"""

    @Test fun problemDetailIsShownWhenNothingTranslatesIt() = runTest {
        val error = withServer(problem, HttpStatusCode.UnprocessableEntity) { it.section("a", owner) } as ApiResult.Error
        assertEquals("validation_failed", error.error)
        assertEquals("Playback session not found", error.message)
        assertEquals("Playback session not found", error.detail)

        ProblemText.localize = { null }
        val untranslated = withServer(problem, HttpStatusCode.UnprocessableEntity) { it.section("a", owner) } as ApiResult.Error
        assertEquals("Playback session not found", untranslated.message)
    }

    @Test fun translatedProblemKeepsTheServerDetailForCode() = runTest {
        var seen: Problem? = null
        ProblemText.localize = { seen = it; "Elige uno de los valores permitidos." }
        val error = withServer(problem, HttpStatusCode.UnprocessableEntity) { it.section("a", owner) } as ApiResult.Error
        assertEquals("Elige uno de los valores permitidos.", error.message)
        assertEquals("Playback session not found", error.detail)
        assertEquals(listOf("invalid_enum"), seen?.errors?.map { it.code })
    }

    @Test fun aFailingTranslatorFallsBackToDetail() = runTest {
        ProblemText.localize = { error("boom") }
        val error = withServer(problem, HttpStatusCode.UnprocessableEntity) { it.section("a", owner) } as ApiResult.Error
        assertEquals("Playback session not found", error.message)
    }

    @Test fun temporaryPasswordKeepsItsInstructions() = runTest {
        ProblemText.localize = { "translated" }
        val body = """{"type":"https://siloserver.org/docs/api/v2/problems/$PASSWORD_CHANGE_REQUIRED","title":"t","status":403,"detail":"x"}"""
        val error = withServer(body, HttpStatusCode.Forbidden) { it.section("a", owner) } as ApiResult.Error
        assertEquals(PASSWORD_CHANGE_REQUIRED_MESSAGE, error.message)
    }
}
