package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NetworkPlaybackBridge {
    private static final String TAG = "SuixinPlaybackBridge";
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static Bundle lastState;
    private static boolean registered;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ART = Executors.newSingleThreadExecutor();
    private static String requestedCover = "";
    private static Bitmap coverBitmap;
    private static WeakReference<ScrollView> lyricScrollRef = new WeakReference<>(null);
    private static WeakReference<TextView> lyricTextRef = new WeakReference<>(null);
    private static WeakReference<SeekBar> boundSeekRef = new WeakReference<>(null);
    private static long manualLyricScrollUntil;
    private static int lyricOffset;
    private static boolean seeking;
    private static final Runnable LYRIC_RECENTER = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() < manualLyricScrollUntil) return;
            autoScroll(lyricScrollRef.get(), lyricTextRef.get(), lyricOffset);
        }
    };

    private NetworkPlaybackBridge() {}

    public static void install(Activity activity) {
        activityRef = new WeakReference<>(activity);
        Log.i(TAG, "install activity=" + activity.getClass().getName());
        if (!registered) {
            activity.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    lastState = intent.getExtras();
                    apply();
                }
            }, new IntentFilter(NetworkStreamService.ACTION_STATE));
            registered = true;
        }
        activity.startService(new Intent(activity, NetworkStreamService.class)
                .setAction(NetworkStreamService.ACTION_REQUEST_STATE));
        apply();
        MAIN.postDelayed(new Runnable() { @Override public void run() { apply(); }}, 300);
        MAIN.postDelayed(new Runnable() { @Override public void run() { apply(); }}, 1000);
    }

    private static void apply() {
        final Activity activity = activityRef.get();
        final Bundle state = lastState;
        if (activity == null || state == null || !state.getBoolean("active", false)) return;
        setText(activity, "title", state.getString("title", "网络音乐"));
        String artistText = state.getString("artist", "");
        String actualQuality = state.getString("actualQuality", "");
        String outputCodec = state.getString("outputCodec", "");
        final int bitrate = state.getInt("bitrate", 0);
        final String format = state.getString("format", "");
        if (!actualQuality.isEmpty()) {
            artistText += "\n源:" + QualityPolicy.label(actualQuality);
            if (!format.isEmpty()) artistText += format.toUpperCase();
            if (bitrate > 0) artistText += " " + Math.round(bitrate / 1000f) + "k";
            if (!outputCodec.isEmpty())
                artistText += " | 耳:" + outputCodec.replace(" · ", "/");
        }
        setText(activity, "artist", artistText);
        View artist = find(activity, "artist");
        if (artist != null) {
            if (artist instanceof TextView && !actualQuality.isEmpty()) {
                TextView qualityLine = (TextView) artist;
                qualityLine.setTextSize(8);
                qualityLine.setSingleLine(false);
                qualityLine.setMaxLines(2);
                qualityLine.setEllipsize(null);
            }
            final String sourceQuality = actualQuality;
            final String headphoneQuality = outputCodec;
            artist.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    NetworkAudioSettings.show(activity, sourceQuality, format,
                            bitrate, headphoneQuality);
                }
            });
        }
        loadCover(activity, state.getString("cover", ""));
        applyCover(activity);

        View progressView = find(activity, "progress");
        if (progressView instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar) progressView;
            progress.setMax(Math.max(1, state.getInt("duration", 1)));
            if (!seeking) progress.setProgress(state.getInt("position", 0));
            if (progress instanceof SeekBar) bindSeek(activity, (SeekBar) progress);
        }

        TextView tip = asText(find(activity, "tip"));
        if (tip != null) {
            String[] lines = state.getStringArray("lyricAll");
            int current = state.getInt("lyricCurrent", 0);
            ScrollView lyricScroll = ensureLyricScroller(activity, tip);
            CharSequence formatted = formatLyrics(
                    lines, current, state.getString("lyric", "暂无歌词"));
            tip.setText(formatted);
            tip.setTextSize(16);
            tip.setLineSpacing(12, 1f);
            tip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            tip.setVisibility(View.VISIBLE);
            if (lines != null && lines.length > 0) {
                lyricOffset = lineOffset(formatted, current);
                if (SystemClock.uptimeMillis() >= manualLyricScrollUntil)
                    autoScroll(lyricScroll, tip, lyricOffset);
            }
        }
        View lyric1 = find(activity, "lyric_view");
        View lyric2 = find(activity, "lyric_view2");
        if (lyric1 != null) lyric1.setVisibility(View.GONE);
        if (lyric2 != null) lyric2.setVisibility(View.GONE);

        bindAction(activity, "prev", NetworkStreamService.ACTION_PREVIOUS);
        bindAction(activity, "next", NetworkStreamService.ACTION_NEXT);
        bindAction(activity, "play", NetworkStreamService.ACTION_TOGGLE);
        View play = find(activity, "play");
        if (play instanceof ImageView) {
            int drawable = activity.getResources().getIdentifier(
                    // The mother APK's legacy resources use these two names in reverse.
                    state.getBoolean("playing", false) ? "ic_default_play" : "ic_default_pause",
                    "drawable", activity.getPackageName());
            if (drawable != 0) ((ImageView) play).setImageResource(drawable);
        }
    }

    private static void bindAction(final Activity activity, String idName, final String action) {
        View view = find(activity, idName);
        if (view == null) return;
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                activity.startService(new Intent(activity, NetworkStreamService.class).setAction(action));
            }
        });
    }

    private static void bindSeek(final Activity activity, final SeekBar seekBar) {
        if (boundSeekRef.get() == seekBar) return;
        boundSeekRef = new WeakReference<>(seekBar);
        seekBar.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                        && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
                    return false;
                seeking = true;
                int width = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
                float x = event.getX() - seekBar.getPaddingLeft();
                float ratio = width <= 0 ? 0f : Math.max(0f, Math.min(1f, x / width));
                seekBar.setProgress(Math.round(ratio * seekBar.getMax()));
                if (action == MotionEvent.ACTION_UP) {
                    activity.startService(new Intent(activity, NetworkStreamService.class)
                            .setAction(NetworkStreamService.ACTION_SEEK)
                            .putExtra("position", seekBar.getProgress()));
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() { seeking = false; }
                    }, 350);
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    seeking = false;
                }
                return true;
            }
        });
    }

    private static void setText(Activity activity, String id, String value) {
        TextView view = asText(find(activity, id));
        if (view != null) view.setText(value);
    }

    private static TextView asText(View view) {
        return view instanceof TextView ? (TextView) view : null;
    }

    private static View find(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    private static CharSequence formatLyrics(String[] lines, int current, String fallback) {
        if (lines == null || lines.length == 0) return fallback;
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) text.append("\n");
            int start = text.length();
            text.append(lines[i]);
            int end = text.length();
            if (i == current) {
                text.setSpan(new ForegroundColorSpan(Color.WHITE), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new RelativeSizeSpan(1.28f), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                text.setSpan(new ForegroundColorSpan(0xff909090), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new RelativeSizeSpan(0.88f), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return text;
    }

    private static ScrollView ensureLyricScroller(Activity activity, TextView tip) {
        ScrollView existing = lyricScrollRef.get();
        if (existing != null && existing.getContext() == activity) return existing;
        ViewGroup parent = tip.getParent() instanceof ViewGroup
                ? (ViewGroup) tip.getParent() : null;
        if (parent == null) return null;
        parent.removeView(tip);
        ScrollView scroll = new ScrollView(activity);
        scroll.setTag("suixin_full_lyrics");
        scroll.setFillViewport(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    manualLyricScrollUntil = SystemClock.uptimeMillis() + 3000;
                    MAIN.removeCallbacks(LYRIC_RECENTER);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    manualLyricScrollUntil = SystemClock.uptimeMillis() + 3000;
                    MAIN.removeCallbacks(LYRIC_RECENTER);
                    MAIN.postDelayed(LYRIC_RECENTER, 3050);
                }
                return false;
            }
        });
        int verticalPadding = Math.max(1,
                activity.getResources().getDisplayMetrics().heightPixels / 2 - 45);
        tip.setPadding(18, verticalPadding, 18, verticalPadding);
        scroll.addView(tip, new ScrollView.LayoutParams(-1, -2));
        parent.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        lyricScrollRef = new WeakReference<>(scroll);
        lyricTextRef = new WeakReference<>(tip);
        return scroll;
    }

    private static int lineOffset(CharSequence text, int current) {
        if (current <= 0) return 0;
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++seen == current) return i + 1;
        }
        return 0;
    }

    private static void autoScroll(final ScrollView scroll, final TextView text,
                                   final int offset) {
        if (scroll == null || text == null) return;
        scroll.post(new Runnable() {
            @Override public void run() {
                if (text.getLayout() == null) return;
                CharSequence lyricText = text.getText();
                int safeOffset = Math.min(offset, Math.max(0, lyricText.length() - 1));
                int line = text.getLayout().getLineForOffset(safeOffset);
                int logicalEnd = safeOffset;
                while (logicalEnd < lyricText.length()
                        && lyricText.charAt(logicalEnd) != '\n') {
                    logicalEnd++;
                }
                int lastLine = text.getLayout().getLineForOffset(
                        Math.max(safeOffset, logicalEnd - 1));
                int lineCenter = text.getCompoundPaddingTop()
                        + (text.getLayout().getLineTop(line)
                        + text.getLayout().getLineBottom(lastLine)) / 2;
                int maxScroll = Math.max(0, text.getHeight() - scroll.getHeight());
                int target = Math.max(0, Math.min(lineCenter - scroll.getHeight() / 2,
                        maxScroll));
                scroll.smoothScrollTo(0, target);
                Log.d(TAG, "lyric center lines=" + line + "-" + lastLine
                        + " target=" + target
                        + " lineCenter=" + lineCenter + " viewport=" + scroll.getHeight());
            }
        });
    }

    /** Consumes crown rotation only while the full lyric page is actually visible. */
    public static boolean onRotary(Activity activity, MotionEvent event) {
        ScrollView scroll = lyricScrollRef.get();
        if (scroll == null || scroll.getContext() != activity || !scroll.isShown()) return false;
        Rect visible = new Rect();
        if (!scroll.getGlobalVisibleRect(visible)
                || visible.width() < scroll.getWidth() / 2
                || visible.height() < scroll.getHeight() / 2) return false;
        float axis = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        if (axis == 0f) return false;
        int amount = Math.round(-axis * 56f
                * activity.getResources().getDisplayMetrics().density);
        scroll.scrollBy(0, amount);
        manualLyricScrollUntil = SystemClock.uptimeMillis() + 3000;
        MAIN.removeCallbacks(LYRIC_RECENTER);
        MAIN.postDelayed(LYRIC_RECENTER, 3050);
        return true;
    }

    private static void loadCover(final Activity activity, final String cover) {
        if (cover == null || cover.isEmpty() || cover.equals(requestedCover)) return;
        requestedCover = cover;
        ART.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(cover).openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    final Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
                    if (bitmap == null) return;
                    coverBitmap = bitmap;
                    Log.i(TAG, "cover loaded " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            Activity current = activityRef.get();
                            if (current != activity || !cover.equals(requestedCover)) return;
                            applyCover(activity);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "cover failed: " + e.getMessage());
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    private static void applyCover(Activity activity) {
        Bitmap bitmap = coverBitmap;
        if (bitmap == null) return;
        View blur = find(activity, "blur_view");
        View flowing = find(activity, "flowing_view");
        if (blur instanceof ImageView) ((ImageView) blur).setImageBitmap(bitmap);
        if (flowing instanceof ImageView) ((ImageView) flowing).setImageBitmap(bitmap);
        View shade = find(activity, "black_background");
        View mainView = find(activity, "main");
        if (mainView instanceof ViewGroup && shade != null) {
            ViewGroup main = (ViewGroup) mainView;
            ImageView networkCover = null;
            View tagged = main.findViewWithTag("suixin_network_cover");
            if (tagged instanceof ImageView) networkCover = (ImageView) tagged;
            if (networkCover == null) {
                networkCover = new ImageView(activity);
                networkCover.setTag("suixin_network_cover");
                networkCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int index = main.indexOfChild(shade);
                main.addView(networkCover, Math.max(0, index),
                        new FrameLayout.LayoutParams(-1, -1));
            }
            networkCover.setImageBitmap(bitmap);
            networkCover.setVisibility(View.VISIBLE);
        }
    }
}
