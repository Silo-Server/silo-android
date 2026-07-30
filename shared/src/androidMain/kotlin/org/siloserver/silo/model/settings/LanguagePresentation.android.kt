package org.siloserver.silo.model.settings

import java.util.Locale

internal actual fun localizedLanguageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag.replace('_', '-'))
    return locale.getDisplayName(Locale.getDefault()).takeIf { it.isNotBlank() }
        ?: tag
}

internal actual fun canonicalLanguageIdentity(tag: String): String {
    val parts = tag.replace('_', '-').split('-').toMutableList()
    if (parts.isEmpty()) return tag.lowercase(Locale.ROOT)

    val primary = parts.first().lowercase(Locale.ROOT)
    val canonicalPrimary = if (primary.length == 3) {
        Locale.getISOLanguages().firstOrNull { twoLetter ->
            runCatching {
                Locale.forLanguageTag(twoLetter).isO3Language.equals(primary, ignoreCase = true)
            }
                .getOrDefault(false)
        } ?: primary
    } else {
        primary
    }
    parts[0] = canonicalPrimary
    return parts.joinToString("-").lowercase(Locale.ROOT)
}
