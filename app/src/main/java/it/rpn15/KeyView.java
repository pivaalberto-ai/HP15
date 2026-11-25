package it.rpn15;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Tasto in rilievo: etichetta f sopra, funzione primaria sulla faccia, funzione g sul bordo frontale. */
public class KeyView extends View {

    public static final int F_COL = Color.rgb(0xFF, 0x9F, 0x43);
    public static final int G_COL = Color.rgb(0x5E, 0xE0, 0x9A);

    private final int code;
    private final String primary, fLabel, gLabel;
    private final float labelH;
    private final Runnable onPress;
    private boolean down;

    private final int capTop, capBot, lipCol, textCol;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public KeyView(Context c, int code, float labelHeightPx, Runnable onPress) {
        super(c);
        this.code = code;
        this.primary = Calc.label(code, 0);
        this.fLabel = Calc.label(code, 1);
        this.gLabel = Calc.label(code, 2);
        this.labelH = labelHeightPx;
        this.onPress = onPress;
        boolean num = primary.length() == 1 && (Character.isDigit(primary.charAt(0)) || primary.equals("."));
        if (code == 42) {
            capTop = Color.rgb(0xFF, 0xB8, 0x72); capBot = Color.rgb(0xE8, 0x87, 0x2C);
            lipCol = Color.rgb(0xB8, 0x64, 0x1A); textCol = Color.rgb(0x22, 0x18, 0x10);
        } else if (code == 43) {
            capTop = Color.rgb(0x8E, 0xEE, 0xBC); capBot = Color.rgb(0x45, 0xC8, 0x84);
            lipCol = Color.rgb(0x2A, 0x93, 0x5D); textCol = Color.rgb(0x10, 0x22, 0x18);
        } else if (num) {
            capTop = Color.rgb(0x5E, 0x64, 0x6E); capBot = Color.rgb(0x40, 0x45, 0x4D);
            lipCol = Color.rgb(0x26, 0x29, 0x2E); textCol = Color.rgb(0xF4, 0xF4, 0xF0);
        } else {
            capTop = Color.rgb(0x48, 0x4D, 0x55); capBot = Color.rgb(0x2E, 0x32, 0x38);
            lipCol = Color.rgb(0x1E, 0x20, 0x24); textCol = Color.rgb(0xF4, 0xF4, 0xF0);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        setContentDescription(primary);
        setHapticFeedbackEnabled(true);
    }

    /** Disegna il testo centrato in orizzontale, rimpicciolendolo se non sta nella larghezza. */
    private void fitText(Canvas c, String s, float cx, float baseline, float maxW, float size, int color) {
        if (s.isEmpty()) return;
        text.setTextSize(size);
        float tw = text.measureText(s);
        if (tw > maxW) text.setTextSize(size * maxW / tw);
        text.setColor(color);
        c.drawText(s, cx, baseline, text);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2;

        // etichetta f stampata sul corpo, sopra il tasto
        fitText(c, fLabel, cx, labelH * 0.78f, w * 0.98f, labelH * 0.78f, F_COL);

        float sx = w * 0.07f, ex = w * 0.93f;
        float shadow = Math.max(3f, h * 0.035f);
        float topY = labelH + (down ? shadow * 0.7f : 0);
        float botY = h - shadow + (down ? shadow * 0.7f : 0);
        float rad = (ex - sx) * 0.12f;

        // ombra sotto il tasto
        paint.setShader(null);
        paint.setColor(Color.argb(down ? 90 : 160, 0, 0, 0));
        r.set(sx, labelH + shadow, ex, h);
        c.drawRoundRect(r, rad, rad, paint);

        // bordo frontale (più scuro), poi la faccia superiore
        float capH = botY - topY;
        float lipH = Math.min(capH * 0.34f, labelH * 1.1f);
        paint.setColor(lipCol);
        r.set(sx, topY, ex, botY);
        c.drawRoundRect(r, rad, rad, paint);

        float faceBot = botY - lipH;
        paint.setShader(new LinearGradient(0, topY, 0, faceBot, capTop, capBot, Shader.TileMode.CLAMP));
        r.set(sx + 1, topY, ex - 1, faceBot);
        c.drawRoundRect(r, rad, rad, paint);
        paint.setShader(null);

        // riflesso sul bordo superiore
        paint.setColor(Color.argb(down ? 25 : 55, 255, 255, 255));
        r.set(sx + rad * 0.6f, topY + 1, ex - rad * 0.6f, topY + Math.max(1.5f, capH * 0.025f));
        c.drawRoundRect(r, 2, 2, paint);

        // funzione primaria sulla faccia
        float faceH = faceBot - topY;
        boolean digit = primary.length() == 1 && Character.isDigit(primary.charAt(0));
        float size = Math.min(faceH * (digit ? 0.62f : 0.48f), (ex - sx) * 0.42f);
        if (code == 36) size = Math.min(labelH * 1.25f, (ex - sx) * 0.3f);   // ENTER, tasto alto
        float base = topY + faceH / 2 + size * 0.36f;
        fitText(c, primary, cx, base, (ex - sx) * 0.86f, size, textCol);

        // funzione g sul bordo frontale
        fitText(c, gLabel, cx, botY - lipH * 0.26f, (ex - sx) * 0.9f, lipH * 0.66f,
                (code == 42 || code == 43) ? textCol : G_COL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                if (onPress != null) onPress.run();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
