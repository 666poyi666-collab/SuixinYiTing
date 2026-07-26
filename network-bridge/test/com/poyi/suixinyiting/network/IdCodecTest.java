package com.poyi.suixinyiting.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Persisted queue codec: a corrupted preference must never poison the pool. */
public class IdCodecTest {

    @Test public void roundTripPreservesOrder() {
        long[] ids = {5, 4, 3, 2, 1, 900000000000L};
        assertArrayEquals(ids, IdCodec.decode(IdCodec.encode(ids)));
    }

    @Test public void emptyAndNullEncodeToEmpty() {
        assertEquals("", IdCodec.encode(new long[0]));
        assertEquals("", IdCodec.encode(null));
    }

    @Test public void emptyBlankNullDecodeToEmptyArray() {
        assertArrayEquals(new long[0], IdCodec.decode(""));
        assertArrayEquals(new long[0], IdCodec.decode("   "));
        assertArrayEquals(new long[0], IdCodec.decode(null));
    }

    @Test public void malformedSegmentsAreDropped() {
        assertArrayEquals(new long[]{1, 2, 3}, IdCodec.decode("1,abc,2, ,3"));
    }

    @Test public void nonPositiveIdsAreDropped() {
        // 0 and negatives can never be valid netease track ids.
        assertArrayEquals(new long[]{7}, IdCodec.decode("0,-1,7,-999"));
    }

    @Test public void surroundingWhitespaceIsTrimmed() {
        assertArrayEquals(new long[]{10, 20}, IdCodec.decode(" 10 , 20 "));
    }

    @Test public void allGarbageYieldsEmpty() {
        assertArrayEquals(new long[0], IdCodec.decode("x,y,z,,-3,0"));
    }
}
