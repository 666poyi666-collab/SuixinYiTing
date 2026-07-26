package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import android.graphics.BitmapFactory;
import android.util.LruCache;

public final class NetworkMusicActivity extends Activity {
    private static final ExecutorService COVER_IO = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> COVERS = new LruCache<>(12);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private NeteaseWebApi api;
    private PlaylistStore store;
    private LinearLayout root;
    private TextView status;
    private ListView list;
    private final ArrayList<Object> rows = new ArrayList<>();
    private ArrayAdapter<Object> adapter;
    private long currentPlaylist;
    private String currentPlaylistName = "";
    private int currentTrackCount;
    private int loaded;
    private boolean loading;
    private volatile boolean destroyed;
    private String launchMode = "playlists";
    private long[] activeSourceIds;
    private long queueCurrentId = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Crown and hardware volume keys stay on the media stream on every
        // network page, not just the player (AUDIO-004).
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        launchMode = getIntent().getStringExtra("mode");
        if (launchMode == null) launchMode = "playlists";
        api = new NeteaseWebApi(this); store = new PlaylistStore(this);
        buildUi();
        io.execute(new Runnable() { @Override public void run() {
            try { long uid = api.currentUserId(); loadPlaylists(uid); }
            catch (Exception e) { main.post(new Runnable() { @Override public void run() { showLogin(); }}); }
        }});
    }

    /** Density-independent pixels; the panel is 378x496 px at 2.0 density. */
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** One reusable row; without this every scroll step inflated a new tree. */
    private static final class RowHolder {
        LinearLayout box;
        ImageView cover;
        TextView primary;
        TextView secondary;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.BLACK);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(7), dp(5), dp(7), dp(3)); root.setBackgroundColor(Color.BLACK);
        String pageTitle = "albums".equals(launchMode) ? "专辑"
                : "artists".equals(launchMode) ? "歌手"
                : "queue".equals(launchMode) ? "播放队列"
                : "liked".equals(launchMode) ? "我喜欢的音乐" : "歌单";
        TextView title = text(pageTitle, 17, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new android.app.AlertDialog.Builder(NetworkMusicActivity.this)
                        .setTitle("网络音乐")
                        .setItems(new String[]{"首选音质", "清除 256MB 滚动缓存", "退出网易云登录"},
                                new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 0) NetworkAudioSettings.show(NetworkMusicActivity.this);
                                else if (which == 1) clearCache();
                                else { api.logout(); recreate(); }
                            }
                        }).show();
                return true;
            }
        });
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));
        status = text("正在读取登录状态…", 9.5f, 0xffaaaaaa);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(14)));
        list = new ListView(this); list.setDividerHeight(1); list.setBackgroundColor(Color.BLACK);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setScrollingCacheEnabled(false);
        adapter = new ArrayAdapter<Object>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, rows) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                // Recycling matters more here than anywhere else in the app: the
                // liked list is 1242 rows. The previous revision ignored
                // convertView and built a fresh LinearLayout, two TextViews and
                // sometimes an ImageView for every single scroll step.
                RowHolder holder;
                if (convert instanceof LinearLayout && convert.getTag() instanceof RowHolder) {
                    holder = (RowHolder) convert.getTag();
                } else {
                    holder = new RowHolder();
                    holder.box = new LinearLayout(NetworkMusicActivity.this);
                    holder.box.setOrientation(LinearLayout.HORIZONTAL);
                    holder.box.setGravity(Gravity.CENTER_VERTICAL);
                    holder.box.setPadding(dp(5), dp(6), dp(4), dp(6));
                    holder.cover = new ImageView(NetworkMusicActivity.this);
                    holder.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams cp =
                            new LinearLayout.LayoutParams(dp(29), dp(29));
                    cp.rightMargin = dp(6);
                    holder.box.addView(holder.cover, cp);
                    LinearLayout labels = new LinearLayout(NetworkMusicActivity.this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    holder.box.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                    holder.primary = text("", 13, Color.WHITE);
                    holder.primary.setSingleLine(true);
                    holder.primary.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(holder.primary);
                    holder.secondary = text("", 9.5f, 0xff9aa0a6);
                    holder.secondary.setSingleLine(true);
                    holder.secondary.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(holder.secondary);
                    holder.box.setTag(holder);
                    convert = holder.box;
                }

                Object row = getItem(pos);
                String coverUrl = row instanceof LibraryGroup
                        ? ((LibraryGroup) row).coverUrl : "";
                if (coverUrl.isEmpty()) {
                    holder.cover.setVisibility(View.GONE);
                    holder.cover.setTag(null);
                } else {
                    holder.cover.setVisibility(View.VISIBLE);
                    loadCover(holder.cover, coverUrl);
                }

                String primary = row instanceof NetworkTrack ? ((NetworkTrack) row).title
                        : row instanceof LibraryGroup ? ((LibraryGroup) row).name
                        : ((NetworkMusicSource.Playlist) row).name;
                if (row instanceof NetworkTrack && ((NetworkTrack) row).id == queueCurrentId)
                    primary = "▶ " + primary;
                holder.primary.setText(primary);

                String sub;
                if (row instanceof NetworkTrack) {
                    NetworkTrack track = (NetworkTrack) row;
                    sub = track.artist;
                    if (!track.album.isEmpty()) sub += " · " + track.album;
                } else if (row instanceof LibraryGroup) {
                    LibraryGroup group = (LibraryGroup) row;
                    sub = (group.subtitle.isEmpty() ? "" : group.subtitle + " · ")
                            + group.trackCount + " 首";
                } else {
                    sub = ((NetworkMusicSource.Playlist) row).trackCount + " 首";
                }
                holder.secondary.setText(sub);
                return convert;
            }
        };
        list.setAdapter(adapter); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int position, long id) {
                Object row = rows.get(position);
                if (row instanceof NetworkMusicSource.Playlist) openPlaylist((NetworkMusicSource.Playlist) row);
                else if (row instanceof LibraryGroup) openGroup((LibraryGroup) row);
                else if (row instanceof NetworkTrack) play((NetworkTrack) row);
            }
        });
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {}
            @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                if (currentPlaylist > 0 && activeSourceIds == null
                        && total > 0 && first + visible >= total - 10) appendPage();
            }
        });
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        return t;
    }

    /**
     * Netease image hosts resize server-side via {@code ?param=WxH}. Asking for
     * a 120 px thumbnail instead of the 300-500 px original cuts both the list's
     * download volume and its decode cost.
     */
    private static String thumbnailUrl(String url) {
        if (url.contains("param=") || !url.contains("music.126.net")) return url;
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "param=120y120";
    }

    private void loadCover(final ImageView view, final String url) {
        view.setTag(url);
        Bitmap cached = COVERS.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        COVER_IO.execute(new Runnable() { @Override public void run() {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(thumbnailUrl(url)).openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                // Thumbnails render at 29 dp (58 px). Decoding the originals at
                // full size filled the cache with far more pixels than the list
                // can ever show.
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap image = BitmapFactory.decodeStream(
                        connection.getInputStream(), null, options);
                if (image == null) return; COVERS.put(url, image);
                main.post(new Runnable() { @Override public void run() {
                    if (url.equals(view.getTag())) view.setImageBitmap(image);
                }});
            } catch (Exception ignored) {
            } finally { if (connection != null) connection.disconnect(); }
        }});
    }

    private void showLogin() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        rows.clear(); adapter.notifyDataSetChanged(); list.setVisibility(View.GONE);
        status.setText("请使用手机网易云音乐扫码登录");
        final ImageView qr = new ImageView(this);
        root.addView(qr, 2, new LinearLayout.LayoutParams(-1, 250));
        final Button button = new Button(this); button.setText("生成登录二维码");
        root.addView(button, 3, new LinearLayout.LayoutParams(-1, 70));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                button.setEnabled(false); status.setText("正在生成二维码…");
                io.execute(new Runnable() { @Override public void run() {
                    try {
                        final NetworkMusicSource.LoginTicket t = api.beginQrLogin();
                        final Bitmap bitmap = qr(t.loginUrl, 240);
                        main.post(new Runnable() { @Override public void run() {
                            qr.setImageBitmap(bitmap); status.setText("扫码后请保持此页面"); poll(t.key, qr, button);
                        }});
                    } catch (final Exception e) { error(e); main.post(new Runnable() {
                        @Override public void run() { button.setEnabled(true); }}); }
                }});
            }
        });
    }

    private void poll(final String key, final ImageView qr, final Button button) {
        io.execute(new Runnable() { @Override public void run() {
            try {
                for (int i = 0; i < 180; i++) {
                    String code = api.pollQrLogin(key);
                    final String visibleCode = code;
                    main.post(new Runnable() { @Override public void run() {
                        if ("801".equals(visibleCode)) status.setText("等待手机扫码…");
                        else if ("802".equals(visibleCode)) status.setText("手机已确认，正在登录…");
                    }});
                    if ("803".equals(code)) {
                        final long uid = api.currentUserId();
                        main.post(new Runnable() { @Override public void run() {
                            root.removeView(qr); root.removeView(button); list.setVisibility(View.VISIBLE);
                            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }});
                        loadPlaylists(uid); return;
                    }
                    if ("800".equals(code)) break;
                    Thread.sleep(2000);
                }
                throw new IllegalStateException("二维码已过期，请重新生成");
            } catch (Exception e) { error(e); main.post(new Runnable() {
                @Override public void run() {
                    button.setEnabled(true);
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }}); }
        }});
    }

    private void loadPlaylists(long uid) throws Exception {
        if ("queue".equals(launchMode)) { loadQueue(); return; }
        final List<NetworkMusicSource.Playlist> cached = store.playlists();
        if ("albums".equals(launchMode) || "artists".equals(launchMode)) {
            showGroups(true);
            final List<NetworkMusicSource.Playlist> data = api.playlists(uid);
            for (int i = 0; i < data.size() && !destroyed; i++) {
                final int done = i + 1;
                final int total = data.size();
                api.syncPlaylist(data.get(i).id, store);
                if (done == total || done % 3 == 0) {
                    showGroups(false);
                    main.post(new Runnable() { @Override public void run() {
                        status.setText(("albums".equals(launchMode) ? "专辑" : "歌手")
                                + " · 已同步 " + done + " / " + total);
                    }});
                }
            }
            showGroups(false);
            return;
        }
        if (!cached.isEmpty()) showPlaylists(cached, true);
        try {
            final List<NetworkMusicSource.Playlist> data = api.playlists(uid);
            showPlaylists(data, false);
        } catch (Exception error) {
            if (cached.isEmpty()) throw error;
            main.post(new Runnable() { @Override public void run() {
                status.setText("网络暂不可用 · 已保留并显示缓存歌单");
            }});
        }
    }

    private void showGroups(final boolean cached) {
        final List<LibraryGroup> groups = "albums".equals(launchMode)
                ? store.albums() : store.artists();
        main.post(new Runnable() { @Override public void run() {
            if (destroyed) return;
            currentPlaylist = 0; activeSourceIds = null; rows.clear(); rows.addAll(groups);
            adapter.notifyDataSetChanged();
            status.setText((cached ? "缓存" : "")
                    + ("albums".equals(launchMode) ? "专辑" : "歌手")
                    + " · " + groups.size());
        }});
    }

    private void openGroup(final LibraryGroup group) {
        status.setText("读取" + group.name + "…");
        io.execute(new Runnable() { @Override public void run() {
            final List<NetworkTrack> tracks = store.tracksForGroup(group);
            final long[] ids = playableIds(tracks);
            main.post(new Runnable() { @Override public void run() {
                rows.clear(); rows.addAll(tracks); activeSourceIds = ids;
                currentPlaylist = -(group.type * 1000000000000L + group.id);
                currentPlaylistName = group.name; loaded = tracks.size();
                adapter.notifyDataSetChanged();
                status.setText(group.name + " · " + tracks.size() + " 首");
            }});
        }});
    }

    private void loadQueue() {
        long playlist = getSharedPreferences("network_player", MODE_PRIVATE)
                .getLong("last_playlist", 0);
        String encoded = getSharedPreferences("network_player", MODE_PRIVATE)
                .getString("last_queue_ids", "");
        long[] base = encoded.isEmpty() ? store.allPlayableIds(playlist) : decodeIds(encoded);
        ShuffleBag bag = new ShuffleBag(getSharedPreferences("network_player", MODE_PRIVATE));
        bag.load(playlist, base);
        final long[] ordered = bag.snapshot();
        final int cursor = bag.cursor();
        final long current = bag.current();
        final List<NetworkTrack> tracks = store.tracksByIds(ordered);
        main.post(new Runnable() { @Override public void run() {
            currentPlaylist = playlist; activeSourceIds = ordered; queueCurrentId = current;
            rows.clear(); rows.addAll(tracks); loaded = tracks.size();
            adapter.notifyDataSetChanged();
            status.setText("本轮随机 · 已播放 " + Math.max(0, cursor) + " / " + ordered.length);
            int at = 0;
            for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id == current) { at = i; break; }
            list.setSelection(Math.max(0, at - 1));
        }});
    }

    private void showPlaylists(final List<NetworkMusicSource.Playlist> data,
                               final boolean cached) {
        main.post(new Runnable() { @Override public void run() {
            currentPlaylist = 0; rows.clear();
            int liked = likedIndex(data);
            if ("queue".equals(launchMode)) {
                long wanted = getSharedPreferences("network_player", MODE_PRIVATE)
                        .getLong("last_playlist", 0);
                for (NetworkMusicSource.Playlist playlist : data) {
                    if (playlist.id == wanted) {
                        openPlaylist(playlist);
                        return;
                    }
                }
            }
            if ("liked".equals(launchMode) && liked >= 0) {
                openPlaylist(data.get(liked));
                return;
            }
            for (int i = 0; i < data.size(); i++) if (i != liked) rows.add(data.get(i));
            adapter.notifyDataSetChanged();
            status.setText((cached ? "缓存歌单 · " : "歌单 · ") + rows.size());
        }});
    }

    private static int likedIndex(List<NetworkMusicSource.Playlist> data) {
        for (int i = 0; i < data.size(); i++)
            if ("我喜欢的音乐".equals(data.get(i).name)) return i;
        return data.isEmpty() ? -1 : 0;
    }

    private void openPlaylist(final NetworkMusicSource.Playlist p) {
        activeSourceIds = null; queueCurrentId = -1;
        currentPlaylist = p.id; currentPlaylistName = p.name; currentTrackCount = p.trackCount;
        loaded = 0; rows.clear(); adapter.notifyDataSetChanged();
        status.setText("同步 " + p.name + "…"); loading = true;
        io.execute(new Runnable() { @Override public void run() {
            try {
                final int count = api.syncPlaylist(p.id, store);
                main.post(new Runnable() { @Override public void run() {
                    if (destroyed || currentPlaylist != p.id) return;
                    currentTrackCount = count;
                    loading = false; loadAllRemaining(p.id);
                }});
            } catch (Exception e) {
                if (currentPlaylist == p.id) { loading = false; error(e); }
            }
        }});
        io.execute(new Runnable() { @Override public void run() {
            try {
                for (int i = 0; i < 30 && currentPlaylist == p.id && loaded == 0; i++) {
                    final List<NetworkTrack> first = store.page(p.id, 0, Integer.MAX_VALUE);
                    if (!first.isEmpty()) {
                        main.post(new Runnable() { @Override public void run() {
                            if (currentPlaylist == p.id && loaded == 0) {
                                rows.addAll(first); loaded = first.size(); adapter.notifyDataSetChanged();
                                currentTrackCount = Math.max(currentTrackCount, loaded);
                                status.setText(p.name + " · 已载入 " + loaded + " / "
                                        + currentTrackCount + " · 后台同步");
                            }
                        }});
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {}
        }});
    }

    private void appendPage() {
        if (loading || currentPlaylist == 0) return;
        loading = true;
        io.execute(new Runnable() { @Override public void run() {
            final List<NetworkTrack> page = store.page(currentPlaylist, loaded, 50);
            main.post(new Runnable() { @Override public void run() {
                rows.addAll(page); loaded += page.size(); adapter.notifyDataSetChanged(); loading = false;
                updateLoadedStatus();
            }});
        }});
    }

    private void loadAllRemaining(final long playlistId) {
        if (destroyed || io.isShutdown()) return;
        loading = true;
        final int offset = loaded;
        io.execute(new Runnable() { @Override public void run() {
            final List<NetworkTrack> rest = store.page(playlistId, offset, Integer.MAX_VALUE);
            main.post(new Runnable() { @Override public void run() {
                if (currentPlaylist != playlistId) return;
                rows.addAll(rest); loaded += rest.size(); adapter.notifyDataSetChanged();
                loading = false; updateLoadedStatus();
            }});
        }});
    }

    private void updateLoadedStatus() {
        status.setText(currentPlaylistName + " · 已载入 " + loaded + " / " + currentTrackCount);
    }

    private void play(NetworkTrack t) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
        boolean cellular = info != null && info.getType() == ConnectivityManager.TYPE_MOBILE;
        final NetworkTrack selected = t;
        if (cellular && !getSharedPreferences("network_settings", MODE_PRIVATE)
                .getBoolean("cellular_confirmed", false)) {
            new android.app.AlertDialog.Builder(this).setTitle("蜂窝高音质播放")
                    .setMessage("播放及后台预缓存会消耗较多流量。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            getSharedPreferences("network_settings", MODE_PRIVATE).edit()
                                    .putBoolean("cellular_confirmed", true)
                                    .putBoolean("cellular_prefetch_confirmed", true)
                                    .putBoolean("cellular_prefetch", true).apply();
                            startTrack(selected);
                        }
                    }).show();
            return;
        }
        startTrack(t);
    }

    private void startTrack(NetworkTrack t) {
        Intent i = new Intent(this, NetworkStreamService.class);
        i.setAction(NetworkStreamService.ACTION_PLAY);
        i.putExtra("playlist", currentPlaylist); i.putExtra("track", t.id);
        if (activeSourceIds != null && activeSourceIds.length > 0)
            i.putExtra("source_ids", activeSourceIds);
        startService(i); status.setText("正在播放：" + t.title);
        Intent main = new Intent();
        main.setClassName(getPackageName(), "com.github.sky130.zero.music.ui.main.MainActivity");
        main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(main);
        finish();
    }

    private static long[] playableIds(List<NetworkTrack> tracks) {
        int count = 0;
        for (NetworkTrack track : tracks) if (track.playable) count++;
        long[] ids = new long[count]; int at = 0;
        for (NetworkTrack track : tracks) if (track.playable) ids[at++] = track.id;
        return ids;
    }

    private static long[] decodeIds(String value) {
        if (value == null || value.isEmpty()) return new long[0];
        String[] parts = value.split(","); long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i]);
        return out;
    }

    private void clearCache() {
        deleteTree(new java.io.File(getCacheDir(), "network_audio"));
        status.setText("滚动缓存已清除");
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File child : children) deleteTree(child);
        f.delete();
    }

    private void error(final Exception e) {
        main.post(new Runnable() { @Override public void run() { status.setText(e.getMessage()); }});
    }

    private static Bitmap qr(String value, int size) throws Exception {
        BitMatrix bits = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            pixels[y * size + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        b.setPixels(pixels, 0, size, 0, 0, size, size); return b;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
