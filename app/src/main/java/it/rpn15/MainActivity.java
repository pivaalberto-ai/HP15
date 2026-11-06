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
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    // Palette originale (non riproduce i colori di nessun prodotto commerciale)
    private static final int BODY = Color.rgb(0x1C, 0x1F, 0x24);
    private static final int KEY = Color.rgb(0x33, 0x38, 0x40);
    private static final int KEY_PRESSED = Color.rgb(0x4A, 0x51, 0x5C);
    private static final int KEY_NUM = Color.rgb(0x3E, 0x44, 0x4E);
    private static final int KEY_EDGE = Color.rgb(0x12, 0x14, 0x18);
    private static final int TXT = Color.rgb(0xF2, 0xF2, 0xF0);
    private static final int F_COL = Color.rgb(0xFF, 0x9F, 0x43);
    private static final int G_COL = Color.rgb(0x5E, 0xE0, 0x9A);
    private static final int LCD_BG = Color.rgb(0xB9, 0xC2, 0xA6);
    private static final int LCD_TXT = Color.rgb(0x1A, 0x22, 0x18);

    private final Calc calc = new Calc();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView disp, annF, annG, annPending, annMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("rpn15", Context.MODE_PRIVATE);
        calc.restore(prefs.getString("state", null));
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.edit().putString("state", calc.serialize()).apply();
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

    private GradientDrawable rounded(int color, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    // ------------------------------------------------------------------
    // Costruzione dell'interfaccia
    // ------------------------------------------------------------------
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BODY);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        root.addView(buildDisplay(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.21f));

        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, dp(6)));

        root.addView(buildKeypad(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.79f));
        return root;
    }

    private View buildDisplay() {
        // contenitore centrato largo circa metà schermo, come un display LCD
        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER);
        outer.setWeightSum(1f);

        LinearLayout lcd = new LinearLayout(this);
        lcd.setOrientation(LinearLayout.VERTICAL);
        lcd.setBackground(rounded(LCD_BG, 6, Color.rgb(0x0C, 0x0E, 0x10)));
        lcd.setPadding(dp(14), dp(2), dp(14), dp(2));

        disp = new TextView(this);
        disp.setTextColor(LCD_TXT);
        disp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        disp.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        disp.setMaxLines(1);
        disp.setAutoSizeTextTypeUniformWithConfiguration(12, 40, 1, TypedValue.COMPLEX_UNIT_SP);
        disp.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String val = Double.toString(calc.x);
            if (val.endsWith(".0")) val = val.substring(0, val.length() - 2);
            cm.setPrimaryClip(ClipData.newPlainText("X", val));
            Toast.makeText(this, "Copiato: " + val, Toast.LENGTH_SHORT).show();
            return true;
        });
        lcd.addView(disp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.74f));

        LinearLayout ann = new LinearLayout(this);
        ann.setOrientation(LinearLayout.HORIZONTAL);
        annF = annText();
        annG = annText();
        annPending = annText();
        annMode = annText();
        ann.addView(annF, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        ann.addView(annG, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        ann.addView(annPending, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
        ann.addView(annMode, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
        lcd.addView(ann, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.26f));

        outer.addView(lcd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));
        return outer;
    }

    private TextView annText() {
        TextView t = new TextView(this);
        t.setTextColor(LCD_TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setMaxLines(1);
        return t;
    }

    private View buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.HORIZONTAL);
        int[] cols = {1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        for (int c : cols) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            if (c == 6) {
                col.addView(keyCell(16), cellParams(1));
                col.addView(keyCell(26), cellParams(1));
                col.addView(keyCell(36), cellParams(2));   // ENTER su due righe
            } else {
                for (int r = 1; r <= 4; r++) col.addView(keyCell(r * 10 + c), cellParams(1));
            }
            pad.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return pad;
    }

    private LinearLayout.LayoutParams cellParams(float weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private View keyCell(final int code) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(3), 0, dp(3), dp(3));

        // etichetta f sopra il tasto
        String fl = Calc.label(code, 1);
        TextView fLabel = new TextView(this);
        fLabel.setText(fl);
        fLabel.setTextColor(F_COL);
        fLabel.setGravity(Gravity.CENTER);
        fLabel.setMaxLines(1);
        fLabel.setAutoSizeTextTypeUniformWithConfiguration(6, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        if (!fl.isEmpty() && !Calc.supported(fl)) fLabel.setAlpha(0.35f);
        int labelH = dp(15);
        cell.addView(fLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, labelH));

        // tasto con funzione primaria e funzione g sul frontale
        TextView key = new TextView(this);
        key.setGravity(Gravity.CENTER);
        key.setAllCaps(false);
        key.setTypeface(Typeface.DEFAULT_BOLD);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        key.setIncludeFontPadding(false);

        String pl = Calc.label(code, 0);
        String gl = Calc.label(code, 2);
        int face = KEY;
        int txt = TXT;
        if (code == 42) { face = F_COL; txt = BODY; }
        else if (code == 43) { face = G_COL; txt = BODY; }
        else if (pl.length() == 1 && (Character.isDigit(pl.charAt(0)) || pl.equals("."))) face = KEY_NUM;

        SpannableStringBuilder sb = new SpannableStringBuilder(pl);
        sb.setSpan(new ForegroundColorSpan(txt), 0, pl.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (pl.length() == 1 && Character.isDigit(pl.charAt(0))) {
            sb.setSpan(new RelativeSizeSpan(1.2f), 0, pl.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!gl.isEmpty()) {
            int start = sb.length();
            sb.append("\n").append(gl);
            sb.setSpan(new RelativeSizeSpan(0.62f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(G_COL), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!Calc.supported(gl)) {
                sb.setSpan(new ForegroundColorSpan(Color.argb(90, 0x5E, 0xE0, 0x9A)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        key.setText(sb);

        StateListDrawable bg = new StateListDrawable();
        int pressed = code == 42 || code == 43 ? Color.WHITE : KEY_PRESSED;
        bg.addState(new int[]{android.R.attr.state_pressed}, rounded(pressed, 7, KEY_EDGE));
        bg.addState(new int[]{}, rounded(face, 7, KEY_EDGE));
        key.setBackground(bg);
        key.setClickable(true);
        key.setHapticFeedbackEnabled(true);
        key.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onKey(code);
        });
        cell.addView(key, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return cell;
    }

    // ------------------------------------------------------------------
    // Gestione tasti e aggiornamento display
    // ------------------------------------------------------------------
    private final Runnable refreshRunnable = this::refresh;

    private void onKey(int code) {
        handler.removeCallbacks(refreshRunnable);
        calc.press(code);
        String msg = calc.message;
        if (msg != null) {
            if (msg.equals("MENU")) showMenu();
            else if (msg.startsWith("UNSUPPORTED:")) {
                Toast.makeText(this, msg.substring(12) + ": funzione non ancora disponibile", Toast.LENGTH_SHORT).show();
            }
        }
        refresh();
        if (calc.showMantissa) {
            disp.setText(calc.mantissa());
            handler.postDelayed(refreshRunnable, 1500);
        }
    }

    private void refresh() {
        disp.setText(calc.display());
        annF.setText(calc.shift == 1 ? "f" : "");
        annG.setText(calc.shift == 2 ? "g" : "");
        annPending.setText(calc.pendingLabel());
        annMode.setText(calc.angle == Calc.RAD ? "RAD" : calc.angle == Calc.GRAD ? "GRAD" : "");
        annF.setTextColor(LCD_TXT);
        annG.setTextColor(LCD_TXT);
    }

    private void showMenu() {
        String sep = calc.comma ? "Separatore decimale: virgola → punto" : "Separatore decimale: punto → virgola";
        String[] items = {sep, "Azzera tutto (stack e registri)", "Informazioni"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("RPN-15")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        calc.comma = !calc.comma;
                    } else if (which == 1) {
                        calc.reset();
                    } else {
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("RPN-15")
                                .setMessage("Calcolatrice RPN ispirata alla HP-15C.\n\n"
                                        + "• f / g selezionano le funzioni arancioni (sopra) e verdi (sul tasto).\n"
                                        + "• Stack X, Y, Z, T con LSTx e registri R0–R9, R.0–R.9.\n"
                                        + "• Tieni premuto il display per copiare X.\n"
                                        + "• Le funzioni sbiadite (programmazione, matrici, SOLVE, ∫) non sono ancora disponibili.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    refresh();
                })
                .show();
    }
}
