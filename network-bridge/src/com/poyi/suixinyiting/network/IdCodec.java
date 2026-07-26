package com.poyi.suixinyiting.network;

/**
 * Comma-separated encoding for the persisted play-queue id list.
 *
 * <p>Extracted from {@link NetworkStreamService} so the round-trip and the
 * malformed-input handling can be unit-tested without standing up a Service.
 * Behaviour is intentionally identical to the original private helpers:
 * non-positive and unparseable entries are dropped on decode, so a corrupted
 * preference can never inject a 0/negative track id into the shuffle pool.
 */
public final class IdCodec {
    private IdCodec() {}

    public static String encode(long[] ids) {
        if (ids == null || ids.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (long id : ids) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    public static long[] decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return new long[0];
        String[] parts = encoded.split(",");
        long[] values = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                long value = Long.parseLong(part.trim());
                if (value > 0) values[count++] = value;
            } catch (NumberFormatException ignored) {
                // A corrupted segment is skipped rather than aborting the whole
                // queue restore.
            }
        }
        if (count == values.length) return values;
        long[] compact = new long[count];
        System.arraycopy(values, 0, compact, 0, count);
        return compact;
    }
}
