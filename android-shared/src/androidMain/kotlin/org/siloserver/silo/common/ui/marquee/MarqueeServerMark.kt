package org.siloserver.silo.common.ui.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * The server's mark: its branded `mark_url` image when there is one, else its
 * initial on a warm tile, else a plain dark tile.
 */
@Composable
fun MarqueeServerMark(name: String?, imageUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.25f)
    var imageFailed by remember(imageUrl) { mutableStateOf(imageUrl == null) }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.trim()?.firstOrNull()?.uppercase()
        if (initial != null) {
            Box(
                Modifier
                    .size(size)
                    .background(Brush.linearGradient(listOf(Color(0xFFEAA65C), Color(0xFFA8521D), Color(0xFF5E290D)))),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = initial,
                    style = TextStyle(
                        color = Color(0xFFFFF6EA),
                        fontSize = (size.value * 0.54f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                    ),
                )
            }
        } else {
            Box(Modifier.size(size).background(Brush.verticalGradient(listOf(Color(0xFF2B2D33), Color(0xFF15171C)))))
        }
        if (!imageFailed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.size(size),
            )
        }
    }
}
