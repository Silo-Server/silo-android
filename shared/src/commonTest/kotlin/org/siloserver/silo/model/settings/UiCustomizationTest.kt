package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UiCustomizationTest {
    @Test
    fun cardPresentationRoundTripsExactWireShape() {
        val value = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)

        assertEquals(
            value,
            UiCustomizationCodec.parseCardPresentation(
                UiCustomizationCodec.encodeCardPresentation(value),
            ),
        )
        assertNull(
            UiCustomizationCodec.parseCardPresentation(
                json("""{"poster_size":"large","caption":"artwork","future":true}"""),
            ),
        )
    }

    @Test
    fun cardPresentationPresetsMapToCanonicalTwoAxisValues() {
        assertEquals(
            CardPresentation(PosterSizePreset.STANDARD, CardCaptionPreset.TITLE_METADATA),
            CardPresentationPreset.BALANCED.presentation,
        )
        assertEquals(
            CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.TITLE),
            CardPresentationPreset.COMPACT.presentation,
        )
        assertEquals(
            CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.TITLE),
            CardPresentationPreset.CINEMA.presentation,
        )
        assertEquals(
            CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            CardPresentationPreset.ARTWORK_ONLY.presentation,
        )
        assertEquals(
            CardPresentationPreset.CINEMA,
            CardPresentationPreset.matching(CardPresentationPreset.CINEMA.presentation),
        )
        assertNull(
            CardPresentationPreset.matching(
                CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.ARTWORK),
            ),
        )
    }

    @Test
    fun confirmedUnsupportedServerSuppressesCachedRevisionFivePresentation() {
        val menu = PrimaryMenu(listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)))
        val card = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)

        assertNull(effectivePrimaryMenuForSupport(menu, false))
        assertEquals(CardPresentation.DEFAULT, effectiveCardPresentationForSupport(card, false))
        assertEquals(menu, effectivePrimaryMenuForSupport(menu, true))
        assertEquals(card, effectiveCardPresentationForSupport(card, true))
        assertEquals(menu, effectivePrimaryMenuForSupport(menu, null))
        assertEquals(card, effectiveCardPresentationForSupport(card, null))
    }

    @Test
    fun primaryMenuRoundTripsEverySupportedItemShape() {
        val menu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Library(7, "Movies"),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Section(7, "recently-added", "Recently Added"),
                PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7),
            ),
        )

        assertEquals(
            menu,
            UiCustomizationCodec.parsePrimaryMenu(UiCustomizationCodec.encodePrimaryMenu(menu)),
        )
    }

    @Test
    fun primaryMenuRejectsMissingOrDuplicateHomeAndDuplicateIdentity() {
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json("""{"items":[{"type":"builtin","destination":"calendar"}]}"""),
            ),
        )
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json(
                    """{"items":[{"type":"builtin","destination":"home"},{"type":"builtin","destination":"home"}]}""",
                ),
            ),
        )
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json(
                    """{"items":[{"type":"builtin","destination":"home"},{"type":"library","library_id":7,"label":"A"},{"type":"library","library_id":7,"label":"B"}]}""",
                ),
            ),
        )
        assertNull(UiCustomizationCodec.parsePrimaryMenu(JsonNull))
    }

    @Test
    fun shortcutsForbidBuiltinsButKeepCollectionLibraryOptional() {
        assertNull(
            UiCustomizationCodec.parseShortcuts(
                json("""{"items":[{"type":"builtin","destination":"home"}]}"""),
            ),
        )
        val shortcuts = NavigationShortcuts(
            listOf(PrimaryMenuItem.Collection("watchlist", "Watchlist")),
        )
        assertEquals(
            shortcuts,
            UiCustomizationCodec.parseShortcuts(UiCustomizationCodec.encodeShortcuts(shortcuts)),
        )
    }

    @Test
    fun itemFieldsHonorTheCanonicalTypesAndBounds() {
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json(
                    """{"items":[{"type":"builtin","destination":"home"},{"type":"library","library_id":"7","label":"Movies"}]}""",
                ),
            ),
        )
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json(
                    """{"items":[{"type":"builtin","destination":"home"},{"type":"library","library_id":7,"label":"   "}]}""",
                ),
            ),
        )
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json(
                    """{"items":[{"type":"builtin","destination":"home"},{"type":"section","library_id":7,"section_id":"${"x".repeat(129)}","label":"Recent"}]}""",
                ),
            ),
        )
        assertNull(
            UiCustomizationCodec.parsePrimaryMenu(
                json(
                    """{"items":[{"type":"builtin","destination":"home"},{"type":"section","library_id":7,"section_id":"   ","label":"Recent"}]}""",
                ),
            ),
        )
        assertNull(
            UiCustomizationCodec.parseShortcuts(
                json(
                    """{"items":[{"type":"collection","collection_id":"\t ","label":"Favorites"}]}""",
                ),
            ),
        )
    }

    @Test
    fun profileClientScopeBuildsWithoutContentIdentity() {
        assertEquals(
            SettingScope.PROFILE_CLIENT,
            SettingScopeIdentity.profileClient().scope,
        )
        assertEquals(
            SiloClientFamily.TV,
            SettingScopeIdentity.profileClient(SiloClientFamily.TV).clientFamily,
        )
        assertFailsWith<IllegalArgumentException> {
            SettingScopeIdentity(
                scope = SettingScope.PROFILE,
                clientFamily = SiloClientFamily.TV,
            )
        }
    }

    @Test
    fun semanticIdentitiesRemainDistinctWhenTargetIdsContainColons() {
        val items = listOf(
            PrimaryMenuItem.Section(7, "recent:4", "Recent"),
            PrimaryMenuItem.Section(74, "recent", "Other Recent"),
            PrimaryMenuItem.Collection(":7:favorites", "Unscoped"),
            PrimaryMenuItem.Collection("favorites", "Scoped", libraryId = 7),
            PrimaryMenuItem.Library(7, "Library"),
        )

        assertEquals(items.size, items.map(UiCustomizationCodec::identity).toSet().size)
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw)
}
