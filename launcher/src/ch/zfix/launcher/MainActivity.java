package ch.zfix.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.SoundPool;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final int WHITE = 0xFFF2F2F2;
    private static final int DIM = 0xFF9A9A9A;
    private static final int CARD_FILL = 0x0FFFFFFF;
    private static final int CARD_LINE = 0x1AFFFFFF;
    private static final int RED = 0xFFD62828;

    private static final String[] TILE_NAME = {"CarPlay · Android Auto", "Karte", "DAB+", "Radio"};
    private static final String[] TILE_SUB = {"iPhone · Android", "Google Maps", "Noxon · UKW 92.90", "UKW · FM"};
    // Zeno wants stencil-style white logos on the tiles, not the colored originals
    private static final boolean USE_COLOR_LOGOS = false;
    private static final String[] TILE_LOGO = {"carplay.png", "androidauto.png"};
    private final boolean[] tileLogo = new boolean[4];
    private static final int[] TILE_ICON = {R.drawable.ic_phone, R.drawable.ic_pin, R.drawable.ic_dab, R.drawable.ic_radio};
    private static final String[][] TILE_KEYWORDS = {
            {"ch.zfix.connect", "carlink", "diplay", "carplay", "zlink"},   // one tile for both phones
            {"com.google.android.apps.maps", "maps"},
            {"dab", "ch.zfix.radio", "com.tw.radio", "fmradio", "radio"},   // DAB+ = app of the USB DAB+ box, else Noxon Road 2 on FM 92.90
            {"ch.zfix.radio", "com.tw.radio", "fmradio", "radio"}};
    /** Contract with ZFix Radio: tune silently, no UI. Frequency in 10 kHz steps (9290 = 92.90 MHz). */
    private static final String ZFIX_RADIO = "ch.zfix.radio";
    private static final boolean USE_ZFIX_RADIO = true;
    private static final String ACTION_TUNE = "ch.zfix.radio.action.TUNE";
    private static final int NOXON_FREQ = 9290;
    /** ZFIX Connect: front screen for CarPlay (CarLink) and Android Auto (ZLink), one action per side. */
    private static final String ZFIX_CONNECT = "ch.zfix.connect";
    private static final int TILE_NOXON = 2;
    private static final int TILE_PHONE = 0, TILE_MAP = 1;
    /** Maps opens as a window over the tile area; the clock/speed column from x=914 stays visible. */
    private static final int MAP_L = 36, MAP_T = 36, MAP_R = 894, MAP_B = 684, MAP_RADIUS = 24;
    private View mapCorners;

    private float s;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView clock, date, nowLabel, nowTitle, nowSub, speedText;
    private ImageView playBtn;
    private LimitSign sign;
    private final TextView[] tileSub = new TextView[TILE_NAME.length];
    private final TextView[] tileTitle = new TextView[TILE_NAME.length];
    private final ImageView[] tileIcon = new ImageView[TILE_NAME.length];
    private final View[] tileView = new View[TILE_NAME.length];
    /** Tile of the current audio source, white on the home screen; -1 = none (e.g. Bluetooth music). */
    private int sourceTile = -1;

    private LocationManager lm;
    private Location lastQueryLoc;
    private long lastQueryTime, lastFixTime;
    private TextView speedUnit;
    private Location prevFix;
    private long lastGpsTime;
    private float smooth = -1;
    private LimitCache limits;
    private long lastPrefetch;
    private int currentLimit = -1, currentSpeed = -1;
    private SoundPool sounds;
    private int warnSound;
    private boolean warned;   // the tone plays once per speeding, again only after being back under the limit
    private final AtomicBoolean querying = new AtomicBoolean(false);

    private FrameLayout root;
    private TextView status;
    /** Car battery in millivolts, sent by ZFix Radio (it reads the MCU); -1 = unknown. */
    private int batteryMv = -1;
    private long radioMvAt;   // last time ZFix Radio sent a usable value

    private static boolean plausible(int mv) { return mv >= 8000 && mv <= 17000; }

    /** Fallbacks when ZFix Radio sends nothing: the system battery (head units feed it the car
     *  voltage) and the kernel power-supply files. Values outside 8-17 V are ignored. */
    private int readSystemMv() {
        try {
            Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int v = b.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1);
                if (v > 0 && v < 100) v *= 1000;   // some report volts
                if (plausible(v)) return v;
            }
        } catch (Exception ignored) { }
        java.io.File[] dirs = new java.io.File("/sys/class/power_supply").listFiles();
        if (dirs != null) for (java.io.File d : dirs) {
            for (String f : new String[]{"voltage_now", "batt_vol", "voltage_avg"}) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(d, f)))) {
                    long v = Long.parseLong(r.readLine().trim());
                    if (v > 100000) v /= 1000;   // microvolts
                    if (plausible((int) v)) return (int) v;
                } catch (Exception ignored) { }
            }
        }
        return -1;
    }
    private final android.content.BroadcastReceiver voltReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            int mv = i.getIntExtra("mv", -1);
            if (plausible(mv)) { batteryMv = mv; radioMvAt = System.currentTimeMillis(); }
            updateStatus();
        }
    };

    private MediaSessionManager msm;
    private MediaController controller;

    /** Car profile (Opel Agila / Fiat Ducato), from profil.json of the deployment tool or the ZFix menu. */
    private Profile profile;
    private LinearLayout carBar;
    private TextView carMsg;
    private LinearLayout carCells;
    private final TextView[] carVal = new TextView[4], carLab = new TextView[4];
    private TextView carDetail;   // value list in the open "Fahrzeug" panel, null when closed

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("zfix", MODE_PRIVATE);
        if (prefs.getInt("source", -1) == 1) prefs.edit().putInt("source", TILE_PHONE).apply();   // 2.7: 1 was Android Auto
        // navigation-guidance usage: the radio lowers the music briefly instead of mixing at full volume
        sounds = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        warnSound = sounds.load(this, R.raw.warnton, 1);
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        s = w / 1280f;
        profile = Profile.load(this);
        setContentView(buildUi());
        updateBadge();
        if (profile.fromFile || prefs.contains("p_fahrzeug")) offerKeyBar();
        askCarIfUnknown();
        syncCarAlias();
        if (openedAsCarApp(getIntent())) { setIntent(new Intent()); showCarDash(); }
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        limits = new LimitCache(this);
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(tick);
        ui.post(tick);
        try { registerReceiver(voltReceiver, new android.content.IntentFilter("ch.zfix.action.VOLTAGE")); } catch (Exception ignored) { }
        startLocation();
        startMedia();
        refreshTiles();
        Obd.listener = obdListener;
        startObd();
        updateCarBar();
        Updater.StatusReceiver.listener = updListener;
        autoCheckUpdate();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        try { unregisterReceiver(voltReceiver); } catch (Exception ignored) { }
        // keep GPS running behind CarPlay/ZLink so the speed is there at once when coming back
        stopMedia();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideMapCorners();   // back on the home screen, Maps is behind us
            // no Android bars on the ZFix screen; our own status line shows Bluetooth, WLAN and volts
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onBackPressed() {
        // home screen: Back only closes an open ZFix panel
        if (root.getChildCount() > 0) {
            View top = root.getChildAt(root.getChildCount() - 1);
            if (top instanceof FrameLayout && top.isClickable()) root.removeView(top);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == 2) { Obd.stop(); startObd(); return; }
        startLocation();
    }

    // ---------------------------------------------------------------- UI

    private int px(float v) { return Math.round(v * s); }

    private TextView text(String t, float size, int color, boolean medium) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        tv.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif-light", Typeface.NORMAL));
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    private GradientDrawable card(int fill, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius * s);
        g.setStroke(Math.max(1, px(1)), CARD_LINE);
        return g;
    }

    private FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        this.root = root;
        root.setBackgroundResource(R.drawable.zfix_bg);

        // top bar
        TextView brand = text("ZFIX", 18, Color.WHITE, true);
        brand.setLetterSpacing(0.15f);
        root.addView(brand, at(px(36), px(20), ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        brand.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showMenu(); return true; }
        });
        updBadge = text("UPDATE", 16, 0xFF0B0B0C, true);
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(WHITE);
        ub.setCornerRadius(10 * s);
        updBadge.setBackground(ub);
        updBadge.setPadding(px(14), px(8), px(14), px(8));
        root.addView(updBadge, at(px(120), px(14), ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showUpdate(); }
        });
        updBadge.setVisibility(View.GONE);

        ImageView apps = new ImageView(this);
        apps.setImageResource(R.drawable.ic_apps);
        apps.setPadding(px(8), px(8), px(8), px(8));
        apps.setAlpha(0.8f);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(px(48), px(48));
        alp.gravity = Gravity.END | Gravity.TOP;
        alp.rightMargin = px(28);
        alp.topMargin = px(10);
        root.addView(apps, alp);
        apps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAppDrawer(); }
        });

        status = text("", 16, DIM, true);
        status.setLetterSpacing(0.08f);
        FrameLayout.LayoutParams slp0 = new FrameLayout.LayoutParams(-2, -2);
        slp0.gravity = Gravity.END | Gravity.TOP;
        slp0.rightMargin = px(96);
        slp0.topMargin = px(22);
        root.addView(status, slp0);

        // left column
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // info column on the right, next to the driver (left-hand drive)
        root.addView(left, at(px(1280 - 36 - 330), px(72), px(330), ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = text("--:--", 88, WHITE, false);
        left.addView(clock);
        date = text("", 22, 0xFFA8A8A8, false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(10);
        left.addView(date, dlp);

        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        now.setBackground(card(CARD_FILL, 20));
        now.setPadding(px(22), px(18), px(22), px(18));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(28);
        left.addView(now, nlp);
        nowLabel = text("JETZT LÄUFT", 14, 0xFF888888, true);
        nowLabel.setLetterSpacing(0.12f);
        now.addView(nowLabel);
        nowTitle = text("Nichts", 26, WHITE, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = px(8);
        now.addView(nowTitle, tlp);
        nowSub = text("", 17, 0xFFAAAAAA, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(4);
        now.addView(nowSub, slp);
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(16);
        now.addView(ctrl, clp);
        ctrl.addView(ctrlButton(R.drawable.ic_prev, 0));
        playBtn = ctrlButton(R.drawable.ic_play, 1);
        ctrl.addView(playBtn);
        ctrl.addView(ctrlButton(R.drawable.ic_next, 2));
        now.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNowPlaying(); }
        });

        // one big card: speed fills the space under "Jetzt läuft", the limit sign sits on its right
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.setBackground(card(CARD_FILL, 20));
        speedRow.setPadding(px(24), 0, px(18), 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, px(276));
        rlp.topMargin = px(20);
        left.addView(speedRow, rlp);
        LinearLayout speedBox = new LinearLayout(this);
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedBox.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.addView(speedBox, new LinearLayout.LayoutParams(0, -2, 1));
        speedText = text("–", SPEED_SIZE, WHITE, false);
        speedText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        speedText.setLetterSpacing(-0.04f);
        speedText.setIncludeFontPadding(false);
        speedText.setSingleLine(true);
        speedBox.addView(speedText);
        TextView unit = text("km/h · aktuell", 18, DIM, false);
        speedUnit = unit;
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.topMargin = px(12);
        speedBox.addView(unit, ulp);
        FrameLayout signBox = new FrameLayout(this);
        speedRow.addView(signBox, new LinearLayout.LayoutParams(px(100), px(100)));
        sign = new LimitSign(this);
        FrameLayout.LayoutParams sgl = new FrameLayout.LayoutParams(px(100), px(100));
        sgl.gravity = Gravity.CENTER;
        signBox.addView(sign, sgl);

        // tile grid
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-1, -1);
        glp.leftMargin = px(36);
        glp.topMargin = px(90);
        glp.rightMargin = px(400);
        glp.bottomMargin = px(showCarBar() ? 36 + CAR_BAR_H + 20 : 36);
        root.addView(grid, glp);
        if (showCarBar()) buildCarBar(root);
        // 2x2: CarPlay / Android Auto on top, DAB+ / Radio below
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, 0, 1);
            if (r == 1) rowLp.topMargin = px(20);
            grid.addView(row, rowLp);
            for (int c = 0; c < 2; c++) {
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1);
                if (c == 1) tl.leftMargin = px(20);
                row.addView(tile(r * 2 + c), tl);
            }
        }
        return root;
    }

    private ImageView ctrlButton(int icon, final int action) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setPadding(px(6), px(6), px(6), px(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
        lp.rightMargin = px(18);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mediaAction(action); }
        });
        return iv;
    }

    private View tile(final int i) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(px(26), px(26), px(26), px(26));
        ImageView icon = new ImageView(this);
        icon.setImageResource(TILE_ICON[i]);
        if (USE_COLOR_LOGOS && i < TILE_LOGO.length) {
            // official logos are pushed by adb to Android/data/ch.zfix.launcher/files/logos/ (not bundled)
            try {
                java.io.File f = new java.io.File(getExternalFilesDir("logos"), TILE_LOGO[i]);
                android.graphics.Bitmap b = f.exists() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
                if (b != null) { icon.setImageBitmap(b); tileLogo[i] = true; }
            } catch (Exception ignored) { }
        }
        t.addView(icon, new LinearLayout.LayoutParams(px(56), px(56)));
        t.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        tileTitle[i] = text(TILE_NAME[i], i == TILE_PHONE ? 26 : 30, WHITE, true);
        t.addView(tileTitle[i]);
        tileSub[i] = text(tileSubText(i), 16, DIM, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(6);
        t.addView(tileSub[i], lp);
        tileView[i] = t;
        tileIcon[i] = icon;
        styleTile(i, false);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String pkg = tilePackage(i);
                // DAB+ box car without ZFix DAB yet: offer it from the update page instead of opening an FM app
                if (i == TILE_NOXON && !profile.noxonAllowed() && !isInstalled("ch.zfix.dab")) { showUpdate(); return; }
                if (i >= TILE_NOXON) pausePhoneAudio(pkg);
                if (i == TILE_MAP) { if (pkg == null || !launchMap(pkg)) pickAppForTile(i); return; }
                if (i == TILE_PHONE && pkg != null && launch(pkg)) { setSource(i); return; }
                if (i == TILE_NOXON && pkg != null && isDabApp(pkg)) {
                    // USB DAB+ box: its app plays through Android, so the FM tuner has to let go of the speakers first
                    stopZfixRadio();
                    if (!launch(pkg)) { pickAppForTile(i); return; }
                }
                else if (i == TILE_NOXON && pkg != null && profile.noxonAllowed()) tuneNoxon(pkg);
                else if (pkg == null || !launch(pkg)) { pickAppForTile(i); return; }
                setSource(i);
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { pickAppForTile(i); return true; }
        });
        return t;
    }

    private void styleTile(int i, boolean hi) {
        Drawable bg = card(hi ? 0xEBFFFFFF : CARD_FILL, 24);
        if (i == TILE_MAP) {
            // street sketch inside the card, clipped by the rounded outline
            bg = new android.graphics.drawable.LayerDrawable(new Drawable[]{bg, getDrawable(R.drawable.map_bg)});
            tileView[i].setClipToOutline(true);
        }
        tileView[i].setBackground(bg);
        if (tileLogo[i]) tileIcon[i].clearColorFilter();
        else if (hi) tileIcon[i].setColorFilter(0xFF0B0B0C, PorterDuff.Mode.SRC_IN);
        else tileIcon[i].clearColorFilter();
        tileTitle[i].setTextColor(hi ? 0xFF0B0B0C : WHITE);
        tileSub[i].setTextColor(hi ? 0xFF555555 : DIM);
    }

    /** Remember which source the user picked; apps without a media session (ZLink, FM) are only known this way. */
    private void setSource(int tile) {
        prefs.edit().putInt("source", tile).apply();
        highlight(tile);
        showMedia();
    }

    private void highlight(int tile) {
        sourceTile = tile;
        for (int i = 0; i < TILE_NAME.length; i++) styleTile(i, i == tile);
    }

    /** Tile for an app package, or -1 (Bluetooth music and others have no tile). */
    private int tileForPackage(String pkg) {
        if (pkg == null) return -1;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("carlink") || p.contains("diplay") || p.contains("zlink") || p.contains("androidauto")) return TILE_PHONE;
        if (p.contains("dab")) return TILE_NOXON;
        if (p.contains("radio")) {
            int saved = prefs.getInt("source", -1);
            return saved == TILE_NOXON ? TILE_NOXON : 3;
        }
        return -1;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.GERMAN).format(d));
            date.setText(new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(d));
            if (System.currentTimeMillis() - lastFixTime > 5000 && !Obd.fresh(Obd.SPEED)) {
                smooth = -1;
                showSpeed("–");
                speedText.setTextColor(WHITE);
                speedUnit.setText(gpsState());
            }
            if (d.getTime() / 1000 % 5 == 0) { showMedia(); updateStatus(); }   // ZLink/FM have no callbacks, poll their notifications
            ui.postDelayed(this, 1000);
        }
    };


    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        try {
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) sb.append("BT");
        } catch (Exception ignored) { }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) sb.append(sb.length() > 0 ? "  ·  " : "").append("WLAN");
        } catch (Exception ignored) { }
        if (Obd.fresh(Obd.VOLT)) batteryMv = Math.round(Obd.val[Obd.VOLT] * 1000);
        else if (System.currentTimeMillis() - radioMvAt > 15000) batteryMv = readSystemMv();
        if (batteryMv > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append(String.format(Locale.GERMAN, "%.1f V", batteryMv / 1000f));
        status.setText(sb.toString());
        status.setTextColor(batteryMv > 0 && batteryMv < 11800 ? RED : DIM);
    }

    // ---------------------------------------------------------------- apps

    private List<ResolveInfo> launchableApps() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final PackageManager pm = getPackageManager();
        List<ResolveInfo> list = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (!r.activityInfo.packageName.equals(getPackageName())) list.add(r);
        }
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        return list;
    }

    private String tilePackage(int i) {
        String saved = prefs.getString("t_" + TILE_NAME[i], null);
        if (saved != null && getPackageManager().getLaunchIntentForPackage(saved) != null) return saved;
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = launchableApps();
        for (String kw : TILE_KEYWORDS[i]) {
            for (ResolveInfo r : apps) {
                String p = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
                String l = r.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
                boolean dabApp = p.contains("dab") || l.contains("dab");
                if (i == TILE_NOXON && !profile.noxonAllowed() && !dabApp) continue;
                if (i == TILE_NOXON && "noxon".equals(profile.dab) && dabApp) continue;
                if (i > TILE_NOXON && dabApp) continue;
                if (p.contains(kw) || l.contains(kw)) return r.activityInfo.packageName;
            }
        }
        return null;
    }

    private String dabPkg;   // app on the DAB+ tile, refreshed with the tiles (showMedia runs every 5 s)

    private void refreshTiles() {
        dabPkg = tilePackage(TILE_NOXON);
        for (int i = 0; i < TILE_NAME.length; i++) {
            tileSub[i].setText(tilePackage(i) == null ? "Lange drücken: App wählen" : tileSubText(i));
        }
    }

    /**
     * The Noxon Road 2 sends on FM 92.90, stored as P1 and last station of the stock radio.
     * Starting the radio app switches the audio source to FM; we come straight back so only the sound changes.
     */
    private void tuneNoxon(String pkg) {
        // ZFix Radio 1.1+ tunes the Noxon silently in the background (confirmed on the unit 2026-10-13)
        if (USE_ZFIX_RADIO && isInstalled(ZFIX_RADIO)) {
            Intent t = new Intent(ACTION_TUNE).setPackage(ZFIX_RADIO).putExtra("freq", profile.dabFreq);
            t.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            sendBroadcast(t);
            tileSub[TILE_NOXON].setText("Noxon läuft");
            return;
        }
        // fallback without ZFix Radio: the stock radio resumes its last station (P1 = 92.90)
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { startActivity(i); } catch (Exception e) { return; }
        overridePendingTransition(0, 0);
        tileSub[TILE_NOXON].setText("Noxon läuft");
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                Intent back = new Intent(MainActivity.this, MainActivity.class);
                back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(back);
                overridePendingTransition(0, 0);
            }
        }, 900);
    }

    private boolean openConnect(String mode) {
        if (!isInstalled(ZFIX_CONNECT)) return false;
        Intent i = new Intent(mode.equals("carplay") ? "ch.zfix.connect.action.CARPLAY" : "ch.zfix.connect.action.ANDROID_AUTO")
                .setPackage(ZFIX_CONNECT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** CarLink/ZLink keep playing over FM (FM runs through the MCU, not Android audio), so pause them first. */
    private void pausePhoneAudio(String keep) {
        try {
            for (MediaController mc : msm.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                String p = mc.getPackageName();
                if (p.equals(keep)) continue;
                // FM goes through the MCU, but a DAB+ box app plays through Android like the phone does
                if (p.contains("radio") && !isDabApp(p)) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            }
        } catch (Exception ignored) { }
    }

    /**
     * Google Maps as a freeform window on the left, so the launcher column on the right stays visible.
     * Needs freeform support switched on (WRITE_SECURE_SETTINGS, granted by install.sh); without it
     * Android ignores the bounds and Maps opens full screen as before.
     */
    private boolean launchMap(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            android.content.ContentResolver cr = getContentResolver();
            if (Settings.Global.getInt(cr, "enable_freeform_support", 0) != 1) Settings.Global.putInt(cr, "enable_freeform_support", 1);
            if (Settings.Global.getInt(cr, "force_resizable_activities", 0) != 1) Settings.Global.putInt(cr, "force_resizable_activities", 1);
        } catch (Exception ignored) { }
        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
        final android.graphics.Rect r = new android.graphics.Rect(px(MAP_L), px(MAP_T), px(MAP_R), px(MAP_B));
        o.setLaunchBounds(r);
        try {
            // hidden on Android 12; WINDOWING_MODE_FREEFORM = 5
            android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, 5);
        } catch (Throwable ignored) { }
        try { startActivity(i, o.toBundle()); }
        catch (Exception e) { return launch(pkg); }
        ui.postDelayed(new Runnable() { @Override public void run() { showMapCorners(r); } }, 400);
        return true;
    }

    /** Maps has square corners; a click-through overlay paints the background into them so the window looks like a tile. */
    private void showMapCorners(android.graphics.Rect r) {
        hideMapCorners();
        if (!Settings.canDrawOverlays(this)) return;
        final float rad = px(MAP_RADIUS);
        View v = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final android.graphics.Path path = new android.graphics.Path();
            @Override protected void onDraw(Canvas c) {
                path.reset();
                path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                path.addRect(0, 0, getWidth(), getHeight(), android.graphics.Path.Direction.CW);
                path.addRoundRect(0, 0, getWidth(), getHeight(), rad, rad, android.graphics.Path.Direction.CW);
                p.setColor(0xFF0B0B0C);
                c.drawPath(path, p);
            }
        };
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(r.width(), r.height(),
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.x = r.left;
        lp.y = r.top;
        try { getWindowManager().addView(v, lp); mapCorners = v; } catch (Exception ignored) { }
    }

    private void hideMapCorners() {
        if (mapCorners == null) return;
        try { getWindowManager().removeView(mapCorners); } catch (Exception ignored) { }
        mapCorners = null;
    }

    private boolean launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private void pickAppForTile(final int tileIndex) {
        appDialog("Apps", new AppPicked() {
            @Override public void picked(String pkg) {
                prefs.edit().putString("t_" + TILE_NAME[tileIndex], pkg).apply();
                refreshTiles();
            }
        });
    }

    /** The apps the driver needs; radio tools (DSP, Fan, Steering Wheel ...) stay under ZFix > Alle Apps. */
    private static final String[] DRAWER_KEEP = {"maps", "zfix", "carlink", "zlink", "com.tw.bt", "bluetooth"};

    private void showAppDrawer() {
        drawerFilter = true;
        showAllApps();
        drawerFilter = false;
    }

    private boolean drawerFilter;

    private void showAllApps() {
        appDialog("Apps", new AppPicked() {
            @Override public void picked(String pkg) { launch(pkg); }
        });
    }

    private interface AppPicked { void picked(String pkg); }

    /** Full-screen ZFix panel (replaces Android dialogs). Returns the content area to fill. */
    private LinearLayout overlay(String title) {
        final FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xFF0B0B0C);
        ov.setClickable(true);
        TextView t = text(title.toUpperCase(Locale.GERMAN), 18, WHITE, true);
        t.setLetterSpacing(0.15f);
        ov.addView(t, at(px(36), px(28), -2, -2));
        TextView close = text("SCHLIESSEN", 18, DIM, true);
        close.setPadding(px(16), px(10), px(16), px(10));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
        clp.gravity = Gravity.END | Gravity.TOP;
        clp.rightMargin = px(20);
        clp.topMargin = px(16);
        ov.addView(close, clp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { root.removeView(ov); }
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = px(80);
        slp.leftMargin = px(36);
        slp.rightMargin = px(36);
        ov.addView(sv, slp);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setTag(ov);
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        return content;
    }

    private void closeOverlay(LinearLayout content) {
        root.removeView((View) content.getTag());
    }

    private void appDialog(String title, final AppPicked cb) {
        final List<ResolveInfo> apps = launchableApps();
        final PackageManager pm = getPackageManager();
        if (drawerFilter) {
            for (int k = apps.size() - 1; k >= 0; k--) {
                String id = (apps.get(k).activityInfo.packageName + " " + apps.get(k).loadLabel(pm)).toLowerCase(Locale.ROOT);
                boolean keep = false;
                for (String kw : DRAWER_KEEP) if (id.contains(kw)) keep = true;
                if (!keep) apps.remove(k);
            }
        }
        final LinearLayout content = overlay(title);
        final int cols = 6;
        LinearLayout row = null;
        for (int n = 0; n < apps.size(); n++) {
            if (n % cols == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, px(150));
                rlp.bottomMargin = px(16);
                content.addView(row, rlp);
            }
            final ResolveInfo r = apps.get(n);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(card(CARD_FILL, 20));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(r.loadIcon(pm));
            cell.addView(ic, new LinearLayout.LayoutParams(px(64), px(64)));
            TextView tv = text(r.loadLabel(pm).toString(), 16, WHITE, false);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = px(12);
            tlp.leftMargin = px(8);
            tlp.rightMargin = px(8);
            cell.addView(tv, tlp);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeOverlay(content);
                    if (r.activityInfo.name.endsWith(".Fahrzeugdaten")) { showCarDash(); return; }
                    cb.picked(r.activityInfo.packageName);
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
            if (n % cols != 0) cl.leftMargin = px(16);
            row.addView(cell, cl);
        }
        if (row != null) {
            for (int k = apps.size() % cols; k != 0 && k < cols; k++) {
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
                cl.leftMargin = px(16);
                row.addView(new View(this), cl);
            }
        }
    }

    private void showMenu() {
        final LinearLayout content = overlay("ZFix");
        int upd = prefs.getInt("upd_count", 0);
        String[] items = {upd > 0 ? "Update  ·  neue Version bereit" : "Update suchen", "Android-Einstellungen", "Alle Apps", "Kachel-Apps zurücksetzen", "Jetzt-läuft-Zugriff erlauben",
                "Entwickleroptionen", "Tempolimit-Karten: " + limits.tileCount() + " Kacheln gespeichert",
                "Fahrzeug: " + profile.name + (profile.ducato() ? "  ·  zu Opel Agila wechseln" : "  ·  zu Fiat Ducato wechseln"),
                "Fahrzeugdaten und OBD-Adapter", "Tasten links (Zurück · Home · Lauter · Leiser): " + keyBarState(), "CAN-Suche (Daten der CAN-Box finden)", "Radio-Einstellungen (CAN-Box, Lenkrad, Kamera)"};
        for (int n = 0; n < items.length; n++) {
            final int which = n;
            TextView b = text(items[n], 24, WHITE, true);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(px(28), 0, px(28), 0);
            b.setBackground(card(CARD_FILL, 20));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(84));
            lp.bottomMargin = px(16);
            content.addView(b, lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeOverlay(content);
                    if (which == 0) showUpdate();
                    if (which == 1) startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    if (which == 2) showAllApps();
                    if (which == 3) {
                        SharedPreferences.Editor e = prefs.edit();
                        for (String k : prefs.getAll().keySet()) if (k.startsWith("t_") || k.equals("source")) e.remove(k);
                        e.apply();
                        refreshTiles(); showMedia();
                    }
                    if (which == 4) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    // opened directly, the radio's own settings menu (and its code prompt) is skipped (tested 2026-10-17)
                    if (which == 5) {
                        openDevOptions();
                    }
                    if (which == 7) {
                        prefs.edit().putString("p_fahrzeug", profile.ducato() ? Profile.AGILA : Profile.DUCATO).remove("p_obd").apply();
                        Obd.stop();
                        if (KeyBar.instance != null) KeyBar.instance.refresh();
                        recreate();
                    }
                    if (which == 8) showCarDash();
                    if (which == 10) showCanScan();
                    if (which == 11) showRadioTools();
                    if (which == 9) {
                        boolean on = KeyBar.wanted(MainActivity.this);
                        if (on && keyBarEnabled()) prefs.edit().putString(KeyBar.PREF, "aus").apply();
                        else { prefs.edit().putString(KeyBar.PREF, "an").apply(); if (!keyBarEnabled()) showKeyBarSetup(); }
                        if (KeyBar.instance != null) KeyBar.instance.refresh();
                    }
                }
            });
        }
    }


    // ---------------------------------------------------------------- online update (GitHub releases)

    private LinearLayout updList;
    private TextView updStatus;

    private TextView bigButton(String label, float size, int h, LinearLayout parent) {
        TextView b = text(label, size, WHITE, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(28), 0, px(28), 0);
        b.setBackground(card(CARD_FILL, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(h));
        lp.bottomMargin = px(16);
        parent.addView(b, lp);
        return b;
    }

    /** ZFix menu > Update: shows the ZFix apps of the newest GitHub release, one big button per app. */
    private void showUpdate() {
        final LinearLayout content = overlay("Update");
        updStatus = text("Suche neue Version im Internet ...", 24, WHITE, false);
        updStatus.setSingleLine(false);
        updStatus.setMaxLines(4);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = px(24);
        content.addView(updStatus, sl);
        updList = new LinearLayout(this);
        updList.setOrientation(LinearLayout.VERTICAL);
        content.addView(updList, new LinearLayout.LayoutParams(-1, -2));
        ((View) content.getTag()).addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { updList = null; updStatus = null; }
        });
        Updater.StatusReceiver.listener = updListener;
        Updater.check(this, updListener);
    }

    private final Updater.Listener updListener = new Updater.Listener() {
        @Override public void onChecked(List<Updater.App> apps, String error) {
            updateBadge();
            if (updList == null) return;
            updList.removeAllViews();
            if (error != null) {
                updStatus.setText(error);
                TextView again = bigButton("NOCHMALS VERSUCHEN", 26, 96, updList);
                again.setGravity(Gravity.CENTER);
                again.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { updStatus.setText("Suche neue Version im Internet ..."); updList.removeAllViews(); Updater.check(MainActivity.this, updListener); }
                });
                return;
            }
            boolean any = false;
            for (Updater.App a : apps) if (a.newer()) any = true;
            boolean allowed = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
            updStatus.setText(any ? "Neue Version bereit. Auf AKTUALISIEREN tippen und danach im Android-Fenster bestätigen."
                    : "Alles ist auf dem neuesten Stand.");
            if (!allowed) {
                TextView allow = bigButton("ZUERST: INSTALLIEREN ERLAUBEN (einmalig)", 24, 96, updList);
                allow.setGravity(Gravity.CENTER);
                allow.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    }
                });
            }
            for (final Updater.App a : apps) {
                // other ZFix apps are only offered when installed, except the DAB+ app in a car with a DAB+ box profile
                if (a.missing() && !(a.pkg.equals("ch.zfix.dab") && !profile.noxonAllowed())) continue;
                String state = a.missing() ? "nicht installiert · neu " + a.version
                        : a.newer() ? "installiert " + a.installedVersion + "  ·  neu " + a.version
                        : "Version " + a.installedVersion + "  ·  aktuell";
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(28), 0, px(16), 0);
                row.setBackground(card(CARD_FILL, 20));
                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.addView(text(a.name, 26, WHITE, true));
                TextView st = text(state, 20, DIM, false);
                LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2);
                stl.topMargin = px(8);
                info.addView(st, stl);
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                if (a.newer() || a.missing()) {
                    final TextView go = text(a.missing() ? "INSTALLIEREN" : "AKTUALISIEREN", 24, 0xFF0B0B0C, true);
                    go.setGravity(Gravity.CENTER);
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(WHITE);
                    g.setCornerRadius(16 * s);
                    go.setBackground(g);
                    go.setPadding(px(28), 0, px(28), 0);
                    row.addView(go, new LinearLayout.LayoutParams(-2, px(84)));
                    go.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (Updater.busy) return;
                            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                                updStatus.setText("Zuerst oben INSTALLIEREN ERLAUBEN antippen, Schalter einschalten, dann zurück.");
                                return;
                            }
                            go.setText("LÄDT ...");
                            go.setTag(a.pkg);
                            Updater.install(MainActivity.this, a, updListener);
                        }
                    });
                    go.setTag(a.pkg);
                }
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, px(116));
                rl.bottomMargin = px(16);
                updList.addView(row, rl);
            }
        }

        @Override public void onProgress(Updater.App a, int percent) {
            TextView b = updButton(a.pkg);
            if (b != null) b.setText("LÄDT " + percent + " %");
        }

        @Override public void onDone(Updater.App a, String error) {
            if (updStatus == null) return;
            TextView b = updButton(a.pkg);
            if (error == null) {
                // handed to Android; its confirmation window comes next (the launcher restarts after its own update)
                if (b != null) b.setText("BITTE BESTÄTIGEN");
                updStatus.setText(a.name + ": im Android-Fenster auf Aktualisieren bzw. Installieren tippen.");
            } else if ("OK".equals(error)) {
                if (b != null) b.setText("FERTIG");
                updStatus.setText(a.name + " ist aktualisiert.");
                prefs.edit().putInt("upd_count", Math.max(0, prefs.getInt("upd_count", 0) - 1)).apply();
                updateBadge();
                refreshTiles();
            } else {
                if (b != null) b.setText("NOCHMALS");
                updStatus.setText(a.name + ": " + error);
            }
        }
    };

    private TextView updButton(String pkg) {
        if (updList == null) return null;
        View v = updList.findViewWithTag(pkg);
        return v instanceof TextView ? (TextView) v : null;
    }

    private TextView updBadge;

    /** Small "UPDATE" next to ZFIX when a newer version was found; tapping it opens the update panel. */
    private void updateBadge() {
        if (updBadge != null) updBadge.setVisibility(prefs.getInt("upd_count", 0) > 0 ? View.VISIBLE : View.GONE);
    }

    /** Quiet check at most every 12 hours, only to show the badge; nothing is installed without a tap. */
    private void autoCheckUpdate() {
        if (System.currentTimeMillis() - prefs.getLong("upd_checked", 0) < 12 * 3600_000L) return;
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        if (ni == null || !ni.isConnected()) return;
        Updater.check(this, new Updater.Listener() {
            @Override public void onChecked(List<Updater.App> apps, String error) { updateBadge(); }
            @Override public void onProgress(Updater.App a, int p) { }
            @Override public void onDone(Updater.App a, String e) { }
        });
    }

    private boolean keyBarEnabled() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/");
    }

    private String keyBarState() {
        if (!KeyBar.wanted(this)) return "aus";
        return keyBarEnabled() ? "an" : "noch einschalten";
    }

    /** One-time help: the bar is an accessibility service, Android only lets the user switch it on. */
    private void showKeyBarSetup() {
        final LinearLayout content = overlay("Tasten einschalten");
        TextView t = text("", 24, WHITE, false);
        t.setSingleLine(false);
        t.setMaxLines(12);
        t.setLineSpacing(px(8), 1f);
        t.setText("Dieses Radio hat keine Tasten für Zurück und Startbildschirm. ZFix zeigt sie links am Rand.\n\n"
                + "1. Unten auf EINSTELLUNGEN ÖFFNEN tippen.\n"
                + "2. Bei „Bedienungshilfen“ den Eintrag „ZFix Tasten“ öffnen und einschalten.\n"
                + "3. Ist der Schalter grau („eingeschränkte Einstellung“): Einstellungen > Apps > ZFix > Drei-Punkte-Menü oben rechts > "
                + "„Eingeschränkte Einstellungen zulassen“, dann Schritt 2 wiederholen.");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = px(28);
        content.addView(t, tl);
        TextView go = bigButton("EINSTELLUNGEN ÖFFNEN", 28, 110, content);
        go.setGravity(Gravity.CENTER);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeOverlay(content);
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            }
        });
    }

    /** Ducato (radio without keys): offer the bar once until it is switched on. */
    private void offerKeyBar() {
        if (!KeyBar.wanted(this) || keyBarEnabled() || prefs.getBoolean("tasten_gefragt", false)) return;
        prefs.edit().putBoolean("tasten_gefragt", true).apply();
        showKeyBarSetup();
    }

    /** First start without profil.json (radio installed from a USB stick, no Mac): ask which car this is. */
    private void askCarIfUnknown() {
        if (profile.fromFile || prefs.contains("p_fahrzeug")) return;
        // the Opel radio (Topway TS10) has ZFix Radio / the Topway radio app: no question for the parents
        if (isInstalled(ZFIX_RADIO) || isInstalled("com.tw.radio")) { prefs.edit().putString("p_fahrzeug", Profile.AGILA).apply(); return; }
        final LinearLayout content = overlay("Welches Auto?");
        TextView q = text("In welchem Auto ist dieses Radio eingebaut?", 26, WHITE, false);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-1, -2);
        ql.bottomMargin = px(32);
        content.addView(q, ql);
        String[][] cars = {{Profile.AGILA, "OPEL AGILA"}, {Profile.DUCATO, "FIAT DUCATO"}};
        for (final String[] c : cars) {
            TextView b = bigButton(c[1], 34, 140, content);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString("p_fahrzeug", c[0]).remove("p_obd").apply();
                    Obd.stop();
                    if (KeyBar.instance != null) KeyBar.instance.refresh();
                    recreate();
                }
            });
        }
    }

    // ---------------------------------------------------------------- car profile, DAB+ box, OBD

    private static final int CAR_BAR_H = 96;

    private boolean isDabApp(String pkg) {
        if (pkg == null) return false;
        if (pkg.toLowerCase(Locale.ROOT).contains("dab")) return true;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().toLowerCase(Locale.ROOT).contains("dab");
        } catch (Exception e) { return false; }
    }

    private String noxonText() {
        return String.format(Locale.ROOT, "UKW %d.%02d", profile.dabFreq / 100, profile.dabFreq % 100);
    }

    private String tileSubText(int i) {
        if (i != TILE_NOXON) return TILE_SUB[i];
        String pkg = tilePackage(TILE_NOXON);
        if (isDabApp(pkg)) return "DAB+ Box";
        return "Noxon · " + noxonText();
    }

    /** ZFix Radio holds the speakers on the FM channel of the MCU; the DAB+ box needs the Android channel. */
    private void stopZfixRadio() {
        if (!isInstalled(ZFIX_RADIO)) return;
        Intent t = new Intent("ch.zfix.radio.action.STOP").setPackage(ZFIX_RADIO);
        t.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(t);
    }

    /** 3.4: vehicle data no longer on the home screen (Zeno), only as the app "Fahrzeugdaten". */
    private boolean showCarBar() { return false; }

    private boolean carDataOn() { return profile.ducato() || !"aus".equals(profile.obd); }

    /** Shows/hides the "Fahrzeugdaten" entry in the app lists. */
    private void syncCarAlias() {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, getPackageName() + ".Fahrzeugdaten"),
                    carDataOn() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }

    private boolean openedAsCarApp(Intent i) {
        return i != null && i.getComponent() != null && i.getComponent().getClassName().endsWith(".Fahrzeugdaten");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (openedAsCarApp(i)) { setIntent(new Intent()); ui.post(new Runnable() { @Override public void run() { showCarDash(); } }); }
    }

    private boolean btPermAsked;

    private void startObd() {
        if ("bt".equals(profile.obd) && Build.VERSION.SDK_INT >= 31 && !btPermAsked
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            btPermAsked = true;   // install.sh grants it; ask once only if that was skipped
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"}, 2);
        }
        String bt = prefs.getString("obd_bt", null);
        Obd.start(this, profile.obd, profile.obdHost, bt);
    }

    private final Obd.Listener obdListener = new Obd.Listener() {
        @Override public void onObd() {
            ui.post(new Runnable() { @Override public void run() { onObdData(); } });
        }
    };

    private long lastObdUi;

    private void onObdData() {
        if (Obd.fresh(Obd.SPEED)) {
            lastFixTime = System.currentTimeMillis();   // keeps the "–" away while the car reports its speed
            currentSpeed = Math.round(Obd.val[Obd.SPEED]);
            showSpeed(String.valueOf(currentSpeed));
            speedUnit.setText("km/h · Fahrzeug");
            updateSpeedColor();
        }
        long now = System.currentTimeMillis();
        if (now - lastObdUi < 900) return;   // the bar does not need more than one update per second
        lastObdUi = now;
        updateCarBar();
        if (carDetail != null) carDetail.setText(carDetailText());
        updateCarDash();
    }

    private void buildCarBar(FrameLayout root) {
        carBar = new LinearLayout(this);
        carBar.setBackground(card(CARD_FILL, 20));
        carBar.setGravity(Gravity.CENTER_VERTICAL);
        carBar.setPadding(px(26), 0, px(26), 0);
        root.addView(carBar, at(px(36), px(720 - 36 - CAR_BAR_H), px(1280 - 400 - 36), px(CAR_BAR_H)));
        carBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCarDash(); }
        });
        carMsg = text("", 20, DIM, false);
        carBar.addView(carMsg, new LinearLayout.LayoutParams(0, -2, 1));
        carCells = new LinearLayout(this);
        carBar.addView(carCells, new LinearLayout.LayoutParams(0, -1, 4));
        for (int k = 0; k < 4; k++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            carVal[k] = text("–", 32, WHITE, false);
            cell.addView(carVal[k]);
            carLab[k] = text("", 13, 0xFF888888, true);
            carLab[k].setLetterSpacing(0.12f);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.topMargin = px(6);
            cell.addView(carLab[k], l);
            carCells.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private void updateCarBar() {
        if (carBar == null) return;
        boolean data = Obd.connected && (Obd.fresh(Obd.RPM) || Obd.fresh(Obd.COOLANT));
        carCells.setVisibility(data ? View.VISIBLE : View.GONE);
        carMsg.setVisibility(data ? View.GONE : View.VISIBLE);
        if (!data) {
            String st = "aus".equals(profile.obd) ? "Fahrzeugdaten aus · tippen zum Einrichten" : Obd.state;
            carMsg.setText(profile.name.toUpperCase(Locale.GERMAN) + "   " + st);
            return;
        }
        setCell(0, "KÜHLWASSER", Obd.fresh(Obd.COOLANT) ? Math.round(Obd.val[Obd.COOLANT]) + " °C" : "–",
                Obd.fresh(Obd.COOLANT) && Obd.val[Obd.COOLANT] >= 108 ? RED : WHITE);
        setCell(1, "DREHZAHL", Obd.fresh(Obd.RPM) ? String.valueOf(Math.round(Obd.val[Obd.RPM] / 10) * 10) : "–", WHITE);
        setCell(2, "BATTERIE", Obd.fresh(Obd.VOLT) ? String.format(Locale.GERMAN, "%.1f V", Obd.val[Obd.VOLT]) : "–",
                Obd.fresh(Obd.VOLT) && Obd.val[Obd.VOLT] < 11.8f ? RED : WHITE);
        if (Obd.fresh(Obd.FUEL)) setCell(3, "TANK", Math.round(Obd.val[Obd.FUEL]) + " %", Obd.val[Obd.FUEL] < 12 ? RED : WHITE);
        else if (Obd.fresh(Obd.BOOST)) setCell(3, "LADEDRUCK", String.format(Locale.GERMAN, "%.1f bar", Obd.val[Obd.BOOST]), WHITE);
        else setCell(3, "MOTORLAST", Obd.fresh(Obd.LOAD) ? Math.round(Obd.val[Obd.LOAD]) + " %" : "–", WHITE);
    }

    private void setCell(int k, String label, String value, int color) {
        carLab[k].setText(label);
        carVal[k].setText(value);
        carVal[k].setTextColor(color);
    }

    private String carDetailText() {
        StringBuilder sb = new StringBuilder();
        sb.append(Obd.connected ? "Verbunden mit " + Obd.adapterName : Obd.state).append("\n\n");
        String[] unit = {" U/min", " km/h", " °C", " V", " %", " bar", " %", " °C", " °C", " °C"};
        for (int k = 0; k < Obd.LABEL.length; k++) {
            if (!Obd.fresh(k)) continue;
            float v = Obd.val[k];
            String t = k == Obd.VOLT || k == Obd.BOOST ? String.format(Locale.GERMAN, "%.1f", v) : String.valueOf(Math.round(v));
            sb.append(Obd.LABEL[k]).append(":  ").append(t).append(unit[k]).append("\n");
        }
        if (Obd.dtcResult != null) sb.append("\nFehlercodes Motor:  ").append(Obd.dtcResult).append("\n");
        return sb.toString().trim();
    }

    /** Opens Android's developer options page directly (skips the build-number taps and the radio's code prompt). */
    /** True once Android's developer options are unlocked (the hidden setting is then 1). */
    private boolean devOptionsOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) { return false; }
    }

    private boolean startAny(Intent... tries) {
        for (Intent i : tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (Exception ignored) { }
        }
        return false;
    }

    /**
     * On the Ducato (NWD) the developer options are locked until the build number is tapped 7x, and the
     * launcher can't flip the setting itself (no WRITE_SECURE_SETTINGS without adb). So: if they are already
     * on, open them; otherwise open "About device" and explain the 7 taps.
     */
    private void openDevOptions() {
        if (devOptionsOn() && startAny(
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"),
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) return;
        showDevHelp();
    }

    private void showDevHelp() {
        final LinearLayout content = overlay("Entwickleroptionen");
        TextView t = text("", 24, WHITE, false);
        t.setSingleLine(false);
        t.setMaxLines(20);
        t.setLineSpacing(px(8), 1f);
        t.setText(devOptionsOn()
                ? "Die Entwickleroptionen sind schon frei. Dieses Radio hat dafür aber keine eigene Seite. Öffne sie über Einstellungen und tippe oben auf die Suche „Entwickleroptionen“."
                : "Dieses Radio gibt die Entwickleroptionen nicht per Knopf frei. Du schaltest sie wie bei jedem Android-Gerät von Hand frei:\n\n"
                + "1. Unten auf GERÄTEINFO ÖFFNEN tippen.\n"
                + "2. Ganz nach unten zu „Build-Nummer“ gehen.\n"
                + "3. Sieben Mal schnell auf „Build-Nummer“ tippen, bis „Sie sind jetzt Entwickler“ erscheint. Wird ein Code verlangt, ist es der PIN/das Muster des Radios, meist keiner.\n"
                + "4. Zurück. Die Entwickleroptionen sind jetzt in den Einstellungen.\n\n"
                + "Danach brauchst du sie für ZFix eigentlich nicht. Launcher, DAB und Tasten laufen ohne.");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = px(28);
        content.addView(t, tl);
        TextView go = bigButton(devOptionsOn() ? "EINSTELLUNGEN ÖFFNEN" : "GERÄTEINFO ÖFFNEN", 28, 110, content);
        go.setGravity(Gravity.CENTER);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeOverlay(content);
                startAny(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
            }
        });
        if (!devOptionsOn()) {
            TextView s2 = bigButton("ODER: WERKSMENÜ (CODE 1617)", 24, 92, content);
            s2.setGravity(Gravity.CENTER);
            s2.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeOverlay(content);
                    startAny(new Intent().setClassName("com.nwd.factory.setting", "com.nwd.factory.setting.FactorySettingActivity"),
                            new Intent().setClassName("com.nwd.factory.setting", "com.nwd.factory.setting.LoginActivity"));
                }
            });
        }
    }

    /** The radio maker's own setting screens (NWD K4811 in the Ducato), opened directly without their menus. */
    private static final String[][] RADIO_TOOLS = {
            {"CAN-Box: Auto und Protokoll wählen", "com.nwd.can.setting", "com.nwd.can.setting.ui.app.canfactory.CanFactoryActivity"},
            {"CAN-Einstellungen", "com.nwd.can.setting", "com.nwd.can.setting.ui.MainActivity"},
            {"Lenkradtasten lernen", "com.nwd.toolallinone.app", "com.nwd.wheel.learning.MainActivity"},
            {"Fahrzeuginfo der CAN-Box", "com.nwd.mycar", "com.nwd.mycar.MainActivity"},
            {"360°- und Rückfahrkamera", "com.ivicar.avm", "com.ivicar.modules.main.view.MainNowadaActivity"},
            {"Werkseinstellungen", "com.nwd.factory.setting", "com.nwd.factory.setting.FactorySettingActivity"},
            {"Entwickleroptionen (Android)", "com.android.settings", null},
            {"EngineerMode (Unisoc-Diagnose)", "com.sprd.engineermode", "com.sprd.engineermode.EngineerModeActivity"},
    };

    private void showRadioTools() {
        final LinearLayout content = overlay("Radio-Einstellungen");
        int shown = 0;
        for (final String[] t : RADIO_TOOLS) {
            if (!isInstalled(t[1])) continue;
            shown++;
            TextView b = bigButton(t[0], 24, 84, content);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (t[2] == null) { openDevOptions(); return; }
                    Intent i = new Intent().setClassName(t[1], t[2]);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); }
                    catch (Exception e) {
                        Intent l = getPackageManager().getLaunchIntentForPackage(t[1]);
                        if (l != null) startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }
            });
        }
        if (shown <= 1) content.addView(text("Für dieses Radio sind keine Einstellungen hinterlegt.", 22, DIM, false));
    }

    /** ZFix menu > CAN-Suche: report + vendor apps to Download/ZFix-CAN for examination on the Mac. */
    private void showCanScan() {
        final LinearLayout content = overlay("CAN-Suche");
        final TextView t = text("Suche läuft, das dauert bis zu einer Minute ...", 22, WHITE, false);
        t.setSingleLine(false);
        t.setMaxLines(100000);
        t.setLineSpacing(px(4), 1f);
        content.addView(t, new LinearLayout.LayoutParams(-1, -2));
        new Thread(new Runnable() {
            @Override public void run() {
                final String rep = CanScan.run(MainActivity.this, new CanScan.Progress() {
                    @Override public void step(final String x) {
                        ui.post(new Runnable() { @Override public void run() { t.setText("Suche läuft ...\n" + x); } });
                    }
                });
                ui.post(new Runnable() { @Override public void run() {
                    t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15 * s);
                    t.setText("FERTIG. Bitte den Ordner Download/ZFix-CAN auf einen USB-Stick kopieren und an den Mac geben.\n\n" + rep);
                } });
            }
        }, "zfix-can-scan").start();
    }

    // ---------------------------------------------------------------- vehicle page (like the Uconnect trip computer)

    private TextView[] dashVal, dashLab;
    private TextView dashMsg, dashTripBtn, dashDtcBtn;
    private int dashTrip = Trip.FAHRT;
    private static final int DASH_N = 12;

    /** Full-screen vehicle page: 12 big tiles (consumption, range, trip, temperatures, battery ...). */
    private void showCarDash() {
        final LinearLayout content = overlay("Fahrzeug · " + profile.name);
        Trip.load(this);
        dashMsg = text("", 20, DIM, false);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.bottomMargin = px(12);
        content.addView(dashMsg, ml);
        dashVal = new TextView[DASH_N];
        dashLab = new TextView[DASH_N];
        final int cols = 4;
        LinearLayout row = null;
        for (int k = 0; k < DASH_N; k++) {
            if (k % cols == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, px(128));
                rl.bottomMargin = px(14);
                content.addView(row, rl);
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(px(22), 0, px(12), 0);
            cell.setBackground(card(CARD_FILL, 20));
            dashLab[k] = text("", 15, 0xFF888888, true);
            dashLab[k].setLetterSpacing(0.12f);
            cell.addView(dashLab[k]);
            dashVal[k] = text("–", 40, WHITE, false);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.topMargin = px(10);
            cell.addView(dashVal[k], vl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
            if (k % cols != 0) cl.leftMargin = px(14);
            row.addView(cell, cl);
        }
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, px(92));
        bl.topMargin = px(4);
        bl.bottomMargin = px(24);
        content.addView(btns, bl);
        dashTripBtn = dashButton(btns, "", new Runnable() { @Override public void run() {
            dashTrip = dashTrip == Trip.FAHRT ? Trip.GESAMT : Trip.FAHRT; updateCarDash(); } });
        dashButton(btns, "GESAMT AUF NULL", new Runnable() { @Override public void run() {
            Trip.resetGesamt(Obd.fresh(Obd.FUEL) ? Obd.val[Obd.FUEL] : -1);
            dashTrip = Trip.GESAMT; updateCarDash(); } });
        dashDtcBtn = dashButton(btns, "FEHLERCODES LESEN", new Runnable() { @Override public void run() {
            if (!Obd.connected) Obd.dtcResult = "Erst Zündung an";
            else { Obd.dtcResult = "wird gelesen"; Obd.wantDtc = true; }
            updateCarDash(); } });
        dashButton(btns, "OBD-ADAPTER", new Runnable() { @Override public void run() {
            closeOverlay(content); showCarPanel(); } });
        ((View) content.getTag()).addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { dashVal = null; }
        });
        updateCarDash();
    }

    private TextView dashButton(LinearLayout parent, String label, final Runnable r) {
        TextView b = text(label, 20, WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(10), 0, px(10), 0);
        b.setBackground(card(CARD_FILL, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = px(14);
        parent.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        return b;
    }

    private void dash(int k, String label, String value, int color) {
        dashLab[k].setText(label);
        dashVal[k].setText(value == null ? "–" : value);
        dashVal[k].setTextColor(value == null ? DIM : color);
    }

    private String obdNum(int k, String fmt) {
        return Obd.fresh(k) ? String.format(Locale.GERMAN, fmt, Obd.val[k]) : null;
    }

    private void updateCarDash() {
        if (dashVal == null) return;
        boolean on = Obd.connected;
        float tank = Obd.fresh(Obd.FUEL) ? Obd.val[Obd.FUEL] : -1;
        float kmh = Obd.fresh(Obd.SPEED) ? Obd.val[Obd.SPEED] : Math.max(0, currentSpeed);
        int t = dashTrip;
        dashMsg.setText(on ? "Verbunden mit " + Obd.adapterName + "   ·   Werte ohne Angabe liefert das Auto nicht"
                : ("aus".equals(profile.obd) ? "Fahrzeugdaten sind aus · unten OBD-ADAPTER antippen" : Obd.state)
                  + "   ·   Fahrtwerte vom letzten Mal");
        float avg = Trip.average(t, tank);
        int range = Trip.range(tank);
        dash(0, "VERBRAUCH JETZT", on ? Trip.now(kmh) : null, WHITE);
        dash(1, t == Trip.FAHRT ? "Ø VERBRAUCH FAHRT" : "Ø VERBRAUCH GESAMT",
                avg > 0 ? String.format(Locale.GERMAN, "%.1f l/100", avg) : null, WHITE);
        dash(2, "REICHWEITE", range >= 0 ? range + " km" : null, range >= 0 && range < 60 ? RED : WHITE);
        dash(3, "TANK", tank >= 0 ? Math.round(tank) + " %" : null, tank >= 0 && tank < 12 ? RED : WHITE);
        double km = Trip.km[t];
        dash(4, t == Trip.FAHRT ? "STRECKE FAHRT" : "STRECKE GESAMT",
                String.format(Locale.GERMAN, km < 100 ? "%.1f km" : "%.0f km", km), WHITE);
        long s = Math.round(Trip.sec[t]);
        dash(5, "FAHRZEIT", String.format(Locale.GERMAN, "%d:%02d h", s / 3600, (s / 60) % 60), WHITE);
        dash(6, "Ø TEMPO", Trip.sec[t] > 60 ? Math.round(km / (Trip.sec[t] / 3600)) + " km/h" : null, WHITE);
        dash(7, "BATTERIE", obdNum(Obd.VOLT, "%.1f V"), Obd.fresh(Obd.VOLT) && Obd.val[Obd.VOLT] < 11.8f ? RED : WHITE);
        dash(8, "KÜHLWASSER", obdNum(Obd.COOLANT, "%.0f °C"),
                Obd.fresh(Obd.COOLANT) && Obd.val[Obd.COOLANT] >= 108 ? RED : WHITE);
        if (Obd.fresh(Obd.OIL)) dash(9, "ÖLTEMPERATUR", obdNum(Obd.OIL, "%.0f °C"), WHITE);
        else dash(9, "ANSAUGLUFT", obdNum(Obd.INTAKE, "%.0f °C"), WHITE);
        dash(10, "AUSSEN", obdNum(Obd.AMBIENT, "%.0f °C"), WHITE);
        if (Obd.fresh(Obd.BOOST)) dash(11, "LADEDRUCK", obdNum(Obd.BOOST, "%.1f bar"), WHITE);
        else dash(11, "DREHZAHL", Obd.fresh(Obd.RPM) ? String.valueOf(Math.round(Obd.val[Obd.RPM] / 10) * 10) : null, WHITE);
        dashTripBtn.setText(t == Trip.FAHRT ? "ANZEIGE: DIESE FAHRT" : "ANZEIGE: GESAMT");
        String dtc = Obd.dtcResult;
        dashDtcBtn.setText(dtc == null ? "FEHLERCODES LESEN" : dtc.toUpperCase(Locale.GERMAN));
        dashDtcBtn.setTextColor(dtc != null && dtc.matches(".*[PCBU][0-9].*") ? RED : WHITE);
    }

    /** "Fahrzeug" panel: live values, fault codes and the choice of OBD adapter. */
    private void showCarPanel() {
        final LinearLayout content = overlay("Fahrzeug · " + profile.name);
        carDetail = text("", 22, WHITE, false);
        carDetail.setSingleLine(false);
        carDetail.setMaxLines(20);
        carDetail.setLineSpacing(px(6), 1f);
        carDetail.setText(carDetailText());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.bottomMargin = px(24);
        content.addView(carDetail, dl);
        ((View) content.getTag()).addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { carDetail = null; }
        });
        List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Fehlercodes Motor lesen");
        acts.add(new Runnable() { @Override public void run() {
            if (!Obd.connected) { Obd.dtcResult = "Erst OBD-Adapter verbinden (Zündung an)"; }
            else { Obd.dtcResult = "wird gelesen"; Obd.wantDtc = true; }
            if (carDetail != null) carDetail.setText(carDetailText());
        } });
        for (final android.bluetooth.BluetoothDevice d : Obd.pairedAdapters()) {
            String n;
            try { n = d.getName(); } catch (SecurityException e) { continue; }
            if (n == null) n = d.getAddress();
            final String name = n;
            labels.add("Bluetooth-Adapter: " + name + (Obd.looksLikeObd(name) ? "" : "  (kein OBD-Name)"));
            acts.add(new Runnable() { @Override public void run() {
                prefs.edit().putString("p_obd", "bt").putString("obd_bt", d.getAddress()).apply();
                applyObdChoice();
            } });
        }
        labels.add("Bluetooth-Adapter automatisch suchen");
        acts.add(new Runnable() { @Override public void run() {
            prefs.edit().putString("p_obd", "bt").remove("obd_bt").apply(); applyObdChoice(); } });
        labels.add("WLAN-Adapter (" + profile.obdHost + ")");
        acts.add(new Runnable() { @Override public void run() {
            prefs.edit().putString("p_obd", "wlan").apply(); applyObdChoice(); } });
        labels.add("Fahrzeugdaten ausschalten");
        acts.add(new Runnable() { @Override public void run() {
            prefs.edit().putString("p_obd", "aus").apply(); applyObdChoice(); } });
        labels.add("Bluetooth-Einstellungen (Adapter koppeln, PIN meist 1234)");
        acts.add(new Runnable() { @Override public void run() {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } });
        for (int n = 0; n < labels.size(); n++) {
            final Runnable r = acts.get(n);
            TextView b = text(labels.get(n), 22, WHITE, true);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(px(28), 0, px(28), 0);
            b.setBackground(card(CARD_FILL, 20));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(76));
            lp.bottomMargin = px(14);
            content.addView(b, lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { r.run(); }
            });
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"}, 2);
        }
    }

    private void applyObdChoice() {
        boolean hadBar = showCarBar();
        profile = Profile.load(this);
        Obd.stop();
        if (hadBar != showCarBar()) { recreate(); return; }
        startObd();
        updateCarBar();
        if (carDetail != null) carDetail.setText(carDetailText());
    }

    // ---------------------------------------------------------------- media ("Jetzt läuft")

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { pickController(list); }
            };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { showMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { showMedia(); }
        @Override public void onSessionDestroyed() { controller = null; showMedia(); }
    };

    private void startMedia() {
        ComponentName cn = new ComponentName(this, MediaListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, ui);
            pickController(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            controller = null;
            nowLabel.setText("JETZT LÄUFT");
            nowTitle.setText("Kein Zugriff");
            nowSub.setText("Tippen zum Erlauben");
        }
    }

    private void stopMedia() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        if (controller != null) controller.unregisterCallback(mediaCallback);
    }

    private void pickController(List<MediaController> list) {
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = null;
        if (list != null) {
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { controller = mc; break; }
            }
            if (controller == null && !list.isEmpty()) controller = list.get(0);
        }
        if (controller != null) controller.registerCallback(mediaCallback, ui);
        showMedia();
    }

    private void showMedia() {
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        int saved = prefs.getInt("source", -1);
        if (playing) {
            int t = tileForPackage(controller.getPackageName());
            highlight(t);
            if (t >= 0 && t != saved) prefs.edit().putInt("source", t).apply();
        } else {
            highlight(saved);
        }
        // CarLink, Spotify, Bluetooth etc. publish titles; ZLink and the FM radio do not
        if (controller != null && (playing || saved < 0 || tileForPackage(controller.getPackageName()) == saved)) {
            showController(playing);
            return;
        }
        playBtn.setImageResource(R.drawable.ic_play);
        boolean dabBox = saved == TILE_NOXON && isDabApp(dabPkg);
        String[] note = notificationText(saved == TILE_PHONE ? "zlink" : dabBox ? "dab" : saved >= TILE_NOXON ? "radio" : null);
        if (dabBox) {
            nowLabel.setText("DAB+");
            nowTitle.setText(note != null ? note[0] : "DAB+ Radio");
            nowSub.setText(note != null ? note[1] : "DAB+ Box");
        } else if (saved == TILE_NOXON) {
            nowLabel.setText("DAB+ · NOXON");
            nowTitle.setText(note != null ? note[0] : noxonText());
            nowSub.setText(note != null ? note[1] : "Noxon Road 2");
        } else if (saved == 3) {
            nowLabel.setText("RADIO · UKW");
            nowTitle.setText(note != null ? note[0] : "UKW-Radio");
            nowSub.setText(note != null ? note[1] : "");
        } else if (saved == TILE_PHONE && note != null) {
            nowLabel.setText("ANDROID AUTO");
            nowTitle.setText(note[0]);
            nowSub.setText(note[1]);
        } else if (controller != null) {
            showController(false);
        } else {
            nowLabel.setText("JETZT LÄUFT");
            nowTitle.setText("Nichts");
            nowSub.setText("Radio oder Musik starten");
        }
    }

    private void showController(boolean playing) {
        String app;
        try {
            app = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(controller.getPackageName(), 0)).toString();
        } catch (Exception e) { app = controller.getPackageName(); }
        nowLabel.setText(app.toUpperCase(Locale.GERMAN) + (playing ? " · LÄUFT" : " · PAUSE"));
        MediaMetadata m = controller.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null && m != null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        nowTitle.setText(title == null || title.isEmpty() ? app : title);
        nowSub.setText(artist == null ? "" : artist);
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /** Title/text of a notification from a package matching the keyword, if that app posts one. */
    private String[] notificationText(String keyword) {
        if (keyword == null) return null;
        for (android.service.notification.StatusBarNotification sbn : MediaListener.current()) {
            if (!sbn.getPackageName().toLowerCase(Locale.ROOT).contains(keyword)) continue;
            Bundle e = sbn.getNotification().extras;
            CharSequence t = e.getCharSequence(android.app.Notification.EXTRA_TITLE);
            CharSequence x = e.getCharSequence(android.app.Notification.EXTRA_TEXT);
            // ZFix Radio's background note for the voltage reader is not music
            if ((t + " " + x).toLowerCase(Locale.ROOT).contains("spannung")) continue;
            if (t != null && t.length() > 0) return new String[]{t.toString(), x == null ? "" : x.toString()};
        }
        return null;
    }

    private void mediaAction(int action) {
        if (controller == null) { openNowPlaying(); return; }
        MediaController.TransportControls tc = controller.getTransportControls();
        PlaybackState st = controller.getPlaybackState();
        if (action == 0) tc.skipToPrevious();
        else if (action == 2) tc.skipToNext();
        else if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
        else tc.play();
    }

    private void openNowPlaying() {
        if (controller != null) { launch(controller.getPackageName()); return; }
        try {
            msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            int saved = prefs.getInt("source", -1);
            String pkg = saved >= 0 ? tilePackage(saved) : tilePackage(3);
            if (saved == TILE_NOXON || pkg == null) pkg = tilePackage(3);
            if (pkg != null) launch(pkg);
        } catch (SecurityException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---------------------------------------------------------------- speed + speed limit

    private boolean locPermAsked;

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // ask once per start, never loop (a denied dialog would come back on every onResume)
            if (!locPermAsked) { locPermAsked = true; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1); }
            speedUnit.setText(gpsState());
            return;
        }
        // the GPS request lives in GpsService and is made only once; here we just listen
        GpsService.client = gpsClient;
        if (!GpsService.running) {
            try { startForegroundService(new Intent(this, GpsService.class)); } catch (Exception ignored) { }
        }
        speedUnit.setText(gpsState());
    }

    private long lastNmeaFix;

    private final GpsService.Client gpsClient = new GpsService.Client() {
        @Override public void onFix(Location l) { onLocation(l); }
        @Override public void onNmeaSpeed(int kmh) {
            if (System.currentTimeMillis() - lastFixTime < 3000 && lastNmeaFix < lastFixTime) return;   // real fixes are arriving
            lastNmeaFix = System.currentTimeMillis();
            lastFixTime = lastNmeaFix;
            if (Obd.fresh(Obd.SPEED)) return;   // the car's own speed is better
            currentSpeed = kmh < 3 ? 0 : kmh;
            showSpeed(String.valueOf(currentSpeed));
            speedUnit.setText("km/h · aktuell");
            updateSpeedColor();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (GpsService.client == gpsClient) GpsService.client = null;
        if (Obd.listener == obdListener) Obd.listener = null;
        Obd.stop();
        hideMapCorners();
        if (sounds != null) sounds.release();
    }

    /** Why there is no speed: shown under the "–" so a driver can tell. */
    private String gpsState() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "Kein GPS-Zugriff";
        try { if (!lm.isLocationEnabled()) return "Standort aus"; } catch (Exception ignored) { }
        try { if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "GPS aus"; } catch (Exception ignored) { }
        String sats = GpsService.satsSeen >= 0 ? " · " + Math.max(GpsService.satsUsed, 0) + "/" + GpsService.satsSeen + " Sat." : "";
        return (lastFixTime == 0 ? "GPS sucht Signal" : "GPS-Signal weg") + sats;
    }

    private void onLocation(Location l) {
        // ignore stale or coarse fixes (fused/network) for the speed, but still use them for the limit
        // GPS and passive deliver the same fix twice; the copy would read as 0 m in 0 s
        if (prevFix != null && l.getElapsedRealtimeNanos() == prevFix.getElapsedRealtimeNanos()) return;
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (!gps && System.currentTimeMillis() - lastGpsTime < 5000) return;
        if (gps) lastGpsTime = System.currentTimeMillis();
        lastFixTime = System.currentTimeMillis();
        float mps = -1;
        if (l.hasAccuracy() && l.getAccuracy() > 50) mps = -1;            // too coarse to tell
        else if (l.hasSpeed() && l.getSpeed() > 0) mps = l.getSpeed();
        else if (prevFix != null) {
            // many head-unit GPS chips never fill in the speed: derive it from the last two fixes
            double dt = (l.getElapsedRealtimeNanos() - prevFix.getElapsedRealtimeNanos()) / 1e9;
            if (dt > 0.3 && dt < 5) mps = (float) (l.distanceTo(prevFix) / dt);
            else if (l.hasSpeed()) mps = 0;
        } else if (l.hasSpeed()) mps = 0;
        if (!(l.hasAccuracy() && l.getAccuracy() > 50)) prevFix = l;
        if (mps >= 0) { smooth = smooth < 0 ? mps : smooth * 0.5f + mps * 0.5f; mps = smooth; }
        currentSpeed = mps < 0 ? -1 : Math.round(mps * 3.6f);
        if (currentSpeed >= 0 && currentSpeed < 3) currentSpeed = 0;
        if (currentSpeed >= 0) { Trip.gpsKmh = currentSpeed; Trip.gpsAt = System.currentTimeMillis(); }
        if (!Obd.fresh(Obd.SPEED)) {   // with an OBD adapter the car's own speed is shown, GPS only finds the limit
            speedUnit.setText("km/h · aktuell");
            showSpeed(currentSpeed < 0 ? "–" : String.valueOf(currentSpeed));
            updateSpeedColor();
        }

        long now = System.currentTimeMillis();
        if (now - lastPrefetch > 10 * 60 * 1000) { lastPrefetch = now; limits.prefetchAround(l.getLatitude(), l.getLongitude()); }
        boolean moved = lastQueryLoc == null || l.distanceTo(lastQueryLoc) > 40;
        boolean due = now - lastQueryTime > (moved ? 8000 : 60000);
        if (due && querying.compareAndSet(false, true)) {
            lastQueryTime = now;
            lastQueryLoc = l;
            final double lat = l.getLatitude(), lon = l.getLongitude();
            new Thread(new Runnable() {
                @Override public void run() {
                    final int limit = limits.lookup(lat, lon);
                    querying.set(false);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            int lim = limit == -2 ? -1 : limit;   // tile not downloaded yet: show unknown, never a stale limit
                            boolean est = lim >= LimitCache.ESTIMATE;
                            if (est) lim -= LimitCache.ESTIMATE;
                            if (lim > 0 && currentLimit > 0 && lim < currentLimit) warned = false;   // e.g. into a 30 zone: warn again
                            currentLimit = lim; sign.setLimit(lim, est); updateSpeedColor();
                        }
                    });
                }
            }).start();
        }
    }

    private static final int SPEED_SIZE = 132;

    /** Three-digit speeds get a smaller font so they do not run into the limit sign. */
    private void showSpeed(String t) {
        speedText.setText(t);
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(t.length() > 2 ? 104 : SPEED_SIZE));
    }

    private void updateSpeedColor() {
        boolean over = currentLimit > 0 && currentSpeed > currentLimit + 3;
        speedText.setTextColor(over ? 0xFFFF5A5A : WHITE);
        if (over && !warned) {
            warned = true;
            if (sounds != null && warnSound != 0) sounds.play(warnSound, 1f, 1f, 1, 0, 1f);
        } else if (currentSpeed >= 0 && currentSpeed <= currentLimit) {
            warned = false;
        }
    }

    /** OpenStreetMap maxspeed of the nearest road. -1 = unknown, -2 = request failed (keep old value). */
    private int fetchLimit(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            String q = String.format(Locale.ROOT,
                    "[out:json][timeout:6];way(around:30,%.6f,%.6f)[highway][maxspeed];out tags geom;", lat, lon);
            byte[] body = ("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8");
            c = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", "ZFix-Launcher/1.0");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            if (c.getResponseCode() != 200) return -2;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONArray els = new JSONObject(bo.toString("UTF-8")).optJSONArray("elements");
            if (els == null || els.length() == 0) return -1;
            double best = Double.MAX_VALUE;
            int bestLimit = -1;
            for (int i = 0; i < els.length(); i++) {
                JSONObject e = els.getJSONObject(i);
                JSONObject tags = e.optJSONObject("tags");
                if (tags == null) continue;
                int lim = parseMaxspeed(tags.optString("maxspeed"));
                if (lim <= 0) continue;
                double d = distanceToWay(lat, lon, e.optJSONArray("geometry"));
                if (d < best) { best = d; bestLimit = lim; }
            }
            return bestLimit;
        } catch (Exception e) {
            return -2;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int parseMaxspeed(String v) {
        if (v == null) return -1;
        v = v.trim();
        String lv = v.toLowerCase(Locale.ROOT);
        if (lv.endsWith(":urban")) return 50;
        if (lv.equals("ch:rural") || lv.equals("at:rural")) return lv.startsWith("at") ? 100 : 80;
        if (lv.endsWith(":rural")) return 100;
        if (lv.equals("ch:trunk")) return 100;
        if (lv.equals("ch:motorway") || lv.equals("at:motorway")) return lv.startsWith("at") ? 130 : 120;
        if (lv.endsWith(":living_street") || lv.equals("walk")) return 20;
        StringBuilder digits = new StringBuilder();
        for (char ch : v.toCharArray()) {
            if (Character.isDigit(ch)) digits.append(ch);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return -1;
        int n = Integer.parseInt(digits.toString());
        if (lv.contains("mph")) n = Math.round(n * 1.609f);
        return n;
    }

    private static double distanceToWay(double lat, double lon, JSONArray g) {
        if (g == null || g.length() == 0) return 1e9;
        double k = Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        double px0 = 0, py0 = 0;
        for (int i = 0; i < g.length(); i++) {
            JSONObject p = g.optJSONObject(i);
            if (p == null) continue;
            double x = (p.optDouble("lon") - lon) * k, y = p.optDouble("lat") - lat;
            if (i == 0) {
                best = Math.min(best, Math.hypot(x, y));
            } else {
                double dx = x - px0, dy = y - py0, len = dx * dx + dy * dy;
                double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(px0 * dx + py0 * dy) / len));
                best = Math.min(best, Math.hypot(px0 + t * dx, py0 + t * dy));
            }
            px0 = x;
            py0 = y;
        }
        return best;
    }

    /** Round Swiss-style speed limit sign. */
    static class LimitSign extends View {
        private int limit = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        LimitSign(Context c) { super(c); }

        private boolean estimate;

        void setLimit(int l, boolean est) { limit = l; estimate = est; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), r = w / 2f, ring = w * 0.107f;
            boolean known = limit > 0;
            p.setStyle(Paint.Style.FILL);
            // a guessed limit (residential road without a sign in the map) is drawn greyed out
            p.setColor(!known ? 0x33FFFFFF : estimate ? 0xFFBDBDBD : Color.WHITE);
            c.drawCircle(r, r, r - 1, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ring);
            p.setColor(!known ? 0x55FFFFFF : estimate ? 0xFF9E5656 : RED);
            c.drawCircle(r, r, r - ring / 2f - 1, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(!known ? 0xAAFFFFFF : estimate ? 0xFF4A4A4A : 0xFF111111);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            String t = known ? String.valueOf(limit) : "–";
            p.setTextSize(w * (t.length() > 2 ? 0.30f : 0.38f));
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(t, r, r - (fm.ascent + fm.descent) / 2f, p);
        }
    }
}
