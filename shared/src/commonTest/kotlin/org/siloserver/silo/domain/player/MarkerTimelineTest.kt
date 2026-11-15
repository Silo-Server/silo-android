package org.siloserver.silo.domain.player

import org.siloserver.silo.model.catalog.MarkerSegment
import org.siloserver.silo.model.catalog.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkerTimelineTest {
    // Game of Thrones S02E03 as TheIntroDB reports it: a mid-credits scene
    // plays between two credits ranges.
    private val segments = listOf(
        MarkerSegment("recap", 20.0, 107.0),
        MarkerSegment("intro", 108.0, 220.0),
        MarkerSegment("credits", 2975.5, 3167.4),
        MarkerSegment("credits", 3189.0, 3300.0),
    )
    private val timeline = MarkerTimeline.resolve(
        segments = segments,
        intro = TimeRange(108.0, 220.0),
        credits = TimeRange(2975.5, 3167.4),
        recap = TimeRange(20.0, 107.0),
        preview = null,
    )

    @Test fun eachCreditsRangeIsActiveOnItsOwnAndTheGapIsNot() {
        assertEquals(TimeRange(2975.5, 3167.4), timeline.active(MarkerKind.Credits, 3000.0))
        assertNull(timeline.active(MarkerKind.Credits, 3167.4))
        assertNull(timeline.active(MarkerKind.Credits, 3180.0))
        assertEquals(TimeRange(3189.0, 3300.0), timeline.active(MarkerKind.Credits, 3189.0))
        assertEquals(TimeRange(3189.0, 3300.0), timeline.active(MarkerKind.Credits, 3250.0))
        assertNull(timeline.active(MarkerKind.Credits, 2975.4))
        assertEquals(TimeRange(20.0, 107.0), timeline.active(MarkerKind.Recap, 50.0))
        assertNull(timeline.active(MarkerKind.Recap, 3000.0))
    }

    @Test fun singularFieldsStandInWhenTheServerSendsNoSegments() {
        val legacy = MarkerTimeline.resolve(
            segments = null,
            intro = TimeRange(0.0, 30.0),
            credits = TimeRange(1200.0, 1260.0),
            recap = null,
            preview = TimeRange(1260.0, 1290.0),
        )
        assertEquals(TimeRange(0.0, 30.0), legacy.active(MarkerKind.Intro, 10.0))
        assertEquals(TimeRange(1200.0, 1260.0), legacy.active(MarkerKind.Credits, 1210.0))
        assertNull(legacy.active(MarkerKind.Recap, 10.0))
    }

    @Test fun anEmptySegmentListMeansNoMarkersEvenWithStaleSingularFields() {
        val cleared = MarkerTimeline.resolve(emptyList(), intro = TimeRange(0.0, 30.0), credits = null, recap = null, preview = null)
        assertNull(cleared.active(MarkerKind.Intro, 10.0))
    }

    @Test fun unsortedAndInvalidSegmentsResolveToOrderedValidRanges() {
        val messy = MarkerTimeline.resolve(
            segments = listOf(
                MarkerSegment("credits", 3189.0, 3300.0),
                MarkerSegment("credits", 50.0, 40.0),
                MarkerSegment("credits", -5.0, 10.0),
                MarkerSegment("credits", 2975.5, 3167.4),
                MarkerSegment("credits", 100.0, Double.POSITIVE_INFINITY),
                MarkerSegment("chapter", 0.0, 3300.0),
            ),
            intro = null, credits = null, recap = null, preview = null,
        )
        assertEquals(
            listOf(TimeRange(2975.5, 3167.4), TimeRange(3189.0, 3300.0)),
            messy.ranges(MarkerKind.Credits),
        )
        assertNull(messy.active(MarkerKind.Credits, 5.0))
        assertEquals(TimeRange(3189.0, 3300.0), messy.active(MarkerKind.Credits, 3200.0))
    }

    @Test fun introPromptFollowsTheOccurrenceThatLastStarted() {
        val twoIntros = MarkerTimeline.resolve(
            segments = listOf(MarkerSegment("intro", 600.0, 660.0), MarkerSegment("intro", 30.0, 90.0)),
            intro = null, credits = null, recap = null, preview = null,
        )
        // Before any starts, the first: an undo stays up when the viewer
        // scrubs back past the start of the intro it skipped.
        assertEquals(TimeRange(30.0, 90.0), twoIntros.occurrenceAt(MarkerKind.Intro, 10.0))
        assertNull(MarkerTimeline.EMPTY.occurrenceAt(MarkerKind.Intro, 10.0))
        assertEquals(TimeRange(30.0, 90.0), twoIntros.occurrenceAt(MarkerKind.Intro, 40.0))
        // After an automatic skip lands on the end, the undo stays anchored to it.
        assertEquals(TimeRange(30.0, 90.0), twoIntros.occurrenceAt(MarkerKind.Intro, 90.0))
        assertEquals(TimeRange(30.0, 90.0), twoIntros.occurrenceAt(MarkerKind.Intro, 300.0))
        assertEquals(TimeRange(600.0, 660.0), twoIntros.occurrenceAt(MarkerKind.Intro, 600.0))
    }

    @Test fun upNextWaitsForTheLastCreditsRangeSoAMidCreditsScenePlays() {
        assertEquals(3189.0, timeline.upNextCreditsStart())
        val touching = MarkerTimeline.resolve(
            segments = listOf(MarkerSegment("credits", 1200.0, 1250.0), MarkerSegment("credits", 1250.5, 1300.0)),
            intro = null, credits = null, recap = null, preview = null,
        )
        assertEquals(1200.0, touching.upNextCreditsStart())
        val single = MarkerTimeline.resolve(null, intro = null, credits = TimeRange(1200.0, 1260.0), recap = null, preview = null)
        assertEquals(1200.0, single.upNextCreditsStart())
        assertNull(MarkerTimeline.EMPTY.upNextCreditsStart())
    }
}
