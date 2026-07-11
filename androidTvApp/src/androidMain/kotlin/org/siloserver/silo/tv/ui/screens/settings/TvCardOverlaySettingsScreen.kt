package org.siloserver.silo.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
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
import org.siloserver.silo.tv.ui.components.TvCardOverlayScale
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * TV "Card Overlays" settings sub-screen — the Compose-for-TV port of
 * silo-apple `TVCardOverlaySettingsView`. Two-pane, focus-driven:
 *
 *  - LEFT: a large live preview poster that re-renders as the user edits,
 *    a movie/show sample switcher, and the preset chips (with the active
 *    preset's description).
 *  - RIGHT: a vertical list of overlay tiles grouped by category header.
 *    Each tile (status dot | name + description | live badge preview |
 *    position chip | chevron) opens a per-overlay detail panel with big
 *    focusable buttons for Visibility, Position (2×2 grid + rows), Accent
 *    Color, and Icon. A Reset-to-Defaults button sits at the bottom.
 *
 * When the store is disabled (admin kill-switch) the admin-disabled notice
 * shows and editing is disabled. Back dismisses the whole sub-screen
 * (handled by the caller's [onDismiss]); Back inside the detail panel
 * returns to the tile list.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvCardOverlaySettingsScreen(
    store: OverlayPrefsStore,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.hydrateIfNeeded() }

    val enabled by store.enabled.collectAsState()
    val prefs by store.prefs.collectAsState()

    var sampleVariant by remember { mutableStateOf(OverlaySampleVariant.Movie) }
    var detailOverlay by remember { mutableStateOf<OverlayId?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 72.dp,
                    top = TvTopMenuLayout.contentTopInset,
                    end = 72.dp,
                    bottom = Spacing.xxxl,
                ),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            OverlayPreviewPane(
                enabled = enabled,
                prefs = prefs,
                sampleVariant = sampleVariant,
                onSampleVariantChange = { sampleVariant = it },
                onPresetSelected = { preset ->
                    store.setPrefs(prefs.copy(preset = preset))
                },
                modifier = Modifier.width(260.dp),
            )
            OverlayControlsPane(
                enabled = enabled,
                prefs = prefs,
                sampleData = sampleVariant.data,
                hasUserOverride = store.hasUserOverride,
                onTileClick = { detailOverlay = it },
                onReset = { scope.launch { store.resetToDefaults() } },
                modifier = Modifier.weight(1f),
            )
        }
    }

    detailOverlay?.let { id ->
        OverlayDetailPanel(
            overlayId = id,
            prefs = prefs,
            sampleData = sampleVariant.data,
            onMutate = { store.setPrefs(it) },
            onDismiss = { detailOverlay = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Sample variant
// ---------------------------------------------------------------------------

internal enum class OverlaySampleVariant(val label: String) {
    Movie("Movie"),
    Show("Show"),
    ;

    val data: OverlayData
        get() = when (this) {
            Movie -> OverlayData(
                resolution = "2160p",
                hdr = "Dolby Vision",
                audio = "TrueHD Atmos",
                audioChannels = "7.1",
                videoCodec = "H.265",
                container = "MKV",
                aspectRatio = "2.39:1",
                releaseType = "REMUX",
                edition = "Director's Cut",
                multiAudio = true,
                multiSub = true,
                ratingImdb = 8.6,
                ratingTmdb = 8.4,
                ratingRtCritic = 94,
                ratingRtAudience = 91,
                contentRating = "PG-13",
                year = 2014,
                runtime = 169,
                originalLanguage = "en",
                studio = "Warner Bros.",
                showStatus = null,
                imdbTop250 = 17,
                rtCertifiedFresh = true,
            )
            Show -> OverlayData(
                resolution = "1080p",
                hdr = "HDR10",
                audio = "EAC3",
                audioChannels = "5.1",
                videoCodec = "H.264",
                container = "MP4",
                aspectRatio = "16:9",
                releaseType = "WEB-DL",
                edition = null,
                multiAudio = true,
                multiSub = true,
                ratingImdb = 9.2,
                ratingTmdb = 8.9,
                ratingRtCritic = 96,
                ratingRtAudience = 88,
                contentRating = "TV-MA",
                year = 2011,
                runtime = 58,
                originalLanguage = "en",
                network = "HBO",
                showStatus = "ended",
                imdbTop250 = null,
                rtCertifiedFresh = true,
            )
        }
}

// ---------------------------------------------------------------------------
// Left pane: live preview
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayPreviewPane(
    enabled: Boolean,
    prefs: CardOverlayPrefs,
    sampleVariant: OverlaySampleVariant,
    onSampleVariantChange: (OverlaySampleVariant) -> Unit,
    onPresetSelected: (org.siloserver.silo.overlays.PresetId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        OverlayPreviewPoster(
            enabled = enabled,
            prefs = prefs,
            data = sampleVariant.data,
            modifier = Modifier.width(210.dp),
        )

        // Sample variant switcher (movie / show).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OverlaySampleVariant.entries.forEach { variant ->
                OverlayChip(
                    label = variant.label,
                    selected = sampleVariant == variant,
                    onClick = { onSampleVariantChange(variant) },
                )
            }
        }

        // Preset chips + the active preset's description.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Style",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            Text(
                text = prefs.preset.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            LazyColumn(
                modifier = Modifier.heightInChips(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(org.siloserver.silo.overlays.PresetId.entries.toList()) { preset ->
                    OverlayChip(
                        label = preset.label,
                        selected = prefs.preset == preset,
                        onClick = { onPresetSelected(preset) },
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

/** Height cap so the preset chip list stays inside the pane without overscroll. */
private fun Modifier.heightInChips(): Modifier = this.height(150.dp)

@Composable
private fun OverlayPreviewPoster(
    enabled: Boolean,
    prefs: CardOverlayPrefs,
    data: OverlayData,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF4D4D4D),
                        Color(0xFF292929),
                        Color(0xFF101010),
                    ),
                ),
            ),
    ) {
        if (enabled) {
            CardOverlays(
                data = data,
                prefs = prefs,
                variant = CardOverlayVariant.Poster,
                scale = TvCardOverlayScale,
                forceOpaqueBackground = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Right pane: overlay tiles grouped by category
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayControlsPane(
    enabled: Boolean,
    prefs: CardOverlayPrefs,
    sampleData: OverlayData,
    hasUserOverride: Boolean,
    onTileClick: (OverlayId) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!enabled) {
            OverlayDisabledNotice()
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.35f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            OverlayCategory.entries.forEach { category ->
                item(key = "header-${category.raw}") {
                    OverlayCategoryHeader(category)
                }
                items(
                    OverlayRegistry.defs(category),
                    key = { it.id.raw },
                ) { def ->
                    OverlayTile(
                        def = def,
                        config = prefs.items[def.id] ?: def.toDefaultConfig(),
                        prefs = prefs,
                        sampleData = sampleData,
                        enabled = enabled,
                        onClick = { if (enabled) onTileClick(def.id) },
                    )
                }
            }
            item(key = "reset") {
                OverlayResetRow(
                    enabled = enabled && hasUserOverride,
                    onClick = onReset,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayDisabledNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = OverlayRowMaxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Card overlays have been disabled by your server administrator.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun OverlayCategoryHeader(category: OverlayCategory) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = category.description,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayTile(
    def: OverlayDef,
    config: OverlayItemConfig,
    prefs: CardOverlayPrefs,
    sampleData: OverlayData,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = overlayRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = OverlayRowMaxWidth)
            .alpha(if (config.enabled) 1f else 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Status dot.
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (config.enabled) Color(0xFF34C759)
                        else Color.White.copy(alpha = 0.18f),
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = def.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFocused) FocusedContent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = def.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Live badge preview — render the single overlay forced-on.
            OverlayBadgePreview(def = def, prefs = prefs, data = sampleData)
            // Position chip.
            Text(
                text = config.position.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Renders one overlay's live badge by building a tiny prefs document that
 * disables every overlay except [def] (forced enabled) so only this badge
 * shows. Uses the public [CardOverlays] renderer so the accent/icon/preset
 * shape all reflect what the user would actually get.
 */
@Composable
private fun OverlayBadgePreview(
    def: OverlayDef,
    prefs: CardOverlayPrefs,
    data: OverlayData,
) {
    val previewPrefs = remember(def.id, prefs, data) {
        singleOverlayPrefs(def.id, prefs, position = OverlayPosition.TopLeft)
    }
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CardOverlays(
            data = data,
            prefs = previewPrefs,
            variant = CardOverlayVariant.Poster,
            scale = TvCardOverlayScale,
            forceOpaqueBackground = false,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayResetRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = { if (enabled) onClick() },
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = overlayRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = OverlayRowMaxWidth)
            .height(64.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(top = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reset to Defaults",
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isFocused -> FocusedContent
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Per-overlay detail panel (full-screen focus trap; Back returns to list)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayDetailPanel(
    overlayId: OverlayId,
    prefs: CardOverlayPrefs,
    sampleData: OverlayData,
    onMutate: (CardOverlayPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val def = OverlayRegistry.def(overlayId) ?: run {
        onDismiss()
        return
    }
    val config = prefs.items[overlayId] ?: def.toDefaultConfig()

    fun patch(mutate: (OverlayItemConfig) -> OverlayItemConfig) {
        val base = prefs.items[overlayId] ?: def.toDefaultConfig()
        val items = prefs.items.toMutableMap()
        items[overlayId] = mutate(base)
        onMutate(prefs.copy(items = items))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 50.dp,
                top = TvTopMenuLayout.contentTopInset,
                end = 50.dp,
                bottom = Spacing.xxxl,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = def.label,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = def.description,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            // Focused preview: ONLY this overlay enabled.
            item {
                OverlayPreviewPoster(
                    enabled = true,
                    prefs = singleOverlayPrefs(overlayId, prefs, config.position),
                    data = sampleData,
                    modifier = Modifier.width(160.dp),
                )
            }

            // Visibility
            item {
                OverlayDetailSection(title = "Visibility") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OverlayBigButton(
                            label = "On",
                            active = config.enabled,
                            onClick = { patch { it.copy(enabled = true) } },
                        )
                        OverlayBigButton(
                            label = "Off",
                            active = !config.enabled,
                            onClick = { patch { it.copy(enabled = false) } },
                        )
                    }
                }
            }

            // Position — 2×2 grid + rows
            item {
                OverlayDetailSection(title = "Position") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(25.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        OverlayPositionGrid(
                            selection = config.position,
                            accent = config.accentColor?.let { tvOverlayColorFromHex(it) }
                                ?: Color.White,
                            width = 110.dp,
                            onSelect = { pos -> patch { it.copy(position = pos) } },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OverlayPosition.entries.forEach { pos ->
                                OverlayBigButton(
                                    label = pos.displayName,
                                    active = config.position == pos,
                                    onClick = { patch { it.copy(position = pos) } },
                                )
                            }
                        }
                    }
                }
            }

            // Accent Color
            item {
                OverlayDetailSection(title = "Accent Color") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 60.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(OverlayAccentPalette.entries, key = { it.hex }) { entry ->
                                OverlayAccentSwatch(
                                    label = entry.label,
                                    hex = entry.hex,
                                    selected = config.accentColor
                                        ?.equals(entry.hex, ignoreCase = true) == true,
                                    onClick = { patch { it.copy(accentColor = entry.hex) } },
                                )
                            }
                        }
                        OverlayBigButton(
                            label = if (def.defaultAccent == null) "No Accent" else "Default",
                            active = config.accentColor == null,
                            onClick = { patch { it.copy(accentColor = null) } },
                        )
                    }
                }
            }

            // Icon (only when icon-capable)
            if (def.iconCapable) {
                item {
                    OverlayDetailSection(title = "Icon") {
                        val presetPrefersIcon = prefs.preset.preferIcon
                        val resolvedShow = config.showIcon ?: presetPrefersIcon
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OverlayBigButton(
                                label = "Show Icon",
                                active = resolvedShow,
                                onClick = {
                                    patch {
                                        it.copy(
                                            showIcon = if (presetPrefersIcon) null else true,
                                        )
                                    }
                                },
                            )
                            OverlayBigButton(
                                label = "Hide Icon",
                                active = !resolvedShow,
                                onClick = {
                                    patch {
                                        it.copy(
                                            showIcon = if (!presetPrefersIcon) null else false,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            def.availabilityNote?.let { note ->
                item {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            item {
                OverlayBigButton(
                    label = "Done",
                    active = true,
                    onClick = onDismiss,
                )
            }
        }
    }
}

/** Whether the preset prefers icons by default — mirrors the renderer preset. */
private val org.siloserver.silo.overlays.PresetId.preferIcon: Boolean
    get() = when (this) {
        org.siloserver.silo.overlays.PresetId.Vibrant,
        org.siloserver.silo.overlays.PresetId.Pill,
        -> true
        else -> false
    }

@Composable
private fun OverlayDetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = 0.6f),
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

// ---------------------------------------------------------------------------
// 2×2 corner position picker (Compose-for-TV port of OverlayPositionGrid)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayPositionGrid(
    selection: OverlayPosition,
    accent: Color,
    width: Dp,
    onSelect: (OverlayPosition) -> Unit,
) {
    val height = width * 1.5f
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        OverlayPosition.entries.forEach { position ->
            OverlayCornerDot(
                selected = selection == position,
                accent = accent,
                onClick = { onSelect(position) },
                modifier = Modifier
                    .align(cornerAlignment(position))
                    .padding(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayCornerDot(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val fill = when {
        selected -> accent
        isFocused -> Color.White.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = fill,
            contentColor = Color.White,
            focusedContainerColor = if (selected) accent else Color.White.copy(alpha = 0.55f),
            focusedContentColor = Color.White,
            pressedContainerColor = if (selected) accent else Color.White.copy(alpha = 0.7f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.3f),
        modifier = modifier.size(34.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}

private fun cornerAlignment(position: OverlayPosition): Alignment =
    when (position) {
        OverlayPosition.TopLeft -> Alignment.TopStart
        OverlayPosition.TopRight -> Alignment.TopEnd
        OverlayPosition.BottomLeft -> Alignment.BottomStart
        OverlayPosition.BottomRight -> Alignment.BottomEnd
    }

// ---------------------------------------------------------------------------
// Shared small primitives
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fillWidth: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = overlayRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = if (fillWidth) Modifier.fillMaxWidth().widthIn(max = 360.dp) else Modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isFocused -> FocusedContent
                selected -> Color.White
                else -> Color.White.copy(alpha = 0.6f)
            },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayBigButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = overlayRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 80.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (active) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isFocused) FocusedContent else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isFocused -> FocusedContent
                    active -> Color.White
                    else -> Color.White.copy(alpha = 0.6f)
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayAccentSwatch(
    label: String,
    hex: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(7.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.10f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.10f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tvOverlayColorFromHex(hex)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected || isFocused) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun overlayRowColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.06f),
    contentColor = Color.White,
    focusedContainerColor = FocusedContainer,
    focusedContentColor = FocusedContent,
    pressedContainerColor = FocusedContainer,
    pressedContentColor = FocusedContent,
)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private const val OverlayRowMaxWidthValue = 480
private val OverlayRowMaxWidth = OverlayRowMaxWidthValue.dp

private fun OverlayDef.toDefaultConfig(): OverlayItemConfig =
    OverlayItemConfig(
        enabled = defaultEnabled,
        position = defaultPosition,
        accentColor = null,
        showIcon = null,
    )

/**
 * Build a standalone prefs document where only [id] is enabled (forced on)
 * and placed at [position], with the user's per-overlay config preserved so
 * the preview reflects accent/icon overrides. Other overlays are disabled.
 */
private fun singleOverlayPrefs(
    id: OverlayId,
    source: CardOverlayPrefs,
    position: OverlayPosition,
): CardOverlayPrefs {
    val base = OverlaySchema.buildDefaults().copy(preset = source.preset)
    val items = base.items.mapValues { (_, cfg) -> cfg.copy(enabled = false) }.toMutableMap()
    val def = OverlayRegistry.def(id)
    val userCfg = source.items[id] ?: def?.toDefaultConfig()
    items[id] = (userCfg ?: OverlayItemConfig(enabled = true, position = position))
        .copy(enabled = true, position = position)
    return base.copy(items = items)
}

/**
 * Parse a 6-digit hex color into a Compose [Color] for the TV settings
 * surface. Mirrors `overlayColorFromHex` in android-shared (which is
 * internal to that module). Falls back to white for malformed input.
 */
private fun tvOverlayColorFromHex(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color.White
    val cleaned = (if (hex.startsWith("#")) hex.substring(1) else hex).trim()
    val expanded = if (cleaned.length == 3) cleaned.map { "$it$it" }.joinToString("") else cleaned
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
