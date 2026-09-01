package org.siloserver.silo.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.tv.data.preferences.TvHomeSectionPreferences
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.screens.home.normalizeTvHomeSections
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.viewmodel.HomeViewModel

/** Full-screen, remote-first Home row editor matching the tvOS settings flow. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvHomeSectionsEditor(
    onDismiss: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
    preferences: TvHomeSectionPreferences = koinInject(),
) {
    val homeState by viewModel.uiState.collectAsState()
    val preferenceState by preferences.state.collectAsState()
    val sections = remember(homeState.sections) {
        homeState.sections.normalizeTvHomeSections()
    }
    val arrangedSections = remember(sections, preferenceState.layout) {
        TvHomeSectionPreferences.arrange(
            sections = sections,
            layout = preferenceState.layout,
            includingHidden = true,
        )
    }
    val rowFocusRequesters = remember(sections.map { it.id }) {
        sections.associate { section ->
            section.id to HomeSectionRowFocusRequesters(
                moveUp = FocusRequester(),
                moveDown = FocusRequester(),
            )
        }
    }
    val doneFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var editorHasFocus by remember { mutableStateOf(false) }
    var focusedMoveControl by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var isClosing by remember { mutableStateOf(false) }

    fun close() {
        if (isClosing) return
        isClosing = true
        onDismiss()
    }

    fun move(section: ResolvedSection, offset: Int) {
        val ids = arrangedSections.map { it.id }.toMutableList()
        val index = ids.indexOf(section.id)
        val target = index + offset
        if (index < 0 || target !in ids.indices) return
        ids[index] = ids[target]
        ids[target] = section.id

        scope.launch {
            preferences.setOrder(ids)
            val requesters = rowFocusRequesters[section.id] ?: return@launch
            val (requester, direction) = when (target) {
                0 -> requesters.moveDown to 1
                ids.lastIndex -> requesters.moveUp to -1
                else -> if (offset < 0) requesters.moveUp to -1 else requesters.moveDown to 1
            }
            requestFocusUntilObserved(
                maxAttempts = TvFrameRelocationMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = requester::requestFocus,
                isFocused = { focusedMoveControl == (section.id to direction) },
            )
        }
    }

    BackHandler(onBack = ::close)

    LaunchedEffect(preferences, viewModel) {
        preferences.refresh()
        // The shell normally already owns populated Home state. A direct
        // Settings entry can still arrive before that first load completes,
        // so explicitly retry only while there is nothing useful to edit.
        if (homeState.sections.isEmpty()) viewModel.loadSections()
    }

    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = doneFocusRequester::requestFocus,
            isFocused = { editorHasFocus },
        )
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { editorHasFocus = it.hasFocus }
                .background(SettingsBackground),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 36.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Home Sections",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = "HOME SECTIONS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HomeSectionsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Home Sections",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 15.sp,
                                    lineHeight = 18.sp,
                                ),
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                            Text(
                                text = if (isEditing) {
                                    "Move rows into your preferred order."
                                } else {
                                    "Choose which rows appear on Home."
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 17.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HomeSectionsControlButton(
                            label = if (isEditing) "Done Editing" else "Edit",
                            onClick = { isEditing = !isEditing },
                            enabled = arrangedSections.size >= 2,
                        )
                        HomeSectionsControlButton(
                            label = "Done",
                            onClick = ::close,
                            focusRequester = doneFocusRequester,
                        )
                    }
                }

                when {
                    arrangedSections.isEmpty() && homeState.isLoading -> {
                        HomeSectionsMessage("Loading Home sections…")
                    }
                    arrangedSections.isEmpty() && homeState.error != null -> {
                        HomeSectionsMessage(
                            "Silo couldn’t refresh the Home rows. Try again when the server is reachable.",
                        )
                    }
                    arrangedSections.isEmpty() -> {
                        HomeSectionsMessage("Home has no populated rows to arrange yet.")
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            items(arrangedSections, key = { it.id }) { section ->
                                val visible = section.id !in preferenceState.layout.hiddenSectionIds
                                val index = arrangedSections.indexOfFirst { it.id == section.id }
                                HomeSectionEditorRow(
                                    section = section,
                                    visible = visible,
                                    editing = isEditing,
                                    canMoveUp = index > 0,
                                    canMoveDown = index in 0 until arrangedSections.lastIndex,
                                    focusRequesters = rowFocusRequesters.getValue(section.id),
                                    onMoveFocusChanged = { direction, focused ->
                                        val control = section.id to direction
                                        when {
                                            focused -> focusedMoveControl = control
                                            focusedMoveControl == control -> focusedMoveControl = null
                                        }
                                    },
                                    onMoveUp = { move(section, -1) },
                                    onMoveDown = { move(section, 1) },
                                    onToggleVisibility = {
                                        scope.launch {
                                            preferences.setVisible(section.id, visible = !visible)
                                        }
                                    },
                                )
                            }
                        }
                        Text(
                            text = if (isEditing) {
                                "Use the arrow buttons to move rows. The new order saves immediately."
                            } else {
                                "Open eye: visible on Home. Closed eye: hidden. Hidden rows leave no gap."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class HomeSectionRowFocusRequesters(
    val moveUp: FocusRequester,
    val moveDown: FocusRequester,
)

@Composable
private fun HomeSectionsCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape),
    ) {
        content()
    }
}

@Composable
private fun HomeSectionsMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeSectionEditorRow(
    section: ResolvedSection,
    visible: Boolean,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    focusRequesters: HomeSectionRowFocusRequesters,
    onMoveFocusChanged: (direction: Int, focused: Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
) {
    HomeSectionsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (visible) 1f else 0.42f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                    ),
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${section.items.size} item${if (section.items.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (editing) {
                HomeSectionsControlButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move ${section.title} earlier",
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    compact = true,
                    focusRequester = focusRequesters.moveUp,
                    onFocusChanged = { onMoveFocusChanged(-1, it) },
                )
                HomeSectionsControlButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move ${section.title} later",
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    compact = true,
                    focusRequester = focusRequesters.moveDown,
                    onFocusChanged = { onMoveFocusChanged(1, it) },
                )
            }
            HomeSectionsControlButton(
                icon = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (visible) {
                    "Hide ${section.title}"
                } else {
                    "Show ${section.title}"
                },
                onClick = onToggleVisibility,
                compact = true,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeSectionsControlButton(
    onClick: () -> Unit,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.09f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
            disabledContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContentColor = Color.White.copy(alpha = 0.28f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = shape),
            focusedBorder = Border.None,
            pressedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(onFocusChanged?.let { callback ->
                Modifier.onFocusChanged { callback(it.isFocused) }
            } ?: Modifier)
            .then(
                if (compact) {
                    Modifier.size(38.dp)
                } else {
                    // A label control is wrap-content horizontally. Its child
                    // must not use fillMaxSize(): this button sits in a Row
                    // whose main axis is intentionally unbounded while its
                    // children are measured, so fillMaxSize made the first
                    // "Edit" control consume the entire editor panel and
                    // pushed Done + every section row off-screen.
                    Modifier
                        .height(38.dp)
                        .widthIn(min = 68.dp)
                }
            ),
    ) {
        if (compact) {
            // A fixed square needs a fixed centering box. A wrap-content Row
            // only centred inside its own measured width, which left the eye
            // and reorder arrows visibly offset within the 38dp surface.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    // Mirror the Surface's minimum width inside its content.
                    // Surface does not centre a wrap-content child when its
                    // own width is enlarged by widthIn(), so Edit/Done were
                    // left-biased inside their focus boxes.
                    .widthIn(min = 68.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
