package it.rpn15;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Palette originale del corpo
    private static final int BODY_TOP = Color.rgb(0x2B, 0x2E, 0x33);
    private static final int BODY_BOT = Color.rgb(0x17, 0x18, 0x1B);
    private static final int BEZEL = Color.rgb(0x0D, 0x0E, 0x10);
    private static final int BEZEL_EDGE = Color.rgb(0x3A, 0x3E, 0x45);
    private static final int ENGRAVE = Color.rgb(0x9A, 0xA0, 0xA8);

    /** Il motore gira su un solo thread di lavoro, così i programmi lunghi non bloccano lo schermo. */
    private final Calc calc = new Calc();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private SegmentDisplay disp;

    /** Copia dello stato da mostrare, preparata dal thread di lavoro. */
    private static final class View_ {
        String display, transientText, message, pending, mode;
        boolean f, g, user, complex, prgm;
        double x;
    }

    private volatile View_ last;
    private long transientUntil;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("rpn15", Context.MODE_PRIVATE);
        setContentView(buildUi());
        final String saved = prefs.getString("state", null);
        worker.execute(() -> {
            calc.restore(saved);
            publish();
        });
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        calc.requestStop();
        worker.execute(() -> prefs.edit().putString("state", calc.serialize()).apply());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------
    // Costruzione dell'interfaccia
    // ------------------------------------------------------------------
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BODY_TOP, BODY_BOT});
        root.setBackground(body);
        root.setPadding(dp(18), dp(10), dp(18), dp(8));

        root.addView(buildDisplayPanel(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.24f));

        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, dp(6)));

        root.addView(buildKeypad(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.76f));
        return root;
    }

    private View buildDisplayPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);

        View left = new View(this);
        panel.addView(left, new LinearLayout.LayoutParams(0, 1, 0.14f));

        // cornice incassata attorno al vetro
        LinearLayout bezel = new LinearLayout(this);
        GradientDrawable bz = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BEZEL, Color.rgb(0x16, 0x17, 0x1A)});
        bz.setCornerRadius(dp(10));
        bz.setStroke(dp(1.5f), BEZEL_EDGE);
        bezel.setBackground(bz);
        bezel.setPadding(dp(10), dp(7), dp(10), dp(7));

        disp = new SegmentDisplay(this);
        disp.setOnLongClickListener(v -> {
            View_ s = last;
            if (s == null) return true;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String val = Double.toString(s.x);
            if (val.endsWith(".0")) val = val.substring(0, val.length() - 2);
            cm.setPrimaryClip(ClipData.newPlainText("X", val));
            Toast.makeText(this, "Copiato: " + val, Toast.LENGTH_SHORT).show();
            return true;
        });
        bezel.addView(disp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(bezel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));

        // nome inciso sul corpo
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER);
        TextView name = new TextView(this);
        name.setText("RPN·15");
        name.setTextColor(ENGRAVE);
        name.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        name.setLetterSpacing(0.12f);
        TextView sub = new TextView(this);
        sub.setText("SCIENTIFIC · PROGRAMMABLE");
        sub.setTextColor(Color.rgb(0x6C, 0x72, 0x7B));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
        sub.setLetterSpacing(0.18f);
        brand.addView(name);
        brand.addView(sub);
        panel.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.24f));
        return panel;
    }

    private View buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.HORIZONTAL);
        float labelH = dp(14);
        int[] cols = {1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        for (int c : cols) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            if (c == 6) {
                col.addView(key(16, labelH), cellParams(1));
                col.addView(key(26, labelH), cellParams(1));
                col.addView(key(36, labelH), cellParams(2));   // ENTER su due righe
            } else {
                for (int r = 1; r <= 4; r++) col.addView(key(r * 10 + c, labelH), cellParams(1));
            }
            pad.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return pad;
    }

    private View key(int code, float labelH) {
        return new KeyView(this, code, labelH, () -> onKey(code));
    }

    private LinearLayout.LayoutParams cellParams(float weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    // ------------------------------------------------------------------
    // Tasti, thread di lavoro e aggiornamento del display
    // ------------------------------------------------------------------
    private void onKey(int code) {
        if (calc.running) {           // qualunque tasto ferma il programma in esecuzione
            calc.requestStop();
            return;
        }
        worker.execute(() -> {
            calc.press(code);
            publish();
        });
    }

    /** Eseguito sul thread di lavoro: copia lo stato e lo passa all'interfaccia. */
    private void publish() {
        View_ s = new View_();
        s.display = calc.display();
        s.transientText = calc.transientText;
        s.message = calc.message;
        s.pending = calc.pendingLabel();
        s.mode = calc.angle == Calc.RAD ? "RAD" : calc.angle == Calc.GRAD ? "GRAD" : "";
        s.f = calc.shift == 1;
        s.g = calc.shift == 2;
        s.user = calc.user;
        s.complex = calc.complex();
        s.prgm = calc.prgm;
        s.x = calc.x();
        calc.transientText = null;
        calc.message = null;
        last = s;
        handler.post(() -> show(s));
    }

    private void show(View_ s) {
        if (s.transientText != null) {
            disp.setText(s.transientText);
            transientUntil = System.currentTimeMillis() + 1300;
        } else {
            transientUntil = 0;
            disp.setText(s.display);
        }
        disp.setAnnunciators(s.user, s.f, s.g, s.pending, s.mode, s.complex, s.prgm);
        if ("MENU".equals(s.message)) showMenu();
    }

    /** Ogni 100 ms: mostra "running" o la pausa di PSE e chiude i messaggi temporanei. */
    private final Runnable ticker = new Runnable() {
        private boolean blink;

        @Override
        public void run() {
            if (calc.running) {
                String p = calc.pauseText;
                if (p != null) disp.setText(p);
                else {
                    blink = !blink;
                    disp.setText(blink ? "running" : "");
                }
            } else if (transientUntil != 0 && System.currentTimeMillis() > transientUntil) {
                transientUntil = 0;
                View_ s = last;
                if (s != null) disp.setText(s.display);
            }
            handler.postDelayed(this, calc.running ? 250 : 100);
        }
    };

    private void showMenu() {
        String sep = calc.comma ? "Separatore decimale: virgola → punto" : "Separatore decimale: punto → virgola";
        String[] items = {sep, "Azzera tutto (stack, registri, programma, matrici)", "Informazioni"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("RPN-15")
                .setItems(items, (d, which) -> {
                    if (which == 2) {
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("RPN-15")
                                .setMessage("Calcolatrice RPN ispirata alla HP-15C.\n\n"
                                        + "• f / g selezionano le funzioni arancioni (sopra) e verdi (sul bordo del tasto).\n"
                                        + "• g P/R entra ed esce dal modo programma.\n"
                                        + "• Qualunque tasto ferma un programma in esecuzione.\n"
                                        + "• Tieni premuto il display per copiare X.\n\n"
                                        + "Le istruzioni complete sono nel manuale PDF.")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }
                    worker.execute(() -> {
                        if (which == 0) calc.comma = !calc.comma;
                        else calc.reset();
                        publish();
                    });
                })
                .show();
    }
}
