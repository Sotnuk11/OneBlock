import sys, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import math, numpy as np
from common import *
n = np.load('../v8/new.npz'); TOP = n['TOP']
def g(x, z): return int(TOP[int(z), int(x)])
# --- мініпідземелля: (id, назва, рівні, вхід, схема, чи перший набір, кімнати) ; кімната: (назва, cx, cz, w, d, floor, h)
MINI = [
    ("loh", "Льох під корінням", "2–5", (610, 1120), "А: кишеня під орієнтиром", True,
     [("горло й сходи", 610, 1128, 4, 10, 62, 5), ("тунель", 618, 1140, 4, 14, 54, 5), ("бойове ядро", 628, 1158, 14, 18, 44, 9), ("схованка", 642, 1150, 6, 8, 46, 5), ("хід назад", 616, 1146, 3, 12, 52, 4)]),
    ("trium", "Трюм у скелі", "8–13", (292, 668), "Б: два рівні гроту", True,
     [("вхід-розлом", 292, 668, 4, 8, 58, 6), ("верхній балкон", 282, 660, 10, 8, 46, 6), ("корінь-міст / полиця", 272, 652, 6, 14, 44, 4), ("нижня зала, корпус корабля", 276, 668, 18, 22, 34, 13), ("головна скриня", 268, 676, 6, 6, 35, 5)]),
    ("usyp", "Забута усипальниця", "36–40", (1335, 830), "В: склеп із видимою метою", True,
     [("сходи з руїни", 1335, 830, 4, 10, 58, 5), ("вид на скриню за ґратами", 1335, 846, 8, 6, 50, 6), ("коридор ніш", 1335, 866, 6, 24, 48, 7), ("бічна зала з механізмом", 1350, 878, 10, 10, 48, 8), ("поховальна камера", 1335, 892, 12, 12, 46, 10)]),
    ("komora", "Покинута комора", "5–10", (650, 975), "А", False, []),
    ("vidvid", "Старий відвід води", "10–14", (560, 690), "водозбір", False, []),
    ("shtolnia", "Завалена штольня", "15–20", (540, 330), "копальня", False, []),
    ("kistiana", "Кістяна кишеня", "18–23", (1080, 1180), "тріщина → зала", False, []),
    ("korin", "Кореневий грот", "22–28", (1105, 965), "Б", False, []),
    ("lodova", "Льодова варта", "27–32", (915, 385), "висічений пост", False, []),
    ("horn", "Охололий горн", "31–36", (1120, 480), "лавова трубка", False, []),
]
STORY = [("Лавові глибини (з кратера)", (1234, 386), 72, -24), ("Крипта собору", (1260, 660), 48, 34)]
s = SVG(); W0 = 900; SC = W0 / 1536; OX, OY = 20, 70
P = lambda x, z: (OX + x * SC, OY + z * SC)
s.a('<text x="20" y="32" font-size="22" font-weight="bold">Острів 1, v9 — прев\'ю підземного шару: Русло Джерел, мініпідземелля, сюжетні зали</text>')
s.a('<text x="20" y="54" font-size="13" fill="#444">Основа — прев\'ю v9 згори (притінене). Під землею лише авторські об\'єми, природної мережі печер немає. Кожне місце — окремий вхід і власне завершення</text>')
s.a(f'<image x="{OX}" y="{OY}" width="{W0}" height="{W0}" href="data:image/png;base64,{b64file("../../../../../../../OneBlock/Нотатки/05-Дослідження/karta/v9-prevyu-zgory-900.png") if False else b64file("prev900.png")}"/>')
s.a(f'<rect x="{OX}" y="{OY}" width="{W0}" height="{W0}" fill="#0b1630" opacity="0.55"/>')
# Русло
s.poly([P(*p) for p in RIVER_UG], closed=False, stroke='#3aa0ff', stroke_width='5')
s.poly([P(*r[2]) for r in ROOMS], closed=False, stroke='#ffd21e', stroke_width='3', stroke_dasharray='7 4')
s.poly([P(*p) for p in BACK], closed=False, stroke='#cccccc', stroke_width='1.5', stroke_dasharray='3 3')
e = P(*ENTR); s.a(f'<circle cx="{e[0]}" cy="{e[1]}" r="9" fill="#ff3b3b" stroke="#fff" stroke-width="2"/>')
s.txt(e[0] + 12, e[1] + 22, 'Русло Джерел · 12–16 · ЄДИНИЙ вхід', 12, '#ffd21e', w='bold', halo=False)
gr = P(*GROTTO); s.txt(gr[0] + 6, gr[1] - 10, 'грот: ґрати, з моря не пройти', 11, '#9fd0ff', halo=False)
for nm, (x, z), r, fy in STORY:
    p = P(x, z); s.a(f'<circle cx="{p[0]}" cy="{p[1]}" r="{r*SC}" fill="#ff6a00" fill-opacity="0.25" stroke="#ff8a3a" stroke-width="2"/>')
    s.txt(p[0], p[1] + r * SC + 14, f'{nm} · Y {fy}', 11, '#ffb070', 'middle', halo=False)
