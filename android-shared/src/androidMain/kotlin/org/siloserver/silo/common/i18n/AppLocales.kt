package org.siloserver.silo.common.i18n

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.siloserver.silo.common.R
import org.siloserver.silo.model.settings.AppLanguage
import org.xmlpull.v1.XmlPullParser

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
     * Before 13, returns [base] reconfigured to the stored tag; call from
     * `attachBaseContext`. From 13 the platform does this itself.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tags = prefs(base).getString(KEY_TAGS, "").orEmpty()
        setProcessDefault(tags)
        if (tags.isEmpty()) return base
        // Only the locale is overridden; orientation, size and night mode
        // keep following the device, since both activities handle those in place.
        val config = Configuration().apply {
            // A new Configuration's font scale is 1, which would override the
            // user's text size; 0 leaves it to the base context.
            fontScale = 0f
            setLocales(LocaleList.forLanguageTags(tags))
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Before 13, puts the app language back as the process default. A device
     * language change is handled in place (`configChanges`), and the platform
     * resets the default to the device language when it delivers one.
     */
    fun reassertDefault(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        setProcessDefault(prefs(context).getString(KEY_TAGS, "").orEmpty())
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
