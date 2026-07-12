package org.siloserver.silo.common.player.video

object VideoPlayerRouteArgs {
    const val RESUME_POSITION = "resumePosition"
    const val QUALITY = "quality"

    fun parseResumePosition(value: String?): Double? {
        val parsed = value?.toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun encodeResumePosition(value: Double?): String? {
        val valid = value?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return valid.toString()
    }

    fun normalizeQuality(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it in PLAYBACK_QUALITIES }

    private val PLAYBACK_QUALITIES = setOf("auto", "original", "2160p", "1080p", "720p", "480p")
}
