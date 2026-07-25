package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

public final class NetworkAudioSettings {
    private static final String[] QUALITY_VALUES =
            {"hires", "lossless", "exhigh", "higher", "standard"};
    private static final String[] QUALITY_LABELS = {
            "Hi-Res 优先", "无损优先", "极高（约 320 kbps）",
            "较高（约 192 kbps）", "标准（约 128 kbps）"};
    private static final long[] CACHE_LIMITS = {
            256L * AudioCacheStore.MB, 512L * AudioCacheStore.MB,
            1024L * AudioCacheStore.MB};

    private NetworkAudioSettings() {}

    public static void show(final Activity activity) {
        show(activity, "", "", 0, "");
    }

    public static void show(final Activity activity, final String actualQuality,
                            final String format, final int bitrate,
                            final String outputCodec) {
        final SharedPreferences settings = activity.getSharedPreferences(
                "network_settings", Activity.MODE_PRIVATE);
        String selected = settings.getString("quality", "lossless");
        boolean enabled = settings.getBoolean("audio_prefetch_enabled", true);
        boolean wifiOnly = settings.getBoolean("wifi_only_prefetch", false);
        boolean cellular = settings.getBoolean("cellular_prefetch", false);
        long limit = settings.getLong("audio_cache_limit", 512L * AudioCacheStore.MB);
        CacheStats stats = AudioCacheStore.get(activity).stats();
        String source = QualityPolicy.label(selected);
        if (actualQuality != null && !actualQuality.isEmpty())
            source += " / 当前 " + QualityPolicy.label(actualQuality);
        String[] rows = {
                "音质  " + source,
                "后台预缓存  " + (enabled ? "开" : "关"),
                "仅 Wi-Fi  " + (wifiOnly ? "开" : "关"),
                "蜂窝预缓存  " + (cellular ? "开" : "关"),
                "缓存容量  " + formatMb(limit),
                "缓存统计  " + formatMb(stats.bytes) + " · " + stats.complete + " 首完整",
                "清理音频缓存"
        };
        new AlertDialog.Builder(activity).setTitle("网络音乐设置")
                .setItems(rows, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) showQuality(activity, actualQuality, format,
                            bitrate, outputCodec);
                    else if (which == 1) {
                        settings.edit().putBoolean("audio_prefetch_enabled", !enabled).apply();
                        notifyPolicy(activity);
                        show(activity, actualQuality, format, bitrate, outputCodec);
                    } else if (which == 2) {
                        boolean next = !wifiOnly;
                        SharedPreferences.Editor edit = settings.edit()
                                .putBoolean("wifi_only_prefetch", next);
                        if (next) edit.putBoolean("cellular_prefetch", false);
                        edit.apply();
                        notifyPolicy(activity);
                        show(activity, actualQuality, format, bitrate, outputCodec);
                    } else if (which == 3) {
                        setCellularPrefetch(activity, !cellular, actualQuality, format,
                                bitrate, outputCodec);
                    } else if (which == 4) showCapacity(activity, actualQuality, format,
                            bitrate, outputCodec);
                    else if (which == 5) showStats(activity, stats, actualQuality, format,
                            bitrate, outputCodec);
                    else if (which == 6) {
                        activity.startService(new Intent(activity, NetworkStreamService.class)
                                .setAction(NetworkStreamService.ACTION_CLEAR_CACHE));
                        Toast.makeText(activity, "正在清理；当前歌曲稍后释放", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private static void showQuality(final Activity activity, final String actualQuality,
                                    final String format, final int bitrate,
                                    final String outputCodec) {
        final SharedPreferences settings = activity.getSharedPreferences(
                "network_settings", Activity.MODE_PRIVATE);
        int selectedIndex = indexOf(QUALITY_VALUES,
                settings.getString("quality", "lossless"));
        new AlertDialog.Builder(activity).setTitle("播放音质")
                .setSingleChoiceItems(QUALITY_LABELS, selectedIndex, (dialog, which) -> {
                    settings.edit().putString("quality", QUALITY_VALUES[which]).apply();
                    activity.startService(new Intent(activity, NetworkStreamService.class)
                            .setAction(NetworkStreamService.ACTION_SET_QUALITY)
                            .putExtra("quality", QUALITY_VALUES[which]));
                    dialog.dismiss();
                }).setNegativeButton("返回", (dialog, which) ->
                        show(activity, actualQuality, format, bitrate, outputCodec)).show();
    }

    private static void showCapacity(final Activity activity, final String actualQuality,
                                     final String format, final int bitrate,
                                     final String outputCodec) {
        final SharedPreferences settings = activity.getSharedPreferences(
                "network_settings", Activity.MODE_PRIVATE);
        long current = settings.getLong("audio_cache_limit", 512L * AudioCacheStore.MB);
        int selected = current <= CACHE_LIMITS[0] ? 0 : current >= CACHE_LIMITS[2] ? 2 : 1;
        String[] labels = {"256 MB", "512 MB", "1 GB"};
        new AlertDialog.Builder(activity).setTitle("缓存容量")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    settings.edit().putLong("audio_cache_limit", CACHE_LIMITS[which]).apply();
                    notifyPolicy(activity);
                    dialog.dismiss();
                    show(activity, actualQuality, format, bitrate, outputCodec);
                }).show();
    }

    private static void setCellularPrefetch(final Activity activity, boolean enable,
                                            final String actualQuality, final String format,
                                            final int bitrate, final String outputCodec) {
        final SharedPreferences settings = activity.getSharedPreferences(
                "network_settings", Activity.MODE_PRIVATE);
        if (!enable) {
            settings.edit().putBoolean("cellular_prefetch", false).apply();
            notifyPolicy(activity);
            show(activity, actualQuality, format, bitrate, outputCodec);
            return;
        }
        new AlertDialog.Builder(activity).setTitle("蜂窝预缓存")
                .setMessage("会批量缓存后续歌曲并消耗较多流量。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开启", (dialog, which) -> {
                    settings.edit().putBoolean("cellular_prefetch", true)
                            .putBoolean("cellular_prefetch_confirmed", true)
                            .putBoolean("wifi_only_prefetch", false).apply();
                    notifyPolicy(activity);
                    show(activity, actualQuality, format, bitrate, outputCodec);
                }).show();
    }

    private static void showStats(final Activity activity, CacheStats stats,
                                  final String actualQuality, final String format,
                                  final int bitrate, final String outputCodec) {
        String message = "已用 " + formatMb(stats.bytes) + " / " + formatMb(stats.limitBytes)
                + "\n完整 " + stats.complete + " 首 · 部分 " + stats.partial + " 首"
                + "\n文件 " + stats.files + " 个";
        new AlertDialog.Builder(activity).setTitle("缓存统计").setMessage(message)
                .setPositiveButton("确定", (dialog, which) ->
                        show(activity, actualQuality, format, bitrate, outputCodec)).show();
    }

    private static String formatMb(long bytes) {
        if (bytes >= 1024L * AudioCacheStore.MB)
            return String.format(java.util.Locale.US, "%.1f GB",
                    bytes / (1024f * AudioCacheStore.MB));
        return Math.round(bytes / (float) AudioCacheStore.MB) + " MB";
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 1;
    }

    private static void notifyPolicy(Activity activity) {
        activity.startService(new Intent(activity, NetworkStreamService.class)
                .setAction(NetworkStreamService.ACTION_PREFETCH_POLICY_CHANGED));
    }
}
