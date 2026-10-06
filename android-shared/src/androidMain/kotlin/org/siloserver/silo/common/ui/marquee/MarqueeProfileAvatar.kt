package org.siloserver.silo.common.ui.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.components.isEmojiAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage
import org.siloserver.silo.model.profile.Profile

/**
 * Warm tile colors, one per profile id (DJB2), matching the Apple apps'
 * `ProfileTilePalette` so a profile has the same color everywhere.
 */
object ProfileTilePalette {
    private val colors = listOf(
        Color(red = 0.850f, green = 0.460f, blue = 0.380f), // coral
        Color(red = 0.400f, green = 0.560f, blue = 0.720f), // slate blue
        Color(red = 0.690f, green = 0.540f, blue = 0.400f), // warm tan
        Color(red = 0.460f, green = 0.620f, blue = 0.520f), // sage
        Color(red = 0.780f, green = 0.480f, blue = 0.520f), // dusty rose
        Color(red = 0.560f, green = 0.480f, blue = 0.720f), // lavender
        Color(red = 0.360f, green = 0.580f, blue = 0.620f), // teal
        Color(red = 0.780f, green = 0.640f, blue = 0.380f), // amber
    )

    fun tint(profileId: String): Color {
        var h = 5381UL
        for (byte in profileId.encodeToByteArray()) {
            h = ((h shl 5) + h) + byte.toUByte().toULong()
        }
        return colors[(h % colors.size.toULong()).toInt()]
    }
}

/**
 * One person's round avatar, like the cast rows on detail pages: their
 * image, emoji or initial on their tint, with lock and KIDS badges riding on
 * the circle. Badges and text scale with [size].
 *
 * @param baseSize the size the badge metrics are designed for (92 phone, 110 TV).
 */
@Composable
fun MarqueeProfileAvatar(
    profile: Profile,
    size: Dp,
    modifier: Modifier = Modifier,
    baseSize: Dp = 92.dp,
    showsBadges: Boolean = true,
) {
    val scale = size / baseSize
    val tint = ProfileTilePalette.tint(profile.id)
    val avatar = profile.avatarRef()
    val image = rememberProfileAvatarImage(avatar)
    Box(modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(lerp(tint, Color.White, 0.35f), tint, lerp(tint, Color.Black, 0.45f)),
                            center = Offset(this.size.width * 0.3f, this.size.height * 0.25f),
                            radius = this.size.width * 0.8f,
                        ),
                    )
                }
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> ThumbhashImage(
                    url = image.url,
                    thumbhash = null,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    transparent = true,
                    cacheKey = image.cacheKey,
                    onError = image.onLoadFailed,
                )
                isEmojiAvatar(avatar) -> BasicText(
                    text = avatar.avatar.orEmpty().trim(),
                    style = TextStyle(fontSize = (46f * scale).sp),
                )
                else -> BasicText(
                    text = profileAvatarDisplayText(avatar = avatar, name = profile.name),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = (38f * scale).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        if (showsBadges && profile.hasPin) {
            val lock = 30.dp * scale
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = lock * 0.08f, y = lock * 0.08f)
                    .size(lock)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1E))
                    .border(lock * 0.07f, Color.Black, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                LockGlyph(lock * 0.42f)
            }
        }
        if (showsBadges && profile.isChild) {
            val font = 11f * scale
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (font * 0.5f).dp, y = (-font * 0.2f).dp)
                    .height((font * 2f).dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(horizontal = (font * 0.75f).dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "KIDS",
                    style = TextStyle(color = Color.Black, fontSize = font.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp),
                )
            }
        }
    }
}

/** A small padlock drawn with shapes, so this module needs no icon set. */
@Composable
private fun LockGlyph(size: Dp) {
    val ink = MarqueeColors.Ink.copy(alpha = 0.75f)
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val w = this.size.width
                val h = this.size.height
                val stroke = w * 0.16f
                // Shackle.
                drawArc(
                    color = ink,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.24f, h * 0.04f),
                    size = androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.6f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawRect(ink, topLeft = Offset(w * 0.24f, h * 0.34f), size = androidx.compose.ui.geometry.Size(stroke, h * 0.12f))
                drawRect(ink, topLeft = Offset(w * 0.76f - stroke, h * 0.34f), size = androidx.compose.ui.geometry.Size(stroke, h * 0.12f))
                // Body.
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(w * 0.1f, h * 0.44f),
                    size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
                )
            },
    )
}
