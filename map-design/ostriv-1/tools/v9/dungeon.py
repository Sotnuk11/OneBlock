import sys, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import math, numpy as np
from common import *
X0, Z0, SC = 180, 470, 1.6
OX, OY = 20, 76
P = lambda x, z: (OX + (x - X0) * SC, OY + (z - Z0) * SC)
W, H = int(560 * SC), int(270 * SC)
n = np.load('../v8/new.npz'); TOP = n['TOP']
s = SVG()
s.a('<text x="20" y="32" font-size="22" font-weight="bold">Острів 1, v9 — підземелля «Русло Джерел» (рівні 12–16): один вхід, один маршрут</text>')
s.a('<text x="20" y="54" font-size="13" fill="#444">План згори поверх карти v8 (поверхня притінена). Жовте — шлях гравця, синє — течія річки, червоні кола — де вони зустрічаються</text>')
s.a(f'<image x="{OX}" y="{OY}" width="{W}" height="{H}" href="data:image/png;base64,{b64file("dcrop.png")}"/>')
s.a(f'<rect x="{OX}" y="{OY}" width="{W}" height="{H}" fill="#0b1630" opacity="0.62"/>')
# річка
s.poly([P(*p) for p in RIVER_SURF], closed=False, stroke='#7fd0ff', stroke_width='5')
s.poly([P(*p) for p in RIVER_UG], closed=False, stroke='#3aa0ff', stroke_width='9', stroke_linejoin='round', opacity='0.9')
for (x0, z0), (x1, z1) in zip(RIVER_UG[:-1], RIVER_UG[1:]):
    a, b = P(x0, z0), P(x1, z1); ang = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))
    s.a(f'<path d="M-6,-5 L6,0 L-6,5 Z" fill="#d8f0ff" transform="translate({(a[0]+b[0])/2:.1f},{(a[1]+b[1])/2:.1f}) rotate({ang:.0f})"/>')
p = P(626, 572); s.txt(p[0] + 10, p[1] - 6, 'струмок зі столу B', 12, '#bfe8ff', halo=False)
# кришталевий плес — розширення
c = P(420, 589); s.a(f'<ellipse cx="{c[0]}" cy="{c[1]}" rx="{24 * SC}" ry="{14 * SC}" fill="#3aa0ff" opacity="0.75"/>')
# море й грот
m = P(226, 592); s.a(f'<rect x="{OX}" y="{m[1]-30}" width="{m[0]-OX}" height="60" fill="#1d4f9a" opacity="0.5"/>')
s.txt(OX + 8, m[1] - 36, 'МОРЕ', 12, '#9fc8ff', w='bold', halo=False)
for k in range(-4, 5): s.a(f'<line x1="{m[0]}" y1="{m[1]+k*5}" x2="{m[0]}" y2="{m[1]+k*5+3}" stroke="#ddd" stroke-width="3"/>')
s.txt(m[0] + 6, m[1] + 44, 'ґрати в арці: море видно, але не пройти', 11, '#ffd0d0', 'start', halo=False)
# маршрут
rp = [P(*r[2]) for r in ROOMS]
s.poly(rp, closed=False, stroke='#ffd21e', stroke_width='4', stroke_dasharray='10 5')
s.poly([P(*p) for p in BACK], closed=False, stroke='#c9c9c9', stroke_width='2.5', stroke_dasharray='3 4')
p = P(420, 625); s.txt(p[0], p[1] + 16, 'потаємний хід назад до брами (відмикається лише зсередини після боса)', 11, '#e0e0e0', 'middle', halo=False)
for k, nm, pt, y, desc in ROOMS:
    q = P(*pt); s.a(f'<circle cx="{q[0]}" cy="{q[1]}" r="12" fill="#111" stroke="#ffd21e" stroke-width="2.5"/>')
    s.txt(q[0], q[1] + 4.5, str(k), 12, '#ffd21e', 'middle', 'bold', halo=False)
e = P(*ENTR); s.txt(e[0] + 14, e[1] + 22, 'ЄДИНИЙ ВХІД', 12, '#ff6b6b', w='bold', halo=False)
for x, z, t_ in MEET:
    q = P(x, z); s.a(f'<circle cx="{q[0]}" cy="{q[1]}" r="19" fill="none" stroke="#ff4040" stroke-width="3"/>')
    s.txt(q[0], q[1] - 23, t_.split(':')[0], 12, '#ff9090', 'middle', 'bold', halo=False)
# легенда кімнат
lx, ly = OX + W + 20, OY
s.txt(lx, ly + 8, 'Кімнати (уздовж течії)', 15, w='bold', halo=False); ly += 22
for k, nm, pt, y, desc in ROOMS:
    s.a(f'<foreignObject x="{lx}" y="{ly}" width="350" height="60"><div xmlns="http://www.w3.org/1999/xhtml" style="font:12.5px Segoe UI,Arial;line-height:1.3"><b>{k}. {nm}</b> (Y≈{y}) — {desc}</div></foreignObject>')
    ly += 46
