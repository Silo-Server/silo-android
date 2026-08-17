package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.catalog.filter.BrowseFacetMediaType
import org.siloserver.silo.catalog.filter.CatalogFacet
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.catalog.filter.facetOptionPairs
import org.siloserver.silo.model.catalog.CatalogFiltersResponse

/**
 * Browse filter sheet, mirroring iOS `FilterView`:
 *
 * 1. Quick section — one-tap watch status (single-select) and dynamic range;
 * 2. Category rows — one per multi-value facet, drilling into a value picker
 *    (with a local search field once the vocabulary passes 12 options);
 * 3. Match All/Any capsule (combines *across* facets);
 * 4. "Preserve sort & filters" toggle.
 *
 * There is no Apply button: the sheet edits a draft and commits on dismiss
 * (iOS commits via onDisappear). Sort lives outside in the control bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    currentFilters: CatalogFilterState,
    availableFilters: CatalogFiltersResponse?,
    mediaType: BrowseFacetMediaType,
    onCommit: (CatalogFilterState) -> Unit,
    onDismiss: () -> Unit,
    // Browse-only rows. Saved lists (Watchlist / Favorites) pass neither: they
    // have one density and are session-scoped, so the rows are omitted.
    viewDensity: CatalogViewDensity? = null,
    onSelectDensity: ((CatalogViewDensity) -> Unit)? = null,
    preserveFilters: Boolean? = null,
    onSetPreserve: ((Boolean) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(currentFilters) }
    var pickerFacet by remember { mutableStateOf<CatalogFacet?>(null) }

    fun commitAndDismiss() {
        onCommit(draft)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { commitAndDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val facets = CatalogFacet.available(mediaType)

            pickerFacet?.let { facet ->
                FacetValuePicker(
                    facet = facet,
                    options = facetOptionPairs(facet, availableFilters),
                    selected = draft.valuesFor(facet),
                    onToggle = { value -> draft = draft.toggle(facet, value) },
                    onClear = { draft = draft.clear(facet) },
                    onBack = { pickerFacet = null },
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { draft = draft.resetFilters() },
                    enabled = draft.canResetFilters,
                ) {
                    Text("Reset")
                }
            }

            // View density lives here rather than beside the sort controls,
            // where the layout options read as extra sort choices.
            if (viewDensity != null && onSelectDensity != null) {
                Text(
                    text = "View",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogViewDensity.entries.forEach { density ->
                        FilterChip(
                            selected = viewDensity == density,
                            onClick = { onSelectDensity(density) },
                            label = { Text(density.label) },
                        )
                    }
                }
            }

            // Quick section: watch status (single-select) + dynamic range.
            val watchOptions = facetOptionPairs(CatalogFacet.WatchStatus, availableFilters)
            if (watchOptions.isNotEmpty()) {
                SectionLabel("Watch Status")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    watchOptions.forEach { (value, label) ->
                        val selected = value in draft.valuesFor(CatalogFacet.WatchStatus)
                        FilterChip(
                            selected = selected,
                            onClick = { draft = draft.toggle(CatalogFacet.WatchStatus, value) },
                            label = { Text(label) },
                            colors = filterSheetChipColors(selected),
                            border = null,
                        )
                    }
                }
            }
            if (CatalogFacet.DynamicRange in facets) {
                SectionLabel("Dynamic Range")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CatalogFacet.DYNAMIC_RANGE_OPTIONS.forEach { (value, label) ->
                        val selected = value in draft.valuesFor(CatalogFacet.DynamicRange)
                        FilterChip(
                            selected = selected,
                            onClick = { draft = draft.toggle(CatalogFacet.DynamicRange, value) },
                            label = { Text(label) },
                            colors = filterSheetChipColors(selected),
                            border = null,
                        )
                    }
                }
            }

            // Category rows — drill-in pickers for the multi-value facets.
            SectionLabel("Categories")
            facets
                .filter { it != CatalogFacet.WatchStatus && it != CatalogFacet.DynamicRange }
                .forEach { facet ->
                    val options = facetOptionPairs(facet, availableFilters)
                    if (options.isEmpty()) return@forEach
                    val selected = draft.valuesFor(facet)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pickerFacet = facet }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = facet.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.isNotEmpty()) {
                            val first = facetValueLabel(facet, selected.sorted().first())
                            Text(
                                text = if (selected.size > 1) "$first +${selected.size - 1}" else first,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

            // Match All / Any — combines across facets; values within one
            // facet are always OR'd.
            SectionLabel("Match")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.matchAll,
                    onClick = { draft = draft.copy(matchAll = true) },
                    label = { Text("All") },
                    colors = filterSheetChipColors(draft.matchAll),
                    border = null,
                )
                FilterChip(
                    selected = !draft.matchAll,
                    onClick = { draft = draft.copy(matchAll = false) },
                    label = { Text("Any") },
                    colors = filterSheetChipColors(!draft.matchAll),
                    border = null,
                )
            }
            Text(
                text = if (draft.matchAll) {
                    "Results match every selected filter."
                } else {
                    "Results match any selected filter."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (preserveFilters != null && onSetPreserve != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Preserve sort & filters",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = preserveFilters, onCheckedChange = onSetPreserve)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * In-sheet facet value picker (iOS `FacetValuePicker`): back row, a Clear
 * action when anything is selected, and a local case-insensitive search
 * field once the vocabulary passes 12 options.
 */
@Composable
private fun FacetValuePicker(
    facet: CatalogFacet,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back to filters",
            )
        }
        Text(
            text = facet.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (selected.isNotEmpty()) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
    if (options.size > 12) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search ${facet.label.lowercase()}") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
    val shown = if (query.isBlank()) {
        options
    } else {
        options.filter { it.second.contains(query.trim(), ignoreCase = true) }
    }
    shown.forEach { (value, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(value) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (value in selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

// iOS phone FilterView chip palette: selected = inverted onSurface bg with
// background-colored text; unselected = surfaceElevated bg with secondary text.
@Composable
private fun filterSheetChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
    selectedLabelColor = MaterialTheme.colorScheme.background,
    containerColor = org.siloserver.silo.android.ui.theme.SiloSurfaceElevated,
    labelColor = if (selected) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    },
)
