package it.rpn15;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Motore di calcolo RPN in stile HP-15C.
 * Non dipende da Android: si può collaudare su qualunque JVM.
 *
 * I tasti sono identificati dal codice riga/colonna della HP-15C
 * (riga 1..4, colonna 1..9 e 0 per la decima), es. 36 = ENTER, 47 = "0".
 *
 * Tutti i metodi vanno chiamati da un solo thread; requestStop() e i campi
 * volatile possono essere letti/usati da altri thread.
 */
public final class Calc {

    // ==================================================================
    // Tastiera: codice -> {funzione primaria, funzione f, funzione g}
    // ==================================================================
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

    public static String label(int code, int layer) {
        String[] a = KEYS.get(code);
        return a == null ? "" : a[layer];
    }

    public static boolean supported(String op) {
        return op != null && !op.isEmpty();
    }

    // ==================================================================
    // Costanti
    // ==================================================================
    public static final double MAX = 9.999999999e99;
    public static final int NREG = 64;        // registri raggiungibili con (i)
    public static final int MAXLINES = 448;   // righe di programma
    public static final int MAXMAT = 400;     // elementi di matrice in totale
    public static final int MAXRET = 7;       // livelli di sottoprogramma

    static final int A_I = 100, A_IND = 101, A_MAT = 200, A_EL = 210, A_MDESC = 220,
            A_DIM = 230, A_RES = 240, A_RAN = 241, A_SIGMA = 242;
    static final int LBL_A = 20;

    public static final int FIX = 0, SCI = 1, ENG = 2;
    public static final int DEG = 0, RAD = 1, GRAD = 2;

    // ==================================================================
    // Stato
    // ==================================================================
    /** Stack: indice 0 = X, 1 = Y, 2 = Z, 3 = T. im = parte immaginaria, mt = descrittore di matrice (0 = numero, 1..5 = A..E). */
    final double[] re = new double[4], im = new double[4];
    final int[] mt = new int[4];
    double lre, lim;
    int lmt;
    /** R0..R9 = 0..9, R.0..R.9 = 10..19, altri registri 20..63 via (i). R2..R7 sono i registri statistici. */
    public final double[] reg = new double[NREG];
    final int[] regTag = new int[NREG];
    public double regI;
    int regITag;
    public final boolean[] flag = new boolean[10];
    private boolean lift = true;

    private boolean entering, inExp, mantNeg, expNeg;
    private final StringBuilder mant = new StringBuilder();
    private String expDigits = "";

    public int fmt = FIX, digits = 4, angle = DEG;
    public boolean comma, user, prgm;
    /** 0 nessuno, 1 = f, 2 = g */
    public int shift;

    final ArrayList<Instr> prog = new ArrayList<>();
    int pc;
    final ArrayList<Integer> rets = new ArrayList<>();
    public volatile boolean running;
    private volatile boolean stopReq;
    public volatile String pauseText;
    private int fnDepth, solveDepth, integDepth;

    final double[][] mat = new double[5][];
    final int[] mrows = new int[5], mcols = new int[5];
    int resultM;

    private Random rng = new Random();
    private double seed;

    public String error;
    /** Messaggio per l'interfaccia: "MENU". */
    public String message;
    /** Testo da mostrare per un attimo (riga di programma, MEM, parte immaginaria...). */
    public String transientText;

    public Calc() {
        for (int i = 0; i < 5; i++) mat[i] = new double[0];
    }

    // ==================================================================
    // Istruzioni ed eccezioni
    // ==================================================================
    static final class Instr {
        final String op;
        final int arg;
        final char ar;
        final int[] codes;

        Instr(String op, int arg, char ar, int[] codes) {
            this.op = op;
            this.arg = arg;
            this.ar = ar;
            this.codes = codes;
        }
    }

    static final class CalcError extends RuntimeException {
        final int code;

