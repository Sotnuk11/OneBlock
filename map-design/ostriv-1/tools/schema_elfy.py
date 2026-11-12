import base64, math, numpy as np
X0, Z0, SC = 520, 560, 0.8
img = base64.b64encode(open('crop_s.png', 'rb').read()).decode()
n = np.load('../v8/new.npz'); TOP = n['TOP']
W, H = 640, 560
def P(x, z): return (20 + (x - X0) * SC, 70 + (z - Z0) * SC)
out = []; A = out.append
def txt(x, y, s, sz=12, col='#111', anc='start', w='normal', halo=True):
    st = 'stroke="#fff" stroke-width="3.2" paint-order="stroke"' if halo else ''
    A(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{sz}" fill="{col}" text-anchor="{anc}" font-weight="{w}" {st}>{s}</text>')
def poly(pts, **kw):
    a = ' '.join(f'{P(x, z)[0]:.1f},{P(x, z)[1]:.1f}' for x, z in pts)
    A(f'<polygon points="{a}" ' + ' '.join(f'{k.replace("_", "-")}="{v}"' for k, v in kw.items()) + '/>')
def line(pts, **kw):
    a = ' '.join(f'{P(x, z)[0]:.1f},{P(x, z)[1]:.1f}' for x, z in pts)
    A(f'<polyline points="{a}" fill="none" ' + ' '.join(f'{k.replace("_", "-")}="{v}"' for k, v in kw.items()) + '/>')
def circ(x, z, r, **kw):
    p = P(x, z); A(f'<circle cx="{p[0]:.1f}" cy="{p[1]:.1f}" r="{r * SC:.1f}" ' + ' '.join(f'{k.replace("_", "-")}="{v}"' for k, v in kw.items()) + '/>')

A('<text x="20" y="34" font-size="22" font-weight="bold">Ліс і місто ельфів — референс (на карті v8, світ не змінено)</text>')
A('<text x="20" y="56" font-size="13" fill="#444">Замість Перехрестя: великий центральний ліс росте на захід; серце — «собор» під кронами, де живуть ельфи</text>')
A(f'<image x="20" y="70" width="{W}" height="{H}" href="data:image/png;base64,{img}"/>')
CX, CZ = 880, 905
FOREST = [(650, 840), (700, 770), (800, 718), (905, 700), (1010, 705), (1120, 742), (1180, 822), (1186, 930), (1150, 1030), (1060, 1078), (950, 1085), (840, 1062), (742, 1022), (662, 972)]
poly(FOREST, fill='#1f5a1f', fill_opacity='0.35', stroke='#c8f0a0', stroke_width='3', stroke_dasharray='9 5')
p = P(CX, CZ); A(f'<ellipse cx="{p[0]}" cy="{p[1]}" rx="{205 * SC}" ry="{150 * SC}" fill="#0d3a12" fill-opacity="0.35" stroke="#9fd88a" stroke-width="2" stroke-dasharray="5 4"/>')
circ(CX, CZ, 118, fill='#062a0b', fill_opacity='0.55', stroke='#e9ffb0', stroke_width='2.5')
for k in range(26):   # велетні-колони ядра
    a = k * 2.4; r = 30 + (k * 37) % 85
    x, z = CX + r * math.cos(a), CZ + r * math.sin(a)
    if math.hypot(x - 845, z - 905) > 22 and math.hypot(x - 885, z - 900) > 20: circ(x, z, 4.5, fill='#7a5a36', stroke='#e9ffb0', stroke_width='1')
line([(835, 652), (842, 760), (838, 840), (846, 893)], stroke='#6fc3ff', stroke_width='3', stroke_dasharray='6 4')
line([(735, 915), (790, 911), (832, 906)], stroke='#ffd21e', stroke_width='5')
for k in range(8):
    x = 745 + k * 12
    for dz in (-9, 9): circ(x, 912 - k * 0.8 + dz, 3, fill='#7a5a36', stroke='#ffd21e', stroke_width='1')
p = P(735, 915); A(f'<path d="M{p[0]-9},{p[1]+8} L{p[0]-9},{p[1]-4} Q{p[0]},{p[1]-14} {p[0]+9},{p[1]-4} L{p[0]+9},{p[1]+8}" fill="none" stroke="#fff" stroke-width="4"/>')
p = P(846, 905); A(f'<ellipse cx="{p[0]}" cy="{p[1]}" rx="{16 * SC}" ry="{11 * SC}" fill="#5fb6ff" stroke="#fff" stroke-width="2"/>')
circ(886, 900, 13, fill='#d9f7a0', stroke='#2a4a10', stroke_width='3')
circ(886, 900, 5, fill='#fff6b0')
CL = [((878, 842), 'Майстерні'), ((888, 966), 'Сади'), ((948, 872), 'Варта'), ((828, 852), 'Бібліотека')]
for (x, z), lab in CL:
    line([(886, 900), (x, z)], stroke='#e0b070', stroke_width='2', stroke_dasharray='4 3')
    circ(x, z, 20, fill='#f0e0a0', fill_opacity='0.35', stroke='#f0e0a0', stroke_width='2')
for a, b in ((0, 2), (2, 1), (0, 3)):
    line([CL[a][0], CL[b][0]], stroke='#e0b070', stroke_width='1.6', stroke_dasharray='4 3')
p = P(972, 905); A(f'<rect x="{p[0]-6}" y="{p[1]-6}" width="12" height="12" fill="#b9a0ff" stroke="#fff" stroke-width="2"/>')
circ(1012, 768, 16, fill='#ffb070', fill_opacity='0.6', stroke='#fff', stroke_width='2')
for x, z in ((700, 862), (770, 1012), (1120, 822), (1100, 1040)):
    p = P(x, z); A(f'<path d="M{p[0]},{p[1]-8} L{p[0]+6},{p[1]+5} L{p[0]-6},{p[1]+5}Z" fill="#fff" stroke="#333"/>')
# номери-підписи на карті
def tag(x, z, k, dx=10, dy=-8):
    p = P(x, z); A(f'<circle cx="{p[0]+dx}" cy="{p[1]+dy}" r="9" fill="#111"/>'); txt(p[0] + dx, p[1] + dy + 4, str(k), 11, '#fff', 'middle', 'bold', halo=False)
for k, (x, z, dx, dy) in enumerate([(735, 915, -14, -14), (790, 911, 0, -16), (846, 905, -6, 18), (886, 900, 16, -14), (878, 842, 18, -10), (972, 905, 14, -6),
                                    (1012, 768, 18, -8), (840, 760, 12, 0), (1150, 960, -10, -10), (700, 862, -12, -8)], 1):
    tag(x, z, k, dx, dy)
# легенда праворуч
lx = 20 + W + 22; ly = 86
items = ['<b>Жива брама + ринок Узлісся</b> — тут сходяться дороги (тунель, старт, Пшеничне). Торг на узліссі, бо в місті — обмін дарами. Знімає питання «хто торговий вузол».',
         '<b>Алея велетнів</b> (sandō) ≈ 100 бл.: стовбури обабіч, ліхтарики, цвіт азалії вздовж — «вітер» веде до міста. Міста не видно до кінця алеї.',
         '<b>Ставок-сад</b> під вікном у кронах — перше світло після темряви, звідси видно Велике Дерево (розкриття, як у Петрі).',
         '<b>Велике Дерево</b> ≈ 90 бл., стовбур 9×9: коло зборів біля коріння, спіральні сходи, зала на +45, сяє вночі (орієнтир для всього острова).',
         '<b>4 тераси-квартали</b> (Майстерні, Сади, Варта, Бібліотека): по 3–5 дерев-домів, платформи на +10…+16, мости 10–20 бл. між ними й до Дерева.',
         '<b>Святилище</b> за другою брамою з вартовим (як Kokiri) — квести про довіру ельфів.',
         '<b>Село вигнанців</b> біля річки з Озера, під наметом лісу — перша зупинка (як Efelim).',
         '<b>Струмок із Озера Джерел</b> до ставка-саду — ліс «п\'є» з озера; уздовж — верби, мох.',
         '<b>Узлісся</b> 8–16 бл.: трави → кущі → берези, далі старий ліс 25–35, у центрі «собор».',
         '<b>Вартові вежі-дерева</b> на краю лісу, лучники на платформах.']
for k, s in enumerate(items, 1):
    A(f'<circle cx="{lx+9}" cy="{ly-4}" r="9" fill="#111"/>'); txt(lx + 9, ly, str(k), 11, '#fff', 'middle', 'bold', halo=False)
    A(f'<foreignObject x="{lx+24}" y="{ly-16}" width="330" height="60"><div xmlns="http://www.w3.org/1999/xhtml" style="font:12.5px Segoe UI,Arial;line-height:1.3;color:#111">{s}</div></foreignObject>')
    ly += 50
# зони
txt(lx, ly + 14, 'Зони лісу:', 13, w='bold', halo=False)
for k, (col, s) in enumerate((('#c8f0a0', 'межа нового великого лісу (росте на захід)'), ('#9fd88a', 'старий ліс, дерева 25–35'), ('#e9ffb0', 'серце-«собор», велетні 40–55 (коло ≈ 120 бл.)'))):
    A(f'<rect x="{lx}" y="{ly + 22 + k * 20}" width="18" height="12" fill="none" stroke="{col}" stroke-width="3"/>'); txt(lx + 26, ly + 33 + k * 20, s, 12, halo=False)
# розріз
y0 = max(70 + H, ly + 84) + 30
A(f'<text x="20" y="{y0}" font-size="16" font-weight="bold">Розріз захід → схід крізь місто (Z≈905), рельєф — з генератора v8</text>')
oy = y0 + 14; Wd = 1000; Hd = 260; xs0, xs1 = 690, 1190; k = Wd / (xs1 - xs0)
A(f'<rect x="20" y="{oy}" width="{Wd}" height="{Hd}" fill="#eef6ff" stroke="#999"/>')
gy = lambda h: oy + Hd - (h - 60) * 1.75
X = lambda x: 20 + (x - xs0) * k
pts = ' '.join(f'{X(x):.1f},{gy(float(TOP[905, x])):.1f}' for x in range(xs0, xs1))
A(f'<polygon points="20,{oy+Hd} {pts} {20+Wd},{oy+Hd}" fill="#7a6a4a"/>')
g = lambda x: float(TOP[905, x])
def tree(x, h, crown_h, crown_r, trunk_w=1.5, bare=0.0, col='#2f6b2a'):
    gb = g(x); A(f'<rect x="{X(x) - trunk_w * k / 2:.1f}" y="{gy(gb + h):.1f}" width="{trunk_w * k:.1f}" height="{gy(gb) - gy(gb + h):.1f}" fill="#6b4a2a"/>')
    A(f'<ellipse cx="{X(x):.1f}" cy="{gy(gb + h - crown_h / 2):.1f}" rx="{crown_r * k:.1f}" ry="{crown_h * 0.875:.1f}" fill="{col}" opacity="0.92"/>')
rng = np.random.default_rng(3)
for x in range(xs0 + 4, xs1, 7):                       # дерева за зонами
    d = abs(x - CX)
    if 832 < x < 860 or 874 < x < 898: continue
    if d < 118:
        if x % 3 == 0: tree(x, int(rng.integers(42, 54)), 14, 12, 3.5, col='#1f5a22')
    elif d < 205:
        if x % 2 == 0: tree(x, int(rng.integers(24, 34)), 12, 7, 2, col='#2f6b2a')
    elif x > 742: tree(x, int(rng.integers(9, 16)), 7, 4, 1, col='#5b9a3a' if (x // 7) % 3 else '#c9d98a')
gb = g(886); A(f'<rect x="{X(882):.1f}" y="{gy(gb + 92):.1f}" width="{9 * k:.1f}" height="{gy(gb) - gy(gb + 92):.1f}" fill="#8a6a3a"/>')
A(f'<ellipse cx="{X(886):.1f}" cy="{gy(gb + 84):.1f}" rx="{30 * k:.1f}" ry="{18 * 1.75:.1f}" fill="#9be07a" stroke="#fff6b0" stroke-width="2"/>')
A(f'<rect x="{X(874):.1f}" y="{gy(gb + 48):.1f}" width="{24 * k:.1f}" height="{6 * 1.75:.1f}" fill="#f0e0a0" stroke="#7a5a2a"/>')
txt(X(886), gy(gb + 105), 'Велике Дерево ≈ 90', 12, '#123', 'middle', 'bold')
txt(X(900) + 4, gy(gb + 51), 'зала +45', 11)
A(f'<line x1="{X(762)}" y1="{gy(g(762) + 36)}" x2="{X(1010)}" y2="{gy(g(1010) + 36)}" stroke="#2a5a20" stroke-width="2" stroke-dasharray="3 3"/>')
txt(X(1012), gy(g(1010) + 37), 'стеля з листя +24…+40, вікна для світла', 11, '#173')
for x0_, x1_, hh in ((800, 832, 14), (898, 940, 13), (940, 975, 28)):
    A(f'<line x1="{X(x0_)}" y1="{gy(g(x0_) + hh)}" x2="{X(x1_)}" y2="{gy(g(x1_) + hh)}" stroke="#e0b070" stroke-width="3"/>')
txt(X(918), gy(g(918) + 16), 'платформи й мости +10…+16', 11, anc='middle')
txt(X(958), gy(g(958) + 31), 'мости в кронах +24…+34', 11, anc='middle')
A(f'<ellipse cx="{X(846):.1f}" cy="{gy(g(846)) + 2:.1f}" rx="{14 * k:.1f}" ry="4" fill="#5fb6ff"/>')
txt(X(846), gy(g(846)) + 18, 'ставок-сад', 11, anc='middle')
A(f'<path d="M{X(872)},{gy(gb - 2)} Q{X(886)},{gy(gb - 18)} {X(900)},{gy(gb - 2)}" fill="#3a2a1a" stroke="#c9a060"/>')
txt(X(886), gy(gb - 22), 'корені-печери, водяна брама', 11, '#fff', 'middle', halo=False)
txt(X(735), oy + 18, 'брама й ринок Узлісся', 11, anc='middle'); txt(X(790), oy + 34, 'алея велетнів', 11, anc='middle')
txt(X(972), oy + 18, 'святилище', 11, anc='middle')
txt(X(1150), oy + 18, 'узлісся: береза', 11, anc='middle')
A(f'<line x1="{X(735)}" y1="{oy+22}" x2="{X(735)}" y2="{gy(g(735))}" stroke="#333" stroke-dasharray="2 3"/>')
foot = oy + Hd + 22
for s in ['Висоти й розміри — оцінки (нотатка 23). Карта знизу — справжній v8; ліс, місто й струмок намальовані поверх як план, генератор їх ще не будує.',
          'Під густою стелею темно: потрібні ліхтарі на ланцюгах, світні ягоди, шрумлайт у кронах — або заборона мобів у зоні міста (плагін).']:
    txt(20, foot, s, 12.5, '#333', halo=False); foot += 18
Ht = foot + 10; Wt = 20 + W + 22 + 360
svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{max(Wt, 1040)}" height="{Ht}" font-family="Segoe UI, Arial"><rect width="100%" height="100%" fill="#fff"/>' + ''.join(out) + '</svg>'
open('elf.html', 'w', encoding='utf-8').write('<html><head><meta charset="utf-8"></head><body style="margin:0">' + svg + '</body></html>')
print('ok', max(Wt, 1040), Ht)
