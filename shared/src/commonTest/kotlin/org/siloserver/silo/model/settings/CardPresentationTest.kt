package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardPresentationTest {

    @Test
    fun wireRoundTrip() {
        val presentation = CardPresentation(CardPosterSize.Large, CardCaption.Artwork)
        val decoded = CardPresentation.decodeOrNull(
            """{"caption":"artwork","poster_size":"large"}""",
        )
        assertEquals(presentation, decoded)
        assertEquals(presentation, CardPresentation.decodeOrNull(presentation.serialize()))

        val element = Json.parseToJsonElement(presentation.serialize())
        assertEquals(presentation, CardPresentation.decodeOrNull(element))
        assertEquals(element, presentation.toJsonElement())
    }

    @Test
    fun encodeAlwaysWritesBothFields_evenAtDefaults() {
        // Contract shape is required:[poster_size,caption]; omitting a
        // default-valued axis would make the server reject the PUT.
        assertEquals(
            Json.parseToJsonElement("""{"poster_size":"standard","caption":"title_metadata"}"""),
            CardPresentation.DEFAULT.toJsonElement(),
        )
        assertEquals(
            Json.parseToJsonElement("""{"poster_size":"standard","caption":"artwork"}"""),
            CardPresentation(CardPosterSize.Standard, CardCaption.Artwork).toJsonElement(),
        )
    }

    @Test
    fun decodeUnknownEnumValue_fallsBackToDefault() {
        assertEquals(
            CardPresentation.DEFAULT,
            CardPresentation.decodeOrDefault("""{"poster_size":"gigantic","caption":"title"}"""),
        )
        assertEquals(
            CardPresentation.DEFAULT,
            CardPresentation.decodeOrDefault("""{"poster_size":"large","caption":"emoji"}"""),
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
            CardPresentation(CardPosterSize.Compact, CardCaption.TitleMetadata),
            CardPresentation.decodeOrNull("""{"poster_size":"compact","future_axis":true}"""),
        )
        assertEquals(CardPresentation.DEFAULT, CardPresentation.decodeOrNull("{}"))
    }

    @Test
    fun presetForwardMapping() {
        assertEquals(
            CardPresentation(CardPosterSize.Standard, CardCaption.TitleMetadata),
            CardPresentationPreset.Balanced.presentation,
        )
        assertEquals(
            CardPresentation(CardPosterSize.Compact, CardCaption.Title),
            CardPresentationPreset.Compact.presentation,
        )
        assertEquals(
            CardPresentation(CardPosterSize.Large, CardCaption.Title),
            CardPresentationPreset.Cinema.presentation,
        )
        assertEquals(
            CardPresentation(CardPosterSize.Large, CardCaption.Artwork),
            CardPresentationPreset.ArtworkOnly.presentation,
        )
    }

    @Test
    fun presetReverseMapping_includingCustom() {
        for (preset in CardPresentationPreset.entries) {
            assertEquals(preset, preset.presentation.preset)
        }
        assertEquals(CardPresentationPreset.Balanced, CardPresentation.DEFAULT.preset)
        // A pair matching no preset is the synthetic "Custom" state.
        assertNull(CardPresentation(CardPosterSize.Compact, CardCaption.Artwork).preset)
        assertNull(CardPresentation(CardPosterSize.Standard, CardCaption.Title).preset)
    }

    @Test
    fun posterScaleMatchesAppleClients() {
        assertEquals(0.86f, CardPosterSize.Compact.posterScale)
        assertEquals(1f, CardPosterSize.Standard.posterScale)
        assertEquals(1.2f, CardPosterSize.Large.posterScale)
    }

    @Test
    fun captionFlags() {
        assertTrue(CardCaption.TitleMetadata.showsTitle)
        assertTrue(CardCaption.TitleMetadata.showsMetadata)
        assertTrue(CardCaption.Title.showsTitle)
        assertFalse(CardCaption.Title.showsMetadata)
        assertFalse(CardCaption.Artwork.showsTitle)
        assertFalse(CardCaption.Artwork.showsMetadata)
    }
}
