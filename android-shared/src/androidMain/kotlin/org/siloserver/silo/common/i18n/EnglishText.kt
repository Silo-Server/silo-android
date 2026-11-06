package org.siloserver.silo.common.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The catalog's English, whatever the app language, for text that is data
 * rather than interface: protocol messages another device reads, logs and the
 * debug test hook. Text a user sees uses the normal resources instead.
 *
 * [install] runs from each `Application`; before it does (unit tests), every
 * lookup returns null.
 */
object EnglishText {
    @Volatile
    private var resources: Resources? = null

    fun install(context: Context) {
        val app = context.applicationContext
        resources = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(Locale.US) },
        ).resources
    }

    fun string(@StringRes id: Int, vararg args: Any): String? = resources?.getString(id, *args)
}
