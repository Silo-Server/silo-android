package org.siloserver.silo.common.player

import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setLine(line, Cue.LINE_TYPE_NUMBER)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .build()

    /** A pop-on CEA-608 row as Media3's Cea608Decoder builds one: the bottom row is line -2. */
    private fun cea608Row(text: CharSequence, line: Float): Cue = Cue.Builder()
        .setText(text)
        .setTextAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLine(line, Cue.LINE_TYPE_NUMBER)
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
    fun aRubyAnnotationSurvivesTheStack() {
        // Silo strips embedded styling before drawing (setApplyEmbeddedStyles(false));
        // language features such as ruby survive that, so they must survive the join.
        val kanji = SpannableString("漢字").apply {
            setSpan(RubySpan("かんじ", TextAnnotation.POSITION_BEFORE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val text = SimultaneousTextCueStacker().stack(
            group(subripCue("spoken"), subripCue(kanji)),
        ).cues.single().text as Spanned

        val spans = text.getSpans(0, text.length, RubySpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("漢字", text.subSequence(text.getSpanStart(spans[0]), text.getSpanEnd(spans[0])).toString())
        assertEquals("かんじ", spans[0].rubyText)
    }

    @Test
    fun cea608RowsAreLeftWhereTheDecoderPutThem() {
        // A two-row pop-on caption: rows 14 and 15 come out of Media3 as -3 and -2.
        // Those are authored rows, not Media3's stacking numbers, and each row is
        // centred on its own.
        val input = group(cea608Row("I'm going", -3f), cea608Row("HOME.", -2f))
        assertSame(input, SimultaneousTextCueStacker().stack(input))
    }

    @Test
    fun anAuthoredWebvttLineBesideADefaultCueIsLeftAlone() {
        // line:-3 is the author's choice; only Media3's own -1, -2, ... run is stacked.
        val input = group(webvttCue("dialogue"), webvttCue("caption higher up", line = -3f))
        assertSame(input, SimultaneousTextCueStacker().stack(input))
    }

    @Test
    fun anEmptyCueIsNotJoinedAsABlankLine() {
        // A SubRip cue made only of tags such as {\fad(200,200)} parses to empty text,
        // which the painter skips; joined it would push the real line up.
        val input = group(subripCue(""), subripCue("real line"))
        assertSame(input, SimultaneousTextCueStacker().stack(input))
    }

    @Test
    fun aCaptionLeftAloneKeepsTheBottomLineWhenTheNextOneArrives() {
        // A gap between two dialogue lines leaves the card alone for a moment; it
        // drops to the bottom like any single caption and stays there, so it moves
        // once instead of dropping and climbing back at every gap.
        val stacker = SimultaneousTextCueStacker()
        val first = subripCue("first line")
        val card = subripCue("[NAME CARD]")
        val second = subripCue("second line")

        assertEquals("[NAME CARD]\nfirst line", stacker.stack(group(first, card)).cues.single().text.toString())
        assertEquals("[NAME CARD]", stacker.stack(group(card)).cues.single().text.toString())
        assertEquals("second line\n[NAME CARD]", stacker.stack(group(card, second)).cues.single().text.toString())
    }

    @Test
    fun theJoinedCueTakesTheFirstDefaultCuesPlaceAndOtherCuesKeepTheirs() {
        val sign = Cue.Builder()
            .setText("Courtroom 3")
            .setLine(0.08f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()

        val cues = SimultaneousTextCueStacker().stack(
            group(subripCue("earlier"), sign, subripCue("later")),
        ).cues

        assertEquals(2, cues.size)
        assertEquals("later\nearlier", cues[0].text.toString())
        assertSame(sign, cues[1])
    }

    @Test
    fun aJoinedWebvttBlockFollowsTheTopPreset() {
        val cue = SimultaneousTextCueStacker().stack(
            group(webvttCue("first"), webvttCue("second")),
        ).cues.single()

        val top = remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Top)
        assertEquals(SUBTITLE_TOP_LINE_FRACTION, top.line)
        assertEquals(Cue.LINE_TYPE_FRACTION, top.lineType)
    }
}
