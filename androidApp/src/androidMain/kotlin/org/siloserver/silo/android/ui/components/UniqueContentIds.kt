package org.siloserver.silo.android.ui.components

/**
 * Compose keyed lazy lists throw [IllegalArgumentException] on a repeated key.
 *
 * Server feeds can return the same title twice in one row — that crashed the
 * TV app in production (`Key "series-tvdb-280619" was already used`, #188).
 * Phone rows and grids key on `contentId` the same way and had no equivalent
 * guard. Deduplicate by identity before keying; first occurrence wins.
 */
fun <T> List<T>.uniqueByContentId(id: (T) -> String): List<T> = distinctBy(id)
