import numpy as np, zlib, struct, base64, sys
sys.path.insert(0, '.')
from cave_test import gnoise
n = np.load('../v8/new.npz'); TOP = n['TOP']; tL = n['tL']
R = 3                                   # крок вибірки, блоків
ax = np.arange(0, 1536, R) + 0.5
X, Z = np.meshgrid(ax, ax)
surf = TOP[::R, ::R].astype(float)
land = surf >= 63
HALLS = [("Перша печера", 370, 650, 20, 50), ("Кришталева зала", 330, 520, 42, 18), ("Підземне озеро", 470, 470, 55, 8),
         ("Грибна печера", 660, 600, 45, 24), ("Лавові глибини", 823, 257, 48, -24), ("Стара копальня", 740, 790, 38, 30),
         ("Морський грот", 150, 580, 30, 52), ("Крипта", 840, 440, 32, 34), ("Безодня", 430, 760, 30, -50)]
def mask(y):
    cheese = gnoise(X / 60, np.full_like(X, y / 40), Z / 60, 7)
    thr = 0.40 + 0.15 * np.clip((y + 10) / 50.0, 0, 1)
    n1 = gnoise(X / 45, np.full_like(X, y / 30), Z / 45, 8); n2 = gnoise(X / 45, np.full_like(X, y / 30), Z / 45, 9)
    big = cheese > thr; tube = (n1 * n1 + n2 * n2 < 0.012) & ~big
    ok = (surf - y > 12) & (y > -60)
    return big & ok, tube & ok
def png(img):
    H, W, _ = img.shape; raw = b''.join(b'\x00' + img[r].tobytes() for r in range(H))
    ch = lambda t, x: struct.pack('>I', len(x)) + t + x + struct.pack('>I', zlib.crc32(t + x) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw, 6)) + ch(b'IEND', b'')
panels = []
for y, lab, col in ((45, 'Y 45 · верхні (рівні 1–5)', (90, 200, 120)), (20, 'Y 20 · середні зали (6–12)', (120, 170, 255)),
                    (-5, 'Y −5 · глибинний сланець (12–16)', (200, 140, 255)), (-40, 'Y −40 · безодня (17–20)', (255, 120, 40))):
    big, tube = mask(y)
    img = np.zeros(X.shape + (3,), np.uint8)
    img[:] = (28, 40, 70); img[land] = (70, 70, 66)
    img[land & (surf - y <= 12)] = (120, 150, 95)                       # тут уже поверхня/захисний шар
    img[big & land] = col; img[tube & land] = tuple(int(c * 0.65) for c in col)
    if y < -54: img[(big | tube) & land] = (255, 80, 0)
    panels.append((lab, img, big[land].mean() + tube[land].mean()))
W = panels[0][1].shape[1]; svg = []
for k, (lab, img, frac) in enumerate(panels):
    ox, oy = 20 + (k % 2) * (W + 24), 90 + (k // 2) * (W + 44)
    svg.append(f'<text x="{ox}" y="{oy - 8}" font-size="15" font-weight="bold">{lab} · порожнечі {frac * 100:.1f}%</text>')
    svg.append(f'<image x="{ox}" y="{oy}" width="{W}" height="{W}" href="data:image/png;base64,{base64.b64encode(png(img)).decode()}"/>')
    for nm, hx, hz, hr, fy in HALLS:
        if abs(fy - [45, 20, -5, -40][k]) < 30:
            cx, cz = ox + hx * 1.5 / R, oy + hz * 1.5 / R
            svg.append(f'<circle cx="{cx:.0f}" cy="{cz:.0f}" r="{hr * 1.5 / R:.0f}" fill="none" stroke="#ffe14a" stroke-width="2"/>')
            svg.append(f'<text x="{cx:.0f}" y="{cz - hr * 1.5 / R - 4:.0f}" font-size="11" fill="#ffe14a" text-anchor="middle">{nm}</text>')
Ht = 90 + 2 * (W + 44) + 60
svg.insert(0, '<text x="20" y="30" font-size="20" font-weight="bold">Підземний світ: зрізи шумових печер під островом v8 (проба, світ не змінено)</text>'
              '<text x="20" y="56" font-size="12" fill="#444">Світлі плями — великі зали («сир»), темніші лінії — тунелі («спагеті»); жовті кола — наші 9 сюжетних залів; зелене — ближче 12 бл. до поверхні (печер нема)</text>')
svg.append(f'<text x="20" y="{Ht - 16}" font-size="12.5" fill="#333">Параметри з розрахунку агента: «сир» 1/60×1/40×1/60, поріг 0,40–0,55; «спагеті» — два шуми 1/45×1/30, n1²+n2² &lt; 0,012. Міста, дороги й входи ще не враховані.</text>')
Wt = 20 + 2 * W + 44
open('slices.html', 'w', encoding='utf-8').write(f'<html><head><meta charset="utf-8"></head><body style="margin:0"><svg xmlns="http://www.w3.org/2000/svg" width="{Wt}" height="{Ht}" font-family="Segoe UI, Arial"><rect width="100%" height="100%" fill="#fff"/>' + ''.join(svg) + '</svg></body></html>')
print('ok', Wt, Ht, [round(p[2] * 100, 1) for p in panels])
