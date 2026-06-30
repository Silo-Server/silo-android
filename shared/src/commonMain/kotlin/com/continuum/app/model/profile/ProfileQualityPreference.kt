package com.continuum.app.model.profile

fun canonicalProfileQualityPreference(label: String?): String? =
    when (label?.trim()?.lowercase()) {
        null, "", "auto" -> null
        "4k", "2160p" -> "2160p"
        "1080p" -> "1080p"
        "720p" -> "720p"
        "480p" -> "480p"
        else -> label.trim().lowercase()
    }

fun displayProfileQualityPreference(value: String?): String =
    when (value?.trim()?.lowercase()) {
        null, "", "auto" -> "Auto"
        "4k", "2160p" -> "4K"
        "1080p" -> "1080p"
        "720p" -> "720p"
        "480p" -> "480p"
        else -> value.trim()
    }
