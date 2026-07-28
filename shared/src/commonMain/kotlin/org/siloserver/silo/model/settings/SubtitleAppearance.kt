package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `wire` repeats what each `@SerialName` declares. It is spelled out as a
 * property so the granular, client-local `subtitle.*` slots can be parsed and
 * written without a JSON round-trip — see [SubtitleAppearanceProjection] — and
 * so a rename cannot change the enum's wire form on one path only.
 */
@Serializable
enum class SubtitleFontSizePreset(val wire: String) {
    @SerialName("small") Small("small"),
    @SerialName("medium") Medium("medium"),
    @SerialName("large") Large("large"),
    @SerialName("xlarge") XLarge("xlarge"),
    @SerialName("xxlarge") XXLarge("xxlarge"),
}

val SubtitleFontSizePreset.pointSize: Double
    get() = when (this) {
        SubtitleFontSizePreset.Small -> 36.0
        SubtitleFontSizePreset.Medium -> 44.0
        SubtitleFontSizePreset.Large -> 56.0
        SubtitleFontSizePreset.XLarge -> 68.0
        SubtitleFontSizePreset.XXLarge -> 82.0
    }

@Serializable
enum class SubtitleBackgroundStylePreset(val wire: String) {
    @SerialName("box") Box("box"),
    @SerialName("shadow") Shadow("shadow"),
    @SerialName("outline") Outline("outline"),
    @SerialName("none") None("none"),
}

@Serializable
enum class SubtitlePositionPreset(val wire: String) {
    @SerialName("bottom") Bottom("bottom"),
    @SerialName("lower-third") LowerThird("lower-third"),
    @SerialName("top") Top("top"),
}

val SubtitlePositionPreset.legacyPosition: Int
    get() = when (this) {
        SubtitlePositionPreset.Top -> 0
        SubtitlePositionPreset.LowerThird -> 70
        SubtitlePositionPreset.Bottom -> 100
    }

@Serializable
data class SubtitleAppearance(
    val fontSize: SubtitleFontSizePreset = SubtitleFontSizePreset.Large,
    val fontFamily: String = SANS_SERIF,
    val fontColor: String = "#ffffff",
    val backgroundColor: String = "#000000",
    val backgroundStyle: SubtitleBackgroundStylePreset = SubtitleBackgroundStylePreset.Shadow,
    val backgroundOpacity: Int = 75,
    val textOutline: Boolean = false,
    val textOutlineColor: String = "#000000",
    val position: SubtitlePositionPreset = SubtitlePositionPreset.Bottom,
) {
    fun sanitized(): SubtitleAppearance {
        val safeFontColor = if (isValidHex(fontColor)) fontColor else DEFAULT.fontColor
        val safeBackgroundColor = if (isValidHex(backgroundColor)) backgroundColor else DEFAULT.backgroundColor
        val safeOutlineColor = if (isValidHex(textOutlineColor)) textOutlineColor else DEFAULT.textOutlineColor
        val clampedOpacity = backgroundOpacity.coerceIn(0, 100)
        return if (
            safeFontColor == fontColor &&
            safeBackgroundColor == backgroundColor &&
            safeOutlineColor == textOutlineColor &&
            clampedOpacity == backgroundOpacity
        ) {
            this
        } else {
            copy(
                fontColor = safeFontColor,
                backgroundColor = safeBackgroundColor,
                textOutlineColor = safeOutlineColor,
                backgroundOpacity = clampedOpacity,
            )
        }
    }

    fun toJsonString(): String = JSON.encodeToString(serializer(), sanitized())

    companion object {
        const val SANS_SERIF: String = "sans-serif"
        const val SERIF: String = "serif"
        const val MONOSPACE: String = "monospace"

        val DEFAULT: SubtitleAppearance = SubtitleAppearance()

        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun decode(json: String?): SubtitleAppearance {
            if (json.isNullOrBlank()) return DEFAULT.sanitized()
            return try {
                JSON.decodeFromString(serializer(), json).sanitized()
            } catch (_: SerializationException) {
                DEFAULT.sanitized()
            } catch (_: Throwable) {
                DEFAULT.sanitized()
            }
        }

        private fun isValidHex(value: String): Boolean {
            val trimmed = if (value.startsWith("#")) value.drop(1) else value
            if (trimmed.length != 6) return false
            return trimmed.all { c ->
                (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            }
        }
    }
}
