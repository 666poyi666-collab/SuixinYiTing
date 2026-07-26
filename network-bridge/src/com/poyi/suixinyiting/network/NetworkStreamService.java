package com.poyi.suixinyiting.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NetworkStreamService extends Service {
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String CODEC_CHANGED_ACTION =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    public static final String ACTION_STATE = "com.poyi.suixinyiting.network.STATE";
    public static final String ACTION_PLAY = "com.poyi.suixinyiting.network.PLAY";
    public static final String ACTION_NEXT = "com.poyi.suixinyiting.network.NEXT";
    public static final String ACTION_PREVIOUS = "com.poyi.suixinyiting.network.PREVIOUS";
    public static final String ACTION_TOGGLE = "com.poyi.suixinyiting.network.TOGGLE";
    public static final String ACTION_STOP = "com.poyi.suixinyiting.network.STOP";
    public static final String ACTION_SET_QUALITY = "com.poyi.suixinyiting.network.SET_QUALITY";
    public static final String ACTION_SEEK = "com.poyi.suixinyiting.network.SEEK";
    public static final String ACTION_REQUEST_STATE =
            "com.poyi.suixinyiting.network.REQUEST_STATE";
    public static final String ACTION_SET_UI_ACTIVE =
            "com.poyi.suixinyiting.network.SET_UI_ACTIVE";
    public static final String ACTION_CLEAR_CACHE =
            "com.poyi.suixinyiting.network.CLEAR_CACHE";
    /** Routes an in-app volume gesture to the system media stream. */
    public static final String ACTION_ADJUST_VOLUME =
            "com.poyi.suixinyiting.network.ADJUST_VOLUME";
    public static final String ACTION_SET_VOLUME = "com.poyi.suixinyiting.network.SET_VOLUME";
    public static final String ACTION_PREFETCH_POLICY_CHANGED =
            "com.poyi.suixinyiting.network.PREFETCH_POLICY_CHANGED";
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final java.util.concurrent.ExecutorService cacheMaintenance =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile MediaPlayer player;
    private volatile MediaPlayer preparingPlayer;
    private volatile RangeCacheDataSource playerSource;
    private volatile RangeCacheDataSource preparingSource;
    private MediaSession session;
    private NeteaseWebApi api;
    private PlaylistStore store;
    private ShuffleBag shuffle;
    private AudioCacheStore cache;
    private AudioPrefetchManager prefetch;
    private AudioManager audioManager;
    private float currentOutputGain = 1f;
    private int gainGeneration;
    private String outputCodec = "";
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (VOLUME_CHANGED_ACTION.equals(intent.getAction())
                    && intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    == AudioManager.STREAM_MUSIC) {
                // Nothing to re-gain: the system stream is the only attenuator.
                // Push the new index so the player UI stays in lockstep with it.
                logSystemVolume("system change");
                broadcastState(trackId > 0 || player != null);
            }
        }
    };
    private final BroadcastReceiver codecReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateOutputCodec(intent, true);
        }
    };
    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) {
                    if (change < 0 && player != null
                            && player.isPlaying()) {
                        pausePlayback("");
                    }
                }
            };
    private long playlistId;
    private long trackId;
    private NetworkTrack currentTrack;
    private StreamVariant currentVariant;
    private Handler mainHandler;
    private long[] lyricTimes = new long[0];
    private String[] lyricLines = new String[0];
    private int lyricRevision;
    private boolean uiActive;
    private int playGeneration;
    private PlaybackPhase phase = PlaybackPhase.IDLE;
    private boolean desiredPlaying;
    private boolean offlineCached;
    private String playbackError = "";
    private String playbackErrorCode = "";
    private volatile CacheStats lastCacheClear;
    private boolean restoreAttempted;
    private int pendingPosition;
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            persistPlayback();
            if (desiredPlaying && mainHandler != null)
                mainHandler.postDelayed(this, 15000);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            broadcastState(true);
            if (uiActive && mainHandler != null && player != null && player.isPlaying())
                mainHandler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.i("SuixinNetease", "audio route became noisy; pausing");
                pausePlayback("耳机已断开");
            }
        }
    };
    private final BroadcastReceiver policyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (prefetch != null) {
                prefetch.cancel();
                if (desiredPlaying) schedulePrefetch();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        api = new NeteaseWebApi(this); store = new PlaylistStore(this);
        cache = AudioCacheStore.get(this); prefetch = new AudioPrefetchManager(this, api, cache);
        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        registerReceiver(volumeReceiver, new IntentFilter(VOLUME_CHANGED_ACTION));
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        IntentFilter policy = new IntentFilter();
        policy.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        policy.addAction(Intent.ACTION_BATTERY_CHANGED);
        policy.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        registerReceiver(policyReceiver, policy);
        outputCodec = getSharedPreferences("network_player", MODE_PRIVATE)
                .getString("output_codec", "");
        Intent currentCodec = registerReceiver(
                codecReceiver, new IntentFilter(CODEC_CHANGED_ACTION));
        if (currentCodec != null) updateOutputCodec(currentCodec, false);
        shuffle = new ShuffleBag(getSharedPreferences("network_player", MODE_PRIVATE));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("network_music", "网络音乐",
                    NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "SuixinNetwork");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        Intent mediaButtons = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(this, NetworkMediaButtonReceiver.class);
        session.setMediaButtonReceiver(PendingIntent.getBroadcast(this, 8, mediaButtons,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (android.os.Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE : 0)));
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                resumePlayback();
            }
            @Override public void onPause() {
                pausePlayback("");
            }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seekTo(position); }
            @Override public void onStop() {
                desiredPlaying = false;
                persistPlayback();
                stopSelf();
            }
        });
        session.setPlaybackToLocal(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        session.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (!restoreAttempted && !ACTION_PLAY.equals(action)) restorePlayback();
        if (intent == null) return START_STICKY;
        if (ACTION_PLAY.equals(action)) {
            restoreAttempted = true;
            playlistId = intent.getLongExtra("playlist", 0);
            trackId = intent.getLongExtra("track", 0);
            long[] supplied = intent.getLongArrayExtra("source_ids");
            long[] fullPool = supplied != null && supplied.length > 0
                    ? supplied : store.allPlayableIds(playlistId);
            android.content.SharedPreferences.Editor queueState =
                    getSharedPreferences("network_player", MODE_PRIVATE).edit()
                            .putLong("last_playlist", playlistId);
            if (supplied != null && supplied.length > 0)
                queueState.putString("last_queue_ids", encodeIds(supplied));
            else queueState.remove("last_queue_ids");
            queueState.apply();
            Log.i("SuixinNetease", "shuffle pool=" + fullPool.length);
            shuffle.load(playlistId, fullPool);
            shuffle.select(trackId);
            beginPlay(trackId, true, 0);
        } else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_TOGGLE.equals(action)) {
            if (desiredPlaying) pausePlayback(""); else resumePlayback();
        } else if (ACTION_SET_QUALITY.equals(action)) {
            String quality = intent.getStringExtra("quality");
            if (quality != null) getSharedPreferences("network_settings", MODE_PRIVATE)
                    .edit().putString("quality", quality).apply();
            prefetch.cancel();
            if (trackId > 0) beginPlay(trackId, desiredPlaying, currentPosition(), true);
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getIntExtra("position", 0));
        } else if (ACTION_ADJUST_VOLUME.equals(action)) {
            adjustSystemVolume(intent.getIntExtra("direction", 0),
                    intent.getBooleanExtra("showUi", false));
        } else if (ACTION_SET_VOLUME.equals(action)) {
            setSystemVolume(intent.getIntExtra("index", systemVolume()),
                    intent.getBooleanExtra("showUi", false));
        } else if (ACTION_REQUEST_STATE.equals(action)) {
            // Activities may be recreated after the most recent state broadcast.
            // Reply with the live player state instead of leaving the mother UI stale.
            broadcastState(trackId > 0 || player != null, true);
        } else if (ACTION_SET_UI_ACTIVE.equals(action)) {
            uiActive = intent.getBooleanExtra("active", false);
            Log.i("SuixinNetease", "playback UI active=" + uiActive);
            boolean playing = player != null && player.isPlaying();
            updateTicker(uiActive && playing);
            if (uiActive) broadcastState(trackId > 0 || player != null, true);
        } else if (ACTION_CLEAR_CACHE.equals(action)) {
            prefetch.cancel();
            cacheMaintenance.execute(new Runnable() { @Override public void run() {
                final CacheStats cleared = cache.clearInactive();
                lastCacheClear = cleared;
                Log.i("SuixinCache", "clear bytes=" + cleared.clearedBytes
                        + " pending=" + cleared.pendingBytes);
                if (mainHandler != null) mainHandler.post(new Runnable() {
                    @Override public void run() {
                        broadcastState(trackId > 0 || player != null, true);
                    }
                });
            }});
        } else if (ACTION_PREFETCH_POLICY_CHANGED.equals(action)) {
            prefetch.cancel();
            cacheMaintenance.execute(new Runnable() { @Override public void run() {
                cache.enforceLimit();
                if (desiredPlaying && mainHandler != null) mainHandler.post(new Runnable() {
                    @Override public void run() { schedulePrefetch(); }
                });
            }});
        } else if (ACTION_STOP.equals(action)) {
            desiredPlaying = false;
            persistPlayback();
            stopSelf();
        }
        return START_STICKY;
    }

    private void next() { long id = shuffle.next(); if (id > 0) beginPlay(id, true, 0); }
    private void previous() { long id = shuffle.previous(); if (id > 0) beginPlay(id, true, 0); }

    private void beginPlay(final long id, boolean shouldPlay, int resumePosition) {
        beginPlay(id, shouldPlay, resumePosition, false);
    }

    private void beginPlay(final long id, boolean shouldPlay, int resumePosition,
                           boolean forceOnline) {
        final int generation = ++playGeneration;
        desiredPlaying = shouldPlay;
        pendingPosition = Math.max(0, resumePosition);
        prefetch.cancel();
        if (preparingPlayer != null) releaseCandidate(preparingPlayer);
        if (player != null) {
            MediaPlayer old = player; RangeCacheDataSource oldSource = playerSource;
            player = null; playerSource = null;
            releasePlayer(old, oldSource);
        }
        play(id, 0, generation, resumePosition, forceOnline);
    }

    private void play(final long id, final int retry, final int generation,
                      final int resumePosition, final boolean forceOnline) {
        if (generation != playGeneration) return;
        trackId = id;
        phase = PlaybackPhase.RESOLVING; offlineCached = false; playbackError = "";
        playbackErrorCode = "";
        Log.i("SuixinNetease", "play track=" + id + " retry=" + retry
                + " generation=" + generation);
        NetworkTrack found = store.find(playlistId, id);
        if (found == null) found = store.findAny(id);
        final NetworkTrack track = found;
        currentTrack = track;
        currentVariant = null;
        Log.i("SuixinNetease", "metadata cover="
                + (track == null ? "<missing>" : track.coverUrl));
        lyricTimes = new long[0];
        lyricLines = new String[0];
        lyricRevision++;
        broadcastState(true, true);
        if (track != null) session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, Long.toString(track.id)).build());
        startForeground(7, notification(track == null ? "网络音乐" : track.title,
                track == null ? "正在连接…" : track.artist));
        io.execute(new Runnable() { @Override public void run() {
            RangeCacheDataSource source = null;
            MediaPlayer candidate = null;
            try {
                String requested = getSharedPreferences("network_settings", MODE_PRIVATE)
                        .getString("quality", "lossless");
                source = forceOnline ? null : RangeCacheDataSource.openOffline(cache, id);
                boolean offlineValue = source != null;
                StreamVariant variant;
                if (offlineValue) {
                    AudioCacheKey key = source.entry().key;
                    variant = new StreamVariant("", requested, key.quality, 0, key.format,
                            Long.MAX_VALUE, "完整缓存");
                } else {
                    try {
                        variant = api.resolve(id, requested);
                        if (generation != playGeneration) { source = close(source); return; }
                        source = new RangeCacheDataSource(cache, id, variant);
                    } catch (Exception onlineError) {
                        source = RangeCacheDataSource.openCached(cache, id);
                        if (source == null) throw onlineError;
                        AudioCacheKey key = source.entry().key;
                        variant = new StreamVariant("", requested, key.quality, 0, key.format,
                                Long.MAX_VALUE, source.entry().isComplete()
                                ? "完整缓存" : "部分缓存");
                        offlineValue = true;
                    }
                }
                final boolean offline = offlineValue;
                LyricData lyric = LyricData.empty();
                try { lyric = parseLyricData(api.lyric(id)); }
                catch (Exception e) { Log.w("SuixinNetease", "lyric failed: " + e.getMessage()); }
                if (generation != playGeneration) { source = close(source); return; }
                Log.i("SuixinNetease", "resolved track=" + id + " requested="
                        + variant.requestedLevel + " actual=" + variant.actualLevel
                        + " bitrate=" + variant.bitrate + " format=" + variant.format
                        + " offline=" + offline);
                final StreamVariant resolvedVariant = variant;
                final LyricData resolvedLyric = lyric;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (generation != playGeneration || trackId != id) return;
                        currentVariant = resolvedVariant;
                        lyricTimes = resolvedLyric.times;
                        lyricLines = resolvedLyric.lines;
                        lyricRevision++;
                        offlineCached = offline;
                        phase = offline ? PlaybackPhase.OFFLINE_CACHED : PlaybackPhase.BUFFERING;
                        broadcastState(true, true);
                    }
                });
                final RangeCacheDataSource candidateSource = source;
                final MediaPlayer nextPlayer = new MediaPlayer();
                candidate = nextPlayer;
                preparingPlayer = nextPlayer; preparingSource = candidateSource;
                nextPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                nextPlayer.setDataSource(candidateSource);
                nextPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer mp) {
                        if (generation != playGeneration || mp != preparingPlayer) {
                            releasePlayer(mp, candidateSource); return;
                        }
                        Log.i("SuixinNetease", "prepared track=" + id
                                + " generation=" + generation);
                        MediaPlayer old = player; RangeCacheDataSource oldSource = playerSource;
                        player = mp; playerSource = candidateSource;
                        preparingPlayer = null; preparingSource = null;
                        applyOutputGain(false);
                        if (resumePosition > 0) {
                            pendingPosition = Math.min(resumePosition, mp.getDuration());
                            mp.seekTo(pendingPosition);
                        } else pendingPosition = 0;
                        if (desiredPlaying) { requestAudioFocus(); mp.start(); }
                        if (old != null && old != mp) releasePlayer(old, oldSource);
                        phase = desiredPlaying
                                ? (offline ? PlaybackPhase.OFFLINE_CACHED : PlaybackPhase.PLAYING)
                                : PlaybackPhase.PAUSED;
                        updateState(desiredPlaying);
                        persistPlayback();
                        if (desiredPlaying) schedulePrefetch();
                    }
                });
                nextPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) {
                        if (generation == playGeneration && mp == player) next();
                    }
                });
                nextPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        if (generation != playGeneration) {
                            releasePlayer(mp, candidateSource); return true;
                        }
                        Log.e("SuixinNetease", "media error track=" + id + " what=" + what
                                + " extra=" + extra + " retry=" + retry);
                        if (retry < 1 && !offline) {
                            releasePlayer(mp, candidateSource);
                            play(id, retry + 1, generation, resumePosition, forceOnline);
                            return true;
                        }
                        failPlayback(generation, offline
                                ? "网络不可用，缓存内容不足" : "歌曲加载失败");
                        return true;
                    }
                });
                nextPlayer.prepareAsync();
            } catch (Exception e) {
                Log.e("SuixinNetease", "play failed track=" + id + " retry=" + retry, e);
                if (candidate != null) releasePlayer(candidate, source); else close(source);
                failPlayback(generation, e.getMessage() == null ? "歌曲加载失败" : e.getMessage());
            }
        }});
    }

    private void schedulePrefetch() {
        if (prefetch == null || shuffle == null) return;
        prefetch.schedule(shuffle.peekNext(256),
                getSharedPreferences("network_settings", MODE_PRIVATE)
                        .getString("quality", "lossless"), playGeneration);
    }

    private void pausePlayback(String reason) {
        desiredPlaying = false;
        MediaPlayer active = player;
        if (active != null && safePlaying(active)) {
            try { active.pause(); } catch (Exception ignored) {}
        }
        phase = trackId > 0 ? PlaybackPhase.PAUSED : PlaybackPhase.IDLE;
        if (reason != null && !reason.isEmpty()) playbackError = reason;
        if (prefetch != null) prefetch.cancel();
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        persistPlayback();
        updateState(false);
    }

    private void resumePlayback() {
        desiredPlaying = true;
        playbackError = ""; playbackErrorCode = "";
        MediaPlayer active = player;
        if (active == null) {
            if (trackId > 0) beginPlay(trackId, true, currentPosition());
            return;
        }
        try {
            requestAudioFocus();
            active.start();
            phase = offlineCached ? PlaybackPhase.OFFLINE_CACHED : PlaybackPhase.PLAYING;
            updateState(true);
            schedulePrefetch();
        } catch (Exception error) {
            failPlayback(playGeneration, "播放恢复失败");
        }
    }

    private void failPlayback(final int generation, final String message) {
        if (mainHandler != null && Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() { @Override public void run() {
                failPlayback(generation, message);
            }});
            return;
        }
        if (generation != playGeneration) return;
        desiredPlaying = false;
        playbackError = message == null || message.isEmpty() ? "歌曲加载失败" : message;
        playbackErrorCode = playbackError.contains("缓存") ? "CACHE_GAP" : "PLAYBACK_FAILED";
        phase = PlaybackPhase.ERROR;
        if (preparingPlayer != null) releaseCandidate(preparingPlayer);
        updateState(false);
        persistPlayback();
    }

    private int currentPosition() {
        MediaPlayer active = player;
        if (active != null) try {
            pendingPosition = Math.max(0, active.getCurrentPosition());
            return pendingPosition;
        }
        catch (Exception ignored) {}
        return pendingPosition;
    }

    private void persistPlayback() {
        if (trackId <= 0) return;
        getSharedPreferences("network_player", MODE_PRIVATE).edit()
                .putLong("last_playlist", playlistId)
                .putLong("restore_track", trackId)
                .putInt("restore_position", currentPosition())
                .putBoolean("restore_desired_playing", desiredPlaying)
                .putLong("restore_saved_at", System.currentTimeMillis())
                .apply();
    }

    private void restorePlayback() {
        if (restoreAttempted) return;
        restoreAttempted = true;
        android.content.SharedPreferences prefs =
                getSharedPreferences("network_player", MODE_PRIVATE);
        long restoredTrack = prefs.getLong("restore_track", 0);
        if (restoredTrack <= 0) return;
        playlistId = prefs.getLong("last_playlist", 0);
        long[] queue = decodeIds(prefs.getString("last_queue_ids", ""));
        if (queue.length == 0) queue = store.allPlayableIds(playlistId);
        shuffle.load(playlistId, queue);
        trackId = restoredTrack;
        boolean resume = prefs.getBoolean("restore_desired_playing", false)
                && hasHeadphoneRoute();
        int position = Math.max(0, prefs.getInt("restore_position", 0));
        Log.i("SuixinNetease", "restore track=" + trackId + " position=" + position
                + " desired=" + resume + " headphones=" + hasHeadphoneRoute());
        beginPlay(trackId, resume, position);
    }

    private boolean hasHeadphoneRoute() {
        if (audioManager == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || (android.os.Build.VERSION.SDK_INT >= 31
                        && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
            }
        }
        return audioManager.isWiredHeadsetOn() || audioManager.isBluetoothA2dpOn();
    }

    private static boolean safePlaying(MediaPlayer value) {
        if (value == null) return false;
        try { return value.isPlaying(); } catch (Exception ignored) { return false; }
    }

    private void releaseCandidate(MediaPlayer candidate) {
        RangeCacheDataSource source = candidate == preparingPlayer ? preparingSource : null;
        if (candidate == preparingPlayer) {
            preparingPlayer = null; preparingSource = null;
        }
        releasePlayer(candidate, source);
    }

    private static void releasePlayer(MediaPlayer value, RangeCacheDataSource source) {
        if (value != null) {
            try { value.reset(); } catch (Exception ignored) {}
            try { value.release(); } catch (Exception ignored) {}
        }
        close(source);
    }

    private static RangeCacheDataSource close(RangeCacheDataSource source) {
        if (source != null) try { source.close(); } catch (Exception ignored) {}
        return null;
    }

    private void updateState(boolean playing) {
        if (audioManager != null) {
            Log.d("SuixinNetease", "system music volume="
                    + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                    + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }
        int stateCode = phase == PlaybackPhase.ERROR
                ? android.media.session.PlaybackState.STATE_ERROR
                : playing ? android.media.session.PlaybackState.STATE_PLAYING
                : phase == PlaybackPhase.BUFFERING || phase == PlaybackPhase.RESOLVING
                ? android.media.session.PlaybackState.STATE_BUFFERING
                : android.media.session.PlaybackState.STATE_PAUSED;
        android.media.session.PlaybackState.Builder stateBuilder =
                new android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY |
                        android.media.session.PlaybackState.ACTION_PAUSE |
                        android.media.session.PlaybackState.ACTION_SEEK_TO |
                        android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT |
                        android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(stateCode, currentPosition(), playing ? 1f : 0f);
        if (phase == PlaybackPhase.ERROR) stateBuilder.setErrorMessage(playbackError);
        session.setPlaybackState(stateBuilder.build());
        broadcastState(true);
        updateTicker(uiActive && playing);
        if (mainHandler != null) {
            mainHandler.removeCallbacks(checkpoint);
            if (desiredPlaying) mainHandler.postDelayed(checkpoint, 15000);
        }
        String title = currentTrack == null ? "网络音乐" : currentTrack.title;
        String subtitle = currentTrack == null ? "" : currentTrack.artist;
        startForeground(7, notification(title, subtitle));
    }

    private void seekTo(long requestedPosition) {
        MediaPlayer active = player;
        if (active == null) return;
        int duration = active.getDuration();
        int position = (int) Math.max(0, Math.min(requestedPosition, duration));
        pendingPosition = position;
        active.seekTo(position);
        Log.i("SuixinNetease", "seek position=" + position + " duration=" + duration);
        updateState(active.isPlaying());
        persistPlayback();
    }

    private void broadcastState(boolean active) {
        broadcastState(active, false);
    }

    private void broadcastState(boolean active, boolean includeLyrics) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra("active", active);
        state.putExtra("track", trackId);
        state.putExtra("title", currentTrack == null ? "网络音乐" : currentTrack.title);
        state.putExtra("artist", currentTrack == null ? "" : currentTrack.artist);
        state.putExtra("cover", currentTrack == null ? "" : currentTrack.coverUrl);
        StreamVariant variant = currentVariant;
        state.putExtra("requestedQuality", variant == null ? "" : variant.requestedLevel);
        state.putExtra("actualQuality", variant == null ? "" : variant.actualLevel);
        state.putExtra("bitrate", variant == null ? 0 : variant.bitrate);
        state.putExtra("format", variant == null ? "" : variant.format);
        state.putExtra("qualityReason", variant == null ? "" : variant.fallbackReason);
        state.putExtra("outputCodec", outputCodec);
        // The UI never keeps a private volume; it mirrors STREAM_MUSIC verbatim.
        state.putExtra("volume", systemVolume());
        state.putExtra("volumeMax", systemVolumeMax());
        state.putExtra("phase", phase.name());
        state.putExtra("generation", playGeneration);
        state.putExtra("offlineCached", offlineCached);
        state.putExtra("errorCode", playbackErrorCode);
        state.putExtra("errorMessage", playbackError);
        state.putExtra("buffering", phase == PlaybackPhase.RESOLVING
                || phase == PlaybackPhase.BUFFERING);
        boolean playing = safePlaying(player);
        int position = currentPosition();
        int duration = 0;
        if (player != null) try { duration = Math.max(0, player.getDuration()); }
        catch (Exception ignored) {}
        state.putExtra("playing", playing);
        state.putExtra("position", position);
        state.putExtra("duration", duration);
        state.putExtra("lyric", lyricLine(position, 0));
        int lyricIndex = lyricIndex(position);
        state.putExtra("lyricRevision", lyricRevision);
        if (includeLyrics) state.putExtra("lyricAll", lyricLines);
        state.putExtra("lyricCurrent", lyricIndex);
        if (cache != null) {
            CacheStats stats = cache.stats();
            state.putExtra("cacheBytes", stats.bytes);
            state.putExtra("cacheLimitBytes", stats.limitBytes);
            state.putExtra("cacheFiles", stats.files);
            state.putExtra("cacheComplete", stats.complete);
            state.putExtra("cachePartial", stats.partial);
        }
        CacheStats cleared = lastCacheClear;
        if (cleared != null) {
            state.putExtra("cacheClearedBytes", cleared.clearedBytes);
            state.putExtra("cachePendingBytes", cleared.pendingBytes);
            state.putExtra("cacheFailedBytes", cleared.failedBytes);
        }
        sendBroadcast(state);
    }

    private static LyricData parseLyricData(String raw) {
        ArrayList<Long> times = new ArrayList<>();
        ArrayList<String> lines = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)");
        if (raw != null) for (String row : raw.split("\\n")) {
            Matcher matcher = pattern.matcher(row.trim());
            if (!matcher.matches()) continue;
            long minute = Long.parseLong(matcher.group(1));
            double second = Double.parseDouble(matcher.group(2));
            String text = matcher.group(3).trim();
            if (text.isEmpty()) continue;
            times.add(minute * 60000L + (long) (second * 1000.0));
            lines.add(text);
        }
        long[] parsedTimes = new long[times.size()];
        String[] parsedLines = lines.toArray(new String[0]);
        for (int i = 0; i < times.size(); i++) parsedTimes[i] = times.get(i);
        return new LyricData(parsedTimes, parsedLines);
    }

    private static final class LyricData {
        final long[] times;
        final String[] lines;
        LyricData(long[] times, String[] lines) { this.times = times; this.lines = lines; }
        static LyricData empty() { return new LyricData(new long[0], new String[0]); }
    }

    private String lyricLine(long position, int delta) {
        if (lyricTimes.length == 0) return delta == 0 ? "暂无歌词" : "";
        int index = lyricIndex(position);
        index += delta;
        return index >= 0 && index < lyricLines.length ? lyricLines[index] : "";
    }

    private int lyricIndex(long position) {
        return LyricIndex.at(lyricTimes, position);
    }

    private void updateTicker(boolean playing) {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(ticker);
        if (playing) mainHandler.postDelayed(ticker, 1000);
    }

    private void requestAudioFocus() {
        if (audioManager != null) audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private static String codecLabel(BluetoothCodecConfig config) {
        String codec;
        int bitrate = 0;
        switch (config.getCodecType()) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                codec = "AAC";
                // OWW221 Qualcomm offload reports a fixed 165 kbps AAC stream.
                bitrate = android.os.Build.MODEL.equalsIgnoreCase("OWW221") ? 165 : 0;
                break;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                codec = "aptX HD"; bitrate = 576; break;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                codec = "aptX"; bitrate = 352; break;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
                codec = "LDAC"; break;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                codec = "SBC"; break;
            default:
                codec = "Bluetooth";
        }
        StringBuilder label = new StringBuilder(codec);
        if (bitrate > 0) label.append(' ').append(bitrate).append('k');
        int sampleRate = sampleRate(config.getSampleRate());
        if (sampleRate > 0) label.append(" · ").append(sampleRate / 1000).append('k');
        int bits = bits(config.getBitsPerSample());
        if (bits > 0) label.append('/').append(bits).append("bit");
        return label.toString();
    }

    private void updateOutputCodec(Intent intent, boolean notify) {
        if (intent == null || !CODEC_CHANGED_ACTION.equals(intent.getAction())) return;
        BluetoothCodecStatus status = intent.getParcelableExtra(
                BluetoothCodecStatus.EXTRA_CODEC_STATUS);
        BluetoothCodecConfig config = status == null ? null : status.getCodecConfig();
        if (config == null) return;
        outputCodec = codecLabel(config);
        getSharedPreferences("network_player", MODE_PRIVATE).edit()
                .putString("output_codec", outputCodec).apply();
        Log.i("SuixinNetease", "bluetooth output=" + outputCodec
                + (notify ? " changed" : " restored"));
        if (notify) broadcastState(trackId > 0 || player != null);
    }

    private static int sampleRate(int value) {
        if ((value & BluetoothCodecConfig.SAMPLE_RATE_192000) != 0) return 192000;
        if ((value & BluetoothCodecConfig.SAMPLE_RATE_176400) != 0) return 176400;
        if ((value & BluetoothCodecConfig.SAMPLE_RATE_96000) != 0) return 96000;
        if ((value & BluetoothCodecConfig.SAMPLE_RATE_88200) != 0) return 88200;
        if ((value & BluetoothCodecConfig.SAMPLE_RATE_48000) != 0) return 48000;
        if ((value & BluetoothCodecConfig.SAMPLE_RATE_44100) != 0) return 44100;
        return 0;
    }

    private static int bits(int value) {
        if ((value & BluetoothCodecConfig.BITS_PER_SAMPLE_32) != 0) return 32;
        if ((value & BluetoothCodecConfig.BITS_PER_SAMPLE_24) != 0) return 24;
        if ((value & BluetoothCodecConfig.BITS_PER_SAMPLE_16) != 0) return 16;
        return 0;
    }

    /**
     * The system media stream is the single volume control (AUDIO-004).
     *
     * <p>AudioPolicy already applies the STREAM_MUSIC curve for this device
     * category — on OWW221 that is {@code (1,-58dB) (20,-40dB) (60,-17dB)
     * (100,-2dB)} for headsets. Multiplying the player by another linear
     * {@code index/max} factor stacked a second attenuation on top of it, so
     * 3/16 over A2DP landed near -55 dB instead of -41 dB. The player therefore
     * always runs at unity and only fades to avoid start/resume pops.
     */
    private void applyOutputGain(boolean animate) {
        final MediaPlayer active = player;
        if (active == null) return;
        final int generation = ++gainGeneration;
        if (!animate || mainHandler == null || currentOutputGain >= 1f) {
            currentOutputGain = 1f;
            try { active.setVolume(1f, 1f); } catch (Exception ignored) {}
            logSystemVolume("unity");
            return;
        }
        final float start = currentOutputGain;
        for (int step = 1; step <= 6; step++) {
            final int currentStep = step;
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (generation != gainGeneration || player != active) return;
                    float value = start + (1f - start) * currentStep / 6f;
                    currentOutputGain = value;
                    try { active.setVolume(value, value); } catch (Exception ignored) {}
                    if (currentStep == 6) logSystemVolume("fade-in");
                }
            }, currentStep * 20L);
        }
    }

    private void logSystemVolume(String reason) {
        if (audioManager == null) return;
        Log.i("SuixinNetease", "output gain=" + currentOutputGain + " (" + reason
                + ") system=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    /** Current system media volume, reported to the UI so it can mirror it exactly. */
    private int systemVolume() {
        return audioManager == null ? 0 : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private int systemVolumeMax() {
        return audioManager == null ? 1
                : Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    /**
     * Applies a user volume request to the system media stream. Every in-app
     * control routes through here so the app never keeps a private volume.
     */
    private void adjustSystemVolume(int direction, boolean showUi) {
        if (audioManager == null) return;
        int flags = showUi ? AudioManager.FLAG_SHOW_UI : 0;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags);
        } catch (SecurityException error) {
            Log.w("SuixinNetease", "volume adjust rejected: " + error.getMessage());
        }
        logSystemVolume("adjust " + direction);
        broadcastState(true);
    }

    private void setSystemVolume(int index, boolean showUi) {
        if (audioManager == null) return;
        int target = Math.max(0, Math.min(index, systemVolumeMax()));
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target,
                    showUi ? AudioManager.FLAG_SHOW_UI : 0);
        } catch (SecurityException error) {
            Log.w("SuixinNetease", "volume set rejected: " + error.getMessage());
        }
        logSystemVolume("set " + target);
        broadcastState(true);
    }

    private Notification notification(String title, String subtitle) {
        Intent open = new Intent(this, NetworkMusicActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 7, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26 ?
                new Notification.Builder(this, "network_music") : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title)
                .setContentText(subtitle).setContentIntent(content).setOngoing(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())).build();
    }

    @Override public void onDestroy() {
        persistPlayback();
        playGeneration++;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(ticker);
            mainHandler.removeCallbacks(checkpoint);
        }
        broadcastState(false);
        io.shutdownNow();
        cacheMaintenance.shutdownNow();
        if (prefetch != null) prefetch.shutdown();
        releaseCandidate(preparingPlayer);
        releasePlayer(player, playerSource);
        player = null; playerSource = null;
        if (session != null) { session.release(); session = null; }
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(codecReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(policyReceiver); } catch (Exception ignored) {}
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        stopForeground(true); super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static String encodeIds(long[] ids) {
        return IdCodec.encode(ids);
    }

    private static long[] decodeIds(String encoded) {
        return IdCodec.decode(encoded);
    }
}
