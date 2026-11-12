import sys, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *
SC = 922 / 1536; OX, OY = 20, 70
P = lambda x, z: (OX + x * SC, OY + z * SC)
s = SVG()
s.a('<text x="20" y="32" font-size="22" font-weight="bold">Острів 1, v9 — поверхня: маршрут рівнів 1–40, ельфійський ліс, вхід у підземелля</text>')
s.a('<text x="20" y="54" font-size="13" fill="#444">Основа — справжня карта v8 з сервера; усе кольорове — проєкт v9 (світ ще не згенеровано)</text>')
s.a(f'<image x="{OX}" y="{OY}" width="922" height="922" href="data:image/png;base64,{b64file("top920.png")}"/>')
s.a(f'<rect x="{OX}" y="{OY}" width="922" height="922" fill="#ffffff" opacity="0.18"/>')
# ліс ельфів
s.poly([P(*p) for p in FOREST], fill='#1f6a2a', fill_opacity='0.30', stroke='#d6ffb0', stroke_width='3', stroke_dasharray='9 5')
c = P(*TREE); s.a(f'<circle cx="{c[0]}" cy="{c[1]}" r="{CORE_R * SC}" fill="#063a10" fill-opacity="0.45" stroke="#f3ffb8" stroke-width="2.5"/>')
s.txt(c[0], c[1] + CORE_R * SC - 8, '«собор» під кронами', 12, '#f3ffb8', 'middle', 'bold', halo=False)
# маршрут
pts = [P(*p) for p in ROUTE]
s.poly(pts, closed=False, stroke='#ffd21e', stroke_width='4', stroke_linejoin='round', opacity='0.95')
for (x0, y0), (x1, y1) in zip(pts[::3], pts[1::3]):
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2; import math; a = math.degrees(math.atan2(y1 - y0, x1 - x0))
    s.a(f'<path d="M-7,-5 L5,0 L-7,5 Z" fill="#b08000" transform="translate({mx:.1f},{my:.1f}) rotate({a:.0f})"/>')
# брама, ринок, алея, ставок, Дерево, перший погляд
g = P(*GATE); m = P(*MARKET); pd = P(*POND); t = P(*TREE); v = P(*VIEW)
s.a(f'<path d="M{g[0]-7},{g[1]+7} L{g[0]-7},{g[1]-3} Q{g[0]},{g[1]-12} {g[0]+7},{g[1]-3} L{g[0]+7},{g[1]+7}" fill="none" stroke="#fff" stroke-width="4"/>')
s.a(f'<rect x="{m[0]-9}" y="{m[1]-6}" width="18" height="12" fill="#ff9e3d" stroke="#fff" stroke-width="2"/>')
s.poly([g, P(800, 909), pd], closed=False, stroke='#fff6b0', stroke_width='4')
s.a(f'<circle cx="{pd[0]}" cy="{pd[1]}" r="6" fill="#5fb6ff" stroke="#fff" stroke-width="2"/>')
s.a(f'<circle cx="{t[0]}" cy="{t[1]}" r="9" fill="#d9f7a0" stroke="#2a4a10" stroke-width="3"/>')
s.a(f'<line x1="{v[0]}" y1="{v[1]}" x2="{t[0]-8}" y2="{t[1]-6}" stroke="#ff7a00" stroke-width="2.5" stroke-dasharray="6 4"/>')
s.a(f'<circle cx="{v[0]}" cy="{v[1]}" r="6" fill="#ff7a00" stroke="#fff" stroke-width="2"/>')
# вхід у підземелля, грот, струмок
s.poly([P(*p) for p in RIVER_SURF], closed=False, stroke='#4fb0ff', stroke_width='3')
s.poly([P(*p) for p in RIVER_UG], closed=False, stroke='#4fb0ff', stroke_width='2.5', stroke_dasharray='5 4')
e = P(*ENTR); s.a(f'<path d="M{e[0]},{e[1]-13} L{e[0]+4},{e[1]-4} L{e[0]+13},{e[1]-4} L{e[0]+6},{e[1]+3} L{e[0]+9},{e[1]+12} L{e[0]},{e[1]+6} L{e[0]-9},{e[1]+12} L{e[0]-6},{e[1]+3} L{e[0]-13},{e[1]-4} L{e[0]-4},{e[1]-4}Z" fill="#ff3b3b" stroke="#fff" stroke-width="2"/>')
gr = P(*GROTTO); s.a(f'<path d="M{gr[0]-8},{gr[1]+6} Q{gr[0]-8},{gr[1]-9} {gr[0]},{gr[1]-9} Q{gr[0]+8},{gr[1]-9} {gr[0]+8},{gr[1]+6}" fill="#222" stroke="#fff" stroke-width="2"/>')
for k in range(-6, 7, 3): s.a(f'<line x1="{gr[0]+k}" y1="{gr[1]-7}" x2="{gr[0]+k}" y2="{gr[1]+6}" stroke="#ccc" stroke-width="1"/>')
# номери зон
for n_, nm, lv, (x, z), _ in ZONES:
    p = P(x, z); dash = ' stroke-dasharray="3 2"' if n_ == 4 else ''
    s.a(f'<circle cx="{p[0]}" cy="{p[1]}" r="13" fill="#111" stroke="#ffd21e" stroke-width="2"{dash}/>')
    s.txt(p[0], p[1] + 4.5, str(n_), 13, '#ffd21e', 'middle', 'bold', halo=False)
    s.txt(p[0] + 17, p[1] + 4, lv, 12, '#111', 'start', 'bold')
