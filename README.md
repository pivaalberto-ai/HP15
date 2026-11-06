# RPN-15

Calcolatrice RPN per Android ispirata alla HP-15C: stack a 4 livelli, tasti f/g,
funzioni scientifiche e statistiche. Codice Java puro, nessuna libreria esterna.

## Come ottenere l'APK

### Opzione A: GitHub (nessun programma da installare)
1. Crea un repository su github.com e carica tutto il contenuto di questa cartella
   (anche la cartella nascosta `.github`).
2. Al caricamento parte da solo il workflow **Build APK** (scheda *Actions*, ~4 minuti).
3. Nella scheda **Releases** del repository trovi `RPN-15.apk`: aprilo dal telefono
   e installalo (Android chiederà di consentire l'installazione da "origini sconosciute").

### Opzione B: Android Studio
1. *File → Open* e scegli questa cartella.
2. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
3. L'APK è in `app/build/outputs/apk/debug/app-debug.apk`.

## Uso

La calcolatrice si apre in orizzontale. Ogni tasto ha tre funzioni:
la **primaria** (sul tasto), la funzione **f** (arancione, sopra il tasto)
e la funzione **g** (verde, sul tasto in basso). Premi f o g e poi il tasto.

| Cosa | Funzioni |
|---|---|
| Stack | ENTER, R↓, R↑, x↔y, CLx, ←, LSTx |
| Aritmetica | + − × ÷, yˣ, √x, x², 1/x, %, Δ%, CHS, EEX |
| Esponenziali | eˣ, LN, 10ˣ, LOG |
| Trigonometria | SIN COS TAN e inverse, HYP / HYP⁻¹, DEG/RAD/GRD, π |
| Conversioni | →P, →R, →H.MS, →H, →RAD, →DEG |
| Numeri | ABS, INT, FRAC, RND, x! (anche non interi), Py,x, Cy,x, RAN# |
| Registri | STO/RCL 0–9 e .0–.9, aritmetica nei registri (STO + 3, RCL × 5…) |
| Statistica | Σ+, Σ−, x̄, s, L.R., ŷ,r, CLΣ, RCL Σ+ (registri R2–R7 come sull'originale) |
| Display | FIX/SCI/ENG n, separatori delle migliaia, PREFIX mostra le 10 cifre |

- Il tasto **ON** apre un menu: separatore decimale punto/virgola, azzeramento, informazioni.
- **Tieni premuto il display** per copiare X negli appunti.
- Stack, registri e impostazioni vengono salvati alla chiusura.

Le funzioni sbiadite (programmazione, SOLVE, ∫, matrici, numeri complessi) non sono
ancora implementate e mostrano un avviso.

## Struttura

- `app/src/main/java/it/rpn15/Calc.java` – motore di calcolo (indipendente da Android)
- `app/src/main/java/it/rpn15/MainActivity.java` – interfaccia
- `.github/workflows/build.yml` – compilazione automatica dell'APK su GitHub
