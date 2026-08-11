package org.siloserver.silo.playback

/**
 * Canonical codec family shared by catalog persistence and mounted-player
 * identity matching. Raw catalog names and decoder/MIME aliases must serialize
 * to the same value or a player-written preference cannot survive a restart.
 */
fun canonicalSubtitleCodecFamily(codecOrMime: String?): String? {
    val normalized = codecOrMime
        ?.trim()
        ?.filter(Char::isLetterOrDigit)
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    return when {
        normalized.contains("pgs") -> "pgs"
        normalized.contains("vobsub") || normalized.contains("dvdsubtitle") -> "vobsub"
        normalized.contains("dvbsub") -> "dvbsub"
        normalized.contains("subrip") || normalized.endsWith("srt") -> "subrip"
        normalized.contains("webvtt") ||
            normalized == "textvtt" ||
            normalized.endsWith("vtt") -> "webvtt"
        normalized.contains("tx3g") || normalized.contains("movtext") -> "tx3g"
        normalized.contains("ssa") || normalized == "ass" -> "ssa"
        normalized.contains("ttml") -> "ttml"
        normalized.contains("cea608") || normalized.contains("eia608") -> "cea608"
        normalized.contains("cea708") -> "cea708"
        else -> normalized
    }
}

/**
 * Whether [family] is a text subtitle family. Server-materialized text
 * artifacts are served as WebVTT regardless of their catalog source format;
 * bitmap families remain exact-match only.
 */
fun isTextSubtitleCodecFamily(family: String?): Boolean =
    when (canonicalSubtitleCodecFamily(family)) {
        "subrip", "webvtt", "ssa", "ttml", "tx3g" -> true
        else -> false
    }

/** Whether [family] is an image-based subtitle codec or MIME alias. */
fun isBitmapSubtitleCodecFamily(family: String?): Boolean =
    when (canonicalSubtitleCodecFamily(family)) {
        "pgs", "vobsub", "dvbsub" -> true
        else -> false
    }

/**
 * Whether the server can hand this bitmap family to the client as a sidecar
 * instead of burning it into the picture.
 *
 * Mirrors `ResolveSubtitlePolicyV3`: the stream handler raw-serves exactly one
 * bitmap shape — an embedded PGS track as `.sup`. VobSub and DVB have no
 * sidecar route and always burn in, so a pick on those is a burn-in identity
 * from the start rather than a client-mounted one.
 */
fun isClientMountableBitmapCodecFamily(family: String?): Boolean =
    canonicalSubtitleCodecFamily(family) == "pgs"
