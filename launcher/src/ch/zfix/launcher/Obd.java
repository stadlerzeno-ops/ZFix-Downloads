package ch.zfix.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads engine data through a standard ELM327 OBD-II adapter (Bluetooth or WLAN) in the OBD socket.
 * Works with every head unit, because it does not depend on the radio's CAN box. Only standard
 * EOBD values (mode 01) and stored fault codes (mode 03); nothing is ever written to the car.
 */
final class Obd {
    interface Listener { void onObd(); }

    static final int RPM = 0, SPEED = 1, COOLANT = 2, VOLT = 3, FUEL = 4, BOOST = 5, LOAD = 6, INTAKE = 7, AMBIENT = 8, OIL = 9;
    static final String[] LABEL = {"Drehzahl", "Tempo (Fahrzeug)", "Kühlwasser", "Batterie", "Tank", "Ladedruck",
            "Motorlast", "Ansaugluft", "Aussentemperatur", "Öltemperatur"};
    static final float[] val = new float[10];
    static final long[] at = new long[10];

    static volatile Listener listener;
    static volatile String state = "OBD aus";
    static volatile boolean connected;
    static volatile String adapterName = "";
    static volatile boolean wantDtc;
    static volatile String dtcResult;

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] NAMES = {"OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "CAN"};

    private static Thread thread;
    private static volatile boolean run;
    private static String config = "";

    static boolean fresh(int k) { return System.currentTimeMillis() - at[k] < 5000; }

    /** Starts or restarts the reader; mode bt/wlan/aus, btAddress may be null (first paired OBD adapter). */
    static synchronized void start(final Context ctx, final String mode, final String host, final String btAddress) {
        String cfg = mode + "|" + host + "|" + btAddress;
        if (thread != null && thread.isAlive() && cfg.equals(config)) return;
        stop();
        config = cfg;
        if ("aus".equals(mode)) { state = "OBD aus"; notifyUi(); return; }
        run = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(ctx.getApplicationContext(), mode, host, btAddress); }
        }, "zfix-obd");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        run = false;
        if (thread != null) thread.interrupt();
        closeQuietly(sock);
        thread = null;
        connected = false;
    }

    private static void notifyUi() {
        Listener l = listener;
        if (l != null) l.onObd();
    }

    // ------------------------------------------------------------------ connection

    private static volatile Closeable sock;
    private static InputStream in;
    private static OutputStream out;

    private static void loop(Context c, String mode, String host, String btAddress) {
        int fails = 0;
        while (run) {
            try {
                if ("wlan".equals(mode)) openWifi(host); else openBt(btAddress);
                init();
                fails = 0;
                poll();
            } catch (Exception e) {
                if (!run) break;
                if (e.getMessage() != null) state = e.getMessage();
                fails++;
            } finally {
                connected = false;
                closeQuietly(sock);
                sock = null;
                notifyUi();
            }
            // engine off or adapter away: try again, less often after repeated failures
            try { Thread.sleep(fails < 3 ? 5000 : 20000); } catch (InterruptedException e) { break; }
        }
    }

    static List<BluetoothDevice> pairedAdapters() {
        List<BluetoothDevice> list = new ArrayList<>();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return list;
            for (BluetoothDevice d : a.getBondedDevices()) list.add(d);
        } catch (Exception ignored) { }
        return list;
    }

    static boolean looksLikeObd(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        for (String k : NAMES) if (n.contains(k)) return true;
        return false;
    }

    private static void openBt(String address) throws Exception {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) throw new Exception("Kein Bluetooth");
        try { if (!a.isEnabled()) throw new Exception("Bluetooth aus"); }
        catch (SecurityException e) { throw new Exception("Kein Bluetooth-Zugriff"); }
        BluetoothDevice dev = null;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (address != null ? d.getAddress().equals(address) : looksLikeObd(d.getName())) { dev = d; break; }
            }
        } catch (SecurityException e) { throw new Exception("Kein Bluetooth-Zugriff"); }
        if (dev == null) throw new Exception("OBD-Adapter nicht gekoppelt");
        adapterName = dev.getName();
        state = "Verbinde mit " + adapterName;
        notifyUi();
        try { a.cancelDiscovery(); } catch (Exception ignored) { }
        BluetoothSocket s;
        try {
            s = dev.createRfcommSocketToServiceRecord(SPP);
            sock = s;
            s.connect();
        } catch (Exception first) {
            // many cheap ELM327 clones only answer on channel 1
            closeQuietly(sock);
            s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
            sock = s;
            try { s.connect(); } catch (Exception e) { throw new Exception(adapterName + " antwortet nicht"); }
        }
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    private static void openWifi(String host) throws Exception {
        String h = host;
        int port = 35000;
        int colon = host.lastIndexOf(':');
        if (colon > 0) { h = host.substring(0, colon); port = Integer.parseInt(host.substring(colon + 1)); }
        adapterName = "WLAN " + h;
        state = "Verbinde mit " + adapterName;
        notifyUi();
        Socket s = new Socket();
        sock = s;
        try { s.connect(new InetSocketAddress(h, port), 4000); }
        catch (Exception e) { throw new Exception("WLAN-Adapter nicht erreichbar"); }
        s.setSoTimeout(12000);
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
    }

    /** Sends one command and returns the answer up to the '>' prompt, upper case, without spaces and echo. */
    private static String cmd(String c, long timeoutMs) throws Exception {
        while (in.available() > 0) in.read();
        out.write((c + "\r").getBytes("US-ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() == 0) { Thread.sleep(10); continue; }
            int b = in.read();
            if (b < 0) throw new Exception("Verbindung getrennt");
            if (b == '>') break;
            sb.append((char) b);
        }
        String r = sb.toString().toUpperCase(Locale.ROOT).replace(" ", "");
        String echo = c.toUpperCase(Locale.ROOT).replace(" ", "");
        if (r.startsWith(echo)) r = r.substring(echo.length());
        return r.replace("SEARCHING...", "").trim();
    }

    // ------------------------------------------------------------------ protocol

    private static final boolean[] supported = new boolean[0x61];
    private static boolean can;

    private static void init() throws Exception {
        state = "Adapter starten";
        notifyUi();
        cmd("ATZ", 2500);
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"}) cmd(c, 1500);
        String r = cmd("0100", 12000);
        if (!r.contains("4100")) {
            if (r.contains("UNABLETOCONNECT") || r.contains("NODATA") || r.contains("ERROR")) throw new Exception("Zündung aus");
            throw new Exception("Keine Antwort vom Auto");
        }
        java.util.Arrays.fill(supported, false);
        readSupported(r, 0x00);
        if (supported[0x20]) readSupported(cmd("0120", 3000), 0x20);
        if (supported[0x40]) readSupported(cmd("0140", 3000), 0x40);
        String dp = cmd("ATDPN", 1500);
        if (dp.startsWith("A")) dp = dp.substring(1);   // "A6" = found automatically, protocol 6
        can = dp.length() > 0 && "6789AB".indexOf(dp.charAt(0)) >= 0;
        connected = true;
        state = "Verbunden";
        notifyUi();
    }

    private static void readSupported(String r, int base) {
        int[] d = data(r, base);
        if (d == null || d.length < 4) return;
        for (int i = 0; i < 32; i++) {
            if ((d[i / 8] & (0x80 >> (i % 8))) != 0 && base + i + 1 < supported.length) supported[base + i + 1] = true;
        }
    }

    /** Bytes after "41 <pid>" in an answer, or null. */
    private static int[] data(String r, int pid) {
        String key = String.format(Locale.ROOT, "41%02X", pid);
        for (String line : r.split("[\\r\\n]+")) {
            int i = line.indexOf(key);
            if (i < 0) continue;
            String hex = line.substring(i + 4);
            int n = hex.length() / 2;
            int[] b = new int[n];
            try {
                for (int k = 0; k < n; k++) b[k] = Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16);
            } catch (NumberFormatException e) { continue; }
            return b;
        }
        return null;
    }

    private static int[] ask(int pid) throws Exception {
        if (!supported[pid]) return null;
        String r = cmd(String.format(Locale.ROOT, "01%02X", pid), 1500);
        if (r.contains("STOPPED") || r.contains("UNABLETOCONNECT")) throw new Exception("Zündung aus");
        return data(r, pid);
    }

    private static void put(int k, float v) { val[k] = v; at[k] = System.currentTimeMillis(); }

    private static void poll() throws Exception {
        int n = 0, misses = 0;
        float baro = 100;
        while (run) {
            int[] d = ask(0x0C);
            if (d != null && d.length >= 2) { put(RPM, (d[0] * 256 + d[1]) / 4f); misses = 0; } else misses++;
            d = ask(0x0D);
            if (d != null && d.length >= 1) put(SPEED, d[0]);
            if (n % 5 == 0) {
                d = ask(0x05); if (d != null && d.length >= 1) put(COOLANT, d[0] - 40);
                d = ask(0x2F); if (d != null && d.length >= 1) put(FUEL, d[0] * 100f / 255f);
                d = ask(0x33); if (d != null && d.length >= 1) baro = d[0];
                d = ask(0x0B); if (d != null && d.length >= 1) put(BOOST, Math.max(0, (d[0] - baro) / 100f));
                d = ask(0x04); if (d != null && d.length >= 1) put(LOAD, d[0] * 100f / 255f);
                d = ask(0x0F); if (d != null && d.length >= 1) put(INTAKE, d[0] - 40);
                d = ask(0x46); if (d != null && d.length >= 1) put(AMBIENT, d[0] - 40);
                d = ask(0x5C); if (d != null && d.length >= 1) put(OIL, d[0] - 40);
                d = ask(0x42);
                if (d != null && d.length >= 2) put(VOLT, (d[0] * 256 + d[1]) / 1000f);
                else readAdapterVoltage();
            }
            if (wantDtc) { wantDtc = false; dtcResult = readDtc(); }
            if (misses > 5) throw new Exception("Zündung aus");
            notifyUi();
            n++;
            Thread.sleep(250);
        }
    }

    /** The adapter measures the voltage on OBD pin 16 itself (works with the engine off as well). */
    private static void readAdapterVoltage() throws Exception {
        String r = cmd("ATRV", 1500);
        StringBuilder num = new StringBuilder();
        for (char ch : r.toCharArray()) {
            if (Character.isDigit(ch) || ch == '.') num.append(ch);
            else if (num.length() > 0) break;
        }
        try {
            float v = Float.parseFloat(num.toString());
            if (v > 6 && v < 20) put(VOLT, v);
        } catch (Exception ignored) { }
    }

    /** Stored engine fault codes (mode 03), e.g. "P0401, P2002", or "Keine Fehler gespeichert". */
    private static String readDtc() {
        try {
            String r = cmd("03", 5000);
            if (r.contains("NODATA")) return "Keine Fehler gespeichert";
            List<String> codes = new ArrayList<>();
            for (String line : r.split("[\\r\\n]+")) {
                int i = line.indexOf("43");
                if (i < 0) continue;
                String hex = line.substring(i + 2);
                if (can && hex.length() >= 2) hex = hex.substring(2);   // CAN answers start with the number of codes
                for (int k = 0; k + 4 <= hex.length(); k += 4) {
                    int v = Integer.parseInt(hex.substring(k, k + 4), 16);
                    if (v == 0) continue;
                    String code = "PCBU".charAt(v >> 14) + String.format(Locale.ROOT, "%d%03X", (v >> 12) & 3, v & 0xFFF);
                    if (!codes.contains(code)) codes.add(code);
                }
            }
            return codes.isEmpty() ? "Keine Fehler gespeichert" : String.join(", ", codes);
        } catch (Exception e) {
            return "Fehlercodes nicht lesbar";
        }
    }
}
