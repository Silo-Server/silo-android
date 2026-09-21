package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.components.skeleton
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.util.rememberDominantColor

/** Uses the detail hero's artwork frame while metadata and actions load. */
@Composable
internal fun DetailLoadingSkeleton(
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    artworkThumbhash: String? = null,
) {
    val shimmer = rememberShimmerProgress()
    val tint by rememberDominantColor(artworkUrl, SiloBackground, artworkThumbhash)
    DetailPageSurface(artworkUrl, artworkThumbhash, tint, modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Loading details" },
        ) {
            if (maxWidth >= ExpandedDetailBreakpoint) {
                val horizontalPadding = expandedDetailHorizontalPadding(maxWidth)
                val posterWidth = expandedDetailPosterWidth(maxWidth)
                val pageSurface = lerp(Color.Black, tint, 0.42f)
                Column(Modifier.fillMaxWidth().background(pageSurface)) {
                    ExpandedDetailHeroBackdrop(artworkUrl, artworkThumbhash, pageSurface) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = horizontalPadding).padding(top = 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            Box(Modifier.width(posterWidth).aspectRatio(2f / 3f)
                                .skeleton(shimmer, RoundedCornerShape(12.dp)))
                            Column(
                                modifier = Modifier.weight(1f).height(posterWidth * 1.5f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                TitlePlaceholder(shimmer)
                                Spacer(Modifier.height(12.dp))
                                MetadataPlaceholder(shimmer)
                                Spacer(Modifier.weight(1f))
                                ActionPlaceholders(shimmer)
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = horizontalPadding)
                            .padding(top = 24.dp, bottom = 40.dp).widthIn(max = 920.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OverviewPlaceholder(shimmer)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    DetailHeroArtwork(artworkUrl, artworkThumbhash) {
                        TitlePlaceholder(shimmer)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SafePadding)
                            .padding(top = 8.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MetadataPlaceholder(shimmer)
                        ActionPlaceholders(shimmer)
                        OverviewPlaceholder(shimmer)
                    }
                }
            }
        }
    }
}

@Composable
private fun TitlePlaceholder(shimmer: Float) {
    Box(Modifier.fillMaxWidth(0.68f).height(38.dp).skeleton(shimmer))
}

@Composable
private fun MetadataPlaceholder(shimmer: Float) {
    Box(Modifier.fillMaxWidth(0.72f).height(20.dp).skeleton(shimmer))
}

@Composable
private fun ActionPlaceholders(shimmer: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(52.dp).skeleton(shimmer, CircleShape))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(4) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(42.dp).skeleton(shimmer, CircleShape))
                    Box(Modifier.width(42.dp).height(12.dp).skeleton(shimmer))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
    }
}

@Composable
private fun OverviewPlaceholder(shimmer: Float) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            Box(Modifier.fillMaxWidth(if (index == 2) 0.7f else 1f).height(14.dp).skeleton(shimmer))
        }
    }
}
