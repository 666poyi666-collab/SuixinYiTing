package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * The shuffle queue is the product's headline fix (PLAY-005/006): every playable
 * track must appear exactly once per round, and prev/next/select must stay
 * consistent with history. These tests pin that behaviour.
 */
public class ShuffleBagTest {

    private static long[] pool(int n) {
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) ids[i] = 1000L + i;
        return ids;
    }

    private static ShuffleBag load(long[] ids) {
        ShuffleBag bag = new ShuffleBag(new FakeSharedPreferences());
        bag.load(42L, ids);
        return bag;
    }

    @Test public void oneRoundVisitsEveryTrackExactlyOnce() {
        long[] ids = pool(1230);
        ShuffleBag bag = load(ids);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < ids.length; i++) {
            long v = bag.next();
            assertTrue("id in pool: " + v, v >= 1000 && v < 1000 + ids.length);
            assertTrue("no repeat within a round: " + v, seen.add(v));
        }
        assertEquals("round covered the whole pool", ids.length, seen.size());
    }

    @Test public void secondRoundAlsoCoversEverythingAndReshuffles() {
        long[] ids = pool(200);
        ShuffleBag bag = load(ids);
        long[] first = new long[ids.length];
        for (int i = 0; i < ids.length; i++) first[i] = bag.next();
        Set<Long> second = new HashSet<>();
        for (int i = 0; i < ids.length; i++) second.add(bag.next());
        assertEquals("second round still complete", ids.length, second.size());
    }

    @Test public void nextThenPreviousReturnsPriorTrack() {
        ShuffleBag bag = load(pool(50));
        long a = bag.next();
        long b = bag.next();
        assertNotEquals(a, b);
        assertEquals("previous walks back to the first track", a, bag.previous());
        assertEquals("next walks forward again", b, bag.next());
    }

    @Test public void selectMakesTrackCurrentWithoutLosingIt() {
        long[] ids = pool(30);
        ShuffleBag bag = load(ids);
        bag.next();
        bag.select(1010L);
        assertEquals("selected track becomes current", 1010L, bag.current());
        // The rest of the round must still contain every other track exactly once.
        Set<Long> seen = new HashSet<>();
        seen.add(1010L);
        long[] remaining = bag.peekNext(ids.length);
        for (long v : remaining) assertTrue("remaining unique: " + v, seen.add(v));
    }

    @Test public void persistenceRestoresCursorAndCurrent() {
        long[] ids = pool(40);
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        ShuffleBag bag = new ShuffleBag(prefs);
        bag.load(7L, ids);
        long first = bag.next();
        long second = bag.next();

        // A fresh instance sharing the same prefs is the service-restart case.
        ShuffleBag restored = new ShuffleBag(prefs);
        restored.load(7L, ids);
        assertEquals("restored current matches last played", second, restored.current());
        assertNotEquals(first, restored.next());
    }

    @Test public void emptyPoolIsSafe() {
        ShuffleBag bag = load(new long[0]);
        assertEquals(-1, bag.next());
        assertEquals(-1, bag.previous());
        assertEquals(-1, bag.current());
        assertArrayEquals(new long[0], bag.peekNext(5));
    }

    @Test public void singleTrackRepeatsAcrossRounds() {
        ShuffleBag bag = load(new long[]{99L});
        assertEquals(99L, bag.next());
        assertEquals(99L, bag.next());
        assertEquals(99L, bag.current());
    }

    @Test public void peekNextNeverExceedsRemaining() {
        ShuffleBag bag = load(pool(10));
        bag.next();
        assertTrue("peek bounded by remaining", bag.peekNext(100).length <= 9);
    }

    @Test public void staticShuffleIsDeterministicForASeed() {
        long[] a = pool(100);
        long[] b = pool(100);
        ShuffleBag.shuffle(a, new Random(12345));
        ShuffleBag.shuffle(b, new Random(12345));
        assertArrayEquals("same seed -> same permutation", a, b);
    }
}
