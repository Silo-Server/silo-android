package org.siloserver.silo.repository

import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.model.section.SectionOverride

/**
 * The override set a profile saves for its Home page
 * (`PUT /api/v2/profile/sections?scope=home`), which replaces every override
 * the profile saved for Home. Mirrors the web editor's `buildSectionOverrides`.
 *
 * [rows] is the Home settings list (`/profile/sections/settings`) in the new
 * display order, with each row's chosen visibility; [baseline] is the same
 * list as the server last confirmed it.
 *
 * The server uses a saved position whenever one is present, so positions are
 * written only once the profile has ordered its Home: when [rows] reorders
 * [baseline], or an earlier save already stored a position for an admin row.
 * Then every row stores its list index. Until then admin rows keep following
 * the admin's order: only a hidden admin row gets a new override, and a
 * saved override keeps the position it has.
 *
 * A row with a saved override keeps that override, its id and every other
 * field (a rename, a featured or item-limit change, a profile-built row's
 * recipe and config). A saved override whose row is not in [rows] (a row the
 * profile removed, or one it cannot currently see) is kept unchanged.
 */
fun buildHomeSectionOverrides(
    rows: List<ProfileSectionSetting>,
    saved: List<SectionOverride>,
    baseline: List<ProfileSectionSetting>,
    newId: () -> String,
): List<SectionOverride> {
    // The server resolves the last saved override for a section, so that is
    // the one a row keeps.
    val savedByRow = LinkedHashMap<String, SectionOverride>()
    for (override in saved) {
        val key = override.rowKey() ?: continue
        savedByRow[key] = override
    }
    val ordered = saved.any { it.sectionId.isNotBlank() && it.position != null } ||
        sharedOrder(rows, baseline) != sharedOrder(baseline, rows)
    val matched = mutableSetOf<SectionOverride>()
    val rowOverrides = rows.mapIndexedNotNull { index, row ->
        val existing = savedByRow[row.rowKey()]
        when {
            existing != null -> {
                matched += existing
                existing.copy(
                    id = existing.id.ifBlank { newId() },
                    position = if (ordered) index else existing.position,
                    hidden = row.hidden,
                )
            }
            // A profile-built row only exists as its saved override; without
            // it there is no recipe to write back.
            row.isCustom -> null
            ordered -> SectionOverride(id = newId(), sectionId = row.id, position = index, hidden = row.hidden)
            row.hidden -> SectionOverride(id = newId(), sectionId = row.id, hidden = true)
            // An untouched visible admin row needs no override.
            else -> null
        }
    }
    // Unlisted overrides go first: should one duplicate a listed row's
    // section, the listed row's override is still the last and wins.
    val unlisted = saved.filter { it !in matched }
    return unlisted + rowOverrides
}

/** The row keys of [a] in order, keeping only rows [b] also has. */
private fun sharedOrder(a: List<ProfileSectionSetting>, b: List<ProfileSectionSetting>): List<String> {
    val keys = b.mapTo(mutableSetOf()) { it.rowKey() }
    return a.map { it.rowKey() }.filter { it in keys }
}

/** Admin rows are matched by section id, profile-built rows by override id. */
private fun ProfileSectionSetting.rowKey(): String = if (isCustom) "custom:$id" else "admin:$id"

private fun SectionOverride.rowKey(): String? = when {
    sectionId.isNotBlank() -> "admin:$sectionId"
    id.isNotBlank() -> "custom:$id"
    else -> null
}
