package com.poyi.suixinyiting.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public final class NetworkMediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_UP) return;
        String action;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                action = NetworkStreamService.ACTION_NEXT;
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                action = NetworkStreamService.ACTION_PREVIOUS;
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                action = NetworkStreamService.ACTION_STOP;
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                action = NetworkStreamService.ACTION_TOGGLE;
                break;
            default:
                return;
        }
        Intent service = new Intent(context, NetworkStreamService.class).setAction(action);
        context.startService(service);
    }
}
