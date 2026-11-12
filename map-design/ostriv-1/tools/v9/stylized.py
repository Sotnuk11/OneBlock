import sys, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, math
from common import *
n = np.load('../v8/new.npz'); TOP = n['TOP'].astype(float); WL = n['WL']; tL = n['tL']; RID = n['RIDGEH']; VM = n['VOLC_M']; LL = n['LL']
R = 2; T = TOP[::R, ::R]; G = np.mgrid[0:1536:R, 0:1536:R]; Zw, Xw = G[0] + 0.5, G[1] + 0.5
land = T >= 63
SEEDS = {1: [(450, 1050), (520, 1170), (360, 1120), (600, 1100)], 2: [(680, 940), (640, 1000)], 3: [(262, 740), (250, 870), (220, 980), (330, 800)],
         5: [(470, 365), (380, 430), (560, 330), (300, 520)], 6: [(960, 1240), (1110, 1200), (860, 1200), (1300, 1000)], 7: [(840, 640), (1000, 800)],
         8: [(615, 226), (760, 250), (700, 350)], 9: [(1150, 330)], 10: [(1300, 760)]}
best = np.full(T.shape, 1e9); zid = np.zeros(T.shape, np.int16)
for k, pts in SEEDS.items():
    for x, z in pts:
        d = np.hypot(Xw - x, Zw - z); u = d < best; best[u] = d[u]; zid[u] = k
def inpoly(px, pz, poly):
    ins = np.zeros(px.shape, bool); j = len(poly) - 1
    for i in range(len(poly)):
        xi, zi = poly[i]; xj, zj = poly[j]
        c = ((zi > pz) != (zj > pz)) & (px < (xj - xi) * (pz - zi) / (zj - zi + 1e-9) + xi); ins ^= c; j = i
    return ins
zid[inpoly(Xw, Zw, FOREST)] = 7
zid[RID[::R, ::R] > 16] = 8
zid[VM[::R, ::R]] = 9
zid[np.hypot(Xw - 1265, Zw - 680) < 135] = 10
COL = {1: (192, 218, 142), 2: (220, 230, 168), 3: (184, 220, 196), 5: (140, 184, 150), 6: (226, 176, 134), 7: (92, 150, 88), 8: (236, 240, 247),
       9: (186, 96, 78), 10: (156, 134, 176)}
img = np.zeros(T.shape + (3,)); img[:] = (166, 204, 222)
for k, c in COL.items(): img[(zid == k) & land] = c
core = np.hypot(Xw - TREE[0], Zw - TREE[1]) < CORE_R; img[core & land] = (60, 118, 66)
beach = land & (tL[::R, ::R] > 0.93) & (zid != 9); img[beach] = (236, 222, 170)
water = (WL[::R, ::R] > T) & land; img[water] = (96, 160, 214)
img[(LL[::R, ::R] > -999)] = (250, 130, 40)
gz, gx = np.gradient(T); shade = np.clip(1 + (-gx - gz) * 0.06, 0.8, 1.15)[..., None]
img = np.where(land[..., None], img * shade, img)
edge = (np.roll(zid, 1, 0) != zid) | (np.roll(zid, 1, 1) != zid); img[edge & land & ~water] = img[edge & land & ~water] * 0.55
coast = land & ~np.roll(land, 2, 0) | land & ~np.roll(land, -2, 0) | land & ~np.roll(land, 2, 1) | land & ~np.roll(land, -2, 1)
img[coast] = (120, 100, 70)
img = np.clip(img, 0, 255).astype(np.uint8)
S_ = 900 / 768; OX, OY = 30, 80
P = lambda x, z: (OX + x / R * S_, OY + z / R * S_)
s = SVG()
s.a(f'<rect x="0" y="0" width="100%" height="100%" fill="#efe4c4"/>')
s.a('<text x="30" y="40" font-size="26" font-weight="bold" fill="#3a2a10" font-family="Georgia, serif">Острів 1 — схематична карта v9 (рівні 1–40)</text>')
s.a('<text x="30" y="62" font-size="13" fill="#5a4a30">У стилі карти Alfheim (SAO): ліс ельфів у центрі, хребет-кільце з проходами, кожна зона — свій колір і свій знак</text>')
s.a(f'<rect x="{OX-6}" y="{OY-6}" width="912" height="912" fill="none" stroke="#7a5a30" stroke-width="3" rx="6"/>')
s.a(f'<image x="{OX}" y="{OY}" width="900" height="900" href="data:image/png;base64,{b64img(img)}"/>')
for k in range(14):   # хвилі
    x, y = OX + 40 + (k * 157) % 820, OY + 30 + (k * 263) % 840
    if not land[min(int((y - OY) / S_), 767), min(int((x - OX) / S_), 767)]:
        s.a(f'<path d="M{x},{y} q6,-5 12,0 t12,0" fill="none" stroke="#5f8fb0" stroke-width="1.6"/>')
