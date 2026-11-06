package it.rpn15;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Motore di calcolo RPN in stile HP-15C.
 * Non dipende da Android: si può collaudare su qualunque JVM.
 *
 * I tasti sono identificati dal codice riga/colonna usato dalla HP-15C
 * (riga 1..4, colonna 1..9 e 0 per la decima), es. 36 = ENTER, 47 = "0".
 */
public final class Calc {

    // ------------------------------------------------------------------
    // Tabella dei tasti: codice -> {funzione primaria, funzione f, funzione g}
    // ------------------------------------------------------------------
    private static final Map<Integer, String[]> KEYS = new HashMap<>();

    private static void k(int code, String p, String f, String g) {
        KEYS.put(code, new String[]{p, f, g});
    }

    static {
        k(11, "√x", "A", "x²");
        k(12, "eˣ", "B", "LN");
        k(13, "10ˣ", "C", "LOG");
        k(14, "yˣ", "D", "%");
        k(15, "1/x", "E", "Δ%");
        k(16, "CHS", "MATRIX", "ABS");
        k(17, "7", "FIX", "DEG");
        k(18, "8", "SCI", "RAD");
        k(19, "9", "ENG", "GRD");
        k(10, "÷", "SOLVE", "x≤y");

        k(21, "SST", "LBL", "BST");
        k(22, "GTO", "HYP", "HYP⁻¹");
        k(23, "SIN", "DIM", "SIN⁻¹");
        k(24, "COS", "(i)", "COS⁻¹");
        k(25, "TAN", "I", "TAN⁻¹");
        k(26, "EEX", "RESULT", "π");
        k(27, "4", "x↔", "SF");
        k(28, "5", "DSE", "CF");
        k(29, "6", "ISG", "F?");
        k(20, "×", "∫xy", "x=0");

        k(31, "R/S", "PSE", "P/R");
        k(32, "GSB", "CLΣ", "RTN");
        k(33, "R↓", "CLPRG", "R↑");
        k(34, "x↔y", "CLREG", "RND");
        k(35, "←", "PREFIX", "CLx");
        k(36, "ENTER", "RAN#", "LSTx");
        k(37, "1", "→R", "→P");
        k(38, "2", "→H.MS", "→H");
        k(39, "3", "→RAD", "→DEG");
        k(30, "−", "Re↔Im", "TEST");

        k(41, "ON", "", "");
        k(42, "f", "", "");
        k(43, "g", "", "");
        k(44, "STO", "FRAC", "INT");
        k(45, "RCL", "USER", "MEM");
        k(47, "0", "x!", "x̄");
        k(48, ".", "ŷ,r", "s");
        k(49, "Σ+", "L.R.", "Σ−");
        k(40, "+", "Py,x", "Cy,x");
    }

    /** Funzioni presenti sulla tastiera ma non ancora implementate (programmazione, matrici, complessi...). */
    private static final Set<String> UNSUPPORTED = new HashSet<>(java.util.Arrays.asList(
            "A", "B", "C", "D", "E", "MATRIX", "SOLVE", "x≤y",
            "SST", "LBL", "BST", "GTO", "DIM", "(i)", "I", "RESULT",
            "x↔", "DSE", "ISG", "SF", "CF", "F?", "∫xy", "x=0",
            "R/S", "PSE", "P/R", "GSB", "RTN", "CLPRG", "Re↔Im", "TEST",
            "USER", "MEM"));

    public static String label(int code, int layer) {
        String[] a = KEYS.get(code);
        return a == null ? "" : a[layer];
    }

    public static boolean supported(String op) {
        return op != null && !op.isEmpty() && !UNSUPPORTED.contains(op);
    }

    // ------------------------------------------------------------------
    // Stato
    // ------------------------------------------------------------------
    public static final double MAX = 9.999999999e99;

    public double x, y, z, t, lastX;
    /** R0..R9 = indici 0..9, R.0..R.9 = indici 10..19. R2..R7 sono i registri statistici. */
    public final double[] reg = new double[20];
    private boolean lift = true;

    // inserimento numero
    private boolean entering, inExp, mantNeg, expNeg;
    private final StringBuilder mant = new StringBuilder();
    private String expDigits = "";

