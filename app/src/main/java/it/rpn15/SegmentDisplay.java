package it.rpn15;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;

/** Display LCD con cifre a sette segmenti, disegnato interamente dall'app. */
public class SegmentDisplay extends View {

    private static final int LCD_TOP = Color.rgb(0xC6, 0xCE, 0xB2);
    private static final int LCD_BOT = Color.rgb(0xA7, 0xB1, 0x92);
    private static final int INK = Color.rgb(0x1B, 0x24, 0x19);

    // segmenti: a=1 b=2 c=4 d=8 e=16 f=32 g=64
    private static int seg(char ch) {
        switch (ch) {
            case '0': return 63;
            case '1': return 6;
            case '2': return 91;
            case '3': return 79;
            case '4': return 102;
            case '5': return 109;
            case '6': return 125;
            case '7': return 7;
            case '8': return 127;
            case '9': return 111;
            case 'A': case 'a': return 119;
            case 'b': case 'B': return 124;
            case 'C': return 57;
            case 'c': return 88;
            case 'd': case 'D': return 94;
            case 'E': case 'e': return 121;
            case 'F': case 'f': return 113;
            case 'G': return 61;
            case 'g': return 111;
            case 'H': return 118;
            case 'h': return 116;
            case 'I': return 6;
            case 'i': return 4;
            case 'J': case 'j': return 30;
            case 'L': case 'l': return 56;
            case 'n': case 'N': return 84;
            case 'o': case 'O': return 92;
            case 'P': case 'p': return 115;
            case 'r': case 'R': return 80;
            case 'S': case 's': return 109;
            case 't': case 'T': return 120;
            case 'U': return 62;
            case 'u': return 28;
            case 'y': case 'Y': return 110;
            case '-': return 64;
            case '_': return 8;
            default: return 0;
        }
    }

    private static final class Cell {
        int segs;
        char mark;   // '.', ',' oppure 0
    }

    private String text = "";
    private String user = "", pending = "", mode = "";
    private boolean fOn, gOn, cOn, prgmOn;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ann = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public SegmentDisplay(Context c) {
        super(c);
        lit.setColor(INK);
        ghost.setColor(Color.argb(20, 0x1B, 0x24, 0x19));
        ann.setColor(INK);
        ann.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    }

    public void setText(String s) {
        if (s == null) s = "";
        if (!s.equals(text)) {
            text = s;
            invalidate();
        }
    }

    public void setAnnunciators(boolean user, boolean f, boolean g, String pending, String mode, boolean complex, boolean prgm) {
        this.user = user ? "USER" : "";
        this.fOn = f;
        this.gOn = g;
        this.pending = pending == null ? "" : pending;
        this.mode = mode == null ? "" : mode;
        this.cOn = complex;
        this.prgmOn = prgm;
        invalidate();
    }