# значки
def mountains(x, z, w=26, col='#f7f9fc'):
    p = P(x, z); s.a(f'<path d="M{p[0]-w/2},{p[1]+8} L{p[0]-w/6},{p[1]-12} L{p[0]+2},{p[1]+2} L{p[0]+w/5},{p[1]-16} L{p[0]+w/2},{p[1]+8}Z" fill="{col}" stroke="#6a6a7a" stroke-width="1.5"/>')
for x in range(360, 1120, 70):
    z = 600 - (x - 150) * 0.2; mountains(x, z - 30 if x > 600 else z - 20)
p = P(1234, 386); s.a(f'<path d="M{p[0]-34},{p[1]+22} L{p[0]-10},{p[1]-14} L{p[0]+10},{p[1]-14} L{p[0]+34},{p[1]+22}Z" fill="#5a2a22" stroke="#2a1010" stroke-width="2"/><ellipse cx="{p[0]}" cy="{p[1]-14}" rx="10" ry="3" fill="#ff8a20"/><path d="M{p[0]},{p[1]-18} q-10,-14 0,-26 q10,-12 0,-24" fill="none" stroke="#777" stroke-width="5" opacity="0.6"/>')
p = P(1265, 670); s.a(f'<path d="M{p[0]-14},{p[1]+16} L{p[0]-14},{p[1]-6} L{p[0]},{p[1]-34} L{p[0]+14},{p[1]-6} L{p[0]+14},{p[1]+16}Z M{p[0]-24},{p[1]+16} L{p[0]-24},{p[1]} L{p[0]-19},{p[1]-12} L{p[0]-14},{p[1]} " fill="#2a2036" stroke="#c9a8ff" stroke-width="1.5"/>')
p = P(450, 1050)
for dx in (-16, 0, 16): s.a(f'<rect x="{p[0]+dx-5}" y="{p[1]-(18 if dx == 0 else 10)}" width="10" height="{24 if dx == 0 else 16}" fill="#d8cdb8" stroke="#5a4a3a"/><path d="M{p[0]+dx-6},{p[1]-(18 if dx == 0 else 10)} L{p[0]+dx},{p[1]-(28 if dx == 0 else 18)} L{p[0]+dx+6},{p[1]-(18 if dx == 0 else 10)}Z" fill="#b04a3a"/>')
p = P(*MARKET)
for k, c in enumerate(('#e85a4a', '#f0c040', '#4a9ae8')): s.a(f'<path d="M{p[0]-22+k*14},{p[1]} l7,-10 l7,10 z" fill="{c}" stroke="#5a3a20"/>')
p = P(*TREE)   # Велике Дерево — найбільший знак, виходить за межі лісу
s.a(f'<path d="M{p[0]-7},{p[1]+18} C{p[0]-5},{p[1]} {p[0]-14},{p[1]-20} {p[0]-3},{p[1]-40} M{p[0]+7},{p[1]+18} C{p[0]+5},{p[1]} {p[0]+14},{p[1]-20} {p[0]+3},{p[1]-40}" fill="none" stroke="#7a5a30" stroke-width="7"/>')
for dx, dy, r in ((0, -62, 34), (-30, -48, 24), (30, -48, 24), (-16, -80, 22), (18, -82, 22)):
    s.a(f'<circle cx="{p[0]+dx}" cy="{p[1]+dy}" r="{r}" fill="#7fc46a" stroke="#2f6a2a" stroke-width="2" opacity="0.95"/>')
s.a(f'<circle cx="{p[0]}" cy="{p[1]-60}" r="5" fill="#fff6b0"/>')
for x, z in ((430, 380), (500, 350), (470, 330)):
    q = P(x, z); s.a(f'<path d="M{q[0]},{q[1]-14} l7,14 h-14 z" fill="#2f6a4a"/>')
for x, z in ((250, 870), (615, 226)):
    q = P(x, z); s.a(f'<path d="M{q[0]-12},{q[1]} h24 l-4,6 h-16 z M{q[0]},{q[1]} v-16 l9,10 h-9" fill="#8a5a30" stroke="#3a2a10" stroke-width="1.2"/>')
