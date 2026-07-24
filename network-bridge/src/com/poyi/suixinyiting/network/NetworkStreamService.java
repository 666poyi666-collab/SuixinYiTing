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
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<Long, StreamVariant> prefetched = new ConcurrentHashMap<>();
    private MediaPlayer player;
    private MediaSession session;
    private NeteaseWebApi api;
    private PlaylistStore store;
    private ShuffleBag shuffle;
    private AudioManager audioManager;
    private float currentOutputGain = 1f;
    private int gainGeneration;
    private String outputCodec = "";
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (VOLUME_CHANGED_ACTION.equals(intent.getAction())
                    && intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    == AudioManager.STREAM_MUSIC) {
                applyOutputGain(true);
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
                        player.pause();
                        updateState(false);
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
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            broadcastState(true);
            if (mainHandler != null) mainHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        api = new NeteaseWebApi(this); store = new PlaylistStore(this);
        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        registerReceiver(volumeReceiver, new IntentFilter(VOLUME_CHANGED_ACTION));
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
                if (player != null) { requestAudioFocus(); player.start(); updateState(true); }
            }
            @Override public void onPause() {
                if (player != null) { player.pause(); updateState(false); }
            }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seekTo(position); }
            @Override public void onStop() { stopSelf(); }
        });
        session.setPlaybackToLocal(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        session.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
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
            play(trackId, 0);
        } else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_TOGGLE.equals(action) && player != null) {
            if (player.isPlaying()) { player.pause(); updateState(false); }
            else { requestAudioFocus(); player.start(); updateState(true); }
        } else if (ACTION_SET_QUALITY.equals(action)) {
            String quality = intent.getStringExtra("quality");
            if (quality != null) getSharedPreferences("network_settings", MODE_PRIVATE)
                    .edit().putString("quality", quality).apply();
            prefetched.clear();
            if (trackId > 0) play(trackId, 0);
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getIntExtra("position", 0));
        } else if (ACTION_REQUEST_STATE.equals(action)) {
            // Activities may be recreated after the most recent state broadcast.
            // Reply with the live player state instead of leaving the mother UI stale.
            broadcastState(trackId > 0 || player != null);
        } else if (ACTION_STOP.equals(action)) stopSelf();
        return START_STICKY;
    }

    private void next() { long id = shuffle.next(); if (id > 0) play(id, 0); }
    private void previous() { long id = shuffle.previous(); if (id > 0) play(id, 0); }

    private void play(final long id, final int retry) {
        trackId = id;
        Log.i("SuixinNetease", "play track=" + id + " retry=" + retry);
        NetworkTrack found = store.find(playlistId, id);
        if (found == null) found = store.findAny(id);
        final NetworkTrack track = found;
        currentTrack = track;
        currentVariant = null;
        Log.i("SuixinNetease", "metadata cover="
                + (track == null ? "<missing>" : track.coverUrl));
        lyricTimes = new long[0];
        lyricLines = new String[0];
        broadcastState(true);
        if (track != null) session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, Long.toString(track.id)).build());
        startForeground(7, notification(track == null ? "网络音乐" : track.title,
                track == null ? "正在连接…" : track.artist));
        io.execute(new Runnable() { @Override public void run() {
            try {
                StreamVariant variant = prefetched.remove(id);
                if (variant == null || variant.expiresAt < System.currentTimeMillis() + 30000)
                    variant = api.resolve(id, getSharedPreferences("network_settings", MODE_PRIVATE)
                            .getString("quality", "lossless"));
                try { parseLyric(api.lyric(id)); }
                catch (Exception e) { Log.w("SuixinNetease", "lyric failed: " + e.getMessage()); }
                Log.i("SuixinNetease", "resolved track=" + id + " requested="
                        + variant.requestedLevel + " actual=" + variant.actualLevel
                        + " bitrate=" + variant.bitrate + " format=" + variant.format);
                currentVariant = variant;
                final String url = variant.url;
                final MediaPlayer next = new MediaPlayer();
                next.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                next.setDataSource(new RangeCacheDataSource(new java.io.File(getCacheDir(), "network_audio"), id, url));
                next.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer mp) {
                        Log.i("SuixinNetease", "prepared track=" + id);
                        requestAudioFocus();
                        MediaPlayer old = player; player = mp;
                        applyOutputGain(false);
                        mp.start();
                        if (old != null) { old.stop(); old.release(); }
                        updateState(true); prefetchTwo();
                        mainHandler.removeCallbacks(ticker);
                        mainHandler.post(ticker);
                    }
                });
                next.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) { next(); }
                });
                next.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        Log.e("SuixinNetease", "media error track=" + id + " what=" + what
                                + " extra=" + extra + " retry=" + retry);
                        prefetched.remove(id);
                        if (retry < 1) {
                            if (player == mp) player = null;
                            try { mp.reset(); mp.release(); } catch (Exception ignored) {}
                            play(id, retry + 1);
                            return true;
                        }
                        return false;
                    }
                });
                next.prepareAsync();
            } catch (Exception e) {
                Log.e("SuixinNetease", "play failed track=" + id + " retry=" + retry, e);
                stopForeground(false);
            }
        }});
    }

    private void prefetchTwo() {
        io.execute(new Runnable() { @Override public void run() {
            long[] ids = shuffle.peekNext(2);
            String quality = getSharedPreferences("network_settings", MODE_PRIVATE)
                    .getString("quality", "lossless");
            for (long id : ids) {
                try { prefetched.put(id, api.resolve(id, quality)); } catch (Exception ignored) {}
            }
        }});
    }

    private void updateState(boolean playing) {
        if (audioManager != null) {
            Log.d("SuixinNetease", "system music volume="
                    + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                    + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }
        android.media.session.PlaybackState state = new android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY |
                        android.media.session.PlaybackState.ACTION_PAUSE |
                        android.media.session.PlaybackState.ACTION_SEEK_TO |
                        android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT |
                        android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? android.media.session.PlaybackState.STATE_PLAYING :
                        android.media.session.PlaybackState.STATE_PAUSED,
                        player == null ? 0 : player.getCurrentPosition(), 1f).build();
        session.setPlaybackState(state);
        broadcastState(true);
    }

    private void seekTo(long requestedPosition) {
        MediaPlayer active = player;
        if (active == null) return;
        int duration = active.getDuration();
        int position = (int) Math.max(0, Math.min(requestedPosition, duration));
        active.seekTo(position);
        Log.i("SuixinNetease", "seek position=" + position + " duration=" + duration);
        updateState(active.isPlaying());
    }

    private void broadcastState(boolean active) {
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
        boolean playing = player != null && player.isPlaying();
        int position = player == null ? 0 : player.getCurrentPosition();
        int duration = player == null ? 0 : player.getDuration();
        state.putExtra("playing", playing);
        state.putExtra("position", position);
        state.putExtra("duration", duration);
        state.putExtra("lyric", lyricLine(position, 0));
        state.putExtra("nextLyric", lyricLine(position, 1));
        int lyricIndex = lyricIndex(position);
        int from = Math.max(0, lyricIndex - 2);
        int to = Math.min(lyricLines.length, lyricIndex + 4);
        String[] lyricWindow = new String[Math.max(0, to - from)];
        if (lyricWindow.length > 0)
            System.arraycopy(lyricLines, from, lyricWindow, 0, lyricWindow.length);
        state.putExtra("lyricWindow", lyricWindow);
        state.putExtra("lyricWindowCurrent", lyricIndex - from);
        state.putExtra("lyricAll", lyricLines);
        state.putExtra("lyricCurrent", lyricIndex);
        sendBroadcast(state);
    }

    private void parseLyric(String raw) {
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
        lyricTimes = new long[times.size()];
        lyricLines = lines.toArray(new String[0]);
        for (int i = 0; i < times.size(); i++) lyricTimes[i] = times.get(i);
    }

    private String lyricLine(long position, int delta) {
        if (lyricTimes.length == 0) return delta == 0 ? "暂无歌词" : "";
        int index = lyricIndex(position);
        index += delta;
        return index >= 0 && index < lyricLines.length ? lyricLines[index] : "";
    }

    private int lyricIndex(long position) {
        int index = 0;
        for (int i = 0; i < lyricTimes.length; i++) {
            if (lyricTimes[i] > position) break;
            index = i;
        }
        return index;
    }

    private void requestAudioFocus() {
        if (audioManager != null) audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private float targetOutputGain() {
        if (audioManager == null) return 1f;
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        if (volume <= 0) return 0f;
        return 0.30f + 0.70f * volume / max;
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

    private void applyOutputGain(boolean animate) {
        final MediaPlayer active = player;
        if (active == null) return;
        final float target = targetOutputGain();
        final int generation = ++gainGeneration;
        if (!animate || mainHandler == null) {
            currentOutputGain = target;
            active.setVolume(target, target);
            Log.i("SuixinNetease", "output gain=" + target + " system="
                    + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                    + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            return;
        }
        final float start = currentOutputGain;
        for (int step = 1; step <= 6; step++) {
            final int currentStep = step;
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (generation != gainGeneration || player != active) return;
                    float value = start + (target - start) * currentStep / 6f;
                    currentOutputGain = value;
                    active.setVolume(value, value);
                    if (currentStep == 6) Log.i("SuixinNetease", "output gain=" + value
                            + " system=" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                }
            }, currentStep * 20L);
        }
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
        if (mainHandler != null) mainHandler.removeCallbacks(ticker);
        broadcastState(false);
        io.shutdownNow();
        if (player != null) { player.stop(); player.release(); player = null; }
        if (session != null) { session.release(); session = null; }
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(codecReceiver); } catch (Exception ignored) {}
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        stopForeground(true); super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static String encodeIds(long[] ids) {
        StringBuilder out = new StringBuilder();
        for (long id : ids) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }
}