        CalcError(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    static final class Halt extends RuntimeException {
        Halt() {
            super(null, null, false, false);
        }
    }

    public void requestStop() {
        stopReq = true;
    }

    private void setError(int code) {
        error = "Error " + code;
        entering = false;
        cancelPfx();
    }

    // ==================================================================
    // Analisi dei tasti
    // ==================================================================
    private String pfx = null;
    private final ArrayList<Integer> buf = new ArrayList<>();
    private char pAr = 0;
    private boolean pDot, pG, pM, pDim;
    private int pLineDigits = -1, pLine;

    private void cancelPfx() {
        pfx = null;
        buf.clear();
        pAr = 0;
        pDot = pG = pM = pDim = false;
        pLineDigits = -1;
        pLine = 0;
    }

    public void press(int code) {
        message = null;
        transientText = null;
        if (error != null) {          // qualunque tasto cancella il messaggio d'errore
            error = null;
            shift = 0;
            cancelPfx();
            return;
        }
        if (pfx != null) {
            if (code == 42 || code == 43) {
                if (code == 43 && (pfx.equals("STO") || pfx.equals("RCL")) && !pG && pAr == 0 && !pDot && !pM && !pDim) {
                    pG = true;
                    buf.add(43);
                }
                return;
            }
            shift = 0;
            try {
                if (prefixKey(code)) return;
            } catch (CalcError e) {
                setError(e.code);
                return;
            }
            cancelPfx();
            normalKey(code, 0);
            return;
        }
        if (code == 42) { shift = shift == 1 ? 0 : 1; return; }
        if (code == 43) { shift = shift == 2 ? 0 : 2; return; }
        int layer = shift;
        shift = 0;
        normalKey(code, layer);
    }

    private static int[] canonical(int code, String op) {
        if (op.equals(label(code, 0))) return new int[]{code};
        if (op.equals(label(code, 1))) return new int[]{42, code};
        return new int[]{43, code};
    }

    private void normalKey(int code, int layer) {
        String op = label(code, layer);
        if (user && code >= 11 && code <= 15 && layer != 2) op = label(code, layer == 0 ? 1 : 0);
        if (op.isEmpty()) return;
        if (op.length() == 1 && op.charAt(0) >= 'A' && op.charAt(0) <= 'E') {
            emit(new Instr("GSB", LBL_A + (op.charAt(0) - 'A'), (char) 0, new int[]{32, code}));
            return;
        }
        int[] head = canonical(code, op);
        switch (op) {
            case "STO": case "RCL": case "GTO": case "GSB": case "LBL":
            case "FIX": case "SCI": case "ENG": case "HYP": case "HYP⁻¹":
            case "x↔": case "DSE": case "ISG": case "SF": case "CF": case "F?":
            case "TEST": case "DIM": case "RESULT": case "MATRIX": case "SOLVE": case "∫xy":
                cancelPfx();
                pfx = op;
                for (int c : head) buf.add(c);
                return;
            default:
                emit(new Instr(op, -1, (char) 0, head));
        }
    }

    private boolean fin(int code, int arg) {
        if (code >= 0) buf.add(code);
        int[] codes = new int[buf.size()];
        for (int i = 0; i < codes.length; i++) codes[i] = buf.get(i);
        Instr in = new Instr(pfx, arg, pAr, codes);
        cancelPfx();
        emit(in);
        return true;
    }

    private boolean prefixKey(int code) {
        int d = digitOf(code);
        switch (pfx) {
            case "FIX": case "SCI": case "ENG":
                if (d >= 0) return fin(code, d);
                if (code == 25) return fin(code, A_I);
                return false;
            case "HYP": case "HYP⁻¹":
                if (code == 23 || code == 24 || code == 25) return fin(code, code);
                return false;
            case "SF": case "CF": case "F?":
                if (d >= 0) return fin(code, d);
                if (code == 25) return fin(code, A_I);
                return false;
            case "TEST": case "MATRIX":
                if (d >= 0) return fin(code, d);
                return false;
            case "DIM":
                if (code >= 11 && code <= 15) return fin(code, A_MAT + code - 11);
                if (code == 24) return fin(code, A_IND);
                return false;
            case "RESULT":
                if (code >= 11 && code <= 15) return fin(code, A_MAT + code - 11);
                return false;
            case "LBL": case "GTO": case "GSB": case "SOLVE": case "∫xy":
                if (pfx.equals("GTO") && pLineDigits >= 0) {
                    if (d < 0) return false;
                    buf.add(code);
                    pLine = pLine * 10 + d;
                    pLineDigits++;
                    if (pLineDigits == 3) {
                        pfx = "GTOL";
                        return fin(-1, pLine);
                    }
                    return true;
                }
                if (pfx.equals("GTO") && code == 16 && !pDot) {
                    buf.add(code);
                    pLineDigits = 0;
                    pLine = 0;
                    return true;
                }
                if (code >= 11 && code <= 15 && !pDot) return fin(code, LBL_A + code - 11);
                if (code == 48 && !pDot) { pDot = true; buf.add(code); return true; }
                if (d >= 0) return fin(code, d + (pDot ? 10 : 0));
                if (code == 25 && !pDot && !pfx.equals("LBL")) return fin(code, A_I);
                return false;
            case "x↔": case "DSE": case "ISG":
                if (code == 48 && !pDot) { pDot = true; buf.add(code); return true; }
                if (d >= 0) return fin(code, d + (pDot ? 10 : 0));
                if (pDot) return false;
                if (code == 25) return fin(code, A_I);
                if (code == 24) return fin(code, A_IND);
                return false;
            case "STO": case "RCL": {
                boolean sto = pfx.equals("STO");
                if (pM) return code >= 11 && code <= 15 && fin(code, A_MDESC + code - 11);
                if (pDim) return code >= 11 && code <= 15 && fin(code, A_DIM + code - 11);
                if (pG) return code >= 11 && code <= 15 && fin(code, A_EL + code - 11);
                if (pDot) return d >= 0 && fin(code, 10 + d);
                if (pAr == 0 && (code == 40 || code == 30 || code == 20 || code == 10)) {
                    pAr = code == 40 ? '+' : code == 30 ? '-' : code == 20 ? '*' : '/';
                    buf.add(code);
                    return true;
                }
                if (code == 48) { pDot = true; buf.add(code); return true; }
                if (d >= 0) return fin(code, d);
                if (code == 25) return fin(code, A_I);
                if (code == 24) return fin(code, A_IND);
                if (code >= 11 && code <= 15) return fin(code, A_MAT + code - 11);
                if (pAr != 0) return false;
                if (code == 16) { pM = true; buf.add(code); return true; }
                if (code == 23 && !sto) { pDim = true; buf.add(code); return true; }
                if (code == 26) return fin(code, A_RES);
                if (code == 36) return fin(code, A_RAN);
                if (code == 49 && !sto) return fin(code, A_SIGMA);
                return false;
            }
            default:
                return false;
        }
    }

    public String pendingLabel() {
        if (pfx == null) return "";
        StringBuilder sb = new StringBuilder(pfx);
        if (pAr != 0) sb.append(pAr == '*' ? '×' : pAr == '/' ? '÷' : pAr == '-' ? '−' : '+');
        if (pG) sb.append(" g");
        if (pM) sb.append(" MATRIX");
        if (pDim) sb.append(" DIM");
        if (pDot) sb.append(" .");
        if (pLineDigits >= 0) {
            sb.append(" CHS ");
            if (pLineDigits > 0) sb.append(String.format(Locale.ROOT, "%0" + pLineDigits + "d", pLine));
        }
        return sb.toString();
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

    // ==================================================================
    // Esecuzione: tastiera, memorizzazione, programma
    // ==================================================================
    private static boolean programmable(Instr in) {
        switch (in.op) {
            case "P/R": case "SST": case "BST": case "←": case "MEM": case "USER":
            case "CLPRG": case "PREFIX": case "ON": case "GTOL":
                return false;
            default:
                return true;
        }
    }

    private void emit(Instr in) {
        if (prgm && programmable(in)) {
            if (prog.size() >= MAXLINES) { setError(10); return; }
            prog.add(pc, in);
            pc++;
            return;
        }
        try {
            keyboard(in);
        } catch (CalcError e) {
            setError(e.code);
        } catch (Halt h) {
            // interrotto dall'utente
        }
    }

    private void keyboard(Instr in) {
        switch (in.op) {
            case "P/R": finishEntry(); prgm = !prgm; return;
            case "SST":
                if (prgm) { pc = pc >= prog.size() ? 0 : pc + 1; return; }
                sstRun();
                return;
            case "BST":
                pc = pc == 0 ? prog.size() : pc - 1;
                if (!prgm) { finishEntry(); transientText = lineText(pc); }
                return;
            case "←":
                if (prgm) {
                    if (pc > 0) { prog.remove(pc - 1); pc--; }
                    return;
                }
                break;
            case "CLPRG":
                if (prgm) prog.clear();
                pc = 0;
                rets.clear();
                return;
            case "MEM": transientText = memText(); return;
            case "USER": user = !user; return;
            case "GTOL":
                if (in.arg > prog.size()) throw new CalcError(4);
                pc = in.arg;
                rets.clear();
                return;
            case "ON": message = "MENU"; return;
            case "PREFIX": if (prgm) return; break;
            default: break;
        }
        if (prgm) return;
        switch (in.op) {
            case "GSB":
                finishEntry();
                rets.clear();
                pc = labelLine(in.arg);
                runProgram();
                return;
            case "R/S":
                finishEntry();
                if (!prog.isEmpty()) runProgram();
                return;
            case "GTO":
                finishEntry();
                pc = labelLine(in.arg);
                rets.clear();
                return;
            case "RTN":
                finishEntry();
                pc = 0;
                rets.clear();
                return;
            case "LBL": case "PSE":
                finishEntry();
                return;
            case "SOLVE": case "∫xy":
                running = true;
                stopReq = false;
                try {
                    exec(in, false);
                } finally {
                    running = false;
                }
                return;
            default:
                break;
        }
        if (isTest(in)) {
            transientText = test(in) ? "true" : "false";
            return;
        }
        exec(in, false);
    }

    private void runProgram() {
        running = true;
        stopReq = false;
        try {
            runLoop(rets.size() == 0 ? 0 : 0);
        } catch (CalcError e) {
            setError(e.code);
        } catch (Halt h) {
            // fermato con un tasto
        } finally {
            finishEntry();
            running = false;
            pauseText = null;
        }
    }

    /** Esegue righe di programma finché un RTN riporta il livello dei sottoprogrammi a 'base' (o R/S, a livello 0). */
    private void runLoop(int base) {
        while (true) {
            if (stopReq) {
                stopReq = false;
                throw new Halt();
            }
            pc++;
            if (pc > prog.size()) {        // fine della memoria: equivale a RTN
                if (doRtn(base)) return;
                continue;
            }
            Instr in = prog.get(pc - 1);
            switch (in.op) {
                case "RTN":
                    finishEntry();
                    if (doRtn(base)) return;
                    continue;
                case "R/S":
                    finishEntry();
                    if (fnDepth == 0) return;
                    continue;
                case "GSB":
                    finishEntry();
                    if (rets.size() >= MAXRET) throw new CalcError(5);
                    int target = labelLine(in.arg);
                    rets.add(pc);
                    pc = target;
                    continue;
                case "GTO":
                    finishEntry();
                    pc = labelLine(in.arg);
                    continue;
                case "LBL":
                    finishEntry();
                    continue;
                case "PSE":
                    finishEntry();
                    pause();
                    continue;
                default:
                    if (exec(in, true)) pc++;
            }
        }
    }

    private boolean doRtn(int base) {
        if (rets.size() > base) {
            pc = rets.remove(rets.size() - 1);
            return false;
        }
        if (base == 0 && fnDepth == 0) pc = 0;
        return true;
    }

    private void pause() {
        pauseText = display();
        try {
            for (int i = 0; i < 20 && !stopReq; i++) Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pauseText = null;
    }

    /** Esegue un sottoprogramma e torna quando incontra il suo RTN. */
    private void callSub(int line) {
        int save = pc;
        int base = rets.size();
        pc = line;
        try {
            runLoop(base);
        } finally {
            pc = save;
            while (rets.size() > base) rets.remove(rets.size() - 1);
        }
    }

    private void sstRun() {
        if (prog.isEmpty()) return;
        if (pc >= prog.size()) { pc = 0; transientText = lineText(0); return; }
        pc++;
        Instr in = prog.get(pc - 1);
        transientText = lineText(pc);
        switch (in.op) {
            case "GSB":
                finishEntry();
                running = true;
                stopReq = false;
                try {
                    callSub(labelLine(in.arg));
                } finally {
                    running = false;
                }
                return;
            case "GTO": finishEntry(); pc = labelLine(in.arg); return;
            case "RTN": finishEntry(); if (!rets.isEmpty()) pc = rets.remove(rets.size() - 1); else pc = 0; return;
            case "R/S": case "LBL": case "PSE": finishEntry(); return;
            default:
                if (exec(in, true)) pc++;
        }
    }

    /** Numero di riga dell'etichetta (ricerca in avanti dalla riga corrente, poi dall'inizio). */
    private int labelLine(int arg) {
        if (arg == A_I) {
            int v = (int) regI;
            if (regI < 0) {
                int line = -v;
                if (line > prog.size()) throw new CalcError(4);
                return line;
            }
            if (v > 24) throw new CalcError(4);
            arg = v;
        }
        int n = prog.size();
        for (int k = 0; k < n; k++) {
            int idx = (pc + k) % n;
            Instr in = prog.get(idx);
            if (in.op.equals("LBL") && in.arg == arg) return idx + 1;
        }
        throw new CalcError(4);
    }

    // ==================================================================
    // Esecuzione di una singola istruzione. Ritorna true se la riga
    // successiva del programma va saltata.
    // ==================================================================
    private boolean exec(Instr in, boolean inProg) {
        String op = in.op;
        if (op.length() == 1 && Character.isDigit(op.charAt(0))) { digit(op.charAt(0)); return false; }
        if (isTest(in)) { finishEntry(); lift = true; return !test(in); }
        switch (op) {
            case ".": point(); break;
            case "EEX": eex(); break;
            case "CHS": chs(); break;
            case "←": back(); break;
            case "ENTER": finishEntry(); liftStack(); lift = false; break;
            case "CLx": finishEntry(); re[0] = 0; mt[0] = 0; lift = false; break;

            case "+": op2((b, a) -> b + a, Calc::cadd, '+'); break;
            case "−": op2((b, a) -> b - a, Calc::csub, '-'); break;
            case "×": op2((b, a) -> b * a, Calc::cmul, '*'); break;
            case "÷": op2((b, a) -> { if (a == 0) throw new CalcError(0); return b / a; }, Calc::cdiv, '/'); break;
            case "yˣ": op2(Calc::power, Calc::cpow, (char) 0); break;
            case "%": {
                finishEntry(); noMat();
                double r = chk(re[1] * re[0] / 100);
                saveLast(); re[0] = r; lift = true;
                break;
            }
            case "Δ%": {
                finishEntry(); noMat();
                if (re[1] == 0) throw new CalcError(0);
                double r = chk((re[0] - re[1]) / re[1] * 100);
                saveLast(); re[0] = r; lift = true;
                break;
            }

            case "√x": op1(a -> { if (a < 0) throw new CalcError(0); return Math.sqrt(a); }, Calc::csqrt); break;
            case "x²": op1(a -> a * a, (a, b) -> cmul(a, b, a, b)); break;
            case "1/x":
                finishEntry();
                if (mt[0] != 0) { matInverse(); break; }
                op1(a -> { if (a == 0) throw new CalcError(0); return 1 / a; }, (a, b) -> cdiv(1, 0, a, b));
                break;
            case "eˣ": op1(Math::exp, Calc::cexp); break;
            case "LN": op1(a -> { if (a <= 0) throw new CalcError(0); return Math.log(a); }, Calc::cln); break;
            case "10ˣ": op1(a -> Math.pow(10, a), (a, b) -> cexp(a * Math.log(10), b * Math.log(10))); break;
            case "LOG": op1(a -> { if (a <= 0) throw new CalcError(0); return Math.log10(a); },
                    (a, b) -> { double[] l = cln(a, b); return C(l[0] / Math.log(10), l[1] / Math.log(10)); }); break;
            case "ABS": op1(Math::abs, (a, b) -> C(Math.hypot(a, b), 0)); break;
            case "INT": op1r(a -> a < 0 ? Math.ceil(a) : Math.floor(a)); break;
            case "FRAC": op1r(a -> a - (a < 0 ? Math.ceil(a) : Math.floor(a))); break;
            case "x!": op1r(Calc::factorial); break;
            case "RND": op1r(this::roundDisplayed); break;
            case "→RAD": op1r(a -> a * Math.PI / 180); break;
            case "→DEG": op1r(a -> a * 180 / Math.PI); break;
            case "→H.MS": op1r(Calc::toHms); break;
            case "→H": op1r(Calc::fromHms); break;

            case "SIN": op1(this::sinA, Calc::csin); break;
            case "COS": op1(this::cosA, Calc::ccos); break;
            case "TAN": op1(this::tanA, Calc::ctan); break;
            case "SIN⁻¹": op1(a -> { if (Math.abs(a) > 1) throw new CalcError(0); return fromRad(Math.asin(a)); }, Calc::casin); break;
            case "COS⁻¹": op1(a -> { if (Math.abs(a) > 1) throw new CalcError(0); return fromRad(Math.acos(a)); }, Calc::cacos); break;
            case "TAN⁻¹": op1(a -> fromRad(Math.atan(a)), Calc::catan); break;
            case "HYP": hyp(in.arg, false); break;
            case "HYP⁻¹": hyp(in.arg, true); break;
            case "DEG": finishEntry(); angle = DEG; break;
            case "RAD": finishEntry(); angle = RAD; break;
            case "GRD": finishEntry(); angle = GRAD; break;

            case "→P": {
                finishEntry(); noMat();
                saveLast();
                if (cx()) {
                    double r = chk(Math.hypot(re[0], im[0]));
                    double th = fromRad(Math.atan2(im[0], re[0]));
                    re[0] = r; im[0] = th;
                } else {
                    double r = chk(Math.hypot(re[0], re[1]));
                    double th = fromRad(Math.atan2(re[1], re[0]));
                    re[0] = r; re[1] = th;
                }
                lift = true;
                break;
            }
            case "→R": {
                finishEntry(); noMat();
                saveLast();
                if (cx()) {
                    double r = re[0], th = im[0];
                    re[0] = chk(r * cosA(th)); im[0] = chk(r * sinA(th));
                } else {
                    double r = re[0], th = re[1];
                    re[0] = chk(r * cosA(th)); re[1] = chk(r * sinA(th));
                }
                lift = true;
                break;
            }

            case "x↔y": finishEntry(); swap(0, 1); lift = true; break;
            case "R↓": finishEntry(); rollDown(); lift = true; break;
            case "R↑": finishEntry(); rollDown(); rollDown(); rollDown(); lift = true; break;
            case "LSTx": finishEntry(); pushVal(lre, lim, lmt); break;
            case "π": finishEntry(); pushVal(Math.PI, 0, 0); break;
            case "RAN#": finishEntry(); seed = Math.floor(rng.nextDouble() * 1e10) / 1e10; pushVal(seed, 0, 0); break;

            case "Re↔Im": {
                finishEntry(); noMat();
                flag[8] = true;
                double tmp = re[0]; re[0] = im[0]; im[0] = tmp;
                lift = true;
                break;
            }
            case "I": {                      // f I: crea il numero complesso Y + iX
                finishEntry(); noMat();
                flag[8] = true;
                double r = re[1], i = re[0];
                saveLast();
                drop();
                re[0] = r; im[0] = i; mt[0] = 0;
                lift = true;
                break;
            }
            case "(i)": finishEntry(); transientText = format(im[0]); break;

            case "STO": return sto(in);
            case "RCL": return rcl(in);
            case "x↔": {
                finishEntry(); noMat();
                double tmp = getReg(in.arg);
                setReg(in.arg, re[0]);
                re[0] = tmp;
                lift = true;
                break;
            }
            case "FIX": case "SCI": case "ENG": {
                finishEntry();
                int n = in.arg == A_I ? (int) Math.min(9, Math.abs(regI)) : in.arg;
                fmt = op.equals("FIX") ? FIX : op.equals("SCI") ? SCI : ENG;
                digits = n;
                break;
            }
            case "DSE": case "ISG": return loopCounter(in.arg, op.equals("ISG"));
            case "SF": case "CF": {
                finishEntry();
                int f = flagNo(in.arg);
                flag[f] = op.equals("SF");
                if (f == 8 && !flag[8]) Arrays.fill(im, 0);
                lift = true;
                break;
            }
            case "F?": { finishEntry(); lift = true; return !flag[flagNo(in.arg)]; }

            case "CLREG": finishEntry(); Arrays.fill(reg, 0); Arrays.fill(regTag, 0); regI = 0; regITag = 0; break;
            case "CLΣ": finishEntry(); for (int i = 2; i <= 7; i++) reg[i] = 0; Arrays.fill(re, 0); Arrays.fill(im, 0); Arrays.fill(mt, 0); break;
            case "PREFIX": finishEntry(); transientText = mantissa(); break;

            case "Σ+": sigma(1); break;
            case "Σ−": sigma(-1); break;
            case "x̄": {
                finishEntry();
                double n = reg[2];
                if (n == 0) throw new CalcError(2);
                double mx = reg[3] / n, my = reg[5] / n;
                pushVal(my, 0, 0); pushVal(mx, 0, 0);
                break;
            }
            case "s": {
                finishEntry();
                double n = reg[2];
                if (n <= 1) throw new CalcError(2);
                double vx = (n * reg[4] - reg[3] * reg[3]) / (n * (n - 1));
                double vy = (n * reg[6] - reg[5] * reg[5]) / (n * (n - 1));
                pushVal(Math.sqrt(Math.max(0, vy)), 0, 0); pushVal(Math.sqrt(Math.max(0, vx)), 0, 0);
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
                pushVal(chk(slope), 0, 0); pushVal(chk(icpt), 0, 0);
                break;
            }
            case "ŷ,r": {
                finishEntry(); noMat();
                double n = reg[2];
                if (n <= 1) throw new CalcError(2);
                double m = n * reg[4] - reg[3] * reg[3];
                double nn = n * reg[6] - reg[5] * reg[5];
                double p = n * reg[7] - reg[3] * reg[5];
                if (m <= 0 || nn <= 0) throw new CalcError(2);
                double r = p / Math.sqrt(m * nn);
                double yh = (m * reg[5] + p * (n * re[0] - reg[3])) / (n * m);
                saveLast();
                re[3] = re[2]; re[2] = re[1]; re[1] = chk(r); re[0] = chk(yh);
                lift = true;
                break;
            }
            case "Py,x":
                finishEntry();
                if (mt[0] != 0) { matCtoP(mt[0] - 1); break; }
                op2((b, a) -> perm(b, a), null, (char) 0);
                break;
            case "Cy,x":
                finishEntry();
                if (mt[0] != 0) { matPtoC(mt[0] - 1); break; }
                op2((b, a) -> comb(b, a), null, (char) 0);
                break;

            case "DIM": dim(in.arg); break;
            case "RESULT": finishEntry(); resultM = in.arg - A_MAT; break;
            case "MATRIX": matrix(in.arg); break;

            case "SOLVE": {
                finishEntry();
                boolean ok = solve(in.arg);
                if (!ok) {
                    if (inProg) return true;
                    throw new CalcError(8);
                }
                break;
            }
            case "∫xy": finishEntry(); integrate(in.arg); break;

            case "GSB": case "GTO": case "LBL": case "RTN": case "R/S": case "PSE":
                finishEntry();
                break;
            default:
                break;
        }
        return false;
    }

    // ==================================================================
    // Test
    // ==================================================================
    private static boolean isTest(Instr in) {
        return in.op.equals("x≤y") || in.op.equals("x=0") || in.op.equals("TEST");
    }

    private boolean test(Instr in) {
        finishEntry();
        double x = re[0], y = re[1];
        boolean eqX0 = x == 0 && (!cx() || im[0] == 0) && mt[0] == 0;
        boolean eqXY = x == y && (!cx() || im[0] == im[1]) && mt[0] == mt[1];
        if (in.op.equals("x≤y")) return x <= y;
        if (in.op.equals("x=0")) return eqX0;
        switch (in.arg) {
            case 0: return !eqX0;
            case 1: return x > 0;
            case 2: return x < 0;
            case 3: return x >= 0;
            case 4: return x <= 0;
            case 5: return eqXY;
            case 6: return !eqXY;
            case 7: return x > y;
            case 8: return x < y;
            default: return x >= y;
        }
    }

    private int flagNo(int arg) {
        int f = arg == A_I ? (int) Math.abs(regI) : arg;
        if (f < 0 || f > 9) throw new CalcError(1);
        return f;
    }

    // ==================================================================
    // Registri
    // ==================================================================
    private int regIndex(int arg) {
        if (arg >= 0 && arg < 20) return arg;
        if (arg == A_IND) {
            if (regITag != 0) throw new CalcError(3);
            int idx = (int) Math.abs(regI);
            if (idx >= NREG) throw new CalcError(3);
            return idx;
        }
        throw new CalcError(3);
    }

    private double getReg(int arg) {
        if (arg == A_I) return regI;
        return reg[regIndex(arg)];
    }

    private void setReg(int arg, double v) {
        if (arg == A_I) { regI = v; regITag = 0; return; }
        int i = regIndex(arg);
        reg[i] = v;
        regTag[i] = 0;
    }

    private static double arith(char ar, double a, double b) {
        switch (ar) {
            case '+': return a + b;
            case '-': return a - b;
            case '*': return a * b;
            case '/': if (b == 0) throw new CalcError(0); return a / b;
            default: return b;
        }
    }

    private boolean sto(Instr in) {
        finishEntry();
        int arg = in.arg;
        lift = true;
        if (arg >= A_MAT && arg < A_MAT + 5) {
            noMat();
            int k = arg - A_MAT;
            int[] rc = r0r1(k);
            int i = (rc[0] - 1) * mcols[k] + rc[1] - 1;
            mat[k][i] = chk(arith(in.ar, mat[k][i], re[0]));
            return user && advance(k);
        }
        if (arg >= A_EL && arg < A_EL + 5) {
            int k = arg - A_EL;
            int r = (int) re[1], c = (int) re[0];
            checkRC(k, r, c);
            double v = re[2];
            mat[k][(r - 1) * mcols[k] + c - 1] = v;
            re[0] = v; im[0] = im[2]; mt[0] = 0;
            re[1] = re[3]; im[1] = im[3]; mt[1] = mt[3];
            re[2] = re[3]; im[2] = im[3]; mt[2] = mt[3];
            return false;
        }
        if (arg >= A_MDESC && arg < A_MDESC + 5) {
            int k = arg - A_MDESC;
            if (mt[0] != 0) {
                int s = mt[0] - 1;
                if (s != k) {
                    setDim(k, mrows[s], mcols[s]);
                    System.arraycopy(mat[s], 0, mat[k], 0, mat[s].length);
                }
            } else {
                Arrays.fill(mat[k], re[0]);
            }
            return false;
        }
        if (arg == A_RES) {
            if (mt[0] == 0) throw new CalcError(1);
            resultM = mt[0] - 1;
            return false;
        }
        if (arg == A_RAN) {
            seed = re[0];
            rng = new Random(Double.doubleToLongBits(re[0]));
            return false;
        }
        if (in.ar == 0) {
            if (arg == A_I) { regI = re[0]; regITag = mt[0]; return false; }
            int i = regIndex(arg);
            reg[i] = re[0];
            regTag[i] = mt[0];
            return false;
        }
        noMat();
        setReg(arg, chk(arith(in.ar, getReg(arg), re[0])));
        return false;
    }

    private boolean rcl(Instr in) {
        finishEntry();
        int arg = in.arg;
        if (arg >= A_MAT && arg < A_MAT + 5) {
            int k = arg - A_MAT;
            int[] rc = r0r1(k);
            double v = mat[k][(rc[0] - 1) * mcols[k] + rc[1] - 1];
            if (in.ar == 0) pushVal(v, 0, 0);
            else { noMat(); saveLast(); re[0] = chk(arith(in.ar, re[0], v)); lift = true; }
            return user && advance(k);
        }
        if (arg >= A_EL && arg < A_EL + 5) {
            int k = arg - A_EL;
            int r = (int) re[1], c = (int) re[0];
            checkRC(k, r, c);
            saveLast();
            drop();
            re[0] = mat[k][(r - 1) * mcols[k] + c - 1]; im[0] = 0; mt[0] = 0;
            lift = true;
            return false;
        }
        if (arg >= A_MDESC && arg < A_MDESC + 5) { pushVal(0, 0, arg - A_MDESC + 1); return false; }
        if (arg >= A_DIM && arg < A_DIM + 5) {
            int k = arg - A_DIM;
            pushVal(mrows[k], 0, 0);
            pushVal(mcols[k], 0, 0);
            return false;
        }
        if (arg == A_RES) { pushVal(0, 0, resultM + 1); return false; }
        if (arg == A_RAN) { pushVal(seed, 0, 0); return false; }
        if (arg == A_SIGMA) { pushVal(reg[5], 0, 0); pushVal(reg[3], 0, 0); return false; }
        if (in.ar == 0) {
            if (arg == A_I) { pushVal(regI, 0, regITag); return false; }
            int i = regIndex(arg);
            pushVal(reg[i], 0, regTag[i]);
            return false;
        }
        noMat();
        double v = getReg(arg);
        saveLast();
        re[0] = chk(arith(in.ar, re[0], v));
        lift = true;
        return false;
    }

    /** ISG / DSE con il numero di controllo nnnnn.xxxyy */
    private boolean loopCounter(int arg, boolean inc) {
        finishEntry();
        lift = true;
        double v = getReg(arg);
        BigDecimal a = new BigDecimal(Double.toString(Math.abs(v)));
        BigDecimal ip = a.setScale(0, RoundingMode.DOWN);
        BigDecimal frac = a.subtract(ip).setScale(5, RoundingMode.HALF_UP);
        long n = ip.longValue() * (v < 0 ? -1 : 1);
        int fr = frac.movePointRight(5).intValue();
        int x = fr / 100, y = fr % 100;
        if (y == 0) y = 1;
        n = inc ? n + y : n - y;
        double fd = frac.doubleValue();
        double nv = n < 0 ? n - fd : n + fd;
        if (n == 0 && fd != 0 && inc) nv = fd;
        setReg(arg, nv);
        return inc ? n > x : n <= x;
    }

    // ==================================================================
    // Inserimento numeri
    // ==================================================================
    private void startEntry() {
        if (lift) liftStack();
        re[0] = 0;
        im[0] = 0;
        mt[0] = 0;
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
        re[0] = entryValue();
    }

    private void point() {
        if (!entering) startEntry();
        if (inExp) return;
        if (mant.indexOf(".") < 0) {
            if (mant.length() == 0) mant.append('0');
            mant.append('.');
        }
        re[0] = entryValue();
    }

    private void eex() {
        if (!entering) { startEntry(); mant.append('1'); }
        if (inExp) return;
        if (mant.length() == 0 || mant.toString().equals("0") || mant.toString().equals("0.")) {
            mant.setLength(0);
            mant.append('1');
        }
        inExp = true;
        re[0] = entryValue();
    }

    private void chs() {
        if (entering) {
            if (inExp) expNeg = !expNeg; else mantNeg = !mantNeg;
            re[0] = entryValue();
        } else if (mt[0] != 0) {
            int k = mt[0] - 1;
            for (int i = 0; i < mat[k].length; i++) mat[k][i] = -mat[k][i];
        } else {
            re[0] = -re[0];
            if (re[0] == 0) re[0] = 0;   // niente -0
            lift = true;
        }
    }

    private void back() {
        if (!entering) { re[0] = 0; mt[0] = 0; lift = false; return; }
        if (inExp) {
            if (expDigits.length() > 0) expDigits = expDigits.substring(0, expDigits.length() - 1);
            else { inExp = false; expNeg = false; }
        } else {
            if (mant.length() > 0) mant.setLength(mant.length() - 1);
            if (mant.length() == 0) { entering = false; re[0] = 0; lift = false; return; }
        }
        re[0] = entryValue();
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
            re[0] = entryValue();
            entering = false;
            lift = true;
        }
    }

    public boolean isEntering() { return entering; }

    // ==================================================================
    // Stack
    // ==================================================================
    private boolean cx() { return flag[8]; }

    private void liftStack() {
        for (int i = 3; i > 0; i--) { re[i] = re[i - 1]; im[i] = im[i - 1]; mt[i] = mt[i - 1]; }
    }

    private void drop() {
        re[1] = re[2]; re[2] = re[3];
        im[1] = im[2]; im[2] = im[3];
        mt[1] = mt[2]; mt[2] = mt[3];
    }

    private void swap(int a, int b) {
        double t = re[a]; re[a] = re[b]; re[b] = t;
        t = im[a]; im[a] = im[b]; im[b] = t;
        int m = mt[a]; mt[a] = mt[b]; mt[b] = m;
    }

    private void rollDown() {
        double r = re[0], i = im[0];
        int m = mt[0];
        for (int k = 0; k < 3; k++) { re[k] = re[k + 1]; im[k] = im[k + 1]; mt[k] = mt[k + 1]; }
        re[3] = r; im[3] = i; mt[3] = m;
    }

    private void pushVal(double r, double i, int m) {
        if (lift) liftStack();
        re[0] = r;
        im[0] = i;
        mt[0] = m;
        lift = true;
    }

    private void saveLast() { lre = re[0]; lim = im[0]; lmt = mt[0]; }

    private void noMat() { if (mt[0] != 0) throw new CalcError(1); }

    private interface F1 { double f(double a); }
    private interface C1 { double[] f(double a, double b); }
    private interface F2 { double f(double y, double x); }
    private interface C2 { double[] f(double yr, double yi, double xr, double xi); }

    /** Funzione di una variabile; in modo complesso usa la versione complessa. */
    private void op1(F1 f, C1 c) {
        finishEntry();
        noMat();
        double r, i;
        if (cx() && c != null) {
            double[] z = c.f(re[0], im[0]);
            r = chk(z[0]);
            i = chk(z[1]);
        } else {
            r = chk(f.f(re[0]));
            i = 0;
        }
        saveLast();
        re[0] = r;
        im[0] = i;
        lift = true;
    }

    /** Funzione che agisce solo sulla parte reale. */
    private void op1r(F1 f) {
        finishEntry();
        noMat();
        double r = chk(f.f(re[0]));
        saveLast();
        re[0] = r;
        lift = true;
    }

    private void op2(F2 f, C2 c, char matOp) {
        finishEntry();
        if (mt[0] != 0 || mt[1] != 0) {
            if (matOp == 0) throw new CalcError(1);
            matArith(matOp);
            return;
        }
        double r, i = 0;
        if (cx() && c != null) {
            double[] z = c.f(re[1], im[1], re[0], im[0]);
            r = chk(z[0]);
            i = chk(z[1]);
        } else {
            r = chk(f.f(re[1], re[0]));
        }
        saveLast();
        drop();
        re[0] = r;
        im[0] = i;
        mt[0] = 0;
        lift = true;
    }

    private double chk(double r) {
        if (Double.isNaN(r)) throw new CalcError(0);
        if (Double.isInfinite(r) || Math.abs(r) > MAX) { flag[9] = true; return Math.copySign(MAX, r); }
        if (Math.abs(r) < 1e-99) return 0;
        return r;
    }

    private static double power(double b, double e) {
        if (b == 0 && e <= 0) throw new CalcError(0);
        if (b < 0 && e != Math.rint(e)) throw new CalcError(0);
        return Math.pow(b, e);
    }

    private void sigma(int sign) {
        finishEntry();
        noMat();
        double x = re[0], y = re[1];
        reg[2] += sign;
        reg[3] += sign * x;
        reg[4] += sign * x * x;
        reg[5] += sign * y;
        reg[6] += sign * y * y;
        reg[7] += sign * x * y;
        saveLast();
        re[0] = reg[2];
        lift = false;
    }

    // ==================================================================
    // Trigonometria (angoli esatti per DEG e GRAD)
    // ==================================================================
    private double toRad(double a) {
        return angle == DEG ? a * Math.PI / 180 : angle == GRAD ? a * Math.PI / 200 : a;
    }

    private double fromRad(double a) {
        return angle == DEG ? a * 180 / Math.PI : angle == GRAD ? a * 200 / Math.PI : a;
    }

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
        return a % (angle == DEG ? 360 : 400);
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

    private void hyp(int key, boolean inv) {
        if (!inv) {
            if (key == 23) op1(Math::sinh, Calc::csinh);
            else if (key == 24) op1(Math::cosh, Calc::ccosh);
            else op1(Math::tanh, Calc::ctanh);
        } else {
            if (key == 23) op1(a -> Math.log(a + Math.sqrt(a * a + 1)), Calc::casinh);
            else if (key == 24) op1(a -> { if (a < 1) throw new CalcError(0); return Math.log(a + Math.sqrt(a * a - 1)); }, Calc::cacosh);
            else op1(a -> { if (Math.abs(a) >= 1) throw new CalcError(0); return 0.5 * Math.log((1 + a) / (1 - a)); }, Calc::catanh);
        }
    }

    // ==================================================================
    // Aritmetica complessa (le funzioni trigonometriche lavorano in radianti)
    // ==================================================================
    private static double[] C(double a, double b) { return new double[]{a, b}; }

    static double[] cadd(double a, double b, double c, double d) { return C(a + c, b + d); }

    static double[] csub(double a, double b, double c, double d) { return C(a - c, b - d); }

    static double[] cmul(double a, double b, double c, double d) { return C(a * c - b * d, a * d + b * c); }

    static double[] cdiv(double a, double b, double c, double d) {
        if (c == 0 && d == 0) throw new CalcError(0);
        if (Math.abs(c) >= Math.abs(d)) {
            double r = d / c, den = c + d * r;
            return C((a + b * r) / den, (b - a * r) / den);
        }
        double r = c / d, den = c * r + d;
        return C((a * r + b) / den, (b * r - a) / den);
    }

    static double[] csqrt(double a, double b) {
        if (a == 0 && b == 0) return C(0, 0);
        double m = Math.hypot(a, b);
        double r = Math.sqrt((m + Math.abs(a)) / 2);
        if (a >= 0) return C(r, b / (2 * r));
        return C(Math.abs(b) / (2 * r), Math.copySign(r, b));
    }

    static double[] cexp(double a, double b) {
        double e = Math.exp(a);
        return C(e * Math.cos(b), e * Math.sin(b));
    }

    static double[] cln(double a, double b) {
        if (a == 0 && b == 0) throw new CalcError(0);
        return C(Math.log(Math.hypot(a, b)), Math.atan2(b, a));
    }

    static double[] cpow(double yr, double yi, double xr, double xi) {
        if (yr == 0 && yi == 0) {
            if (xi == 0 && xr > 0) return C(0, 0);
            throw new CalcError(0);
        }
        double[] l = cln(yr, yi);
        double[] m = cmul(xr, xi, l[0], l[1]);
        return cexp(m[0], m[1]);
    }

    static double[] csin(double a, double b) { return C(Math.sin(a) * Math.cosh(b), Math.cos(a) * Math.sinh(b)); }

    static double[] ccos(double a, double b) { return C(Math.cos(a) * Math.cosh(b), -Math.sin(a) * Math.sinh(b)); }

    static double[] ctan(double a, double b) {
        double[] s = csin(a, b), c = ccos(a, b);
        return cdiv(s[0], s[1], c[0], c[1]);
    }

    static double[] csinh(double a, double b) { return C(Math.sinh(a) * Math.cos(b), Math.cosh(a) * Math.sin(b)); }

    static double[] ccosh(double a, double b) { return C(Math.cosh(a) * Math.cos(b), Math.sinh(a) * Math.sin(b)); }

    static double[] ctanh(double a, double b) {
        double[] s = csinh(a, b), c = ccosh(a, b);
        return cdiv(s[0], s[1], c[0], c[1]);
    }

    static double[] casin(double a, double b) {
        // -i ln(iz + sqrt(1 - z²))
        double[] s = csqrt(1 - a * a + b * b, -2 * a * b);
        double[] w = cln(-b + s[0], a + s[1]);
        return C(w[1], -w[0]);
    }

    static double[] cacos(double a, double b) {
        double[] s = casin(a, b);
        return C(Math.PI / 2 - s[0], -s[1]);
    }

    static double[] catan(double a, double b) {
        // (i/2) [ln(1 - iz) - ln(1 + iz)]
        double[] l1 = cln(1 + b, -a), l2 = cln(1 - b, a);
        double dr = l1[0] - l2[0], di = l1[1] - l2[1];
        return C(-di / 2, dr / 2);
    }

    static double[] casinh(double a, double b) {
        double[] s = csqrt(a * a - b * b + 1, 2 * a * b);
        return cln(a + s[0], b + s[1]);
    }

    static double[] cacosh(double a, double b) {
        double[] p = csqrt(a + 1, b), m = csqrt(a - 1, b);
        double[] q = cmul(p[0], p[1], m[0], m[1]);
        return cln(a + q[0], b + q[1]);
    }

    static double[] catanh(double a, double b) {
        double[] l1 = cln(1 + a, b), l2 = cln(1 - a, -b);
        return C((l1[0] - l2[0]) / 2, (l1[1] - l2[1]) / 2);
    }

    // ==================================================================
    // Fattoriale, gamma, permutazioni, ore
    // ==================================================================
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

    // ==================================================================
    // SOLVE: ricerca di uno zero della funzione definita dall'etichetta
    // ==================================================================
    private double fEval(int line, double x) {
        if (stopReq) { stopReq = false; throw new Halt(); }
        entering = false;
        for (int i = 0; i < 4; i++) { re[i] = x; im[i] = 0; mt[i] = 0; }
        lift = true;
        fnDepth++;
        try {
            callSub(line);
        } finally {
            fnDepth--;
        }
        finishEntry();
        if (mt[0] != 0) throw new CalcError(1);
        double v = re[0];
        if (Double.isNaN(v)) throw new CalcError(0);
        return v;
    }

    private boolean solve(int arg) {
        if (solveDepth > 0) throw new CalcError(7);
        noMat();
        int line = labelLine(arg);
        double a = re[0], b = re[1];
        if (a == b) b = a == 0 ? 1e-7 : a * (1 + 1e-7);
        solveDepth++;
        try {
            double[] r = solveCore(line, a, b);
            if (r == null) return false;
            re[0] = r[0]; re[1] = r[1]; re[2] = r[2];
            im[0] = im[1] = im[2] = 0;
            mt[0] = mt[1] = mt[2] = 0;
            lift = true;
            return true;
        } finally {
            solveDepth--;
        }
    }

    /** Restituisce {radice, stima precedente, f(radice)} oppure null se non trova la radice. */
    private double[] solveCore(int line, double a, double b) {
        double fa = fEval(line, a);
        if (fa == 0) return new double[]{a, b, 0};
        double fb = fEval(line, b);
        if (fb == 0) return new double[]{b, a, 0};
        int evals = 2;
        // fase 1: cerca un cambio di segno (secante con salvaguardie)
        while (Math.signum(fa) == Math.signum(fb)) {
            if (evals > 160) {
                double best = Math.abs(fa) < Math.abs(fb) ? a : b;
                double fbest = Math.min(Math.abs(fa), Math.abs(fb));
                if (fbest < 1e-40) return new double[]{best, best == a ? b : a, best == a ? fa : fb};
                return null;
            }
            if (Math.abs(fa) < Math.abs(fb)) {
                double t = a; a = b; b = t;
                t = fa; fa = fb; fb = t;
            }
            double c;
            if (fb != fa) c = b - fb * (b - a) / (fb - fa); else c = b + (b - a);
            double step = c - b, span = Math.abs(b - a);
            if (!Double.isFinite(c) || Math.abs(step) > 100 * span + 1) c = b + Math.copySign(10 * span + 1e-3, step == 0 ? 1 : step);
            if (c == b) c = b + (b - a) * 0.5;
            if (c == b) {   // nessun progresso possibile
                if (Math.abs(fb) < 1e-40) return new double[]{b, a, fb};
                return null;
            }
            double fc = fEval(line, c);
            evals++;
            if (fc == 0) return new double[]{c, b, 0};
            if (Math.signum(fc) != Math.signum(fb)) {
                a = b; fa = fb; b = c; fb = fc;
                break;
            }
            if (Math.abs(fc) < Math.abs(fb)) {
                a = b; fa = fb; b = c; fb = fc;
            } else {
                // la secante peggiora: prova il punto medio, poi allarga
                double m = (a + b) / 2;
                double fm = fEval(line, m);
                evals++;
                if (fm == 0) return new double[]{m, b, 0};
                if (Math.signum(fm) != Math.signum(fb)) {
                    a = b; fa = fb; b = m; fb = fm;
                    break;
                }
                if (Math.abs(fm) < Math.abs(fb)) {
                    a = b; fa = fb; b = m; fb = fm;
                } else {
                    a = c; fa = fc;
                }
            }
        }
        // fase 2: metodo di Brent sull'intervallo [a, b] con cambio di segno
        double c = a, fc = fa, d = b - a, e = d;
        double prev = a;
        for (int it = 0; it < 200; it++) {
            if (Math.signum(fb) == Math.signum(fc)) { c = a; fc = fa; d = b - a; e = d; }
            if (Math.abs(fc) < Math.abs(fb)) {
                a = b; b = c; c = a;
                fa = fb; fb = fc; fc = fa;
            }
            double tol = 2 * Math.ulp(b) + 1e-300;
            double m = 0.5 * (c - b);
            if (Math.abs(m) <= tol || fb == 0) return new double[]{b, prev, fb};
            if (Math.abs(e) >= tol && Math.abs(fa) > Math.abs(fb)) {
                double s = fb / fa, p, q;
                if (a == c) {
                    p = 2 * m * s;
                    q = 1 - s;
                } else {
                    double qq = fa / fc, r = fb / fc;
                    p = s * (2 * m * qq * (qq - r) - (b - a) * (r - 1));
                    q = (qq - 1) * (r - 1) * (s - 1);
                }
                if (p > 0) q = -q; else p = -p;
                if (2 * p < Math.min(3 * m * q - Math.abs(tol * q), Math.abs(e * q))) {
                    e = d;
                    d = p / q;
                } else {
                    d = m;
                    e = m;
                }
            } else {
                d = m;
                e = m;
            }
            prev = b;
            a = b;
            fa = fb;
            b += Math.abs(d) > tol ? d : Math.copySign(tol, m);
            fb = fEval(line, b);
        }
        return new double[]{b, prev, fb};
    }

    // ==================================================================
    // ∫: integrazione numerica (Gauss-Kronrod adattivo, non valuta gli estremi)
    // ==================================================================
    private static final double[] XGK = {
            0.991455371120812639206854697526329, 0.949107912342758524526189684047851,
            0.864864423359769072789712788640926, 0.741531185599394439863864773280788,
            0.586087235467691130294144845693013, 0.405845151377397166906606412076961,
            0.207784955007898467600689403773245, 0.000000000000000000000000000000000};
    private static final double[] WGK = {
            0.022935322010529224963732008058970, 0.063092092629978553290700663189204,
            0.104790010322250183839876322541518, 0.140653259715525918745189590510238,
            0.169004726639267902826583426598550, 0.190350578064785409913256402421014,
            0.204432940075298892414161999234649, 0.209482141084727828012999174891714};
    private static final double[] WG = {
            0.129484966168869693270611432679082, 0.279705391489276667901467771423780,
            0.381830050505118944950369775488975, 0.417959183673469387755102040816327};

    private int integEvals;

    /** {integrale, errore stimato, max |f|} sull'intervallo [a, b] */
    private double[] gk15(int line, double a, double b) {
        double c = (a + b) / 2, h = (b - a) / 2;
        double fc = fEval(line, c);
        double rk = fc * WGK[7], rg = fc * WG[3], fmax = Math.abs(fc);
        for (int j = 0; j < 7; j++) {
            double dx = h * XGK[j];
            double f1 = fEval(line, c - dx), f2 = fEval(line, c + dx);
            fmax = Math.max(fmax, Math.max(Math.abs(f1), Math.abs(f2)));
            rk += WGK[j] * (f1 + f2);
            if (j % 2 == 1) rg += WG[j / 2] * (f1 + f2);
        }
        integEvals += 15;
        return new double[]{rk * h, Math.abs((rk - rg) * h), fmax};
    }

    private void integrate(int arg) {
        if (integDepth > 0) throw new CalcError(7);
        noMat();
        int line = labelLine(arg);
        double lo = re[1], hi = re[0];
        integDepth++;
        try {
            integEvals = 0;
            double[] first = gk15(line, lo, hi);
            double fmax = first[2];
            double delta;   // incertezza sui valori di f legata al formato del display
            if (fmt == FIX) delta = 0.5 * Math.pow(10, -digits);
            else {
                double mag = fmax == 0 ? 1 : Math.pow(10, Math.floor(Math.log10(fmax)));
                delta = 0.5 * Math.pow(10, -digits) * mag;
            }
            double goal = Math.max(delta * Math.abs(hi - lo), 1e-15 * Math.abs(first[0]));
            ArrayList<double[]> segs = new ArrayList<>();   // {a, b, val, err}
            segs.add(new double[]{lo, hi, first[0], first[1]});
            double total = first[0], err = first[1];
            while (err > goal && integEvals < 6000) {
                int w = 0;
                for (int i = 1; i < segs.size(); i++) if (segs.get(i)[3] > segs.get(w)[3]) w = i;
                double[] s = segs.remove(w);
                double mid = (s[0] + s[1]) / 2;
                double[] l = gk15(line, s[0], mid), r = gk15(line, mid, s[1]);
                segs.add(new double[]{s[0], mid, l[0], l[1]});
                segs.add(new double[]{mid, s[1], r[0], r[1]});
                total = 0;
                err = 0;
                for (double[] q : segs) { total += q[2]; err += q[3]; }
            }
            re[0] = chk(total);
            re[1] = chk(Math.max(err, delta * Math.abs(hi - lo)));
            re[2] = hi;
            re[3] = lo;
            Arrays.fill(im, 0);
            Arrays.fill(mt, 0);
            lift = true;
        } finally {
            integDepth--;
        }
    }

    // ==================================================================
    // Matrici
    // ==================================================================
    private int totalElements(int except) {
        int t = 0;
        for (int i = 0; i < 5; i++) if (i != except) t += mat[i].length;
        return t;
    }

    private void setDim(int k, int r, int c) {
        if (r < 0 || c < 0 || r > 99 || c > 99) throw new CalcError(1);
        if (r == 0 || c == 0) { r = 0; c = 0; }
        if (totalElements(k) + r * c > MAXMAT) throw new CalcError(10);
        double[] n = new double[r * c];
        System.arraycopy(mat[k], 0, n, 0, Math.min(n.length, mat[k].length));
        mat[k] = n;
        mrows[k] = r;
        mcols[k] = c;
    }

    private void checkRC(int k, int r, int c) {
        if (r < 1 || c < 1 || r > mrows[k] || c > mcols[k]) throw new CalcError(3);
    }

    private int[] r0r1(int k) {
        int r = (int) reg[0], c = (int) reg[1];
        checkRC(k, r, c);
        return new int[]{r, c};
    }

    /** Avanza gli indici R0/R1 (modo USER); true se ha superato l'ultimo elemento. */
    private boolean advance(int k) {
        int r = (int) reg[0], c = (int) reg[1];
        c++;
        boolean wrap = false;
        if (c > mcols[k]) {
            c = 1;
            r++;
            if (r > mrows[k]) { r = 1; wrap = true; }
        }
        reg[0] = r;
        reg[1] = c;
        return wrap;
    }

    private double[][] toArr(int k) {
        double[][] a = new double[mrows[k]][mcols[k]];
        for (int i = 0; i < mrows[k]; i++)
            for (int j = 0; j < mcols[k]; j++) a[i][j] = mat[k][i * mcols[k] + j];
        return a;
    }

    private void fromArr(int k, double[][] a) {
        int r = a.length, c = r == 0 ? 0 : a[0].length;
        setDim(k, r, c);
        for (int i = 0; i < r; i++)
            for (int j = 0; j < c; j++) mat[k][i * c + j] = chk(a[i][j]);
    }

    private void dim(int arg) {
        finishEntry();
        lift = true;
        if (arg == A_IND) {
            int n = (int) Math.abs(re[0]);
            if (n < 1 || n > NREG) throw new CalcError(10);
            return;
        }
        int k = arg - A_MAT;
        int r = (int) Math.abs(re[1]), c = (int) Math.abs(re[0]);
        setDim(k, r, c);
    }

    private void resultDesc() {
        saveLast();
        drop();
        re[0] = 0;
        im[0] = 0;
        mt[0] = resultM + 1;
        lift = true;
    }

    private void matArith(char op) {
        int ky = mt[1] - 1, kx = mt[0] - 1;
        int R = resultM;
        double[][] out;
        if (ky >= 0 && kx >= 0) {
            double[][] A = toArr(ky), B = toArr(kx);
            switch (op) {
                case '+': case '-': {
                    if (mrows[ky] != mrows[kx] || mcols[ky] != mcols[kx]) throw new CalcError(11);
                    out = new double[A.length][A.length == 0 ? 0 : A[0].length];
                    for (int i = 0; i < A.length; i++)
                        for (int j = 0; j < A[0].length; j++) out[i][j] = op == '+' ? A[i][j] + B[i][j] : A[i][j] - B[i][j];
                    break;
                }
                case '*':
                    if (mcols[ky] != mrows[kx] || R == ky || R == kx) throw new CalcError(11);
                    out = mul(A, B);
                    break;
                default: // '/': X⁻¹ · Y
                    if (mrows[kx] != mcols[kx] || mrows[ky] != mrows[kx] || R == kx) throw new CalcError(11);
                    out = solveLinear(B, A);
                    break;
            }
        } else if (ky >= 0) {          // matrice in Y, numero in X
            double s = re[0];
            double[][] A = toArr(ky);
            out = new double[A.length][A.length == 0 ? 0 : A[0].length];
            for (int i = 0; i < A.length; i++)
                for (int j = 0; j < A[0].length; j++) out[i][j] = arith(op, A[i][j], s);
        } else {                        // numero in Y, matrice in X
            double s = re[1];
            double[][] B = toArr(kx);
            if (op == '/') {
                if (mrows[kx] != mcols[kx]) throw new CalcError(11);
                out = inverse(B);
                for (double[] row : out) for (int j = 0; j < row.length; j++) row[j] *= s;
            } else {
                out = new double[B.length][B.length == 0 ? 0 : B[0].length];
                for (int i = 0; i < B.length; i++)
                    for (int j = 0; j < B[0].length; j++) out[i][j] = arith(op, s, B[i][j]);
            }
        }
        fromArr(R, out);
        resultDesc();
    }

    private void matInverse() {
        int k = mt[0] - 1;
        if (mrows[k] != mcols[k] || mrows[k] == 0) throw new CalcError(11);
        double[][] inv = inverse(toArr(k));
        saveLast();
        fromArr(resultM, inv);
        re[0] = 0;
        mt[0] = resultM + 1;
        lift = true;
    }

    private static double[][] mul(double[][] A, double[][] B) {
        int n = A.length, m = B.length, p = m == 0 ? 0 : B[0].length;
        double[][] o = new double[n][p];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < p; j++) {
                double s = 0;
                for (int k = 0; k < m; k++) s += A[i][k] * B[k][j];
                o[i][j] = s;
            }
        return o;
    }

    /** Risolve A·X = B con eliminazione di Gauss a pivot parziale. */
    private static double[][] solveLinear(double[][] A0, double[][] B0) {
        int n = A0.length, m = B0.length == 0 ? 0 : B0[0].length;
        double[][] A = new double[n][];
        double[][] B = new double[n][];
        for (int i = 0; i < n; i++) { A[i] = A0[i].clone(); B[i] = B0[i].clone(); }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r;
            if (A[piv][col] == 0) throw new CalcError(0);
            double[] t = A[col]; A[col] = A[piv]; A[piv] = t;
            t = B[col]; B[col] = B[piv]; B[piv] = t;
            for (int r = col + 1; r < n; r++) {
                double f = A[r][col] / A[col][col];
                if (f == 0) continue;
                for (int c = col; c < n; c++) A[r][c] -= f * A[col][c];
                for (int c = 0; c < m; c++) B[r][c] -= f * B[col][c];
            }
        }
        double[][] X = new double[n][m];
        for (int c = 0; c < m; c++)
            for (int r = n - 1; r >= 0; r--) {
                double s = B[r][c];
                for (int k = r + 1; k < n; k++) s -= A[r][k] * X[k][c];
                X[r][c] = s / A[r][r];
            }
        return X;
    }

