package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;

public final class NetworkAudioSettings {
    private static final String[] QUALITY_VALUES = {"hires", "lossless", "exhigh", "higher", "standard"};
    private static final String[] QUALITY_LABELS = {
            "Hi-Res 优先", "无损优先", "极高（约 320 kbps）", "较高（约 192 kbps）", "标准（约 128 kbps）"};
    private NetworkAudioSettings() {}

    public static void show(final Activity activity) {
        showQuality(activity, "", "", 0, "");
    }

    public static void show(final Activity activity, String actualQuality, String format,
                            int bitrate, String outputCodec) {
        showQuality(activity, actualQuality, format, bitrate, outputCodec);
    }

    private static void showQuality(final Activity activity, String actualQuality, String format,
                                    int bitrate, String outputCodec) {
        String selected = activity.getSharedPreferences("network_settings", Activity.MODE_PRIVATE)
                .getString("quality", "lossless");
        String current = "";
        if (actualQuality != null && !actualQuality.isEmpty()) {
            current += "\n当前源：" + QualityPolicy.label(actualQuality);
            if (format != null && !format.isEmpty()) current += " " + format.toUpperCase();
            if (bitrate > 0) current += " " + Math.round(bitrate / 1000f) + " kbps";
        }
        if (outputCodec != null && !outputCodec.isEmpty())
            current += "\n当前耳机：" + outputCodec;
        final int selectedIndex = indexOf(QUALITY_VALUES, selected);
        String[] displayLabels = QUALITY_LABELS.clone();
        displayLabels[selectedIndex] += current;
        new AlertDialog.Builder(activity)
                .setSingleChoiceItems(displayLabels, selectedIndex, (dialog, which) -> {
                    activity.getSharedPreferences("network_settings", Activity.MODE_PRIVATE)
                            .edit().putString("quality", QUALITY_VALUES[which]).apply();
                    activity.startService(new Intent(activity, NetworkStreamService.class)
                            .setAction(NetworkStreamService.ACTION_SET_QUALITY)
                            .putExtra("quality", QUALITY_VALUES[which]));
                    dialog.dismiss();
                }).show();
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 1;
    }
}
