package org.siloserver.silo.model.catalog

/**
 * One edition (cut), with every presentation of it merged: a single file and
 * a multi-part set of the same cut are one edition.
 */
data class PlaybackEdition(
    /** The trimmed edition key; "" is the unnamed edition. */
    val id: String,
    val label: String,
    /** The edition's raw name as the server sent it, if any. */
    val raw: String?,
    /**
     * The files the Version menu offers, in server order: each presentation's
     * first part with variants, else every file of the edition.
     */
    val versions: List<FileVersion>,
    /** Every file of the edition, all parts. */
    val fileIds: Set<Int>,
)

fun editionLabel(raw: String?, key: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() }
        ?: key?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("[_-]+"))
            ?.filter { it.isNotEmpty() }?.joinToString(" ") {
                if (it.equals("imax", ignoreCase = true)) "IMAX" else it.replaceFirstChar { c -> c.uppercase() }
            }

val FileVersion.editionLabel: String?
    get() = editionLabel(editionRaw, editionKey)

/** The edition a key names: trimmed, with null and blank both the unnamed edition. */
fun normalizedEditionKey(key: String?): String = key?.trim().orEmpty()

/**
 * The editions of an item, one per edition key, in server order (the order
 * of [variants], else of first appearance in [versions]).
 */
fun playbackEditions(
    versions: List<FileVersion>,
    variants: List<PlaybackVariant> = emptyList(),
): List<PlaybackEdition> {
    val byId = versions.associateBy { it.fileId }
    val serverIndex = versions.withIndex().associate { it.value.fileId to it.index }
    if (variants.isNotEmpty()) {
        return variants.groupBy { normalizedEditionKey(it.editionKey) }.mapNotNull { (key, cut) ->
            val choices = cut
                .flatMap { variant -> variant.parts.minByOrNull { it.partIndex }?.versions.orEmpty() }
                .mapNotNull { byId[it.fileId] }
                .distinctBy { it.fileId }
                .sortedBy { serverIndex.getValue(it.fileId) }
            if (choices.isEmpty()) return@mapNotNull null
            playbackEdition(
                key = key,
                raws = cut.map { it.editionRaw },
                versions = choices,
                fileIds = cut.flatMap { variant -> variant.parts.flatMap { it.versions } }.map { it.fileId }.toSet(),
            )
        }
    }
    return versions.groupBy { normalizedEditionKey(it.editionKey) }.map { (key, files) ->
        playbackEdition(key, files.map { it.editionRaw }, files, files.map { it.fileId }.toSet())
    }
}

private fun playbackEdition(
    key: String,
    raws: List<String?>,
    versions: List<FileVersion>,
    fileIds: Set<Int>,
): PlaybackEdition {
    val raw = raws.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    return PlaybackEdition(key, editionLabel(raw, key) ?: "Standard", raw, versions, fileIds)
}

fun List<PlaybackEdition>.hasEditionChoices(): Boolean = size > 1
