package com.poyi.suixinyiting.network;

import java.util.List;

public interface NetworkMusicSource {
    final class LoginTicket {
        public final String key;
        public final String loginUrl;
        public LoginTicket(String key, String loginUrl) {
            this.key = key;
            this.loginUrl = loginUrl;
        }
    }

    final class Playlist {
        public final long id;
        public final String name;
        public final int trackCount;
        public Playlist(long id, String name, int trackCount) {
            this.id = id;
            this.name = name;
            this.trackCount = trackCount;
        }
    }

    LoginTicket beginQrLogin() throws Exception;
    String pollQrLogin(String key) throws Exception;
    long currentUserId() throws Exception;
    List<Playlist> playlists(long userId) throws Exception;
    int syncPlaylist(long playlistId, PlaylistStore store) throws Exception;
    List<NetworkTrack> page(long playlistId, int offset, int limit);
    String lyric(long songId) throws Exception;
    void logout();
}