    private static double[][] inverse(double[][] A) {
        int n = A.length;
        double[][] I = new double[n][n];
        for (int i = 0; i < n; i++) I[i][i] = 1;
        return solveLinear(A, I);
    }

    private static double det(double[][] A0) {
        int n = A0.length;
        double[][] A = new double[n][];
        for (int i = 0; i < n; i++) A[i] = A0[i].clone();
        double d = 1;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r;
            if (A[piv][col] == 0) return 0;
            if (piv != col) { double[] t = A[col]; A[col] = A[piv]; A[piv] = t; d = -d; }
            d *= A[col][col];
            for (int r = col + 1; r < n; r++) {
                double f = A[r][col] / A[col][col];
                for (int c = col; c < n; c++) A[r][c] -= f * A[col][c];
            }
        }
        return d;
    }

    private static double[][] transpose(double[][] A) {
        int r = A.length, c = r == 0 ? 0 : A[0].length;
        double[][] t = new double[c][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) t[j][i] = A[i][j];
        return t;
    }

    private int needX() {
        finishEntry();
        if (mt[0] == 0) throw new CalcError(1);
        return mt[0] - 1;
    }

    private void matrix(int n) {
        finishEntry();
        switch (n) {
            case 0:
                for (int k = 0; k < 5; k++) { mat[k] = new double[0]; mrows[k] = 0; mcols[k] = 0; }
                break;
            case 1:
                reg[0] = 1;
                reg[1] = 1;
                break;
            case 2: {          // Zᴾ (2m×n) -> Z̃ (2m×2n)
                int k = needX();
                if (mrows[k] % 2 != 0) throw new CalcError(11);
                double[][] A = toArr(k);
                int m = A.length / 2, c = mcols[k];
                double[][] o = new double[2 * m][2 * c];
                for (int i = 0; i < m; i++)
                    for (int j = 0; j < c; j++) {
                        double a = A[i][j], b = A[i + m][j];
                        o[i][j] = a; o[i][j + c] = -b;
                        o[i + m][j] = b; o[i + m][j + c] = a;
                    }
                fromArr(k, o);
                break;
            }
            case 3: {          // Z̃ (2m×2n) -> Zᴾ (2m×n)
                int k = needX();
                if (mcols[k] % 2 != 0) throw new CalcError(11);
                double[][] A = toArr(k);
                int c = mcols[k] / 2;
                double[][] o = new double[A.length][c];
                for (int i = 0; i < A.length; i++) System.arraycopy(A[i], 0, o[i], 0, c);
                fromArr(k, o);
                break;
            }
            case 4: {
                int k = needX();
                fromArr(k, transpose(toArr(k)));
                break;
            }
            case 5: {          // Yᵀ · X
                int kx = needX();
                if (mt[1] == 0) throw new CalcError(1);
                int ky = mt[1] - 1;
                if (mrows[ky] != mrows[kx] || resultM == kx || resultM == ky) throw new CalcError(11);
                fromArr(resultM, mul(transpose(toArr(ky)), toArr(kx)));
                resultDesc();
                break;
            }
            case 6: {          // residuo: RESULT − Y · X
                int kx = needX();
                if (mt[1] == 0) throw new CalcError(1);
                int ky = mt[1] - 1;
                int R = resultM;
                if (R == kx || R == ky || mcols[ky] != mrows[kx] || mrows[R] != mrows[ky] || mcols[R] != mcols[kx])
                    throw new CalcError(11);
                double[][] p = mul(toArr(ky), toArr(kx)), r = toArr(R);
                for (int i = 0; i < r.length; i++) for (int j = 0; j < r[0].length; j++) r[i][j] -= p[i][j];
                fromArr(R, r);
                resultDesc();
                break;
            }
            case 7: {          // norma di riga
                int k = needX();
                double best = 0;
                for (int i = 0; i < mrows[k]; i++) {
                    double s = 0;
                    for (int j = 0; j < mcols[k]; j++) s += Math.abs(mat[k][i * mcols[k] + j]);
                    best = Math.max(best, s);
                }
                saveLast();
                re[0] = chk(best); mt[0] = 0; lift = true;
                break;
            }
            case 8: {          // norma di Frobenius
                int k = needX();
                double s = 0;
                for (double v : mat[k]) s += v * v;
                saveLast();
                re[0] = chk(Math.sqrt(s)); mt[0] = 0; lift = true;
                break;
            }
            default: {         // 9: determinante
                int k = needX();
                if (mrows[k] != mcols[k] || mrows[k] == 0) throw new CalcError(11);
                double d = det(toArr(k));
                saveLast();
                re[0] = chk(d); mt[0] = 0; lift = true;
                break;
            }
        }
    }