# виноски
for (x, z), t_, dx, dy in (((708, 922), 'Узлісся: ринок і брама', -40, 78), ((712, 900), 'перший погляд на «собор» (тераса ринку)', -30, -70),
                           ((588, 642), 'Брама Джерел — єдиний вхід', 70, -36), ((248, 592), 'Морський грот: море видно, прохід закрито', 20, -46),
                           ((800, 909), 'алея велетнів → ставок-сад → Велике Дерево', 40, 64)):
    p = P(x, z); s.a(f'<line x1="{p[0]}" y1="{p[1]}" x2="{p[0]+dx}" y2="{p[1]+dy}" stroke="#ff7a00" stroke-width="1.5"/>')
    s.txt(p[0] + dx, p[1] + dy + (12 if dy > 0 else -3), t_, 12, '#7a2e00', 'start' if dx >= 0 else 'middle', 'bold')
# легенда-таблиця праворуч
lx, ly = OX + 922 + 24, OY + 6
s.txt(lx, ly + 10, 'Порядок проходження', 15, w='bold', halo=False); ly += 26
for n_, nm, lv, _, nxt in ZONES:
    s.a(f'<circle cx="{lx+11}" cy="{ly+6}" r="11" fill="#111"/>'); s.txt(lx + 11, ly + 10.5, str(n_), 12, '#ffd21e', 'middle', 'bold', halo=False)
    s.a(f'<foreignObject x="{lx+28}" y="{ly-6}" width="340" height="86"><div xmlns="http://www.w3.org/1999/xhtml" style="font:12.5px Segoe UI,Arial;line-height:1.3"><b>{nm}</b> — рівні <b>{lv}</b><br/><span style="color:#444">→ {nxt}</span></div></foreignObject>')
    ly += 78
ly += 6
for col, t_ in (('#ffd21e', 'маршрут гравця'), ('#d6ffb0', 'межа ельфійського лісу (весь центральний ліс)'), ('#4fb0ff', 'річка: суцільна — на поверхні, пунктир — під землею'),
                ('#ff3b3b', 'вхід у підземелля'), ('#ff7a00', 'перший погляд на «собор»')):
    s.a(f'<rect x="{lx}" y="{ly}" width="22" height="8" fill="{col}"/>'); s.txt(lx + 30, ly + 9, t_, 12, halo=False); ly += 20
s.html(OX + 922 + 24 + 380, OY + 922 + 30, 'surface.html')
print('ok', OX + 922 + 24 + 380, OY + 922 + 30)
