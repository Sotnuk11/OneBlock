# Острів 1 (Lv 1-20): вокселевий макет за схемою Нотатки/05-Дослідження/karta/ostriv-1-luky.svg.
# Запуск: blender.exe -b --factory-startup -P build_island1.py -- <вихідна папка>
# 1 одиниця Blender = 1 блок Minecraft; X = схід, Y = північ, Z = 0 на рівні моря (Y=63 у грі).
# Координати схеми задані в квадраті 1024 і множаться на K: острів займає квадрат 1536×1536.
import bpy, math, random, sys, os
import numpy as np

OUT = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else os.path.dirname(os.path.abspath(__file__))
K = 1.5
SIZE, CELL = 1536, 4
N = SIZE // CELL
SEA = 63
rng = random.Random(7)

def S(x, y):  # точка схеми -> блоки світу (y на південь, як у SVG)
    return x * K, y * K

def lin(c):  # кольори в коді задані як sRGB, Blender чекає лінійні
    return tuple(x ** 2.2 for x in c)

# ---------- геометрія схеми (ті самі числа, що в SVG) ----------
def blob_t(px, py, cx, cy, r, bumps, seed):
    a = np.arctan2(py - cy, px - cx)
    k = np.ones_like(a)
    for amp, freq, ph in bumps:
        k += amp * np.sin(freq * a + ph + seed)
    return np.hypot(px - cx, py - cy) / (r * k)

def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)

def parse_path(d):
    tok = d.replace(",", " ").replace("M", " M ").replace("C", " C ").replace("S", " S ").split()
    pts, i, cur, last_c2 = [], 0, None, None
    def bez(p0, p1, p2, p3):
        return [tuple((1-t)**3*np.array(p0) + 3*(1-t)**2*t*np.array(p1) + 3*(1-t)*t*t*np.array(p2) + t**3*np.array(p3)) for t in np.linspace(0, 1, 24)]
    while i < len(tok):
        c = tok[i]; i += 1
        if c == "M":
            cur = (float(tok[i]), float(tok[i+1])); i += 2; pts.append(cur)
        elif c == "C":
            p1 = (float(tok[i]), float(tok[i+1])); p2 = (float(tok[i+2]), float(tok[i+3])); p3 = (float(tok[i+4]), float(tok[i+5])); i += 6
            pts += bez(cur, p1, p2, p3)[1:]; cur, last_c2 = p3, p2
        elif c == "S":
            p1 = (2*cur[0]-last_c2[0], 2*cur[1]-last_c2[1]); p2 = (float(tok[i]), float(tok[i+1])); p3 = (float(tok[i+2]), float(tok[i+3])); i += 4
            pts += bez(cur, p1, p2, p3)[1:]; cur, last_c2 = p3, p2
    return pts

def dist_to_paths(px, py, paths):  # px, py у координатах схеми; результат у блоках світу
    best = np.full(np.shape(px), 1e9)
    for d in paths:
        pts = parse_path(d)
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            vx, vy = x1 - x0, y1 - y0
            L = vx*vx + vy*vy or 1
            t = np.clip(((px - x0)*vx + (py - y0)*vy) / L, 0, 1)
            best = np.minimum(best, np.hypot(px - (x0 + t*vx), py - (y0 + t*vy)))
    return best * K

LAND = (512, 520, 410, [(.09, 3, 0), (.06, 5, 1), (.05, 7, 2), (.03, 11, 3)], .4)
MOUNTAIN = (560, 330, 120, [(.12, 3, 1), (.06, 5, 2)], 1)
CRATER = (560, 330, 40, [(.1, 4, 1)], 1)
FOREST = (650, 590, 130, [(.12, 3, 2), (.08, 6, 1)], 2)
CLIFFS = (250, 780, 110, [(.1, 3, 1), (.06, 5, 1)], 3)
RAVINES = (740, 800, 95, [(.15, 3, 0), (.1, 5, 1)], 4)
FORT = (840, 440, 85, [(.12, 3, 0)], 5)
MEADOW = (330, 520, 100, [(.1, 3, 0)], 6)
HIGHLANDS = (330, 300, 110, [(.15, 3, 0), (.1, 5, 2)], 7)   # пагорби на північному заході закривають огляд
RIVERS = ["M560,380 C520,450 430,430 360,470 S200,430 120,420", "M600,400 C650,470 700,520 760,560 S880,640 930,700"]
ROADS = ["M300,710 C380,650 440,620 490,610", "M490,610 C560,560 600,500 640,470 S780,440 830,450",
         "M490,610 C470,500 480,350 470,250 S500,150 512,130", "M300,710 C240,620 200,520 190,430",
         "M490,610 C580,680 660,740 730,780"]
# центр (схема), радіус і кількість будинків (у блоках)
TOWNS = [(300, 700, 70, 34, "start"), (490, 610, 45, 14, "village"), (195, 440, 38, 10, "fishing"), (512, 150, 60, 20, "port")]

