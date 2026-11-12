import numpy as np, zlib, struct, base64, math
o = np.load('old.npz'); n = np.load('new.npz')
X0, X1, Z0, Z1, SC = 100, 1460, 150, 760, 0.75
VC = (1234, 386); RB = 215

def png(img):
    H, W, _ = img.shape; raw = b''.join(b'\x00' + img[r].tobytes() for r in range(H))
    ch = lambda t, x: struct.pack('>I', len(x)) + t + x + struct.pack('>I', zlib.crc32(t + x) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw, 6)) + ch(b'IEND', b'')

def colour(d):
    T = d['TOP'].astype(float); WL = d['WL']; LL = d['LL']
    gz, gx = np.gradient(T); sl = np.hypot(gx, gz)
    Z, X = np.mgrid[0:T.shape[0], 0:T.shape[1]]
    lo = np.clip((T - 70) / 60, 0, 1)[..., None]
    c = (1 - lo) * np.array([88, 145, 62]) + lo * np.array([118, 128, 80])
    c = np.where((T > 140)[..., None], np.array([128, 134, 100]), c)
    c = np.where((sl > 1.25)[..., None], np.array([128, 122, 116]), c)
    snow = (T >= 176 + 22 * np.clip((X - 700) / 500, 0, 1) ** 1.5) & (sl < 0.9); c = np.where(snow[..., None], np.array([238, 240, 245]), c)
    if 'VOLC_M' in d.files:
        ash = np.clip((X - 900) / 330, 0, 1) * (T > 130)            # попіл поступово густішає вздовж хребта до вулкана
        ash = np.maximum(ash, d['VOLC_M'] * (T > 90))
    else:
        ash = ((np.hypot(X - VC[0], Z - VC[1]) < RB) & (T > 100)).astype(float)
    ash = (ash * ~snow)[..., None]
    c = c * (1 - ash) + np.array([78, 68, 68]) * ash
    c = c * np.clip(1 + (-gx - gz) * 0.12, 0.55, 1.4)[..., None]
    w = WL > T; dep = np.clip((WL - T) / 12, 0, 1)[..., None]
    c = np.where(w[..., None], (1 - dep) * np.array([70, 130, 200]) + dep * np.array([35, 70, 150]), c)
    c = np.where((LL > -999)[..., None], np.array([245, 110, 25]), c)
    c = c[Z0:Z1, X0:X1]
    H, W = c.shape[:2]; ys = (np.arange(int(H * SC)) / SC).astype(int); xs = (np.arange(int(W * SC)) / SC).astype(int)
    return np.clip(c[ys][:, xs], 0, 255).astype(np.uint8)

