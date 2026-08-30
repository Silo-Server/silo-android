package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Wire models for the canonical `ui.card_presentation` setting — the
 * cross-client "Cards & Posters" preference (poster size + caption style).
 * The stored value is ALWAYS the complete two-field object; there is no
 * partial patch or per-field merging, matching iOS
 * `CardPresentationPreference` and the server contract exactly.
 */

@Serializable
enum class CardPosterSize(val raw: String) {
    @SerialName("compact") Compact("compact"),
    @SerialName("standard") Standard("standard"),
    @SerialName("large") Large("large"),
    ;

    /**
     * Artwork scale used by free-scrolling rails and standalone cards.
     * Grids adjust their column count (or adaptive min width) instead so the
     * selected size never causes overlapping focus frames.
     */
    val posterScale: Float
        get() = when (this) {
            Compact -> 0.86f
            Standard -> 1f
            Large -> 1.2f
        }

    val displayName: String
        get() = when (this) {
            Compact -> "Compact"
            Standard -> "Standard"
            Large -> "Large"
        }

    companion object {
        fun fromRaw(value: String?): CardPosterSize? =
            value?.let { v -> entries.firstOrNull { it.raw == v } }
    }
}

@Serializable
enum class CardCaption(val raw: String) {
    @SerialName("title_metadata") TitleMetadata("title_metadata"),
    @SerialName("title") Title("title"),
    @SerialName("artwork") Artwork("artwork"),
    ;

    /** Gates the title line under every browse/rail/grid card. */
    val showsTitle: Boolean get() = this != Artwork

    /** Gates the metadata/year line under every browse/rail/grid card. */
    val showsMetadata: Boolean get() = this == TitleMetadata

    val displayName: String
        get() = when (this) {
            TitleMetadata -> "Title & Metadata"
            Title -> "Title Only"
            Artwork -> "Artwork Only"
        }

    companion object {
        fun fromRaw(value: String?): CardCaption? =
            value?.let { v -> entries.firstOrNull { it.raw == v } }
    }
}

@Serializable
data class CardPresentation(
    @SerialName("poster_size") val posterSize: CardPosterSize = CardPosterSize.Standard,
    val caption: CardCaption = CardCaption.TitleMetadata,
) {
    /** The client-side preset this pair matches, or null when "Custom". */
    val preset: CardPresentationPreset?
        get() = CardPresentationPreset.entries.firstOrNull { it.presentation == this }

    fun toJsonElement(): JsonElement = json.encodeToJsonElement(this)

    fun serialize(): String = json.encodeToString(serializer(), this)

    companion object {
        val DEFAULT = CardPresentation()

        // encodeDefaults: the contract requires the complete two-field object
        // (additionalProperties:false, both fields required), so default-valued
        // axes must still be written.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Defensive wire decode: null/malformed JSON or an unknown enum value
         * (a newer server's `poster_size`/`caption`) yields null so callers
         * fall back to [DEFAULT] instead of crashing. A missing field decodes
         * to its default per the contract's whole-object semantics.
         */
        fun decodeOrNull(element: JsonElement?): CardPresentation? {
            if (element == null || element is JsonNull) return null
            return runCatching { json.decodeFromJsonElement(serializer(), element) }.getOrNull()
        }

        fun decodeOrNull(raw: String?): CardPresentation? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
        }

        fun decodeOrDefault(element: JsonElement?): CardPresentation =
            decodeOrNull(element) ?: DEFAULT

        fun decodeOrDefault(raw: String?): CardPresentation =
            decodeOrNull(raw) ?: DEFAULT
    }
}

/**
 * Friendly cross-client recipes over the two contract axes. Never on the
 * wire — the server only ever stores the normalized size/caption object — so
 * a user can start from a preset and still fine-tune either control. A pair
 * matching no preset renders as a synthetic "Custom" in pickers
 * ([CardPresentation.preset] == null).
 */
enum class CardPresentationPreset {
    Balanced,
    Compact,
    Cinema,
    ArtworkOnly,
    ;

    val presentation: CardPresentation
        get() = when (this) {
            Balanced -> CardPresentation.DEFAULT
            Compact -> CardPresentation(CardPosterSize.Compact, CardCaption.Title)
            Cinema -> CardPresentation(CardPosterSize.Large, CardCaption.Title)
            ArtworkOnly -> CardPresentation(CardPosterSize.Large, CardCaption.Artwork)
        }

    val displayName: String
        get() = when (this) {
            Balanced -> "Balanced"
            Compact -> "Compact"
            Cinema -> "Cinema"
            ArtworkOnly -> "Artwork Only"
        }
}
