package org.siloserver.silo.android.profiles

import org.siloserver.silo.common.profiles.ProfileSelectionPrompt
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
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

    @Test
    fun `a launch with no remembered profile opens the only profile by the server's list, else the cached one`() {
        val jordan = Profile(id = "jordan", name = "Jordan")
        val sam = Profile(id = "sam", name = "Sam")
        data class Case(val fresh: ApiResult<List<Profile>>?, val cached: String?, val expected: String?)
        listOf(
            // The server answered: its list decides, whatever the cache says.
            Case(ApiResult.Success(listOf(jordan)), cached = null, expected = "jordan"),
            Case(ApiResult.Success(listOf(jordan)), cached = "old", expected = "jordan"),
            Case(ApiResult.Success(listOf(jordan.copy(hasPin = true))), cached = "jordan", expected = null),
            Case(ApiResult.Success(listOf(jordan, sam)), cached = "jordan", expected = null),
            Case(ApiResult.Success(emptyList()), cached = "jordan", expected = null),
            // It answered with an error: the picker explains it.
            Case(ApiResult.Error(403, "password_change_required", ""), cached = "jordan", expected = null),
            // No answer: the cached list decides, so no one-tile picker is drawn.
            Case(ApiResult.NetworkError(RuntimeException("offline")), cached = "jordan", expected = "jordan"),
            Case(null, cached = "jordan", expected = "jordan"),
            Case(null, cached = null, expected = null),
        ).forEach { case ->
            assertEquals(case.expected, launchOnlyProfileId(case.fresh, case.cached), case.toString())
        }
    }
}
