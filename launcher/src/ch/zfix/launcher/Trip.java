package ch.zfix.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Trip computer like the Uconnect display: distance, driving time, average speed, fuel used and
 * consumption, range. Fed by the OBD reader once per poll; two trips: "Fahrt" (this drive, starts
 * again after the car stood 20 minutes) and "Gesamt" (until reset in the vehicle page).
 * Fuel comes from PID 5E (engine fuel rate) when the car offers it; otherwise the average consumption
 * of "Gesamt" is worked out from the drop of the tank level (PID 2F) over at least 50 km.
 */
final class Trip {
    static final int FAHRT = 0, GESAMT = 1;
    /** Fiat Ducato 250: 90 litres (option 120 l); changeable in prefs "tank_l". */
    static float tankLitres = 90;

    static final double[] km = new double[2], sec = new double[2], litres = new double[2];
    static float tankStart = -1;          // tank % when "Gesamt" was reset, -1 = not known yet
    static volatile float rateLh = -1;    // current fuel flow in l/h, -1 = car does not tell
    static volatile boolean fuelKnown;    // PID 5E delivered values during this trip
    /** GPS speed from the launcher, used when the car does not send its speed. */
    static volatile float gpsKmh = -1;
    static volatile long gpsAt;

    private static long lastTick, lastSave, lastSeen;
    private static SharedPreferences sp;

    static synchronized void load(Context c) {
        if (sp != null) return;
        sp = c.getApplicationContext().getSharedPreferences("zfix", Context.MODE_PRIVATE);
        tankLitres = sp.getFloat("tank_l", 90);
        for (int t = 0; t < 2; t++) {
            km[t] = sp.getFloat("trip" + t + "_km", 0);
            sec[t] = sp.getFloat("trip" + t + "_s", 0);
            litres[t] = sp.getFloat("trip" + t + "_l", 0);
        }
        tankStart = sp.getFloat("trip_tank0", -1);
        lastSeen = sp.getLong("trip_seen", 0);
        fuelKnown = sp.getBoolean("trip_fuel", false);
    }

    /** Called when the adapter (re)connects: a new drive after 20 minutes without data. */
    static synchronized void connected() {
        long now = System.currentTimeMillis();
        if (now - lastSeen > 20 * 60_000L) { km[FAHRT] = 0; sec[FAHRT] = 0; litres[FAHRT] = 0; }
        lastTick = 0;
    }

    /** One poll: speed in km/h, engine running, fuel flow in l/h (or -1), tank % (or -1). */
    static synchronized void tick(float kmh, boolean running, float lh, float tankPct) {
        long now = System.currentTimeMillis();
        lastSeen = now;
        rateLh = lh;
        if (lh >= 0) fuelKnown = true;
        if (tankStart < 0 && tankPct >= 0) tankStart = tankPct;
        if (kmh <= 0 && now - gpsAt < 3000 && gpsKmh > 0) kmh = gpsKmh;
        if (lastTick > 0 && running) {
            double dt = Math.min(5, (now - lastTick) / 1000.0);   // gaps (adapter hang) do not count
            for (int t = 0; t < 2; t++) {
                km[t] += Math.max(0, kmh) * dt / 3600.0;
                sec[t] += dt;
                if (lh >= 0) litres[t] += lh * dt / 3600.0;
            }
        }
        lastTick = now;
        if (now - lastSave > 30_000) save();
    }

    static synchronized void resetGesamt(float tankPct) {
        km[GESAMT] = 0; sec[GESAMT] = 0; litres[GESAMT] = 0;
        tankStart = tankPct;
        save();
    }

    static synchronized void save() {
        lastSave = System.currentTimeMillis();
        if (sp == null) return;
        SharedPreferences.Editor e = sp.edit();
        for (int t = 0; t < 2; t++) {
            e.putFloat("trip" + t + "_km", (float) km[t]).putFloat("trip" + t + "_s", (float) sec[t]).putFloat("trip" + t + "_l", (float) litres[t]);
        }
        e.putFloat("trip_tank0", tankStart).putLong("trip_seen", lastSeen).putBoolean("trip_fuel", fuelKnown).apply();
    }

    /** Average l/100 km of a trip, or -1. */
    static float average(int t, float tankPct) {
        if (fuelKnown && km[t] > 2) return (float) (litres[t] * 100 / km[t]);
        // without the fuel flow: tank drop over the "Gesamt" trip
        if (t == GESAMT && tankStart >= 0 && tankPct >= 0 && km[GESAMT] > 50) {
            float used = (tankStart - tankPct) / 100f * tankLitres;
            if (used > 3) return (float) (used * 100 / km[GESAMT]);
        }
        return -1;
    }

    /** Consumption right now: l/100 km while driving, l/h while standing; null if unknown. */
    static String now(float kmh) {
        float lh = rateLh;
        if (lh < 0) return null;
        if (kmh > 5) return String.format(java.util.Locale.GERMAN, "%.1f l/100", lh * 100 / kmh);
        return String.format(java.util.Locale.GERMAN, "%.1f l/h", lh);
    }

    /** Range in km from tank level and average consumption ("Gesamt" first, then this drive), or -1. */
    static int range(float tankPct) {
        if (tankPct < 0) return -1;
        float avg = average(GESAMT, tankPct);
        if (avg < 3) avg = average(FAHRT, tankPct);
        if (avg < 3 || avg > 40) return -1;
        return Math.round(tankPct / 100f * tankLitres / avg * 100);
    }
}
