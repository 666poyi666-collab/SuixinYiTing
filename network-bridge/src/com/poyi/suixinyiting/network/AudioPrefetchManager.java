package com.poyi.suixinyiting.network;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AudioPrefetchManager {
    private static final String TAG = "SuixinPrefetch";
    private final Context context;
    private final NeteaseWebApi api;
    private final AudioCacheStore cache;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicInteger generation = new AtomicInteger();

    public AudioPrefetchManager(Context context, NeteaseWebApi api, AudioCacheStore cache) {
        this.context = context.getApplicationContext(); this.api = api; this.cache = cache;
    }

    public void cancel() { generation.incrementAndGet(); }

    public void schedule(final long[] ids, final String quality, final int playGeneration) {
        final int task = generation.incrementAndGet();
        final Policy policy = policy();
        if (!policy.allowed || ids == null || ids.length == 0) return;
        worker.execute(new Runnable() { @Override public void run() {
            long initial = cache.stats().bytes;
            int completed = 0;
            long estimatedSeconds = 0;
            for (int i = 0; i < ids.length && completed < policy.trackLimit; i++) {
                if (task != generation.get()) return;
                long used = Math.max(0, cache.stats().bytes - initial);
                if (used >= policy.byteLimit) return;
                AudioCacheStore.Entry entry = null;
                try {
                    StreamVariant variant = api.resolve(ids[i], quality);
                    if (task != generation.get()) return;
                    entry = cache.openOnline(ids[i], variant);
                    cache.pin(entry);
                    long trackSeconds = variant.bitrate > 0
                            ? Math.max(1, entry.key.contentLength * 8L / variant.bitrate) : 0;
                    if (policy.secondsLimit > 0 && completed > 0
                            && estimatedSeconds + trackSeconds > policy.secondsLimit) return;
                    long remaining = Math.max(0, policy.byteLimit - used);
                    final int expectedTask = task;
                    boolean done = cache.fill(entry, variant.url,
                            new AudioCacheStore.Cancellation() {
                                @Override public boolean cancelled() {
                                    return expectedTask != generation.get();
                                }
                            }, remaining);
                    if (done) {
                        completed++;
                        estimatedSeconds += trackSeconds;
                    }
                } catch (Exception error) {
                    Log.w(TAG, "track=" + ids[i] + " skipped: " + error.getMessage());
                } finally { if (entry != null) cache.unpin(entry); }
            }
            Log.i(TAG, "generation=" + playGeneration + " completed=" + completed
                    + " bytes=" + Math.max(0, cache.stats().bytes - initial));
        }});
    }

    public void shutdown() { cancel(); worker.shutdownNow(); }

    private Policy policy() {
        SharedPreferences settings = context.getSharedPreferences(
                "network_settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean("audio_prefetch_enabled", true)) return Policy.disabled();
        ConnectivityManager connectivity = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = connectivity == null ? null : connectivity.getActiveNetworkInfo();
        if (network == null || !network.isConnected()) return Policy.disabled();
        boolean cellular = network.getType() == ConnectivityManager.TYPE_MOBILE;
        if (cellular && (!settings.getBoolean("cellular_prefetch", false)
                || settings.getBoolean("wifi_only_prefetch", false))) return Policy.disabled();
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
        int scale = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = scale <= 0 ? 100 : level * 100 / scale;
        int status = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (percent < 15 || (power != null && power.isPowerSaveMode())) return Policy.disabled();
        if (percent < 30) return new Policy(true, 2, 96L * AudioCacheStore.MB, 15 * 60L);
        if (!cellular && charging) {
            long target = cache.limitBytes() * 9 / 10;
            return new Policy(true, 256, Math.max(0, target - cache.stats().bytes), 0);
        }
        return new Policy(true, 8, 256L * AudioCacheStore.MB, 45 * 60L);
    }

    private static final class Policy {
        final boolean allowed; final int trackLimit; final long byteLimit;
        final long secondsLimit;
        Policy(boolean allowed, int trackLimit, long byteLimit, long secondsLimit) {
            this.allowed = allowed; this.trackLimit = trackLimit; this.byteLimit = byteLimit;
            this.secondsLimit = secondsLimit;
        }
        static Policy disabled() { return new Policy(false, 0, 0, 0); }
    }
}
