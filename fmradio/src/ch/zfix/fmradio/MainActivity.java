package ch.zfix.fmradio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nwd.radio.service.data.Frequency;

import java.util.Locale;

/**
 * ZFix Radio (UKW) for the Fiat Ducato NWD head unit. Drives the vendor FM tuner through the
 * exported radio service and shows a clean ZFix screen with big buttons for the parents.
 * No emoji, dark ZFix look, large touch targets.
 */
public class MainActivity extends Activity implements Radio.Listener {

    private static final int BG = 0xFF0B0B0C, WHITE = 0xFFF2F2F2, DIM = 0xFF9A9A9A,
            CARD = 0xFF1A1A1C, CARD_HI = 0xFFF2F2F2, LINE = 0x22FFFFFF, ACCENT = 0xFFD62828;

    private float s;
    private Radio radio;
    private MediaSession media;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView freqText, psText, rdsText, statusText;
    private final TextView[] presetBtn = new TextView[6];
    private final int[] presetFreq = new int[6];
    private int curFreq;
    private boolean connected;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = getResources().getDisplayMetrics().density;
        setContentView(buildUi());
        setupMedia();
        radio = new Radio(this, this);
        radio.bind();
        statusText.setText("Verbinde mit dem Radio…");
    }

    @Override protected void onResume() {
        super.onResume();
        if (radio != null && radio.ready()) { int f = radio.currentFreq(); if (f > 0) showFreq(f, null, -1); refreshPresets(); }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (media != null) { media.release(); media = null; }
        if (radio != null) radio.unbind();
    }

    private int px(float dp) { return Math.round(dp * s); }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(px(28), px(24), px(28), px(24));

        // left: big frequency + station + RDS
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.25f);
        left.setLayoutParams(lp);

        TextView brand = label("ZFIX RADIO", 18, DIM, true);
        brand.setLetterSpacing(0.18f);
        left.addView(brand);

        freqText = label("––.–", 92, WHITE, true);
        freqText.setPadding(0, px(6), 0, 0);
        left.addView(freqText);
        TextView mhz = label("MHz · UKW", 22, DIM, false);
        left.addView(mhz);

        psText = label("", 34, WHITE, true);
        psText.setPadding(0, px(14), 0, 0);
        left.addView(psText);
        rdsText = label("", 20, DIM, false);
        rdsText.setPadding(0, px(4), 0, 0);
        rdsText.setMaxLines(2);
        left.addView(rdsText);

        statusText = label("", 18, DIM, false);
        statusText.setPadding(0, px(18), 0, 0);
        left.addView(statusText);

        root.addView(left);

        // right: controls
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));

        // seek / step row
        LinearLayout tune = new LinearLayout(this);
        tune.setOrientation(LinearLayout.HORIZONTAL);
        tune.addView(ctrl("‹‹", "Suchlauf ab", new Runnable() { public void run() { radio.seek(false); } }, 1f));
        tune.addView(gap());
        tune.addView(ctrl("‹", "", new Runnable() { public void run() { radio.step(false, curFreq); } }, 0.7f));
        tune.addView(gap());
        tune.addView(ctrl("›", "", new Runnable() { public void run() { radio.step(true, curFreq); } }, 0.7f));
        tune.addView(gap());
        tune.addView(ctrl("››", "Suchlauf auf", new Runnable() { public void run() { radio.seek(true); } }, 1f));
        right.addView(tune, rowLp(1.1f));

        right.addView(spacer(px(14)));

        // scan row
        TextView scan = ctrl("SENDER SUCHEN", "", new Runnable() { public void run() {
            statusText.setText("Suche Sender…"); radio.scan();
        } }, 1f);
        right.addView(scan, rowLp(0.7f));

        right.addView(spacer(px(14)));

        // presets grid 3x2
        TextView pTitle = label("Gespeicherte Sender  ·  lange drücken zum Speichern", 16, DIM, false);
        pTitle.setPadding(px(4), 0, 0, px(8));
        right.addView(pTitle);
        for (int r = 0; r < 2; r++) {
            LinearLayout prow = new LinearLayout(this);
            prow.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                presetBtn[idx] = preset(idx);
                if (c > 0) prow.addView(gap());
                prow.addView(presetBtn[idx]);
            }
            right.addView(prow, rowLp(1f));
            if (r == 0) right.addView(spacer(px(12)));
        }

        root.addView(right);
        return root;
    }

    private LinearLayout.LayoutParams rowLp(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, weight);
        return p;
    }

    private View spacer(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(-1, h)); return v; }
    private View gap() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(px(12), -1)); return v; }

    private TextView label(String t, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.getPaint().setFakeBoldText(bold);
        return tv;
    }

    private GradientDrawable card(int fill, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        g.setStroke(px(1), LINE);
        return g;
    }

    /** A big control button with a glyph and an optional caption below. */
    private TextView ctrl(String glyph, String caption, final Runnable action, float weight) {
        final TextView tv = new TextView(this);
        tv.setText(caption.isEmpty() ? glyph : glyph + "\n" + caption);
        tv.setTextColor(WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, caption.isEmpty() ? 40 : 26);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(card(CARD, 20));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -1, weight));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!connected) { statusText.setText("Noch nicht mit dem Radio verbunden"); return; }
                flash(tv); action.run();
            }
        });
        return tv;
    }

    private TextView preset(final int idx) {
        final TextView tv = new TextView(this);
        tv.setText("—");
        tv.setTextColor(DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(card(CARD, 18));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!connected) return;
                if (presetFreq[idx] > 0) { flash(tv); radio.tune(presetFreq[idx], idx); }
            }
        });
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (!connected || curFreq <= 0) return true;
                radio.savePreset(idx);
                presetFreq[idx] = curFreq;
                setPreset(idx, curFreq);
                statusText.setText("Sender auf Platz " + (idx + 1) + " gespeichert");
                return true;
            }
        });
        return tv;
    }

    private void flash(final TextView tv) {
        tv.setBackground(card(CARD_HI, 20));
        final int tc = tv.getCurrentTextColor();
        tv.setTextColor(0xFF0B0B0C);
        ui.postDelayed(new Runnable() { @Override public void run() {
            tv.setBackground(card(CARD, 20)); tv.setTextColor(tc);
        } }, 160);
    }

    // ---------------------------------------------------------------- data

    private String fmt(int freq) {
        if (freq <= 0) return "––.–";
        return String.format(Locale.US, "%.1f", freq / 100.0);
    }

    private void showFreq(int freq, String ps, int prefabIndex) {
        curFreq = freq;
        freqText.setText(fmt(freq));
        if (ps != null) psText.setText(ps.trim());
        if (freq > 0 && connected) statusText.setText("");
        updateMedia();
    }

    private void setPreset(int idx, int freq) {
        presetFreq[idx] = freq;
        presetBtn[idx].setText(freq > 0 ? fmt(freq) : "—");
        presetBtn[idx].setTextColor(freq > 0 ? WHITE : DIM);
    }

    private void refreshPresets() {
        Frequency[] p = radio.presets();
        if (p == null) return;
        for (int i = 0; i < presetBtn.length; i++) setPreset(i, i < p.length && p[i] != null ? p[i].getFrequency() : 0);
    }

    // Radio.Listener (callbacks arrive on a binder thread -> hop to UI)
    @Override public void onConnected(final boolean ok) {
        ui.post(new Runnable() { @Override public void run() {
            connected = ok;
            if (ok) {
                statusText.setText("");
                int f = radio.currentFreq();
                if (f > 0) showFreq(f, null, -1);
                else radio.tune(curFreq > 0 ? curFreq : 9290, -1);  // last or 92.90 to open FM
                refreshPresets();
            } else {
                statusText.setText("Radio-Dienst nicht erreichbar. Ist dies das Ducato-Radio?");
            }
        } });
    }

    @Override public void onFrequency(final int freq, final String ps, final int prefabIndex) {
        ui.post(new Runnable() { @Override public void run() { showFreq(freq, ps, prefabIndex); } });
    }

    @Override public void onScanState(final int state) {
        ui.post(new Runnable() { @Override public void run() {
            statusText.setText(state == 2 ? "Suche Sender…" : "");
            if (state != 2) refreshPresets();
        } });
    }

    @Override public void onRds(final String rt) {
        ui.post(new Runnable() { @Override public void run() { rdsText.setText(rt == null ? "" : rt.trim()); } });
    }

    @Override public void onPresets(final Frequency[] presets) {
        ui.post(new Runnable() { @Override public void run() {
            if (presets == null) return;
            for (int i = 0; i < presetBtn.length; i++) setPreset(i, i < presets.length && presets[i] != null ? presets[i].getFrequency() : 0);
        } });
    }

    // ---------------------------------------------------------------- media session (so the launcher shows now-playing)

    private void setupMedia() {
        try {
            media = new MediaSession(this, "ZFixRadio");
            media.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            media.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { if (radio != null && curFreq > 0) radio.tune(curFreq, -1); }
                @Override public void onSkipToNext() { if (radio != null) radio.seek(true); }
                @Override public void onSkipToPrevious() { if (radio != null) radio.seek(false); }
            });
            media.setActive(true);
            updateMedia();
        } catch (Exception ignored) { }
    }

    private void updateMedia() {
        if (media == null) return;
        try {
            String title = curFreq > 0 ? "UKW " + fmt(curFreq) + " MHz" : "ZFix Radio";
            String sub = psText != null && psText.getText().length() > 0 ? psText.getText().toString() : "ZFix Radio";
            media.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                    .build());
            media.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
        } catch (Exception ignored) { }
    }
}
