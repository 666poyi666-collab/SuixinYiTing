package com.poyi.suixinyiting.network;

public final class NetworkTrack {
    public final long id;
    public final String title;
    public final String artist;
    public final long[] artistIds;
    public final String[] artistNames;
    public final String album;
    public final long albumId;
    public final String coverUrl;
    public final boolean playable;
    public final int order;

    public NetworkTrack(long id, String title, String artist, long[] artistIds,
                        String[] artistNames, long albumId, String album,
                        String coverUrl, boolean playable, int order) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.artistIds = artistIds == null ? new long[0] : artistIds;
        this.artistNames = artistNames == null ? new String[0] : artistNames;
        this.albumId = albumId;
        this.album = album == null ? "" : album;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.playable = playable;
        this.order = order;
    }

    public NetworkTrack(long id, String title, String artist, long albumId, String album,
                        String coverUrl, boolean playable, int order) {
        this(id, title, artist, new long[0], new String[0], albumId, album,
                coverUrl, playable, order);
    }
}
