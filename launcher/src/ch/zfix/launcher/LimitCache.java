package ch.zfix.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.JsonReader;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline OpenStreetMap speed limits. The car has no internet on the road, so roads with a maxspeed
 * are downloaded in tiles (~5.5 x 5.5 km) while the radio is on WLAN and looked up from disk while driving.
 */
final class LimitCache {
    private static final double TILE_LAT = 0.05, TILE_LON = 0.075;
    private static final long MAX_AGE = 60L * 24 * 3600 * 1000;   // refresh tiles after 60 days
    private static final int PREFETCH_RADIUS = 4;                 // 9 x 9 tiles = about 50 x 50 km

    /** Stored limit + ESTIMATE = no limit in OpenStreetMap, guessed from the road type (residential = 30). */
    static final int ESTIMATE = 1000;

    /** One road: speed limit plus its points as lat/lon pairs. */
    private static final class Way { int limit; float[] pts; }

    private final Context ctx;
    private final File dir;
    // Tile file format (big-endian, as written by DataOutputStream and by tempolimit-karten.py on the Mac):
    // int wayCount; per way: short limit, short pointCount, then pointCount x (float lat, float lon)
    private final Map<String, List<Way>> mem = new LinkedHashMap<String, List<Way>>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Way>> e) { return size() > 9; }
    };
    private volatile boolean prefetching;

    LimitCache(Context c) {
        ctx = c.getApplicationContext();
        // external app folder, so tiles prepared on the Mac can be copied in with adb push
        File ext = ctx.getExternalFilesDir("limits");
        dir = ext != null ? ext : new File(ctx.getFilesDir(), "limits");
        dir.mkdirs();
    }

    /** Downloaded tiles, for the ZFix menu (the files are not reachable without root). */
    int tileCount() {
        String[] f = dir.list();
        int n = 0;
        if (f != null) for (String name : f) if (name.endsWith(".bin")) n++;
        return n;
    }

    private static int ty(double lat) { return (int) Math.floor(lat / TILE_LAT); }
    private static int tx(double lon) { return (int) Math.floor(lon / TILE_LON); }
    private File file(int y, int x) { return new File(dir, y + "_" + x + ".bin"); }

    /**
     * Limit of the nearest road within 30 m (+ ESTIMATE if only guessed). -1 = no road with a limit,
     * -2 = tile not downloaded yet. A tagged road wins over a guessed one unless the guessed one is clearly closer.
     */
    int lookup(double lat, double lon) {
        int y = ty(lat), x = tx(lon);
        List<Way> ways = load(y, x);
        if (ways == null) {
            if (!online()) return -2;
            if (!fetch(y, x)) return -2;
            ways = load(y, x);
            if (ways == null) return -2;
        }
        double k = Math.cos(Math.toRadians(lat));
        double bestReal = Double.MAX_VALUE, bestEst = Double.MAX_VALUE;
        int real = -1, est = -1;
        for (Way w : ways) {
            double d = dist(lat, lon, k, w.pts) * 111320;
            if (w.limit >= ESTIMATE) { if (d < bestEst) { bestEst = d; est = w.limit; } }
            else if (d < bestReal) { bestReal = d; real = w.limit; }
        }
        if (bestReal <= 30 && bestReal <= bestEst + 10) return real;
        if (bestEst <= 30) return est;
        return -1;
    }

    /** On WLAN: download every tile around the car that is missing or old. Runs once at a time. */
    void prefetchAround(final double lat, final double lon) {
        if (prefetching || !unmetered()) return;
        prefetching = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int cy = ty(lat), cx = tx(lon);
                    // nearest tiles first, so the home area is ready soonest
                    for (int r = 0; r <= PREFETCH_RADIUS; r++) {
                        for (int y = cy - r; y <= cy + r; y++) {
                            for (int x = cx - r; x <= cx + r; x++) {
                                if (Math.max(Math.abs(y - cy), Math.abs(x - cx)) != r) continue;
                                File f = file(y, x);
                                if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) continue;
                                if (!unmetered()) return;
                                fetch(y, x);
                                Thread.sleep(1500);   // be gentle with the public Overpass server
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    prefetching = false;
                }
            }
        }).start();
    }

    private synchronized List<Way> load(int y, int x) {
        String key = y + "_" + x;
        List<Way> ways = mem.get(key);
        if (ways != null) return ways;
        File f = file(y, x);
        if (!f.exists()) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
            int n = in.readInt();
            ways = new ArrayList<Way>(n);
            for (int i = 0; i < n; i++) {
                Way w = new Way();
                w.limit = in.readShort();
                int m = in.readShort() & 0xFFFF;
                w.pts = new float[m * 2];
                for (int j = 0; j < m * 2; j++) w.pts[j] = in.readFloat();
                ways.add(w);
            }
            mem.put(key, ways);
            return ways;
        } catch (Exception e) {
            f.delete();
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /** Downloads one tile from Overpass and stores it (an empty tile is stored too, so it is not asked again). */
    private boolean fetch(int y, int x) {
        double s = y * TILE_LAT, w = x * TILE_LON;
        String q = String.format(Locale.ROOT,
                "[out:json][timeout:60];(way[highway][maxspeed](%1$.5f,%2$.5f,%3$.5f,%4$.5f);"
                        + "way[highway~\"^(residential|living_street)$\"][!maxspeed](%1$.5f,%2$.5f,%3$.5f,%4$.5f););out tags geom;",
                s, w, s + TILE_LAT, w + TILE_LON);
        HttpURLConnection c = null;
        try {
            byte[] body = ("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8");
            c = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(70000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", "ZFix-Launcher/2.8");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            if (c.getResponseCode() != 200) return false;
            List<Way> ways = parse(c.getInputStream());
            File tmp = new File(dir, y + "_" + x + ".tmp");
            DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp)));
            out.writeInt(ways.size());
            for (Way wy : ways) {
                out.writeShort(wy.limit);
                out.writeShort(wy.pts.length / 2);
                for (float v : wy.pts) out.writeFloat(v);
            }
            out.close();
            synchronized (this) {
                tmp.renameTo(file(y, x));
                mem.remove(y + "_" + x);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<Way> parse(InputStream is) throws java.io.IOException {
        List<Way> ways = new ArrayList<Way>();
        JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(is), "UTF-8"));
        try {
            r.beginObject();
            while (r.hasNext()) {
                if (!r.nextName().equals("elements")) { r.skipValue(); continue; }
                r.beginArray();
                while (r.hasNext()) {
                    int limit = -1;
                    String highway = null;
                    float[] pts = null;
                    r.beginObject();
                    while (r.hasNext()) {
                        String n = r.nextName();
                        if (n.equals("tags")) {
                            r.beginObject();
                            while (r.hasNext()) {
                                String tag = r.nextName();
                                if (tag.equals("maxspeed")) limit = MainActivity.parseMaxspeed(r.nextString());
                                else if (tag.equals("highway")) highway = r.nextString();
                                else r.skipValue();
                            }
                            r.endObject();
                        } else if (n.equals("geometry")) {
                            List<Float> g = new ArrayList<Float>();
                            r.beginArray();
                            while (r.hasNext()) {
                                double la = 0, lo = 0;
                                r.beginObject();
                                while (r.hasNext()) {
                                    String k = r.nextName();
                                    if (k.equals("lat")) la = r.nextDouble();
                                    else if (k.equals("lon")) lo = r.nextDouble();
                                    else r.skipValue();
                                }
                                r.endObject();
                                g.add((float) la);
                                g.add((float) lo);
                            }
                            r.endArray();
                            pts = new float[g.size()];
                            for (int i = 0; i < pts.length; i++) pts[i] = g.get(i);
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                    if (limit <= 0) limit = guess(highway);
                    if (limit > 0 && pts != null && pts.length >= 2 && pts.length / 2 < 65535) {
                        Way w = new Way();
                        w.limit = limit;
                        w.pts = pts;
                        ways.add(w);
                    }
                }
                r.endArray();
            }
            r.endObject();
        } finally {
            r.close();
        }
        return ways;
    }

    /** Swiss rules for roads without a tagged limit: living street 20, residential (often a 30 zone) 30. */
    static int guess(String highway) {
        if ("living_street".equals(highway)) return ESTIMATE + 20;
        if ("residential".equals(highway)) return ESTIMATE + 30;
        return -1;
    }

    /** Distance in degrees of latitude from the point to the polyline. */
    private static double dist(double lat, double lon, double k, float[] p) {
        double best = Double.MAX_VALUE;
        double ax = (p[1] - lon) * k, ay = p[0] - lat;
        best = Math.hypot(ax, ay);
        for (int i = 2; i + 1 < p.length; i += 2) {
            double bx = (p[i + 1] - lon) * k, by = p[i] - lat;
            double dx = bx - ax, dy = by - ay;
            double len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
            ax = bx;
            ay = by;
        }
        return best;
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean unmetered() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            // the head unit's WLAN is not always flagged "not metered", so any WLAN counts
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        } catch (Exception e) {
            return false;
        }
    }
}
