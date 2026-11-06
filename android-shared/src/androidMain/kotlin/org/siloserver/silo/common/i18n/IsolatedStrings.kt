package org.siloserver.silo.common.i18n

import android.content.Context
import android.content.res.Resources
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
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
fun stringResourceWithNames(@StringRes id: Int, vararg names: String): String =
    stringResource(id, *isolated(names))

/**
 * [pluralStringResource] for a count message whose placeholders are
 * `{count}`, `{count_text}` (the count as digits), then titles or names,
 * which are isolated as in [stringResourceWithNames].
 */
@Composable
fun pluralStringResourceWithNames(@PluralsRes id: Int, count: Int, vararg names: String): String =
    pluralStringResource(id, count, count, count.toString(), *isolated(names))

@Composable
private fun isolated(names: Array<out String>): Array<String> =
    isolated(names, rtl = LocalLayoutDirection.current == LayoutDirection.Rtl)

private fun isolated(names: Array<out String>, rtl: Boolean): Array<String> =
    names.map { if (rtl) "\u2068$it\u2069" else it }.toTypedArray()

/** [stringResourceWithNames] outside composition, for helpers that take [Resources]. */
fun Resources.getStringWithNames(@StringRes id: Int, vararg names: String): String =
    getString(id, *isolated(names, rtl = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL))

/**
 * [Context.getString] for a message whose arguments are titles or names, for
 * text built outside composition (a toast). Isolates them as
 * [stringResourceWithNames] does, in a right-to-left interface only.
 */
fun Context.getStringWithNames(@StringRes id: Int, vararg names: String): String =
    resources.getStringWithNames(id, *names)
