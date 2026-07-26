package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * LRC parsing (PLAY-002/003). Guards the two defects the old inline parser had:
 * multi-timestamp lines and unsorted output that broke {@link LyricIndex}'s
 * binary-search precondition.
 */
public class LrcParserTest {

    @Test public void parsesBasicTimedLines() {
        LrcParser.Result r = LrcParser.parse("[00:01.00]hello\n[00:03.50]world");
        assertArrayEquals(new long[]{1000, 3500}, r.times);
        assertArrayEquals(new String[]{"hello", "world"}, r.lines);
    }

    @Test public void expandsMultipleTimestampsOnOneLine() {
        // [00:12][00:15]chorus -> two entries, same text, both timings kept.
        LrcParser.Result r = LrcParser.parse("[00:12.00][00:15.30]chorus");
        assertArrayEquals(new long[]{12000, 15300}, r.times);
        assertArrayEquals(new String[]{"chorus", "chorus"}, r.lines);
        for (String line : r.lines) assertTrue("no bracket leaked into text",
                line.indexOf('[') < 0);
    }

    @Test public void sortsByTimestampSoBinarySearchHolds() {
        // Multi-tag and file-order can interleave; output must be ascending.
        LrcParser.Result r = LrcParser.parse(
                "[00:20.00]late\n[00:05.00][00:25.00]repeat\n[00:10.00]mid");
        for (int i = 1; i < r.times.length; i++) {
            assertTrue("ascending at " + i, r.times[i] >= r.times[i - 1]);
        }
        assertArrayEquals(new long[]{5000, 10000, 20000, 25000}, r.times);
        assertArrayEquals(new String[]{"repeat", "mid", "late", "repeat"}, r.lines);
    }

    @Test public void skipsMetadataTags() {
        LrcParser.Result r = LrcParser.parse(
                "[ti:Song]\n[ar:Artist]\n[al:Album]\n[offset:500]\n[00:02.00]real");
        assertArrayEquals(new long[]{2000}, r.times);
        assertArrayEquals(new String[]{"real"}, r.lines);
    }

    @Test public void dropsTimedButEmptyLines() {
        LrcParser.Result r = LrcParser.parse("[00:01.00]\n[00:02.00]   \n[00:03.00]sung");
        assertArrayEquals(new long[]{3000}, r.times);
        assertArrayEquals(new String[]{"sung"}, r.lines);
    }

    @Test public void handlesFractionlessAndThreeDigitFractions() {
        LrcParser.Result r = LrcParser.parse("[01:00]a\n[00:02.5]b\n[00:03.123]c");
        assertArrayEquals(new long[]{60000, 2500, 3123},
                new long[]{r.times[2], r.times[0], r.times[1]});
    }

    @Test public void ignoresUntaggedAndMalformedLines() {
        LrcParser.Result r = LrcParser.parse("plain text\n[bad]nope\n[00:04.00]ok\n");
        assertArrayEquals(new long[]{4000}, r.times);
        assertArrayEquals(new String[]{"ok"}, r.lines);
    }

    @Test public void nullAndEmptyYieldEmpty() {
        assertEquals(0, LrcParser.parse(null).times.length);
        assertEquals(0, LrcParser.parse("").times.length);
        assertEquals(0, LrcParser.parse("\n\n").lines.length);
    }

    @Test public void minutesOverSixtyAreLinearNotClamped() {
        // 75:00 = 4500s; some long tracks/podcasts exceed 60 minutes.
        LrcParser.Result r = LrcParser.parse("[75:00.00]late");
        assertArrayEquals(new long[]{4_500_000}, r.times);
    }

    @Test public void outputFeedsLyricIndexConsistently() {
        LrcParser.Result r = LrcParser.parse(
                "[00:01.00]one\n[00:02.00][00:04.00]two\n[00:03.00]three");
        // times sorted: 1000 one, 2000 two, 3000 three, 4000 two
        assertEquals("one", r.lines[LyricIndex.at(r.times, 1500)]);
        assertEquals("two", r.lines[LyricIndex.at(r.times, 2500)]);
        assertEquals("three", r.lines[LyricIndex.at(r.times, 3500)]);
        assertEquals("two", r.lines[LyricIndex.at(r.times, 9000)]);
    }
}
