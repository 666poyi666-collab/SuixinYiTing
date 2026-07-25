package com.poyi.suixinyiting.network;

public final class CacheStats {
    public final long bytes;
    public final long limitBytes;
    public final int files;
    public final int complete;
    public final int partial;
    public final long clearedBytes;
    public final long pendingBytes;
    public final long failedBytes;

    public CacheStats(long bytes, long limitBytes, int files, int complete, int partial,
                      long clearedBytes, long pendingBytes, long failedBytes) {
        this.bytes = bytes;
        this.limitBytes = limitBytes;
        this.files = files;
        this.complete = complete;
        this.partial = partial;
        this.clearedBytes = clearedBytes;
        this.pendingBytes = pendingBytes;
        this.failedBytes = failedBytes;
    }
}