ly += 4
for x, z, t_ in MEET:
    s.a(f'<foreignObject x="{lx}" y="{ly}" width="350" height="44"><div xmlns="http://www.w3.org/1999/xhtml" style="font:12.5px Segoe UI,Arial;line-height:1.3;color:#a00"><b>{t_.split(":")[0]}</b>:{t_.split(":",1)[1]}</div></foreignObject>')
    ly += 36
# розріз
oy = OY + H + 46
s.a(f'<text x="20" y="{oy - 12}" font-size="16" font-weight="bold">Розріз уздовж маршруту (схід → захід), висота перебільшена ×2</text>')
L = 380; Hd = 220; kx = (W - 40) / L
seg = [ROOMS[0][2]] + [r[2] for r in ROOMS[1:]]
d = [0.0]
for (x0, z0), (x1, z1) in zip(seg[:-1], seg[1:]): d.append(d[-1] + math.hypot(x1 - x0, z1 - z0))
sx = lambda dd: OX + 20 + dd * kx
sy = lambda yy: oy + Hd - (yy - 50) * 2
s.a(f'<rect x="{OX}" y="{oy}" width="{W}" height="{Hd + 10}" fill="#f2efe8" stroke="#999"/>')
# поверхня над маршрутом
surf = []
for k in range(len(seg) - 1):
    (x0, z0), (x1, z1) = seg[k], seg[k + 1]
    for t in np.linspace(0, 1, 12, endpoint=False):
        x, z = x0 + (x1 - x0) * t, z0 + (z1 - z0) * t; surf.append((d[k] + (d[k + 1] - d[k]) * t, float(TOP[int(z), int(x)])))
surf.append((d[-1], float(TOP[seg[-1][1], seg[-1][0]])))
pts = ' '.join(f'{sx(a):.1f},{sy(min(b, 160)):.1f}' for a, b in surf)
s.a(f'<polygon points="{sx(0):.1f},{oy} {pts} {sx(d[-1]):.1f},{oy}" fill="#dfe8d2"/><polygon points="{sx(0):.1f},{oy+Hd+10} {pts} {sx(d[-1]):.1f},{oy+Hd+10}" fill="#a39e94"/>')
s.txt(sx(150), sy(158) + 14, 'хребет (столи A–B), поверхня обрізана на Y 160', 11, '#333', 'middle', halo=False)
s.a(f'<line x1="{sx(d[-1])-40}" y1="{sy(63)}" x2="{OX+W}" y2="{sy(63)}" stroke="#1d4f9a" stroke-width="3"/>')
s.txt(OX + W - 6, sy(63) + 14, 'море Y 63', 11, '#1d4f9a', 'end', halo=False)
# річка в розрізі (за кімнатами)
ry = [(d[0], 98), (d[1], 86), (d[2], 81), (d[3], 78), (d[4], 75), (d[5], 71), (d[6], 67), (d[7], 63)]
s.a('<polyline points="' + ' '.join(f'{sx(a):.1f},{sy(b):.1f}' for a, b in ry) + '" fill="none" stroke="#3aa0ff" stroke-width="5"/>')
s.a('<polyline points="' + ' '.join(f'{sx(a):.1f},{sy(r[3]):.1f}' for a, r in zip(d, ROOMS)) + '" fill="none" stroke="#e0a800" stroke-width="3" stroke-dasharray="8 4"/>')
for a, r in zip(d, ROOMS):
    s.a(f'<rect x="{sx(a)-14}" y="{sy(r[3]+7)}" width="28" height="14" fill="#2a2420" opacity="0.85"/>')
    s.txt(sx(a), sy(r[3] + 7) - 4, f'{r[0]}', 11, '#7a5a00', 'middle', 'bold')
for a, lab in ((d[0], 'М1'), (d[4], 'М2'), (d[6], 'М3')):
    s.a(f'<circle cx="{sx(a)}" cy="{sy(80)}" r="11" fill="none" stroke="#ff4040" stroke-width="2.5"/>'); s.txt(sx(a), sy(80) + 4, lab, 10, '#c00', 'middle', 'bold', halo=False)
s.txt(OX + 10, oy + 18, 'схід — Брама Джерел', 11, halo=False); s.txt(OX + W - 10, oy + 18, 'захід — Морський грот', 11, anc='end', halo=False)
foot = oy + Hd + 34
for t_ in ['Прийоми з Wynncraft: вхід-орієнтир на дорозі з NPC-воротарем і квестом-«крихтою» (Decrepit Sewers, Infested Pit); поріг-спуск у провал; лінійні кімнати,',
           'двері відчиняє бій, бій чергується з паркуром; кімната перед босом — міст (Infested Pit); прогрес видно у світі (Timelost Sanctum — тут вода спадає). Річка — природний покажчик.']:
    s.txt(20, foot, t_, 12.5, '#333', halo=False); foot += 18
s.html(OX + W + 20 + 360, foot + 10, 'dungeon.html')
print('ok', OX + W + 20 + 360, foot + 10)
