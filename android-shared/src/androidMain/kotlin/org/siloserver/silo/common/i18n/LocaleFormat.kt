package org.siloserver.silo.common.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.icu.number.NumberFormatter
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import org.siloserver.silo.model.settings.AppLanguage
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The locale dates, times and numbers are shown in, and helpers that format
 * with it. Every call reads the current locale, so a language change applies
 * without a restart.
 *
 * Decision D26: with no app language, or when the device's own language is
 * the one chosen, format as the device does (an `en-GB` device that picks
 * English keeps day-month order); otherwise format in the chosen language.
 * The interface language is the default locale: the platform sets it to the
 * per-app language on 13+, and [AppLocales.wrap] does before 13. A language
 * without a catalog formats as its stand-in (D64, [AppLocales.standIn]).
 */
object LocaleFormat {
    @Volatile private var deviceLocales: LocaleList? = null
    @Volatile private var shipped: List<String>? = null

    /** The last [locale] result, for the default locale and device list it came from. */
    private class Resolved(val ui: Locale, val uiList: LocaleList?, val devices: LocaleList?, val locale: Locale)
    @Volatile private var resolved: Resolved? = null

    private val formatters = ConcurrentHashMap<FormatterKey, DateTimeFormatter>()
    private data class FormatterKey(val skeleton: String, val fallback: String, val locale: Locale, val padYear: Boolean)

