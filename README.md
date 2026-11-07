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

Il manuale completo in italiano è in [`docs/RPN-15_Manuale.pdf`](docs/RPN-15_Manuale.pdf).

Funzioni disponibili: stack XYZT con LSTx, funzioni scientifiche e iperboliche, conversioni,
statistica e regressione, 64 registri con indirizzamento indiretto (I e (i)), programmazione
(448 righe, etichette, sottoprogrammi, test, ISG/DSE, flag, USER, SST/BST, PSE),
SOLVE, integrazione numerica, numeri complessi e matrici A–E (sistemi lineari, inversa,
determinante, norme, trasposta).

- Il tasto **ON** apre un menu: separatore decimale, azzeramento, informazioni.
- **Tieni premuto il display** per copiare X negli appunti.
- Qualunque tasto ferma un programma in esecuzione.
- Tutto lo stato (programmi compresi) viene salvato alla chiusura.

## Struttura

- `app/src/main/java/it/rpn15/Calc.java` – motore di calcolo (indipendente da Android)
- `app/src/main/java/it/rpn15/MainActivity.java` – interfaccia
- `docs/` – manuale PDF e relativo sorgente HTML
- `.github/workflows/build.yml` – compilazione automatica dell'APK su GitHub
