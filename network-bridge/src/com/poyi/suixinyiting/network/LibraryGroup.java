package com.poyi.suixinyiting.network;

public final class LibraryGroup {
    public static final int ALBUM = 1;
    public static final int ARTIST = 2;
    public final int type;
    public final long id;
    public final String name;
    public final String subtitle;
    public final String coverUrl;
    public final int trackCount;

    public LibraryGroup(int type, long id, String name, String subtitle,
                        String coverUrl, int trackCount) {
        this.type = type;
        this.id = id;
        this.name = name == null ? "" : name;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.trackCount = trackCount;
    }
}
