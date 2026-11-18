package ch.zfix.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the GPS switched on from boot to shutdown. The head unit's GPS has no assistance data and needs
 * minutes of uninterrupted tracking for a first fix, so the request is made once here and never re-made
 * when the home screen pauses or resumes (that restarted the chip every few seconds and it never got a fix).
 */
public class GpsService extends Service {
    interface Client {
        void onFix(Location l);
        void onNmeaSpeed(int kmh);
    }

    static volatile Client client;
    static volatile Location last;
    static volatile int satsUsed = -1, satsSeen = -1;
    static volatile boolean running;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LocationManager lm;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            last = l;
            Client c = client;
            if (c != null) c.onFix(l);
        }
        @Override public void onStatusChanged(String p, int st, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    private final GnssStatus.Callback gnss = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus st) {
            int used = 0;
            for (int i = 0; i < st.getSatelliteCount(); i++) if (st.usedInFix(i)) used++;
            satsUsed = used;
            satsSeen = st.getSatelliteCount();
        }
    };

    /** Some head units report a fix only as raw NMEA; pass the $..RMC speed on as a fallback. */
    private final OnNmeaMessageListener nmea = new OnNmeaMessageListener() {
        @Override public void onNmeaMessage(String msg, long ts) {
            if (msg == null || msg.length() < 7 || !msg.startsWith("$") || !msg.substring(3, 6).equals("RMC")) return;
            String[] f = msg.split(",", -1);
            if (f.length < 8 || !"A".equals(f[2])) return;
            try {
                int kmh = Math.round((f[7].isEmpty() ? 0 : Float.parseFloat(f[7])) * 1.852f);
                Client c = client;
                if (c != null) c.onNmeaSpeed(kmh);
            } catch (Exception ignored) { }
        }
    };

    /**
     * Android turns wireless debugging off after every reboot and every time the WLAN drops (each drive).
     * With WRITE_SECURE_SETTINGS (granted once by install.sh) it is switched back on whenever the radio is on WLAN.
     * Android itself still only accepts it on networks marked "always allow" and only from paired computers.
     */
    static void enableWifiAdb(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1
                    && Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1)
                Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
        } catch (Exception ignored) { }   // permission not granted: nothing to do
    }

    private final ConnectivityManager.NetworkCallback wifi = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { enableWifiAdb(GpsService.this); }
            }, 5000);
        }
    };

    /**
     * At ignition the head unit restores its last source and opens the stock radio app (com.tw.radio) on top.
     * For 20 s after boot or wake-up, the ZFix home screen is brought back whenever that app comes to the front.
     * Needs usage access (granted once by install.sh with appops); without it nothing happens.
     */
    private static final String STOCK_RADIO = "com.tw.radio";
    private long guardUntil;

    private final Runnable guard = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() > guardUntil) return;
            if (STOCK_RADIO.equals(foregroundApp())) {
                try {
                    startActivity(new Intent(GpsService.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                } catch (Exception ignored) { }
            }
            ui.postDelayed(this, 1000);
        }
    };

    private void startGuard() {
        guardUntil = System.currentTimeMillis() + 20000;
        ui.removeCallbacks(guard);
        ui.postDelayed(guard, 1000);
    }

    private String foregroundApp() {
        try {
            UsageStatsManager um = getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(now - 60000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { startGuard(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("gps", "ZFix GPS", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "gps")
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("ZFix GPS")
                .setOngoing(true)
                .build();
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Exception e) {
            startForeground(1, n);
        }
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, listener, Looper.getMainLooper()); } catch (Exception ignored) { }
        try {
            if (lm.getAllProviders().contains("fused"))
                lm.requestLocationUpdates("fused", 5000, 0, listener, Looper.getMainLooper());
        } catch (Exception ignored) { }
        try { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, listener, Looper.getMainLooper()); } catch (Exception ignored) { }
        try { lm.registerGnssStatusCallback(gnss, ui); } catch (Exception ignored) { }
        try { lm.addNmeaListener(nmea, ui); } catch (Exception ignored) { }
        try {
            getSystemService(ConnectivityManager.class).registerNetworkCallback(
                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi);
        } catch (Exception ignored) { }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try { registerReceiver(wake, f); } catch (Exception ignored) { }
        startGuard();   // the service starts at boot
        running = true;
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        running = false;
        try { lm.removeUpdates(listener); } catch (Exception ignored) { }
        try { lm.unregisterGnssStatusCallback(gnss); } catch (Exception ignored) { }
        try { lm.removeNmeaListener(nmea); } catch (Exception ignored) { }
        try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifi); } catch (Exception ignored) { }
        try { unregisterReceiver(wake); } catch (Exception ignored) { }
        ui.removeCallbacks(guard);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
