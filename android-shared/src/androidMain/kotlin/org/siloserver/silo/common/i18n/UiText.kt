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
import org.siloserver.silo.model.settings.AppLanguage
import org.siloserver.silo.network.ApiResult
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
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
    is UiText.Key -> resources.catalogResources().let { it.format(key.resId, args, it.isRtl) }
    is UiText.Plural -> resources.catalogResources().let { it.formatPlural(key.resId, key.countIndex, count, args, it.isRtl) }
    is UiText.Number -> NumberFormat.getNumberInstance(resources.formatLocale).apply {
        isGroupingUsed = false
        maximumFractionDigits = maxFractionDigits
        minimumIntegerDigits = minIntegerDigits
    }.format(value)
    is UiText.CalendarDate -> resources.formatLocale.let { locale ->
        LocalDate.of(year, month, day)
            .format(
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale)
                    .withDecimalStyle(DecimalStyle.of(locale)),
            )
    }
    is UiText.LanguageName -> languageName(tag, uppercaseIfUnnamed, resources.formatLocale)
}

/**
 * [tag]'s language name in [locale]. A tag the platform can't name (it echoes
 * the tag back) shows as given, or uppercased when [uppercaseIfUnnamed].
 */
private fun languageName(tag: String, uppercaseIfUnnamed: Boolean, locale: Locale): String {
    val name = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(locale)
    return when {
        name.isBlank() -> if (uppercaseIfUnnamed) tag.uppercase(Locale.ROOT) else tag
        uppercaseIfUnnamed && name.equals(tag, ignoreCase = true) -> tag.uppercase(Locale.ROOT)
        else -> name
    }
}

/**
 * Resources that resolve the shared module's text in its catalog language:
 * these when the app ships the configured language (so an en-GB phone keeps its
 * own date order, D26) or it is a debug pseudo-locale. Otherwise the strings
 * fall back to English, and so do plural rules, digits, number and date formats
 * and text direction, so a language changes nothing until its catalog ships (D25).
 */
private fun Resources.catalogResources(): Resources {
    val locale = configuration.locales[0]
    val tag = locale.toLanguageTag()
    if (AppLanguage.isPseudo(tag) || AppLanguage.match(tag, AppLocales.shipped(this)) != null) return this
    fallbackResources?.let { (config, fallback) -> if (config == configuration) return fallback }
    val config = android.content.res.Configuration(configuration)
    @Suppress("DEPRECATION")
    val fallback = Resources(
        assets,
        displayMetrics,
        android.content.res.Configuration(configuration).apply { setLocale(Locale.forLanguageTag(AppLanguage.DEFAULT)) },
    )
    fallbackResources = config to fallback
    return fallback
}

/** The last [catalogResources] fallback, for the configuration it was made for. */
@Volatile private var fallbackResources: Pair<android.content.res.Configuration, Resources>? = null

private val Resources.isRtl: Boolean get() = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

/** The locale numbers, dates and language names are written in; see [catalogResources]. */
private val Resources.formatLocale: Locale
    get() = catalogResources().configuration.locales[0]

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
