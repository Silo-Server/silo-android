package org.siloserver.silo.common.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.siloserver.silo.network.ApiResult

/** [UiText] for a catalog string the app names by resource id. */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** [UiText]'s text in composition; it follows an in-place language change. */
@Composable
fun UiText.text(): String = resolve(localizedResources(), rtl = LocalLayoutDirection.current == LayoutDirection.Rtl)

/** [text] outside composition, such as for a toast. */
fun UiText.text(context: Context): String = text(context.resources)

/** [text] for helpers and tests that hold a plain [Resources]. */
fun UiText.text(resources: Resources): String =
    resolve(resources, rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)

private fun UiText.resolve(resources: Resources, rtl: Boolean): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> resources.format(id, args, rtl)
    is UiText.Key -> resources.format(key.resId, args, rtl)
    is UiText.Plural -> resources.getQuantityString(key.resId, count, count, count.toString(), *isolated(names.toTypedArray(), rtl))
}

/** A message whose String and [UiText] arguments are isolated in a right-to-left interface. */
private fun Resources.format(@StringRes id: Int, args: List<Any>, rtl: Boolean): String {
    if (args.isEmpty()) return getString(id)
    val values = args.map { if (it is UiText) it.resolve(this, rtl) else it }
    return if (args.all { it is String || it is UiText }) {
        getString(id, *isolated(values.map { it as String }.toTypedArray(), rtl))
    } else {
        getString(id, *values.toTypedArray())
    }
}

/** [errorText] with a resource id as the fallback. */
fun ApiResult<*>.errorText(@StringRes fallback: Int): UiText = errorText(UiText.Res(fallback))


/**
 * The app's resources, for catalog text built outside `stringResource` (helpers
 * that unit tests call with a plain `Resources`). Reading [LocalConfiguration]
 * recomposes the caller when the language changes in place, as `stringResource`
 * does. `LocalResources`, which lint suggests, isn't in this Compose version.
 */
@SuppressLint("LocalContextResourcesRead")
@Composable
@ReadOnlyComposable
fun localizedResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/**
 * Uppercases interface text with the interface language's rules (Turkish
 * dotted I, for example), not the root locale's.
 */
@Composable
@ReadOnlyComposable
fun String.uppercaseForUi(): String = uppercase(LocalConfiguration.current.locales[0])

/** Lowercases interface text with the interface language's rules. */
@Composable
@ReadOnlyComposable
fun String.lowercaseForUi(): String = lowercase(LocalConfiguration.current.locales[0])
