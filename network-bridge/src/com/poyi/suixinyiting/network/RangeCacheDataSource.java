package com.poyi.suixinyiting.network;

import android.media.MediaDataSource;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.BitSet;

public final class RangeCacheDataSource extends MediaDataSource {
    private static final int BLOCK = 256 * 1024;
    private static final long CACHE_LIMIT = 256L * 1024L * 1024L;
    private final String url;
    private final long trackId;
    private final File file;
    private final RandomAccessFile raf;
    private final BitSet blocks = new BitSet();
    private final long size;
    private volatile boolean closed;
    private long fetchedBytes;

    public RangeCacheDataSource(File root, long trackId, String url) throws Exception {
        this.url = url;
        this.trackId = trackId;
        if (!root.exists()) root.mkdirs();
        trim(root);
        file = new File(root, trackId + ".range");
        raf = new RandomAccessFile(file, "rw");
        size = probe(url);
        Log.i("SuixinTraffic", "track=" + trackId + " audioBytes=" + size
                + " audioMiB=" + String.format(java.util.Locale.US, "%.2f",
                size / 1048576.0));
        file.setLastModified(System.currentTimeMillis());
    }

    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int count) {
        if (closed || position >= size) return -1;
        int wanted = (int) Math.min(count, size - position);
        try {
            int first = (int) (position / BLOCK);
            int last = (int) ((position + wanted - 1) / BLOCK);
            for (int b = first; b <= last; b++) if (!blocks.get(b)) fetch(b);
            raf.seek(position);
            return raf.read(buffer, offset, wanted);
        } catch (Exception e) {
            Log.e("SuixinTraffic", "track=" + trackId + " read failed position="
                    + position + " count=" + count, e);
            return -1;
        }
    }

    private void fetch(int block) throws Exception {
        long start = (long) block * BLOCK;
        long end = Math.min(size - 1, start + BLOCK - 1);
        long started = SystemClock.elapsedRealtime();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(18000);
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11)");
        java.io.InputStream in = c.getInputStream();
        raf.seek(start); byte[] chunk = new byte[32 * 1024]; int n; long received = 0;
        while ((n = in.read(chunk)) > 0) { raf.write(chunk, 0, n); received += n; }
        in.close(); c.disconnect(); blocks.set(block);
        fetchedBytes += received;
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
        double mbps = received * 8.0 / elapsed / 1000.0;
        Log.i("SuixinTraffic", "track=" + trackId + " block=" + block
                + " bytes=" + received + " elapsedMs=" + elapsed + " mbps="
                + String.format(java.util.Locale.US, "%.2f", mbps)
                + " fetchedBytes=" + fetchedBytes);
        file.setLastModified(System.currentTimeMillis());
    }

    private static long probe(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Range", "bytes=0-0"); c.setConnectTimeout(12000);
        c.setReadTimeout(18000); c.connect();
        String range = c.getHeaderField("Content-Range");
        long length = range != null && range.contains("/") ?
                Long.parseLong(range.substring(range.lastIndexOf('/') + 1)) : c.getContentLengthLong();
        c.disconnect();
        if (length <= 0) throw new IllegalStateException("音频长度未知");
        return length;
    }

    private static void trim(File root) {
        File[] files = root.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        long total = 0; for (File f : files) total += f.length();
        for (File f : files) if (total > CACHE_LIMIT) { long n = f.length(); if (f.delete()) total -= n; }
    }

    @Override public long getSize() { return size; }
    @Override public synchronized void close() {
        closed = true; try { raf.close(); } catch (Exception ignored) {}
    }
}
