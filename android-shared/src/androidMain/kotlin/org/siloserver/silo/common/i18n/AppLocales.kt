package org.siloserver.silo.common.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.siloserver.silo.model.settings.AppLanguage

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
        val config = Configuration().apply { setLocales(LocaleList.forLanguageTags(tags)) }
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
     * The shipped language the interface shows now, for `Accept-Language`.
     * The default locale list already carries the per-app value on 13+, and
     * [wrap] sets the default locale before 13.
     */
    fun appliedTag(): String =
        AppLanguage.match(LocaleList.getDefault().toLanguageTags().split(',')) ?: AppLanguage.SHIPPED.first()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(context: Context): LocaleManager =
        context.getSystemService(LocaleManager::class.java)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
