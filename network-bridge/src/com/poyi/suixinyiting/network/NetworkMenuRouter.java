package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.content.Intent;

/** Routes the mother APK's existing menu rows without changing adapter positions. */
public final class NetworkMenuRouter {
    private NetworkMenuRouter() {}

    public static boolean open(Activity activity, int route) {
        String mode;
        switch (route) {
            case 1:
            case 2:
                mode = "queue";
                break;
            case 3:
                mode = "liked";
                break;
            case 4:
                mode = "albums";
                break;
            case 5:
                mode = "artists";
                break;
            case 6:
                mode = "playlists";
                break;
            default:
                return false;
        }
        activity.startActivity(new Intent(activity, NetworkMusicActivity.class)
                .putExtra("mode", mode));
        return true;
    }
}
