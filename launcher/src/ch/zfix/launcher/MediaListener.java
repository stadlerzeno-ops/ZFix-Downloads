package ch.zfix.launcher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Lets the launcher read active media sessions ("Jetzt läuft"), and the notifications of apps
 * without a media session (ZLink, FM radio) as a fallback.
 */
public class MediaListener extends NotificationListenerService {

    private static MediaListener instance;

    @Override public void onListenerConnected() { instance = this; }

    @Override public void onListenerDisconnected() { instance = null; }

    static StatusBarNotification[] current() {
        MediaListener l = instance;
        if (l == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] n = l.getActiveNotifications();
            return n == null ? new StatusBarNotification[0] : n;
        } catch (Exception e) {
            return new StatusBarNotification[0];
        }
    }
}
