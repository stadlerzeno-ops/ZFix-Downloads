package ch.zfix.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * "ZFix Tasten": fixed buttons at the left screen edge (Home, Zurück, Lauter, Leiser) for radios without
 * hardware keys (the K-type unit in the Ducato). Runs as an accessibility service because only that may press
 * "Back" for other apps; its overlay needs no extra permission. Hidden while the ZFix home screen is in front.
 */
public class KeyBar extends AccessibilityService {
    static final String PREF = "tasten";   // "auto" (Ducato on, Agila off), "an", "aus"
    static KeyBar instance;

    private LinearLayout bar;
    private boolean added, onHome;

    static boolean wanted(Context c) {
        SharedPreferences sp = c.getSharedPreferences("zfix", Context.MODE_PRIVATE);
        String v = sp.getString(PREF, "auto");
        if ("an".equals(v)) return true;
        if ("aus".equals(v)) return false;
        return Profile.load(c).ducato();
    }

    @Override protected void onServiceConnected() {
        instance = this;
        refresh();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return;
        boolean home = pkg.equals(getPackageName()) && e.getClassName() != null
                && e.getClassName().toString().endsWith("MainActivity");
        if (home != onHome) { onHome = home; refresh(); }
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        remove();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** Called by the ZFix menu after switching the bar on/off or the car. */
    void refresh() {
        if (wanted(this) && !onHome) show(); else remove();
    }

    private void show() {
        if (added) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        float s = w / 1280f;
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setBackgroundColor(0xF00B0B0C);
        int pad = Math.round(10 * s);
        bar.setPadding(pad, pad, pad, pad);
        add(R.drawable.ic_home, s, new Runnable() { @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); } });
        add(R.drawable.ic_back, s, new Runnable() { @Override public void run() { performGlobalAction(GLOBAL_ACTION_BACK); } });
        add(R.drawable.ic_vol_up, s, new Runnable() { @Override public void run() { volume(AudioManager.ADJUST_RAISE); } });
        add(R.drawable.ic_vol_down, s, new Runnable() { @Override public void run() { volume(AudioManager.ADJUST_LOWER); } });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(92 * s), WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.TOP;
        try { wm.addView(bar, lp); added = true; } catch (Exception ignored) { }
    }

    private void add(int icon, float s, final Runnable action) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        int p = Math.round(16 * s);
        b.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(18 * s);
        g.setStroke(Math.max(1, Math.round(s)), 0x1AFFFFFF);
        b.setBackground(g);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = Math.round(6 * s);
        lp.bottomMargin = Math.round(6 * s);
        bar.addView(b, lp);
    }

    private void volume(int dir) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
    }

    private void remove() {
        if (!added) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(bar); } catch (Exception ignored) { }
        added = false;
    }
}