# ---------- рельєф ----------
wx = (np.arange(N) * CELL + CELL / 2)[None, :].repeat(N, 0)   # блоки, схід
wy = (np.arange(N) * CELL + CELL / 2)[:, None].repeat(N, 1)   # блоки, південь
sx, sy = wx / K, wy / K                                         # координати схеми

def fbm(x, y, seed, amp=1.0):
    r = np.random.default_rng(seed); v = np.zeros_like(x)
    for octave in range(5):
        f = 0.005 * 2 ** octave
        for _ in range(3):
            a = r.uniform(0, math.pi); v += amp / 2 ** octave * np.sin(f * (x*math.cos(a) + y*math.sin(a)) + r.uniform(0, 6.3))
    return v / 3

tL = blob_t(sx, sy, *LAND)
inland = smooth(0.97, 0.45, tL)
h = SEA + 1 + 24 * inland + fbm(wx, wy, 1, 12) * inland
h = np.where(tL > 0.97, SEA + 1, h)
h = np.where(tL > 1.0, SEA - 2 - np.minimum(24, (tL - 1) * 140), h)

tH = blob_t(sx, sy, *HIGHLANDS)
h += 35 * smooth(1.0, 0.3, tH) + fbm(wx, wy, 5, 10) * smooth(1, .3, tH)
tM = blob_t(sx, sy, *MOUNTAIN)
h += 110 * np.clip(1 - tM, 0, 1) ** 1.5 + fbm(wx, wy, 2, 8) * np.clip(1 - tM, 0, 1)
tC = blob_t(sx, sy, *CRATER)
rim = h[np.abs(tC - 1) < 0.15].max()
h = np.where(tC < 1, rim - 10, h)

tCl = blob_t(sx, sy, *CLIFFS)
h += 34 * smooth(1.0, 0.6, tCl) + 6 * np.round(fbm(wx, wy, 3, 2)) * smooth(1, .5, tCl)
tR = blob_t(sx, sy, *RAVINES)
h += 16 * smooth(1.0, 0.6, tR)
groove = np.abs(np.sin((wx + wy) * 0.037 + 2 * np.sin(wx * 0.013)))
ravine_cut = (groove < 0.32) & (tR < 0.9)
h = np.where(ravine_cut, h - 24, h)

for x0, y0, rad, _, _ in TOWNS:  # вирівняти майданчики
    X0, Y0 = S(x0, y0)
    m = np.hypot(wx - X0, wy - Y0) < rad
    ref = np.median(h[m & (tL < 0.97)]) if np.any(m & (tL < 0.97)) else SEA + 2
    h = np.where(m & (tL < 0.99), np.maximum(ref, SEA + 1), h)

dRiver = dist_to_paths(sx, sy, RIVERS)
river = (dRiver < 8) & (tL < 1.02)
h = np.where(river, h - 3, h)
dRoad = dist_to_paths(sx, sy, ROADS)
road = (dRoad < 3.5) & ~river & (tL < 0.99)

H = (np.round(h / 2) * 2).astype(int)