    /** Zᶜ (m×2n, coppie reale/immaginaria) -> Zᴾ (2m×n, prima le parti reali). */
    private void matCtoP(int k) {
        if (mcols[k] % 2 != 0) throw new CalcError(11);
        double[][] A = toArr(k);
        int m = A.length, n = mcols[k] / 2;
        double[][] o = new double[2 * m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) { o[i][j] = A[i][2 * j]; o[i + m][j] = A[i][2 * j + 1]; }
        fromArr(k, o);
    }

    private void matPtoC(int k) {
        if (mrows[k] % 2 != 0) throw new CalcError(11);
        double[][] A = toArr(k);
        int m = A.length / 2, n = mcols[k];
        double[][] o = new double[m][2 * n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) { o[i][2 * j] = A[i][j]; o[i][2 * j + 1] = A[i + m][j]; }
        fromArr(k, o);
    }

    // ==================================================================
    // Visualizzazione
    // ==================================================================
    public String display() {
        if (error != null) return error;
        if (prgm) return lineText(pc);
        if (entering) return entryDisplay();
        if (mt[0] != 0) return descText(mt[0] - 1);
        return format(re[0]);
    }

    private String descText(int k) {
        return String.format(Locale.ROOT, "%-5s%3d%4d", String.valueOf((char) ('A' + k)), mrows[k], mcols[k]);
    }

