package org.siloserver.silo.common.i18n

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.NumberingSystem
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import org.siloserver.silo.common.R
import org.siloserver.silo.model.settings.AppLanguage
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The per-app language, read and set the way this app can: the framework
 * `LocaleManager` on Android 13+, and before 13 a tag stored here and applied
 * by [wrap] from each activity's `attachBaseContext`.
 *
 * `AppCompatDelegate.setApplicationLocales` is no use here: both activities
 * are plain `ComponentActivity`, and it only reaches the platform through an
 * `AppCompatActivity`.
 *
 * Tags are `LocaleList.toLanguageTags()` strings; "" means follow the device.
 *
 * Decision D64: when the language in effect has no Silo catalog (a device
 * language this build doesn't ship), [wrap] shows the activities in a stand-in
 * ([standIn]) instead, so resources, `%d` digits, plural rules, direction and
 * [LocaleFormat] all agree until that language ships.
 *
 * Decision D75: the shown locale also carries [LocaleFormat]'s digits
 * ([withFormatDigits]), so `%d` in resources and plurals writes the same
 * digits as the numbers and dates the app formats itself.
 */
object AppLocales {
    private const val PREFS = "silo_app_locales"
    private const val KEY_TAGS = "tags"

    /** The per-app value the platform (or, before 13, this app) holds now. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(context).applicationLocales.toLanguageTags()
        } else {
            prefs(context).getString(KEY_TAGS, "").orEmpty()
        }

    /**
     * Sets the per-app language. On 13+ the platform persists it and
     * reconfigures every activity (in place: both declare
     * `locale|layoutDirection` in `configChanges`).
     *
     * @return true before 13, when the caller must recreate the visible
     *   activity so [wrap] applies the stored tag.
     */
    fun apply(context: Context, tags: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(context).applicationLocales = LocaleList.forLanguageTags(tags)
            return false
        }
        prefs(context).edit(commit = true) { putString(KEY_TAGS, tags) }
        return true
    }

    /**
     * [base] reconfigured to the locales the app shows; call from
     * `attachBaseContext`. Before 13 that is the stored tag (from 13 the
     * platform applies the per-app value itself), and on every version a
     * language without a catalog is replaced by its [standIn] (D64).
     */
    fun wrap(base: Context): Context {
        val tags = legacyTags(base)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) setProcessDefault(tags)
        val locales = inEffect(tags, base)
        val shown = shown(base, locales)
        if (tags.isEmpty() && shown == locales) return base
        // Only the locale is overridden; orientation, size and night mode
        // keep following the device, since both activities handle those in place.
        val config = Configuration().apply {
            // A new Configuration's font scale is 1, which would override the
            // user's text size; 0 leaves it to the base context.
            fontScale = 0f
            setLocales(shown)
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Whether [activity] shows another language than [wrap] would give it now.
     * [wrap] fixes an activity's locale, so a language change it delivers in
     * place (`configChanges`) needs a recreation to apply; call from
     * `onConfigurationChanged`.
     */
    fun needsRecreate(activity: Activity): Boolean {
        // Before 13 the platform resets the default to the device language on a change;
        // put the app language back first, as check() would, so the digits compare like for like.
        reassertDefault(activity)
        val locales = inEffect(legacyTags(activity), activity.applicationContext)
        if (locales.isEmpty) return false
        return shown(activity, locales)[0] != activity.resources.configuration.locales[0]
    }

    /** The per-app locales: the stored [tags] before 13, else [context]'s configuration. */
    private fun inEffect(tags: String, context: Context): LocaleList =
        if (tags.isEmpty()) context.resources.configuration.locales else LocaleList.forLanguageTags(tags)

    /** [locales], or their [standIn] when the first has no catalog, with [LocaleFormat]'s digits. */
    private fun shown(context: Context, locales: LocaleList): LocaleList {
        // Both callers can run before AppLanguageStore.check, so read the device's list now.
        LocaleFormat.refresh(context)
        val shown = standIn(locales, shipped(context))?.let { LocaleList(it) } ?: locales
        return withFormatDigits(shown, LocaleFormat.numberLocale())
    }

    /**
     * D75: [locales] with the first given [format]'s digits (a `-u-nu-`
     * keyword) when its own default differs, and otherwise unchanged.
     * `Resources` formats `%d` in its configuration's first locale, while
     * [LocaleFormat] follows the device's region (D26): an app set to `ar` on
     * an `ar-EG` device formats in `ar-EG` (Arabic-Indic digits), but bare
     * `ar` defaults to Latin digits from Android 16. Resource matching,
     * plural rules and direction ignore the keyword.
     */
    internal fun withFormatDigits(locales: LocaleList, format: Locale): LocaleList {
        if (locales.isEmpty) return locales
        val first = locales[0]
        val digits = numberingSystem(format)
        if (numberingSystem(first) == digits) return locales
        val pinned = Locale.Builder().setLocale(first).setUnicodeLocaleKeyword("nu", digits).build()
        return LocaleList(pinned, *(1 until locales.size()).map { locales[it] }.toTypedArray())
    }

    private fun numberingSystem(locale: Locale): String =
        numberingSystems.getOrPut(locale) { NumberingSystem.getInstance(ULocale.forLocale(locale)).name }

    private val numberingSystems = ConcurrentHashMap<Locale, String>()

    /**
     * D64: the locale to show instead of [locales]' first when this build has
     * no catalog for it: the first locale in the list whose language ships
     * (D27 matching), kept as the device has it so its region's formats apply
     * (`en-AU`, D26), else English. Null when the first is shipped (an en-GB
     * device keeps its own formats, D59) or a debug pseudo-locale.
     */
    internal fun standIn(locales: LocaleList, shipped: List<String>): Locale? {
        if (locales.isEmpty) return null
        val first = locales[0].toLanguageTag()
        if (AppLanguage.isPseudo(first) || AppLanguage.match(first, shipped) != null) return null
        return (0 until locales.size()).map { locales[it] }
            .firstOrNull { !AppLanguage.isPseudo(it.toLanguageTag()) && AppLanguage.match(it.toLanguageTag(), shipped) != null }
            ?: Locale.forLanguageTag(AppLanguage.DEFAULT)
    }

    /** The tag stored before 13; "" from 13, where the platform holds the per-app value. */
    private fun legacyTags(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "" else prefs(context).getString(KEY_TAGS, "").orEmpty()

    /**
     * Before 13, puts the app language back as the process default. A device
     * language change is handled in place (`configChanges`), and the platform
     * resets the default to the device language when it delivers one.
     */
    fun reassertDefault(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        setProcessDefault(legacyTags(context))
    }

    /** Formatters and `Accept-Language` read the default; "" restores the device's. */
    private fun setProcessDefault(tags: String) {
        LocaleList.setDefault(
            if (tags.isEmpty()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(tags),
        )
    }

    /**
     * On the first 13+ start after an OS upgrade, hands a tag stored before
     * 13 to the platform, so the choice is not silently lost. A value the
     * user already chose in system settings since the upgrade wins.
     */
    fun migrateLegacyTag(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = prefs(context)
        val legacy = prefs.getString(KEY_TAGS, null) ?: return
        if (legacy.isNotEmpty() && localeManager(context).applicationLocales.isEmpty) {
            localeManager(context).applicationLocales = LocaleList.forLanguageTags(legacy)
        }
        prefs.edit(commit = true) { remove(KEY_TAGS) }
    }

    /**
     * The shipped language the interface shows now, for `Accept-Language`
     * and server text. The default locale list already carries the per-app
     * value on 13+. Before 13 the stored tag is read directly: [wrap] sets
     * the default only once an activity starts, and a process can run
     * without one (a worker, a notification action).
     */
    fun appliedTag(context: Context): String {
        val tags = current(context).takeIf { it.isNotEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU }
            ?: LocaleList.getDefault().toLanguageTags()
        return AppLanguage.match(tags.split(','), shipped(context)) ?: AppLanguage.DEFAULT
    }

    @Volatile private var shippedCache: List<String>? = null

    /** Sets the shipped languages for a unit test, whose build ships only English; null reads them again. */
    @VisibleForTesting
    internal fun setShippedForTests(shipped: List<String>?) {
        shippedCache = shipped
    }

    /**
     * The languages this build ships: its generated localeConfig (the
     * languages it has resources for), without pseudo-locales. A language
     * counts once its translations ship, and debug-only resources count only
     * in debug builds.
     */
    fun shipped(context: Context): List<String> =
        shipped(context.applicationContext.resources, context.packageName)

    /** [shipped] for code that holds only [Resources], such as [UiText]'s resolver. */
    internal fun shipped(resources: Resources): List<String> =
        shipped(resources, resources.getResourcePackageName(R.string.catalog_error_network))

    private fun shipped(resources: Resources, packageName: String): List<String> =
        shippedCache ?: (listOf(AppLanguage.DEFAULT) + readLocaleConfig(resources, packageName).filterNot(AppLanguage::isPseudo))
            .distinct().also { shippedCache = it }

    /**
     * The pseudo-locales (`en-XA`, `ar-XB`) in this build's localeConfig.
     * Only debug builds have them; the picker offers them there for
     * pseudo-locale checks on devices whose system settings list none.
     */
    fun pseudoLocales(context: Context): List<String> =
        readLocaleConfig(context.applicationContext.resources, context.packageName).filter(AppLanguage::isPseudo)

    // AGP generates this resource into each app; android-shared can't see the
    // apps' R classes, so it is looked up by name.
    @SuppressLint("DiscouragedApi")
    private fun readLocaleConfig(res: Resources, packageName: String): List<String> {
        val id = res.getIdentifier("_generated_res_locale_config", "xml", packageName)
        val tags = if (id == 0) emptyList() else runCatching {
            res.getXml(id).use { parser ->
                buildList {
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                            parser.getAttributeValue(ANDROID_NS, "name")?.let(::add)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
        return tags
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(context: Context): LocaleManager =
        context.getSystemService(LocaleManager::class.java)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
