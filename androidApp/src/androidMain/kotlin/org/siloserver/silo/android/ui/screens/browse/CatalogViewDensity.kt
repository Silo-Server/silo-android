package org.siloserver.silo.android.ui.screens.browse

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR

enum class CatalogViewDensity(
    @StringRes val labelRes: Int,
    val minCardWidth: Dp,
) {
    Comfortable(CommonR.string.library_density_comfortable, 128.dp),
    Normal(CommonR.string.library_density_normal, 104.dp),
    Compact(CommonR.string.library_density_compact, 92.dp),
}
