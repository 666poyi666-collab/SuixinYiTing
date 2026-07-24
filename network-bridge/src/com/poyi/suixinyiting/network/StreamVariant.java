package com.poyi.suixinyiting.network;

public final class StreamVariant {
    public final String url;
    public final String requestedLevel;
    public final String actualLevel;
    public final int bitrate;
    public final String format;
    public final long expiresAt;
    public final String fallbackReason;

    public StreamVariant(String url, String requestedLevel, String actualLevel,
                         int bitrate, String format, long expiresAt, String fallbackReason) {
        this.url = url;
        this.requestedLevel = requestedLevel;
        this.actualLevel = actualLevel;
        this.bitrate = bitrate;
        this.format = format;
        this.expiresAt = expiresAt;
        this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
    }
}