def P(x, z, ox, oy): return (ox + (x - X0) * SC, oy + (z - Z0) * SC)
W_ = int((X1 - X0) * SC); H_ = int((Z1 - Z0) * SC); TW = W_ + 40
svg = []; A = svg.append
def txt(x, y, s, sz=14, col='#111', anc='start', w='normal', halo=True):
    st = 'stroke="#fff" stroke-width="3" paint-order="stroke"' if halo else ''
    A(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{sz}" fill="{col}" text-anchor="{anc}" font-weight="{w}" {st}>{s}</text>')

y = 10
A(f'<text x="20" y="{y + 26}" font-size="24" font-weight="bold">Острів 1, v8 (схема з генератора): хребет, тунель, вулкан</text>'); y += 44
TUN = n['TUN']; Wl = n['WELL']
for lab, d, ov in (('БУЛО — v7', o, False),
                   ('СТАЛО — v8 (згенеровано): гори до берега на заході, прямий тунель, вулкан витягнутий уздовж хребта', n, True)):
    txt(20, y + 18, lab, 16, w='bold'); y += 26
    b = base64.b64encode(png(colour(d))).decode()
    A(f'<image x="20" y="{y}" width="{W_}" height="{H_}" href="data:image/png;base64,{b}"/>')
    if ov:
        a = P(690, 700, 20, y); bb = P(690, 330, 20, y); s_ = P(TUN[0], TUN[1], 20, y); n_ = P(TUN[2], TUN[3], 20, y)
        A(f'<line x1="{a[0]}" y1="{a[1]}" x2="{s_[0]}" y2="{s_[1]}" stroke="#ffd21e" stroke-width="4"/>')
        A(f'<line x1="{n_[0]}" y1="{n_[1]}" x2="{bb[0]}" y2="{bb[1]}" stroke="#ffd21e" stroke-width="4"/>')
        A(f'<line x1="{s_[0]}" y1="{s_[1]}" x2="{n_[0]}" y2="{n_[1]}" stroke="#ffd21e" stroke-width="3" stroke-dasharray="7 5"/>')
        for p in (s_, n_): A(f'<rect x="{p[0] - 5}" y="{p[1] - 5}" width="10" height="10" fill="#222" stroke="#ffd21e" stroke-width="2"/>')
        w = P(Wl[0], Wl[1], 20, y); A(f'<circle cx="{w[0]}" cy="{w[1]}" r="9" fill="none" stroke="#ffd21e" stroke-width="2.5"/>')
        txt(w[0] + 12, w[1] + 4, 'колодязь-світло', 12, '#5a4000')
        txt(n_[0] + 8, n_[1] - 6, 'крижана брама', 12, '#123a6a'); txt(s_[0] + 10, s_[1] + 14, 'темна брама', 12, '#222')
        for lab2, (x, z) in (('СТІЛ A', (420, 540)), ('СТІЛ B', (760, 500)), ('СТІЛ C', (862, 438)), ('СТІЛ D', (1015, 412))):
            p = P(x, z, 20, y); txt(p[0], p[1], lab2, 14, '#1b3d1b', 'middle', 'bold')
        for x, z in ((525, 512), (750, 452), (955, 420)):
            p = P(x, z, 20, y); txt(p[0], p[1] + 4, 'сідловина', 11, '#333', 'middle')
        p = P(305, 565, 20, y); txt(p[0], p[1] + 4, 'перевал (дорога)', 12, '#333', 'middle')
        p = P(1105, 398, 20, y); txt(p[0], p[1] - 14, 'плече', 12, '#eee', 'middle', halo=False)
        p = P(VC[0], VC[1], 20, y); txt(p[0], p[1] + 4, 'кальдера', 13, '#fff', 'middle', 'bold', halo=False)
        for x, z in ((452, 497), (655, 446), (870, 403)):
            p = P(x, z, 20, y); A(f'<path d="M{p[0] - 6},{p[1] + 5} L{p[0]},{p[1] - 7} L{p[0] + 6},{p[1] + 5}Z" fill="#555" stroke="#fff"/>')
        p = P(440, 470, 20, y); txt(p[0], p[1] - 10, '▲ скельні зуби', 11, '#333', 'middle')
        p = P(860, 560, 20, y); txt(p[0], p[1], 'відроги між потоками', 12, '#333', 'middle')
        for lab4, (x, z) in (('Хвойне село', (470, 365)), ('Рибальське', (258, 717)), ('Зах. порт', (250, 870))):
            p = P(x, z, 20, y)
            if p[1] < y + H_: A(f'<circle cx="{p[0]}" cy="{p[1]}" r="5" fill="#c33" stroke="#fff"/>'); txt(p[0] + 8, p[1] + 4, lab4, 12, '#7a1010')
    y += H_ + 18

def prof(T, c, half=170):
    dv = (0.24, 0.97); return [float(T[int(c[1] + dv[1] * k), int(c[0] + dv[0] * k)]) for k in range(-half, half + 1)]
txt(20, y + 18, 'Розрізи поперек хребта (1 блок = 1 піксель, без перебільшення; ліворуч північ, праворуч південь; сіре — v7, зелене — v8)', 15, w='bold'); y += 30
secs = (('Стіл A', (420, 534)), ('Стіл B (над тунелем; провал — колодязь)', (645, 471)), ('Сідловина', (750, 452)), ('Стіл C', (862, 433)))
cw, chh = 345, 175
for i, (lab, c) in enumerate(secs):
    ox = 20 + (i % 2) * (cw + 40); oy = y + (i // 2) * (chh + 30)
    A(f'<rect x="{ox}" y="{oy}" width="{cw}" height="{chh}" fill="#eef4fb" stroke="#999"/>')
    sy = lambda h, oy=oy: oy + chh - (h - 60)
    A(f'<line x1="{ox}" y1="{sy(63)}" x2="{ox + cw}" y2="{sy(63)}" stroke="#6aa0d8" stroke-dasharray="4 3"/>')
    for T, col, wd in ((o['TOP'], '#999', 2), (n['TOP'], '#2e6b2e', 2.5)):
        pts = ' '.join(f'{ox + 3 + k:.0f},{sy(h):.1f}' for k, h in enumerate(prof(T, c)))
        A(f'<polyline points="{pts}" fill="none" stroke="{col}" stroke-width="{wd}"/>')
    for hh in (100, 150, 200): A(f'<text x="{ox + cw - 4}" y="{sy(hh) - 2}" font-size="10" fill="#678" text-anchor="end">Y{hh}</text>')
    txt(ox + 6, oy + 16, lab, 13, w='bold')
# праворуч від розрізів — план тунелю (текстом)
px0 = 20 + 2 * (cw + 40)
plan = ['Тунель (прямий, рівний), з півдня на північ:',
        '1. Виїмка-ущелина: дорога 5, стіни круті,',
        '   з неї видно темну браму й світло вдалині.',
        '2. Темна брама: колони, ніші, жаровні.',
        '3. Колонада: зала 15 завширшки, колони',
        '   кожні 7, піднята доріжка з парапетом.',
        '4. Колодязь: відкрите небо, озерце внизу,',
        '   кам’яний міст; зверху — оглядова на столі B.',
        '5. Смугастий коридор: пісковик, кальцит,',
        '   синій лід, вогні в нішах.',
        '6. Крижана брама, алея жердин, вид на порт.',
        'Прямий: з будь-якого кінця видно світло',
        'колодязя й виходу — гравець не губиться.']
for k, s in enumerate(plan): txt(px0, y + 14 + k * 19, s, 13, w='bold' if k == 0 else 'normal', halo=False)
y += 2 * (chh + 30) + 10

txt(20, y + 18, 'Вулкан, захід → схід: плече, конус, кальдера', 15, w='bold'); y += 28
vw = 460; vh = 175; ox = 20; oy = y
A(f'<rect x="{ox}" y="{oy}" width="{vw}" height="{vh}" fill="#eef4fb" stroke="#999"/>')
sy = lambda h: oy + vh - (h - 50)
A(f'<line x1="{ox}" y1="{sy(63)}" x2="{ox + vw}" y2="{sy(63)}" stroke="#6aa0d8" stroke-dasharray="4 3"/>')
for T, col, wd in ((o['TOP'], '#999', 2), (n['TOP'], '#7a2a1a', 2.5)):
    pts = ' '.join(f'{ox + k:.0f},{sy(float(T[386, 1000 + k])):.1f}' for k in range(vw))
    A(f'<polyline points="{pts}" fill="none" stroke="{col}" stroke-width="{wd}"/>')
A(f'<line x1="{ox + 174}" y1="{sy(183)}" x2="{ox + 294}" y2="{sy(183)}" stroke="#f5701a" stroke-width="4"/>')
for hh in (100, 150, 200): A(f'<text x="{ox + vw - 4}" y="{sy(hh) - 2}" font-size="10" fill="#678" text-anchor="end">Y{hh}</text>')
txt(ox + 30, sy(178), 'плече', 11); txt(ox + 240, sy(226), 'край Y214, лава Y183', 11, anc='middle')

tx = ox + vw + 40; tw = TW - tx - 20; th = 175
txt(tx, oy - 8, f'Тунель уздовж: прямий, підлога Y={int(TUN[4])} по всій довжині', 15, w='bold')
A(f'<rect x="{tx}" y="{oy}" width="{tw}" height="{th}" fill="#eef4fb" stroke="#999"/>')
L = 370.0; kx = tw / L
syt = lambda h: oy + th - (h - 60) * 0.9
T = n['TOP']; pts = []
for k in range(int(L) + 1):
    t = k / L; x = 690; z = 700 - 370 * t; pts.append(f'{tx + k * kx:.1f},{syt(float(T[int(z), int(x)])):.1f}')
A(f'<polygon points="{tx},{oy + th} ' + ' '.join(pts) + f' {tx + tw},{oy + th}" fill="#b9b2a6" stroke="#555"/>')
ty = int(TUN[4])
ts = (700 - TUN[1]) * kx; tn = (700 - TUN[3]) * kx
A(f'<rect x="{tx + ts}" y="{syt(ty + 9)}" width="{tn - ts}" height="{9 * 0.9}" fill="#3a3030"/>')
A(f'<line x1="{tx}" y1="{syt(ty)}" x2="{tx + tw}" y2="{syt(ty)}" stroke="#ffd21e" stroke-width="2.5"/>')
wm = (700 - Wl[1]) * kx
A(f'<rect x="{tx + wm - 12}" y="{syt(186)}" width="24" height="{(186 - ty + 12) * 0.9}" fill="#cfe8ff" opacity="0.9"/>')
for a_, b_, lab in ((0, ts, 'виїмка'), (ts, wm - 12, 'колонада'), (wm + 12, tn, 'смугастий коридор'), (tn, tw, 'виїмка')):
    txt(tx + (a_ + b_) / 2, syt(ty) + 16, lab, 11, '#222', 'middle')
txt(tx + wm, syt(192), 'колодязь', 11, '#0d3b66', 'middle')
txt(tx + ts, syt(ty + 13), 'темна брама', 11, '#222', 'middle'); txt(tx + tn, syt(ty + 13), 'крижана брама', 11, '#123a6a', 'middle')
txt(tx + 6, oy + 14, 'південь (Перехрестя)', 11); txt(tx + tw - 6, oy + 14, 'північ (порт)', 11, anc='end')
y += vh + 22
for s in ['Розміри — оцінки; це рельєф із генератора v8, але споруди (брами, колонада, міст, хатки на столах) ще не побудовані й світ не згенеровано.']:
    txt(20, y + 14, s, 13, '#444', halo=False); y += 19
H = y + 12
open('schema.svg', 'w', encoding='utf-8').write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{TW}" height="{H}" font-family="Segoe UI, Arial"><rect width="100%" height="100%" fill="#fff"/>' + ''.join(svg) + '</svg>')
open('schema.html', 'w', encoding='utf-8').write(f'<html><head><meta charset="utf-8"></head><body style="margin:0">' + open('schema.svg', encoding='utf-8').read() + '</body></html>')
print('ok', TW, H)