q = P(232, 700); s.a(f'<rect x="{q[0]-3}" y="{q[1]-18}" width="6" height="18" fill="#fff" stroke="#a03030"/><circle cx="{q[0]}" cy="{q[1]-20}" r="3" fill="#ffd040"/>')
q = P(1050, 1230); s.a(f'<path d="M{q[0]-20},{q[1]+8} l6,-14 h14 l6,14 z" fill="#c8784a" stroke="#6a3a20"/>')
q = P(*GATE); s.a(f'<path d="M{q[0]-6},{q[1]+6} v-8 q6,-8 12,0 v8" fill="none" stroke="#fff" stroke-width="3"/>')
q = P(690, 520); s.a(f'<path d="M{q[0]-6},{q[1]+5} v-6 q6,-8 12,0 v6 z" fill="#222" stroke="#fff"/>')
q = P(*ENTR); s.a(f'<circle cx="{q[0]}" cy="{q[1]}" r="7" fill="#222" stroke="#ff7a00" stroke-width="3"/>')
q = P(*GROTTO); s.a(f'<path d="M{q[0]-7},{q[1]+5} q7,-14 14,0" fill="#222" stroke="#fff" stroke-width="1.5"/>')
s.poly([P(*p) for p in RIVER_UG], closed=False, stroke='#3a7ad0', stroke_width='2', stroke_dasharray='4 3')
# стрічки-назви
def banner(x, z, t, dx=0, dy=0, col='#2f6a3a'):
    p = P(x, z); X_, Y_ = p[0] + dx, p[1] + dy; w = 8 + len(t) * 7.3
    s.a(f'<rect x="{X_-w/2}" y="{Y_-12}" width="{w}" height="22" rx="4" fill="{col}" stroke="#fff" stroke-width="1.5"/>')
    s.txt(X_, Y_ + 4, t, 13, '#fff', 'middle', 'bold', halo=False)
for n_, nm, lv, (x, z), _ in ZONES:
    off = {1: (0, 34), 2: (-90, 56), 3: (0, 36), 4: (0, 0), 5: (0, -40), 6: (0, 34), 7: (80, 60), 8: (60, -40), 9: (0, 52), 10: (0, 44)}[n_]
    if n_ == 4: continue
    banner(x, z, f'{n_} · {nm.split(" (")[0].split(" →")[0]} · {lv}', *off, col='#7a3a8a' if n_ == 10 else '#a03a2a' if n_ == 9 else '#2f6a3a')
def callout(x, z, t, dx, dy):
    p = P(x, z); s.a(f'<line x1="{p[0]}" y1="{p[1]}" x2="{p[0]+dx}" y2="{p[1]+dy}" stroke="#d06000" stroke-width="1.5"/>')
    w = 8 + len(t) * 6.6; X_, Y_ = p[0] + dx, p[1] + dy
    s.a(f'<rect x="{X_-w/2}" y="{Y_-11}" width="{w}" height="20" rx="3" fill="#ff8a1e" stroke="#fff" stroke-width="1.2"/>')
    s.txt(X_, Y_ + 4, t, 12, '#fff', 'middle', 'bold', halo=False)
callout(*ENTR, '4 · Брама Джерел (підземелля 12–16)', 30, 46)
callout(*GROTTO, 'Морський грот (закритий)', 20, 46)
callout(690, 520, 'Тунель (крижана брама)', 120, -40)
callout(305, 565, 'Перевал', 60, -62)
callout(*VIEW, 'перший погляд на «собор»', -20, -30)
callout(835, 610, 'Озеро Джерел', 70, -40)
# компас і легенда
cx, cy = OX + 840, OY + 830
s.a(f'<circle cx="{cx}" cy="{cy}" r="30" fill="#efe4c4" stroke="#7a5a30" stroke-width="2"/><path d="M{cx},{cy-28} l7,28 l-7,28 l-7,-28 z" fill="#7a5a30"/><path d="M{cx-28},{cy} l28,7 l28,-7 l-28,-7 z" fill="#b89a70"/>')
s.txt(cx, cy - 34, 'Пн', 12, '#3a2a10', 'middle', 'bold', halo=False)
lx, ly = OX + 930, OY + 4
s.a(f'<text x="{lx}" y="{ly+14}" font-size="16" font-weight="bold" fill="#3a2a10" font-family="Georgia, serif">Зони в порядку проходження</text>'); ly += 30
for n_, nm, lv, _, nxt in ZONES:
    col = {1: COL[1], 2: COL[2], 3: COL[3], 4: (60, 60, 60), 5: COL[5], 6: COL[6], 7: COL[7], 8: COL[8], 9: COL[9], 10: COL[10]}[n_]
    s.a(f'<rect x="{lx}" y="{ly}" width="16" height="16" fill="rgb{col}" stroke="#5a4a30"/>')
    s.a(f'<foreignObject x="{lx+24}" y="{ly-3}" width="300" height="80"><div xmlns="http://www.w3.org/1999/xhtml" style="font:12.5px Segoe UI,Arial;line-height:1.28;color:#2a2010"><b>{n_}. {nm}</b> · {lv}<br/><span style="color:#5a4a30">→ {nxt}</span></div></foreignObject>')
    ly += 74
s.html(lx + 330, OY + 930, 'stylized.html')
print('ok', lx + 330, OY + 930)