# ---------- кольори верхніх граней ----------
PAL = {  # верх, бік
    "grass": ((0.36, 0.62, 0.22), (0.45, 0.32, 0.20)), "meadow": ((0.55, 0.75, 0.30), (0.45, 0.32, 0.20)),
    "forest": ((0.20, 0.42, 0.15), (0.38, 0.27, 0.17)), "sand": ((0.90, 0.84, 0.60), (0.85, 0.78, 0.55)),
    "seabed": ((0.78, 0.72, 0.52), (0.70, 0.64, 0.46)), "stone": ((0.52, 0.52, 0.52), (0.42, 0.42, 0.42)),
    "basalt": ((0.26, 0.24, 0.25), (0.20, 0.19, 0.20)), "lava": ((1.0, 0.35, 0.05), (0.9, 0.3, 0.05)),
    "water": ((0.20, 0.45, 0.80), (0.20, 0.42, 0.75)), "path": ((0.62, 0.50, 0.32), (0.50, 0.40, 0.26)),
    "terracotta": ((0.66, 0.42, 0.30), (0.58, 0.36, 0.26)), "dirt": ((0.45, 0.33, 0.22), (0.38, 0.28, 0.19)),
    "field": ((0.85, 0.75, 0.30), (0.45, 0.32, 0.20)), "town": ((0.58, 0.56, 0.52), (0.45, 0.43, 0.40)),
}
NAMES = list(PAL)
cat = np.full((N, N), NAMES.index("grass"))
def setc(mask, name): cat[mask] = NAMES.index(name)
setc(blob_t(sx, sy, *MEADOW) < 1, "meadow")
FX, FY = S(250, 575)
farm = (np.hypot(wx - FX, wy - FY) < 80) & ((wx // 8 + wy // 24) % 2 == 0)
setc(farm, "field")
setc(blob_t(sx, sy, *FOREST) < 1, "forest")
setc(blob_t(sx, sy, *FORT) < 1, "dirt")
setc(tR < 1, "terracotta")
slope = np.zeros_like(H)
for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
    slope = np.maximum(slope, np.abs(H - np.roll(np.roll(H, dy, 0), dx, 1)))
setc((slope >= 6) & (tL < 0.97), "stone")
setc((tCl < 1) & (slope >= 4), "stone")
setc((tH < 0.5) & (slope >= 4), "stone")
setc(tM < 0.75, "stone")
setc(tM < 0.45, "basalt")
setc(tL >= 0.97, "sand")
setc(tL > 1.0, "seabed")
for x0, y0, rad, _, _ in TOWNS:
    X0, Y0 = S(x0, y0)
    setc((np.hypot(wx - X0, wy - Y0) < rad * 0.55) & (tL < 0.99), "town")
setc(road, "path")
setc(river, "water")
setc(tC < 1, "lava")

# ---------- збірка мешу з боксів і граней ----------
class MeshBuf:
    def __init__(s): s.v, s.f, s.c, s.m = [], [], [], []
    def quad(s, pts, col, mat=0):
        b = len(s.v); s.v += pts; s.f.append((b, b+1, b+2, b+3)); s.c.append(col); s.m.append(mat)
    def box(s, x0, y0, z0, x1, y1, z1, col, mat=0, top=None):
        Y0, Y1 = SIZE - y1, SIZE - y0   # світ: y на південь -> Blender Y на північ
        s.quad([(x0, Y0, z1), (x1, Y0, z1), (x1, Y1, z1), (x0, Y1, z1)], top or col, mat)
        s.quad([(x0, Y1, z0), (x1, Y1, z0), (x1, Y0, z0), (x0, Y0, z0)], col, mat)
        s.quad([(x0, Y0, z0), (x1, Y0, z0), (x1, Y0, z1), (x0, Y0, z1)], col, mat)
        s.quad([(x1, Y1, z0), (x0, Y1, z0), (x0, Y1, z1), (x1, Y1, z1)], col, mat)
        s.quad([(x1, Y0, z0), (x1, Y1, z0), (x1, Y1, z1), (x1, Y0, z1)], col, mat)
        s.quad([(x0, Y1, z0), (x0, Y0, z0), (x0, Y0, z1), (x0, Y1, z1)], col, mat)
    def build(s, name, mats, collection=None):
        me = bpy.data.meshes.new(name); me.from_pydata(s.v, [], s.f); me.update()
        attr = me.color_attributes.new("Col", "FLOAT_COLOR", "CORNER")
        cols = np.repeat(np.array([(*lin(c), 1.0) for c in s.c], dtype=np.float32), 4, axis=0)
        attr.data.foreach_set("color", cols.ravel())
        for m in mats: me.materials.append(m)
        me.polygons.foreach_set("material_index", np.array(s.m, dtype=np.int32))
        ob = bpy.data.objects.new(name, me); (collection or bpy.context.collection).objects.link(ob)
        return ob

def mat_vc(name, rough=0.9, alpha=1.0):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; bsdf = nt.nodes["Principled BSDF"]; bsdf.inputs["Roughness"].default_value = rough
    a = nt.nodes.new("ShaderNodeAttribute"); a.attribute_name = "Col"
    nt.links.new(a.outputs["Color"], bsdf.inputs["Base Color"])
    if alpha < 1:
        bsdf.inputs["Alpha"].default_value = alpha
        try: m.surface_render_method = "BLENDED"
        except AttributeError: pass
    return m

def mat_emit(name, col, strength):
    m = mat_vc(name); bsdf = m.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Emission Color"].default_value = (*lin(col), 1); bsdf.inputs["Emission Strength"].default_value = strength
    return m

M_BASE = mat_vc("Блоки")
M_LAVA, M_GLOW = mat_emit("Лава", (1, .35, .05), 1.5), mat_emit("Світло", (1, .9, .5), 2)
M_BEAM, M_MAGIC = mat_emit("Промінь боса", (1, .1, .1), 2.5), mat_emit("Кристали", (.7, .3, 1), 2)
MATS = [M_BASE, M_LAVA, M_GLOW, M_BEAM, M_MAGIC]
M_GHOST = mat_vc("Прозорий рельєф", alpha=0.10)

terrain = MeshBuf()
Z = (H - SEA).astype(float)
Zpad = np.pad(Z, 1, constant_values=-40)
for j in range(N):
    for i in range(N):
        x0, y0 = i * CELL, j * CELL; x1, y1 = x0 + CELL, y0 + CELL
        Y0, Y1 = SIZE - y1, SIZE - y0
        z = Z[j, i]; name = NAMES[cat[j, i]]; top, side = PAL[name]
        mat = 1 if name == "lava" else 0
        terrain.quad([(x0, Y0, z), (x1, Y0, z), (x1, Y1, z), (x0, Y1, z)], top, mat)
        sidecol = PAL["stone"][1] if name in ("grass", "meadow", "forest") and z - min(Zpad[j+1, i+2], Zpad[j+1, i], Zpad[j, i+1], Zpad[j+2, i+1]) > 6 else side
        e = Zpad[j+1, i+2]
        if z > e: terrain.quad([(x1, Y0, e), (x1, Y1, e), (x1, Y1, z), (x1, Y0, z)], sidecol)
        w = Zpad[j+1, i]
        if z > w: terrain.quad([(x0, Y1, w), (x0, Y0, w), (x0, Y0, z), (x0, Y1, z)], sidecol)
        n = Zpad[j, i+1]      # північ = менший y = більший Blender Y
        if z > n: terrain.quad([(x1, Y1, n), (x0, Y1, n), (x0, Y1, z), (x1, Y1, z)], sidecol)
        s_ = Zpad[j+2, i+1]
        if z > s_: terrain.quad([(x0, Y0, s_), (x1, Y0, s_), (x1, Y0, z), (x0, Y0, z)], sidecol)
terrain_ob = terrain.build("Рельєф", MATS)

def ground(x, y):  # x, y у блоках світу
    i, j = int(np.clip(x // CELL, 0, N-1)), int(np.clip(y // CELL, 0, N-1))
    return float(Z[j, i]), NAMES[cat[j, i]]

# ---------- рослинність ----------
flora = MeshBuf()
LEAVES = [(0.16, 0.40, 0.12), (0.22, 0.48, 0.16), (0.12, 0.33, 0.10), (0.28, 0.52, 0.18)]
def tree(x, y, big=False):
    z, _ = ground(x, y); th = rng.randint(6, 10) + (5 if big else 0); w = rng.choice((5, 7, 7, 9)) + (4 if big else 0)
    flora.box(x-1, y-1, z, x+1, y+1, z+th, (0.36, 0.25, 0.14))
    leaf = rng.choice(LEAVES)
    flora.box(x-w/2, y-w/2, z+th-3, x+w/2, y+w/2, z+th+3, leaf)
    flora.box(x-w/2+2, y-w/2+2, z+th+3, x+w/2-2, y+w/2-2, z+th+5, leaf)
def near_town(x, y):
    return any(math.hypot(x - S(a, b)[0], y - S(a, b)[1]) < r + 6 for a, b, r, _, _ in TOWNS)
for j in range(N):
    for i in range(N):
        name = NAMES[cat[j, i]]; x, y = i*CELL + rng.uniform(0, CELL), j*CELL + rng.uniform(0, CELL)
        p = {"forest": 0.30, "grass": 0.012, "meadow": 0.004}.get(name, 0)
        if p and rng.random() < p and not near_town(x, y) and dRoad[j, i] > 5 and dRiver[j, i] > 9:
            tree(x, y, big=(name == "forest" and rng.random() < 0.15))
flora_ob = flora.build("Дерева", MATS)

# ---------- споруди (позиції зі схеми, розміри в блоках) ----------
st = MeshBuf()
WALLS = [(0.86, 0.82, 0.72), (0.70, 0.55, 0.36), (0.80, 0.78, 0.74)]
ROOFS = [(0.55, 0.20, 0.16), (0.25, 0.30, 0.45), (0.35, 0.25, 0.18)]
def on_road(x, y, pad):
    return dist_to_paths(np.array([x / K]), np.array([y / K]), ROADS)[0] < pad
def house(x, y, w, d, hgt, roof=None):
    z, _ = ground(x, y); wall = rng.choice(WALLS); roof = roof or rng.choice(ROOFS)
    st.box(x-w/2, y-d/2, z-2, x+w/2, y+d/2, z+hgt, wall)
    for k in range(3):
        st.box(x-w/2-1+k*1.5, y-d/2-1+k*1.5, z+hgt+k*1.5, x+w/2+1-k*1.5, y+d/2+1-k*1.5, z+hgt+(k+1)*1.5, roof)
    st.box(x-0.5, y-d/2-0.2, z, x+1.5, y-d/2, z+3, (0.30, 0.20, 0.12))  # двері
def town(cx, cy, rad, count):
    placed = []
    for _ in range(count * 30):
        if len(placed) >= count: break
        a, r = rng.uniform(0, 6.283), rng.uniform(10, rad)
        x, y = cx + r*math.cos(a), cy + r*math.sin(a); w, d = rng.choice((7, 9, 11)), rng.choice((7, 9))
        _, nm = ground(x, y)
        if nm in ("water", "sand", "seabed") or any(abs(x-px) < (w+pw)/2+3 and abs(y-py) < (d+pd)/2+3 for px, py, pw, pd in placed): continue
        if on_road(x, y, w/2 + 3): continue
        placed.append((x, y, w, d)); house(x, y, w, d, rng.randint(5, 8))
for x0, y0, rad, count, _ in TOWNS:
    town(*S(x0, y0), rad, count)

# стартове місто: ратуша з вежею й мур
TX, TY = S(300, 700); z, _ = ground(TX, TY)
st.box(TX-8, TY-8, z, TX+8, TY+8, z+14, (0.80, 0.78, 0.74)); st.box(TX-4, TY-4, z+14, TX+4, TY+4, z+28, (0.75, 0.73, 0.70))
st.box(TX-5, TY-5, z+28, TX+5, TY+5, z+31, (0.25, 0.30, 0.45)); st.box(TX-1.5, TY-1.5, z+31, TX+1.5, TY+1.5, z+34, (1, .85, .3), 2)
for k in range(160):
    a = k / 160 * 6.283; x, y = TX + 76*math.cos(a), TY + 76*math.sin(a)
    if on_road(x, y, 6): continue
    zz, nm = ground(x, y)
    if nm in ("water", "seabed"): continue
    if k % 20 == 0: st.box(x-4, y-4, zz-2, x+4, y+4, zz+11, (0.50, 0.50, 0.52), top=(0.35, 0.35, 0.38))
    else: st.box(x-1.5, y-1.5, zz-2, x+1.5, y+1.5, zz+5, (0.58, 0.58, 0.60))

# порт: причали й корабель
PX, PY = S(512, 150)
for dx in (-48, 0, 48):
    st.box(PX+dx-2, PY-110, -1, PX+dx+2, PY-30, 1.2, (0.48, 0.35, 0.22))
SX, SY = PX + 22, PY - 80
st.box(SX-3, SY-15, -2, SX+3, SY+15, 3, (0.42, 0.28, 0.16)); st.box(SX-2, SY-12, 3, SX+2, SY+12, 5, (0.50, 0.36, 0.22))
st.box(SX-0.5, SY-0.5, 5, SX+0.5, SY+0.5, 30, (0.36, 0.25, 0.14)); st.box(SX-6, SY-0.2, 12, SX+6, SY+0.2, 27, (0.95, 0.94, 0.90))

# маяк
LX, LY = S(140, 560); zl, _ = ground(LX, LY)
for k in range(8):
    st.box(LX-4, LY-4, zl+k*4, LX+4, LY+4, zl+(k+1)*4, (0.95, 0.95, 0.95) if k % 2 == 0 else (0.75, 0.15, 0.12))
st.box(LX-3, LY-3, zl+32, LX+3, LY+3, zl+36, (1, .9, .5), 2); st.box(LX-5, LY-5, zl+36, LX+5, LY+5, zl+38, (0.2, 0.2, 0.2))

# вітряк на фермі
WX, WY = S(232, 548); zw, _ = ground(WX, WY)
st.box(WX-4, WY-4, zw, WX+4, WY+4, zw+16, (0.86, 0.82, 0.72)); st.box(WX-5, WY-5, zw+16, WX+5, WY+5, zw+19, (0.55, 0.20, 0.16))
st.box(WX-0.5, WY-6, zw+5, WX+0.5, WY-5, zw+27, (0.85, 0.85, 0.80)); st.box(WX-11, WY-6, zw+15.5, WX+11, WY-5, zw+16.5, (0.85, 0.85, 0.80))

# руїни фортеці з ареною боса
FX, FY = S(840, 440); zf, _ = ground(FX, FY)
for side in range(4):
    for k in range(20):
        if rng.random() < 0.25: continue
        t = -50 + k * 5
        x, y = [(FX+t, FY-50), (FX+50, FY+t), (FX-t, FY+50), (FX-50, FY-t)][side]
        st.box(x-2.5, y-2.5, zf-2, x+2.5, y+2.5, zf+rng.choice((4, 6, 8, 10)), (0.40, 0.40, 0.42))
for tx, ty, th in ((-50, -50, 18), (50, -50, 12), (50, 50, 20), (-50, 50, 8)):
    st.box(FX+tx-6, FY+ty-6, zf-2, FX+tx+6, FY+ty+6, zf+th, (0.36, 0.36, 0.38))
st.box(FX-1, FY-1, zf, FX+1, FY+1, zf+220, (1, .1, .1), 3)

# точки контенту на поверхні
CAVES = [(370, 650), (600, 710), (380, 360), (750, 760), (420, 220), (520, 880), (140, 600)]
QUESTS = [(250, 570), (720, 620), (300, 330), (660, 870), (700, 360), (560, 520), (400, 470), (820, 560)]
CAMPS = [(420, 560), (830, 700), (610, 200), (300, 260), (680, 420)]
DUNGEON = (640, 470)
for x, y in [S(*p) for p in CAVES]:
    zc, _ = ground(x, y); st.box(x-6, y-4, zc-2, x+6, y+4, zc+8, (0.42, 0.42, 0.44)); st.box(x-3, y-4.2, zc, x+3, y-4, zc+5, (0.05, 0.05, 0.05))
for x, y in [S(*p) for p in QUESTS]:
    zq, _ = ground(x, y); st.box(x-0.5, y-0.5, zq, x+0.5, y+0.5, zq+9, (0.36, 0.25, 0.14)); st.box(x-1.5, y-1.5, zq+9, x+1.5, y+1.5, zq+12, (1, .8, .1), 2)
for x, y in [S(*p) for p in CAMPS]:
    zt, _ = ground(x, y)
    for k in range(3): st.box(x-5+k*1.5, y-4, zt+k*2, x+5-k*1.5, y+4, zt+(k+1)*2, (0.62, 0.48, 0.30))
    st.box(x+7, y-1, zt, x+9, y+1, zt+1.5, (1, .6, .2), 2)
DX, DY = S(*DUNGEON); zd, _ = ground(DX, DY)
st.box(DX-8, DY-4, zd-2, DX+8, DY+4, zd+12, (0.30, 0.30, 0.32)); st.box(DX-4, DY-4.5, zd, DX+4, DY-4, zd+8, (0.7, 0.2, 1), 4)
structures_ob = st.build("Споруди", MATS)

# ---------- печери ----------
# Зали: центр (схема), радіус (схема), підлога Y у грі, висота, тип
HALLS = [
    ("Перша печера (навчання)", 370, 650, 20, 50, 8, "stone"),
    ("Кришталева зала", 330, 520, 42, 18, 18, "crystal"),
    ("Підземне озеро", 470, 470, 55, 8, 20, "lake"),
    ("Грибна печера", 660, 600, 45, 24, 14, "mushroom"),
    ("Лавові глибини (підземелля)", 560, 330, 48, -24, 22, "lava"),
    ("Стара копальня", 740, 790, 38, 30, 10, "mine"),
    ("Морський грот", 150, 580, 30, 52, 10, "grotto"),
    ("Склеп під фортецею", 840, 440, 32, 34, 12, "crypt"),
    ("Безодня", 430, 760, 30, -50, 34, "abyss"),
]
HALL = {h[0]: h for h in HALLS}
def hall_pt(name):
    _, x, y, _, fy, hh, _ = HALL[name]; return (x, y, fy + 3)
def surf(x, y):  # вхід на поверхні: Y у грі
    X, Y = S(x, y); return ground(X, Y)[0] + SEA - 1
TUNNELS = [
    [(370, 650, surf(370, 650)), hall_pt("Перша печера (навчання)")],
    [hall_pt("Перша печера (навчання)"), (350, 590, 34), hall_pt("Кришталева зала")],
    [(380, 360, surf(380, 360)), (360, 430, 40), hall_pt("Кришталева зала")],
    [hall_pt("Кришталева зала"), (400, 500, 14), hall_pt("Підземне озеро")],
    [hall_pt("Підземне озеро"), (560, 540, 16), hall_pt("Грибна печера")],
    [(600, 710, surf(600, 710)), (630, 660, 36), hall_pt("Грибна печера")],
    [(DUNGEON[0], DUNGEON[1], surf(*DUNGEON)), (610, 410, 20), (585, 360, 0), hall_pt("Лавові глибини (підземелля)")],
    [hall_pt("Підземне озеро"), (510, 400, -6), hall_pt("Лавові глибини (підземелля)")],
    [(750, 760, surf(750, 760)), hall_pt("Стара копальня")],
    [hall_pt("Стара копальня"), (700, 700, 28), hall_pt("Грибна печера")],
    [(140, 600, surf(140, 600)), hall_pt("Морський грот")],
    [(FX / K + 30, FY / K, surf(FX / K + 30, FY / K)), hall_pt("Склеп під фортецею")],
    [hall_pt("Склеп під фортецею"), (760, 520, 28), hall_pt("Грибна печера")],
    [(420, 220, surf(420, 220)), (460, 290, 30), (470, 380, 14), hall_pt("Підземне озеро")],
    [(520, 880, surf(520, 880)), (470, 800, 30), hall_pt("Безодня")],
    [hall_pt("Безодня"), (400, 700, 10), hall_pt("Перша печера (навчання)")],
]
CAVE_COL = {"stone": (0.55, 0.55, 0.58), "crystal": (0.62, 0.45, 0.85), "lake": (0.30, 0.55, 0.85),
            "mushroom": (0.40, 0.75, 0.70), "lava": (0.85, 0.35, 0.15), "mine": (0.72, 0.55, 0.35),
            "grotto": (0.35, 0.70, 0.80), "crypt": (0.45, 0.42, 0.40), "abyss": (0.28, 0.25, 0.40),
            "tunnel": (0.78, 0.76, 0.70)}
V = 4  # розмір вокселя печер у блоках
vox = {}
def carve(i, j, k, kind):
    if (i, j, k) not in vox or kind != "tunnel": vox[(i, j, k)] = kind
for name, x, y, r, floor, hh, kind in HALLS:
    X, Y = S(x, y); R = r * K
    for i in range(int((X - R) // V), int((X + R) // V) + 1):
        for j in range(int((Y - R) // V), int((Y + R) // V) + 1):
            dx, dy = (i * V + 2 - X) / R, (j * V + 2 - Y) / R
            d = dx*dx + dy*dy + 0.25 * math.sin(i * 0.9) * math.cos(j * 0.7)
            if d >= 1: continue
            top = floor + hh * (1 - d) ** 0.5 + 2
            for k in range(int(floor // V), int(top // V) + 1):
                carve(i, j, k, kind)
for path in TUNNELS:
    for (x0, y0, z0), (x1, y1, z1) in zip(path, path[1:]):
        X0, Y0 = S(x0, y0); X1, Y1 = S(x1, y1)
        steps = int(math.hypot(X1 - X0, Y1 - Y0, z1 - z0) / 2) + 1
        for s in range(steps + 1):
            t = s / steps
            x, y, z = X0 + (X1 - X0) * t, Y0 + (Y1 - Y0) * t, z0 + (z1 - z0) * t
            for oi in (-1, 0, 1):
                for oj in (-1, 0, 1):
                    if abs(oi) + abs(oj) < 2: carve(int(x // V) + oi, int(y // V) + oj, int(z // V), "tunnel")
caves = MeshBuf()
for (i, j, k), kind in vox.items():
    col = CAVE_COL[kind]; mat = 1 if kind == "lava" else 0
    x0, y0, z0 = i * V, j * V, k * V - SEA; x1, y1, z1 = x0 + V, y0 + V, z0 + V
    Y0, Y1 = SIZE - y1, SIZE - y0
    if (i, j, k + 1) not in vox: caves.quad([(x0, Y0, z1), (x1, Y0, z1), (x1, Y1, z1), (x0, Y1, z1)], col, mat)
    if (i, j, k - 1) not in vox: caves.quad([(x0, Y1, z0), (x1, Y1, z0), (x1, Y0, z0), (x0, Y0, z0)], col, mat)
    if (i, j + 1, k) not in vox: caves.quad([(x0, Y0, z0), (x1, Y0, z0), (x1, Y0, z1), (x0, Y0, z1)], col, mat)
    if (i, j - 1, k) not in vox: caves.quad([(x1, Y1, z0), (x0, Y1, z0), (x0, Y1, z1), (x1, Y1, z1)], col, mat)
    if (i + 1, j, k) not in vox: caves.quad([(x1, Y0, z0), (x1, Y1, z0), (x1, Y1, z1), (x1, Y0, z1)], col, mat)
    if (i - 1, j, k) not in vox: caves.quad([(x0, Y1, z0), (x0, Y0, z0), (x0, Y0, z1), (x0, Y1, z1)], col, mat)
# прикраси залів: кристали, гриби, підпори копальні
deco = MeshBuf()
for name, x, y, r, floor, hh, kind in HALLS:
    X, Y = S(x, y); R = r * K * 0.7; z = floor - SEA
    for _ in range({"crystal": 40, "mushroom": 30, "mine": 18, "lava": 25}.get(kind, 0)):
        a, d = rng.uniform(0, 6.283), rng.uniform(0, R); px, py = X + d*math.cos(a), Y + d*math.sin(a)
        if kind == "crystal":
            hgt = rng.uniform(4, 10); deco.box(px-1, py-1, z, px+1, py+1, z+hgt, (0.8, 0.5, 1), 4)
        elif kind == "mushroom":
            hgt = rng.uniform(5, 10); deco.box(px-1, py-1, z, px+1, py+1, z+hgt, (0.9, 0.88, 0.80))
            deco.box(px-4, py-4, z+hgt, px+4, py+4, z+hgt+2, rng.choice(((0.25, 0.75, 0.85), (0.55, 0.35, 0.85))), 4)
        elif kind == "mine":
            deco.box(px-3, py-0.5, z, px-2, py+0.5, z+6, (0.45, 0.32, 0.20)); deco.box(px+2, py-0.5, z, px+3, py+0.5, z+6, (0.45, 0.32, 0.20))
            deco.box(px-3, py-0.5, z+6, px+3, py+0.5, z+7, (0.45, 0.32, 0.20))
        elif kind == "lava":
            deco.box(px-3, py-3, z, px+3, py+3, z+1, (1, .4, .05), 1)
caves_ob = caves.build("Печери", MATS)
deco_ob = deco.build("Прикраси печер", MATS)
cave_floor = len({(i, j) for (i, j, k) in vox}) * V * V

# ---------- море, світло, камери ----------
bpy.ops.mesh.primitive_plane_add(size=SIZE * 3, location=(SIZE/2, SIZE/2, -0.4))
sea = bpy.context.active_object; sea.name = "Море"
mw = bpy.data.materials.new("Вода"); mw.use_nodes = True
b = mw.node_tree.nodes["Principled BSDF"]
b.inputs["Base Color"].default_value = (*lin((0.16, 0.45, 0.72)), 1); b.inputs["Roughness"].default_value = 0.08; b.inputs["Alpha"].default_value = 0.82
try: mw.surface_render_method = "BLENDED"
except AttributeError: pass
sea.data.materials.append(mw)

world = bpy.context.scene.world or bpy.data.worlds.new("Небо"); bpy.context.scene.world = world
world.use_nodes = True; world.node_tree.nodes["Background"].inputs["Color"].default_value = (*lin((0.62, 0.78, 0.96)), 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.9
sun = bpy.data.lights.new("Сонце", "SUN"); sun.energy = 4.0; sun.angle = math.radians(3)
so = bpy.data.objects.new("Сонце", sun); bpy.context.collection.objects.link(so)
so.rotation_euler = (math.radians(50), 0, math.radians(-40))

C = SIZE / 2
def camera(name, loc, look, ortho=None, lens=35):
    cd = bpy.data.cameras.new(name); cd.clip_end = 30000
    if ortho: cd.type = "ORTHO"; cd.ortho_scale = ortho
    else: cd.lens = lens
    co = bpy.data.objects.new(name, cd); bpy.context.collection.objects.link(co); co.location = loc
    tgt = bpy.data.objects.new(name + " ціль", None); bpy.context.collection.objects.link(tgt); tgt.location = look
    c = co.constraints.new("TRACK_TO"); c.target = tgt; c.track_axis = "TRACK_NEGATIVE_Z"; c.up_axis = "UP_Y"
    return co
def top_camera(name):  # прямо згори, північ угорі (без TRACK_TO, щоб кадр не перевертався)
    cd = bpy.data.cameras.new(name); cd.clip_end = 30000; cd.type = "ORTHO"; cd.ortho_scale = 1590
    co = bpy.data.objects.new(name, cd); bpy.context.collection.objects.link(co)
    co.location = (C, C, 3000); co.rotation_euler = (0, 0, 0)
    return co
def B(x, y, z):  # точка схеми -> Blender
    X, Y = S(x, y); return (X, SIZE - Y, z)
cams = {
    "ostriv-1-oglyad": (camera("Огляд", (C - 1900, C - 1900, 1800), (C, C, 10), ortho=1770), (1920, 1080), "surface"),
    "ostriv-1-zverhu": (top_camera("Згори"), (1400, 1400), "surface"),
    "ostriv-1-start": (camera("Вид зі старту", B(205, 845, 80), B(520, 420, 45), lens=24), (1920, 1080), "surface"),
    "ostriv-1-port": (camera("Вид на порт і гору", B(420, 20, 75), B(560, 330, 75), lens=28), (1920, 1080), "surface"),
    "ostriv-1-pechery": (top_camera("Печери згори"), (1400, 1400), "caves"),
    "ostriv-1-rozriz": (camera("Розріз", (C, -2500, -10), (C, C, -10), ortho=1600), (1920, 720), "caves"),
}

sc = bpy.context.scene
for eng in ("BLENDER_EEVEE", "BLENDER_EEVEE_NEXT"):
    try: sc.render.engine = eng; break
    except TypeError: continue
try: sc.eevee.taa_render_samples = 32
except AttributeError: pass
sc.view_settings.view_transform = "Standard"
sc.render.image_settings.file_format = "PNG"

def mode(kind):  # surface: звичайний вигляд; caves: рельєф прозорий, печери видно
    ghost = kind == "caves"
    for i in range(len(MATS)):
        terrain_ob.data.materials[i] = M_GHOST if ghost else MATS[i]
    for ob in (flora_ob, structures_ob, sea):
        ob.hide_render = ghost
    for ob in (caves_ob, deco_ob):
        ob.hide_render = not ghost

os.makedirs(OUT, exist_ok=True)
for fname, (cam, res, kind) in cams.items():
    mode(kind); sc.camera = cam
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.filepath = os.path.join(OUT, fname + ".png")
    bpy.ops.render.render(write_still=True)
mode("surface"); caves_ob.hide_render = deco_ob.hide_render = False
sc.camera = cams["ostriv-1-oglyad"][0]
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "ostriv-1.blend"))
try: os.remove(os.path.join(OUT, "ostriv-1.blend1"))
except OSError: pass
land_area = int(np.sum(tL < 1.0)) * CELL * CELL
print("ISLAND1 DONE land_blocks2=%d cave_floor_blocks2=%d halls=%d voxels=%d" % (land_area, cave_floor, len(HALLS), len(vox)))
