package org.siloserver.silo.android.ui.screens.reader

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import org.siloserver.silo.common.ebook.ReaderCapabilities
import org.siloserver.silo.common.ebook.ReaderDisplaySettings
import org.siloserver.silo.common.ebook.ReaderEngineKind
import org.siloserver.silo.common.ebook.ReaderFontFamily
import org.siloserver.silo.common.ebook.ReaderSection
import org.siloserver.silo.common.ebook.ReaderShellEvent
import org.siloserver.silo.common.ebook.ReaderShellUiState
import org.siloserver.silo.common.ebook.ReaderSheet
import org.siloserver.silo.common.ebook.ReaderTheme
import org.siloserver.silo.common.ebook.reduceReaderShellState
import org.siloserver.silo.model.ebook.EbookAnnotation
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderShell(
    state: ReaderUiState,
    onBackClick: () -> Unit,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (EbookAnnotation) -> Unit,
    onJumpToBookmark: (EbookAnnotation) -> Unit,
    onJumpToSection: (ReaderSection) -> Unit,
    onSettingsChange: (ReaderDisplaySettings) -> Unit,
    content: @Composable (onToggleChrome: () -> Unit) -> Unit,
) {
    var shellState by remember { mutableStateOf(ReaderShellUiState()) }
    val supportsSettings = state.capabilities.supportsTextSize ||
        state.capabilities.supportsMargins ||
        state.capabilities.supportsTheme
    val systemDark = isSystemInDarkTheme()
    val readerBackground = state.displaySettings.readerSystemBarBackground(systemDark)
    ReaderSystemBarEffect(
        settings = state.displaySettings,
        systemDark = systemDark,
    )

    fun send(event: ReaderShellEvent) {
        shellState = reduceReaderShellState(shellState, event)
    }
    val onToggleChrome = { send(ReaderShellEvent.ToggleChrome) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBackground),
    ) {
        ReaderContentFrame {
            content(onToggleChrome)
        }

        if (shellState.chromeVisible) {
            ReaderTopChrome(
                state = state,
                supportsSettings = supportsSettings,
                onBackClick = onBackClick,
                onBookmarksClick = { send(ReaderShellEvent.OpenSheet(ReaderSheet.Bookmarks)) },
                onSectionsClick = { send(ReaderShellEvent.OpenSheet(ReaderSheet.Sections)) },
                onSettingsClick = { send(ReaderShellEvent.OpenSheet(ReaderSheet.Settings)) },
                onAddBookmark = onAddBookmark,
            )
            ReaderBottomChrome(state = state)
        }
    }

    when (shellState.activeSheet) {
        ReaderSheet.Bookmarks -> BookmarkSheet(
            bookmarks = state.bookmarks,
            onJumpTo = {
                onJumpToBookmark(it)
                send(ReaderShellEvent.DismissSheet)
            },
            onDelete = onDeleteBookmark,
            onDismiss = { send(ReaderShellEvent.DismissSheet) },
        )
        ReaderSheet.Sections -> SectionsSheet(
            sections = state.sections,
            onJumpTo = {
                onJumpToSection(it)
                send(ReaderShellEvent.DismissSheet)
            },
            onDismiss = { send(ReaderShellEvent.DismissSheet) },
        )
        ReaderSheet.Settings -> ReaderSettingsSheet(
            settings = state.displaySettings,
            capabilities = state.capabilities,
            onSettingsChange = onSettingsChange,
            onDismiss = { send(ReaderShellEvent.DismissSheet) },
        )
        ReaderSheet.None,
        ReaderSheet.More -> Unit
    }
}

