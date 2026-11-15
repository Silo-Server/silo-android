package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.ui.components.ThumbhashImage

private const val AudiobookCoverArtSizeDp = 280

/**
 * Hero cover + title / author / narrator. Pulled out of the monolith
 * screen; purely presentational, driven by VM ui-state fields.
 */
@Composable
fun AudiobookCoverHeader(
    title: String,
    author: String?,
    narrator: String?,
    coverUrl: String?,
    coverThumbhash: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // iOS oversized artwork: cornerRadius 20, ~36pt side insets, a deep
        // drop shadow. The cover fills the width (capped, matching the
        // PortraitPlayerLayout maxWidth 420 frame) rather than a fixed square.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            ThumbhashImage(
                url = coverUrl,
                thumbhash = coverThumbhash,
                contentDescription = title,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(28.dp, RoundedCornerShape(20.dp), clip = false)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        // iOS titleBlock: cover→title gap 28, line spacing 6.
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
        author?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
        narrator?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResourceWithNames(CommonR.string.audiobook_narratedBy, it),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}
