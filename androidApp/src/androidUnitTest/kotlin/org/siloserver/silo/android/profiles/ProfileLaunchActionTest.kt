package org.siloserver.silo.android.profiles

import org.siloserver.silo.common.profiles.ProfileSelectionPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileLaunchActionTest {

    @Test
    fun `asks only when the setting asks and the server can be used`() {
        assertEquals(
            ProfileLaunchAction.KeepProfile,
            profileLaunchAction(prompt = ProfileSelectionPrompt.None, startsOfflineOnDownloads = false),
        )
        assertEquals(
            ProfileLaunchAction.KeepProfile,
            profileLaunchAction(prompt = ProfileSelectionPrompt.None, startsOfflineOnDownloads = true),
        )
        assertEquals(
            ProfileLaunchAction.AskWhoIsWatching,
            profileLaunchAction(prompt = ProfileSelectionPrompt.WhoIsWatching, startsOfflineOnDownloads = false),
        )
        // Several accounts are saved: whose account comes first.
        assertEquals(
            ProfileLaunchAction.AskWhichAccount,
            profileLaunchAction(prompt = ProfileSelectionPrompt.ChangeAccount, startsOfflineOnDownloads = false),
        )
    }

    @Test
    fun `offline with downloads keeps the profile instead of stranding the picker or Change Account`() {
        assertEquals(
            ProfileLaunchAction.KeepProfileOffline,
            profileLaunchAction(prompt = ProfileSelectionPrompt.WhoIsWatching, startsOfflineOnDownloads = true),
        )
        assertEquals(
            ProfileLaunchAction.KeepProfileOffline,
            profileLaunchAction(prompt = ProfileSelectionPrompt.ChangeAccount, startsOfflineOnDownloads = true),
        )
    }
}