    private static ArrayList<Cell> parse(String s) {
        ArrayList<Cell> cells = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '.' || ch == ',') {
                if (cells.isEmpty() || cells.get(cells.size() - 1).mark != 0) cells.add(new Cell());
                cells.get(cells.size() - 1).mark = ch;
            } else {
                Cell c = new Cell();
                c.segs = seg(ch);
                cells.add(c);
            }
        }
        return cells;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h * 0.06f;

        rect.set(0, 0, w, h);
        bg.setShader(new LinearGradient(0, 0, 0, h, LCD_TOP, LCD_BOT, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, bg);
        // ombra interna in alto: il vetro sembra incassato
        shade.setShader(new LinearGradient(0, 0, 0, h * 0.16f, Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, shade);

        ArrayList<Cell> cells = parse(text);
        int n = Math.max(12, cells.size());
        float padX = h * 0.14f;
        float cellW = (w - 2 * padX) / n;
        float digitH = h * 0.54f;
        float top = h * 0.12f;
        float glyphW = Math.min(cellW * 0.66f, digitH * 0.52f);
        float t = glyphW * 0.18f;
        float skew = 0.09f;
        int offset = n - cells.size();

        for (int i = 0; i < n; i++) {
            float x0 = padX + i * cellW + (cellW - glyphW) * 0.4f;
            drawGlyph(canvas, x0, top, glyphW, digitH, t, skew, 127, ghost);
            int ci = i - offset;
            if (ci >= 0) {
                Cell c = cells.get(ci);
                drawGlyph(canvas, x0, top, glyphW, digitH, t, skew, c.segs, lit);
                if (c.mark != 0) drawMark(canvas, x0 + glyphW + (cellW - glyphW) * 0.42f, top + digitH, t, c.mark, lit);
            }
        }

        // indicatori in basso
        float ah = h * 0.13f;
        ann.setTextSize(ah);
        float base = h * 0.93f;
        canvas.drawText(user, w * 0.04f, base, ann);
        if (fOn) canvas.drawText("f", w * 0.17f, base, ann);
        if (gOn) canvas.drawText("g", w * 0.22f, base, ann);
        canvas.drawText(pending, w * 0.29f, base, ann);
        canvas.drawText(mode, w * 0.62f, base, ann);
        if (cOn) canvas.drawText("C", w * 0.76f, base, ann);
        if (prgmOn) canvas.drawText("PRGM", w * 0.84f, base, ann);
    }

    private void moveTo(float ox, float oy, float x, float y, float H, float skew, boolean first) {
        float px = ox + x + (H - y) * skew, py = oy + y;
        if (first) path.moveTo(px, py); else path.lineTo(px, py);
    }

    private void hSeg(Canvas c, float ox, float oy, float x1, float x2, float y, float t, float H, float skew, Paint p) {
        float h2 = t / 2, gap = t * 0.15f;
        x1 += gap;
        x2 -= gap;
        path.reset();
        moveTo(ox, oy, x1, y, H, skew, true);
        moveTo(ox, oy, x1 + h2, y - h2, H, skew, false);
        moveTo(ox, oy, x2 - h2, y - h2, H, skew, false);
        moveTo(ox, oy, x2, y, H, skew, false);
        moveTo(ox, oy, x2 - h2, y + h2, H, skew, false);
        moveTo(ox, oy, x1 + h2, y + h2, H, skew, false);
        path.close();
        c.drawPath(path, p);
    }

    private void vSeg(Canvas c, float ox, float oy, float x, float y1, float y2, float t, float H, float skew, Paint p) {
        float h2 = t / 2, gap = t * 0.15f;
        y1 += gap;
        y2 -= gap;
        path.reset();
        moveTo(ox, oy, x, y1, H, skew, true);
        moveTo(ox, oy, x + h2, y1 + h2, H, skew, false);
        moveTo(ox, oy, x + h2, y2 - h2, H, skew, false);
        moveTo(ox, oy, x, y2, H, skew, false);
        moveTo(ox, oy, x - h2, y2 - h2, H, skew, false);
        moveTo(ox, oy, x - h2, y1 + h2, H, skew, false);
        path.close();
        c.drawPath(path, p);
    }

    private void drawGlyph(Canvas c, float ox, float oy, float W, float H, float t, float skew, int s, Paint p) {
        float l = t / 2, rr = W - t / 2, tp = t / 2, mid = H / 2, bt = H - t / 2;
        if ((s & 1) != 0) hSeg(c, ox, oy, l, rr, tp, t, H, skew, p);
        if ((s & 2) != 0) vSeg(c, ox, oy, rr, tp, mid, t, H, skew, p);
        if ((s & 4) != 0) vSeg(c, ox, oy, rr, mid, bt, t, H, skew, p);
        if ((s & 8) != 0) hSeg(c, ox, oy, l, rr, bt, t, H, skew, p);
        if ((s & 16) != 0) vSeg(c, ox, oy, l, mid, bt, t, H, skew, p);
        if ((s & 32) != 0) vSeg(c, ox, oy, l, tp, mid, t, H, skew, p);
        if ((s & 64) != 0) hSeg(c, ox, oy, l, rr, mid, t, H, skew, p);
    }

    private void drawMark(Canvas c, float x, float yBottom, float t, char mark, Paint p) {
        float rad = t * 0.62f;
        float cy = yBottom - rad;
        c.drawCircle(x, cy, rad, p);
        if (mark == ',') {
            path.reset();
            path.moveTo(x + rad * 0.2f, cy);
            path.lineTo(x + rad, cy + rad * 0.4f);
            path.lineTo(x - rad * 0.9f, cy + rad * 3.0f);
            path.lineTo(x - rad * 0.4f, cy + rad * 0.6f);
            path.close();
            c.drawPath(path, p);
        }
    }
}
