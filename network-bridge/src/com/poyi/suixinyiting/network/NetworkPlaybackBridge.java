package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import java.lang.ref.WeakReference;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NetworkPlaybackBridge {
    private static final String TAG = "SuixinPlaybackBridge";
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static Bundle lastState;
    private static boolean registered;
    private static boolean lifecycleRegistered;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ART = Executors.newSingleThreadExecutor();
    private static String requestedCover = "";
    private static Bitmap coverBitmap;
    private static Bitmap appliedCoverBitmap;
    private static WeakReference<Activity> appliedCoverActivity = new WeakReference<>(null);
    private static WeakReference<ScrollView> lyricScrollRef = new WeakReference<>(null);
    private static WeakReference<TextView> lyricTextRef = new WeakReference<>(null);
    private static WeakReference<SeekBar> boundSeekRef = new WeakReference<>(null);
    private static final WeakHashMap<View, String> BOUND_ACTIONS = new WeakHashMap<>();
    private static final WeakHashMap<View, String> BOUND_QUALITY = new WeakHashMap<>();
    private static String[] cachedLyricLines = new String[0];
    private static String cachedLyricText = "";
    private static int[] cachedLineOffsets = new int[0];
    private static int cachedLyricRevision = Integer.MIN_VALUE;
    private static int pendingLyricIndex;
    private static int renderedLyricIndex = Integer.MIN_VALUE;
    private static String lyricFallback = "暂无歌词";
    private static WeakReference<ImageView> playImageRef = new WeakReference<>(null);
    private static boolean lastPlaying;
    private static boolean hasPlayingState;
    private static long manualLyricScrollUntil;
    private static int lyricOffset;
    private static boolean seeking;
    private static final Runnable LYRIC_RECENTER = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() < manualLyricScrollUntil) return;
            TextView text = lyricTextRef.get();
            renderPendingLyrics(text, true);
            autoScroll(lyricScrollRef.get(), text, lyricOffset, true);
        }
    };

    private NetworkPlaybackBridge() {}

    public static void install(Activity activity) {
        activityRef = new WeakReference<>(activity);
        Log.i(TAG, "install activity=" + activity.getClass().getName());
        if (!lifecycleRegistered) {
            activity.getApplication().registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityResumed(Activity current) {
                            if (current == activityRef.get()) {
                                hasPlayingState = false;
                                playImageRef = new WeakReference<>(null);
                                setUiActive(current, true);
                                MAIN.postDelayed(new Runnable() {
                                    @Override public void run() { apply(); }
                                }, 250);
                            }
                        }
                        @Override public void onActivityPaused(Activity current) {
                            if (current == activityRef.get()) setUiActive(current, false);
                        }
                        @Override public void onActivityCreated(Activity current, Bundle state) {}
                        @Override public void onActivityStarted(Activity current) {}
                        @Override public void onActivityStopped(Activity current) {}
                        @Override public void onActivitySaveInstanceState(Activity current, Bundle state) {}
                        @Override public void onActivityDestroyed(Activity current) {}
                    });
            lifecycleRegistered = true;
        }
        if (!registered) {
            activity.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    Bundle incoming = intent.getExtras();
                    if (incoming == null) return;
                    if (lastState == null) lastState = new Bundle(incoming);
                    else lastState.putAll(incoming);
                    apply();
                }
            }, new IntentFilter(NetworkStreamService.ACTION_STATE));
            registered = true;
        }
        setUiActive(activity, true);
        apply();
        MAIN.postDelayed(new Runnable() { @Override public void run() { apply(); }}, 300);
        MAIN.postDelayed(new Runnable() { @Override public void run() { apply(); }}, 1000);
    }

    private static void setUiActive(Activity activity, boolean active) {
        activity.startService(new Intent(activity, NetworkStreamService.class)
                .setAction(NetworkStreamService.ACTION_SET_UI_ACTIVE)
                .putExtra("active", active));
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
            String qualityKey = actualQuality + '|' + format + '|' + bitrate + '|' + outputCodec;
            if (!qualityKey.equals(BOUND_QUALITY.get(artist))) {
                BOUND_QUALITY.put(artist, qualityKey);
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
        }
        loadCover(activity, state.getString("cover", ""));
        applyCover(activity);

        View progressView = find(activity, "progress");
        if (progressView instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar) progressView;
            int duration = Math.max(1, state.getInt("duration", 1));
            if (progress.getMax() != duration) progress.setMax(duration);
            int position = state.getInt("position", 0);
            if (!seeking && progress.getProgress() != position) progress.setProgress(position);
            if (progress instanceof SeekBar) bindSeek(activity, (SeekBar) progress);
        }

        TextView tip = asText(find(activity, "tip"));
        if (tip != null) {
            ScrollView lyricScroll = ensureLyricScroller(activity, tip);
            int revision = state.getInt("lyricRevision", 0);
            String[] lines = state.getStringArray("lyricAll");
            if (revision != cachedLyricRevision && lines != null)
                cacheLyrics(lines, revision);
            pendingLyricIndex = state.getInt("lyricCurrent", 0);
            lyricFallback = state.getString("lyric", "暂无歌词");
            tip.setVisibility(View.VISIBLE);
            if (SystemClock.uptimeMillis() >= manualLyricScrollUntil) {
                boolean changed = renderPendingLyrics(tip, false);
                if (changed && cachedLyricLines.length > 0)
                    autoScroll(lyricScroll, tip, lyricOffset, true);
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
            boolean playing = state.getBoolean("playing", false);
            int drawable = activity.getResources().getIdentifier(
                        // The mother APK's legacy resources use these two names in reverse.
                        playing ? "ic_default_play" : "ic_default_pause",
                        "drawable", activity.getPackageName());
            boolean drawableMismatch = drawable != 0
                    && !sameDrawable((ImageView) play, activity, drawable);
            if (playImageRef.get() != play || !hasPlayingState || lastPlaying != playing
                    || drawableMismatch) {
                if (drawable != 0) ((ImageView) play).setImageResource(drawable);
                playImageRef = new WeakReference<>((ImageView) play);
                lastPlaying = playing;
                hasPlayingState = true;
            }
        }
    }

    private static boolean sameDrawable(ImageView view, Activity activity, int resource) {
        Drawable current = view.getDrawable();
        Drawable expected;
        try { expected = activity.getResources().getDrawable(resource, activity.getTheme()); }
        catch (Exception error) { return false; }
        if (current == null || expected == null) return current == expected;
        Drawable.ConstantState currentState = current.getConstantState();
        Drawable.ConstantState expectedState = expected.getConstantState();
        return currentState != null && expectedState != null && currentState.equals(expectedState);
    }

    private static void bindAction(final Activity activity, String idName, final String action) {
        View view = find(activity, idName);
        if (view == null) return;
        if (action.equals(BOUND_ACTIONS.get(view))) return;
        BOUND_ACTIONS.put(view, action);
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
        if (view != null && !TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private static TextView asText(View view) {
        return view instanceof TextView ? (TextView) view : null;
    }

    private static View find(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    private static void cacheLyrics(String[] lines, int revision) {
        cachedLyricLines = lines == null ? new String[0] : lines.clone();
        cachedLineOffsets = new int[cachedLyricLines.length];
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < cachedLyricLines.length; i++) {
            if (i > 0) text.append('\n');
            cachedLineOffsets[i] = text.length();
            text.append(cachedLyricLines[i]);
        }
        cachedLyricText = text.toString();
        cachedLyricRevision = revision;
        renderedLyricIndex = Integer.MIN_VALUE;
    }

    private static boolean renderPendingLyrics(TextView view, boolean force) {
        if (view == null) return false;
        if (!force && SystemClock.uptimeMillis() < manualLyricScrollUntil) return false;
        if (cachedLyricLines.length == 0) {
            if (!TextUtils.equals(view.getText(), lyricFallback)) view.setText(lyricFallback);
            renderedLyricIndex = 0;
            lyricOffset = 0;
            return true;
        }
        int current = Math.max(0, Math.min(pendingLyricIndex, cachedLyricLines.length - 1));
        if (renderedLyricIndex == current && view.getText().length() == cachedLyricText.length())
            return false;
        SpannableString text = new SpannableString(cachedLyricText);
        int start = cachedLineOffsets[current];
        int end = start + cachedLyricLines[current].length();
        text.setSpan(new ForegroundColorSpan(Color.WHITE), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(1.45f), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        renderedLyricIndex = current;
        lyricOffset = start;
        return true;
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
        tip.setTextSize(14);
        tip.setLineSpacing(12, 1f);
        tip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tip.setTextColor(0xff909090);
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
        renderedLyricIndex = Integer.MIN_VALUE;
        return scroll;
    }

    private static void autoScroll(final ScrollView scroll, final TextView text,
                                   final int offset, final boolean animate) {
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
                Rect visible = new Rect();
                boolean fullyVisible = scroll.getGlobalVisibleRect(visible)
                        && visible.width() >= scroll.getWidth() * 9 / 10
                        && visible.height() >= scroll.getHeight() * 9 / 10;
                if (animate && fullyVisible) scroll.smoothScrollTo(0, target);
                else scroll.scrollTo(0, target);
                Log.d(TAG, "lyric center lines=" + line + "-" + lastLine
                        + " target=" + target
                        + " lineCenter=" + lineCenter + " viewport=" + scroll.getHeight()
                        + " animate=" + (animate && fullyVisible));
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
                InputStream input = null;
                try {
                    connection = (HttpURLConnection) new URL(cover).openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    input = connection.getInputStream();
                    byte[] encoded = readBytes(input);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
                    int target = Math.max(activity.getResources().getDisplayMetrics().widthPixels,
                            activity.getResources().getDisplayMetrics().heightPixels);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target);
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bitmap = BitmapFactory.decodeByteArray(
                            encoded, 0, encoded.length, options);
                    if (bitmap == null) return;
                    coverBitmap = bitmap;
                    Log.i(TAG, "cover loaded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                            + " sample=" + options.inSampleSize);
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
                    if (input != null) try { input.close(); } catch (Exception ignored) {}
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    private static void applyCover(Activity activity) {
        Bitmap bitmap = coverBitmap;
        if (bitmap == null) return;
        if (appliedCoverActivity.get() == activity && appliedCoverBitmap == bitmap) return;
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
        appliedCoverActivity = new WeakReference<>(activity);
        appliedCoverBitmap = bitmap;
    }

    private static byte[] readBytes(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (width / (sample * 2) >= target && height / (sample * 2) >= target)
            sample *= 2;
        return sample;
    }
}