for mid, nm, lv, (x, z), sch, first, rooms in MINI:
    p = P(x, z)
    if first:
        for rn, cx, cz, w, d, fy, h in rooms:
            q = P(cx - w / 2, cz - d / 2); s.a(f'<rect x="{q[0]}" y="{q[1]}" width="{w*SC}" height="{d*SC}" fill="#b8ff6a" fill-opacity="0.7" stroke="#fff" stroke-width="0.8"/>')
        s.a(f'<circle cx="{p[0]}" cy="{p[1]}" r="7" fill="#b8ff6a" stroke="#fff" stroke-width="2"/>')
        s.txt(p[0] + 10, p[1] - 8, f'{nm} · {lv}', 12, '#d8ff9a', w='bold', halo=False)
    else:
        s.a(f'<circle cx="{p[0]}" cy="{p[1]}" r="6" fill="none" stroke="#d8d8d8" stroke-width="2" stroke-dasharray="2 2"/>')
        s.txt(p[0] + 9, p[1] + 4, f'{nm} · {lv}', 11, '#d8d8d8', halo=False)
# легенда
lx, ly = OX + W0 + 20, OY
for col, t in (('#ffd21e', 'маршрут Русла (кімнати 0–7)'), ('#3aa0ff', 'підземна річка'), ('#ff3b3b', 'вхід у Русло'), ('#b8ff6a', 'мініпідземелля — перший набір (3 прототипи)'),
               ('#d8d8d8', 'мініпідземелля — кандидати на потім (7)'), ('#ff8a3a', 'сюжетні зали своїх зон (лишаються)')):
    s.a(f'<rect x="{lx}" y="{ly}" width="22" height="10" fill="{col}"/>'); s.txt(lx + 30, ly + 10, t, 12, halo=False); ly += 22
ly += 10
for t in ['Прибрано зі старої v8: Перша, Грибна, Кришталева печери,', 'Стара копальня, Безодня, схованки, тунелі між ними', 'й випадкові отвори (рішення користувача).', '',
          'Правило: між місцями немає спільної мережі;', 'жодне мініпідземелля не обходить брам,', 'тунелю чи гроту Русла.']:
    s.txt(lx, ly, t, 12, '#333', halo=False); ly += 17
# розрізи перших трьох
oy = OY + W0 + 40
s.a(f'<text x="20" y="{oy-12}" font-size="16" font-weight="bold">Розрізи трьох прототипів (поверхня — справжня з генератора; 1 блок = 4 пікс. по висоті, 2 по довжині)</text>')
cw = (W0 + 380 - 40) / 3; ch = 230
for k, (mid, nm, lv, ent, sch, first, rooms) in enumerate([m for m in MINI if m[5]]):
    ox = OX + k * (cw + 20); s.a(f'<rect x="{ox}" y="{oy}" width="{cw}" height="{ch}" fill="#f2efe8" stroke="#999"/>')
    zs = [r[2] - r[4] / 2 for r in rooms] + [r[2] + r[4] / 2 for r in rooms]; z0, z1 = min(zs) - 12, max(zs) + 12
    xc = ent[0]; kx = (cw - 20) / (z1 - z0); ys = [r[5] for r in rooms]; ymin = min(ys) - 6
    ymax = max(g(xc, z) for z in np.arange(z0, z1)) + 6
    ky = min(4.0, (ch - 30) / (ymax - ymin))
    X = lambda z: ox + 10 + (z - z0) * kx; Y = lambda y: oy + ch - 8 - (y - ymin) * ky
    surf = [(z, max(g(r[1], z) for r in rooms if abs(r[1] - xc) < 30) if False else g(xc, z)) for z in np.arange(z0, z1, 1.0)]
    pts = ' '.join(f'{X(z):.1f},{Y(t):.1f}' for z, t in surf)
    s.a(f'<polygon points="{X(z0)},{oy+ch} {pts} {X(z1-1)},{oy+ch}" fill="#a39e94"/>')
    s.a('<polyline points="' + pts + '" fill="none" stroke="#3b7a2a" stroke-width="2.5"/>')
    if min(t for _, t in surf) <= 63: s.a(f'<line x1="{ox}" y1="{Y(63)}" x2="{ox+cw}" y2="{Y(63)}" stroke="#2a6ad0" stroke-dasharray="4 3"/>')
    cover = []
    for rn, cx, cz, w, d, fy, h in rooms:
        s.a(f'<rect x="{X(cz-d/2):.1f}" y="{Y(fy+h):.1f}" width="{d*kx:.1f}" height="{h*ky:.1f}" fill="#2a2420" stroke="#ffd21e" stroke-width="1"/>')
        top_here = min(g(cx, z) for z in range(int(cz - d / 2), int(cz + d / 2) + 1)); cv = top_here - (fy + h); cover.append((rn, cv))
    s.txt(ox + 8, oy + 16, f'{nm} · рівні {lv} · {sch}', 12.5, w='bold', halo=False)
    worst = min(c for r, c in cover if r not in ("горло й сходи", "вхід-розлом", "сходи з руїни"))
    s.txt(ox + 8, oy + 32, f'найтонша порода над залою: {worst} бл.' + (' — замало, опустити' if worst < 8 else ''), 11.5, '#a00' if worst < 8 else '#264', halo=False)
    yy = oy + 48
    for rn, cx, cz, w, d, fy, h in rooms:
        s.txt(ox + 8, yy, f'· {rn}: {w}×{d}, підлога Y {fy}, висота {h}', 10.5, '#333', halo=False); yy += 13
H = oy + ch + 40
s.txt(20, H - 14, 'Розміри — за пропорціями з нотатки 22 (розд. 11.1): горло 3–5, тунель 8–18, бойове ядро 14×18…18×24 при висоті 7–12, схованка 4×6…6×8, спуск 8–20. Плани — початкові, перевіряти в клієнті.', 12, '#333', halo=False)
s.html(OX + W0 + 380, H, 'under.html')
print('ok', OX + W0 + 380, H)
