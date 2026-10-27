package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire models for the canonical `ui.card_presentation` setting — the
 * cross-client "Cards & Posters" preference (poster size). The stored value
 * is ALWAYS the complete two-field object; there is no partial patch or
 * per-field merging, matching iOS `CardPresentationPreference` and the
 * server contract exactly.
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

/**
 * Card presentation the client renders from `ui.card_presentation`. Poster size
 * is the only choice: the caption choice is retired and every card shows its
 * title and details.
 */
data class CardPresentation(
    val posterSize: CardPosterSize = CardPosterSize.Standard,
) {
    fun toJsonElement(): JsonElement = json.encodeToJsonElement(Wire.serializer(), Wire(posterSize))

    fun serialize(): String = json.encodeToString(Wire.serializer(), Wire(posterSize))

    /**
     * The stored contract object. The schema still requires `caption`, so it
     * is always written as `title_metadata`; a stored value of any shape is
     * read and ignored.
     */
    @Serializable
    private data class Wire(
        @SerialName("poster_size") val posterSize: CardPosterSize = CardPosterSize.Standard,
        val caption: JsonElement = JsonPrimitive(RETIRED_CAPTION),
    )

    companion object {
        val DEFAULT = CardPresentation()

        private const val RETIRED_CAPTION = "title_metadata"

        // encodeDefaults: the contract requires the complete two-field object
        // (additionalProperties:false, both fields required), so default-valued
        // axes must still be written.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Defensive wire decode: null/malformed JSON or an unknown
         * `poster_size` (a newer server's) yields null so callers fall back
         * to [DEFAULT] instead of crashing. A missing field decodes to its
         * default per the contract's whole-object semantics.
         */
        fun decodeOrNull(element: JsonElement?): CardPresentation? {
            if (element == null || element is JsonNull) return null
            return runCatching { json.decodeFromJsonElement(Wire.serializer(), element) }
                .getOrNull()?.let { CardPresentation(it.posterSize) }
        }

        fun decodeOrNull(raw: String?): CardPresentation? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(Wire.serializer(), raw) }
                .getOrNull()?.let { CardPresentation(it.posterSize) }
        }

        fun decodeOrDefault(element: JsonElement?): CardPresentation =
            decodeOrNull(element) ?: DEFAULT

        fun decodeOrDefault(raw: String?): CardPresentation =
            decodeOrNull(raw) ?: DEFAULT
    }
}
