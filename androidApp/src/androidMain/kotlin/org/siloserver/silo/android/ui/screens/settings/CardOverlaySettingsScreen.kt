package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayAccentPalette
import org.siloserver.silo.overlays.OverlayCategory
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDef
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayItemConfig
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.overlays.OverlayRegistry
import org.siloserver.silo.overlays.OverlaySchema
import org.siloserver.silo.overlays.PresetId
import kotlinx.coroutines.launch

/**
 * Phone Card Overlays settings. Android port of Apple's
 * `CardOverlaySettingsView`. Layout principles, in order of importance:
 *
 * 1. Live preview, always visible: the poster card sits at the top so the
 *    user sees every change immediately.
 * 2. Glanceable category navigation: a segmented picker filters the long
 *    overlay list to one category at a time.
 * 3. Per-overlay badge preview: every row shows the actual rendered badge
 *    it would produce, with the user's current preset + accent applied.
 * 4. Inline disclosure: tapping a row expands it to reveal the visual
 *    corner picker + accent swatches + icon toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardOverlaySettingsScreen(
    store: OverlayPrefsStore,
    onBackClick: () -> Unit,
) {
    val enabled by store.enabled.collectAsState()
    val prefs by store.prefs.collectAsState()

    var sampleVariant by remember { mutableStateOf(OverlaySampleVariant.Movie) }
    var category by remember { mutableStateOf(OverlayCategory.Tech) }
    var expandedOverlay by remember { mutableStateOf<OverlayId?>(null) }

    LaunchedEffect(Unit) { store.hydrateIfNeeded() }

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Card overlays",
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
            )
        },
        // Ground only. The twelve bespoke composables below still run on their
        // own card system (14dp radius, translucent `surfaceVariant`) rather
        // than the shared grouped-settings primitives — moving them over is a
        // larger job than this visual pass.
        containerColor = SiloSettingsBackground,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            // --- Sticky-ish live preview pane ---
            item {
                PreviewPane(
                    enabled = enabled,
                    prefs = prefs,
                    sampleVariant = sampleVariant,
                    onSampleVariantChange = { sampleVariant = it },
                    onPresetChange = { newPreset ->
                        store.setPrefs(prefs.copy(preset = newPreset))
                    },
                )
            }

            if (!enabled) {
                item { DisabledBanner() }
            }

            // --- Category picker ---
            item {
                CategoryPicker(
                    selected = category,
                    onSelected = { category = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            item {
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            // --- Overlay rows ---
            items(OverlayRegistry.defs(category)) { def ->
                OverlayRow(
                    def = def,
                    prefs = prefs,
                    sampleData = sampleVariant.data,
                    enabledGlobally = enabled,
                    isExpanded = expandedOverlay == def.id,
                    onToggleExpand = {
                        expandedOverlay = if (expandedOverlay == def.id) null else def.id
                    },
                    onUpdate = { next -> store.setPrefs(next) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // --- Reset footer ---
            item {
                ResetFooter(
                    hasUserOverride = store.hasUserOverride,
                    store = store,
                )
            }
        }
    }
}

// extension to allow items(List) without explicit import collision
private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.items(
    list: List<T>,
    crossinline itemContent: @Composable androidx.compose.foundation.lazy.LazyItemScope.(T) -> Unit,
) = items(count = list.size) { index -> itemContent(list[index]) }

// MARK: - Preview pane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewPane(
    enabled: Boolean,
    prefs: CardOverlayPrefs,
    sampleVariant: OverlaySampleVariant,
    onSampleVariantChange: (OverlaySampleVariant) -> Unit,
    onPresetChange: (PresetId) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewPoster(enabled = enabled, prefs = prefs, sampleData = sampleVariant.data)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SegmentedRow(
                options = OverlaySampleVariant.entries.toList(),
                selected = sampleVariant,
                label = { it.label },
                onSelected = onSampleVariantChange,
            )

            PresetMenu(selected = prefs.preset, onSelected = onPresetChange)

            Text(
                text = prefs.preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun PreviewPoster(
    enabled: Boolean,
    prefs: CardOverlayPrefs,
    sampleData: OverlayData,
) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF525252),
                        Color(0xFF2E2E2E),
                        Color(0xFF141414),
                    ),
                ),
            ),
    ) {
        if (enabled) {
            CardOverlays(
                data = sampleData,
                prefs = prefs,
                variant = CardOverlayVariant.Poster,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetMenu(
    selected: PresetId,
    onSelected: (PresetId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    RoundedCornerShape(8.dp),
                )
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = selected.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PresetId.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        onSelected(preset)
                        expanded = false
                    },
                )
            }
        }
    }
}

// MARK: - Category picker (segmented)

@Composable
private fun CategoryPicker(
    selected: OverlayCategory,
    onSelected: (OverlayCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedRow(
        options = OverlayCategory.entries.toList(),
        selected = selected,
        label = { shortLabel(it) },
        onSelected = onSelected,
        modifier = modifier,
    )
}

private fun shortLabel(category: OverlayCategory): String =
    when (category) {
        OverlayCategory.Tech -> "Tech"
        OverlayCategory.Ratings -> "Ratings"
        OverlayCategory.Metadata -> "Info"
        OverlayCategory.Ribbons -> "Ribbons"
    }

/** A lightweight segmented control matching the iOS `.pickerStyle(.segmented)`. */
@Composable
private fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onSelected(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// MARK: - Disabled banner

@Composable
private fun DisabledBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Overlays disabled by your server administrator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Overlay row

@Composable
private fun OverlayRow(
    def: OverlayDef,
    prefs: CardOverlayPrefs,
    sampleData: OverlayData,
    enabledGlobally: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (CardOverlayPrefs) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = prefs.items[def.id] ?: OverlayItemConfig(
        enabled = def.defaultEnabled,
        position = def.defaultPosition,
        accentColor = null,
        showIcon = null,
    )

    fun write(mutate: (OverlayItemConfig) -> OverlayItemConfig) {
        val items = prefs.items.toMutableMap()
        items[def.id] = mutate(config)
        onUpdate(prefs.copy(items = items))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (isExpanded) 0.55f else 0.35f,
                ),
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                RoundedCornerShape(14.dp),
            )
            .animateContentSize()
            .padding(14.dp),
    ) {
        // Header row: toggle + label/desc + badge preview + chevron
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.enabled,
                onCheckedChange = { newValue -> write { it.copy(enabled = newValue) } },
                enabled = enabledGlobally,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabledGlobally, onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = def.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = def.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                BadgePreview(def = def, prefs = prefs, config = config, sampleData = sampleData)
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (config.enabled) 1f else 0.5f),
            ) {
                Divider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.width(120.dp)) {
                        Text(
                            text = "Position",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OverlayPositionGrid(
                            selection = config.position,
                            onSelect = { pos -> write { it.copy(position = pos) } },
                            accent = config.accentColor?.let { hexToColor(it) }
                                ?: def.defaultAccent?.let { hexToColor(it) }
                                ?: Color.White,
                            width = 72.dp,
                            enabled = config.enabled,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (def.iconCapable) {
                            IconToggle(
                                preset = prefs.preset,
                                showIcon = config.showIcon,
                                enabled = config.enabled,
                                onChange = { resolved, preferIcon ->
                                    write {
                                        it.copy(showIcon = if (resolved == preferIcon) null else resolved)
                                    }
                                },
                            )
                        }
                        AccentPicker(
                            selectedHex = config.accentColor,
                            enabled = config.enabled,
                            onSelect = { hex -> write { it.copy(accentColor = hex) } },
                            onClear = { write { it.copy(accentColor = null) } },
                        )
                    }
                }
                def.availabilityNote?.let { note ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Renders the actual badge this overlay would produce. Builds a standalone
 * single-item prefs document (defaults + this overlay enabled, with its
 * accent/icon override) so the chip reflects THIS overlay only, mirroring
 * Apple's `previewPrefs`.
 */
@Composable
private fun BadgePreview(
    def: OverlayDef,
    prefs: CardOverlayPrefs,
    config: OverlayItemConfig,
    sampleData: OverlayData,
) {
    // Only render if the overlay would resolve a value for the sample data.
    if (def.getValue(sampleData).isNullOrBlank() && def.getIcon?.invoke(sampleData) == null) {
        return
    }
    val previewItem = config.copy(enabled = true, position = OverlayPosition.TopLeft)
    val previewPrefs = OverlaySchema.buildDefaults().copy(
        preset = prefs.preset,
        items = OverlayRegistry.defs(def.category)
            .associate { d ->
                d.id to OverlayItemConfig(
                    enabled = d.id == def.id,
                    position = OverlayPosition.TopLeft,
                    accentColor = if (d.id == def.id) config.accentColor else null,
                    showIcon = if (d.id == def.id) config.showIcon else null,
                )
            }
            .toMutableMap()
            .apply { put(def.id, previewItem) },
    )
    Box(
        modifier = Modifier
            .height(36.dp)
            .width(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CardOverlays(
            data = sampleData,
            prefs = previewPrefs,
            variant = CardOverlayVariant.Poster,
        )
    }
}

@Composable
private fun IconToggle(
    preset: PresetId,
    showIcon: Boolean?,
    enabled: Boolean,
    onChange: (resolved: Boolean, preferIcon: Boolean) -> Unit,
) {
    val preferIcon = presetPrefersIcon(preset)
    val resolved = showIcon ?: preferIcon
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Show icon",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = resolved,
            onCheckedChange = { newValue -> onChange(newValue, preferIcon) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun AccentPicker(
    selectedHex: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Accent",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selectedHex != null) {
                TextButton(onClick = onClear, enabled = enabled) {
                    Text("Default", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayAccentPalette.entries.forEach { entry ->
                val isSelected = selectedHex?.lowercase() == entry.hex.lowercase()
                val scale by animateFloatAsState(if (isSelected) 1.05f else 1f, label = "swatch")
                Box(
                    modifier = Modifier
                        .scale(scale)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(hexToColor(entry.hex))
                        .border(
                            BorderStroke(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f),
                            ),
                            CircleShape,
                        )
                        .clickable(enabled = enabled) { onSelect(entry.hex) },
                )
            }
        }
    }
}

// MARK: - Reset footer

@Composable
private fun ResetFooter(
    hasUserOverride: Boolean,
    store: OverlayPrefsStore,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(
            onClick = { scope.launch { store.resetToDefaults() } },
            enabled = hasUserOverride,
        ) {
            Text(
                text = "Reset to Defaults",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = if (hasUserOverride) {
                "Clears your overrides and falls back to the server's baseline."
            } else {
                "Using the server's baseline overlays."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Position grid (Compose port of OverlayPositionGrid.swift)

@Composable
private fun OverlayPositionGrid(
    selection: OverlayPosition,
    onSelect: (OverlayPosition) -> Unit,
    accent: Color,
    width: Dp,
    enabled: Boolean,
) {
    val height = width * 1.5f
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                RoundedCornerShape(12.dp),
            ),
    ) {
        OverlayPosition.entries.forEach { position ->
            CornerDot(
                position = position,
                selected = selection == position,
                accent = accent,
                enabled = enabled,
                onClick = { onSelect(position) },
                modifier = Modifier.align(alignmentFor(position)),
            )
        }
    }
}

@Composable
private fun CornerDot(
    position: OverlayPosition,
    selected: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) accent else Color.White.copy(alpha = 0.18f))
                .border(
                    BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                    ),
                    CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
        )
    }
}

private fun alignmentFor(position: OverlayPosition): Alignment =
    when (position) {
        OverlayPosition.TopLeft -> Alignment.TopStart
        OverlayPosition.TopRight -> Alignment.TopEnd
        OverlayPosition.BottomLeft -> Alignment.BottomStart
        OverlayPosition.BottomRight -> Alignment.BottomEnd
    }

// MARK: - Helpers

/**
 * Mirrors the per-preset icon preference from the android-shared renderer
 * (`OverlayPresetStyles`), which is internal to that module. Keep in sync.
 */
private fun presetPrefersIcon(preset: PresetId): Boolean =
    when (preset) {
        PresetId.Vibrant, PresetId.Pill -> true
        PresetId.Minimal, PresetId.Classic, PresetId.Square -> false
    }

/** Parses `#rgb`, `#rrggbb`, or `#aarrggbb`; falls back to white on error. */
private fun hexToColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color.White
    val cleaned = (if (hex.startsWith("#")) hex.substring(1) else hex).trim()
    val expanded = if (cleaned.length == 3) {
        buildString { cleaned.forEach { append(it); append(it) } }
    } else {
        cleaned
    }
    return when (expanded.length) {
        6 -> {
            val rgb = expanded.toLongOrNull(16) ?: return Color.White
            Color(0xFF000000.toInt() or (rgb.toInt() and 0x00FFFFFF))
        }
        8 -> {
            val argb = expanded.toLongOrNull(16) ?: return Color.White
            Color(argb.toInt())
        }
        else -> Color.White
    }
}

// MARK: - Sample data

/** A representative sample bag so the preview shows real badges. */
enum class OverlaySampleVariant(val label: String) {
    Movie("Movie"),
    Show("Show"),
    ;

    val data: OverlayData
        get() = when (this) {
            Movie -> OverlayData(
                resolution = "4K",
                hdr = "DV",
                audio = "Atmos",
                audioChannels = "7.1",
                videoCodec = "HEVC",
                container = "MKV",
                aspectRatio = "2.39:1",
                releaseType = "BluRay",
                edition = "Director's Cut",
                multiAudio = true,
                multiSub = true,
                ratingImdb = 8.7,
                ratingTmdb = 8.4,
                ratingRtCritic = 94,
                ratingRtAudience = 91,
                contentRating = "PG-13",
                year = 2024,
                runtime = 142,
                originalLanguage = "EN",
                studio = "Warner Bros.",
                imdbTop250 = 42,
                rtCertifiedFresh = true,
            )
            Show -> OverlayData(
                resolution = "1080p",
                hdr = "HDR10",
                audio = "DD+",
                audioChannels = "5.1",
                videoCodec = "H.264",
                container = "MKV",
                aspectRatio = "16:9",
                releaseType = "WEB-DL",
                multiAudio = true,
                multiSub = true,
                ratingImdb = 9.1,
                ratingTmdb = 8.8,
                ratingRtCritic = 97,
                ratingRtAudience = 89,
                contentRating = "TV-MA",
                year = 2023,
                runtime = 52,
                originalLanguage = "EN",
                network = "HBO",
                showStatus = "Returning",
            )
        }
}