    public String lineText(int line) {
        if (line <= 0 || line > prog.size()) return String.format(Locale.ROOT, "%03d-", Math.max(0, line));
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%03d-", line));
        int[] c = prog.get(line - 1).codes;
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < c.length; i++) {
            if (i > 0) codes.append(',');
            int d = digitOf(c[i]);
            codes.append(d >= 0 ? " " + d : String.format(Locale.ROOT, "%02d", c[i]));
        }
        // allinea a destra come sul display originale
        int pad = Math.max(0, 9 - codes.length());
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.append(codes).toString();
    }

    private String memText() {
        int free = MAXLINES - prog.size();
        return String.format(Locale.ROOT, "P%03d  L%03d", prog.size(), free);
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
        if (v == 0) v = 0;
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
        if (e > 99) {
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

    /** Le 10 cifre significative di X, come CLEAR PREFIX. */
    public String mantissa() {
        double x = re[0];
        if (x == 0) return "0000000000";
        BigDecimal bd = new BigDecimal(Math.abs(x)).round(new MathContext(10, RoundingMode.HALF_UP));
        String u = bd.unscaledValue().toString();
        return (u + "0000000000").substring(0, 10);
    }

    // accessori per interfaccia e test
    public double x() { return re[0]; }
    public double y() { return re[1]; }
    public double z() { return re[2]; }
    public double t() { return re[3]; }
    public double xi() { return im[0]; }
    public boolean complex() { return flag[8]; }
    public int programLines() { return prog.size(); }
    public int pc() { return pc; }

    // ==================================================================
    // Salvataggio dello stato
    // ==================================================================
    private static final char FS = '\u001F', LS = '\u001E';

    private static String join(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.toString();
    }

    private static String joinI(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.toString();
    }

    private static double[] splitD(String s) {
        if (s.isEmpty()) return new double[0];
        String[] p = s.split(",");
        double[] a = new double[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Double.parseDouble(p[i]);
        return a;
    }

    private static int[] splitI(String s) {
        if (s.isEmpty()) return new int[0];
        String[] p = s.split(",");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i]);
        return a;
    }

    public String serialize() {
        finishEntry();
        StringBuilder sb = new StringBuilder("v2\n");
        sb.append("re=").append(join(re)).append('\n');
        sb.append("im=").append(join(im)).append('\n');
        sb.append("mt=").append(joinI(mt)).append('\n');
        sb.append("last=").append(lre).append(',').append(lim).append(',').append(lmt).append('\n');
        sb.append("reg=").append(join(reg)).append('\n');
        sb.append("regt=").append(joinI(regTag)).append('\n');
        sb.append("ri=").append(regI).append(',').append(regITag).append('\n');
        StringBuilder fl = new StringBuilder();
        for (boolean b : flag) fl.append(b ? '1' : '0');
        sb.append("flags=").append(fl).append('\n');
        sb.append("mode=").append(joinI(new int[]{lift ? 1 : 0, fmt, digits, angle, comma ? 1 : 0, user ? 1 : 0, resultM, pc})).append('\n');
        for (int k = 0; k < 5; k++) {
            sb.append("m").append(k).append('=').append(mrows[k]).append(',').append(mcols[k]);
            for (double v : mat[k]) sb.append(',').append(v);
            sb.append('\n');
        }
        sb.append("prog=");
        for (int i = 0; i < prog.size(); i++) {
            Instr in = prog.get(i);
            if (i > 0) sb.append(LS);
            sb.append(in.op).append(FS).append(in.arg).append(FS).append((int) in.ar).append(FS).append(joinI(in.codes));
        }
        sb.append('\n');
        return sb.toString();
    }

    public void restore(String s) {
        if (s == null) return;
        if (!s.startsWith("v2\n")) return;   // formati precedenti: si riparte da zero
        try {
            Map<String, String> m = new HashMap<>();
            for (String line : s.split("\n")) {
                int e = line.indexOf('=');
                if (e > 0) m.put(line.substring(0, e), line.substring(e + 1));
            }
            System.arraycopy(splitD(m.get("re")), 0, re, 0, 4);
            System.arraycopy(splitD(m.get("im")), 0, im, 0, 4);
            System.arraycopy(splitI(m.get("mt")), 0, mt, 0, 4);
            double[] l = splitD(m.get("last"));
            lre = l[0]; lim = l[1]; lmt = (int) l[2];
            double[] r = splitD(m.get("reg"));
            System.arraycopy(r, 0, reg, 0, Math.min(r.length, NREG));
            int[] rt = splitI(m.get("regt"));
            System.arraycopy(rt, 0, regTag, 0, Math.min(rt.length, NREG));
            double[] ri = splitD(m.get("ri"));
            regI = ri[0]; regITag = (int) ri[1];
            String fl = m.get("flags");
            for (int i = 0; i < 10 && i < fl.length(); i++) flag[i] = fl.charAt(i) == '1';
            int[] md = splitI(m.get("mode"));
            lift = md[0] == 1; fmt = md[1]; digits = md[2]; angle = md[3];
            comma = md[4] == 1; user = md[5] == 1; resultM = md[6];
            for (int k = 0; k < 5; k++) {
                double[] a = splitD(m.get("m" + k));
                mrows[k] = (int) a[0];
                mcols[k] = (int) a[1];
                mat[k] = Arrays.copyOfRange(a, 2, a.length);
            }
            prog.clear();
            String p = m.get("prog");
            if (p != null && !p.isEmpty()) {
                for (String line : p.split(String.valueOf(LS))) {
                    String[] f = line.split(String.valueOf(FS), -1);
                    prog.add(new Instr(f[0], Integer.parseInt(f[1]), (char) Integer.parseInt(f[2]), splitI(f[3])));
                }
            }
            pc = Math.min(md[7], prog.size());
        } catch (RuntimeException e) {
            reset();
        }
    }

    public void reset() {
        Arrays.fill(re, 0);
        Arrays.fill(im, 0);
        Arrays.fill(mt, 0);
        lre = lim = 0;
        lmt = 0;
        Arrays.fill(reg, 0);
        Arrays.fill(regTag, 0);
        regI = 0;
        regITag = 0;
        Arrays.fill(flag, false);
        lift = true;
        entering = false;
        fmt = FIX;
        digits = 4;
        angle = DEG;
        user = false;
        prgm = false;
        shift = 0;
        cancelPfx();
        error = null;
        prog.clear();
        pc = 0;
        rets.clear();
        for (int k = 0; k < 5; k++) { mat[k] = new double[0]; mrows[k] = 0; mcols[k] = 0; }
        resultM = 0;
    }
}
