package ch.zfix.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "CAN-Suche": finds out how this radio passes on the data of its CAN box (Hiworld etc.), without adb.
 * Writes a report and copies the vendor's car/CAN apps to Download/ZFix-CAN, so they can be taken to the
 * Mac on a USB stick and examined there. Reads only; nothing is sent to the car.
 */
final class CanScan {
    private static final String[] KEYS = {"can", "car", "mcu", "vehicle", "canbus", "hiworld", "raise", "simple",
            "syu", "microntek", "tw.", "fyt", "nwd", "ts.", "auto", "setting", "factory", "bt", "radio", "obd"};
    private static final long MAX_COPY = 250L * 1024 * 1024;

    interface Progress { void step(String text); }

    /** Runs on a background thread; returns the report text. */
    static String run(Context c, Progress p) {
        StringBuilder r = new StringBuilder();
        r.append("ZFix CAN-Suche ").append(new java.util.Date()).append("\n\n");
        r.append("Geraet: ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL).append(" / ").append(Build.DEVICE)
                .append("\nBoard: ").append(Build.BOARD).append("  Hardware: ").append(Build.HARDWARE)
                .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
                .append("\nBuild: ").append(Build.DISPLAY).append("\n").append(Build.FINGERPRINT).append("\n\n");

        p.step("Systemeinstellungen lesen");
        r.append("== Systemeigenschaften (can/car/mcu) ==\n");
        for (String line : exec("getprop")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("can") || l.contains("car") || l.contains("mcu") || l.contains("protocol") || l.contains("vehicle")
                    || l.contains("hiworld") || l.contains("platform") || l.contains("customer") || l.contains("ro.product")
                    || l.contains("ro.build.display") || l.contains("persist.sys.")) r.append(line).append("\n");
        }

        r.append("\n== /dev (seriell/can/mcu) ==\n");
        String[] dev = new File("/dev").list();
        if (dev != null) for (String d : dev) {
            String l = d.toLowerCase(Locale.ROOT);
            if (l.startsWith("tty") && !l.startsWith("tty0") && !l.matches("tty\\d+") || l.contains("can") || l.contains("mcu") || l.contains("car"))
                r.append(d).append(" ");
        }
        r.append("\n");

        p.step("Apps durchsuchen");
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS;
        List<PackageInfo> all = pm.getInstalledPackages(0);
        List<PackageInfo> hits = new ArrayList<>();
        r.append("\n== Alle Apps (").append(all.size()).append(") ==\n");
        for (PackageInfo pi : all) {
            String label = String.valueOf(pi.applicationInfo.loadLabel(pm));
            boolean sys = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            r.append(sys ? "S " : "  ").append(pi.packageName).append("  [").append(label).append("] ").append(pi.versionName).append("\n");
            String id = (pi.packageName + " " + label).toLowerCase(Locale.ROOT);
            if (pi.packageName.startsWith("com.android.") || pi.packageName.startsWith("com.google.")
                    || pi.packageName.startsWith("android.") || pi.packageName.startsWith("ch.zfix.")) continue;
            for (String k : KEYS) if (id.contains(k)) { hits.add(pi); break; }
        }

        r.append("\n== Fahrzeug-/CAN-Apps im Detail ==\n");
        for (PackageInfo h : hits) {
            PackageInfo pi;
            try { pi = pm.getPackageInfo(h.packageName, flags); } catch (Exception e) { continue; }
            r.append("\n# ").append(pi.packageName).append("  (").append(pi.applicationInfo.sourceDir).append(")\n");
            if (pi.sharedUserId != null) r.append("  sharedUserId ").append(pi.sharedUserId).append("\n");
            if (pi.permissions != null) for (android.content.pm.PermissionInfo x : pi.permissions) r.append("  perm ").append(x.name).append("\n");
            list(r, "act", pi.activities);
            list(r, "svc", pi.services);
            list(r, "rcv", pi.receivers);
            if (pi.providers != null) for (android.content.pm.ProviderInfo x : pi.providers)
                r.append("  prv ").append(x.name).append(x.exported ? " (exportiert)" : "").append("  authority ").append(x.authority).append("\n");
        }

        p.step("Bericht speichern");
        save(c, "zfix-can-bericht.txt", r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), null);

        long copied = 0;
        int n = 0;
        for (PackageInfo h : hits) {
            File f = new File(h.applicationInfo.sourceDir);
            if (!f.canRead() || copied + f.length() > MAX_COPY) continue;
            p.step("App kopieren " + (++n) + "/" + hits.size() + ": " + h.packageName);
            if (save(c, h.packageName + ".apk", null, f)) copied += f.length();
        }
        r.append("\nKopiert: ").append(n).append(" Apps, ").append(copied / (1024 * 1024)).append(" MB nach Download/ZFix-CAN\n");
        return r.toString();
    }

    private static void list(StringBuilder r, String kind, ComponentInfo[] items) {
        if (items == null) return;
        for (ComponentInfo x : items) {
            r.append("  ").append(kind).append(" ").append(x.name);
            if (x.exported) r.append(" (exportiert)");
            r.append("\n");
        }
    }

    private static List<String> exec(String cmd) {
        List<String> out = new ArrayList<>();
        try {
            Process pr = Runtime.getRuntime().exec(cmd);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String l;
                while ((l = br.readLine()) != null && out.size() < 5000) out.add(l);
            }
        } catch (Exception e) { out.add("(" + e + ")"); }
        return out;
    }

    /** Download/ZFix-CAN/<name>, from bytes or a file. Uses MediaStore, so no storage permission is needed. */
    private static boolean save(Context c, String name, byte[] data, File src) {
        try {
            OutputStream os;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ZFix-CAN");
                v.put(MediaStore.Downloads.MIME_TYPE, data != null ? "text/plain" : "application/octet-stream");
                Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                os = c.getContentResolver().openOutputStream(u);
            } else {
                File d = new File(android.os.Environment.getExternalStoragePublicDirectory("Download"), "ZFix-CAN");
                d.mkdirs();
                os = new java.io.FileOutputStream(new File(d, name));
            }
            try (OutputStream o = os) {
                if (data != null) o.write(data);
                else try (InputStream in = new FileInputStream(src)) {
                    byte[] b = new byte[65536];
                    int k;
                    while ((k = in.read(b)) > 0) o.write(b, 0, k);
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
