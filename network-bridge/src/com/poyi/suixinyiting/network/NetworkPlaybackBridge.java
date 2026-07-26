package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders network playback into the master player page.
 *
 * <p>Watch-specific rendering rules this class exists to enforce:
 *
 * <ul>
 *   <li><b>Resolve ids once.</b> The previous revision called
 *       {@link Resources#getIdentifier} for every field on every state
 *       broadcast — roughly fifteen string-keyed resource-table lookups per
 *       second. Ids are now resolved once per process and views once per
 *       activity instance.
 *   <li><b>Never re-layout for a lyric line change.</b> The highlight moves by
 *       mutating a {@link ForegroundColorSpan} on the {@link Spannable} the
 *       TextView already holds. Colour spans are not metric-affecting, so the
 *       TextView invalidates instead of re-measuring the whole lyric block.
 *       Bold and relative-size spans were doing a full text re-layout on every
 *       line, which is what made the lyric page stutter.
 *   <li><b>Own no view hierarchy.</b> The lyric scroller and the cover backdrop
 *       are declared in XML, so resuming the page binds instead of rebuilding.
 * </ul>
 */
public final class NetworkPlaybackBridge {
    private static final String TAG = "SuixinPlaybackBridge";
    private static final int LYRIC_DIM = 0x99ffffff;
    private static final int LYRIC_ACTIVE = Color.WHITE;
    private static final long MANUAL_SCROLL_HOLD_MS = 3000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ART = Executors.newSingleThreadExecutor();

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static Bundle lastState;
    private static boolean registered;
    private static boolean lifecycleRegistered;

    private static Ids ids;
    private static Views views;

    private static String requestedCover = "";
    private static Bitmap coverBitmap;
    private static Bitmap appliedCoverBitmap;

    private static String[] lyricLines = new String[0];
    private static int[] lyricOffsets = new int[0];
    private static String lyricText = "";
    private static int lyricRevision = Integer.MIN_VALUE;
    private static Spannable lyricSpannable;
    private static ForegroundColorSpan lyricHighlight;
    private static int renderedLyricIndex = Integer.MIN_VALUE;
    private static int pendingLyricIndex;
    private static String lyricFallback = "暂无歌词";
    private static int lyricHighlightStart;

    private static long manualLyricScrollUntil;
    private static boolean seeking;
    private static boolean lastPlaying;
    private static boolean hasPlayingState;
    private static String shownPosition = "";
    private static String shownDuration = "";

    private static final Runnable LYRIC_RECENTER = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() < manualLyricScrollUntil) return;
            Views bound = views;
            if (bound == null) return;
            moveHighlight(bound, true);
            centerCurrentLyric(bound, true);
        }
    };

    private NetworkPlaybackBridge() {}

    /** Resource ids resolved once; {@code getIdentifier} is far too slow per frame. */
    private static final class Ids {
        final int title, artist, quality, status, elapsed, total;
        final int progress, play, prev, next, cover, blur, flowing;
        final int lyricScroll, lyricText, lyricScrim, lyricView, lyricView2;
        final int playIcon, pauseIcon;

        Ids(Activity activity) {
            Resources res = activity.getResources();
            String pkg = activity.getPackageName();
            title = res.getIdentifier("title", "id", pkg);
            artist = res.getIdentifier("artist", "id", pkg);
            quality = res.getIdentifier("suixin_quality", "id", pkg);
            status = res.getIdentifier("suixin_status", "id", pkg);
            elapsed = res.getIdentifier("suixin_position", "id", pkg);
            total = res.getIdentifier("suixin_duration", "id", pkg);
            progress = res.getIdentifier("progress", "id", pkg);
            play = res.getIdentifier("play", "id", pkg);
            prev = res.getIdentifier("prev", "id", pkg);
            next = res.getIdentifier("next", "id", pkg);
            cover = res.getIdentifier("suixin_network_cover", "id", pkg);
            blur = res.getIdentifier("blur_view", "id", pkg);
            flowing = res.getIdentifier("flowing_view", "id", pkg);
            lyricScroll = res.getIdentifier("suixin_lyric_scroll", "id", pkg);
            lyricText = res.getIdentifier("tip", "id", pkg);
            lyricScrim = res.getIdentifier("suixin_lyric_scrim", "id", pkg);
            lyricView = res.getIdentifier("lyric_view", "id", pkg);
            lyricView2 = res.getIdentifier("lyric_view2", "id", pkg);
            // The master APK's legacy resources use these two names in reverse.
            playIcon = res.getIdentifier("ic_default_play", "drawable", pkg);
            pauseIcon = res.getIdentifier("ic_default_pause", "drawable", pkg);
        }
    }

    /** Views for one activity instance; re-bound only when the page is rebuilt. */
    private static final class Views {
        final Activity activity;
        TextView title, artist, quality, status, elapsed, total, lyric;
        SeekBar progress;
        ImageView play, prev, next, cover, blur, flowing;
        ScrollView lyricScroll;
        View lyricScrim, lyricView, lyricView2;
        boolean actionsBound;
        boolean seekBound;
        boolean qualityBound;
        boolean lyricTouchBound;
        boolean lyricPadded;
        boolean iconApplied;
        boolean centerPending;
        String qualityKey = "";

        Views(Activity activity) { this.activity = activity; }

        boolean stale() {
            // The player page lives in a ViewPager; swiping away can drop it.
            return title == null || !title.isAttachedToWindow();
        }
    }

    public static void install(Activity activity) {
        activityRef = new WeakReference<>(activity);
        Log.i(TAG, "install activity=" + activity.getClass().getName());

        // Hardware volume keys and the crown must always land on the media
        // stream, including while playback is paused (AUDIO-004).
        activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);

        if (!lifecycleRegistered) {
            activity.getApplication().registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityResumed(Activity current) {
                            if (current != activityRef.get()) return;
                            hasPlayingState = false;
                            setUiActive(current, true);
                            MAIN.postDelayed(APPLY, 250);
                        }
                        @Override public void onActivityPaused(Activity current) {
                            if (current == activityRef.get()) setUiActive(current, false);
                        }
                        @Override public void onActivityDestroyed(Activity current) {
                            if (views != null && views.activity == current) views = null;
                        }
                        @Override public void onActivityCreated(Activity current, Bundle state) {}
                        @Override public void onActivityStarted(Activity current) {}
                        @Override public void onActivityStopped(Activity current) {}
                        @Override public void onActivitySaveInstanceState(Activity a, Bundle s) {}
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
        MAIN.postDelayed(APPLY, 300);
        MAIN.postDelayed(APPLY, 1000);
    }

    private static final Runnable APPLY = new Runnable() {
        @Override public void run() { apply(); }
    };

    private static void setUiActive(Activity activity, boolean active) {
        activity.startService(new Intent(activity, NetworkStreamService.class)
                .setAction(NetworkStreamService.ACTION_SET_UI_ACTIVE)
                .putExtra("active", active));
    }

    private static Views bind(Activity activity) {
        Views bound = views;
        if (bound != null && bound.activity == activity && !bound.stale()) return bound;
        if (ids == null) ids = new Ids(activity);
        bound = new Views(activity);
        bound.title = text(activity, ids.title);
        bound.artist = text(activity, ids.artist);
        bound.quality = text(activity, ids.quality);
        bound.status = text(activity, ids.status);
        bound.elapsed = text(activity, ids.elapsed);
        bound.total = text(activity, ids.total);
        bound.lyric = text(activity, ids.lyricText);
        View progress = activity.findViewById(ids.progress);
        bound.progress = progress instanceof SeekBar ? (SeekBar) progress : null;
        bound.play = image(activity, ids.play);
        bound.prev = image(activity, ids.prev);
        bound.next = image(activity, ids.next);
        bound.cover = image(activity, ids.cover);
        bound.blur = image(activity, ids.blur);
        bound.flowing = image(activity, ids.flowing);
        View scroll = activity.findViewById(ids.lyricScroll);
        bound.lyricScroll = scroll instanceof ScrollView ? (ScrollView) scroll : null;
        bound.lyricScrim = activity.findViewById(ids.lyricScrim);
        bound.lyricView = activity.findViewById(ids.lyricView);
        bound.lyricView2 = activity.findViewById(ids.lyricView2);
        views = bound;
        // A rebound page has no spans from the previous instance.
        lyricSpannable = null;
        renderedLyricIndex = Integer.MIN_VALUE;
        appliedCoverBitmap = null;
        shownPosition = "";
        shownDuration = "";
        Log.i(TAG, "bound views title=" + (bound.title != null)
                + " quality=" + (bound.quality != null)
                + " lyricScroll=" + (bound.lyricScroll != null)
                + " cover=" + (bound.cover != null));
        return bound;
    }

    private static TextView text(Activity activity, int id) {
        View view = id == 0 ? null : activity.findViewById(id);
        return view instanceof TextView ? (TextView) view : null;
    }

    private static ImageView image(Activity activity, int id) {
        View view = id == 0 ? null : activity.findViewById(id);
        return view instanceof ImageView ? (ImageView) view : null;
    }

    private static void apply() {
        final Activity activity = activityRef.get();
        final Bundle state = lastState;
        if (activity == null || state == null || !state.getBoolean("active", false)) return;
        final Views bound = bind(activity);
        if (bound.title == null) return;

        setText(bound.title, state.getString("title", "网络音乐"));
        setText(bound.artist, state.getString("artist", ""));
        applyQuality(activity, bound, state);
        applyStatus(bound, state);
        applyCover(bound, state);
        applyProgress(bound, state);
        applyTransport(activity, bound, state);
        applyLyrics(bound, state);
    }

    // ---------------------------------------------------------------- quality

    private static void applyQuality(final Activity activity, Views bound, Bundle state) {
        final String actual = state.getString("actualQuality", "");
        final String format = state.getString("format", "");
        final int bitrate = state.getInt("bitrate", 0);
        final String codec = state.getString("outputCodec", "");
        if (bound.quality == null) return;

        String key = actual + '|' + format + '|' + bitrate + '|' + codec;
        if (key.equals(bound.qualityKey)) return;
        bound.qualityKey = key;

        StringBuilder label = new StringBuilder();
        if (!actual.isEmpty()) {
            label.append(QualityPolicy.label(actual));
            if (!format.isEmpty()) label.append(' ').append(format.toUpperCase());
            if (bitrate > 0) label.append(' ').append(Math.round(bitrate / 1000f)).append('k');
        }
        if (!codec.isEmpty()) {
            if (label.length() > 0) label.append(" · ");
            label.append(codec.replace(" · ", "/"));
        }
        setText(bound.quality, label.toString());
        bound.quality.setVisibility(label.length() == 0 ? View.INVISIBLE : View.VISIBLE);

        if (!bound.qualityBound) {
            bound.qualityBound = true;
            bound.quality.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Bundle current = lastState;
                    if (current == null) return;
                    NetworkAudioSettings.show(activity,
                            current.getString("actualQuality", ""),
                            current.getString("format", ""),
                            current.getInt("bitrate", 0),
                            current.getString("outputCodec", ""));
                }
            });
        }
    }

    private static void applyStatus(Views bound, Bundle state) {
        if (bound.status == null) return;
        String message = state.getString("errorMessage", "");
        if (message.isEmpty()) {
            if (state.getBoolean("buffering", false)) {
                message = "PlaybackPhase.RESOLVING".equals(state.getString("phase"))
                        ? "解析中…" : "缓冲中…";
            } else if (state.getBoolean("offlineCached", false)) {
                message = "离线缓存";
            }
        }
        if (message.isEmpty()) {
            if (bound.status.getVisibility() != View.GONE) bound.status.setVisibility(View.GONE);
            return;
        }
        setText(bound.status, message);
        if (bound.status.getVisibility() != View.VISIBLE) bound.status.setVisibility(View.VISIBLE);
    }

    // --------------------------------------------------------------- progress

    private static void applyProgress(Views bound, Bundle state) {
        SeekBar progress = bound.progress;
        if (progress == null) return;
        int duration = Math.max(1, state.getInt("duration", 1));
        if (progress.getMax() != duration) progress.setMax(duration);
        int position = state.getInt("position", 0);
        if (!seeking && progress.getProgress() != position) progress.setProgress(position);
        bindSeek(bound);

        if (bound.elapsed != null) {
            String value = clock(seeking ? progress.getProgress() : position);
            if (!value.equals(shownPosition)) {
                shownPosition = value;
                bound.elapsed.setText(value);
            }
        }
        if (bound.total != null) {
            String value = clock(state.getInt("duration", 0));
            if (!value.equals(shownDuration)) {
                shownDuration = value;
                bound.total.setText(value);
            }
        }
    }

    private static String clock(int millis) {
        int seconds = Math.max(0, millis) / 1000;
        int minutes = seconds / 60;
        seconds %= 60;
        return minutes + (seconds < 10 ? ":0" : ":") + seconds;
    }

    private static void bindSeek(final Views bound) {
        if (bound.seekBound || bound.progress == null) return;
        bound.seekBound = true;
        final SeekBar seekBar = bound.progress;
        final Activity activity = bound.activity;
        seekBar.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                        && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
                    return false;
                seeking = true;
                int width = seekBar.getWidth() - seekBar.getPaddingLeft()
                        - seekBar.getPaddingRight();
                float x = event.getX() - seekBar.getPaddingLeft();
                float ratio = width <= 0 ? 0f : Math.max(0f, Math.min(1f, x / width));
                int target = Math.round(ratio * seekBar.getMax());
                seekBar.setProgress(target);
                if (bound.elapsed != null) {
                    String value = clock(target);
                    if (!value.equals(shownPosition)) {
                        shownPosition = value;
                        bound.elapsed.setText(value);
                    }
                }
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

    // -------------------------------------------------------------- transport

    private static void applyTransport(Activity activity, Views bound, Bundle state) {
        if (!bound.actionsBound) {
            bound.actionsBound = true;
            bindAction(activity, bound.prev, NetworkStreamService.ACTION_PREVIOUS);
            bindAction(activity, bound.next, NetworkStreamService.ACTION_NEXT);
            bindAction(activity, bound.play, NetworkStreamService.ACTION_TOGGLE);
        }
        ImageView play = bound.play;
        if (play == null) return;
        boolean playing = state.getBoolean("playing", false);
        // Resolving the id and inflating the vector to compare drawables cost a
        // few milliseconds on every one-second tick. The icon only has to change
        // when the transport state changes or the page was just re-bound.
        if (hasPlayingState && lastPlaying == playing && bound.iconApplied) return;
        int drawable = playing ? ids.playIcon : ids.pauseIcon;
        if (drawable != 0) play.setImageResource(drawable);
        lastPlaying = playing;
        hasPlayingState = true;
        bound.iconApplied = true;
    }

    private static void bindAction(final Activity activity, View view, final String action) {
        if (view == null) return;
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                activity.startService(
                        new Intent(activity, NetworkStreamService.class).setAction(action));
            }
        });
    }

    private static void setText(TextView view, String value) {
        if (view != null && !TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    // ----------------------------------------------------------------- lyrics

    private static void applyLyrics(Views bound, Bundle state) {
        TextView view = bound.lyric;
        ScrollView scroll = bound.lyricScroll;
        if (view == null || scroll == null) return;

        if (scroll.getVisibility() != View.VISIBLE) scroll.setVisibility(View.VISIBLE);
        if (bound.lyricScrim != null && bound.lyricScrim.getVisibility() != View.VISIBLE)
            bound.lyricScrim.setVisibility(View.VISIBLE);
        if (bound.lyricView != null && bound.lyricView.getVisibility() != View.GONE)
            bound.lyricView.setVisibility(View.GONE);
        if (bound.lyricView2 != null && bound.lyricView2.getVisibility() != View.GONE)
            bound.lyricView2.setVisibility(View.GONE);
        if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);

        if (!bound.lyricPadded) {
            bound.lyricPadded = true;
            // Half a viewport of padding is what lets the first and last line
            // reach the vertical centre of the screen.
            int pad = Math.max(1,
                    bound.activity.getResources().getDisplayMetrics().heightPixels / 2 - 40);
            view.setPadding(view.getPaddingLeft(), pad, view.getPaddingRight(), pad);
        }
        if (!bound.lyricTouchBound) {
            bound.lyricTouchBound = true;
            scroll.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                        holdManualScroll(false);
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        holdManualScroll(true);
                    }
                    return false;
                }
            });
        }

        int revision = state.getInt("lyricRevision", 0);
        String[] lines = state.getStringArray("lyricAll");
        if (revision != lyricRevision && lines != null) cacheLyrics(lines, revision);
        pendingLyricIndex = state.getInt("lyricCurrent", 0);
        lyricFallback = state.getString("lyric", "暂无歌词");

        if (lyricSpannable == null || view.getText() != lyricSpannable) installLyricText(view);
        if (SystemClock.uptimeMillis() < manualLyricScrollUntil) return;
        boolean moved = moveHighlight(bound, false);
        // Also catch up after the user swipes back to a page that fell behind
        // while it was off screen.
        if (moved || bound.centerPending) centerCurrentLyric(bound, true);
    }

    private static void holdManualScroll(boolean scheduleRecenter) {
        manualLyricScrollUntil = SystemClock.uptimeMillis() + MANUAL_SCROLL_HOLD_MS;
        MAIN.removeCallbacks(LYRIC_RECENTER);
        if (scheduleRecenter) MAIN.postDelayed(LYRIC_RECENTER, MANUAL_SCROLL_HOLD_MS + 50);
    }

    private static void cacheLyrics(String[] lines, int revision) {
        lyricLines = lines == null ? new String[0] : lines.clone();
        lyricOffsets = new int[lyricLines.length];
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lyricLines.length; i++) {
            if (i > 0) builder.append('\n');
            lyricOffsets[i] = builder.length();
            builder.append(lyricLines[i]);
        }
        lyricText = builder.toString();
        lyricRevision = revision;
        lyricSpannable = null;
        renderedLyricIndex = Integer.MIN_VALUE;
    }

    /**
     * Installs the whole lyric block once per song. Everything after this is a
     * span move, so the text is measured exactly once per song rather than once
     * per line.
     */
    private static void installLyricText(TextView view) {
        String body = lyricLines.length == 0 ? lyricFallback : lyricText;
        SpannableString span = new SpannableString(body);
        view.setTextColor(LYRIC_DIM);
        view.setText(span, TextView.BufferType.SPANNABLE);
        CharSequence installed = view.getText();
        lyricSpannable = installed instanceof Spannable ? (Spannable) installed : span;
        lyricHighlight = new ForegroundColorSpan(LYRIC_ACTIVE);
        renderedLyricIndex = Integer.MIN_VALUE;
        lyricHighlightStart = 0;
    }

    /**
     * Moves the highlight to the current line.
     *
     * @return true when the highlighted line actually changed
     */
    private static boolean moveHighlight(Views bound, boolean force) {
        TextView view = bound.lyric;
        if (view == null || lyricSpannable == null) return false;
        if (lyricLines.length == 0) {
            renderedLyricIndex = 0;
            lyricHighlightStart = 0;
            return false;
        }
        int current = Math.max(0, Math.min(pendingLyricIndex, lyricLines.length - 1));
        if (!force && current == renderedLyricIndex) return false;
        int start = lyricOffsets[current];
        int end = start + lyricLines[current].length();
        if (end > lyricSpannable.length()) return false;
        // ForegroundColorSpan is not metric-affecting: this invalidates the
        // affected lines without re-measuring or re-laying out the block.
        lyricSpannable.removeSpan(lyricHighlight);
        if (end > start) {
            lyricSpannable.setSpan(lyricHighlight, start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        renderedLyricIndex = current;
        lyricHighlightStart = start;
        return true;
    }

    private static void centerCurrentLyric(final Views bound, final boolean animate) {
        final ScrollView scroll = bound.lyricScroll;
        final TextView view = bound.lyric;
        if (scroll == null || view == null) return;
        // The lyric page stays attached as an off-screen ViewPager page. While
        // the player page is showing there is nothing to re-centre, so skip the
        // post, the Layout queries and the scroller entirely and catch up the
        // next time the page is actually on screen.
        if (!onScreen(scroll)) {
            bound.centerPending = true;
            return;
        }
        bound.centerPending = false;
        scroll.post(new Runnable() {
            @Override public void run() {
                if (view.getLayout() == null) return;
                CharSequence body = view.getText();
                int offset = Math.min(lyricHighlightStart, Math.max(0, body.length() - 1));
                int firstLine = view.getLayout().getLineForOffset(offset);
                int logicalEnd = offset;
                while (logicalEnd < body.length() && body.charAt(logicalEnd) != '\n') logicalEnd++;
                int lastLine = view.getLayout().getLineForOffset(Math.max(offset, logicalEnd - 1));
                int center = view.getCompoundPaddingTop()
                        + (view.getLayout().getLineTop(firstLine)
                        + view.getLayout().getLineBottom(lastLine)) / 2;
                int maxScroll = Math.max(0, view.getHeight() - scroll.getHeight());
                int target = Math.max(0,
                        Math.min(center - scroll.getHeight() / 2, maxScroll));
                if (animate && onScreen(scroll)) scroll.smoothScrollTo(0, target);
                else scroll.scrollTo(0, target);
            }
        });
    }

    /** True only while the view occupies most of the panel, i.e. is the live page. */
    private static boolean onScreen(View view) {
        if (!view.isShown()) return false;
        Rect visible = new Rect();
        return view.getGlobalVisibleRect(visible)
                && visible.width() >= view.getWidth() * 9 / 10
                && visible.height() >= view.getHeight() * 9 / 10;
    }

    /** Consumes crown rotation only while the full lyric page is actually visible. */
    public static boolean onRotary(Activity activity, MotionEvent event) {
        Views bound = views;
        if (bound == null || bound.activity != activity) return false;
        ScrollView scroll = bound.lyricScroll;
        if (scroll == null || !scroll.isShown()) return false;
        Rect visible = new Rect();
        if (!scroll.getGlobalVisibleRect(visible)
                || visible.width() < scroll.getWidth() / 2
                || visible.height() < scroll.getHeight() / 2) return false;
        float axis = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        if (axis == 0f) return false;
        int amount = Math.round(-axis * 56f
                * activity.getResources().getDisplayMetrics().density);
        scroll.scrollBy(0, amount);
        holdManualScroll(true);
        return true;
    }

    // ------------------------------------------------------------------ cover

    private static void applyCover(Views bound, Bundle state) {
        loadCover(bound.activity, state.getString("cover", ""));
        Bitmap bitmap = coverBitmap;
        if (bitmap == null || appliedCoverBitmap == bitmap) return;
        ImageView cover = bound.cover;
        if (cover != null) {
            cover.setImageBitmap(bitmap);
            if (cover.getVisibility() != View.VISIBLE) cover.setVisibility(View.VISIBLE);
        }
        // Keep the master blur/flowing background modes fed with the same art so
        // they stay usable for network tracks (UI-006).
        if (bound.blur != null) bound.blur.setImageBitmap(bitmap);
        if (bound.flowing != null) bound.flowing.setImageBitmap(bitmap);
        appliedCoverBitmap = bitmap;
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
                            if (activityRef.get() != activity
                                    || !cover.equals(requestedCover)) return;
                            apply();
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

    private static byte[] readBytes(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2;
        return sample;
    }
}
