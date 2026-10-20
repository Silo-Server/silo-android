package org.siloserver.silo.android.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siloserver.silo.cast.SiloCastCloseReason
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastNowPlayingSummary
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.common.cast.SiloCastTarget

/** The phone's rules for a TV that keeps several controllers (silo-apple SiloControlPhonePolicy). */
class SiloCastPhonePolicyTest {
    private val target = SiloCastTarget(deviceId = "tv-1", name = "Living Room", host = "10.0.0.2", port = 4711, version = 2)
    private val request = SiloCastLaunchRequest(
        serverId = "srv-1",
        playback = SiloCastPlaybackRequest(contentId = "miasma", startFromBeginning = true),
    )

    @Test
    fun anOlderTvsCloseAlwaysForgetsIt() {
        for (reason in listOf(null, SiloCastCloseReason.Timeout, SiloCastCloseReason.Replaced)) {
            assertEquals(
                SiloCastCloseResponse.LetGo(forgetTarget = true),
                SiloCastClosePolicy.response(reason, tvKeepsOtherControllers = false),
            )
        }
    }

    @Test
    fun aMultiControllerTvSaysWhetherToReconnectOrForget() {
        fun response(reason: String?) = SiloCastClosePolicy.response(reason, tvKeepsOtherControllers = true)
        assertEquals(SiloCastCloseResponse.Reconnect, response(SiloCastCloseReason.Timeout))
        assertEquals(SiloCastCloseResponse.LetGo(forgetTarget = true), response(SiloCastCloseReason.TvDisconnected))
        assertEquals(SiloCastCloseResponse.LetGo(forgetTarget = true), response(null))
        for (reason in listOf(
            SiloCastCloseReason.TooManyControllers,
            SiloCastCloseReason.Unauthorized,
            SiloCastCloseReason.LaunchTimeout,
            SiloCastCloseReason.Replaced,
        )) {
            assertEquals(reason, SiloCastCloseResponse.LetGo(forgetTarget = false), response(reason))
        }
    }

    @Test
    fun theTvsPromptNamesWhoIsWatchingAndTiesConsentToThatTitle() {
        val pending = SiloCastReplacePrompt.fromTv(
            target,
            request,
            SiloCastNowPlayingSummary(contentId = "oak", title = "Oak Street", subtitle = "Season 1", profileName = "Alex"),
            fallbackMessage = "Alex is watching Oak Street on this TV.",
        )
        assertEquals("Alex is watching Season 1 · Oak Street on Living Room. Play yours instead?", pending.message)
        assertEquals("Replace", pending.confirmLabel)
        assertEquals(SiloCastReplaceConsent(contentId = "oak"), pending.consent)
        assertEquals(request, pending.request)
    }

    @Test
    fun aPromptWithoutAProfileNameOrSummaryStillReads() {
        assertEquals(
            "Someone is watching Oak Street on Living Room. Play yours instead?",
            SiloCastReplacePrompt.tvMessage(SiloCastNowPlayingSummary(title = "Oak Street"), "Living Room", null),
        )
        val fallback = SiloCastReplacePrompt.fromTv(target, request, nowPlaying = null, fallbackMessage = "Taken.")
        assertEquals("Taken.", fallback.message)
        assertNull(fallback.consent.contentId)
    }

    @Test
    fun thePhoneAsksBeforeReplacingADifferentTitleOnly() {
        assertTrue(SiloCastReplacePrompt.replacesPlayingTitle("oak", "miasma"))
        assertFalse(SiloCastReplacePrompt.replacesPlayingTitle("oak", "oak"))
        assertFalse(SiloCastReplacePrompt.replacesPlayingTitle(null, "miasma"))
        assertFalse(SiloCastReplacePrompt.replacesPlayingTitle("", "miasma"))

        val pending = SiloCastReplacePrompt.beforeSending(target, request, currentContentId = "oak", currentTitle = "Oak Street")
        assertEquals("Living Room is playing Oak Street. Playing this will stop it.", pending.message)
        assertEquals("Play on Living Room", pending.confirmLabel)
        assertEquals(SiloCastReplaceConsent(contentId = "oak"), pending.consent)
    }

    @Test
    fun resumeTokensBelongToOneTvServerAndProfile() {
        val tokens = SiloCastResumeTokens { lhs, rhs -> lhs == rhs }
        tokens.store("tok-1", tvId = "tv-1", serverId = "srv-1", profileId = "alex")
        assertEquals("tok-1", tokens.token("tv-1", "srv-1", "alex"))
        assertNull(tokens.token("tv-2", "srv-1", "alex"))
        assertNull(tokens.token("tv-1", "srv-2", "alex"))
        assertNull(tokens.token("tv-1", "srv-1", "sam"))

        // A ready without a token (older TV, or a reused identity) keeps the last one.
        tokens.store(null, tvId = "tv-1", serverId = "srv-1", profileId = "alex")
        tokens.store("", tvId = "tv-1", serverId = "srv-1", profileId = "alex")
        assertEquals("tok-1", tokens.token("tv-1", "srv-1", "alex"))
    }
}
