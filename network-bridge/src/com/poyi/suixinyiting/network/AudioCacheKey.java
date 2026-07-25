package com.poyi.suixinyiting.network;

import java.security.MessageDigest;
import java.util.Locale;

public final class AudioCacheKey {
    public final long trackId;
    public final String quality;
    public final String format;
    public final long contentLength;
    public final String validator;
    public final String id;

    public AudioCacheKey(long trackId, String quality, String format,
                         long contentLength, String validator) {
        this.trackId = trackId;
        this.quality = clean(quality);
        this.format = clean(format);
        this.contentLength = contentLength;
        this.validator = validator == null ? "" : validator.trim();
        this.id = digest(trackId + "|" + this.quality + "|" + this.format + "|"
                + contentLength + "|" + this.validator);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
