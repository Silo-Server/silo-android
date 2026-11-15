package org.siloserver.silo.model.profile

// "original" and its aliases read as Auto: there is no Original choice, and
// Auto plays the source whenever the device and connection can.

fun canonicalProfileQualityPreference(label: String?): String? =
    when (label?.trim()?.lowercase()) {
        null, "", "auto", "original", "source", "max" -> null
        "4k", "2160p" -> "2160p"
        "1080p" -> "1080p"
        "720p" -> "720p"
        "480p" -> "480p"
        else -> label.trim().lowercase()
    }

fun displayProfileQualityPreference(value: String?): String =
    when (value?.trim()?.lowercase()) {
        null, "", "auto", "original", "source", "max" -> "Auto"
        "4k", "2160p" -> "4K"
        "1080p" -> "1080p"
        "720p" -> "720p"
        "480p" -> "480p"
        else -> value.trim()
    }
