package com.poyi.suixinyiting.network;

/**
 * Binary search for the active lyric line at a playback position.
 *
 * <p>Extracted from {@link NetworkStreamService} so the boundary behaviour is
 * unit-tested. {@code times} must be ascending (the parser guarantees this).
 * Returns the index of the last line whose timestamp is {@code <= position};
 * before the first timestamp it returns 0, so an empty or pre-roll state still
 * highlights the opening line rather than nothing.
 */
public final class LyricIndex {
    private LyricIndex() {}

    public static int at(long[] times, long position) {
        if (times == null || times.length == 0) return 0;
        int low = 0;
        int high = times.length - 1;
        int result = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= position) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }
}
