package org.siloserver.silo.tv.ui.screens.profiles

import android.net.Uri
import androidx.annotation.StringRes
import org.siloserver.silo.common.R as CommonR

/**
 * DiceBear avatar presets. Mirrors silo-apple's ProfileAvatarPresets and the
 * web avatar contract:
 *
 *     preset:dicebear:<styleId>:<seed>
 */
object TvProfileAvatarPresets {
    data class Style(
        val id: String,
        @StringRes val label: Int,
        val summary: String,
    )

    data class Preset(
        val styleId: String,
        val seed: String,
    ) {
        val id: String = "$styleId:$seed"
        val ref: String = presetRef(styleId, seed)
        val previewUrl: String = imageUrl(styleId, seed)
    }

    val styles = listOf(
        Style("identicon", CommonR.string.profiles_avatarStyle_identicon, "Geometric patterns"),
        Style("initials", CommonR.string.profiles_avatarStyle_initials, "Letter-based with bold backgrounds"),
        Style("bottts-neutral", CommonR.string.profiles_avatarStyle_bottts, "Modular robot faces"),
        Style("fun-emoji", CommonR.string.profiles_avatarStyle_funEmoji, "Big, colorful faces"),
        Style("pixel-art-neutral", CommonR.string.profiles_avatarStyle_pixelArt, "Retro pixel characters"),
    )

    const val DefaultStyleId = "fun-emoji"
    const val OptionCount = 18

    fun batch(styleId: String, batch: Int = 0): List<Preset> {
        val style = styleId.takeIf { requested -> styles.any { it.id == requested } } ?: DefaultStyleId
        val styleLen = style.length
        return List(OptionCount) { index ->
            val adjectiveIndex = ((batch * 7) + (index * 3) + styleLen).positiveMod(adjectives.size)
            val nounIndex = ((batch * 11) + (index * 5) + (styleLen * 2)).positiveMod(nouns.size)
            Preset(styleId = style, seed = "${adjectives[adjectiveIndex]}-${nouns[nounIndex]}")
        }
    }

    fun presetRef(styleId: String, seed: String): String =
        "preset:dicebear:$styleId:$seed"

    fun imageUrl(styleId: String, seed: String, size: Int = 256): String =
        "https://api.dicebear.com/9.x/${Uri.encode(styleId)}/png?seed=${Uri.encode(seed)}&size=$size"

    fun parseRef(value: String?): Preset? {
        val parts = value?.trim()?.split(':', limit = 4).orEmpty()
        if (parts.size != 4) return null
        if (!parts[0].equals("preset", ignoreCase = true)) return null
        if (!parts[1].equals("dicebear", ignoreCase = true)) return null
        val style = parts[2].trim()
        val seed = parts[3].trim()
        if (style.isEmpty() || seed.isEmpty()) return null
        return Preset(styleId = style, seed = seed)
    }

    fun effectiveAvatarRef(
        styleId: String,
        selectedSeed: String?,
        batch: Int,
        name: String,
        fallbackAvatar: String?,
    ): String? {
        if (styleId == "initials") {
            return presetRef("initials", name.trim().ifBlank { "You" })
        }
        selectedSeed?.takeIf { it.isNotBlank() }?.let { return presetRef(styleId, it) }
        fallbackAvatar?.takeIf { parseRef(it)?.styleId == styleId }?.let { return it }
        fallbackAvatar?.takeIf { it.isNotBlank() && parseRef(it) == null }?.let { return it }
        return batch(styleId, batch).firstOrNull()?.ref
    }

    private fun Int.positiveMod(modulus: Int): Int {
        val remainder = this % modulus
        return if (remainder >= 0) remainder else remainder + modulus
    }

    private val adjectives = listOf(
        "cosmic", "jelly", "starlight", "mango", "bubble", "neon", "marble",
        "comet", "snappy", "pepper", "glimmer", "ripple", "pocket", "candy",
        "ember", "mochi", "sprout", "twinkle", "whiz", "plasma", "mint",
        "velvet", "crunchy", "sunbeam", "toffee", "splash", "boomer", "lunar",
        "fizzy", "biscuit", "rocket", "cobalt",
    )

    private val nouns = listOf(
        "otter", "rocket", "fox", "cookie", "gecko", "bunny", "penguin",
        "puffin", "tiger", "panda", "saturn", "wizard", "skater", "nebula",
        "robot", "pirate", "meteor", "sprite", "pebble", "nova", "dragon",
        "donut", "parrot", "panther", "goblin", "muffin", "laser", "pickle",
        "falcon", "sunset", "toaster", "bandit",
    )
}
