package org.siloserver.silo.common.ui

import org.siloserver.silo.model.catalog.ItemDetail

fun movieDirectorCredit(detail: ItemDetail): String? {
    if (!detail.type.equals("movie", ignoreCase = true)) return null
    val names = detail.crew
        .asSequence()
        .filter { it.job?.trim().equals("Director", ignoreCase = true) }
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(3)
        .toList()
    return names.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "Directed by ", separator = ", ")
}
