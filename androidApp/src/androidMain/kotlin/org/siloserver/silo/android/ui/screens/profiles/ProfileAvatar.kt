package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.common.ui.components.ProfileAvatarRef
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.isEmojiAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage

/**
 * Pre-defined avatar options using the server's supported preset vocabulary.
 */
object AvatarOptions {
    val presets = listOf(
        "preset:dicebear:fun-emoji:cosmic-otter",
        "preset:dicebear:fun-emoji:comet-cat",
        "preset:dicebear:fun-emoji:star-bear",
        "preset:dicebear:fun-emoji:neon-pup",
        "preset:dicebear:fun-emoji:orbit-bunny",
        "preset:dicebear:fun-emoji:solar-owl",
        "preset:dicebear:fun-emoji:nova-gecko",
        "preset:dicebear:fun-emoji:pixel-penguin",
        "preset:dicebear:fun-emoji:mango-fox",
        "preset:dicebear:fun-emoji:bubble-lion",
        "preset:dicebear:fun-emoji:marble-panda",
        "preset:dicebear:fun-emoji:starlight-tiger",
        "preset:dicebear:fun-emoji:candy-dragon",
        "preset:dicebear:fun-emoji:ember-parrot",
        "preset:dicebear:fun-emoji:mochi-robot",
        "preset:dicebear:fun-emoji:twinkle-sprite",
        "preset:dicebear:fun-emoji:mint-puffin",
        "preset:dicebear:fun-emoji:velvet-panther",
        "preset:dicebear:fun-emoji:sunbeam-falcon",
        "preset:dicebear:fun-emoji:lunar-meteor",
    )

    /** Deterministic colour for a given avatar string so the same profile always gets the same colour. */
    fun colorForAvatar(avatar: String): Color {
        val palette = listOf(
            Color(0xFF544F47),
            Color(0xFF6C6257),
            Color(0xFF7D7062),
            Color(0xFF4A504C),
            Color(0xFF655B50),
            Color(0xFF505862),
            Color(0xFF776A5C),
            Color(0xFF4B4C55),
        )
        val index = avatar.hashCode().let { if (it < 0) -it else it } % palette.size
        return palette[index]
    }
}

/**
 * Displays a profile avatar as an image, emoji, or initials inside a coloured circle.
 *
 * @param avatar Avatar ref + server-resolved URL for the profile.
 * @param name Profile name, used for initials fallback.
 * @param size Circle diameter.
 * @param selected Whether to show a highlight border.
 * @param onClick Optional click handler.
 */
@Composable
fun ProfileAvatar(
    avatar: ProfileAvatarRef,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val displayText = profileAvatarDisplayText(avatar = avatar, name = name)
    // iOS ProfileAvatarView uses a single flat siloSurfaceVariant (#0E0F12).
    val bgColor = Color(0xFF0E0F12)
    val avatarImage = rememberProfileAvatarImage(avatar)

    val borderModifier = if (selected) {
        Modifier.border(3.dp, AuthColors.Primary, CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .then(borderModifier)
            .clip(CircleShape)
            .background(bgColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarImage != null) {
            ThumbhashImage(
                url = avatarImage.url,
                thumbhash = null,
                contentDescription = "$name avatar",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                transparent = true,
                cacheKey = avatarImage.cacheKey,
                onError = avatarImage.onLoadFailed,
            )
        } else {
            // iOS: emoji at size*0.45; initials at size*0.34 semibold, onSurface.
            if (isEmojiAvatar(avatar)) {
                Text(
                    text = displayText,
                    fontSize = (size.value * 0.45).sp,
                )
            } else {
                Text(
                    text = displayText,
                    fontSize = (size.value * 0.34).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuthColors.OnSurface,
                )
            }
        }
    }
}

/**
 * Smaller selectable server-backed avatar used in the create/edit picker grid.
 */
@Composable
fun AvatarPickerItem(
    avatarRef: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProfileAvatar(
        // Picker entries are always preset refs, never uploads, so there is no
        // server URL to carry alongside them.
        avatar = ProfileAvatarRef(avatarRef),
        name = "Profile avatar",
        modifier = modifier,
        size = 40.dp,
        selected = isSelected,
        onClick = onClick,
    )
}
