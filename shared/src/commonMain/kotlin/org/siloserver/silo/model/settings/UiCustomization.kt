package org.siloserver.silo.model.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Canonical like-device families used by `X-Silo-Client-Family`. */
enum class SiloClientFamily(val wire: String) {
    TV("tv"),
    MOBILE("mobile"),
    TABLET("tablet"),
    DESKTOP("desktop"),
    WEB("web"),
}

enum class PosterSizePreset(val wire: String, val label: String) {
    COMPACT("compact", "Compact"),
    STANDARD("standard", "Standard"),
    LARGE("large", "Large");

    companion object {
        fun fromWire(value: String?): PosterSizePreset? =
            entries.firstOrNull { it.wire == value }
    }
}

enum class CardCaptionPreset(val wire: String, val label: String) {
    TITLE_METADATA("title_metadata", "Title & metadata"),
    TITLE("title", "Title only"),
    ARTWORK("artwork", "Artwork only");

    companion object {
        fun fromWire(value: String?): CardCaptionPreset? =
            entries.firstOrNull { it.wire == value }
    }
}

data class CardPresentation(
    val posterSize: PosterSizePreset = PosterSizePreset.STANDARD,
    val caption: CardCaptionPreset = CardCaptionPreset.TITLE_METADATA,
) {
    companion object {
        val DEFAULT = CardPresentation()
    }
}

/**
 * Friendly combinations shared by every client UI. These are shortcuts over
 * the canonical two-axis card presentation value, not a separate wire value,
 * so choosing either granular control naturally produces a Custom state when
 * it no longer matches one of these combinations.
 */
enum class CardPresentationPreset(
    val wire: String,
    val label: String,
    val presentation: CardPresentation,
) {
    BALANCED(
        "balanced",
        "Balanced",
        CardPresentation(PosterSizePreset.STANDARD, CardCaptionPreset.TITLE_METADATA),
    ),
    COMPACT(
        "compact",
        "Compact",
        CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.TITLE),
    ),
    CINEMA(
        "cinema",
        "Cinema",
        CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.TITLE),
    ),
    ARTWORK_ONLY(
        "artwork_only",
        "Artwork Only",
        CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
    );

    companion object {
        fun fromWire(value: String?): CardPresentationPreset? =
            entries.firstOrNull { it.wire == value }

        fun matching(value: CardPresentation): CardPresentationPreset? =
            entries.firstOrNull { it.presentation == value }
    }
}

enum class PrimaryMenuBuiltin(val wire: String, val label: String) {
    HOME("home", "Home"),
    MOVIES("movies", "Movies"),
    SERIES("series", "Series"),
    MUSIC("music", "Music"),
    AUDIOBOOKS("audiobooks", "Audiobooks"),
    FOR_YOU("for_you", "For You"),
    CALENDAR("calendar", "Calendar");

    companion object {
        fun fromWire(value: String?): PrimaryMenuBuiltin? =
            entries.firstOrNull { it.wire == value }
    }
}

sealed interface PrimaryMenuItem {
    val label: String

    data class Builtin(val destination: PrimaryMenuBuiltin) : PrimaryMenuItem {
        override val label: String get() = destination.label
    }

    data class Library(
        val libraryId: Int,
        override val label: String,
    ) : PrimaryMenuItem

    data class Section(
        val libraryId: Int,
        val sectionId: String,
        override val label: String,
    ) : PrimaryMenuItem

    data class Collection(
        val collectionId: String,
        override val label: String,
        val libraryId: Int? = null,
    ) : PrimaryMenuItem
}

data class PrimaryMenu(val items: List<PrimaryMenuItem>)

/**
 * A confirmed incompatible server must not activate revision-5 cached UI
 * values. A null capability is deliberately treated as unknown so an offline
 * launch can continue using its last trusted presentation.
 */
fun effectivePrimaryMenuForSupport(
    value: PrimaryMenu?,
    uiCustomizationSupported: Boolean?,
): PrimaryMenu? = value.takeUnless { uiCustomizationSupported == false }

fun effectiveCardPresentationForSupport(
    value: CardPresentation,
    uiCustomizationSupported: Boolean?,
): CardPresentation = value.takeUnless { uiCustomizationSupported == false }
    ?: CardPresentation.DEFAULT

data class NavigationShortcuts(val items: List<PrimaryMenuItem>) {
    companion object {
        val EMPTY = NavigationShortcuts(emptyList())
    }
}

/** Server-contract collection bounds shared by codecs and client editors. */
object UiCustomizationLimits {
    const val MAX_PRIMARY_MENU_ITEMS = 64
    const val MAX_NAVIGATION_SHORTCUT_ITEMS = 256
}

/**
 * Strict, defensive codecs for settings-manifest revision 5.
 *
 * The server validates writes, but locally cached values survive server
 * switches and app upgrades. Rejecting malformed cache documents as a whole
 * keeps navigation usable (the caller falls back to its native default) and
 * guarantees Android never renders a menu without Home.
 */
