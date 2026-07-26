package com.poyi.suixinyiting.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LRC lyric text into time-sorted (timestamp, line) pairs.
 *
 * <p>Extracted and hardened from {@link NetworkStreamService}. The previous
 * inline parser had two real defects that this class fixes:
 *
 * <ul>
 *   <li><b>Multiple timestamps per line.</b> LRC allows a line to carry several
 *       tags for repeated lyrics, e.g. {@code [00:12.00][00:15.30]副歌}. The old
 *       regex captured only the first tag and folded the remaining {@code [...]}
 *       into the lyric text, losing the second timing and leaking a bracket into
 *       the display. Every leading tag now yields its own entry.
 *   <li><b>Unsorted output.</b> Multi-tag lines and metadata produce timestamps
 *       out of file order, but {@link LyricIndex} binary-searches and therefore
 *       requires ascending times. Entries are now sorted by timestamp, so the
 *       active-line lookup is always correct.
 * </ul>
 *
 * <p>Metadata tags ({@code [ti:]}, {@code [ar:]}, {@code [offset:]}, …) have a
 * non-numeric minute field and are ignored. Timed but empty lines (interludes)
 * are dropped, matching the original behaviour.
 */
public final class LrcParser {
    // A single leading [mm:ss] or [mm:ss.xxx] tag; applied repeatedly per line.
    private static final Pattern TAG = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    private LrcParser() {}

    public static final class Result {
        public final long[] times;
        public final String[] lines;

        Result(long[] times, String[] lines) {
            this.times = times;
            this.lines = lines;
        }
    }

    public static Result parse(String raw) {
        List<long[]> entries = new ArrayList<>(); // [time, index into texts]
        List<String> texts = new ArrayList<>();
        if (raw != null) {
            for (String row : raw.split("\\n")) {
                String rest = row.trim();
                List<Long> stamps = new ArrayList<>();
                Matcher matcher = TAG.matcher(rest);
                while (matcher.find()) {
                    long minute = parseLongSafe(matcher.group(1));
                    double second = parseDoubleSafe(matcher.group(2));
                    if (minute >= 0 && second >= 0) {
                        stamps.add(minute * 60000L + Math.round(second * 1000.0));
                    }
                    rest = rest.substring(matcher.end()).trim();
                    matcher = TAG.matcher(rest);
                }
                if (stamps.isEmpty() || rest.isEmpty()) continue;
                int textIndex = texts.size();
                texts.add(rest);
                for (long stamp : stamps) entries.add(new long[]{stamp, textIndex});
            }
        }
        // Stable sort by timestamp keeps same-time lines in file order.
        Collections.sort(entries, (a, b) -> Long.compare(a[0], b[0]));

        long[] times = new long[entries.size()];
        String[] lines = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            times[i] = entries.get(i)[0];
            lines[i] = texts.get((int) entries.get(i)[1]);
        }
        return new Result(times, lines);
    }

    private static long parseLongSafe(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return -1; }
    }

    private static double parseDoubleSafe(String value) {
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return -1; }
    }
}
