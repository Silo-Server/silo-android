package org.siloserver.silo.model.settings

/**
 * The settings contract revision a device setting needs, for the keys newer
 * than every server this app supports. A server answers a batched read that
 * names a key its contract lacks with 404 for the whole batch, and refuses a
 * write of one as unknown_setting, so these keys are read and sent only once
 * the server's revision is known to include them.
 */
object DeviceSettingRevisions {
    /** Revision that introduced `ui.theme_music_enabled` and `ui.theme_music_loop`. */
    const val THEME_MUSIC = 11

    private val THEME_MUSIC_KEYS = setOf(SettingKeys.UI_THEME_MUSIC_ENABLED, SettingKeys.UI_THEME_MUSIC_LOOP)

    /** The revision [key] needs, or null for a key every supported server stores. */
    fun minimumRevision(key: String): Int? =
        CellularQuality.minimumRevision(key) ?: THEME_MUSIC.takeIf { key in THEME_MUSIC_KEYS }
}