@Composable
private fun ReaderSystemBarEffect(
    settings: ReaderDisplaySettings,
    systemDark: Boolean,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val barBackground = settings.readerSystemBarBackground(systemDark).toArgb()
    val useDarkIcons = settings.readerSystemBarsUseDarkIcons(systemDark)

    DisposableEffect(activity, barBackground, useDarkIcons) {
        if (activity == null) {
            onDispose { }
        } else {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val originalStatusBarColor = window.statusBarColor
            val originalNavigationBarColor = window.navigationBarColor
            val originalLightStatusBars = controller.isAppearanceLightStatusBars
            val originalLightNavigationBars = controller.isAppearanceLightNavigationBars

            window.statusBarColor = barBackground
            window.navigationBarColor = barBackground
            controller.isAppearanceLightStatusBars = useDarkIcons
            controller.isAppearanceLightNavigationBars = useDarkIcons

            onDispose {
                window.statusBarColor = originalStatusBarColor
                window.navigationBarColor = originalNavigationBarColor
                controller.isAppearanceLightStatusBars = originalLightStatusBars
                controller.isAppearanceLightNavigationBars = originalLightNavigationBars
            }
        }
    }
}

@Composable
private fun ReaderContentFrame(
    content: @Composable () -> Unit,
) {
    val statusPadding = WindowInsets.statusBars.asPaddingValues()
    val navigationPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = statusPadding.calculateTopPadding(),
                bottom = navigationPadding.calculateBottomPadding(),
            ),
    ) {
        content()
    }
}

@Composable
private fun ReaderTopChrome(
    state: ReaderUiState,
    supportsSettings: Boolean,
    onBackClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onSectionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAddBookmark: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val surfaceColor = state.displaySettings.readerChromeSurface(systemDark)
    val contentColor = state.displaySettings.readerChromeText(systemDark)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        FloatingReaderChromeContainer(
            surfaceColor = surfaceColor,
            contentColor = contentColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                }
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onBookmarksClick, enabled = state.capabilities.supportsBookmarks) {
                    Icon(Icons.Default.Bookmarks, contentDescription = stringResource(CommonR.string.reader_bookmarks_title))
                }
                IconButton(
                    onClick = onSectionsClick,
                    enabled = state.capabilities.supportsSections && state.sections.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(CommonR.string.reader_sections_title))
                }
                IconButton(onClick = onSettingsClick, enabled = supportsSettings) {
                    Icon(Icons.Default.Tune, contentDescription = stringResource(CommonR.string.reader_settings_title))
                }
                IconButton(
                    onClick = onAddBookmark,
                    enabled = state.fileId != null && state.capabilities.supportsBookmarks,
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(CommonR.string.reader_bookmarks_add))
                }
            }
        }
    }
}

@Composable
private fun FloatingReaderChromeContainer(
    surfaceColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color.Black.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.28f),
                )
                .clip(RoundedCornerShape(28.dp))
                .background(surfaceColor)
                .consumeChromeTouches(),
        ) {
            content()
        }
    }
}