    public static final int FIX = 0, SCI = 1, ENG = 2;
    public int fmt = FIX, digits = 4;
    public static final int DEG = 0, RAD = 1, GRAD = 2;
    public int angle = DEG;
    /** true = virgola come separatore decimale (punto per le migliaia). */
    public boolean comma = false;

    /** 0 nessuno, 1 = f, 2 = g */
    public int shift = 0;

    private static final int P_NONE = 0, P_STO = 1, P_RCL = 2, P_FIX = 3, P_SCI = 4, P_ENG = 5, P_HYP = 6, P_AHYP = 7;
    private int pending = P_NONE;
    private char regOp = 0;
    private boolean regDot = false;

    public String error = null;
    /** Messaggio per l'interfaccia: "MENU" oppure "UNSUPPORTED:<funzione>". */
    public String message = null;
    /** true dopo CLEAR PREFIX: l'interfaccia mostra per un attimo la mantissa completa. */
    public boolean showMantissa = false;

    private static final class CalcError extends RuntimeException {
        final int code;
        CalcError(int code) { super(null, null, false, false); this.code = code; }
    }

    // ------------------------------------------------------------------
    // Pressione tasti
    // ------------------------------------------------------------------
    public void press(int code) {
        message = null;
        showMantissa = false;
        if (error != null) {          // qualunque tasto cancella il messaggio d'errore
            error = null;
            shift = 0;
            pending = P_NONE;
            return;
        }
        if (code == 42) { shift = shift == 1 ? 0 : 1; return; }
        if (code == 43) { shift = shift == 2 ? 0 : 2; return; }
        int layer = shift;
        shift = 0;
        try {
            if (pending != P_NONE) {
                if (handlePending(code, layer)) return;
                pending = P_NONE;
                regOp = 0;
                regDot = false;
            }
            exec(label(code, layer));
        } catch (CalcError e) {
            pending = P_NONE;
            error = "Error " + e.code;
        }
    }

    private static int digitOf(int code) {
        switch (code) {
            case 47: return 0;
            case 37: return 1;
            case 38: return 2;
            case 39: return 3;
            case 27: return 4;
            case 28: return 5;
            case 29: return 6;
            case 17: return 7;
            case 18: return 8;
            case 19: return 9;
            default: return -1;
        }
    }

