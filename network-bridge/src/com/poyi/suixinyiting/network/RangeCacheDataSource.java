package com.poyi.suixinyiting.network;

import android.media.MediaDataSource;
import android.util.Log;
import java.io.RandomAccessFile;

public final class RangeCacheDataSource extends MediaDataSource {
    private final AudioCacheStore store;
    private final AudioCacheStore.Entry entry;
    private final String url;
    private final RandomAccessFile file;
    private volatile boolean closed;

    public RangeCacheDataSource(AudioCacheStore store, long trackId, StreamVariant variant)
            throws Exception {
        this(store, store.openOnline(trackId, variant), variant.url);
    }

    public static RangeCacheDataSource openOffline(AudioCacheStore store, long trackId)
            throws Exception {
        AudioCacheStore.Entry entry = store.findComplete(trackId);
        if (entry == null || !entry.file.exists()) return null;
        return new RangeCacheDataSource(store, entry, null);
    }

    public static RangeCacheDataSource openCached(AudioCacheStore store, long trackId)
            throws Exception {
        AudioCacheStore.Entry entry = store.findCached(trackId);
        if (entry == null || !entry.file.exists()) return null;
        return new RangeCacheDataSource(store, entry, null);
    }

    private RangeCacheDataSource(AudioCacheStore store, AudioCacheStore.Entry entry, String url)
            throws Exception {
        this.store = store; this.entry = entry; this.url = url;
        this.file = new RandomAccessFile(entry.file, "r");
        store.pin(entry);
        Log.i("SuixinCache", "open track=" + entry.key.trackId + " complete="
                + entry.isComplete() + " cached=" + entry.downloadedBytes());
    }

    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int count) {
        if (closed || position >= entry.key.contentLength) return -1;
        int wanted = (int) Math.min(count, entry.key.contentLength - position);
        try {
            int first = (int) (position / AudioCacheStore.BLOCK_BYTES);
            int last = (int) ((position + wanted - 1) / AudioCacheStore.BLOCK_BYTES);
            for (int block = first; block <= last; block++) {
                if (!store.ensureBlock(entry, url, block, false)) {
                    if (url != null && !url.isEmpty()) {
                        Log.i("SuixinCache", "direct range track=" + entry.key.trackId
                                + " position=" + position + " count=" + wanted);
                        return store.readDirect(url, position, buffer, offset, wanted,
                                entry.key.contentLength);
                    }
                    Log.w("SuixinCache", "offline gap track=" + entry.key.trackId
                            + " block=" + block);
                    return -1;
                }
            }
            file.seek(position);
            return file.read(buffer, offset, wanted);
        } catch (Exception e) {
            Log.e("SuixinCache", "read failed track=" + entry.key.trackId
                    + " position=" + position, e);
            return -1;
        }
    }

    public AudioCacheStore.Entry entry() { return entry; }
    @Override public long getSize() { return entry.key.contentLength; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { file.close(); } catch (Exception ignored) {}
        store.unpin(entry);
    }
}
