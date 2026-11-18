package ch.zfix.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Online update of the ZFix apps from GitHub (branch "autoradio" of the public repo ZFix-Downloads).
 * The branch carries the APKs and a file "versionen.json":
 * {"apps":[{"paket":"ch.zfix.launcher","name":"ZFix Launcher","version":"3.0","code":21,
 *           "datei":"ZFix-Launcher.apk","sha256":"..."}, ...]}
 * Only the car's internet connection is needed; Android asks once to allow installing and then
 * shows its own "Aktualisieren" confirmation for every app.
 */
final class Updater {
    /** Public download repo; the car apps live on its branch "autoradio" (main holds the PC tools). */
    static final String REPO = "stadlerzeno-ops/ZFix-Downloads";
    static final String BRANCH = "autoradio";
    private static final String BASE = "https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/";
    static final String ACTION_STATUS = "ch.zfix.launcher.INSTALL_STATUS";

    static final class App {
        String pkg, name, version, file, sha256, url;
        int code;
        long size;
        /** installed versionCode, -1 = not installed */
        long installedCode = -1;
        String installedVersion;

        boolean newer() { return installedCode >= 0 && code > installedCode; }
        boolean missing() { return installedCode < 0; }
    }

    interface Listener {
        void onChecked(List<App> apps, String error);
        void onProgress(App app, int percent);
        void onDone(App app, String error);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    static volatile boolean busy;

    /** Fetches the newest release and compares it with what is installed. Result on the UI thread. */
    static void check(final Context c, final Listener l) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                List<App> list = new ArrayList<>();
                String err = null;
                try {
                    // "?t=" bypasses the 5-minute cache of raw.githubusercontent.com
                    String manifestUrl = BASE + "versionen.json?t=" + (System.currentTimeMillis() / 60000);
                    JSONArray apps = new JSONObject(new String(get(manifestUrl), "UTF-8")).getJSONArray("apps");
                    PackageManager pm = app.getPackageManager();
                    for (int i = 0; i < apps.length(); i++) {
                        JSONObject j = apps.getJSONObject(i);
                        App a = new App();
                        a.pkg = j.getString("paket");
                        a.name = j.optString("name", a.pkg);
                        a.version = j.optString("version", "?");
                        a.code = j.getInt("code");
                        a.file = j.getString("datei");
                        a.sha256 = j.optString("sha256", "").toLowerCase(Locale.ROOT);
                        a.url = BASE + a.file;
                        a.size = j.optLong("bytes", 0);
                        if (a.url == null) continue;
                        try {
                            PackageInfo pi = pm.getPackageInfo(a.pkg, 0);
                            a.installedCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                            a.installedVersion = pi.versionName;
                        } catch (PackageManager.NameNotFoundException ignored) { }
                        list.add(a);
                    }
                    SharedPreferences sp = app.getSharedPreferences("zfix", Context.MODE_PRIVATE);
                    int n = 0;
                    for (App a : list) if (a.newer()) n++;
                    sp.edit().putLong("upd_checked", System.currentTimeMillis()).putInt("upd_count", n).apply();
                } catch (java.net.UnknownHostException e) {
                    err = "Keine Internetverbindung. WLAN oder Handy-Hotspot einschalten.";
                } catch (java.io.FileNotFoundException e) {
                    err = "Auf GitHub gibt es noch keine Version (" + REPO + ").";
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final List<App> res = list;
                final String error = err;
                UI.post(new Runnable() { @Override public void run() { l.onChecked(res, error); } });
            }
        }, "zfix-update-check").start();
    }

    /** Downloads one APK, checks its SHA-256 and hands it to the Android package installer. */
    static void install(final Context c, final App a, final Listener l) {
        final Context app = c.getApplicationContext();
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                File f = new File(app.getCacheDir(), "update.apk");
                try {
                    download(a, f, l);
                    if (a.sha256.length() == 64 && !a.sha256.equals(sha256(f)))
                        throw new Exception("Download beschädigt, bitte nochmals versuchen");
                    PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setAppPackageName(a.pkg);
                    p.setSize(f.length());
                    if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                    int id = pi.createSession(p);
                    PackageInstaller.Session s = pi.openSession(id);
                    try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("apk", 0, f.length())) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                        s.fsync(out);
                    }
                    Intent cb = new Intent(ACTION_STATUS).setPackage(app.getPackageName()).putExtra("paket", a.pkg).putExtra("name", a.name);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pend = PendingIntent.getBroadcast(app, id, cb, flags);
                    s.commit(pend.getIntentSender());
                    s.close();
                } catch (java.net.UnknownHostException e) {
                    err = "Keine Internetverbindung";
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String error = err;
                if (error != null) busy = false;
                UI.post(new Runnable() { @Override public void run() { l.onDone(a, error); } });
            }
        }, "zfix-update-install").start();
    }

    /** Receives the result of the package installer; asks the user to confirm when Android wants that. */
    public static final class StatusReceiver extends BroadcastReceiver {
        static volatile Listener listener;
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) { }
                }
                return;
            }
            busy = false;
            Listener l = listener;
            App a = new App();
            a.pkg = i.getStringExtra("paket");
            a.name = i.getStringExtra("name");
            String err = st == PackageInstaller.STATUS_SUCCESS ? null : statusText(st, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if (l != null) l.onDone(a, err == null ? "OK" : err);
        }
    }

    private static String statusText(int st, String msg) {
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "Abgebrochen";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "Andere Signatur als die installierte App (einmal per Mac neu installieren)";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "Passt nicht zu diesem Radio";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "Zu wenig Speicher";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "Vom Radio blockiert";
            default: return "Fehlgeschlagen" + (msg != null ? ": " + msg : "");
        }
    }

    private static void download(App a, File f, Listener l) throws Exception {
        HttpURLConnection con = open(a.url);
        long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : a.size;
        long got = 0;
        int last = -1;
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                got += n;
                final int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != last) {
                    last = pct;
                    final App fa = a;
                    UI.post(new Runnable() { @Override public void run() { l.onProgress(fa, pct); } });
                }
            }
        } finally { con.disconnect(); }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally { con.disconnect(); }
    }

    /** GitHub sends release files through redirects to another host; follow them by hand. */
    private static HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "ZFix-Launcher");
                        int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) break;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code == 404) { con.disconnect(); throw new java.io.FileNotFoundException(url); }
            if (code == 403) { con.disconnect(); throw new Exception("GitHub lässt gerade keine Abfrage zu, später nochmals versuchen"); }
            if (code != 200) { con.disconnect(); throw new Exception("GitHub antwortet mit Fehler " + code); }
            return con;
        }
        throw new Exception("Zu viele Weiterleitungen");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