    /**
     * Re-reads the device's language list. Called from each `Application`
     * and from [AppLanguageStore.check] (each activity's `onStart` and
     * `onConfigurationChanged`), which also covers coming back from system
     * settings after a device language change.
     */
    fun refresh(context: Context) {
        if (shipped == null) shipped = AppLocales.shipped(context)
        deviceLocales = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // On 13+ the process configuration carries the per-app
                // language, so only LocaleManager still knows the device's.
                context.getSystemService(LocaleManager::class.java).systemLocales
            } else {
                Resources.getSystem().configuration.locales
            }
        }.getOrNull()
        resolved = null
    }

    /**
     * Forgets the device list, so a unit test doesn't leak it to the next, and
     * sets the shipped languages ([shipped] null reads them at the next
     * [refresh]). Tests ship only English unless they pass more.
     */
    @VisibleForTesting
    internal fun resetForTests(shipped: List<String>? = null) {
        deviceLocales = null
        this.shipped = shipped
        resolved = null
    }

    /** The locale to format dates, times and numbers in (D26). */
    fun locale(): Locale {
        val ui = Locale.getDefault()
        val devices = deviceLocales ?: runCatching { Resources.getSystem().configuration.locales }.getOrNull()
        // With a stand-in possible (D64), the whole default list: its later entries pick it.
        val uiList = if (shipped != null) LocaleList.getDefault() else null
        resolved?.let { if (it.ui == ui && it.uiList == uiList && it.devices == devices) return it.locale }
        return resolve(ui, devices).also { resolved = Resolved(ui, uiList, devices, it) }
    }

    private fun resolve(ui: Locale, devices: LocaleList?): Locale {
        if (AppLanguage.isPseudo(ui.toLanguageTag())) return ui
        // D64: a language without a catalog formats as the language shown in its place.
        shipped?.let { list -> AppLocales.standIn(LocaleList.getDefault(), list)?.let { return it } }
        val device = devices?.takeUnless { it.isEmpty }?.get(0) ?: return ui
        return if (speaksSameLanguage(device, ui)) device else ui
    }

    /**
     * The language the interface shows, for words built in code (language
     * names): the default locale only when the app ships it. A German device
     * with no German catalog that follows the device shows English text,
     * formats and language names (D64). A pseudo-locale is its own interface.
     */
    fun interfaceLocale(): Locale {
        val ui = Locale.getDefault()
        val list = shipped ?: return ui
        if (AppLanguage.isPseudo(ui.toLanguageTag())) return ui
        val tag = AppLanguage.match(LocaleList.getDefault().toLanguageTags().split(','), list) ?: AppLanguage.DEFAULT
        return Locale.forLanguageTag(tag)
    }

    /**
     * The locale for a number inside text (D55): [locale] when it speaks the
     * interface language, otherwise the interface language, so an English
     * interface on an Arabic device writes "3 episodes" as `%d` does.
     */
    fun numberLocale(): Locale {
        val format = locale()
        val text = interfaceLocale()
        return if (speaksSameLanguage(format, text)) format else text
    }

    private fun speaksSameLanguage(a: Locale, b: Locale): Boolean {
        if (a.language != b.language) return false
        return runCatching { maxScript(a) == maxScript(b) }.getOrDefault(true)
    }

    private fun maxScript(locale: Locale): String = ULocale.addLikelySubtags(ULocale.forLocale(locale)).script

    /**
     * [value] as digits in [locale], without grouping (D55): "1234" in
     * English, "١٢٣٤" in Arabic. For numbers passed to a String placeholder.
     */
    fun integer(value: Long, locale: Locale = numberLocale(), minDigits: Int = 1): String =
        String.format(locale, "%0${minDigits.coerceAtLeast(1)}d", value)

    fun integer(value: Int, locale: Locale = numberLocale(), minDigits: Int = 1): String =
        integer(value.toLong(), locale, minDigits)

    /**
     * A count grouped by [locale]'s thousands separator, in its digits:
     * "1,234" in English, "1.234" in German, "١٬٢٣٤" in Arabic. For counts
     * that can run large: a collection's size, a person's title count. Years,
     * codes and episode numbers stay ungrouped ([integer]).
     *
     * On Android 11+ it follows the locale's minimum grouping, as Apple does:
     * Spanish writes "1284" and groups from "12.840". Older versions group
     * every count over 999.
     */
    fun groupedCount(value: Int, locale: Locale = numberLocale()): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NumberFormatter.withLocale(locale)
                .grouping(NumberFormatter.GroupingStrategy.AUTO)
                .format(value)
                .toString()
        } else {
            NumberFormat.getIntegerInstance(locale).format(value)
        }

    /**
     * A season or episode number inside a code ("S2", "E05", "S2021E01") in
     * ASCII digits in every language: codes are technical (D78). A phrase
     * such as "Season 2" uses [integer].
     */
    fun codeNumber(value: Int, minDigits: Int = 1): String = integer(value, Locale.ROOT, minDigits)

    /**
     * [value] with up to [maxFractionDigits] decimals and no trailing zeros
     * or grouping, rounded half up as `String.format` does: a playback speed
     * reads "1.25", "1.5" or "2" in English and "1,25" in German.
     */
    fun decimal(value: Double, maxFractionDigits: Int, locale: Locale = locale()): String =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = maxFractionDigits
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    /** [percent] (0–100) as the locale writes a percentage: "45%" in English, "٤٥٪" in Arabic-Indic Arabic. */
    fun percent(percent: Int, locale: Locale = numberLocale()): String =
        NumberFormat.getPercentInstance(locale).apply { isGroupingUsed = false }.format(percent / 100.0)

    /** A clock-style duration, "1:02:03" or, without [showHours], "2:03", in [locale]'s digits. */
    fun clock(
        hours: Number,
        minutes: Number,
        seconds: Number,
        showHours: Boolean = hours.toLong() > 0,
        locale: Locale = locale(),
    ): String = if (showHours) {
        String.format(locale, "%d:%02d:%02d", hours.toLong(), minutes.toLong(), seconds.toLong())
    } else {
        String.format(locale, "%d:%02d", minutes.toLong(), seconds.toLong())
    }

    /**
     * A formatter for a date or time [skeleton] (`yMMMd`, `MMMd`, `jmm`), in
     * [locale]'s own field order and digits. US English matches the fixed
     * patterns the app used before: "Jul 8, 2026", "8:00 PM".
     *
     * @param fallbackPattern used when java.time can't read the locale's
     *   pattern; the pattern the call site had before.
     * @param padYear pad years to four digits, as the old `yyyy` patterns
     *   did; false for call sites that printed the year as is.
     */
    fun dateFormatter(
        skeleton: String,
        fallbackPattern: String,
        locale: Locale = locale(),
        padYear: Boolean = true,
    ): DateTimeFormatter = formatters.getOrPut(FormatterKey(skeleton, fallbackPattern, locale, padYear)) {
        buildFormatter(skeleton, fallbackPattern, locale, padYear)
    }

    private fun buildFormatter(skeleton: String, fallbackPattern: String, locale: Locale, padYear: Boolean): DateTimeFormatter {
        val best = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
            .getOrNull()?.let { normalizePattern(it, padYear) }
        val formatter = best?.let { pattern ->
            runCatching { DateTimeFormatter.ofPattern(pattern, locale) }.getOrNull()
                // ICU's flexible day periods (`B`, `b`) predate java.time on Android.
                ?: runCatching { DateTimeFormatter.ofPattern(withAmPm(pattern), locale) }.getOrNull()
        } ?: DateTimeFormatter.ofPattern(fallbackPattern, locale)
        return formatter.withDecimalStyle(DecimalStyle.of(locale))
    }

    /**
     * ICU 72+ puts a narrow no-break space before AM/PM; the app printed a
     * space. With [padYear], a lone `y` becomes `yyyy`.
     */
    internal fun normalizePattern(pattern: String, padYear: Boolean = true): String = mapUnquoted(pattern) { run ->
        if (padYear && run == "y") "yyyy" else run.replace('\u202F', ' ')
    }

    /** ICU's flexible day periods ("in the evening") as plain AM/PM, which java.time reads everywhere. */
    internal fun withAmPm(pattern: String): String =
        mapUnquoted(pattern) { run -> if (run[0] == 'B' || run[0] == 'b') "a" else run }

    /** Applies [transform] to each run of one unquoted letter, or each unquoted non-letter. */
    private inline fun mapUnquoted(pattern: String, transform: (String) -> String): String {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> {
                    val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length - 1 else it }
                    out.append(pattern, i, end + 1)
                    i = end + 1
                }
                c.isLetter() -> {
                    var j = i
                    while (j < pattern.length && pattern[j] == c) j++
                    out.append(transform(pattern.substring(i, j)))
                    i = j
                }
                else -> {
                    out.append(transform(c.toString()))
                    i++
                }
            }
        }
        return out.toString()
    }
}

/**
 * A number for a String placeholder in text a model builds and a screen draws
 * later: it is formatted ([LocaleFormat.integer]) when drawn, so it follows a
 * language change. [minDigits] zero-pads ("05").
 */
data class LocaleInteger(val value: Long, val minDigits: Int = 1) {
    constructor(value: Int, minDigits: Int = 1) : this(value.toLong(), minDigits)

    fun format(locale: Locale = LocaleFormat.numberLocale()): String = LocaleFormat.integer(value, locale, minDigits)
}

/** [LocaleFormat.numberLocale] for a composable; see [currentFormatLocale]. */
@Composable
@ReadOnlyComposable
fun currentNumberLocale(): Locale {
    LocalConfiguration.current
    return LocaleFormat.numberLocale()
}

/**
 * [LocaleFormat.locale] for a composable. Reading the configuration makes
 * the caller recompose after an in-place language change; pass the result
 * to formatters and to `remember` keys.
 */
@Composable
@ReadOnlyComposable
fun currentFormatLocale(): Locale {
    LocalConfiguration.current
    return LocaleFormat.locale()
}
