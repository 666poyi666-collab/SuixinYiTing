package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Active-lyric binary search boundaries (PLAY-002/003). */
public class LyricIndexTest {

    private static final long[] TIMES = {0, 1000, 2000, 3500, 8000};

    @Test public void beforeFirstTimestampHighlightsLineZero() {
        assertEquals(0, LyricIndex.at(TIMES, -50));
        assertEquals(0, LyricIndex.at(TIMES, 0));
        assertEquals(0, LyricIndex.at(TIMES, 999));
    }

    @Test public void exactTimestampSelectsThatLine() {
        assertEquals(1, LyricIndex.at(TIMES, 1000));
        assertEquals(3, LyricIndex.at(TIMES, 3500));
        assertEquals(4, LyricIndex.at(TIMES, 8000));
    }

    @Test public void betweenTimestampsSelectsThePriorLine() {
        assertEquals(1, LyricIndex.at(TIMES, 1500));
        assertEquals(2, LyricIndex.at(TIMES, 3499));
        assertEquals(3, LyricIndex.at(TIMES, 7999));
    }

    @Test public void afterLastTimestampStaysOnLastLine() {
        assertEquals(4, LyricIndex.at(TIMES, 99999));
    }

    @Test public void emptyOrNullReturnsZero() {
        assertEquals(0, LyricIndex.at(new long[0], 1234));
        assertEquals(0, LyricIndex.at(null, 1234));
    }

    @Test public void singleLineAlwaysZero() {
        assertEquals(0, LyricIndex.at(new long[]{500}, 0));
        assertEquals(0, LyricIndex.at(new long[]{500}, 10_000));
    }

    @Test public void matchesLinearScanAcrossManyPositions() {
        long[] times = new long[64];
        for (int i = 0; i < times.length; i++) times[i] = i * 250L;
        for (long pos = -100; pos < times.length * 250L + 100; pos += 37) {
            assertEquals("pos=" + pos, linear(times, pos), LyricIndex.at(times, pos));
        }
    }

    private static int linear(long[] times, long pos) {
        int result = 0;
        for (int i = 0; i < times.length; i++) if (times[i] <= pos) result = i;
        return result;
    }
}