    private boolean handlePending(int code, int layer) {
        int d = digitOf(code);
        switch (pending) {
            case P_FIX:
            case P_SCI:
            case P_ENG:
                if (layer == 0 && d >= 0) {
                    fmt = pending - P_FIX;
                    digits = d;
                    pending = P_NONE;
                    return true;
                }
                return false;
            case P_HYP:
            case P_AHYP:
                if (layer == 0 && (code == 23 || code == 24 || code == 25)) {
                    boolean inv = pending == P_AHYP;
                    pending = P_NONE;
                    hyp(code, inv);
                    return true;
                }
                return false;
            case P_STO:
            case P_RCL:
                if (layer != 0) return false;
                boolean sto = pending == P_STO;
                if (!regDot && regOp == 0 && (code == 40 || code == 30 || code == 20 || code == 10)) {
                    regOp = code == 40 ? '+' : code == 30 ? '-' : code == 20 ? '*' : '/';
                    return true;
                }
                if (code == 48 && !regDot) { regDot = true; return true; }
                if (d >= 0) {
                    int r = d + (regDot ? 10 : 0);
                    char op = regOp;
                    pending = P_NONE;
                    regOp = 0;
                    regDot = false;
                    if (sto) store(r, op); else recall(r, op);
                    return true;
                }
                if (!sto && code == 49 && regOp == 0 && !regDot) {   // RCL Σ+
                    pending = P_NONE;
                    pushVal(reg[5]);
                    push();
                    x = reg[3];
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    public String pendingLabel() {
        switch (pending) {
            case P_STO: return "STO" + (regOp == 0 ? "" : String.valueOf(regOp == '*' ? '×' : regOp == '/' ? '÷' : regOp)) + (regDot ? " ." : "");
            case P_RCL: return "RCL" + (regOp == 0 ? "" : String.valueOf(regOp == '*' ? '×' : regOp == '/' ? '÷' : regOp)) + (regDot ? " ." : "");
            case P_FIX: return "FIX";
            case P_SCI: return "SCI";
            case P_ENG: return "ENG";
            case P_HYP: return "HYP";
            case P_AHYP: return "HYP⁻¹";
            default: return "";
        }
    }

    private void exec(String op) {
        if (op.length() == 1 && Character.isDigit(op.charAt(0))) { digit(op.charAt(0)); return; }
        switch (op) {
            case ".": point(); break;
            case "EEX": eex(); break;
            case "CHS": chs(); break;
            case "←": back(); break;
            case "ENTER": finishEntry(); push(); lift = false; break;
            case "CLx": entering = false; x = 0; lift = false; break;

            case "+": op2((b, a) -> b + a); break;
            case "−": op2((b, a) -> b - a); break;
            case "×": op2((b, a) -> b * a); break;
            case "÷": op2((b, a) -> { if (a == 0) throw new CalcError(0); return b / a; }); break;
            case "yˣ": op2(Calc::power); break;
            case "%": finishEntry(); { double r = check(y * x / 100); lastX = x; x = r; lift = true; } break;
            case "Δ%": finishEntry(); { if (y == 0) throw new CalcError(0); double r = check((x - y) / y * 100); lastX = x; x = r; lift = true; } break;

            case "√x": op1(a -> { if (a < 0) throw new CalcError(0); return Math.sqrt(a); }); break;
            case "x²": op1(a -> a * a); break;
            case "1/x": op1(a -> { if (a == 0) throw new CalcError(0); return 1 / a; }); break;
            case "eˣ": op1(Math::exp); break;
            case "LN": op1(a -> { if (a <= 0) throw new CalcError(0); return Math.log(a); }); break;
            case "10ˣ": op1(a -> Math.pow(10, a)); break;
            case "LOG": op1(a -> { if (a <= 0) throw new CalcError(0); return Math.log10(a); }); break;
            case "ABS": op1(Math::abs); break;
            case "INT": op1(a -> a < 0 ? Math.ceil(a) : Math.floor(a)); break;
            case "FRAC": op1(a -> a - (a < 0 ? Math.ceil(a) : Math.floor(a))); break;
            case "x!": op1(Calc::factorial); break;
            case "RND": op1(this::roundDisplayed); break;
            case "→RAD": op1(a -> a * Math.PI / 180); break;
            case "→DEG": op1(a -> a * 180 / Math.PI); break;
            case "→H.MS": op1(Calc::toHms); break;
            case "→H": op1(Calc::fromHms); break;

            case "SIN": op1(this::sinA); break;
            case "COS": op1(this::cosA); break;
            case "TAN": op1(this::tanA); break;
            case "SIN⁻¹": op1(a -> { if (Math.abs(a) > 1) throw new CalcError(0); return fromRad(Math.asin(a)); }); break;
            case "COS⁻¹": op1(a -> { if (Math.abs(a) > 1) throw new CalcError(0); return fromRad(Math.acos(a)); }); break;
            case "TAN⁻¹": op1(a -> fromRad(Math.atan(a))); break;
            case "HYP": finishEntry(); pending = P_HYP; break;
            case "HYP⁻¹": finishEntry(); pending = P_AHYP; break;
            case "DEG": finishEntry(); angle = DEG; break;
            case "RAD": finishEntry(); angle = RAD; break;
            case "GRD": finishEntry(); angle = GRAD; break;

            case "→P": {
                finishEntry();
                double r = check(Math.hypot(x, y));
                double th = fromRad(Math.atan2(y, x));
                lastX = x; x = r; y = th; lift = true;
                break;
            }
            case "→R": {
                finishEntry();
                double r = x, th = y;
                double nx = check(r * cosA(th)), ny = check(r * sinA(th));
                lastX = x; x = nx; y = ny; lift = true;
                break;
            }

            case "x↔y": { finishEntry(); double tmp = x; x = y; y = tmp; lift = true; break; }
            case "R↓": { finishEntry(); double tmp = x; x = y; y = z; z = t; t = tmp; lift = true; break; }
            case "R↑": { finishEntry(); double tmp = t; t = z; z = y; y = x; x = tmp; lift = true; break; }
            case "LSTx": finishEntry(); pushVal(lastX); break;
            case "π": finishEntry(); pushVal(Math.PI); break;
            case "RAN#": finishEntry(); pushVal(Math.floor(Math.random() * 1e10) / 1e10); break;

            case "STO": finishEntry(); pending = P_STO; break;
            case "RCL": finishEntry(); pending = P_RCL; break;
            case "FIX": finishEntry(); pending = P_FIX; break;
            case "SCI": finishEntry(); pending = P_SCI; break;
            case "ENG": finishEntry(); pending = P_ENG; break;

            case "CLREG": finishEntry(); java.util.Arrays.fill(reg, 0); break;
            case "CLΣ": finishEntry(); for (int i = 2; i <= 7; i++) reg[i] = 0; x = y = z = t = 0; break;
            case "PREFIX": finishEntry(); showMantissa = true; break;

            case "Σ+": sigma(1); break;
            case "Σ−": sigma(-1); break;
            case "x̄": {
                finishEntry();
                double n = reg[2];
                if (n == 0) throw new CalcError(2);
                double mx = reg[3] / n, my = reg[5] / n;
                pushVal(my); push(); x = mx; lift = true;
                break;
            }
            case "s": {
                finishEntry();
                double n = reg[2];
                if (n <= 1) throw new CalcError(2);
                double vx = (n * reg[4] - reg[3] * reg[3]) / (n * (n - 1));
                double vy = (n * reg[6] - reg[5] * reg[5]) / (n * (n - 1));
                double sx = Math.sqrt(Math.max(0, vx)), sy = Math.sqrt(Math.max(0, vy));
                pushVal(sy); push(); x = sx; lift = true;
                break;
            }
            case "L.R.": {
                finishEntry();
                double n = reg[2];
                if (n <= 1) throw new CalcError(2);
                double m = n * reg[4] - reg[3] * reg[3];
                if (m == 0) throw new CalcError(2);
                double slope = (n * reg[7] - reg[3] * reg[5]) / m;
                double icpt = (reg[5] * reg[4] - reg[3] * reg[7]) / m;
                pushVal(check(slope)); push(); x = check(icpt); lift = true;
                break;
            }
            case "ŷ,r": {
                finishEntry();
                double n = reg[2];
                if (n <= 1) throw new CalcError(2);
                double m = n * reg[4] - reg[3] * reg[3];
                double nn = n * reg[6] - reg[5] * reg[5];
                double p = n * reg[7] - reg[3] * reg[5];
                if (m <= 0 || nn <= 0) throw new CalcError(2);
                double r = p / Math.sqrt(m * nn);
                double yh = (m * reg[5] + p * (n * x - reg[3])) / (n * m);
                lastX = x;
                t = z; z = y; y = check(r); x = check(yh); lift = true;
                break;
            }
            case "Py,x": op2((b, a) -> perm(b, a)); break;
            case "Cy,x": op2((b, a) -> comb(b, a)); break;

            case "ON": message = "MENU"; break;
            default:
                if (!op.isEmpty()) message = "UNSUPPORTED:" + op;
        }
    }

    // ------------------------------------------------------------------
    // Inserimento numeri
    // ------------------------------------------------------------------
    private void startEntry() {
        if (lift) push();
        x = 0;
        entering = true;
        inExp = false;
        mantNeg = false;
        expNeg = false;
        mant.setLength(0);
        expDigits = "";
    }

    private int mantDigitCount() {
        int c = 0;
        for (int i = 0; i < mant.length(); i++) if (Character.isDigit(mant.charAt(i))) c++;
        return c;
    }

    private void digit(char c) {
        if (!entering) startEntry();
        if (inExp) {
            expDigits = expDigits + c;
            if (expDigits.length() > 2) expDigits = expDigits.substring(expDigits.length() - 2);
        } else {
            if (mantDigitCount() >= 10) return;
            if (mant.toString().equals("0")) mant.setLength(0);
            mant.append(c);
        }
        x = entryValue();
    }

    private void point() {
        if (!entering) startEntry();
        if (inExp) return;
        if (mant.indexOf(".") < 0) {
            if (mant.length() == 0) mant.append('0');
            mant.append('.');
        }
        x = entryValue();
    }

    private void eex() {
        if (!entering) { startEntry(); mant.append('1'); }
        if (inExp) return;
        if (mant.length() == 0 || mant.toString().equals("0") || mant.toString().equals("0.")) {
            mant.setLength(0);
            mant.append('1');
        }
        inExp = true;
        x = entryValue();
    }

    private void chs() {
        if (entering) {
            if (inExp) expNeg = !expNeg; else mantNeg = !mantNeg;
            x = entryValue();
        } else {
            x = -x;
            if (x == 0) x = 0; // niente -0
        }
    }

    private void back() {
        if (!entering) { x = 0; lift = false; return; }
        if (inExp) {
            if (expDigits.length() > 0) expDigits = expDigits.substring(0, expDigits.length() - 1);
            else { inExp = false; expNeg = false; }
        } else {
            if (mant.length() > 0) mant.setLength(mant.length() - 1);
            if (mant.length() == 0) { entering = false; x = 0; lift = false; return; }
        }
        x = entryValue();
    }

    private double entryValue() {
        String m = mant.length() == 0 ? "0" : mant.toString();
        String s = (mantNeg ? "-" : "") + m + (expDigits.isEmpty() ? "" : "E" + (expNeg ? "-" : "") + expDigits);
        try {
            double v = Double.parseDouble(s);
            if (Math.abs(v) > MAX) v = Math.copySign(MAX, v);
            return v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void finishEntry() {
        if (entering) {
            x = entryValue();
            entering = false;
            lift = true;
        }
    }

    public boolean isEntering() { return entering; }

    // ------------------------------------------------------------------
    // Stack e funzioni
    // ------------------------------------------------------------------
    private void push() { t = z; z = y; y = x; }

    private void pushVal(double v) {
        if (lift) push();
        x = v;
        lift = true;
    }

    private interface F1 { double f(double a); }
    private interface F2 { double f(double b, double a); }

    private void op1(F1 f) {
        finishEntry();
        double r = check(f.f(x));
        lastX = x;
        x = r;
        lift = true;
    }

    private void op2(F2 f) {
        finishEntry();
        double r = check(f.f(y, x));
        lastX = x;
        x = r;
        y = z;
        z = t;
        lift = true;
    }

    private static double check(double r) {
        if (Double.isNaN(r)) throw new CalcError(0);
        if (Double.isInfinite(r) || Math.abs(r) > MAX) return Math.copySign(MAX, r);
        if (Math.abs(r) < 1e-99) return 0;
        return r;
    }

    private static double power(double b, double e) {
        if (b == 0 && e <= 0) throw new CalcError(0);
        if (b < 0 && e != Math.rint(e)) throw new CalcError(0);
        return Math.pow(b, e);
    }

    private void store(int r, char op) {
        double v;
        switch (op) {
            case '+': v = reg[r] + x; break;
            case '-': v = reg[r] - x; break;
            case '*': v = reg[r] * x; break;
            case '/': if (x == 0) throw new CalcError(0); v = reg[r] / x; break;
            default: v = x;
        }
        reg[r] = check(v);
        lift = true;
    }

    private void recall(int r, char op) {
        if (op == 0) { pushVal(reg[r]); return; }
        double v;
        switch (op) {
            case '+': v = x + reg[r]; break;
            case '-': v = x - reg[r]; break;
            case '*': v = x * reg[r]; break;
            default: if (reg[r] == 0) throw new CalcError(0); v = x / reg[r];
        }
        v = check(v);
        lastX = x;
        x = v;
        lift = true;
    }

    private void sigma(int sign) {
        finishEntry();
        reg[2] += sign;
        reg[3] += sign * x;
        reg[4] += sign * x * x;
        reg[5] += sign * y;
        reg[6] += sign * y * y;
        reg[7] += sign * x * y;
        lastX = x;
        x = reg[2];
        lift = false;
    }

    // --- trigonometria con angoli esatti per DEG e GRAD ---
    private double toRad(double a) {
        return angle == DEG ? a * Math.PI / 180 : angle == GRAD ? a * Math.PI / 200 : a;
    }

    private double fromRad(double a) {
        return angle == DEG ? a * 180 / Math.PI : angle == GRAD ? a * 200 / Math.PI : a;
    }

    /** Restituisce il quadrante esatto (0..3) se l'angolo è multiplo di 90°/100g, altrimenti -1. */
    private int exactQuadrant(double a) {
        if (angle == RAD) return -1;
        double q = angle == DEG ? 90 : 100;
        double r = a % (4 * q);
        if (r < 0) r += 4 * q;
        if (r % q != 0) return -1;
        return (int) (r / q);
    }

    private double reduce(double a) {
        if (angle == RAD) return a;
        double full = angle == DEG ? 360 : 400;
        return a % full;
    }

    private double sinA(double a) {
        int k = exactQuadrant(a);
        if (k >= 0) return new double[]{0, 1, 0, -1}[k];
        return Math.sin(toRad(reduce(a)));
    }

    private double cosA(double a) {
        int k = exactQuadrant(a);
        if (k >= 0) return new double[]{1, 0, -1, 0}[k];
        return Math.cos(toRad(reduce(a)));
    }

    private double tanA(double a) {
        int k = exactQuadrant(a);
        if (k == 0 || k == 2) return 0;
        if (k == 1) return MAX;
        if (k == 3) return -MAX;
        return Math.tan(toRad(reduce(a)));
    }

    private void hyp(int code, boolean inv) {
        if (!inv) {
            if (code == 23) op1(Math::sinh);
            else if (code == 24) op1(Math::cosh);
            else op1(Math::tanh);
        } else {
            if (code == 23) op1(a -> Math.log(a + Math.sqrt(a * a + 1)));
            else if (code == 24) op1(a -> { if (a < 1) throw new CalcError(0); return Math.log(a + Math.sqrt(a * a - 1)); });
            else op1(a -> { if (Math.abs(a) >= 1) throw new CalcError(0); return 0.5 * Math.log((1 + a) / (1 - a)); });
        }
    }

    // --- fattoriale, gamma, permutazioni ---
    private static final double[] LANCZOS = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    private static double gamma(double v) {
        if (v < 0.5) return Math.PI / (Math.sin(Math.PI * v) * gamma(1 - v));
        v -= 1;
        double a = LANCZOS[0];
        double tt = v + 7.5;
        for (int i = 1; i < 9; i++) a += LANCZOS[i] / (v + i);
        return Math.sqrt(2 * Math.PI) * Math.pow(tt, v + 0.5) * Math.exp(-tt) * a;
    }

    private static double factorial(double a) {
        if (a < 0 && a == Math.rint(a)) throw new CalcError(0);
        if (a == Math.rint(a)) {
            if (a > 170) return Double.POSITIVE_INFINITY;
            double r = 1;
            for (int i = 2; i <= (int) a; i++) r *= i;
            return r;
        }
        return gamma(a + 1);
    }

    private static void checkPerm(double n, double k) {
        if (n != Math.rint(n) || k != Math.rint(k) || n < 0 || k < 0 || k > n) throw new CalcError(0);
    }

    private static double perm(double n, double k) {
        checkPerm(n, k);
        double r = 1;
        for (double i = n - k + 1; i <= n; i++) {
            r *= i;
            if (r > MAX) return Double.POSITIVE_INFINITY;
        }
        return r;
    }

    private static double comb(double n, double k) {
        checkPerm(n, k);
        if (k > n - k) k = n - k;
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
            if (r > MAX) return Double.POSITIVE_INFINITY;
        }
        return Math.rint(r);
    }

    // --- ore/minuti/secondi ---
    private static double toHms(double v) {
        double sgn = Math.signum(v);
        double tot = Math.abs(v) * 3600;
        tot = Math.round(tot * 1e6) / 1e6;
        long hh = (long) Math.floor(tot / 3600);
        double rem = tot - hh * 3600.0;
        long mm = (long) Math.floor(rem / 60);
        double ss = rem - mm * 60.0;
        return sgn * (hh + mm / 100.0 + ss / 10000.0);
    }

    private static double fromHms(double v) {
        double sgn = Math.signum(v);
        BigDecimal a = new BigDecimal(Double.toString(Math.abs(v)));
        BigDecimal h = a.setScale(0, RoundingMode.DOWN);
        BigDecimal frac = a.subtract(h).movePointRight(2);
        BigDecimal mm = frac.setScale(0, RoundingMode.DOWN);
        BigDecimal ss = frac.subtract(mm).movePointRight(2);
        return sgn * (h.doubleValue() + mm.doubleValue() / 60.0 + ss.doubleValue() / 3600.0);
    }

    private double roundDisplayed(double v) {
        // rilegge il numero esattamente come appare sul display
        String s = format(v).replace(String.valueOf(grp()), "").replace(dp(), '.');
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\S+)\\s+(-?)(\\d\\d)$").matcher(s);
        try {
            if (m.find()) {
                int e = Integer.parseInt(m.group(3));
                if (m.group(2).equals("-")) e = -e;
                return new BigDecimal(m.group(1)).scaleByPowerOfTen(e).doubleValue();
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return v;
        }
    }

    // ------------------------------------------------------------------
    // Visualizzazione
    // ------------------------------------------------------------------
    public String display() {
        if (error != null) return error;
        if (entering) return entryDisplay();
        return format(x);
    }

    private char dp() { return comma ? ',' : '.'; }
    private char grp() { return comma ? '.' : ','; }

    private String group(String intPart) {
        boolean neg = intPart.startsWith("-");
        String d = neg ? intPart.substring(1) : intPart;
        StringBuilder sb = new StringBuilder();
        int n = d.length();
        for (int i = 0; i < n; i++) {
            sb.append(d.charAt(i));
            int left = n - i - 1;
            if (left > 0 && left % 3 == 0) sb.append(grp());
        }
        return (neg ? "-" : "") + sb;
    }

    /** "1234.5" -> "1,234.5" ; aggiunge sempre il punto decimale come la HP. */
    private String groupPlain(String s) {
        int p = s.indexOf('.');
        if (p < 0) return group(s) + dp();
        return group(s.substring(0, p)) + dp() + s.substring(p + 1);
    }

    private static String expStr(int e) {
        return (e < 0 ? " -" : "  ") + String.format(Locale.ROOT, "%02d", Math.abs(e));
    }

    private String entryDisplay() {
        String m = mant.length() == 0 ? "0" : mant.toString();
        String s = (mantNeg ? "-" : "") + m;
        int p = s.indexOf('.');
        String out = p < 0 ? group(s) + dp() : group(s.substring(0, p)) + dp() + s.substring(p + 1);
        if (inExp) {
            String e = expDigits.isEmpty() ? "00" : (expDigits.length() == 1 ? "0" + expDigits : expDigits);
            out = out + (expNeg ? " -" : "  ") + e;
        }
        return out;
    }

    public String format(double v) {
        if (v == 0) v = 0; // elimina -0
        switch (fmt) {
            case SCI: return sci(v, digits);
            case ENG: return eng(v, digits);
            default: return fix(v, digits);
        }
    }

    private String fix(double v, int n) {
        if (v == 0) return groupPlain(BigDecimal.ZERO.setScale(n).toPlainString());
        BigDecimal bd = new BigDecimal(v).round(new MathContext(10, RoundingMode.HALF_UP));
        if (bd.abs().compareTo(new BigDecimal("1E10")) >= 0) return sci(v, n);
        int intDigits = bd.abs().compareTo(BigDecimal.ONE) < 0 ? 1
                : bd.abs().setScale(0, RoundingMode.DOWN).toPlainString().length();
        int dec = Math.max(0, Math.min(n, 10 - intDigits));
        BigDecimal r = bd.setScale(dec, RoundingMode.HALF_UP);
        if (r.signum() == 0) return sci(v, n);
        if (r.abs().compareTo(new BigDecimal("1E10")) >= 0) return sci(v, n);
        return groupPlain(r.toPlainString());
    }

    private String sci(double v, int n) {
        n = Math.min(n, 6);
        if (v == 0) return BigDecimal.ZERO.setScale(n).toPlainString().replace('.', dp()) + (n == 0 ? String.valueOf(dp()) : "") + expStr(0);
        BigDecimal bd = new BigDecimal(v).round(new MathContext(n + 1, RoundingMode.HALF_UP));
        int e = bd.precision() - bd.scale() - 1;
        if (e > 99) { // l'arrotondamento non deve produrre un esponente a 3 cifre
            bd = new BigDecimal(v).round(new MathContext(n + 1, RoundingMode.DOWN));
            e = bd.precision() - bd.scale() - 1;
        }
        BigDecimal m = bd.movePointLeft(e).setScale(n, RoundingMode.HALF_UP);
        String ms = m.toPlainString();
        if (ms.indexOf('.') < 0) ms = ms + ".";
        return ms.replace('.', dp()) + expStr(e);
    }

    private String eng(double v, int n) {
        n = Math.min(n, 6);
        if (v == 0) return BigDecimal.ZERO.setScale(n).toPlainString().replace('.', dp()) + (n == 0 ? String.valueOf(dp()) : "") + expStr(0);
        BigDecimal bd = new BigDecimal(v).round(new MathContext(n + 1, RoundingMode.HALF_UP));
        int e = bd.precision() - bd.scale() - 1;
        if (e > 99) {
            bd = new BigDecimal(v).round(new MathContext(n + 1, RoundingMode.DOWN));
            e = bd.precision() - bd.scale() - 1;
        }
        int e3 = Math.floorDiv(e, 3) * 3;
        BigDecimal m = bd.movePointLeft(e3);
        int sc = Math.max(0, n - (e - e3));
        String ms = m.setScale(sc, RoundingMode.HALF_UP).toPlainString();
        if (ms.indexOf('.') < 0) ms = ms + ".";
        return ms.replace('.', dp()) + expStr(e3);
    }

    /** Le 10 cifre significative di X, come CLEAR PREFIX sulla HP-15C. */
    public String mantissa() {
        if (x == 0) return "0000000000";
        BigDecimal bd = new BigDecimal(Math.abs(x)).round(new MathContext(10, RoundingMode.HALF_UP));
        String u = bd.unscaledValue().toString();
        return (u + "0000000000").substring(0, 10);
    }

    // ------------------------------------------------------------------
    // Salvataggio dello stato
    // ------------------------------------------------------------------
    public String serialize() {
        finishEntry();
        StringBuilder sb = new StringBuilder("v1");
        double[] st = {x, y, z, t, lastX};
        for (double d : st) sb.append(';').append(d);
        for (double d : reg) sb.append(';').append(d);
        sb.append(';').append(lift ? 1 : 0)
          .append(';').append(fmt)
          .append(';').append(digits)
          .append(';').append(angle)
          .append(';').append(comma ? 1 : 0);
        return sb.toString();
    }

    public void restore(String s) {
        if (s == null || !s.startsWith("v1;")) return;
        try {
            String[] p = s.split(";");
            int i = 1;
            x = Double.parseDouble(p[i++]);
            y = Double.parseDouble(p[i++]);
            z = Double.parseDouble(p[i++]);
            t = Double.parseDouble(p[i++]);
            lastX = Double.parseDouble(p[i++]);
            for (int r = 0; r < reg.length; r++) reg[r] = Double.parseDouble(p[i++]);
            lift = p[i++].equals("1");
            fmt = Integer.parseInt(p[i++]);
            digits = Integer.parseInt(p[i++]);
            angle = Integer.parseInt(p[i++]);
            comma = p[i++].equals("1");
        } catch (RuntimeException e) {
            reset();
        }
    }

    public void reset() {
        x = y = z = t = lastX = 0;
        java.util.Arrays.fill(reg, 0);
        lift = true;
        entering = false;
        fmt = FIX;
        digits = 4;
        angle = DEG;
        shift = 0;
        pending = P_NONE;
        error = null;
    }
}
