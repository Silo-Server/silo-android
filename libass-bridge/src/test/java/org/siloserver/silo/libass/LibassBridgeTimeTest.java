package org.siloserver.silo.libass;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LibassBridgeTimeTest {
    private static final long MEDIA3_OFFSET_US = 1_000_000_000_000L;

    @Test
    public void positiveSubtitleOffsetDelaysLibassFrame() {
        assertEquals(
                4_500_000L,
                LibassBridge.adjustedPositionUs(MEDIA3_OFFSET_US + 5_000_000L, 500_000L)
        );
    }

    @Test
    public void negativeSubtitleOffsetAdvancesLibassFrame() {
        assertEquals(
                5_500_000L,
                LibassBridge.adjustedPositionUs(MEDIA3_OFFSET_US + 5_000_000L, -500_000L)
        );
    }
}
