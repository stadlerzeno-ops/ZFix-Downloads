package ch.zfix.fmradio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.nwd.radio.service.RadioCallback;
import com.nwd.radio.service.RadioFeature;
import com.nwd.radio.service.data.Frequency;

/**
 * Binds the radio service of the NWD head unit (Fiat Ducato, SPRD/UIS8581) and drives the FM tuner.
 * The service com.nwd.radio.service.RadioService is exported (action ACTION_RADIO_SERVICE) and returns
 * the RadioFeature binder. Frequencies are in 10 kHz steps (9290 = 92.90 MHz). FM band = FM1 (0).
 */
final class Radio {
    static final String PKG = "com.nwd.radio.service";
    static final String SVC = "com.nwd.radio.service.RadioService";
    static final String ACTION = "com.nwd.radio.service.ACTION_RADIO_SERVICE";
    // broadcast the service listens to internally, as a fallback to open/tune FM
    static final String ACTION_SET_FREQ = "com.nwd.action.ACTION_SET_RADIO_FREQUENCE";
    static final String EXTRA_BAND = "extra_band";
    static final String EXTRA_FREQ = "extra_frequence";

    static final byte FM1 = 0;
    static final int FM_MIN = 8750, FM_MAX = 10800, FM_STEP = 10;   // Europe: 87.5-108.0 MHz, 100 kHz grid

    interface Listener {
        void onConnected(boolean ok);
        void onFrequency(int freq, String psName, int prefabIndex);
        void onScanState(int state);
        void onRds(String radioText);
        void onPresets(Frequency[] presets);
    }

    private final Context ctx;
    private final Listener l;
    private RadioFeature svc;
    private boolean bound;

    Radio(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = RadioFeature.Stub.asInterface(binder);
            try { svc.registCallback(cb); } catch (Exception ignored) { }
            l.onConnected(svc != null);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            svc = null; l.onConnected(false);
        }
    };

    private final RadioCallback.Stub cb = new RadioCallback.Stub() {
        @Override public void notifyCurrentFrequency(byte band, int freq, String ps, int prefabIndex) {
            l.onFrequency(freq, ps, prefabIndex);
        }
        @Override public void notifyRadioScanState(int state) { l.onScanState(state); }
        @Override public void notifyRtMessage(String rt) { l.onRds(rt); }
        @Override public void notifyPrefabFrequency(Frequency[] f) { l.onPresets(f); }
        @Override public void notifyState(byte s) { }
        @Override public void notifyNearOn(boolean b) { }
        @Override public void notifyStereo(boolean b) { }
        @Override public void notifyRDSStateChange() { }
        @Override public void notifyCurrentPTYType(byte b) { }
        @Override public void notifyPrefabPTYType(byte b) { }
        @Override public void notifyRadioPoint(com.nwd.radio.service.data.RadioPoint[] p) { }
        @Override public void notifyStereoOn(boolean b) { }
        @Override public void notifyCurrentIsTA(boolean b) { }
        @Override public void notifyRdsShowState(boolean b) { }
    };

    void bind() {
        if (bound) return;
        Intent i = new Intent(ACTION).setComponent(new ComponentName(PKG, SVC));
        try { bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE); } catch (Exception e) { bound = false; }
        if (!bound) l.onConnected(false);
    }

    void unbind() {
        try { if (svc != null) svc.unRegistCallback(cb); } catch (Exception ignored) { }
        try { if (bound) ctx.unbindService(conn); } catch (Exception ignored) { }
        bound = false; svc = null;
    }

    boolean ready() { return svc != null; }

    /** Tune to an exact frequency (10 kHz units). prefabIndex -1 = manual. */
    void tune(int freq, int prefabIndex) {
        freq = Math.max(FM_MIN, Math.min(FM_MAX, freq));
        try {
            if (svc != null) svc.setCurrentFrequency(freq, FM1, prefabIndex);
        } catch (RemoteException ignored) { }
        // belt-and-suspenders: also ask the service via its broadcast (opens FM if needed)
        try {
            ctx.sendBroadcast(new Intent(ACTION_SET_FREQ).putExtra(EXTRA_BAND, FM1).putExtra(EXTRA_FREQ, freq));
        } catch (Exception ignored) { }
    }

    void seek(boolean up) { try { if (svc != null) svc.seek(up); } catch (RemoteException ignored) { } }
    void step(boolean up, int cur) { tune(up ? cur + FM_STEP : cur - FM_STEP, -1); }
    void scan() { try { if (svc != null) svc.search(true); } catch (RemoteException ignored) { } }
    void savePreset(int index) { try { if (svc != null) svc.saveCurrentFrequency((byte) index); } catch (RemoteException ignored) { } }

    int currentFreq() {
        try { if (svc != null) { Frequency f = svc.getCurrentFrequency(); if (f != null) return f.getFrequency(); } } catch (Exception ignored) { }
        return 0;
    }

    Frequency[] presets() {
        try { if (svc != null) return svc.getPrefabFrequency(); } catch (Exception ignored) { }
        return null;
    }
}
