package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.util.LanguageNames
import org.siloserver.silo.model.subtitles.SubtitleResult

// Web-parity palette (SubtitleSearchModal.tsx providerInfo / scoreColor).
private val ScoreGreen = Color(0xFF22C55E)
private val ScoreAmber = Color(0xFFEAB308)
private val ScoreRed = Color(0xFFEF4444)

private data class ProviderBadge(val abbr: String, val color: Color)

private val ProviderBadges = mapOf(
    "opensubtitles" to ProviderBadge("OS", Color(0xFFEAB308)),
    "subdl" to ProviderBadge("SDL", Color(0xFF3B82F6)),
    "subsource" to ProviderBadge("SS", Color(0xFFEF4444)),
)

private fun scoreBadgeColor(score: Double): Color = when (scoreBadgeBucket(score.toInt())) {
    ScoreBadgeBucket.High -> ScoreGreen
    ScoreBadgeBucket.Medium -> ScoreAmber
    ScoreBadgeBucket.Low -> ScoreRed
}

/**
 * Provider subtitle search sheet, mirroring the web's SubtitleSearchModal:
 * language dropdown → Search → tappable result rows (score badge, release
 * name, provider/HI badges, downloads count) with inline download progress.
 * On a successful download the ViewModel merges + auto-selects the track and
 * flips downloadCompleted, which dismisses this sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSearchSheet(
    tools: PlayerViewModel.SubtitleToolsUiState,
    defaultLanguage: String,
    onSearch: (String) -> Unit,
    onDownload: (SubtitleResult) -> Unit,
    onDismiss: () -> Unit,
    // Tracks-submenu back affordance: closes this sheet and reopens the parent
    // TracksSheet (wired in PlayerOverlay). Null falls back to a plain dismiss.
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    var selectedLanguage by remember { mutableStateOf(defaultLanguage) }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    // On successful download return to the tracks sheet (via onBack) so the
    // freshly added track is visible, rather than dropping onto bare player
    // controls. One-shot flag in VM.
    LaunchedEffect(tools.downloadCompleted) {
        if (tools.downloadCompleted) (onBack ?: onDismiss)()
    }

    PlayerModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Cap below the top edge + keep content flings from
                // dismissing the sheet — see PlayerSheetSupport.
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F2937).copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        ) {
            PlayerSheetHeader(
                title = "Search Subtitles",
                onBack = onBack,
                onDismiss = onDismiss,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .clickable { languageMenuExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = LanguageNames.displayName(selectedLanguage),
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Choose language",
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    DropdownMenu(
                        expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        LanguageNames.dropdownOptions.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedLanguage = code
                                    languageMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = { onSearch(selectedLanguage) },
                    enabled = !tools.searchLoading,
                ) {
                    if (tools.searchLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("Search")
                    }
                }
            }

            tools.searchError?.let { error ->
                Text(
                    text = error,
                    color = ScoreRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            tools.searchWarnings.forEach { warning ->
                Text(
                    text = warning,
                    color = ScoreAmber,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            if (tools.searchAttempted && !tools.searchLoading &&
                tools.searchError == null && tools.searchResults.isEmpty()
            ) {
                Text(
                    text = "No subtitles found.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (tabletopPaneHeight == null) {
                            Modifier.heightIn(max = 420.dp)
                        } else {
                            Modifier.weight(1f)
                        },
                    ),
            ) {
                items(tools.searchResults, key = { "${it.provider}:${it.id}" }) { result ->
                    val key = "${result.provider}:${result.id}"
                    SubtitleResultRow(
                        result = result,
                        isDownloading = tools.downloadingKey == key,
                        enabled = tools.downloadingKey == null,
                        onClick = { onDownload(result) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SubtitleResultRow(
    result: SubtitleResult,
    isDownloading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !isDownloading, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val scoreColor = scoreBadgeColor(result.score)
        Box(
            modifier = Modifier
                .background(scoreColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = result.score.toInt().toString(),
                color = scoreColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.releaseName,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val badge = ProviderBadges[result.provider]
                    ?: ProviderBadge(result.provider.take(3).uppercase(), Color.Gray)
                SubtitleBadgeText(text = badge.abbr, color = badge.color)
                Text(
                    text = LanguageNames.displayName(result.language),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
                if (result.hearingImpaired) {
                    SubtitleBadgeText(text = "HI", color = Color.White.copy(alpha = 0.7f))
                }
                Text(
                    text = "${result.downloads} downloads",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                )
            }
        }
        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun SubtitleBadgeText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
