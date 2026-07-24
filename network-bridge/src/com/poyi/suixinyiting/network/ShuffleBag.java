package com.poyi.suixinyiting.network;

import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Random;

public final class ShuffleBag {
    private final SharedPreferences prefs;
    private long playlistId;
    private long seed;
    private long[] bag = new long[0];
    private int cursor;

    public ShuffleBag(SharedPreferences prefs) { this.prefs = prefs; }

    public synchronized void load(long playlistId, long[] ids) {
        this.playlistId = playlistId;
        seed = prefs.getLong("shuffle_seed_" + playlistId, System.nanoTime());
        bag = Arrays.copyOf(ids, ids.length);
        shuffle(bag, new Random(seed));
        cursor = Math.min(prefs.getInt("shuffle_cursor_" + playlistId, 0), bag.length);
        long current = prefs.getLong("shuffle_current_" + playlistId, -1);
        if (current != -1) moveToCursor(current);
    }

    private void moveToCursor(long id) {
        for (int i = 0; i < bag.length; i++) if (bag[i] == id) {
            long tmp = bag[i]; bag[i] = bag[Math.max(0, cursor - 1)]; bag[Math.max(0, cursor - 1)] = tmp;
            return;
        }
    }

    public synchronized long next() {
        if (bag.length == 0) return -1;
        if (cursor >= bag.length) {
            seed = System.nanoTime();
            shuffle(bag, new Random(seed));
            cursor = 0;
        }
        long value = bag[cursor++];
        persist(value);
        return value;
    }

    public synchronized void select(long id) {
        if (bag.length == 0) return;
        int found = -1;
        for (int i = cursor; i < bag.length; i++) if (bag[i] == id) { found = i; break; }
        if (found < 0) for (int i = 0; i < cursor; i++) if (bag[i] == id) { found = i; break; }
        if (found >= 0) {
            int at = Math.min(cursor, bag.length - 1);
            long t = bag[at]; bag[at] = bag[found]; bag[found] = t;
            cursor = at + 1; persist(id);
        }
    }

    public synchronized long previous() {
        if (bag.length == 0) return -1;
        cursor = Math.max(1, cursor - 1);
        long value = bag[cursor - 1];
        persist(value);
        return value;
    }

    public synchronized long[] peekNext(int count) {
        int available = Math.max(0, Math.min(count, bag.length - cursor));
        long[] result = new long[available];
        if (available > 0) System.arraycopy(bag, cursor, result, 0, available);
        return result;
    }

    public synchronized long[] snapshot() { return Arrays.copyOf(bag, bag.length); }
    public synchronized int cursor() { return cursor; }
    public synchronized long current() {
        return cursor > 0 && cursor <= bag.length ? bag[cursor - 1] : -1;
    }

    private void persist(long current) {
        prefs.edit().putLong("shuffle_seed_" + playlistId, seed)
                .putInt("shuffle_cursor_" + playlistId, cursor)
                .putLong("shuffle_current_" + playlistId, current).apply();
    }

    static void shuffle(long[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long t = values[i]; values[i] = values[j]; values[j] = t;
        }
    }
}