object UiCustomizationCodec {
    fun parseCardPresentation(value: JsonElement?): CardPresentation? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys != setOf("poster_size", "caption")) return null
        val size = PosterSizePreset.fromWire(obj.string("poster_size")) ?: return null
        val caption = CardCaptionPreset.fromWire(obj.string("caption")) ?: return null
        return CardPresentation(size, caption)
    }

    fun encodeCardPresentation(value: CardPresentation): JsonObject = buildJsonObject {
        put("poster_size", value.posterSize.wire)
        put("caption", value.caption.wire)
    }

    /** `null` means inherit the client's native/default primary menu. */
    fun parsePrimaryMenu(value: JsonElement?): PrimaryMenu? {
        if (value == null || value is JsonNull) return null
        val obj = value as? JsonObject ?: return null
        if (obj.keys != setOf("items")) return null
        val array = obj["items"] as? JsonArray ?: return null
        if (array.size !in 1..UiCustomizationLimits.MAX_PRIMARY_MENU_ITEMS) return null
        val items = array.map { parseItem(it, allowBuiltin = true) ?: return null }
        if (items.map(::identity).toSet().size != items.size) return null
        if (items.count { it == PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME) } != 1) return null
        return PrimaryMenu(items)
    }

    fun encodePrimaryMenu(value: PrimaryMenu): JsonObject = buildJsonObject {
        put("items", buildJsonArray { value.items.forEach { add(encodeItem(it)) } })
    }

    fun parseShortcuts(value: JsonElement?): NavigationShortcuts? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys != setOf("items")) return null
        val array = obj["items"] as? JsonArray ?: return null
        if (array.size > UiCustomizationLimits.MAX_NAVIGATION_SHORTCUT_ITEMS) return null
        val items = array.map { parseItem(it, allowBuiltin = false) ?: return null }
        if (items.map(::identity).toSet().size != items.size) return null
        return NavigationShortcuts(items)
    }

    fun encodeShortcuts(value: NavigationShortcuts): JsonObject = buildJsonObject {
        put("items", buildJsonArray { value.items.forEach { add(encodeItem(it)) } })
    }

    /** One non-builtin shortcut item for the atomic membership endpoint. */
    fun parseShortcutItem(value: JsonElement?): PrimaryMenuItem? =
        value?.let { parseItem(it, allowBuiltin = false) }

    fun encodeShortcutItem(value: PrimaryMenuItem): JsonObject? {
        if (value is PrimaryMenuItem.Builtin) return null
        return encodeItem(value).takeIf { parseShortcutItem(it) != null }
    }

    fun identity(item: PrimaryMenuItem): String = when (item) {
        is PrimaryMenuItem.Builtin -> "builtin:${item.destination.wire}"
        is PrimaryMenuItem.Library -> "library:${item.libraryId}"
        is PrimaryMenuItem.Section -> "section:${item.libraryId}:${item.sectionId}"
        is PrimaryMenuItem.Collection ->
            "collection:${item.libraryId ?: ""}:${item.collectionId}"
    }

    private fun parseItem(value: JsonElement, allowBuiltin: Boolean): PrimaryMenuItem? {
        val obj = value as? JsonObject ?: return null
        return when (obj.string("type")) {
            "builtin" -> {
                if (!allowBuiltin || obj.keys != setOf("type", "destination")) return null
                PrimaryMenuBuiltin.fromWire(obj.string("destination"))
                    ?.let { PrimaryMenuItem.Builtin(it) }
            }
            "library" -> {
                if (obj.keys != setOf("type", "library_id", "label")) return null
                val id = obj.positiveInt("library_id") ?: return null
                val label = obj.boundedLabel("label") ?: return null
                PrimaryMenuItem.Library(id, label)
            }
            "section" -> {
                if (obj.keys != setOf("type", "library_id", "section_id", "label")) return null
                val libraryId = obj.positiveInt("library_id") ?: return null
                val sectionId = obj.boundedTargetId("section_id") ?: return null
                val label = obj.boundedLabel("label") ?: return null
                PrimaryMenuItem.Section(libraryId, sectionId, label)
            }
            "collection" -> {
                val allowed = setOf("type", "collection_id", "label", "library_id")
                if (obj.keys.any { it !in allowed } || obj.keys.none { it == "collection_id" } ||
                    obj.keys.none { it == "label" }
                ) return null
                val collectionId = obj.boundedTargetId("collection_id")
                    ?: return null
                val label = obj.boundedLabel("label") ?: return null
                val libraryId = obj["library_id"]?.let {
                    obj.positiveInt("library_id") ?: return null
                }
                PrimaryMenuItem.Collection(collectionId, label, libraryId)
            }
            else -> null
        }
    }

    private fun encodeItem(item: PrimaryMenuItem): JsonObject = buildJsonObject {
        when (item) {
            is PrimaryMenuItem.Builtin -> {
                put("type", "builtin")
                put("destination", item.destination.wire)
            }
            is PrimaryMenuItem.Library -> {
                put("type", "library")
                put("library_id", item.libraryId)
                put("label", item.label)
            }
            is PrimaryMenuItem.Section -> {
                put("type", "section")
                put("library_id", item.libraryId)
                put("section_id", item.sectionId)
                put("label", item.label)
            }
            is PrimaryMenuItem.Collection -> {
                put("type", "collection")
                put("collection_id", item.collectionId)
                put("label", item.label)
                item.libraryId?.let { put("library_id", it) }
            }
        }
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.contentOrNull
    }

    private fun JsonObject.boundedString(key: String, maxLength: Int): String? =
        string(key)?.takeIf { it.isNotEmpty() && it.length <= maxLength }

    private fun JsonObject.boundedLabel(key: String): String? =
        boundedString(key, maxLength = 256)?.takeIf { label ->
            label.any { character -> !character.isWhitespace() }
        }

    private fun JsonObject.boundedTargetId(key: String): String? =
        boundedString(key, maxLength = 128)?.takeIf { id ->
            id.any { character -> !character.isWhitespace() }
        }

    private fun JsonObject.positiveInt(key: String): Int? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull?.takeIf { it > 0 }
    }
}
