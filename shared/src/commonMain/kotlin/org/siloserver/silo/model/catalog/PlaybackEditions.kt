package org.siloserver.silo.model.catalog

/** An edition's first playable part and the files that identify it across all parts. */
data class PlaybackEdition(
    val id: String,
    val label: String,
    val versions: List<FileVersion>,
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

/** Matches web's edition choices: an edition offers the files of its first part. */
fun playbackEditions(
    versions: List<FileVersion>,
    variants: List<PlaybackVariant> = emptyList(),
): List<PlaybackEdition> {
    val byId = versions.associateBy { it.fileId }
    if (variants.isNotEmpty()) {
        return variants.mapNotNull { variant ->
            val firstPart = variant.parts.minByOrNull { it.partIndex } ?: return@mapNotNull null
            val choices = firstPart.versions.mapNotNull { byId[it.fileId] }
            if (choices.isEmpty()) return@mapNotNull null
            PlaybackEdition(
                id = variant.variantId,
                label = editionLabel(variant.editionRaw, variant.editionKey) ?: "Standard",
                versions = choices,
                fileIds = variant.parts.flatMap { it.versions }.map { it.fileId }.toSet(),
            )
        }
    }
    return versions.groupBy { it.editionKey?.trim()?.takeIf(String::isNotEmpty) ?: it.editionLabel.orEmpty() }
        .map { (key, files) ->
            PlaybackEdition(
                id = key,
                label = files.first().editionLabel ?: "Standard",
                versions = files,
                fileIds = files.map { it.fileId }.toSet(),
            )
        }
}

fun List<PlaybackEdition>.hasEditionChoices(): Boolean =
    size > 1 && map { it.label.lowercase() }.distinct().size > 1
