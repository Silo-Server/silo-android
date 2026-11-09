package org.siloserver.silo.common.player

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Media3's `SubtitlePainter` draws every text cue without a line at the same
 * bottom spot and never moves one cue out of another's way. These lock the
 * stacking that keeps two captions shown at once (a sign and the dialogue under
 * it) readable.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SubtitleSimultaneousCueTest {

    /** A cue as Media3's SubRip parser builds one without an alignment tag. */
    private fun subripCue(text: CharSequence): Cue = Cue.Builder().setText(text).build()

    /** A cue as Media3's WebVTT parser builds one with default placement. */
    private fun webvttCue(text: CharSequence, line: Float = -1f): Cue = Cue.Builder()
        .setText(text)
        .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
        .setLine(line, Cue.LINE_TYPE_NUMBER)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .build()

    private fun group(vararg cues: Cue) = CueGroup(cues.toList(), 1_000L)

    @Test
    fun twoSubripCuesShownAtOnceBecomeOneBlockWithTheEarlierCueAtTheBottom() {
        val stacked = SimultaneousTextCueStacker().stack(
            group(subripCue("[Authorized Representative]"), subripCue("in the plaintiff's evaluation,")),
        )

        assertEquals(1, stacked.cues.size)
        val cue = stacked.cues.single()
        assertEquals("in the plaintiff's evaluation,\n[Authorized Representative]", cue.text.toString())
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(1_000L, stacked.presentationTimeUs)
    }

    @Test
    fun streamedWebvttCuesEachAtTheDefaultLineStackAndKeepThePositionPreset() {
        val stacked = SimultaneousTextCueStacker().stack(
            group(webvttCue("first"), webvttCue("second")),
        )

        val cue = stacked.cues.single()
        assertEquals("second\nfirst", cue.text.toString())
        assertEquals(-1f, cue.line)
        assertEquals(Cue.LINE_TYPE_NUMBER, cue.lineType)
        // The block still carries the parser default, so the Position preset re-places it.
        assertEquals(
            Cue.DIMEN_UNSET,
            remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Bottom).line,
        )
    }

    @Test
    fun webvttCuesMedia3AlreadyNumberedStackInLineOrder() {
        // A whole WebVTT segment comes out of Media3 as -1 (earliest) and -2.
        val stacked = SimultaneousTextCueStacker().stack(
            group(webvttCue("later", line = -2f), webvttCue("earliest", line = -1f)),
        )

        val cue = stacked.cues.single()
        assertEquals("later\nearliest", cue.text.toString())
        assertEquals(-1f, cue.line)
    }

    @Test
    fun aCaptionThatStaysKeepsItsLineWhileTheLinesBesideItChange() {
        // A name card shown across two lines of dialogue, as in the issue's episode.
        val stacker = SimultaneousTextCueStacker()
        val first = subripCue("leading to a significant drop")
        val card = subripCue("[Authorized Representative] [Plaintiff]")
        val second = subripCue("and causing tremendous distress.")

        assertEquals("leading to a significant drop", stacker.stack(group(first)).cues.single().text.toString())
        assertEquals(
            "[Authorized Representative] [Plaintiff]\nleading to a significant drop",
            stacker.stack(group(first, card)).cues.single().text.toString(),
        )
        // The first line ends and the next one starts: the card stays on top
        // and the new line takes the freed bottom line.
        assertEquals(
            "[Authorized Representative] [Plaintiff]\nand causing tremendous distress.",
            stacker.stack(group(card, second)).cues.single().text.toString(),
        )
    }

    @Test
    fun aWebvttCaptionMedia3RenumbersKeepsItsLine() {
        // In a whole WebVTT segment Media3 numbers the earliest cue -1, so a card
        // drawn at -2 becomes -1 once the line under it ends.
        val stacker = SimultaneousTextCueStacker()
        stacker.stack(group(webvttCue("first line", line = -1f), webvttCue("card", line = -2f)))

        val cue = stacker.stack(
            group(webvttCue("card", line = -1f), webvttCue("next line", line = -2f)),
        ).cues.single()

        assertEquals("card\nnext line", cue.text.toString())
        assertEquals(-1f, cue.line)
    }

    @Test
    fun anAuthoredPlacementIsLeftWhereTheAuthorPutIt() {
        // SubRip {\an8}: a sign placed at the top of the picture.
        val sign = Cue.Builder()
            .setText("Courtroom 3")
            .setLine(0.08f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .build()
        val dialogue = subripCue("Please be seated.")

        val input = group(sign, dialogue)
        assertSame(input, SimultaneousTextCueStacker().stack(input))
    }

    @Test
    fun aSingleCueIsReturnedUnchanged() {
        val input = group(subripCue("only one"))
        assertSame(input, SimultaneousTextCueStacker().stack(input))
    }

    @Test
    fun anExactDuplicateIsShownOnce() {
        val stacked = SimultaneousTextCueStacker().stack(
            group(subripCue("same line"), subripCue("same line")),
        )

        assertEquals("same line", stacked.cues.single().text.toString())
    }

    @Test
    fun stylingInsideEachCueSurvivesTheStack() {
        val italic = SpannableString("whispered").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val text = SimultaneousTextCueStacker().stack(
            group(subripCue("spoken"), subripCue(italic)),
        ).cues.single().text as Spanned

        val spans = text.getSpans(0, text.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("whispered", text.subSequence(text.getSpanStart(spans[0]), text.getSpanEnd(spans[0])).toString())
        assertTrue(spans[0].style == Typeface.ITALIC)
    }
}
