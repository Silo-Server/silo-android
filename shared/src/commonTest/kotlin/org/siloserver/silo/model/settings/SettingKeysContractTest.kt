package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins this client's hand-written key list against the generated contract.
 *
 * PlaybackSettingsKeys predates the contract and still exists because Android
 * carries local-only keys the server has no opinion about. What it must not do
 * is disagree with the contract about the keys they share — a name that drifts
 * is a setting the server rejects, and the drift is invisible until a user's
 * write silently stops persisting.
 */
class SettingKeysContractTest {

    @Test
    fun everyKeyAndroidFlushesIsOneTheServerStores() {
        val remote = SettingKeys.REMOTE.toSet()

        // Android flattens subtitle appearance into granular fields locally; the
        // contract carries the composite object instead, so those are expected
        // to be absent from REMOTE and are projected before flushing.
        val locallyFlattened = PlaybackSettingsKeys.DeviceSettings.filter {
            it.startsWith("subtitle.")
        }.toSet()

        val unknown = PlaybackSettingsKeys.DeviceSettings.toSet() - remote - locallyFlattened
        assertTrue(
            unknown.isEmpty(),
            "these keys are flushed to the server but have no contract definition, " +
                "so the server will reject them: $unknown",
        )
    }

    @Test
    fun localOnlyKeysAreNeverFlushed() {
        // The contract's client_local persistence and Android's exclusion from
        // DeviceSettings are the same statement. A key the contract calls local
        // that Android flushes anyway would poison a whole settings batch.
        val flushed = PlaybackSettingsKeys.DeviceSettings.toSet()
        for (key in SettingKeys.CLIENT_LOCAL) {
            // Android suffixes two of these with ".local"; compare on the base.
            assertTrue(
                key !in flushed,
                "$key is client_local in the contract but Android flushes it",
            )
        }
    }

    @Test
    fun theSharedKeysAgreeOnSpelling() {
        // The specific pairs that drifted before the contract existed. Android
        // shipped player.next_up_prompt_seconds while Apple and the server used
        // playback.next_up_prompt_seconds, so the same preference was two
        // settings and neither client could read the other's.
        assertEquals(SettingKeys.PLAYBACK_AUDIO_LANGUAGE, PlaybackSettingsKeys.AudioLanguage)
        assertEquals(SettingKeys.PLAYBACK_PREFERRED_QUALITY, PlaybackSettingsKeys.PreferredQuality)
        assertEquals(SettingKeys.PLAYBACK_AUTO_SKIP_INTRO, PlaybackSettingsKeys.AutoSkipIntro)
        assertEquals(SettingKeys.PLAYBACK_AUTO_SKIP_CREDITS, PlaybackSettingsKeys.AutoSkipCredits)
        assertEquals(SettingKeys.PLAYBACK_AUTO_PLAY_NEXT, PlaybackSettingsKeys.AutoPlayNext)
        // The two the cutover actually renamed. The membership checks above
        // still pass if either constant points at some *other* contract key,
        // so name both pairs explicitly.
        assertEquals(
            SettingKeys.PLAYBACK_SUBTITLE_APPEARANCE,
            PlaybackSettingsKeys.SubtitleAppearance,
        )
        assertEquals(
            SettingKeys.PLAYBACK_NEXT_UP_PROMPT_SECONDS,
            PlaybackSettingsKeys.NextUpPromptSeconds,
        )
        assertEquals(SettingKeys.PLAYER_PLAYBACK_SPEED, PlaybackSettingsKeys.PlaybackSpeed)
        assertEquals(SettingKeys.PLAYER_AUDIO_SYNC_MS, PlaybackSettingsKeys.AudioSyncMs)
        assertEquals(SettingKeys.PLAYER_SUBTITLE_SYNC_MS, PlaybackSettingsKeys.SubtitleSyncMs)
        assertEquals(SettingKeys.PLAYER_HDR_ENABLED, PlaybackSettingsKeys.HdrEnabled)
        assertEquals(SettingKeys.PLAYER_VIDEO_GRAVITY, PlaybackSettingsKeys.VideoGravity)
        assertEquals(SettingKeys.PLAYER_ORIENTATION_MODE, PlaybackSettingsKeys.OrientationMode)
        assertEquals(
            SettingKeys.PLAYER_SLEEP_TIMER_DEFAULT_MINUTES,
            PlaybackSettingsKeys.SleepTimerDefaultMinutes,
        )
        assertEquals(
            SettingKeys.PLAYER_MATCH_FRAME_RATE,
            PlaybackSettingsKeys.MatchContentFrameRate,
        )
    }

    @Test
    fun theTypeTablesComeFromTheContract() {
        // Every generated classification must be a key the contract also lists
        // as remote, or the tables describe settings that cannot be written.
        val remote = SettingKeys.REMOTE.toSet()
        for (key in SettingKeys.BOOLEAN_KEYS + SettingKeys.INT_KEYS + SettingKeys.DOUBLE_KEYS) {
            assertTrue(key in remote, "$key is classified but not remote")
        }

        // And the three must not overlap: a key in two tables would parse
        // differently depending on which check ran first.
        assertTrue((SettingKeys.BOOLEAN_KEYS intersect SettingKeys.INT_KEYS).isEmpty())
        assertTrue((SettingKeys.BOOLEAN_KEYS intersect SettingKeys.DOUBLE_KEYS).isEmpty())
        assertTrue((SettingKeys.INT_KEYS intersect SettingKeys.DOUBLE_KEYS).isEmpty())
    }

    @Test
    fun theRenameTableTargetsTheKeysThatWereRenamed() {
        // The local migration copies each old slot into the value on the right,
        // so a target that drifts off the contract would move the user's value
        // into a slot nothing reads — the same silent revert the table exists
        // to prevent, just one rename later.
        assertEquals(
            mapOf(
                "subtitle_appearance" to SettingKeys.PLAYBACK_SUBTITLE_APPEARANCE,
                "player.next_up_prompt_seconds" to SettingKeys.PLAYBACK_NEXT_UP_PROMPT_SECONDS,
            ),
            PlaybackSettingsKeys.RenamedLocalKeys,
        )
        // And every old spelling must be genuinely retired: a key that is still
        // live would have its value copied out from under it.
        for (oldKey in PlaybackSettingsKeys.RenamedLocalKeys.keys) {
            assertTrue(
                oldKey !in SettingKeys.REMOTE,
                "$oldKey is still a contract key; renaming it locally would strand it",
            )
        }
    }

    @Test
    fun qualityIsTwoAxesHere() {
        // The compound ladder values are gone; a client composes a resolution
        // and a bitrate. Both keys have to exist for the picker to offer the
        // presets the phone and TV UIs show.
        assertTrue(SettingKeys.PLAYBACK_PREFERRED_QUALITY in SettingKeys.REMOTE)
        assertTrue(SettingKeys.PLAYBACK_MAX_BITRATE_KBPS in SettingKeys.REMOTE)
        assertTrue(SettingKeys.PLAYBACK_MAX_BITRATE_KBPS in SettingKeys.INT_KEYS)
    }
}
