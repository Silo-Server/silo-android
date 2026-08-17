package org.siloserver.silo.android.ui.screens.calendar

import android.content.Context
import org.siloserver.silo.viewmodel.CalendarFilterStore

/**
 * SharedPreferences-backed [CalendarFilterStore]. Device-global, like the
 * iOS `UserDefaults["calendar.filter"]` it mirrors.
 */
class CalendarPrefsStore(context: Context) : CalendarFilterStore {
    private val prefs = context.applicationContext.getSharedPreferences("calendar_prefs", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_FILTER, null)

    override fun write(filter: String) {
        prefs.edit().putString(KEY_FILTER, filter).apply()
    }

    private companion object {
        const val KEY_FILTER = "calendar.filter"
    }
}
