package com.poyi.suixinyiting.network;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

public final class PlaylistStore extends SQLiteOpenHelper {
    private final ConcurrentHashMap<Long, Long> syncTokens = new ConcurrentHashMap<>();

    public PlaylistStore(Context context) {
        super(context, "network_music.db", null, 3);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlist(id INTEGER PRIMARY KEY,name TEXT NOT NULL,track_count INTEGER NOT NULL DEFAULT 0,synced_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE track(playlist_id INTEGER NOT NULL,track_id INTEGER NOT NULL,position INTEGER NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album_id INTEGER NOT NULL DEFAULT 0,album TEXT NOT NULL,cover_url TEXT NOT NULL,playable INTEGER NOT NULL,sync_token INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(playlist_id,track_id))");
        db.execSQL("CREATE TABLE track_artist(track_id INTEGER NOT NULL,artist_id INTEGER NOT NULL,artist_name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(track_id,artist_id))");
        db.execSQL("CREATE UNIQUE INDEX idx_track_position ON track(playlist_id,position)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2)
            db.execSQL("ALTER TABLE track ADD COLUMN sync_token INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE track ADD COLUMN album_id INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS track_artist(track_id INTEGER NOT NULL,artist_id INTEGER NOT NULL,artist_name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(track_id,artist_id))");
        }
    }

    public void savePlaylists(List<NetworkMusicSource.Playlist> items) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (NetworkMusicSource.Playlist item : items) {
                ContentValues v = new ContentValues();
                v.put("id", item.id); v.put("name", item.name); v.put("track_count", item.trackCount);
                db.insertWithOnConflict("playlist", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<NetworkMusicSource.Playlist> playlists() {
        ArrayList<NetworkMusicSource.Playlist> result = new ArrayList<>();
        Cursor c = getReadableDatabase().query("playlist",
                new String[]{"id", "name", "track_count"}, null, null,
                null, null, "rowid ASC");
        try {
            while (c.moveToNext())
                result.add(new NetworkMusicSource.Playlist(
                        c.getLong(0), c.getString(1), c.getInt(2)));
        } finally { c.close(); }
        return result;
    }

    public void replaceTracks(long playlistId, List<NetworkTrack> tracks) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("track", "playlist_id=?", new String[]{Long.toString(playlistId)});
            long token = System.currentTimeMillis();
            for (NetworkTrack t : tracks) putTrack(db, playlistId, t, token);
            ContentValues v = new ContentValues();
            v.put("synced_at", System.currentTimeMillis());
            v.put("track_count", tracks.size());
            db.update("playlist", v, "id=?", new String[]{Long.toString(playlistId)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void beginSync(long playlistId) {
        syncTokens.put(playlistId, System.currentTimeMillis());
    }

    public void appendTracks(long playlistId, List<NetworkTrack> tracks) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Long token = syncTokens.get(playlistId);
            long value = token == null ? System.currentTimeMillis() : token.longValue();
            for (NetworkTrack t : tracks) putTrack(db, playlistId, t, value);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void finishSync(long playlistId, int count) {
        Long token = syncTokens.remove(playlistId);
        long value = token == null ? 0L : token.longValue();
        getWritableDatabase().delete("track", "playlist_id=? AND sync_token<>?",
                new String[]{Long.toString(playlistId), Long.toString(value)});
        ContentValues v = new ContentValues();
        v.put("synced_at", System.currentTimeMillis()); v.put("track_count", count);
        getWritableDatabase().update("playlist", v, "id=?", new String[]{Long.toString(playlistId)});
    }

    private static void putTrack(SQLiteDatabase db, long playlistId, NetworkTrack t, long syncToken) {
        ContentValues v = new ContentValues();
        v.put("playlist_id", playlistId); v.put("track_id", t.id); v.put("position", t.order);
        v.put("title", t.title); v.put("artist", t.artist); v.put("album", t.album);
        v.put("album_id", t.albumId);
        v.put("cover_url", t.coverUrl); v.put("playable", t.playable ? 1 : 0);
        v.put("sync_token", syncToken);
        db.insertWithOnConflict("track", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        if (t.artistIds.length > 0) {
            db.delete("track_artist", "track_id=?", new String[]{Long.toString(t.id)});
            for (int i = 0; i < t.artistIds.length; i++) {
                ContentValues artist = new ContentValues();
                artist.put("track_id", t.id); artist.put("artist_id", t.artistIds[i]);
                artist.put("artist_name", i < t.artistNames.length ? t.artistNames[i] : "");
                artist.put("position", i);
                db.insertWithOnConflict("track_artist", null, artist, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
    }

    public List<NetworkTrack> page(long playlistId, int offset, int limit) {
        List<NetworkTrack> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("track",
                new String[]{"track_id","title","artist","album_id","album","cover_url","playable","position"},
                "playlist_id=?", new String[]{Long.toString(playlistId)}, null, null,
                "position ASC", offset + "," + limit);
        try {
            while (c.moveToNext()) out.add(readTrack(c));
        } finally { c.close(); }
        return out;
    }

    public long[] allPlayableIds(long playlistId) {
        Cursor c = getReadableDatabase().query("track", new String[]{"track_id"},
                "playlist_id=? AND playable=1", new String[]{Long.toString(playlistId)},
                null, null, "position ASC");
        long[] result = new long[c.getCount()];
        int i = 0;
        try { while (c.moveToNext()) result[i++] = c.getLong(0); } finally { c.close(); }
        return result;
    }

    public NetworkTrack find(long playlistId, long trackId) {
        Cursor c = getReadableDatabase().query("track",
                new String[]{"track_id","title","artist","album_id","album","cover_url","playable","position"},
                "playlist_id=? AND track_id=?", new String[]{Long.toString(playlistId),Long.toString(trackId)},
                null, null, null, "1");
        try {
            if (!c.moveToFirst()) return null;
            return readTrack(c);
        } finally { c.close(); }
    }

    public NetworkTrack findAny(long trackId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT track_id,title,artist,album_id,album,cover_url,playable,MIN(position) " +
                        "FROM track WHERE track_id=? GROUP BY track_id",
                new String[]{Long.toString(trackId)});
        try { return c.moveToFirst() ? readTrack(c) : null; } finally { c.close(); }
    }

    public List<LibraryGroup> albums() {
        ArrayList<LibraryGroup> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT album_id,album,MIN(artist),MIN(cover_url),COUNT(DISTINCT track_id) " +
                        "FROM track WHERE album<>'' GROUP BY album_id,album ORDER BY album COLLATE NOCASE", null);
        try { while (c.moveToNext()) out.add(new LibraryGroup(LibraryGroup.ALBUM,
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4)));
        } finally { c.close(); }
        return out;
    }

    public List<LibraryGroup> artists() {
        ArrayList<LibraryGroup> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT a.artist_id,a.artist_name,COUNT(DISTINCT a.track_id) FROM track_artist a " +
                        "JOIN track t ON t.track_id=a.track_id GROUP BY a.artist_id,a.artist_name " +
                        "ORDER BY a.artist_name COLLATE NOCASE", null);
        try { while (c.moveToNext()) out.add(new LibraryGroup(LibraryGroup.ARTIST,
                c.getLong(0), c.getString(1), "", "", c.getInt(2)));
        } finally { c.close(); }
        return out;
    }

    public List<NetworkTrack> tracksForGroup(LibraryGroup group) {
        String sql;
        String[] args = new String[]{Long.toString(group.id)};
        if (group.type == LibraryGroup.ALBUM) {
            sql = "SELECT track_id,title,artist,album_id,album,cover_url,playable,MIN(position) " +
                    "FROM track WHERE album_id=? GROUP BY track_id ORDER BY MIN(position)";
        } else {
            sql = "SELECT t.track_id,t.title,t.artist,t.album_id,t.album,t.cover_url,t.playable,MIN(t.position) " +
                    "FROM track t JOIN track_artist a ON a.track_id=t.track_id WHERE a.artist_id=? " +
                    "GROUP BY t.track_id ORDER BY MIN(t.position)";
        }
        return rawTracks(sql, args);
    }

    public List<NetworkTrack> tracksByIds(long[] ids) {
        ArrayList<NetworkTrack> out = new ArrayList<>();
        HashMap<Long, NetworkTrack> byId = new HashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT track_id,title,artist,album_id,album,cover_url,playable,MIN(position) " +
                        "FROM track GROUP BY track_id", null);
        try { while (c.moveToNext()) {
            NetworkTrack track = readTrack(c); byId.put(track.id, track);
        }} finally { c.close(); }
        for (long id : ids) { NetworkTrack track = byId.get(id); if (track != null) out.add(track); }
        return out;
    }

    private List<NetworkTrack> rawTracks(String sql, String[] args) {
        ArrayList<NetworkTrack> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try { while (c.moveToNext()) out.add(readTrack(c)); } finally { c.close(); }
        return out;
    }

    private static NetworkTrack readTrack(Cursor c) {
        return new NetworkTrack(c.getLong(0), c.getString(1), c.getString(2),
                c.getLong(3), c.getString(4), c.getString(5), c.getInt(6) != 0, c.getInt(7));
    }
}
