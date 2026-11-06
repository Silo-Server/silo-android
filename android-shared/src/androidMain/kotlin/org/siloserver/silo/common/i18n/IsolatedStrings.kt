package org.siloserver.silo.common.i18n

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection

/**
 * [stringResource] for a message whose arguments are titles or names. In a
 * right-to-left interface each one is wrapped in Unicode isolates (FSI…PDI),
 * so a left-to-right name keeps the sentence's punctuation and word order.
 * Left-to-right output keeps its exact bytes. `BidiFormatter.unicodeWrap`
 * uses embeddings and marks instead, which leak the name's direction into the
 * text around it.
 */
@Composable
fun stringResourceWithNames(@StringRes id: Int, vararg names: String): String {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return stringResource(id, *names.map { if (rtl) "\u2068$it\u2069" else it }.toTypedArray())
}

/**
 * [Context.getString] for a message whose arguments are titles or names, for
 * text built outside composition (a toast). Isolates them as
 * [stringResourceWithNames] does, in a right-to-left interface only.
 */
fun Context.getStringWithNames(@StringRes id: Int, vararg names: String): String {
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    return getString(id, *names.map { if (rtl) "\u2068$it\u2069" else it }.toTypedArray())
}
