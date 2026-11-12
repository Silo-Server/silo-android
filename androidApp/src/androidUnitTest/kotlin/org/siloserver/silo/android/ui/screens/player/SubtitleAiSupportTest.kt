package org.siloserver.silo.android.ui.screens.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubtitleAiSupportTest {

    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun track(codec: String?, source: String?) = PlayerSubtitleInfo(
        index = 0, language = "en", codec = codec, label = null,
        source = source, forced = null, url = "/u",
    )

    @Test
    fun embeddedTextTracksAreTranslatable() {
        assertTrue(isTranslatableSource(track("subrip", "embedded")))
        assertTrue(isTranslatableSource(track("ass", "embedded"))) // ffmpeg-extractable
    }

    @Test
    fun embeddedBitmapTracksAreNot() {
        assertFalse(isTranslatableSource(track("hdmv_pgs_subtitle", "embedded")))
        assertFalse(isTranslatableSource(track("dvd_subtitle", "embedded")))
    }

    @Test
    fun externalAndDownloadedMustBeParseableText() {
        assertTrue(isTranslatableSource(track("srt", "external")))
        assertTrue(isTranslatableSource(track("vtt", "downloaded")))
        assertFalse(isTranslatableSource(track("ass", "external"))) // server can't parse external ASS
        assertFalse(isTranslatableSource(track(null, "external")))
    }

    @Test
    fun transcribeKindMatchesWebSemantics() {
        assertEquals("transcribe", transcribeKindFor("en", "en"))
        assertEquals("transcribe_translate", transcribeKindFor("ja", "en"))
        assertEquals("transcribe_translate", transcribeKindFor(null, "en"))
    }

    @Test
    fun quotaLineShowsRemaining() {
        val q = SubtitleAiQuota(limited = true, limit = 10, used = 3, remaining = 7, period = "week")
        assertEquals("7 of 10 transcriptions left for the last 7 days.", quotaLineText(q, resources))
    }

    @Test
    fun quotaLineShowsExhausted() {
        val q = SubtitleAiQuota(limited = true, limit = 5, used = 5, remaining = 0, period = "day")
        assertEquals(
            "You've used all 5 transcriptions for the last 24 hours. Try again later.",
            quotaLineText(q, resources),
        )
    }

    @Test
    fun unknownPeriodFallsThrough() {
        val q = SubtitleAiQuota(limited = true, limit = 2, used = 0, remaining = 2, period = "fortnight")
        assertEquals("2 of 2 transcriptions left for the last fortnight.", quotaLineText(q, resources))
    }
}
