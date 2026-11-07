import html, pathlib
K = {
11:("√x","A","x²"),12:("eˣ","B","LN"),13:("10ˣ","C","LOG"),14:("yˣ","D","%"),15:("1/x","E","Δ%"),
16:("CHS","MATRIX","ABS"),17:("7","FIX","DEG"),18:("8","SCI","RAD"),19:("9","ENG","GRD"),10:("÷","SOLVE","x≤y"),
21:("SST","LBL","BST"),22:("GTO","HYP","HYP⁻¹"),23:("SIN","DIM","SIN⁻¹"),24:("COS","(i)","COS⁻¹"),25:("TAN","I","TAN⁻¹"),
26:("EEX","RESULT","π"),27:("4","x↔","SF"),28:("5","DSE","CF"),29:("6","ISG","F?"),20:("×","∫xy","x=0"),
31:("R/S","PSE","P/R"),32:("GSB","CLΣ","RTN"),33:("R↓","CLPRG","R↑"),34:("x↔y","CLREG","RND"),35:("←","PREFIX","CLx"),
36:("ENTER","RAN#","LSTx"),37:("1","→R","→P"),38:("2","→H.MS","→H"),39:("3","→RAD","→DEG"),30:("−","Re↔Im","TEST"),
41:("ON","",""),42:("f","",""),43:("g","",""),44:("STO","FRAC","INT"),45:("RCL","USER","MEM"),
47:("0","x!","x̄"),48:(".","ŷ,r","s"),49:("Σ+","L.R.","Σ−"),40:("+","Py,x","Cy,x")}
def key(code, rowspan=1):
    p,f,g = K[code]
    cls = "key"
    if code==42: cls+=" kf"
    if code==43: cls+=" kg"
    if p.isdigit() or p==".": cls+=" kn"
    style = f' style="grid-row: span {rowspan}"' if rowspan>1 else ""
    return (f'<div class="cell"{style}><div class="fl">{html.escape(f)}</div>'
            f'<div class="{cls}"><span class="pl">{html.escape(p)}</span><span class="gl">{html.escape(g)}</span>'
            f'<span class="code">{code}</span></div></div>')
cells=[]
for r in range(1,5):
    for c in [1,2,3,4,5,6,7,8,9,0]:
        code=r*10+c
        if c==6 and r==3: cells.append(key(36,2)); continue
        if c==6 and r==4: continue
        cells.append(key(code))
keyboard = '<div class="kbd">' + "".join(cells) + '</div>'

body = pathlib.Path("/home/claude/manual/body.html").read_text()
css = pathlib.Path("/home/claude/manual/style.css").read_text()
import re
def kspan(m):
    t=m.group(1); cls="k f" if t=="f" else "k g" if t=="g" else "k"
    return f'<span class="{cls}">{t}</span>'
body = re.sub(r"\[\[(.+?)\]\]", kspan, body)
body = re.sub(r"\{\{lcd:(.+?)\}\}", lambda m: f'<span class="lcd">{m.group(1)}</span>', body)
out = f'<!doctype html><html lang="it"><head><meta charset="utf-8"><title>RPN-15 – Manuale</title><style>{css}</style></head><body>{body.replace("{{KEYBOARD}}", keyboard)}</body></html>'
pathlib.Path("/home/claude/manual/manuale.html").write_text(out)
