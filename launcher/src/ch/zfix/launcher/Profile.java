package ch.zfix.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * Which car the launcher runs in. Written by the ZFix deployment tool to
 * Android/data/ch.zfix.launcher/files/profil.json, e.g.
 * {"fahrzeug":"ducato","name":"Fiat Ducato","dab":"auto","obd":"bt"}.
 * Without the file the launcher behaves as in the Opel Agila (2.8). The ZFix menu can switch the car locally.
 */
final class Profile {
    static final String AGILA = "agila", DUCATO = "ducato";

    String car = AGILA;
    String name = "Opel Agila";
    /** auto = app of a USB DAB+ box if installed, else Noxon on FM; usb = only the box app; noxon = only the Noxon. */
    String dab = "auto";
    int dabFreq = 9290;
    /** OBD adapter: bt (Bluetooth ELM327), wlan (WLAN ELM327) or aus. */
    String obd = "aus";
    String obdHost = "192.168.0.10:35000";
    boolean fromFile;

    boolean ducato() { return DUCATO.equals(car); }

    /** The Noxon FM transmitter is only in the Agila; "noxon" in profil.json allows it anywhere. */
    boolean noxonAllowed() { return "noxon".equals(dab) || ("auto".equals(dab) && !ducato()); }

    static Profile load(Context c) {
        Profile p = new Profile();
        try {
            File f = new File(c.getExternalFilesDir(null), "profil.json");
            if (f.exists()) {
                byte[] b = new byte[(int) Math.min(f.length(), 65536)];
                int n;
                try (FileInputStream in = new FileInputStream(f)) { n = in.read(b); }
                JSONObject j = new JSONObject(new String(b, 0, Math.max(n, 0), "UTF-8"));
                p.car = j.optString("fahrzeug", AGILA).toLowerCase(java.util.Locale.ROOT);
                p.fromFile = true;
                p.defaults();
                p.name = j.optString("name", p.name);
                p.dab = j.optString("dab", p.dab).toLowerCase(java.util.Locale.ROOT);
                p.dabFreq = j.optInt("dab_freq", p.dabFreq);
                p.obd = j.optString("obd", p.obd).toLowerCase(java.util.Locale.ROOT);
                p.obdHost = j.optString("obd_host", p.obdHost);
            }
        } catch (Exception ignored) { }
        // local choices in the ZFix menu win over the file
        SharedPreferences sp = c.getSharedPreferences("zfix", Context.MODE_PRIVATE);
        String car = sp.getString("p_fahrzeug", null);
        if (car != null && !car.equals(p.car)) { p.car = car; p.defaults(); }
        p.obd = sp.getString("p_obd", p.obd);
        if (!DUCATO.equals(p.car) && !AGILA.equals(p.car)) p.car = AGILA;
        return p;
    }

    private void defaults() {
        if (ducato()) { name = "Fiat Ducato"; obd = "bt"; }
        else { name = "Opel Agila"; obd = "aus"; }
    }
}
