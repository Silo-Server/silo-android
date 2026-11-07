package org.siloserver.silo.common.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.siloserver.silo.network.ApiResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    is UiText.Plural -> resources.formatPlural(key.resId, key.countIndex, count, args, rtl)
    is UiText.Number -> NumberFormat.getNumberInstance(resources.locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = maxFractionDigits
        minimumIntegerDigits = minIntegerDigits
    }.format(value)
    is UiText.CalendarDate -> resources.locale.let { locale ->
        LocalDate.of(year, month, day)
            .format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale))
    }
    is UiText.LanguageName -> languageName(tag, resources.locale)
}

/**
 * [tag]'s language name in [locale]. A tag the platform can't name (it echoes
 * the tag back) shows as the uppercased tag.
 */
private fun languageName(tag: String, locale: Locale): String {
    val name = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(locale)
    return if (name.isBlank() || name.equals(tag, ignoreCase = true)) tag.uppercase(Locale.ROOT) else name
}

/** The interface language: the first locale of these resources. */
private val Resources.locale: Locale get() = configuration.locales[0]

/** A message whose String and [UiText] arguments are isolated in a right-to-left interface. */
private fun Resources.format(@StringRes id: Int, args: List<Any>, rtl: Boolean): String =
    if (args.isEmpty()) getString(id) else getString(id, *formatArgs(args, rtl))

/** A plural: [count] at [countIndex] among the placeholders (first by default), [args] as for [format] around it. */
private fun Resources.formatPlural(@PluralsRes id: Int, countIndex: Int, count: Int, args: List<Any>, rtl: Boolean): String {
    val values = formatArgs(args, rtl).toMutableList().apply { add(countIndex, count) }
    return getQuantityString(id, count, *values.toTypedArray())
}

/** [args] with each [UiText] resolved; when all are text, each is isolated in a right-to-left interface. */
private fun Resources.formatArgs(args: List<Any>, rtl: Boolean): Array<out Any> {
    val values = args.map { if (it is UiText) it.resolve(this, rtl) else it }
    return if (args.all { it is String || it is UiText }) {
        isolated(values.map { it as String }.toTypedArray(), rtl)
    } else {
        values.toTypedArray()
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
