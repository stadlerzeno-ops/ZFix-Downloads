package ch.zfix.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the GPS right at ignition, so it is already tracking satellites when the home screen appears. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        GpsService.enableWifiAdb(c);
        try { c.startForegroundService(new Intent(c, GpsService.class)); } catch (Exception ignored) { }
    }
}
