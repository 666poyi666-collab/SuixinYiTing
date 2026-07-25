package com.poyi.suixinyiting.network;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.StatFs;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AudioCacheStore extends SQLiteOpenHelper {
    public static final int BLOCK_BYTES = 256 * 1024;
    public static final long MB = 1024L * 1024L;
    private static final String TAG = "SuixinCache";
    private static AudioCacheStore instance;
    private final File root;
    private final SharedPreferences settings;
    private final Map<String,Object> locks = new HashMap<>();
    private final Map<String,Integer> pins = new HashMap<>();
    private final Set<String> pendingDelete = new HashSet<>();
    private final Object downloadGate = new Object();
    private int playbackWaiters;
    private boolean downloadActive;

    public static synchronized AudioCacheStore get(Context context) {
        if (instance == null) {
            instance = new AudioCacheStore(context.getApplicationContext());
            instance.purgePending();
        }
        return instance;
    }

    private AudioCacheStore(Context context) {
        super(context, "audio_cache.db", null, 2);
        root = new File(context.getCacheDir(), "network_audio_v2");
        if (!root.exists()) root.mkdirs();
        settings = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE);
        migrateLegacy(new File(context.getCacheDir(), "network_audio"));
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cache_entry (cache_id TEXT PRIMARY KEY, track_id INTEGER NOT NULL,"
                + " quality TEXT NOT NULL, format TEXT NOT NULL, content_length INTEGER NOT NULL,"
                + " validator TEXT NOT NULL, block_map BLOB, downloaded_bytes INTEGER NOT NULL,"
                + " complete INTEGER NOT NULL, last_access INTEGER NOT NULL,"
                + " pending_delete INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX cache_track ON cache_entry(track_id,complete,last_access)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE cache_entry ADD COLUMN pending_delete "
                + "INTEGER NOT NULL DEFAULT 0");
    }

    public static final class Probe {
        public final long length;
        public final String validator;
        public final boolean ranges;
        Probe(long length, String validator, boolean ranges) {
            this.length = length; this.validator = validator; this.ranges = ranges;
        }
    }

    public static final class Entry {
        public final AudioCacheKey key;
        public final File file;
        private BitSet blocks;
        private long downloadedBytes;
        private boolean complete;
        Entry(AudioCacheKey key, File file, BitSet blocks, long downloadedBytes,
              boolean complete) {
            this.key = key; this.file = file; this.blocks = blocks;
            this.downloadedBytes = downloadedBytes; this.complete = complete;
        }
        public synchronized boolean hasBlock(int block) { return blocks.get(block); }
        public synchronized boolean isComplete() { return complete; }
        public synchronized long downloadedBytes() { return downloadedBytes; }
    }

    public Probe probe(String url) throws Exception {
        HttpURLConnection connection = open(url, 0, 0);
        try {
            int code = connection.getResponseCode();
            if (code != 206 && code != 200)
                throw new IllegalStateException("音频探测响应 " + code);
            long length;
            boolean ranges = code == 206;
            if (ranges) {
                long[] parsed = parseContentRange(connection.getHeaderField("Content-Range"));
                if (parsed[0] != 0 || parsed[1] != 0)
                    throw new IllegalStateException("音频 Range 探测不匹配");
                length = parsed[2];
            } else {
                length = connection.getContentLengthLong();
            }
            if (length <= 0) throw new IllegalStateException("音频长度未知");
            String etag = connection.getHeaderField("ETag");
            String modified = connection.getHeaderField("Last-Modified");
            String validator = etag != null && !etag.isEmpty() ? "e:" + etag
                    : modified != null && !modified.isEmpty() ? "m:" + modified : "";
            closeBody(connection);
            return new Probe(length, validator, ranges);
        } finally { connection.disconnect(); }
    }

    public Entry openOnline(long trackId, StreamVariant variant) throws Exception {
        Probe probe = probe(variant.url);
        AudioCacheKey key = new AudioCacheKey(trackId, variant.actualLevel, variant.format,
                probe.length, probe.validator);
        return loadOrCreate(key);
    }

    public synchronized Entry findComplete(long trackId) {
        Cursor cursor = getReadableDatabase().query("cache_entry", null,
                "track_id=? AND complete=1 AND pending_delete=0",
                new String[]{Long.toString(trackId)},
                null, null, "last_access DESC", "1");
        try { return cursor.moveToFirst() ? fromCursor(cursor) : null; }
        finally { cursor.close(); }
    }

    public synchronized Entry findCached(long trackId) {
        Cursor cursor = getReadableDatabase().query("cache_entry", null,
                "track_id=? AND downloaded_bytes>0 AND pending_delete=0",
                new String[]{Long.toString(trackId)},
                null, null, "complete DESC,last_access DESC", "1");
        try { return cursor.moveToFirst() ? fromCursor(cursor) : null; }
        finally { cursor.close(); }
    }

    public void pin(Entry entry) {
        synchronized (pins) {
            Integer count = pins.get(entry.key.id);
            pins.put(entry.key.id, count == null ? 1 : count + 1);
        }
        touch(entry.key.id);
    }

    public void unpin(Entry entry) {
        boolean delete = false;
        synchronized (pins) {
            Integer count = pins.get(entry.key.id);
            if (count == null || count <= 1) {
                pins.remove(entry.key.id);
                delete = pendingDelete.remove(entry.key.id);
            } else pins.put(entry.key.id, count - 1);
        }
        if (delete) deleteEntry(entry.key.id);
    }

    public boolean ensureBlock(Entry entry, String url, int block, boolean prefetch)
            throws Exception {
        if (entry.hasBlock(block)) { touch(entry.key.id); return true; }
        if (isPending(entry.key.id)) return false;
        if (url == null || url.isEmpty()) return false;
        synchronized (lock(entry.key.id)) {
            reload(entry);
            if (entry.hasBlock(block)) return true;
            int expected = expectedBytes(entry.key.contentLength, block);
            if (!reserve(expected, entry.key.id)) return false;
            acquireDownload(prefetch);
            try { fetch(entry, url, block, expected); }
            finally { releaseDownload(); }
            return true;
        }
    }

    public boolean fill(Entry entry, String url, Cancellation cancellation, long byteBudget)
            throws Exception {
        long startedBytes = entry.downloadedBytes();
        int count = blockCount(entry.key.contentLength);
        for (int block = 0; block < count; block++) {
            if (cancellation != null && cancellation.cancelled()) return false;
            if (entry.hasBlock(block)) continue;
            long consumed = entry.downloadedBytes() - startedBytes;
            int expected = expectedBytes(entry.key.contentLength, block);
            if (byteBudget > 0 && consumed + expected > byteBudget) return false;
            if (!ensureBlock(entry, url, block, true)) return false;
        }
        return entry.isComplete();
    }

    public int readDirect(String url, long position, byte[] buffer, int offset, int count,
                          long contentLength) throws Exception {
        if (url == null || url.isEmpty() || count <= 0) return -1;
        acquireDownload(false);
        try { return readDirectLocked(url, position, buffer, offset, count, contentLength); }
        finally { releaseDownload(); }
    }

    private int readDirectLocked(String url, long position, byte[] buffer, int offset, int count,
                                 long contentLength) throws Exception {
        long end = position + count - 1;
        HttpURLConnection connection = open(url, position, end);
        InputStream input = null;
        try {
            int code = connection.getResponseCode();
            boolean full = code == 200 && position == 0 && count == contentLength;
            if (code != 206 && !full)
                throw new IllegalStateException("直连 Range 响应 " + code);
            if (code == 206) {
                long[] range = parseContentRange(connection.getHeaderField("Content-Range"));
                if (range[0] != position || range[1] != end || range[2] != contentLength)
                    throw new IllegalStateException("直连 Content-Range 不匹配");
            } else if (connection.getContentLengthLong() != contentLength) {
                throw new IllegalStateException("直连完整音频长度不匹配");
            }
            input = connection.getInputStream();
            int total = 0;
            while (total < count) {
                int read = input.read(buffer, offset + total, count - total);
                if (read < 0) break;
                total += read;
            }
            if (total != count || input.read() != -1)
                throw new IllegalStateException("直连音频字节数不匹配");
            return total;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
            connection.disconnect();
        }
    }

    public interface Cancellation { boolean cancelled(); }

    private void acquireDownload(boolean prefetch) throws InterruptedException {
        synchronized (downloadGate) {
            if (!prefetch) playbackWaiters++;
            try {
                while (downloadActive || (prefetch && playbackWaiters > 0))
                    downloadGate.wait();
                downloadActive = true;
            } finally {
                if (!prefetch) playbackWaiters--;
            }
        }
    }

    private void releaseDownload() {
        synchronized (downloadGate) {
            downloadActive = false;
            downloadGate.notifyAll();
        }
    }

    public synchronized CacheStats stats() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(downloaded_bytes),0),COUNT(*),"
                + "COALESCE(SUM(complete),0) FROM cache_entry", null);
        try {
            cursor.moveToFirst();
            long bytes = cursor.getLong(0); int files = cursor.getInt(1);
            int complete = cursor.getInt(2);
            return new CacheStats(bytes, limitBytes(), files, complete, files - complete,
                    0, 0, 0);
        } finally { cursor.close(); }
    }

    public synchronized CacheStats clearInactive() {
        CacheStats before = stats();
        long pending = 0;
        long failed = 0;
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<Long> sizes = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("cache_entry",
                new String[]{"cache_id","downloaded_bytes"}, null, null,
                null, null, null);
        try {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0)); sizes.add(cursor.getLong(1));
            }
        } finally { cursor.close(); }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i); long bytes = sizes.get(i);
            if (isPinned(id)) {
                synchronized (pins) { pendingDelete.add(id); }
                ContentValues marker = new ContentValues(); marker.put("pending_delete", 1);
                getWritableDatabase().update("cache_entry", marker, "cache_id=?",
                        new String[]{id});
                pending += bytes;
            } else if (!deleteEntry(id)) failed += bytes;
        }
        CacheStats after = stats();
        return new CacheStats(after.bytes, after.limitBytes, after.files, after.complete,
                after.partial, Math.max(0, before.bytes - after.bytes), pending, failed);
    }

    public long limitBytes() {
        return settings.getLong("audio_cache_limit", 512L * MB);
    }

    public synchronized void enforceLimit() { trimTo(limitBytes(), null); }

    public static int blockCount(long size) {
        return (int) ((size + BLOCK_BYTES - 1) / BLOCK_BYTES);
    }

    private Entry loadOrCreate(AudioCacheKey key) {
        synchronized (lock(key.id)) {
            Cursor cursor = getReadableDatabase().query("cache_entry", null,
                    "cache_id=? AND pending_delete=0", new String[]{key.id}, null, null, null);
            try { if (cursor.moveToFirst()) return fromCursor(cursor); }
            finally { cursor.close(); }
            ContentValues values = values(key, new BitSet(), 0, false);
            getWritableDatabase().insertOrThrow("cache_entry", null, values);
            File data = file(key.id);
            try {
                if (!data.exists() && !data.createNewFile())
                    throw new IllegalStateException("缓存文件创建失败");
            } catch (Exception error) {
                getWritableDatabase().delete("cache_entry", "cache_id=?",
                        new String[]{key.id});
                throw new IllegalStateException("缓存文件创建失败", error);
            }
            return new Entry(key, data, new BitSet(), 0, false);
        }
    }

    private Entry fromCursor(Cursor cursor) {
        AudioCacheKey key = new AudioCacheKey(cursor.getLong(cursor.getColumnIndex("track_id")),
                cursor.getString(cursor.getColumnIndex("quality")),
                cursor.getString(cursor.getColumnIndex("format")),
                cursor.getLong(cursor.getColumnIndex("content_length")),
                cursor.getString(cursor.getColumnIndex("validator")));
        byte[] map = cursor.getBlob(cursor.getColumnIndex("block_map"));
        return new Entry(key, file(key.id), map == null ? new BitSet() : BitSet.valueOf(map),
                cursor.getLong(cursor.getColumnIndex("downloaded_bytes")),
                cursor.getInt(cursor.getColumnIndex("complete")) != 0);
    }

    private void reload(Entry entry) {
        Cursor cursor = getReadableDatabase().query("cache_entry", null,
                "cache_id=?", new String[]{entry.key.id}, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                byte[] map = cursor.getBlob(cursor.getColumnIndex("block_map"));
                synchronized (entry) {
                    entry.blocks = map == null ? new BitSet() : BitSet.valueOf(map);
                    entry.downloadedBytes = cursor.getLong(cursor.getColumnIndex("downloaded_bytes"));
                    entry.complete = cursor.getInt(cursor.getColumnIndex("complete")) != 0;
                }
            }
        } finally { cursor.close(); }
    }

    private void fetch(Entry entry, String url, int block, int expected) throws Exception {
        long start = (long) block * BLOCK_BYTES;
        long end = start + expected - 1;
        HttpURLConnection connection = open(url, start, end);
        RandomAccessFile output = null;
        InputStream input = null;
        try {
            int code = connection.getResponseCode();
            boolean full = code == 200 && start == 0;
            if (code != 206 && !full)
                throw new IllegalStateException("音频 Range 响应 " + code);
            if (code == 206) {
                long[] range = parseContentRange(connection.getHeaderField("Content-Range"));
                if (range[0] != start || range[1] != end || range[2] != entry.key.contentLength)
                    throw new IllegalStateException("音频 Content-Range 不匹配");
            } else if (connection.getContentLengthLong() != entry.key.contentLength) {
                throw new IllegalStateException("完整音频长度不匹配");
            }
            if (full && !reserve(entry.key.contentLength - entry.downloadedBytes(),
                    entry.key.id)) throw new IllegalStateException("缓存空间不足");
            input = connection.getInputStream();
            output = new RandomAccessFile(entry.file, "rw");
            output.seek(full ? 0 : start);
            byte[] buffer = new byte[32 * 1024]; int read; long received = 0;
            long expectedResponse = full ? entry.key.contentLength : expected;
            while ((read = input.read(buffer)) != -1) {
                if (received + read > expectedResponse)
                    throw new IllegalStateException("音频响应超出请求范围");
                output.write(buffer, 0, read); received += read;
            }
            if (received != expectedResponse)
                throw new IllegalStateException("音频块不完整 " + received + "/" + expectedResponse);
            output.getFD().sync();
            synchronized (entry) {
                if (full) entry.blocks.set(0, blockCount(entry.key.contentLength));
                else entry.blocks.set(block);
                entry.downloadedBytes = bytesFor(entry.blocks, entry.key.contentLength);
                entry.complete = entry.blocks.nextClearBit(0) >= blockCount(entry.key.contentLength);
            }
            commit(entry);
            Log.i(TAG, "track=" + entry.key.trackId + " block=" + block
                    + " bytes=" + received + " cached=" + entry.downloadedBytes());
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
            if (output != null) try { output.close(); } catch (Exception ignored) {}
            connection.disconnect();
        }
    }

    private synchronized boolean reserve(long incoming, String protectedId) {
        long limit = limitBytes();
        if (incoming > limit) return false;
        if (!storageHealthy(incoming)) return false;
        trimTo(Math.max(0, limit - incoming), protectedId);
        return stats().bytes <= limit - incoming;
    }

    private void trimTo(long target, String protectedId) {
        CacheStats current = stats();
        if (current.bytes <= target) return;
        Cursor cursor = getReadableDatabase().query("cache_entry",
                new String[]{"cache_id","downloaded_bytes"}, null, null,
                null, null, "last_access ASC");
        long bytes = current.bytes;
        try {
            while (cursor.moveToNext() && bytes > target) {
                String id = cursor.getString(0);
                if ((protectedId != null && id.equals(protectedId)) || isPinned(id)) continue;
                long size = cursor.getLong(1);
                if (deleteEntry(id)) bytes -= size;
            }
        } finally { cursor.close(); }
    }

    private boolean storageHealthy(long incoming) {
        StatFs stat = new StatFs(root.getAbsolutePath());
        long total = stat.getBlockCountLong() * stat.getBlockSizeLong();
        long available = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        long reserve = Math.max(512L * MB, total / 20);
        return available - incoming >= reserve;
    }

    private synchronized boolean deleteEntry(String id) {
        if (isPinned(id)) return false;
        File data = file(id); boolean deleted = !data.exists() || data.delete();
        if (deleted) getWritableDatabase().delete("cache_entry", "cache_id=?",
                new String[]{id});
        return deleted;
    }

    private boolean isPinned(String id) {
        synchronized (pins) { return pins.containsKey(id); }
    }

    private boolean isPending(String id) {
        synchronized (pins) { return pendingDelete.contains(id); }
    }

    private void commit(Entry entry) {
        ContentValues values;
        synchronized (entry) {
            values = values(entry.key, entry.blocks, entry.downloadedBytes, entry.complete);
        }
        values.remove("pending_delete");
        getWritableDatabase().update("cache_entry", values, "cache_id=?",
                new String[]{entry.key.id});
    }

    private ContentValues values(AudioCacheKey key, BitSet blocks, long bytes, boolean complete) {
        ContentValues values = new ContentValues();
        values.put("cache_id", key.id); values.put("track_id", key.trackId);
        values.put("quality", key.quality); values.put("format", key.format);
        values.put("content_length", key.contentLength); values.put("validator", key.validator);
        values.put("block_map", blocks.toByteArray()); values.put("downloaded_bytes", bytes);
        values.put("complete", complete ? 1 : 0); values.put("last_access", System.currentTimeMillis());
        values.put("pending_delete", 0);
        return values;
    }

    private void touch(String id) {
        ContentValues values = new ContentValues(); values.put("last_access", System.currentTimeMillis());
        getWritableDatabase().update("cache_entry", values, "cache_id=?", new String[]{id});
    }

    private Object lock(String id) {
        synchronized (locks) {
            Object value = locks.get(id);
            if (value == null) { value = new Object(); locks.put(id, value); }
            return value;
        }
    }

    private File file(String id) { return new File(root, id + ".audio"); }

    private static HttpURLConnection open(String url, long start, long end) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(18000);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11)");
        return connection;
    }

    private static long[] parseContentRange(String value) {
        if (value == null || !value.startsWith("bytes ") || !value.contains("/")
                || !value.contains("-")) throw new IllegalStateException("缺少 Content-Range");
        try {
            String[] parts = value.substring(6).split("/", 2);
            String[] range = parts[0].split("-", 2);
            return new long[]{Long.parseLong(range[0]), Long.parseLong(range[1]),
                    Long.parseLong(parts[1])};
        } catch (Exception e) { throw new IllegalStateException("Content-Range 无效"); }
    }

    private static void closeBody(HttpURLConnection connection) {
        try { InputStream input = connection.getInputStream(); if (input != null) input.close(); }
        catch (Exception ignored) {}
    }

    private static int expectedBytes(long size, int block) {
        long start = (long) block * BLOCK_BYTES;
        return (int) Math.min(BLOCK_BYTES, size - start);
    }

    private static long bytesFor(BitSet blocks, long size) {
        long bytes = 0;
        for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1))
            bytes += expectedBytes(size, block);
        return bytes;
    }

    private static void migrateLegacy(File legacy) {
        if (!legacy.exists()) return;
        File[] files = legacy.listFiles();
        if (files != null) for (File file : files) if (file.getName().endsWith(".range")) file.delete();
        legacy.delete();
    }

    private synchronized void purgePending() {
        ArrayList<String> ids = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("cache_entry", new String[]{"cache_id"},
                "pending_delete=1", null, null, null, null);
        try {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        } finally { cursor.close(); }
        for (String id : ids) deleteEntry(id);
    }
}
