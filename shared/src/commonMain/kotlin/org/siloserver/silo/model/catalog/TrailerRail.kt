package org.siloserver.silo.model.catalog

/**
 * One card in the detail page's "Trailers & More" rail: either a remote
 * provider video (YouTube) or a locally scanned extras file (trailer,
 * featurette, behind the scenes, …).
 *
 * Keys are namespaced so a local extra whose contentId collides with a
 * YouTube key cannot shadow it. Mirrors Apple's `TrailerRailEntry`.
 */
sealed interface TrailerRailEntry {
    val key: String
    val title: String
    val kind: String

    data class Remote(val video: ItemVideo) : TrailerRailEntry {
        override val key = "remote:${video.site}:${video.siteKey}"
        override val title = video.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: extraKindLabel(video.kind)
        override val kind = video.kind
    }

    data class Local(val extra: ItemExtra) : TrailerRailEntry {
        override val key = "local:${extra.contentId}"
        override val title = extra.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: extraKindLabel(extra.kind)
        override val kind = extra.kind
    }
}

/**
 * Merge an item's remote videos and local extras into one ordered rail.
 *
 * Remote videos come first in server order (the server already sorts
 * trailers and official uploads first), then local extras in server order.
 * YouTube is the only remote site the clients can open, so other sites are
 * dropped rather than rendered as cards that do nothing.
 */
fun trailerRailEntries(detail: ItemDetail): List<TrailerRailEntry> = buildList {
    detail.videos.orEmpty()
        .filter { it.site.equals("youtube", ignoreCase = true) && it.siteKey.isNotBlank() }
        .forEach { add(TrailerRailEntry.Remote(it)) }
    detail.extras.orEmpty().forEach { add(TrailerRailEntry.Local(it)) }
}.distinctBy(TrailerRailEntry::key)

/** YouTube's still for a video. `hqdefault` exists for every upload. */
fun youtubeThumbnailUrl(siteKey: String): String = "https://i.ytimg.com/vi/$siteKey/hqdefault.jpg"

/** Local extras are real files, so the scanner knows their runtime. */
fun trailerRailDurationLabel(entry: TrailerRailEntry): String? {
    val totalSeconds = (entry as? TrailerRailEntry.Local)?.extra?.durationSeconds
        ?.takeIf { it > 0 }
        ?: return null
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun extraKindLabel(kind: String): String = when (kind.lowercase()) {
    "trailer" -> "Trailer"
    "teaser" -> "Teaser"
    "featurette" -> "Featurette"
    "behind_the_scenes", "behind-the-scenes" -> "Behind the Scenes"
    "deleted_scene", "deleted-scene" -> "Deleted Scene"
    "interview" -> "Interview"
    else -> kind.replace('_', ' ').replace('-', ' ').trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { "Extra" }
}
