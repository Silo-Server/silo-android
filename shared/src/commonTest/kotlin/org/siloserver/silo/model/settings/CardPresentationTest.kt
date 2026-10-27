package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CardPresentationTest {

    @Test
    fun storedCaptionIsIgnored_andWrittenAsTitleMetadata() {
        // The caption choice is retired: a stored value from an older client
        // must not hide titles, and the contract still requires the field.
        val decoded = CardPresentation.decodeOrNull(
            """{"caption":"artwork","poster_size":"large"}""",
        )
        assertEquals(CardPresentation(CardPosterSize.Large), decoded)
        assertEquals(
            Json.parseToJsonElement("""{"poster_size":"large","caption":"title_metadata"}"""),
            decoded?.toJsonElement(),
        )
        assertEquals(decoded, CardPresentation.decodeOrNull(decoded?.serialize()))
    }

    @Test
    fun decodeUnknownEnumValue_fallsBackToDefault() {
        assertEquals(
            CardPresentation.DEFAULT,
            CardPresentation.decodeOrDefault("""{"poster_size":"gigantic","caption":"title"}"""),
        )
    }

    @Test
    fun decodeMalformed_fallsBackToDefault() {
        assertEquals(CardPresentation.DEFAULT, CardPresentation.decodeOrDefault(null as String?))
        assertEquals(CardPresentation.DEFAULT, CardPresentation.decodeOrDefault(""))
        assertEquals(CardPresentation.DEFAULT, CardPresentation.decodeOrDefault("not json"))
        assertEquals(CardPresentation.DEFAULT, CardPresentation.decodeOrDefault("[1,2]"))
    }

    @Test
    fun decodeIgnoresUnknownKeys_andMissingFieldsUseDefaults() {
        assertEquals(
            CardPresentation(CardPosterSize.Compact),
            CardPresentation.decodeOrNull("""{"poster_size":"compact","future_axis":true}"""),
        )
        assertEquals(CardPresentation.DEFAULT, CardPresentation.decodeOrNull("{}"))
    }

    @Test
    fun posterScaleMatchesAppleClients() {
        assertEquals(0.86f, CardPosterSize.Compact.posterScale)
        assertEquals(1f, CardPosterSize.Standard.posterScale)
        assertEquals(1.2f, CardPosterSize.Large.posterScale)
    }
}