@Composable
private fun BoxScope.ReaderBottomChrome(state: ReaderUiState) {
    val progressPercent = (state.progressPercent * 100).toInt().toString()
    val currentPage = state.currentPage + 1
    val progressLabel = readerBottomChromeLabel(
        state = state,
        pageProgressLabel = state.pageCount
            ?.let { stringResource(CommonR.string.reader_progress_pageOf, progressPercent, currentPage, it) }
            ?: stringResource(CommonR.string.reader_progress_page, progressPercent, currentPage),
        externalReaderLabel = stringResource(CommonR.string.reader_progress_externalReader),
    )
    val syncError = state.syncError
    val systemDark = isSystemInDarkTheme()
    val surfaceColor = state.displaySettings.readerChromeSurface(systemDark)
    val contentColor = state.displaySettings.readerChromeText(systemDark)

    FloatingReaderChromeContainer(
        surfaceColor = surfaceColor,
        contentColor = contentColor,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            if (progressLabel != null) {
                Text(
                    text = progressLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = 0.72f),
                )
            }
            if (syncError != null) {
                Text(
                    text = stringResource(syncError),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (state.isSyncing) {
                Text(
                    text = stringResource(CommonR.string.reader_progress_syncing),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/**
 * Bottom chrome progress line. The composable caller resolves the
 * translated page line ("12% - Page 3 of 40") and the external-reader
 * fallback; the defaults keep this callable from plain JVM tests.
 */
internal fun readerBottomChromeLabel(
    state: ReaderUiState,
    pageProgressLabel: String,
    externalReaderLabel: String,
): String? {
    val progressPercent = (state.progressPercent * 100).toInt()
    return when (state.capabilities.engineKind) {
        ReaderEngineKind.Reflowable -> "$progressPercent%"
        ReaderEngineKind.FixedDocument,
        ReaderEngineKind.ComicManga -> pageProgressLabel
        ReaderEngineKind.External -> state.formatDisplayName.ifBlank { externalReaderLabel }
    }
}

private fun Modifier.consumeChromeTouches() =
    pointerInput(Unit) {
        detectTapGestures(onTap = {})
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(
    bookmarks: List<EbookAnnotation>,
    onJumpTo: (EbookAnnotation) -> Unit,
    onDelete: (EbookAnnotation) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(CommonR.string.reader_bookmarks_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        if (bookmarks.isEmpty()) {
            Text(stringResource(CommonR.string.reader_bookmarks_empty), modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn {
                items(bookmarks, key = { it.id }) { bookmark ->
                    ListItem(
                        headlineContent = { Text(bookmark.location ?: stringResource(CommonR.string.reader_bookmarks_savedFallback)) },
                        supportingContent = { Text(bookmark.createdAt.orEmpty()) },
                        modifier = Modifier.clickable { onJumpTo(bookmark) },
                        trailingContent = {
                            IconButton(onClick = { onDelete(bookmark) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(CommonR.string.reader_bookmarks_delete))
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionsSheet(
    sections: List<ReaderSection>,
    onJumpTo: (ReaderSection) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(CommonR.string.reader_sections_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(sections, key = { it.index }) { section ->
                ListItem(
                    headlineContent = {
                        Text(section.title.ifBlank { stringResource(CommonR.string.reader_sections_numbered, section.index + 1) })
                    },
                    supportingContent = { Text(stringResource(CommonR.string.reader_pageNumber, section.index + 1)) },
                    modifier = Modifier.clickable { onJumpTo(section) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsSheet(
    settings: ReaderDisplaySettings,
    capabilities: ReaderCapabilities,
    onSettingsChange: (ReaderDisplaySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(CommonR.string.reader_settings_title), style = MaterialTheme.typography.titleLarge)
            if (capabilities.supportsTheme) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(CommonR.string.reader_settings_theme), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ReaderTheme.System, ReaderTheme.Light, ReaderTheme.Sepia, ReaderTheme.Dark).forEach { theme ->
                            FilterChip(
                                selected = settings.theme == theme,
                                onClick = { onSettingsChange(settings.copy(theme = theme).normalized()) },
                                label = { Text(theme.displayLabel()) },
                            )
                        }
                    }
                }
            }
            if (capabilities.supportsTextSize) {
                ReaderSettingSlider(
                    label = stringResource(CommonR.string.reader_settings_textSize),
                    valueLabel = settings.textScale.readerPercentLabel(),
                    value = settings.textScale,
                    valueRange = 0.6f..3.0f,
                    onValueChange = { onSettingsChange(settings.copy(textScale = it).normalized()) },
                )
            }
            if (capabilities.supportsMargins) {
                ReaderSettingSlider(
                    label = stringResource(CommonR.string.reader_settings_margins),
                    valueLabel = settings.marginScale.readerPercentLabel(),
                    value = settings.marginScale,
                    valueRange = 0.75f..1.5f,
                    onValueChange = { onSettingsChange(settings.copy(marginScale = it).normalized()) },
                )
            }
            if (capabilities.supportsTextSize) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(CommonR.string.reader_settings_font), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderFontFamily.entries.forEach { family ->
                            FilterChip(
                                selected = settings.fontFamily == family,
                                onClick = { onSettingsChange(settings.copy(fontFamily = family).normalized()) },
                                label = { Text(family.displayLabel()) },
                            )
                        }
                    }
                }
                ReaderSettingSlider(
                    label = stringResource(CommonR.string.reader_settings_lineSpacing),
                    valueLabel = "%.2f".format(settings.lineHeight),
                    value = settings.lineHeight,
                    valueRange = 1.1f..2.2f,
                    onValueChange = { onSettingsChange(settings.copy(lineHeight = it).normalized()) },
                )
            }
        }
    }
}

@Composable
private fun ReaderSettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

@Composable
private fun ReaderTheme.displayLabel(): String = when (this) {
    ReaderTheme.System -> stringResource(CommonR.string.reader_settings_theme_system)
    ReaderTheme.Light -> stringResource(CommonR.string.reader_settings_theme_light)
    ReaderTheme.Sepia -> stringResource(CommonR.string.reader_settings_theme_sepia)
    ReaderTheme.Dark -> stringResource(CommonR.string.reader_settings_theme_dark)
}

@Composable
private fun ReaderFontFamily.displayLabel(): String = when (this) {
    ReaderFontFamily.Serif -> stringResource(CommonR.string.reader_settings_font_serif)
    ReaderFontFamily.SansSerif -> stringResource(CommonR.string.reader_settings_font_sansSerif)
    ReaderFontFamily.Slab -> stringResource(CommonR.string.reader_settings_font_slab)
    ReaderFontFamily.Dyslexic -> stringResource(CommonR.string.reader_settings_font_dyslexic)
}

@Composable
private fun Float.readerPercentLabel(): String {
    val percent = (this * 100).roundToInt()
    return if (percent == 100) stringResource(CommonR.string.reader_settings_normalPercent) else "$percent%"
}

private fun ReaderDisplaySettings.readerSystemBarBackground(systemDark: Boolean): Color =
    when (normalized().theme) {
        ReaderTheme.System -> if (systemDark) READER_DARK_BACKGROUND else READER_LIGHT_BACKGROUND
        ReaderTheme.Light -> READER_LIGHT_BACKGROUND
        ReaderTheme.Sepia -> READER_SEPIA_BACKGROUND
        ReaderTheme.Dark -> READER_DARK_BACKGROUND
    }

private fun ReaderDisplaySettings.readerSystemBarsUseDarkIcons(systemDark: Boolean): Boolean =
    when (normalized().theme) {
        ReaderTheme.System -> !systemDark
        ReaderTheme.Light,
        ReaderTheme.Sepia -> true
        ReaderTheme.Dark -> false
    }

private fun ReaderDisplaySettings.readerChromeSurface(systemDark: Boolean): Color =
    when (normalized().theme) {
        ReaderTheme.System -> if (systemDark) {
            Color(0xE61F1F22)
        } else {
            Color(0xE6FFFDF8)
        }
        ReaderTheme.Light -> Color(0xE6FFFDF8)
        ReaderTheme.Sepia -> Color(0xE8F1E4C9)
        ReaderTheme.Dark -> Color(0xE61F1F22)
    }

private fun ReaderDisplaySettings.readerChromeText(systemDark: Boolean): Color =
    when (normalized().theme) {
        ReaderTheme.System -> if (systemDark) Color.White else Color(0xFF1D1B16)
        ReaderTheme.Light -> Color(0xFF1D1B16)
        ReaderTheme.Sepia -> Color(0xFF2A2014)
        ReaderTheme.Dark -> Color.White
    }

private val READER_LIGHT_BACKGROUND = Color(0xFFFFFBFE)
private val READER_SEPIA_BACKGROUND = Color(0xFFF4ECD8)
private val READER_DARK_BACKGROUND = Color(0xFF1C1B1F)
