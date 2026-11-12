# Острів 1 (Lv 1-40), v9 (поверхня + місто ельфів + підземний шар, план 16 розд. 28): блоки 1:1, квадрат 1536×1536; печери поки з v8.
# Лише карта для огляду, без логіки плагіна. Схема: Нотатки/05-Дослідження/karta/ostriv-1-luky.svg,
# розкладка та сама, що в build_island1.py (координати схеми × K).
# Формат Anvil 1.21.1 (DataVersion 3955): гра 26.x сама оновить світ під час першого відкриття.
# Запуск: "<Blender>/5.2/python/bin/python.exe" island1_world.py <папка світу>   (потрібен numpy)
import sys, os, math, zlib, gzip, struct, random, time, json, functools
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stamp
from tools.v9.common import (FOREST as ELF_FOREST, GATE as ELF_GATE, MARKET as ELF_MARKET,
                             POND as ELF_POND, TREE as ELF_TREE, CORE_R as ELF_CORE_R,
                             ENTR as SPRING_GATE, RIVER_SURF, RIVER_UG, ROOMS as RUSLO_ROOMS, BACK as RUSLO_BACK, MOUTH as RUSLO_MOUTH)
RIVER_SURF = list(RIVER_SURF[:-1]) + [(606, 646), (601, 666), (591, 686)]   # v10: повз браму до західної річки, а не в нікуди

OUT = sys.argv[1]
SIZE, K, SEA = 1536, 1.5, 63          # вода до Y=62
DATA_VERSION, VERSION_NAME = 3955, "1.21.1"
YMIN, NY = -64, 384
rng = random.Random(11)
nrng = np.random.default_rng(11)
t_start = time.time()

def log(msg):
    print(f"[{time.time() - t_start:6.1f}s] {msg}", flush=True)

def S(x, y):  # точка схеми -> блоки світу (x на схід, z на південь)
    return int(round(x * K)), int(round(y * K))

# ---------- розкладка (як у build_island1.py) ----------
LAND = (512, 520, 410, [(.09, 3, 0), (.06, 5, 1), (.05, 7, 2), (.03, 11, 3)], .4)
VOLC = (823, 257)   # v7: вулкан — кінцева найвища вершина хребта біля північно-східного берега (як Котопахі в Андах)
MOUNTAIN = (823, 257, 120, [(.12, 3, 1), (.06, 5, 2)], 1)
CRATER = (823, 257, 40, [(.1, 4, 1)], 1)
FOREST = (650, 590, 130, [(.12, 3, 2), (.08, 6, 1)], 2)
CLIFFS = (250, 780, 110, [(.1, 3, 1), (.06, 5, 1)], 3)
RAVINES = (740, 800, 95, [(.15, 3, 0), (.1, 5, 1)], 4)
FORT = (840, 440, 85, [(.12, 3, 0)], 5)
MEADOW_ZONE = (330, 520, 100, [(.1, 3, 0)], 6)
HIGHLANDS = (330, 300, 110, [(.15, 3, 0), (.1, 5, 2)], 7)
def cr_path(pts):  # точки світу -> шлях схеми з кривих Catmull-Rom (плавна річка чи траса)
    P = [(x / K, y / K) for x, y in pts]; P = [P[0]] + P + [P[-1]]
    s = f"M{P[1][0]:.1f},{P[1][1]:.1f}"
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6); c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        s += f" C{c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} {p2[0]:.1f},{p2[1]:.1f}"
    return s
# v5: нова річка від західної річки на південь до моря, повз стартове місто зі сходу й Пшеничне із заходу (правка 14)
NEW_RIVER = cr_path([(500, 782), (498, 790),   # v10: власне джерело, без розгалуження від західної річки
                      (522, 860), (546, 932), (575, 990), (650, 1032), (760, 1062), (830, 1135),
                     (815, 1236), (842, 1330), (905, 1445)])
RIVERS = ["M560,380 C520,450 430,430 360,470 S200,430 120,420", "M600,400 C650,470 700,520 760,560 S880,640 930,700", NEW_RIVER,
          cr_path([(642, 455), (650, 380), (648, 290), (645, 160)])]            # v7: з хребта на північ, гирло — гавань порту
STREAMS = [cr_path(p_) for p_ in ([(760, 470), (790, 525), (815, 585)], [(882, 452), (872, 520), (852, 582)], [(968, 440), (935, 505), (882, 592)],
                                   [(432, 520), (412, 440), (380, 360), (320, 280)], [(1040, 452), (1030, 520), (985, 590), (930, 640)])]   # потоки з хребта
ROADS = ["M300,710 C380,650 440,620 490,610", "M490,610 C560,560 600,500 640,470 S780,440 830,450",
         cr_path([(735, 915), (705, 795), (690, 720), (690, 650), (690, 400), (690, 330), (655, 250), (615, 192)]),   # v8: прямо північ — південь крізь тунель
         "M300,710 C250,650 200,540 172,482",                                   # v8: старт → Рибальське (нове місце біля моря)
         "M490,610 C580,680 660,740 730,780",
         "M172,478 C190,440 200,405 203,377 S250,280 313,245",                  # v8: узбережжя → перевал → Хвойне село
         "M313,243 C340,215 360,185 380,165 S405,150 410,140",                  # пагорби → порт
         "M556,665 C570,730 585,790 590,840",                                   # Узлісся → Пшеничне
         "M300,710 C330,750 360,790 383,815"]                                   # v5: третя брама старту → кладовище
# хаби зон: центр, радіус плато (блоки), кількість будинків, вид (нотатки 16; v5 — фортеці більші, плато ширше)
TOWNS = [(300, 700, 104, 40, "start"), (ELF_MARKET[0] / K, ELF_MARKET[1] / K, 28, 6, "village"), (172, 478, 36, 10, "fishing"), (410, 151, 72, 20, "port"), (167, 580, 70, 18, "port_w"),
         (313, 243, 36, 14, "hills"), (590, 840, 56, 16, "south")]
ZONES = [  # id, назва, рівні, хаб, «насіння» (схема) для поділу острова
    ("start", "Старт", "1–5", "start", [(300, 700), (330, 790), (250, 640), (380, 700)]),
    ("forest", "Узлісся й ельфійський ліс", "5–10 / 22–28", "village", [(490, 610), (650, 590), (560, 520), (720, 640)]),
    ("coast", "Узбережжя", "5–10", "fishing", [(172, 478), (140, 560), (160, 500), (250, 470)]),
    ("hills", "Північні пагорби", "10–15", "hills", [(313, 243), (330, 300), (400, 230), (300, 200), (420, 330)]),
    ("south", "Яри й південь", "10–15", "south", [(590, 840), (740, 800), (470, 850), (660, 880)]),
    ("volcano", "Вулкан і фортеця", "15–20", "port", [(512, 150), (560, 330), (840, 440), (700, 300), (900, 600), (650, 200)]),
]
QUESTS = {  # хаб: (назва квесту, ціль — назва точки); остання — «хлібна крихта»
    "start": [("Перший урожай", "Ферма з вітряком"), ("Загублена вівця", "Загін"), ("Бджоли", "Пасіка"), ("Перша печера", "Перша печера"),
              ("Свічка в святилищі", "Святилище"), ("Дорога на Узлісся", "Узлісся"), ("Лист рибалкам", "Рибальське село")],
    "village": [("Мисливці", "Мисливський табір"), ("Серце лісу", "Велетенське дерево"), ("Розбитий віз", "Розбитий віз"), ("Меч героя", "Меч героя"),
                ("Гриби під лісом", "Грибна печера"), ("На південь", "Село Пшеничне")],
    "fishing": [("Вогонь маяка", "Маяк"), ("Корабель на березі", "Розбитий корабель"), ("Пірс", "Рибальський пірс"), ("Через перевал", "Хвойне село")],
    "hills": [("Сторожова вежа", "Сторожова вежа"), ("Колос", "Голова колоса"), ("Акведук", "Акведук"), ("Водоспад", "Водоспад"),
              ("Небесні острови", "Літаючі острови"), ("До порту", "Порт")],
    "south": [("Яри й копальня", "Стара копальня"), ("Археологи", "Табір археологів"), ("Вежа в ярах", "Вежа в ярах"), ("Скелет звіра", "Скелет звіра"),
              ("Церква", "Покинута церква"), ("До порту", "Порт")],
    "port": [("Табір розбійників", "Табір розбійників"), ("Аванпост", "Аванпост розбійників"), ("Лавові глибини", "Лавові глибини"),
             ("Край кратера", "Край кратера"), ("Собор", "Обсидіановий собор")],
}
HALLS = [  # назва, центр (схема), радіус (схема), підлога Y, висота, тип
    ("Перша печера", 370, 650, 20, 50, 8, "stone"),
    ("Кришталева зала", 330, 520, 42, 18, 18, "crystal"),
    ("Підземне озеро", 470, 470, 55, 8, 20, "lake"),
    ("Грибна печера", 660, 600, 45, 24, 14, "mushroom"),
    ("Лавові глибини", 823, 257, 48, -24, 22, "lava"),
    ("Стара копальня", 740, 790, 38, 30, 10, "mine"),
    ("Морський грот", 150, 580, 30, 52, 10, "grotto"),
    ("Склеп під собором", 840, 440, 32, 34, 12, "crypt"),
    ("Безодня", 430, 760, 30, -50, 34, "abyss"),
]
CAVE_ENTRANCES = [(370, 650), (600, 710), (380, 360), (750, 760), (420, 220), (520, 880), (140, 600)]
DUNGEON = (790, 345)
DUNGEONS_OPEN = False   # v10: данжі відкладено (правка користувача) — входи запечатані, підземні об'єми не вирізаються

# ---------- геометрія ----------
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
    tok = d.replace(",", " ").replace("M", " M ").replace("C", " C ").replace("S", " S ").replace("L", " L ").split()
    pts, i, cur, last_c2 = [], 0, None, None
    def bez(p0, p1, p2, p3, n=48):
        return [tuple((1-t)**3*np.array(p0) + 3*(1-t)**2*t*np.array(p1) + 3*(1-t)*t*t*np.array(p2) + t**3*np.array(p3)) for t in np.linspace(0, 1, n)]
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
        elif c == "L":
            p3 = (float(tok[i]), float(tok[i+1])); i += 2
            pts += [(cur[0] + (p3[0] - cur[0]) * t, cur[1] + (p3[1] - cur[1]) * t) for t in np.linspace(0, 1, 24)[1:]]; cur = last_c2 = p3
    return pts

@functools.lru_cache(maxsize=None)
def world_path(d):  # точки шляху в блоках світу
    return tuple((x * K, y * K) for x, y in parse_path(d))

def dist_to_paths(px, pz, paths):  # px, pz у блоках
    best = np.full(np.broadcast(px, pz).shape, 1e9)
    for d in paths:
        pts = world_path(d)
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            vx, vy = x1 - x0, y1 - y0
            L = vx*vx + vy*vy or 1
            t = np.clip(((px - x0)*vx + (pz - y0)*vy) / L, 0, 1)
            best = np.minimum(best, np.hypot(px - (x0 + t*vx), pz - (y0 + t*vy)))
    return best

def fbm(x, y, seed, amp=1.0, f0=0.005):
    r = np.random.default_rng(seed); v = np.zeros(np.broadcast(x, y).shape)
    for octave in range(5):
        f = f0 * 2 ** octave
        for _ in range(3):
            a = r.uniform(0, math.pi); v += amp / 2 ** octave * np.sin(f * (x*math.cos(a) + y*math.sin(a)) + r.uniform(0, 6.3))
    return v / 3

# ---------- рельєф 1:1 (масиви [z, x]) ----------
ax = np.arange(SIZE) + 0.5
wx, wz = ax[None, :], ax[:, None]
sx, sz = wx / K, wz / K
tL = blob_t(sx, sz, *LAND)
inland = smooth(0.97, 0.45, tL)
h = SEA + 1 + 24 * inland + fbm(wx, wz, 1, 12) * inland
dune = SEA + 1 + 3 * smooth(1.0, 0.93, tL) + np.clip(fbm(wx, wz, 9, 5), 0, 4) * smooth(1.0, 0.965, tL) * smooth(0.92, 0.95, tL)
h = np.where(tL > 0.93, np.minimum(h, dune), h)                      # широкий пляж із дюнами
h = np.where(tL > 1.0, SEA - 1 - np.minimum(26, (tL - 1) * 45 + np.maximum(0, tL - 1.06) * 160), h)   # мілина, потім глибше
tH = blob_t(sx, sz, *HIGHLANDS)
h += 35 * smooth(1.0, 0.3, tH) + fbm(wx, wz, 5, 10) * smooth(1, .3, tH)
HILLS = [(420, 420, 45, 16), (600, 800, 50, 18), (700, 250, 40, 20), (250, 640, 35, 12), (560, 720, 40, 14),
         (880, 760, 35, 14), (380, 600, 30, 10), (820, 300, 40, 16), (450, 330, 35, 12), (640, 880, 40, 12)]
for hx, hz, hr, hh_ in HILLS:  # окремі пагорби, щоб рівнина не була пласкою
    X0, Z0 = S(hx, hz)
    h += hh_ * np.exp(-((wx - X0) ** 2 + (wz - Z0) ** 2) / (hr * K) ** 2) * inland
def path_info(px, pz, pts):
    """Відстань до ламаної, бік (знак векторного добутку) і частка довжини найближчої точки."""
    pts = np.asarray(pts, float); seg = np.hypot(*np.diff(pts, axis=0).T); cum = np.concatenate([[0], np.cumsum(seg)])
    best = np.full(np.broadcast(px, pz).shape, 1e9); side = np.zeros_like(best); s = np.zeros_like(best)
    for i in range(len(pts) - 1):
        (x0, z0), (x1, z1) = pts[i], pts[i + 1]; vx, vz = x1 - x0, z1 - z0; L2 = vx * vx + vz * vz or 1
        t = np.clip(((px - x0) * vx + (pz - z0) * vz) / L2, 0, 1)
        d = np.hypot(px - (x0 + t * vx), pz - (z0 + t * vz)); upd = d < best
        best = np.where(upd, d, best); side = np.where(upd, vx * (pz - z0) - vz * (px - x0), side); s = np.where(upd, cum[i] + t * seg[i], s)
    return best, side, s / cum[-1]

# v8: хребет зі «столами» (плато під квести й хатки), сідловинами й відрогами; вулкан — плече, угнутий конус і кальдера
# (нотатка 24: полонини, Seceda, Рорайма, Тейде, Фудзі)
RIDGE_PTS = [(150, 600), (300, 562), (470, 522), (640, 472), (800, 442), (950, 420), (1080, 402), (1160, 392)]
RIDGES = [(cr_path(RIDGE_PTS), 92, 64)]
def cos_interp(x, xs, ys):   # плавна (косинусна) інтерполяція між контрольними точками
    xs, ys = np.asarray(xs, float), np.asarray(ys, float); i = np.clip(np.searchsorted(xs, x) - 1, 0, len(xs) - 2)
    t = np.clip((x - xs[i]) / (xs[i + 1] - xs[i]), 0, 1); t = (1 - np.cos(np.pi * t)) / 2
    return ys[i] + (ys[i + 1] - ys[i]) * t
# висота над рівниною вздовж хребта (s 0 захід … 1 вулкан): столи A (s≈.27), B (≈.50, під ним тунель), C (≈.71), D (≈.86), плече (≈.95)
RS_S = [0, .07, .13, .20, .27, .34, .40, .45, .50, .55, .60, .66, .71, .76, .80, .85, .89, .95, 1.0]
RS_H = [52, 62, 48, 68, 72, 68, 50, 78, 82, 78, 58, 86, 90, 86, 66, 86, 90, 96, 100]   # v8: на заході гори до берега; на сході хребет росте у вулкан
RS_T = [.18, .26, .12, .28, .36, .28, .10, .30, .38, .30, .10, .28, .36, .28, .10, .26, .26, .30, .30]   # частка ширини — рівний верх
_rd, _rside, _rs = path_info(wx, wz, world_path(RIDGES[0][0]))
_W = np.where(_rside > 0, 150.0, 115.0) * (0.55 + 0.45 * smooth(0.12, 0.26, _rs)) * (0.9 + 0.2 * _rs)   # на заході хребет вужчий: гори до берега без сіл на схилах        # південний бік пологий і ширший, північний — крутіший
_top = cos_interp(_rs, RS_S, RS_T)
_v = np.clip((1 - _rd / _W) / (1 - _top), 0, 1); _prof = _v * _v * (3 - 2 * _v)
_hadd = cos_interp(_rs, RS_S, RS_H)
RIDGEH = _hadd * _prof
SPURS = [(400, 538, 1, 100), (505, 512, 1, 90), (820, 439, 1, 120), (925, 424, 1, 110), (1005, 413, 1, 100),
         (500, 513, -1, 90), (780, 446, -1, 100), (920, 424, -1, 90)]           # відроги між потоками: (x, z на осі, бік, довжина)
for ax_, az_, sd_, L_ in SPURS:
    ex_, ez_ = ax_ + 0.24 * sd_ * L_, az_ + 0.97 * sd_ * L_
    _d, _, _t = path_info(wx, wz, [(ax_, az_), (ex_, ez_)])
    _s0 = float(_rs[int(az_), int(ax_)]); _hs = 0.72 * float(cos_interp(np.array(_s0), RS_S, RS_H))
    _w = 30 * (1 - 0.5 * _t) + 6; _vv = np.clip(1 - _d / _w, 0, 1)
    RIDGEH = np.maximum(RIDGEH, _hs * (1 - _t) ** 1.4 * _vv * _vv * (3 - 2 * _vv))
RIDGEH *= 1 - 0.85 * np.exp(-((wx - 305) ** 2 + (wz - 565) ** 2) / 34 ** 2)       # перевал дороги узбережжя
_ridge_v = np.clip(RIDGEH / 70, 0, 1)
_terr = np.round(RIDGEH / 6) * 6                                                    # тераси на схилах, наполовину
_slope_band = smooth(0.12, 0.3, _prof) * smooth(0.95, 0.75, _prof)
RIDGEH = RIDGEH * (1 - 0.5 * _slope_band) + _terr * 0.5 * _slope_band + fbm(wx, wz, 13, 3) * _ridge_v
for tx_, tz_ in ((452, 497), (655, 446), (870, 403)):                                 # скельні зуби на північному краю столів (Seceda)
    _r = np.hypot(wx - tx_, wz - tz_) + 3 * fbm(wx, wz, 77, 1, f0=0.08)
    RIDGEH = np.maximum(RIDGEH, RIDGEH + 22 * np.clip(1 - _r / 13, 0, 1) ** 0.7)
RIDGEH *= smooth(1.0, 0.85, tL)
h += RIDGEH
tM = blob_t(sx, sz, *MOUNTAIN)                                       # зона вулкана (червоний край, потоки лави)
VC0, VC1 = S(*VOLC)
_rv = np.hypot(wx - VC0, wz - VC1); _av = np.arctan2(wz - VC1, wx - VC0)
_RC = 40 * K * (1 + 0.1 * np.sin(4 * _av + 2)); _RB = 215 + 12 * fbm(wx, wz, 21, 1, f0=0.006)   # без «пелюсток»
_ux = ((wx - VC0) * 0.99 - (wz - VC1) * 0.12) / np.maximum(_rv, 1)       # 1 — на схід, −1 — назустріч хребту
_dout = np.maximum(_rv - _RC, 0) * (1 + 0.45 * np.sqrt(np.clip(1 - _ux * _ux, 0, 1))) / (1 + 0.9 * np.clip(-_ux, 0, 1))   # уздовж хребта довший, упоперек вужчий
VOLC_TOP = 214
_cone = SEA - 14 + (VOLC_TOP - SEA + 14) * np.clip(1 - _dout / (_RB - _RC), 0, 1) ** 1.7   # підніжжя йде під воду + fbm(wx, wz, 2, 4) * np.clip(1 - _rv / _RB, 0, 1)
tM = (_RC + _dout) / (120 * K)                                        # v8: зона вулкана за формою конуса (витягнута вздовж хребта)
VOLC_M = (_cone > h) & (_cone > 118)                                                    # де вже конус, а не хребет
h = np.where(_dout < _RB - _RC, np.maximum(h, _cone), h)              # хребет вливається в плече конуса; поза основою конус не діє (інакше зникає море)
tC = blob_t(sx, sz, *CRATER)
h = np.where((tC >= 1) & (tC < 1.4), np.maximum(h, VOLC_TOP - 2 * (tC - 1) / 0.4), h)   # кільце-плато краю кратера
rim = VOLC_TOP
h = np.where(tC < 1, rim - 32, h)                                     # рівне дно кальдери
tCl = blob_t(sx, sz, *CLIFFS)
h += 34 * smooth(1.0, 0.6, tCl) + 6 * np.round(fbm(wx, wz, 3, 2)) * smooth(1, .5, tCl)
tR = blob_t(sx, sz, *RAVINES)
h += 16 * smooth(1.0, 0.6, tR)

# v5: різкий обрив ярів до моря замість пологого пляжу (правка 14, помаранчева дуга): скеля 25–35 блоків, унизу смуга каміння
CLIFF_PATH = cr_path([(1022, 1258), (1056, 1320), (1140, 1328), (1215, 1302), (1250, 1205), (1258, 1098)])
CB = (slice(960, 1420), slice(930, 1400))   # [z, x]
_cp = world_path(CLIFF_PATH); _mid = len(_cp) // 2
_sgn = np.sign((_cp[_mid + 1][0] - _cp[_mid][0]) * (1200 - _cp[_mid][1]) - (_cp[_mid + 1][1] - _cp[_mid][1]) * (1110 - _cp[_mid][0]))
dCl, sideCl, sCl = path_info(wx[:, CB[1]], wz[CB[0], :], _cp)
taperCl = smooth(0, 0.08, sCl) * smooth(1, 0.92, sCl)
inCl = sideCl * _sgn > 0
hb = h[CB]
topCl = SEA + 29 + 5 * fbm(wx[:, CB[1]], wz[CB[0], :], 41, 1, f0=0.02) + 0.03 * dCl
wIn = smooth(95, 30, dCl) * taperCl
hb = np.where(inCl, np.maximum(hb, hb * (1 - wIn) + topCl * wIn), hb)
wOut = smooth(40, 8, dCl) * taperCl * smooth(0.86, 0.93, tL[CB])   # море лише там, де й був берег
tgtOut = np.where(dCl < 3.5, np.minimum(hb, SEA + 1), np.minimum(hb, SEA - 1 - (dCl - 3.5) * 0.8))
hb = np.where(~inCl & (dCl < 40), hb * (1 - wOut) + tgtOut * wOut, hb)
h[CB] = hb
CLIFF_IN = np.zeros((SIZE, SIZE), bool); CLIFF_IN[CB] = inCl & (dCl < 75) & (wIn > 0.35)
CLIFF_STRIP = np.zeros((SIZE, SIZE), bool); CLIFF_STRIP[CB] = ~inCl & (dCl < 3.5) & (taperCl > 0.5) & (tL[CB] > 0.9)
groove = np.abs(np.sin((wx + wz) * 0.037 + 2 * np.sin(wx * 0.013)))
h = np.where((groove < 0.32) & (tR < 0.9), np.maximum(h - 24, SEA + 3), h)   # дно ярів сухе
for x0, y0, rad, _, kind_ in TOWNS:
    X0, Z0 = S(x0, y0)
    dist_ = np.hypot(wx - X0, wz - Z0); m = dist_ < rad
    ref = np.median(h[m & (tL < 0.97)]) if np.any(m & (tL < 0.97)) else SEA + 2
    w_ = smooth(rad + 18, rad - 2, dist_) * (tL < 0.99)
    if kind_ == "port":   # v6: порт терасами вниз до води (план 16, розд. 17), три пандуси з набережної нагору
        tgt = SEA + 2 + 4 * np.clip((wz - (Z0 - 25)) // 17, 0, 4)
        for rx_ in (X0 - 45, X0, X0 + 45):
            tgt = np.where(np.abs(wx - rx_) < 3.5, SEA + 2 + np.clip((wz - (Z0 - 25)) * 0.235, 0, 16), tgt)
        h = h * (1 - w_) + tgt * w_
    elif kind_ == "port_w":   # v7: західний порт, вода на заході
        tgt = SEA + 2 + 4 * np.clip((wx - (X0 - 25)) // 17, 0, 4)
        for rz_ in (Z0 - 45, Z0, Z0 + 45):
            tgt = np.where(np.abs(wz - rz_) < 3.5, SEA + 2 + np.clip((wx - (X0 - 25)) * 0.235, 0, 16), tgt)
        h = h * (1 - w_) + tgt * w_
    else:
        h = h * (1 - w_) + max(ref, SEA + 1) * w_
# v5: стартове місто терасами, замок на найвищому південно-західному краю, пандус уздовж головної вулиці (нотатки 17, розд. 10)
TX5, TZ5 = S(300, 700); CAX, CAZ = TX5 - 42, TZ5 + 42; START_R = 86
_dC = np.hypot(wx - CAX, wz - CAZ); _dT = np.hypot(wx - TX5, wz - TZ5)
_seg, _, _ = path_info(wx, wz, [(TX5, TZ5), (CAX, CAZ)])
_tier = np.where(_dC < 22, 11, np.where(_dC < 44, 6, 0)).astype(float)
_tier = np.where(_seg < 4, np.interp(_dC, [16, 28, 36, 50], [11, 6, 6, 0]), _tier)
h = h + np.where(_dT < START_R - 4, _tier, 0)
dRiver = dist_to_paths(wx, wz, RIVERS)
dSpring, _, spring_s = path_info(wx, wz, RIVER_SURF)
dRoad = dist_to_paths(wx, wz, ROADS)
dStream = dist_to_paths(wx, wz, STREAMS)
stream = (dStream < 2.6) & (tL < 1.02) & (dRiver >= 5.5)
dRiver = np.minimum(dRiver, dStream + 3)
river = ((dRiver < 5.5) & (tL < 1.02)) | stream
SPRING_STREAM = dSpring < 3.0
river |= SPRING_STREAM
dRiver = np.minimum(dRiver, dSpring + 2.5)
TOP0 = np.round(h).astype(np.int32) - 1                    # рельєф до річок
TOP = np.where(river, TOP0 - 3 - np.round((5.5 - dRiver) / 2).astype(np.int32), TOP0)
WL = np.full((SIZE, SIZE), -999, np.int32)                  # рівень води
WL[TOP < SEA - 1] = SEA - 1
WL[river] = np.maximum(TOP0[river] - 1, SEA - 1)
# v10: логіка річок (правка користувача): рівень води спадає від витоку до гирла й однаковий упоперек русла
_rbest = np.full((SIZE, SIZE), 1e9); RLEV = np.full((SIZE, SIZE), -999, np.int32); RIDX = np.full((SIZE, SIZE), -1, np.int8)
for _ri, d_ in enumerate(RIVERS + STREAMS):
    pts_ = np.asarray(world_path(d_), float)
    di_, _, si_ = path_info(wx, wz, pts_)
    seg_ = np.hypot(*np.diff(pts_, axis=0).T); cum_ = np.concatenate([[0], np.cumsum(seg_)]) / max(seg_.sum(), 1)
    tt_ = np.linspace(0, 1, 600); xs_ = np.interp(tt_, cum_, pts_[:, 0]); zs_ = np.interp(tt_, cum_, pts_[:, 1])
    hh_ = TOP0[np.clip(zs_.astype(int), 0, SIZE - 1), np.clip(xs_.astype(int), 0, SIZE - 1)] - 1
    prof_ = np.minimum.accumulate(hh_) if hh_[:20].mean() >= hh_[-20:].mean() else np.minimum.accumulate(hh_[::-1])[::-1]
    lev_ = np.rint(np.interp(si_, tt_, np.maximum(prof_, SEA - 1))).astype(np.int32)
    m_ = (di_ < _rbest) & (di_ < 9)
    _rbest = np.where(m_, di_, _rbest); RLEV = np.where(m_, lev_, RLEV); RIDX = np.where(m_, _ri, RIDX)
_rm = river & ~SPRING_STREAM & (RLEV > -999)
WL[_rm] = RLEV[_rm]
TOP = np.where(_rm, np.minimum(TOP, RLEV - 2 - np.round(np.clip(5.5 - dRiver, 0, 5.5) / 2).astype(np.int32)), TOP)
LL = np.where(tC < 1, rim - 31, -999).astype(np.int32)      # рівень лави в кратері
PONDS = [(440, 560, 12), (620, 780, 10), (780, 380, 11), (880, 520, 10), (360, 380, 10), (300, 820, 9),
         (560, 640, 8), (700, 430, 9), (420, 760, 9), (260, 480, 8), (840, 660, 9), (600, 300, 8)]
POND = np.zeros((SIZE, SIZE), bool)
for px_, pz_, pr in PONDS:
    X0, Z0 = S(px_, pz_); R = pr * K
    ang = np.arctan2(wz - Z0, wx - X0); d = np.hypot(wx - X0, wz - Z0) / R + 0.13 * np.sin(ang * 2 + px_) + 0.08 * np.sin(ang * 5 + pz_) + 0.04 * np.sin(ang * 11)
    m = d < 1
    if (dRoad[m] < 5).any() or (tL[m] > 0.9).any() or river[m].any() or (tC[m] < 1.3).any(): continue
    level = int(np.percentile(TOP0[(d >= 1) & (d < 1.35)], 10))   # v10: рівень за нижньою частиною берега
    depth = np.round(1 + 3 * (1 - np.clip(d, 0, 1) ** 2)).astype(np.int32)
    TOP = np.where(m, np.minimum(TOP, level - 1 - depth), TOP)
    WL = np.where(m, level - 1, WL); POND |= m
# v6: вулкан — червоний край і вхід у підземелля (план 16, розд. 17; кадри 58–72 гри, 10–12 відео)
VCX, VCZ = S(*VOLC)
CR = lambda a: 40 * K * (1 + 0.1 * math.sin(4 * a + 2))          # край кратера (як blob CRATER)
ISL_Y = int(rim) - 30                                                 # верх острова й мосту, на 2 вище лави
ISLAND = np.hypot(wx - VCX, wz - VCZ) < 9.5
TOP[ISLAND] = ISL_Y; LL[ISLAND] = -999
LAVASTREAM = np.zeros((SIZE, SIZE), bool)
_gz0, _gx0 = np.gradient(TOP0.astype(float))
for k in range(10):   # потоки лави з кратера схилами вниз
    a = k / 10 * 6.283 + 0.3 * math.sin(k * 7.1)
    if abs(((a - math.pi + math.pi) % 6.283) - math.pi) < 0.45: continue      # не на каньйон
    x, z = VCX + (CR(a) + 3) * math.cos(a), VCZ + (CR(a) + 3) * math.sin(a)
    for step in range(260):
        ix, iz = int(x), int(z)
        if not (0 < ix < SIZE - 1 and 0 < iz < SIZE - 1) or tM[iz, ix] > 0.86 or dRoad[iz, ix] < 6: break
        for px, pz in ((ix, iz), (ix + 1, iz), (ix, iz + 1)):
            if not LAVASTREAM[pz, px] and tC[pz, px] >= 1:
                LL[pz, px] = TOP[pz, px]; TOP[pz, px] -= 1; LAVASTREAM[pz, px] = True
        gx_, gz_ = _gx0[iz, ix], _gz0[iz, ix]; gl = math.hypot(gx_, gz_) or 1
        ox_, oz_ = (x - VCX), (z - VCZ); ol = math.hypot(ox_, oz_) or 1
        x += -gx_ / gl * 0.6 + ox_ / ol * 0.4 + 0.35 * math.sin(step * 0.31 + k)
        z += -gz_ / gl * 0.6 + oz_ / ol * 0.4 + 0.35 * math.cos(step * 0.27 + k)
# каньйон від дороги на заході до кратера: дно плавно піднімається, стіни круті (шипи над ним — у точках інтересу)
CAN_A = (float(VCX - 140), float(VCZ)); CAN_B = (VCX - CR(math.pi) - 1, float(VCZ))   # v7: ущелина вздовж гребеня хребта до кратера
_cd, _, _ct = path_info(wx, wz, [CAN_A, CAN_B])
_fa = int(TOP[int(CAN_A[1]), int(CAN_A[0])])
_floor = np.round(_fa + (ISL_Y - _fa) * _ct).astype(np.int32)
CANYON = (_cd < 4) & (_ct > 0) & (_ct < 1)
TOP = np.where(CANYON, np.minimum(TOP, _floor), TOP)
_wall = (_cd >= 4) & (_cd < 9) & (_ct > 0.02) & (_ct < 0.99)
TOP = np.where(_wall, np.minimum(TOP, _floor + np.round((_cd - 4) * 3.5).astype(np.int32)), TOP)
CANYON_NEAR = (_cd < 13) & (_ct > -0.03) & (_ct < 1.03)            # v10: смуга, де не можна ставити шипи
LL = np.where((CANYON | _wall) & LAVASTREAM, -999, LL); LAVASTREAM &= ~(CANYON | _wall)   # v10: лава потоків не заливає прохід
# v7: озеро-джерело в центрі острова (на місці старого вулкана): у нього стікають потоки з хребта, з нього течуть річки
LAKE_C = (835, 610)
_le = ((wx - LAKE_C[0]) / 64) ** 2 + ((wz - LAKE_C[1]) / 41) ** 2 + 0.1 * fbm(wx, wz, 111, 1, f0=0.03)
LAKE = _le < 1
_ll = int(np.percentile(TOP0[(_le >= 1) & (_le < 1.3)], 10))   # v10: рівень за берегом
TOP = np.where(LAKE, np.minimum(TOP, _ll - 2 - np.round(4 * (1 - np.clip(_le, 0, 1))).astype(np.int32)), TOP)
WL = np.where(LAKE, _ll - 1, WL); POND |= LAKE
_lk = _rm & np.isin(RIDX, [0, 1]) & ~LAKE                          # річки з озера не вищі за озеро
WL = np.where(_lk, np.minimum(WL, _ll - 2), WL); TOP = np.where(_lk, np.minimum(TOP, WL - 2), TOP)
LAKE_ISL = np.hypot(wx - (LAKE_C[0] + 8), wz - (LAKE_C[1] - 2)) < 9
TOP = np.where(LAKE_ISL, _ll + 1, TOP); WL = np.where(LAKE_ISL, -999, WL); POND &= ~LAKE_ISL
# v9: струмок зі столу B знижується до Брами Джерел; лише поверхня, без нових залів.
_spring_pts = np.asarray(RIVER_SURF)
_spring_lengths = np.r_[0, np.cumsum(np.hypot(*np.diff(_spring_pts, axis=0).T))]
_spring_levels = np.minimum.accumulate([TOP0[z, x] - 1 for x, z in RIVER_SURF])
_spring_water = np.rint(np.interp(spring_s, _spring_lengths / _spring_lengths[-1], _spring_levels)).astype(np.int32)
TOP[SPRING_STREAM] = _spring_water[SPRING_STREAM] - 2
WL[SPRING_STREAM] = _spring_water[SPRING_STREAM]
road = (dRoad < 2.2) & ~river & (tL < 0.99)
roadside = (dRoad < 3.2) & ~road & ~river & (tL < 0.99)
# v8: тунель прямий і рівний (правка користувача): підлога TUN_Y по всій довжині; до порталів — виїмки-ущелини,
# посередині — світловий колодязь з озерцем (Son Doong), через який іде міст
TUN_A, TUN_B = (690.0, 640.0), (690.0, 330.0)   # строго північ — південь
_tl = math.hypot(TUN_B[0] - TUN_A[0], TUN_B[1] - TUN_A[1])
_tp = [(TUN_A[0] + (TUN_B[0] - TUN_A[0]) * t, TUN_A[1] + (TUN_B[1] - TUN_A[1]) * t) for t in np.linspace(0, 1, 400)]
_tg = np.array([TOP0[int(z_), int(x_)] for x_, z_ in _tp])
TUN_Y = int(_tg[-1]) + 2                                                # рівень підлоги біля північного порталу
_in = np.where(_tg - TUN_Y >= 16)[0]                                    # де над склепінням досить скелі — закрито
TUN_N = tuple(int(v) for v in _tp[_in[-1]])
# v10 (правка користувача): південний вхід не в ямі, а на схилі (≈ 690, 598) на рівні плато; підлога плавно
# спускається до північного порталу (≈ 1 блок на 14), сходинки в тунелі ставить road_tunnel
TUN_S = (690, 598); TUN_YS = int(TOP[620, 690]) - 2
_TZ, _TYL = [TUN_N[1], TUN_S[1], TUN_S[1] + 40], [TUN_Y, TUN_YS, TUN_YS + 10]
def tun_floor(z): return np.rint(np.interp(z, _TZ, _TYL)).astype(np.int32)
_td, _, _tt = path_info(wx, wz, [TUN_A, TUN_B])
_ts_ = (TUN_A[1] - TUN_S[1]) / (TUN_A[1] - TUN_B[1]); _tn_ = (TUN_A[1] - TUN_N[1]) / (TUN_A[1] - TUN_B[1])
_cut = ((_tt <= _ts_ + 0.01) | (_tt >= _tn_ - 0.01)) & (_tt > 0) & (_tt < 1)   # виїмки перед порталами (із запасом у 3 блоки)
_fl = tun_floor(wz)
TOP = np.where(_cut & (_td < 3), np.minimum(TOP, _fl - 1), TOP)
_cw = _cut & (_td >= 3) & (_td < 14)
TOP = np.where(_cw, np.minimum(TOP, _fl - 1 + np.round((_td - 3) * 3.2).astype(np.int32)), TOP)   # круті стіни ущелини
_over = (_td < 6) & (_tt > _ts_) & (_tt < _tn_)                         # дорога не йде гребенем над тунелем
road &= ~_over; roadside &= ~_over
TUN_WELL = (690.0, (TUN_S[1] + TUN_N[1]) / 2)                           # світловий колодязь посередині
WELL_R = 13
WELL = np.hypot(wx - TUN_WELL[0], wz - TUN_WELL[1]) < WELL_R             # рівне коло: облицювання й сходи по краю
_wf = int(tun_floor(TUN_WELL[1]))
TOP = np.where(WELL, np.minimum(TOP, _wf - 12), TOP); WL = np.where(WELL, _wf - 6, WL)
log(f"тунель: {TUN_S} -> {TUN_N}, підлога Y {TUN_YS} -> {TUN_Y}, колодязь {TUN_WELL}, міст Y {_wf}")
# v10: береги — суша поруч із водою не нижча за рівень води (інакше вода «спирається на повітря»)
for _ in range(2):
    _wet = (WL > TOP) & ~WELL
    _wn = np.full((SIZE, SIZE), -999, np.int32); _wv = np.where(_wet, WL, -999)
    for dz_, dx_ in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):
        _wn = np.maximum(_wn, np.roll(np.roll(_wv, dz_, 0), dx_, 1))
    _low = ~_wet & (TOP < _wn) & ~WELL
    TOP = np.where(_low, _wn, TOP)
log(f"береги: підняли {int(_low.sum())} клітинок у другому проході")
log("рельєф готовий")
if os.environ.get("OSTRIV_TERRAIN_ONLY"):   # схема: зберегти лише рельєф і вийти (tools/schema_v8.py)
    np.savez_compressed(os.environ["OSTRIV_TERRAIN_ONLY"], TOP=TOP, WL=WL, LL=LL, RIDGEH=RIDGEH, tL=tL, TUN=np.array(TUN_S + TUN_N + (TUN_Y,)), WELL=np.array(TUN_WELL), VOLC_M=VOLC_M)
    log("рельєф збережено"); sys.exit(0)

# ---------- категорії поверхні ----------
GRASS, MEADOW, FORESTC, SAND, SEABED, STONE, BASALT, LAVA, RIVERBED, PATH, TERRA, FORTC, FIELD, TOWN, HIGH, BANK, SNOW = range(17)
cat = np.full((SIZE, SIZE), GRASS, np.uint8)
cat[blob_t(sx, sz, *MEADOW_ZONE) < 1] = MEADOW
cat[tH < 0.8] = HIGH
# v5: поле на весь жовтий овал (правка 14): ділянки 15×22, між ними стежки й живоплоти, кожна ділянка — своя тераса
dTown0 = np.hypot(wx - TX5, wz - TZ5)
FCX, FCZ, FA, FB, FTH = 485, 826, 212, 112, math.radians(-10.6)
_u = ((wx - FCX) * math.cos(FTH) + (wz - FCZ) * math.sin(FTH)) / FA; _v = (-(wx - FCX) * math.sin(FTH) + (wz - FCZ) * math.cos(FTH)) / FB
field_area = (np.hypot(wx - 250, wz - 870) > 100) & (_u ** 2 + _v ** 2 + 0.06 * fbm(wx, wz, 51, 1, f0=0.02) < 1) & (tL < 0.9) & (dRiver > 9) & (dRoad > 4) & ~POND & (dTown0 > START_R + 18)
pxm, pzm = (wx // 1).astype(int) % 15 + 0 * wz, (wz // 1).astype(int) % 22 + 0 * wx
field = field_area & (pxm != 0) & (pzm != 0)
HEDGE = field_area & (pzm == 0) & (pxm != 0)
FPATH = field_area & (pxm == 0)
FIELD_CROP = np.zeros((SIZE, SIZE), np.uint8)   # 1 пшениця, 2 морква, 3 картопля, 4 буряк, 5 гарбузи й дині, 6 соняшники, 7 перелог із сіном
_pid = (wx // 15).astype(int) * 1000 + (wz // 22).astype(int)
fz_, fx_ = np.nonzero(field)
for pid in np.unique(_pid[fz_, fx_]):
    sel = _pid[fz_, fx_] == pid; zz_, xx_ = fz_[sel], fx_[sel]
    TOP[zz_, xx_] = int(np.median(TOP[zz_, xx_]))          # тераса ділянки
    FIELD_CROP[zz_, xx_] = (1, 1, 1, 2, 2, 3, 3, 4, 5, 6, 7)[(pid * 7919) % 11]
cat[field & (FIELD_CROP <= 4)] = FIELD
FOREST_KIND = np.zeros((SIZE, SIZE), np.uint8)   # 1 мішаний, 2 березовий, 3 старий темний, 4 хвойний
FOREST_KIND[blob_t(sx, sz, *FOREST) < 1] = 1
for blob_, kind_ in (((760, 305, 85, [(.15, 3, 1), (.1, 5, 2)], 11), 2), ((430, 830, 95, [(.12, 3, 0), (.1, 6, 1)], 12), 3),
                     ((215, 270, 100, [(.12, 3, 2), (.08, 5, 1)], 13), 4), ((445, 405, 55, [(.15, 3, 1)], 14), 1),
                     ((885, 815, 60, [(.15, 3, 0)], 15), 2), ((835, 250, 70, [(.12, 4, 1)], 16), 4)):
    FOREST_KIND[(blob_t(sx, sz, *blob_) < 1) & (FOREST_KIND == 0)] = kind_
# v5: ліси до намальованих дуг (правка 14); межі нерівні, щоб не було прямих ліній
JX, JZ = 14 * fbm(wx, wz, 61, 1, f0=0.025), 14 * fbm(wx, wz, 62, 1, f0=0.025)
def poly_mask(pts, jitter=True):
    P = np.asarray(pts, float); m = np.zeros((SIZE, SIZE), bool); X, Z = (wx + JX, wz + JZ) if jitter else (wx, wz)
    for (x0, z0), (x1, z1) in zip(P, np.roll(P, -1, 0)):
        if z0 == z1: continue
        m ^= ((z0 > Z) != (z1 > Z)) & (X < x0 + (Z - z0) * (x1 - x0) / (z1 - z0))
    return m
fnoise = fbm(wx, wz, 63, 1, f0=0.012)
for pts_, kinds_ in (([(451, 298), (530, 260), (578, 250), (640, 300), (672, 365), (652, 430), (614, 470), (540, 480), (468, 440), (432, 360)], "nw"),
                     ([(921, 269), (960, 260), (998, 269), (1040, 300), (1066, 346), (1046, 410), (1008, 451), (985, 418), (948, 350), (912, 300)], "ne"),
                     ([(1098, 790), (1162, 845), (1150, 935), (1123, 1018), (1075, 1042), (1058, 930), (1078, 840)], 1),
                     ([(595, 1090), (530, 1080), (461, 1094), (430, 1171), (458, 1228), (503, 1268), (560, 1248), (600, 1180), (616, 1120)], 2)):
    m_ = poly_mask(pts_) & (FOREST_KIND == 0) & (dTown0 > START_R + 16)
    if kinds_ == "nw": FOREST_KIND[m_] = np.where(TOP0[m_] > 112, 4, 1)
    elif kinds_ == "ne": FOREST_KIND[m_] = np.where(fnoise[m_] > 0, 2, 4)
    else: FOREST_KIND[m_] = kinds_
# v9: весь центральний полігон ельфійський; березовий край 16 блоків, ядро-собор R118.
ELF_MASK = poly_mask(ELF_FOREST, jitter=False)
_elf_edge_d, _, _ = path_info(wx, wz, ELF_FOREST + [ELF_FOREST[0]])
ELF_EDGE = ELF_MASK & (_elf_edge_d < 16 + 3 * fnoise)
ELF_CORE = ELF_MASK & (np.hypot(wx - ELF_TREE[0], wz - ELF_TREE[1]) < ELF_CORE_R)
FOREST_KIND[ELF_MASK] = 1
FOREST_KIND[ELF_EDGE] = 2
FOREST_KIND[field_area] = 0
cat[FOREST_KIND > 0] = FORESTC
tF = blob_t(sx, sz, *FORT) + 0.08 * fbm(wx, wz, 102, 1, f0=0.025)
cat[tF < 1.12] = FORTC   # v6: обсидіановий край навколо собору боса, різка межа
cat[tR < 1] = TERRA
gz, gx = np.gradient(TOP0.astype(float))
slope = np.hypot(gx, gz)
cat[(slope > 1.6) & (tL < 0.97) & (cat != TERRA)] = STONE
cat[tM < 0.75] = STONE
cat[tM < 0.45] = BASALT
cat[(TOP0 > 140 + 6 * fbm(wx, wz, 72, 1, f0=0.03)) & (tM > 0.75)] = SNOW   # v8: більше снігу (правка користувача)
dRidgeW = dist_to_paths(wx, wz, [RIDGES[0][0]])
EAST = np.clip((wx - 760) / 475, 0, 1)
SNOWLINE_W = 136 + 8 * fbm(wx, wz, 71, 1, f0=0.02) + 12 * EAST ** 1.5   # ближче до вулкана сніг лише на верхівках
cat[(dRidgeW < 100 * K) & (TOP0 > SNOWLINE_W) & (tM > 0.75)] = SNOW   # v5: довший і ширший сніжний верх західного хребта
cat[tL >= 0.945] = SAND
cat[(tM < 0.75) & (TOP >= SEA)] = STONE; cat[(tM < 0.45) & (TOP >= SEA)] = BASALT   # v7: вулкан насипав мис у морі
VCOLLAR = (tM > 0.47) & (tM < 0.6) & (fbm(wx, wz, 112, 1, f0=0.04) > -0.25) & (TOP >= SEA)
cat[VCOLLAR] = SNOW   # сніговий комір на схилах вулкана (як Ключевська, Вільярріка)
cat[CLIFF_IN & (cat != SEABED)] = TERRA
cat[(cat == SAND) & (tR < 1.15) & (TOP > SEA + 3)] = TERRA   # без піщаних латок на плато ярів
cat[CLIFF_STRIP] = STONE
cat[TOP < SEA - 1] = SEABED
cat[(dRiver < 7.5) & ~river & (tL < 0.99)] = BANK
cat[road & (RIDGEH < 6)] = PATH   # v7: над тунелем дороги на поверхні немає
cat[river] = RIVERBED
cat[POND] = RIVERBED
cat[tC < 1] = LAVA
cat[LAVASTREAM] = LAVA
cat[LAKE_ISL] = GRASS
cat[ISLAND] = BASALT
cat[CANYON] = PATH
# зони: найближче «насіння» з легким шумом на межах; растр 4×4 блоки, як біоми гри
ZCELL = 4; ZN = SIZE // ZCELL
zc = (np.arange(ZN) * ZCELL + 2.0)
ZX, ZZ = np.meshgrid(zc, zc)
best_d = np.full((ZN, ZN), 1e9); ZONE2D = np.zeros((ZN, ZN), np.uint8)
for zi, (_, _, _, _, seeds) in enumerate(ZONES):
    for sx0, sz0 in seeds:
        X0, Z0 = S(sx0, sz0); dd = np.hypot(ZX - X0, ZZ - Z0) + 18 * np.sin(ZX * 0.031 + zi) * np.cos(ZZ * 0.027 - zi)
        upd = dd < best_d; best_d[upd] = dd[upd]; ZONE2D[upd] = zi + 1
ZONE2D[tL[2::ZCELL, 2::ZCELL] > 1.0] = 0   # море
def zone_at(x, z):
    return int(ZONE2D[min(int(z) // ZCELL, ZN - 1), min(int(x) // ZCELL, ZN - 1)])
log("категорії готові")

# ---------- блоки ----------
STATES, SID = ["minecraft:air"], {"minecraft:air": 0}
def sid(state):
    if not state.startswith("minecraft:"): state = "minecraft:" + state
    if state not in SID:
        SID[state] = len(STATES); STATES.append(state)
    return SID[state]
AIR = 0
def mix(*names):  # кілька варіантів для шуму
    return [sid(n) for n in names]

# верхній блок, шар під ним (4 блоки)
TOPMAT = {SNOW: mix("snow_block", "snow_block", "snow_block", "stone"), GRASS: mix("grass_block"), MEADOW: mix("grass_block"), FORESTC: mix("grass_block", "grass_block", "grass_block", "podzol", "coarse_dirt"),
          SAND: mix("sand"), SEABED: mix("sand", "sand", "gravel", "clay"), STONE: mix("stone", "stone", "andesite", "cobblestone", "tuff"),
          BASALT: mix("blackstone", "basalt", "smooth_basalt", "magma_block"), LAVA: mix("magma_block"), RIVERBED: mix("gravel", "sand", "clay"),
          PATH: mix("dirt_path", "dirt_path", "dirt_path", "coarse_dirt"), TERRA: mix("terracotta"), FORTC: mix("calcite", "calcite", "white_concrete_powder", "diorite", "snow_block", "smooth_quartz", "calcite"),
          FIELD: mix("farmland[moisture=7]"), TOWN: mix("stone_bricks", "cobblestone", "andesite", "polished_andesite", "stone_bricks"),
          HIGH: mix("grass_block", "grass_block", "grass_block", "coarse_dirt", "moss_block"), BANK: mix("sand", "gravel", "grass_block", "grass_block")}
SUBMAT = {SNOW: sid("stone"), SAND: sid("sandstone"), SEABED: sid("sand"), STONE: sid("stone"), BASALT: sid("blackstone"), LAVA: sid("blackstone"),
          RIVERBED: sid("gravel"), TERRA: sid("terracotta"), TOWN: sid("cobblestone"), FORTC: sid("calcite")}
DIRT, STONE_ID, DEEP, BEDROCK = sid("dirt"), sid("stone"), sid("deepslate"), sid("bedrock")
WATER, LAVA_ID = sid("water"), sid("lava")
BANDS = mix("terracotta", "orange_terracotta", "orange_terracotta", "yellow_terracotta", "terracotta", "brown_terracotta",
            "red_terracotta", "terracotta", "white_terracotta", "light_gray_terracotta", "orange_terracotta", "red_sand")

noise_col = nrng.integers(0, 1 << 30, (SIZE, SIZE))
TOPB = np.zeros((SIZE, SIZE), np.uint16); SUBB = np.full((SIZE, SIZE), DIRT, np.uint16)
for c, opts in TOPMAT.items():
    m = cat == c
    TOPB[m] = np.array(opts, np.uint16)[noise_col[m] % len(opts)]
    SUBB[m] = SUBMAT.get(c, DIRT)
# грядки: два рядки води в кожній ділянці (не до краю, щоб вода не витікала з тераси), решта рілля
fw = (cat == FIELD) & np.isin(pzm, [6, 16]) & (pxm >= 2) & (pxm <= 13)
TOPB[fw] = WATER
# межі ділянок поля: стежки й земля під живоплотом
TOPB[FPATH & (cat != RIVERBED)] = np.array(mix("dirt_path", "coarse_dirt", "dirt_path", "gravel"), np.uint16)[noise_col[FPATH & (cat != RIVERBED)] % 4]
_fall = field & (FIELD_CROP >= 5)
TOPB[_fall] = np.array(mix("grass_block", "grass_block", "coarse_dirt", "rooted_dirt"), np.uint16)[noise_col[_fall] % 4]
# v5: майже ніде чистої трави — плями грубої землі, гравію й моху (нотатки 19, розд. 5)
_pn = fbm(wx, wz, 81, 1, f0=0.05); _pn2 = fbm(wx, wz, 82, 1, f0=0.07)
_gr = np.isin(cat, [GRASS, MEADOW, HIGH, BANK]) & (TOPB == sid("grass_block")) & ~field_area
_m1 = _gr & (_pn > 0.42) & (noise_col % 3 != 0); TOPB[_m1] = np.array(mix("coarse_dirt", "coarse_dirt", "rooted_dirt", "dirt"), np.uint16)[noise_col[_m1] % 4]
_m2 = _gr & (_pn2 < -0.62) & (noise_col % 3 == 0); TOPB[_m2] = np.array(mix("gravel", "gravel", "cobblestone", "andesite"), np.uint16)[noise_col[_m2] % 4]
_fo = (cat == FORESTC) & (TOPB == sid("grass_block"))
_m3 = _fo & (_pn > 0.3); TOPB[_m3] = np.array(mix("moss_block", "podzol", "moss_block", "coarse_dirt"), np.uint16)[noise_col[_m3] % 4]
# смуга каміння й гравію під обривом ярів
_ash = (cat == SNOW) & (EAST > 0.45) & (noise_col % 3 == 0)   # сніг із попелом ближче до вулкана
TOPB[_ash] = np.array(mix("gravel", "tuff", "light_gray_concrete_powder", "andesite"), np.uint16)[noise_col[_ash] % 4]
TOPB[CLIFF_STRIP] = np.array(mix("gravel", "stone", "cobblestone", "gravel", "andesite"), np.uint16)[noise_col[CLIFF_STRIP] % 5]
# «зіпсована» земля навколо фортеці: що ближче до арени, то темніше
CORRUPT = 17
# v6: червона зона зла переїхала на вулкан: різка межа, ближче до кратера густіше
tMv = tM + 0.07 * fbm(wx, wz, 101, 1, f0=0.02)   # v8: за формою конуса
corr = np.where(tMv < 1.32, 0.45 + 0.55 * smooth(1.32, 0.8, tMv), 0.0) * ~field_area * (tF > 1.5) * (RIDGEH < 25)   # смуга до собору
land_ = np.isin(cat, [GRASS, MEADOW, FORESTC, HIGH, BANK, FORTC])
rr = nrng.random((SIZE, SIZE))
c1 = land_ & (rr < corr * 0.9); c2 = land_ & (rr < corr * 0.45)
TOPB[c1] = np.array(mix("coarse_dirt", "podzol", "rooted_dirt", "coarse_dirt"), np.uint16)[noise_col[c1] % 4]
TOPB[c2] = np.array(mix("netherrack", "blackstone", "crimson_nylium", "magma_block", "netherrack"), np.uint16)[noise_col[c2] % 5]
_cp3 = land_ & (corr > 0) & (np.abs(fbm(wx, wz, 103, 1, f0=0.06)) > 0.5)   # плями червоного, помаранчевого, жовтого
TOPB[_cp3] = np.array(mix("red_terracotta", "orange_terracotta", "red_terracotta", "yellow_terracotta", "netherrack"), np.uint16)[noise_col[_cp3] % 5]
# обсидіановий край: біла земля з фіолетово-чорними жилками, на межі латки
_vein = (cat == FORTC) & (np.abs(fbm(wx, wz, 104, 1, f0=0.035)) < 0.07)
TOPB[_vein] = np.array(mix("crying_obsidian", "obsidian", "obsidian", "amethyst_block", "purple_concrete"), np.uint16)[noise_col[_vein] % 5]
_edge = np.isin(cat, [GRASS, MEADOW, FORESTC, HIGH]) & (tF >= 1.12) & (tF < 1.4) & (noise_col % 4 == 0)
TOPB[_edge] = np.array(mix("calcite", "obsidian", "calcite", "crying_obsidian"), np.uint16)[noise_col[_edge] % 4]
TOPB[CANYON] = np.array(mix("blackstone", "polished_blackstone_bricks", "basalt", "blackstone"), np.uint16)[noise_col[CANYON] % 4]
TOPB[ISLAND] = sid("polished_blackstone_bricks")
cat[land_ & (corr > 0.35) & (cat != FORTC)] = CORRUPT
# дороги: світла середина, темніші колії; біля хабів бруківка
rd_ = cat == PATH
TOPB[rd_ & (dRoad < 1.1)] = sid("dirt_path")
edge_ = rd_ & (dRoad >= 1.1)
TOPB[edge_] = np.array(mix("coarse_dirt", "gravel", "dirt_path", "coarse_dirt"), np.uint16)[noise_col[edge_] % 4]
for x0, y0, rad, _, _ in TOWNS:
    X0, Z0 = S(x0, y0); cob = rd_ & (np.hypot(wx - X0, wz - Z0) < rad * 1.15)
    TOPB[cob] = np.array(mix("cobblestone", "stone_bricks", "andesite", "mossy_cobblestone", "cobblestone"), np.uint16)[noise_col[cob] % 5]
log("матеріали готові")

def top_at(x, z):
    return int(TOP[int(np.clip(z, 0, SIZE-1)), int(np.clip(x, 0, SIZE-1))])
def cat_at(x, z):
    return int(cat[int(np.clip(z, 0, SIZE-1)), int(np.clip(x, 0, SIZE-1))])
def is_land(x, z):
    return 0 <= x < SIZE and 0 <= z < SIZE and TOP[z, x] >= SEA - 1 and cat[z, x] not in (RIVERBED, SEABED, LAVA)

# ---------- правки блоків (споруди, дерева, декор) ----------
EDITS = []  # (xs, ys, zs, ids); пізніші перекривають ранніші
BE_IDS = {"chest": "chest", "bell": "bell", "player_head": "skull", "skeleton_skull": "skull", "decorated_pot": "decorated_pot",
          "barrel": "barrel", "trapped_chest": "trapped_chest"}
def be_id(nm_):
    """Блоки, які гра малює чи наповнює лише з даними блока."""
    if nm_.endswith("_bed"): return "bed"
    if nm_.endswith("_hanging_sign") or nm_.endswith("_wall_hanging_sign"): return "hanging_sign"
    if nm_.endswith("_sign"): return "sign"
    if nm_.endswith("_banner"): return "banner"
    if nm_.endswith("_head") or nm_.endswith("_skull"): return "skull"
    return BE_IDS.get(nm_)
BLOCK_ENTITIES = []  # (x, y, z, id): блоки, які гра малює лише з даними блока
TRANSPOSE = False   # v7: будувати дзеркально (x <-> z), щоб той самий код порту дав порт із водою на заході
_TM = {"north": "west", "west": "north", "south": "east", "east": "south"}
@functools.lru_cache(maxsize=None)
def tstate(st):
    name, _, props = st.partition("[")
    if not props: return st
    out = []
    for kv in props.rstrip("]").split(","):
        k, v = kv.split("=")
        if k == "facing": v = _TM.get(v, v)
        elif k == "axis": v = {"x": "z", "z": "x"}.get(v, v)
        elif k == "shape": v = {"inner_left": "inner_right", "inner_right": "inner_left", "outer_left": "outer_right", "outer_right": "outer_left"}.get(v, v)
        elif k == "hinge": v = {"left": "right", "right": "left"}.get(v, v)
        out.append(f"{_TM.get(k, k)}={v}")
    return name + "[" + ",".join(out) + "]"
def put(xs, ys, zs, state):
    if TRANSPOSE:
        xs, zs = zs, xs
        state = tstate(state if isinstance(state, str) else STATES[state])
    xs, ys, zs = np.broadcast_arrays(np.asarray(xs, np.int32), np.asarray(ys, np.int32), np.asarray(zs, np.int32))
    i = sid(state) if isinstance(state, str) else state
    EDITS.append((xs.ravel(), ys.ravel(), zs.ravel(), np.full(xs.size, i, np.uint16)))
    nm_ = STATES[i].split("[")[0].replace("minecraft:", "")
    be = be_id(nm_)
    if be:
        for x, y, z in zip(xs.ravel(), ys.ravel(), zs.ravel()): BLOCK_ENTITIES.append((int(x), int(y), int(z), "minecraft:" + be))
def setb(x, y, z, state):
    put([x], [y], [z], state)
def fill(x0, y0, z0, x1, y1, z1, state):
    x0, x1 = sorted((int(x0), int(x1))); y0, y1 = sorted((int(y0), int(y1))); z0, z1 = sorted((int(z0), int(z1)))
    X, Y, Z = np.mgrid[x0:x1+1, y0:y1+1, z0:z1+1]
    put(X, Y, Z, state)
def hollow(x0, y0, z0, x1, y1, z1, state):  # стіни коробки
    fill(x0, y0, z0, x1, y1, z0, state); fill(x0, y0, z1, x1, y1, z1, state)
    fill(x0, y0, z0, x0, y1, z1, state); fill(x1, y0, z0, x1, y1, z1, state)
def sphere(cx, cy, cz, r, state, ry=None, chance=1.0):
    ry = ry or r; R = int(math.ceil(max(r, ry)))
    X, Y, Z = np.mgrid[-R:R+1, -R:R+1, -R:R+1]
    m = (X*X + Z*Z) / (r*r) + (Y*Y) / (ry*ry) <= 1
    if chance < 1: m &= nrng.random(m.shape) < chance
    put(X[m] + int(cx), Y[m] + int(cy), Z[m] + int(cz), state)

SIGNS = []   # (x, y, z, рядки)
RES_MASK = np.zeros((SIZE, SIZE), bool)  # тут не садимо дерева й декор (растр замість списку: швидко при тисячах резервів)
def reserve(x, z, r):
    x, z = int(x), int(z); R = int(math.ceil(r))
    a0, a1, b0, b1 = max(x - R, 0), min(x + R + 1, SIZE), max(z - R, 0), min(z + R + 1, SIZE)
    if a0 >= a1 or b0 >= b1: return
    X, Z = np.meshgrid(np.arange(a0, a1), np.arange(b0, b1))
    RES_MASK[b0:b1, a0:a1] |= (X - x) ** 2 + (Z - z) ** 2 < r * r
def reserved(x, z):
    x, z = int(x), int(z)
    return 0 <= x < SIZE and 0 <= z < SIZE and bool(RES_MASK[z, x])

ROAD_PTS = np.array([p for d in ROADS for p in world_path(d)])
def sign(x, z, *lines, y=None):
    """Табличка на стовпчику, повернута до найближчої дороги."""
    y = (top_at(x, z) + 1) if y is None else y
    d = ROAD_PTS - (x, z); j = int(np.argmin(np.hypot(d[:, 0], d[:, 1]))); vx, vz = d[j]
    if abs(vx) + abs(vz) < 1: vx, vz = 0, 1
    rot = int(round(math.degrees(math.atan2(-vx, vz)) / 22.5)) % 16
    setb(x, y, z, "oak_fence")
    setb(x, y + 1, z, f"oak_sign[rotation={rot}]")
    SIGNS.append((x, y + 1, z, list(lines)[:4]))

# ---------- дерева ----------
def leaves(kind): return f"{kind}_leaves[persistent=true]"
def oak(x, z, kind="oak", y=None):
    y = (top_at(x, z) + 1) if y is None else y; hgt = rng.randint(5, 7)
    fill(x, y, z, x, y + hgt - 1, z, f"{kind}_log")
    for dy, r in ((hgt - 3, 2), (hgt - 2, 2), (hgt - 1, 1), (hgt, 1)):
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if (dx or dz) and abs(dx) == r and abs(dz) == r and (dy >= hgt - 1 or rng.random() < 0.5): continue
                if dx == 0 and dz == 0 and dy < hgt: continue
                setb(x + dx, y + dy, z + dz, leaves(kind))
def spruce(x, z, y=None):
    y = (top_at(x, z) + 1) if y is None else y; hgt = rng.randint(8, 12)
    fill(x, y, z, x, y + hgt - 1, z, "spruce_log")
    for k in range(hgt - 2):
        r = [1, 2, 3][(hgt - 2 - k) % 3] if k < hgt - 4 else 1
        yy = y + 2 + k
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if abs(dx) + abs(dz) <= r + 1 and (dx or dz): setb(x + dx, yy, z + dz, leaves("spruce"))
    setb(x, y + hgt, z, leaves("spruce")); setb(x, y + hgt + 1, z, leaves("spruce"))
def big_oak(x, z, kind="dark_oak", hgt=None, crown=5.0, y=None):
    y = (top_at(x, z) + 1) if y is None else y; hgt = hgt or rng.randint(9, 13)
    fill(x, y - 1, z, x + 1, y + hgt, z + 1, f"{kind}_log")
    sphere(x + 0.5, y + hgt, z + 0.5, crown, leaves(kind), ry=crown * 0.7, chance=0.92)
    for _ in range(3):
        ox, oz = rng.randint(-4, 4), rng.randint(-4, 4)
        sphere(x + ox, y + hgt - 2, z + oz, crown * 0.6, leaves(kind), ry=crown * 0.45, chance=0.9)
def flat_tree(x, z, kind="oak", y=None):
    """Дерево з пласкою широкою кроною в 2–3 яруси на тонкому вигнутому стовбурі (нотатки 19, розд. 9)."""
    y = (top_at(x, z) + 1) if y is None else y; hgt = rng.randint(5, 8)
    lx, lz = rng.uniform(-1.0, 1.0), rng.uniform(-1.0, 1.0); px, pz = x, z
    for k in range(hgt):
        px, pz = int(round(x + lx * k * k / hgt)), int(round(z + lz * k * k / hgt)); setb(px, y + k, pz, f"{kind}_log")
    tiers = [(hgt, rng.uniform(4.5, 6.5), 0, 0), (hgt - 2, rng.uniform(3.0, 4.6), rng.randint(-2, 2), rng.randint(-2, 2))]   # v6: ширше
    if rng.random() < 0.5: tiers.append((hgt + 1, 1.8, rng.randint(-1, 1), rng.randint(-1, 1)))
    for dy, r, ox, oz in tiers:
        disc(px + ox, y + dy, pz + oz, r, leaves(kind), chance=0.93)
        if dy == hgt: disc(px + ox, y + dy - 1, pz + oz, r * 0.55, leaves(kind), chance=0.8)
        if ox or oz: fill(px, y + dy - 1, pz, px + ox, y + dy - 1, pz + oz, f"{kind}_log") if abs(ox) + abs(oz) == 1 else None
def bezier3(p0, p1, p2, p3, n):
    return [tuple((1 - t) ** 3 * np.array(p0) + 3 * (1 - t) ** 2 * t * np.array(p1) + 3 * (1 - t) * t * t * np.array(p2) + t ** 3 * np.array(p3)) for t in np.linspace(0, 1, n)]
def limb(pts, r0, r1, state):
    """Гілка чи стовбур уздовж кривої: диски, що тоншають від r0 до r1."""
    n = len(pts)
    for i, (x, y, z) in enumerate(pts):
        r = r0 + (r1 - r0) * i / max(n - 1, 1)
        if r < 0.7: setb(int(round(x)), int(round(y)), int(round(z)), state)
        else: sphere(x, y, z, r, state, ry=0.9)
def mega_tree(x, z, dark=True, height=None, crown_scale=1.0):
    """Дерево-велетень за прийомом Nivla: крива Безьє стовбура з радіусом 3→1, корені, 3 рівні гілок,
    крона з 6–12 сплюснутих еліпсоїдів: зверху азалія й дуб, знизу темний дуб; ліани на стовбурі (нотатки 19, розд. 8)."""
    g = top_at(x, z) + 1; H = height or rng.randint(26, 36); lean = (rng.uniform(-6, 6), rng.uniform(-6, 6))
    wood = "dark_oak_wood" if dark else "oak_wood"
    trunk = bezier3((x, g - 3, z), (x + lean[0] * 0.2, g + H * 0.35, z + lean[1] * 0.2), (x + lean[0] * 0.8, g + H * 0.7, z + lean[1] * 0.8), (x + lean[0], g + H, z + lean[1]), H + 4)
    limb(trunk, 3.4, 1.2, wood)
    for k in range(rng.randint(4, 6)):   # корені
        a = k / 5 * 6.283 + rng.uniform(-0.3, 0.3); L = rng.uniform(5, 8)
        limb(bezier3((x, g + 3, z), (x + 2 * math.cos(a), g + 1, z + 2 * math.sin(a)), (x + L * 0.7 * math.cos(a), g, z + L * 0.7 * math.sin(a)), (x + L * math.cos(a), g - 2, z + L * math.sin(a)), 10), 1.6, 0.6, wood)
    ends = [trunk[-1]]
    for lvl, frac in enumerate((0.55, 0.72, 0.88)):   # три рівні гілок
        bx, by, bz = trunk[int(frac * (len(trunk) - 1))]
        for _ in range(rng.randint(2, 3)):
            a = rng.uniform(0, 6.283); L = rng.uniform(7, 12) * (1 - lvl * 0.2) * crown_scale
            e = (bx + L * math.cos(a), by + rng.uniform(3, 7), bz + L * math.sin(a))
            limb(bezier3((bx, by, bz), (bx + L * 0.3 * math.cos(a), by + 1, bz + L * 0.3 * math.sin(a)), (e[0], e[1] - 2, e[2]), e, int(L) + 4), 1.4, 0.6, wood)
            ends.append(e)
    for ex, ey, ez in ends:
        for _ in range(rng.randint(1, 2)):
            rx, ry_ = rng.uniform(4.5, 7.5) * crown_scale, rng.uniform(2.2, 3.4); cx_, cy_, cz_ = ex + rng.uniform(-2, 2), ey + rng.uniform(0, 2), ez + rng.uniform(-2, 2)
            R = int(math.ceil(rx)); X, Y, Z = np.mgrid[-R:R + 1, -int(ry_) - 1:int(ry_) + 2, -R:R + 1]
            m = (X * X + Z * Z) / (rx * rx) + (Y * Y) / (ry_ * ry_) <= 1
            m &= np.sin(X * 0.9 + cx_) * np.cos(Z * 0.8 + cz_) + 0.3 * np.sin(Y * 1.7) > -0.75   # вирізано шумом
            m &= nrng.random(m.shape) < 0.92
            up, dn = m & (Y >= 1), m & (Y < 1)
            put(X[dn] + int(cx_), Y[dn] + int(cy_), Z[dn] + int(cz_), leaves("dark_oak" if dark else "oak"))
            sel = nrng.random(up.sum()) < 0.5
            put((X[up] + int(cx_))[sel], (Y[up] + int(cy_))[sel], (Z[up] + int(cz_))[sel], "azalea_leaves[persistent=true]")
            put((X[up] + int(cx_))[~sel], (Y[up] + int(cy_))[~sel], (Z[up] + int(cz_))[~sel], "flowering_azalea_leaves[persistent=true]" if rng.random() < 0.3 else leaves("oak"))
    for side, (dx_, dz_) in (("west", (4, 0)), ("east", (-4, 0)), ("north", (0, 4)), ("south", (0, -4))):   # ліани на корі
        if rng.random() < 0.6:
            vy = g + rng.randint(3, 10)
            fill(x + dx_, vy, z + dz_, x + dx_, vy + rng.randint(3, 8), z + dz_, f"vine[{side}=true]")
def white_dead_tree(x, z):
    """Біле мертве дерево зі світлою корою (Corrupted Orchard)."""
    g = top_at(x, z) + 1; hgt = rng.randint(4, 7); fill(x, g, z, x, g + hgt, z, "birch_wood")
    for _ in range(rng.randint(2, 4)):
        dx_, dz_ = rng.choice(((1, 0), (-1, 0), (0, 1), (0, -1))); yy = g + rng.randint(hgt // 2, hgt)
        for q in range(1, rng.randint(2, 4)): setb(x + dx_ * q, yy + q // 2, z + dz_ * q, "stripped_birch_log[axis=x]" if dx_ else "stripped_birch_log[axis=z]")
def bush(x, z):
    y = top_at(x, z) + 1; kind = rng.choice(("oak", "oak", "birch", "azalea"))
    setb(x, y, z, leaves(kind))
    if rng.random() < 0.5: setb(x + rng.choice((-1, 1)), y, z, leaves(kind))
    if rng.random() < 0.3: setb(x, y + 1, z, leaves(kind))

# ---------- споруди ----------
STYLES = [  # стіни, каркас, підлога, (сходи даху, блок даху)
    ("oak_planks", "spruce_log", "spruce_planks", ("dark_oak_stairs", "dark_oak_planks")),
    ("white_terracotta", "dark_oak_log", "oak_planks", ("spruce_stairs", "spruce_planks")),
    ("stone_bricks", "stripped_spruce_log", "spruce_planks", ("deepslate_tile_stairs", "deepslate_tiles")),
    ("calcite", "spruce_log", "oak_planks", ("brick_stairs", "bricks")),
    ("mud_bricks", "oak_log", "spruce_planks", ("mangrove_stairs", "mangrove_planks")),
]
def gable_roof(x0, z0, x1, z1, y, roof, along_x=True):
    stairs, solid = roof
    if along_x:
        for k in range((z1 - z0 + 3) // 2 + 1):
            zn, zs, yy = z0 - 1 + k, z1 + 1 - k, y + k
            if zn > zs: break
            if zn == zs: fill(x0 - 1, yy, zn, x1 + 1, yy, zn, solid.replace("planks", "slab") if "planks" in solid else solid); break
            fill(x0 - 1, yy, zn, x1 + 1, yy, zn, f"{stairs}[facing=south]"); fill(x0 - 1, yy, zs, x1 + 1, yy, zs, f"{stairs}[facing=north]")
            if zs - zn > 1: fill(x0, yy, zn + 1, x0, yy, zs - 1, solid); fill(x1, yy, zn + 1, x1, yy, zs - 1, solid)
    else:
        for k in range((x1 - x0 + 3) // 2 + 1):
            xw, xe, yy = x0 - 1 + k, x1 + 1 - k, y + k
            if xw > xe: break
            if xw == xe: fill(xw, yy, z0 - 1, xw, yy, z1 + 1, solid.replace("planks", "slab") if "planks" in solid else solid); break
            fill(xw, yy, z0 - 1, xw, yy, z1 + 1, f"{stairs}[facing=east]"); fill(xe, yy, z0 - 1, xe, yy, z1 + 1, f"{stairs}[facing=west]")
            if xe - xw > 1: fill(xw + 1, yy, z0, xe - 1, yy, z0, solid); fill(xw + 1, yy, z1, xe - 1, yy, z1, solid)
def pyramid_roof(x0, z0, x1, z1, y, roof):
    stairs, solid = roof
    k = 0
    while x0 - 1 + k <= x1 + 1 - k and z0 - 1 + k <= z1 + 1 - k:
        a, b, c, d, yy = x0 - 1 + k, x1 + 1 - k, z0 - 1 + k, z1 + 1 - k, y + k
        if a == b or c == d: fill(a, yy, c, b, yy, d, solid); break
        fill(a, yy, c, b, yy, c, f"{stairs}[facing=south]"); fill(a, yy, d, b, yy, d, f"{stairs}[facing=north]")
        fill(a, yy, c + 1, a, yy, d - 1, f"{stairs}[facing=east]"); fill(b, yy, c + 1, b, yy, d - 1, f"{stairs}[facing=west]")
        k += 1
def platform(x0, z0, x1, z1, mat="cobblestone"):
    """Вирівнює ділянку: фундамент до найвищої точки, повітря над нею. Повертає Y підлоги."""
    area = TOP[max(z0, 0):z1 + 1, max(x0, 0):x1 + 1]
    fy = int(area.max()) + 1 if area.size else SEA
    lo = int(area.min()) if area.size else SEA - 4
    fill(x0, min(lo, fy - 1) - 2, z0, x1, fy - 1, z1, mat)
    fill(x0, fy, z0, x1, fy + 20, z1, "air")
    return fy
# ---------- v5: будинки за чек-листом (нотатки 18, розд. 8; 19, розд. 9) ----------
def card(dx, dz):  # напрям на сторону світу
    if abs(dx) >= abs(dz): return "east" if dx > 0 else "west"
    return "south" if dz > 0 else "north"
OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}
DV = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
BASE_MIX = ("cobblestone", "stone", "andesite", "mossy_cobblestone", "cobblestone", "tuff")
SLAB = {"dark_oak_planks": "dark_oak_slab", "spruce_planks": "spruce_slab", "oak_planks": "oak_slab", "acacia_planks": "acacia_slab",
        "mangrove_planks": "mangrove_slab", "birch_planks": "birch_slab", "deepslate_tiles": "deepslate_tile_slab", "bricks": "brick_slab",
        "stone_bricks": "stone_brick_slab", "red_sandstone": "red_sandstone_slab", "purpur_block": "purpur_slab", "mud_bricks": "mud_brick_slab",
        "cobblestone": "cobblestone_slab", "polished_blackstone_bricks": "polished_blackstone_brick_slab"}
TRIM = ("stone_brick_stairs", "stone_bricks")
MOSS_P = 0.0   # частка дахів, що обросли мохом і листям (порт, v6)
def pane(axis_x):  # скляна панель, з'єднана вздовж стіни
    return "glass_pane[east=true,west=true]" if axis_x else "glass_pane[north=true,south=true]"

def gable2(x0, z0, x1, z1, y, roof, along_x=True, ov=1, pitch=1, trim=None, gable=None, flare=True):
    """Двосхилий дах: звис ov, нахил pitch (1 = 45°, 2 = 63°), нижній ряд пологіший (вигнутий край), облямівка ребер trim,
    фронтони з gable. Гребінь уздовж x (along_x) або z. Повертає висоту гребеня."""
    stairs, solid = roof
    tst, tso = trim or roof
    if along_x: a0, a1, lo, hi, w0, w1 = x0 - ov, x1 + ov, z0 - ov, z1 + ov, x0, x1
    else: a0, a1, lo, hi, w0, w1 = z0 - ov, z1 + ov, x0 - ov, x1 + ov, z0, z1
    nrow = (hi - lo) // 2 + 1
    hs, yy = [], y
    for k in range(nrow):
        yy = y if k == 0 else yy + (1 if (flare and k == 1 and pitch > 1) else pitch); hs.append(yy)
    def P(a, c, yv, st):   # a — уздовж гребеня, c — поперек
        if along_x: setb(a, yv, c, st)
        else: setb(c, yv, a, st)
    def F(a0_, a1_, c, yv0, yv1, st):
        if yv1 < yv0: return
        if along_x: fill(a0_, yv0, c, a1_, yv1, c, st)
        else: fill(c, yv0, a0_, c, yv1, a1_, st)
    if gable:   # фронтони: трикутник стіни під дахом
        for c in range(lo + 1, hi):
            k = min(c - lo, hi - c); top_ = hs[min(k, nrow - 1)] - 1
            for a in (w0, w1): F(a, a, c, y, top_, gable)
    f_lo, f_hi = ("south", "north") if along_x else ("east", "west")
    for k in range(nrow):
        n_, s_ = lo + k, hi - k; yk = hs[k]; prev = hs[k - 1] if k else yk - 1
        if n_ == s_:   # непарна ширина: гребінь
            F(a0, a1, n_, prev + 1, yk, solid); F(a0, a1, n_, yk + 1, yk + 1, SLAB.get(tso, tso))
            return yk + 1
        for c, face in ((n_, f_lo), (s_, f_hi)):
            F(a0 + 1, a1 - 1, c, prev + 1, yk - 1, solid)
            F(a0 + 1, a1 - 1, c, yk, yk, f"{stairs}[facing={face}]")
            if MOSS_P:
                for a in range(a0 + 1, a1):
                    if rng.random() < MOSS_P: P(a, c, yk + 1, "moss_carpet" if rng.random() < 0.75 else "oak_leaves[persistent=true]")
            for a in (a0, a1):   # облямівка ребер
                F(a, a, c, prev + 1, yk - 1, tso); F(a, a, c, yk, yk, f"{tst}[facing={face}]")
        if s_ - n_ == 1:   # парна ширина: шапка на гребені
            F(a0, a1, n_, yk + 1, yk + 1, SLAB.get(tso, tso)); F(a0, a1, s_, yk + 1, yk + 1, SLAB.get(tso, tso))
            return yk + 1
    return hs[-1]

def house(cx, cz, w, d, style=None, hgt=None, door_side="south", floors=None, jetty=False, trim=None, pitch=None, cross=None,
          chimney=None, lamp=False, ov=1):
    """Будинок: цоколь із суміші каменю, каркас 60/30/10 (стовпи через 3–4, балки на поверхах), вікна з віконницями,
    верхні поверхи нависають над вулицею (jetty), дах зі звисом, вигнутим краєм, облямівкою, Т-дах і димар."""
    wall, frame, floor, roof = style or rng.choice(STYLES)
    custom = hgt is not None
    if not custom:
        floors = floors or (2 if w >= 9 and d >= 7 and rng.random() < 0.6 else 1)
        hgt = 4 * floors
    else: floors = 1
    x0, z0 = cx - w // 2, cz - d // 2; x1, z1 = x0 + w - 1, z0 + d - 1
    fy = platform(x0 - 1, z0 - 1, x1 + 1, z1 + 1, "cobblestone")
    for x in range(x0 - 1, x1 + 2):   # сходинки й каміння довкола основи
        for z in (z0 - 1, z1 + 1): setb(x, fy - 1, z, rng.choice(BASE_MIX))
    for z in range(z0, z1 + 1):
        for x in (x0 - 1, x1 + 1): setb(x, fy - 1, z, rng.choice(BASE_MIX))
    fill(x0, fy - 1, z0, x1, fy - 1, z1, floor)
    jet = jetty and floors >= 2
    ux0, uz0, ux1, uz1 = x0, z0, x1, z1
    if jet:
        if door_side == "south": uz1 += 1
        elif door_side == "north": uz0 -= 1
        elif door_side == "east": ux1 += 1
        else: ux0 -= 1
    fr_axis = lambda ax: f"{frame}[axis={ax}]" if frame.endswith("_log") or frame.endswith("_wood") else frame
    posts = 3 if rng.random() < 0.5 else 4
    for k in range(floors):
        a0, b0, a1, b1 = (x0, z0, x1, z1) if k == 0 else (ux0, uz0, ux1, uz1)
        ylo, yhi = fy + 4 * k, (fy + 4 * k + 3) if not custom else fy + hgt - 1
        hollow(a0, ylo, b0, a1, yhi, b1, wall)
        fill(a0 + 1, ylo, b0 + 1, a1 - 1, yhi - 1, b1 - 1, "air")
        if k == 0:   # цоколь: нижній ряд стіни з каменю
            for x in range(a0, a1 + 1):
                for z in (b0, b1): setb(x, ylo, z, rng.choice(BASE_MIX))
            for z in range(b0 + 1, b1): setb(a0, ylo, z, rng.choice(BASE_MIX)); setb(a1, ylo, z, rng.choice(BASE_MIX))
        # балка зверху поверху й стовпи каркаса
        fill(a0, yhi, b0, a1, yhi, b0, fr_axis("x")); fill(a0, yhi, b1, a1, yhi, b1, fr_axis("x"))
        fill(a0, yhi, b0, a0, yhi, b1, fr_axis("z")); fill(a1, yhi, b0, a1, yhi, b1, fr_axis("z"))
        xs_ = sorted(set([a0, a1] + list(range(a0 + posts, a1, posts)))); zs_ = sorted(set([b0, b1] + list(range(b0 + posts, b1, posts))))
        for x in xs_:
            for z in (b0, b1): fill(x, ylo + (1 if k == 0 else 0), z, x, yhi - 1, z, fr_axis("y"))
        for z in zs_:
            for x in (a0, a1): fill(x, ylo + (1 if k == 0 else 0), z, x, yhi - 1, z, fr_axis("y"))
        fill(a0 + 1, yhi, b0 + 1, a1 - 1, yhi, b1 - 1, floor)   # стеля/підлога наступного поверху
        # вікна посередині прольотів між стовпами, віконниці-люки
        wy = ylo + 1 if custom else ylo + 1
        for xa, xb in zip(xs_, xs_[1:]):
            if xb - xa < 3: continue
            xm = (xa + xb) // 2
            for z, out in ((b0, "north"), (b1, "south")):
                for yv in ((wy, wy + 1) if (xb - xa) >= 4 and k > 0 else (wy,)): setb(xm, yv, z, pane(True))
                oz = z + DV[out][1]
                if rng.random() < 0.7:
                    for sx_ in (xm - 1, xm + 1):
                        if sx_ not in xs_: setb(sx_, wy, oz, f"spruce_trapdoor[facing={out},open=true,half=bottom]")
        for za, zb in zip(zs_, zs_[1:]):
            if zb - za < 3: continue
            zm = (za + zb) // 2
            for x, out in ((a0, "west"), (a1, "east")):
                for yv in ((wy, wy + 1) if (zb - za) >= 4 and k > 0 else (wy,)): setb(x, yv, zm, pane(False))
                ox_ = x + DV[out][0]
                if rng.random() < 0.7:
                    for sz_ in (zm - 1, zm + 1):
                        if sz_ not in zs_: setb(ox_, wy, sz_, f"spruce_trapdoor[facing={out},open=true,half=bottom]")
        setb((a0 + a1) // 2, yhi - 1, (b0 + b1) // 2, "lantern[hanging=true]")
        if custom: break
    if jet:   # кронштейни під виступом верхнього поверху
        ins = OPP[door_side]
        if door_side in ("south", "north"):
            zz = uz1 if door_side == "south" else uz0
            for x in range(x0, x1 + 1): setb(x, fy + 3, zz, f"{'stone_brick_stairs' if x in (x0, x1) else 'spruce_stairs'}[facing={ins},half=top]")
        else:
            xx = ux1 if door_side == "east" else ux0
            for z in range(z0, z1 + 1): setb(xx, fy + 3, z, f"{'stone_brick_stairs' if z in (z0, z1) else 'spruce_stairs'}[facing={ins},half=top]")
    # двері, дашок над ними й ліхтар
    dx, dz = {"south": (cx, z1), "north": (cx, z0), "east": (x1, cz), "west": (x0, cz)}[door_side]
    setb(dx, fy, dz, f"spruce_door[facing={door_side},half=lower]"); setb(dx, fy + 1, dz, f"spruce_door[facing={door_side},half=upper]")
    ox_, oz_ = DV[door_side]
    if not jet:
        for t in (-1, 0, 1):
            px_, pz_ = (dx + t, dz + oz_) if door_side in ("south", "north") else (dx + ox_, dz + t)
            setb(px_, fy + 2, pz_, SLAB.get(roof[1], roof[1]) if roof[1] in SLAB else f"{roof[0]}[facing={OPP[door_side]},half=top]")
    lx_, lz_ = (dx + 1, dz + oz_) if door_side in ("south", "north") else (dx + ox_, dz + 1)
    setb(lx_, fy + 2 if jet else fy + 1, lz_, "lantern[hanging=true]")
    if lamp:   # ліхтар на балці над вулицею
        bx_, bz_ = (dx - 2, dz + 2 * oz_) if door_side in ("south", "north") else (dx + 2 * ox_, dz - 2)
        arm = "spruce_fence[north=true,south=true]" if door_side in ("south", "north") else "spruce_fence[east=true,west=true]"
        setb(bx_ - ox_, fy + 3, bz_ - oz_, arm); setb(bx_, fy + 3, bz_, arm); setb(bx_, fy + 2, bz_, "lantern[hanging=true]")
    # меблі
    by = fy + 4 * (floors - 1)
    bed = rng.choice(("red_bed", "blue_bed", "white_bed", "yellow_bed", "green_bed"))
    setb(ux0 + 1, by, uz1 - 1, f"{bed}[part=foot,facing=north]"); setb(ux0 + 1, by, uz1 - 2, f"{bed}[part=head,facing=north]")
    setb(x1 - 1, fy, z1 - 1, "bookshelf"); setb(x0 + 1, fy, z0 + 1, rng.choice(("crafting_table", "furnace[facing=east]", "barrel", "smoker[facing=east]")))
    setb(cx, fy, cz, rng.choice(("red_carpet", "brown_carpet", "green_carpet", "white_carpet")))
    if floors > 1:   # драбина між поверхами
        fill(x1 - 1, fy, z0 + 1, x1 - 1, fy + 4 * (floors - 1), z0 + 1, "ladder[facing=west]")
    for x, z in ((x0 - 1, z0 - 1), (x1 + 1, z1 + 1), (x0 - 1, z1 + 1), (x1 + 1, z0 - 1)):  # квіти різної висоти біля стін
        r_ = rng.random()
        if r_ < 0.25: setb(x, fy, z, "rose_bush[half=lower]"); setb(x, fy + 1, z, "rose_bush[half=upper]")
        elif r_ < 0.45: setb(x, fy, z, "lilac[half=lower]"); setb(x, fy + 1, z, "lilac[half=upper]")
        elif r_ < 0.7: setb(x, fy, z, rng.choice(("azalea_leaves[persistent=true]", "flowering_azalea", "potted_red_tulip", "potted_cornflower")))
    # дах
    tw, td = ux1 - ux0 + 1, uz1 - uz0 + 1
    along_x = tw >= td
    span = td if along_x else tw
    if pitch is None: pitch = 2 if (span <= 9 and rng.random() < 0.6) else 1
    ry = gable2(ux0, uz0, ux1, uz1, fy + hgt, roof, along_x=along_x, ov=ov, pitch=pitch, trim=trim, gable=wall)
    door_long = (door_side in ("south", "north")) == along_x
    if cross is None: cross = door_long and (tw if along_x else td) >= 11 and rng.random() < 0.55
    if cross:   # Т-дах: поперечний фронтон над дверима
        if door_side in ("south", "north"):
            zf = uz1 if door_side == "south" else uz0
            za_, zb_ = (cz, zf) if door_side == "south" else (zf, cz)
            gable2(cx - 2, za_, cx + 2, zb_, fy + hgt, roof, along_x=False, ov=1, pitch=pitch, trim=trim, gable=wall)
        else:
            xf = ux1 if door_side == "east" else ux0
            xa_, xb_ = (cx, xf) if door_side == "east" else (xf, cx)
            gable2(xa_, cz - 2, xb_, cz + 2, fy + hgt, roof, along_x=True, ov=1, pitch=pitch, trim=trim, gable=wall)
    if chimney is None: chimney = rng.random() < 0.45
    if chimney:   # димар; іноді з багаттям нагорі (дим)
        chx, chz = (x0 + 1, z0 + 1) if door_side != "north" else (x0 + 1, z1 - 1)
        mat_ = rng.choice(("bricks", "cobblestone", "stone_bricks"))
        fill(chx, fy, chz, chx, ry + 2, chz, mat_)
        if rng.random() < 0.35: setb(chx, ry + 3, chz, "campfire[lit=true]")
    return fy

def disc(cx, y, cz, r, state, chance=1.0):
    R = int(math.ceil(r)); X, Z = np.mgrid[-R:R + 1, -R:R + 1]
    m = X * X + Z * Z <= r * r
    if chance < 1: m &= nrng.random(m.shape) < chance
    put(X[m] + int(cx), np.full(m.sum(), int(y)), Z[m] + int(cz), state)
def col(x, z, y0, y1, pal=None):
    """Стовпчик муру з градієнтом каменю: унизу моховитий, посередині цегла, вгорі світліший (нотатки 18, розд. 8)."""
    if y1 < y0: return
    if pal: fill(x, y0, z, x, y1, z, pal); return
    H = y1 - y0 + 1; y = y0
    while y <= y1:
        rel = (y - y0) / H; run = rng.randint(1, 3)
        lst = (("mossy_cobblestone", "mossy_stone_bricks", "cobblestone", "mossy_stone_bricks", "stone") if rel < 0.2 else
               ("stone_bricks", "stone_bricks", "cracked_stone_bricks", "stone_bricks", "andesite") if rel < 0.72 else
               ("stone_bricks", "smooth_stone", "stone_bricks", "polished_andesite"))
        fill(x, y, z, x, min(y + run - 1, y1), z, rng.choice(lst)); y += run

def cone_roof(cx, cz, y, r, roof, h=None):
    """Конічний дах зі сходів на кільцях, що звужуються до вершини. Повертає Y вершини."""
    stairs, solid = roof
    n = h or int(r * 1.9) + 2
    R = int(math.ceil(r)) + 1; X, Z = np.mgrid[-R:R + 1, -R:R + 1]; D = np.hypot(X, Z)
    for k in range(n):
        rk = r * (1 - k / n)
        if rk < 0.6: setb(cx, y + k, cz, solid); continue
        ring = (D <= rk + 0.2) & (D > rk - 1.4)
        for a, b in zip(X[ring], Z[ring]):
            if D[a + R, b + R] > rk - 0.75: setb(cx + a, y + k, cz + b, f"{stairs}[facing={card(-a, -b)}]")
            else: setb(cx + a, y + k, cz + b, solid)
    return y + n

def flag(x, y, z, color, side="east", tall=3):
    """Жердина з прапорцем (нотатки 19, розд. 9)."""
    fill(x, y, z, x, y + tall, z, "spruce_fence")
    dx, dz = DV[side]
    fill(x + dx, y + tall - 1, z + dz, x + 2 * dx, y + tall, z + 2 * dz, f"{color}_wool")

def round_tower(cx, cz, r, hgt, roof=("deepslate_tile_stairs", "deepslate_tiles"), flag_c=None, base=None, pal=None, slits=True):
    """Кругла вежа: стіни з градієнтом, перекриття, бійниці; з конічним дахом або з зубцями й виносом. Повертає Y верху стіни."""
    R = int(math.ceil(r)) + 2; X, Z = np.mgrid[-R:R + 1, -R:R + 1]; D = np.hypot(X, Z)
    shell = (D <= r + 0.5) & (D > r - 0.7); inner = D <= r - 0.7
    if base is None: base = int(np.median([top_at(cx + a, cz + b) for a, b in zip(X[D <= r + 0.5], Z[D <= r + 0.5])]))
    yt = base + hgt
    for a, b in zip(X[shell], Z[shell]): col(cx + a, cz + b, min(top_at(cx + a, cz + b), base) - 3, yt, pal)
    for a, b in zip(X[inner], Z[inner]):
        g_ = top_at(cx + a, cz + b)
        if g_ < base: fill(cx + a, g_, cz + b, cx + a, base - 1, cz + b, "cobblestone")
        fill(cx + a, base, cz + b, cx + a, yt - 1, cz + b, "air")
        for yv in range(base - 1, yt + 1, 6): setb(cx + a, yv, cz + b, "spruce_planks")
        setb(cx + a, yt, cz + b, "spruce_planks")
    if slits:
        for yv in range(base + 4, yt - 1, 6):
            for a, b in ((0, -int(r)), (0, int(r)), (-int(r), 0), (int(r), 0)): setb(cx + a, yv, cz + b, "air"); setb(cx + a, yv + 1, cz + b, "air")
    setb(cx, base + 2, cz, "lantern")
    if roof:
        top_ = cone_roof(cx, cz, yt + 1, r + 1.6, roof)
        if flag_c: flag(cx, top_, cz, flag_c, side=rng.choice(("east", "west", "north", "south")))
    else:
        rim = (D > r + 0.5) & (D <= r + 1.5)
        for a, b in zip(X[rim], Z[rim]):
            setb(cx + a, yt - 1, cz + b, f"stone_brick_stairs[facing={card(-a, -b)},half=top]"); fill(cx + a, yt, cz + b, cx + a, yt + 1, cz + b, "stone_bricks")
            if int((math.atan2(b, a) + 4) * r) % 2 == 0: setb(cx + a, yt + 2, cz + b, "stone_bricks")
        for a, b in zip(X[shell], Z[shell]): setb(cx + a, yt + 1, cz + b, "stone_bricks")
        if flag_c: flag(cx, yt + 1, cz, flag_c, tall=4)
    return yt

def ring_pts(cx, cz, R, wob=0.04, ph=0.0):
    n = int(2 * math.pi * R * 1.2)
    return [(cx + R * (1 + wob * math.sin(3 * a + ph) + 0.6 * wob * math.sin(5 * a + 2 * ph)) * math.cos(a),
             cz + R * (1 + wob * math.sin(3 * a + ph) + 0.6 * wob * math.sin(5 * a + 2 * ph)) * math.sin(a), a)
            for a in (k / n * 2 * math.pi for k in range(n))]

def ring_gates(ring, extra_angles=()):
    """Брами там, де дорога перетинає лінію муру; плюс додаткові за кутом."""
    xs = np.array([p[0] for p in ring]); zs = np.array([p[1] for p in ring]); d = dist_to_paths(xs, zs, ROADS)
    n, out = len(ring), []
    for i in range(n):
        if d[i] < 2.5 and d[i] <= d[(i - 1) % n] and d[i] <= d[(i + 1) % n] and all(min(abs(i - j), n - abs(i - j)) > 12 for j in out): out.append(i)
    for a in extra_angles:
        out.append(int(round((a % (2 * math.pi)) / (2 * math.pi) * n)) % n)
    return out

def moat(cx, cz, R, wob, ph, inner, outer, keep):
    """Рів із водою навколо муру (там, де keep(кут)); змінює рельєф, рівень води й категорії."""
    M = int(R + outer + 4); a0, a1, b0, b1 = max(cx - M, 0), min(cx + M, SIZE - 1), max(cz - M, 0), min(cz + M, SIZE - 1)
    X, Z = np.meshgrid(np.arange(a0, a1 + 1) + 0.5, np.arange(b0, b1 + 1) + 0.5)
    A = np.arctan2(Z - cz, X - cx); rho = np.hypot(X - cx, Z - cz)
    rr = R * (1 + wob * np.sin(3 * A + ph) + 0.6 * wob * np.sin(5 * A + 2 * ph)); off = rho - rr
    T_ = TOP[b0:b1 + 1, a0:a1 + 1]
    m = (off >= inner) & (off <= outer) & np.vectorize(keep)(A) & (T_ >= SEA - 1)
    if not m.any(): return
    L = int(np.median(T_[m])); mid, hw = (inner + outer) / 2, (outer - inner) / 2
    dep = np.round(2 + 2 * (1 - np.abs(off - mid) / hw)).astype(np.int32)
    T_[m] = (L - 1 - dep)[m]
    WL[b0:b1 + 1, a0:a1 + 1][m] = L - 1
    cat[b0:b1 + 1, a0:a1 + 1][m] = RIVERBED
    nb = noise_col[b0:b1 + 1, a0:a1 + 1]
    TOPB[b0:b1 + 1, a0:a1 + 1][m] = np.array(mix("gravel", "clay", "gravel", "mud"), np.uint16)[nb[m] % 4]
    bank = (off >= inner - 1.2) & (off < inner) | (off > outer) & (off <= outer + 1.2)
    bank &= np.vectorize(keep)(A) & (T_ >= SEA - 1)
    TOPB[b0:b1 + 1, a0:a1 + 1][bank] = np.array(mix("stone_bricks", "mossy_stone_bricks", "cobblestone", "stone_bricks"), np.uint16)[nb[bank] % 4]
    return L

PAVE = ("andesite", "stone", "cobblestone", "polished_andesite", "stone_bricks", "cracked_stone_bricks", "andesite", "tuff")
GUTTER = ("cobblestone", "andesite", "mossy_cobblestone", "stone")
def city_streets(cx, cz, Rin, extra=()):
    """Вулиці всередині мурів: мостова мішаниною каменю, жолоби на 1 блок нижче з обох боків (нотатки 19, розд. 9)."""
    M = int(Rin) + 2; a0, a1, b0, b1 = cx - M, cx + M, cz - M, cz + M
    X, Z = np.meshgrid(np.arange(a0, a1 + 1) + 0.5, np.arange(b0, b1 + 1) + 0.5)
    ins = np.hypot(X - cx, Z - cz) < Rin
    dS = dRoad[b0:b1 + 1, a0:a1 + 1].copy()
    for pts in extra: dS = np.minimum(dS, path_info(X, Z, pts)[0])
    rv = river[b0:b1 + 1, a0:a1 + 1] | (cat[b0:b1 + 1, a0:a1 + 1] == RIVERBED)
    core = ins & (dS < 2.6) & ~rv; gut = ins & (dS >= 2.6) & (dS < 3.6) & ~rv
    nb = noise_col[b0:b1 + 1, a0:a1 + 1]
    TOPB[b0:b1 + 1, a0:a1 + 1][core] = np.array(mix(*PAVE), np.uint16)[nb[core] % len(PAVE)]
    TOP[b0:b1 + 1, a0:a1 + 1][gut] -= 1
    TOPB[b0:b1 + 1, a0:a1 + 1][gut] = np.array(mix(*GUTTER), np.uint16)[nb[gut] % len(GUTTER)]
    STREETMASK[b0:b1 + 1, a0:a1 + 1] |= core | gut
    cat[b0:b1 + 1, a0:a1 + 1][core | gut] = PATH

def city_wall(cx, cz, ring, hgt=13, thick=5, gates=(), tower_every=46, tower_r=4.5, roof=("deepslate_tile_stairs", "deepslate_tiles"),
              flag_c="red", keep=lambda a: True, low=lambda a: False, name=None, moat_out=None, square=None):
    """Мур 5 блоків завтовшки й 12–16 заввишки: зубці з виносом, круглі (або квадратні зі шпилем) вежі, подвійні брами з ґратами."""
    n = len(ring); half = thick // 2
    G = np.array([max(top_at(x, z), SEA) for x, z, _ in ring], float)
    k = 8; Gs = np.convolve(np.r_[G[-k:], G, G[:k]], np.ones(2 * k + 1) / (2 * k + 1), "same")[k:-k]
    Wt = np.round(Gs + hgt).astype(int)
    for i, (x, z, a) in enumerate(ring):
        if not keep(a): continue
        if low(a): Wt[i] -= hgt // 2
        nx, nz = math.cos(a), math.sin(a); top = Wt[i]; inward = card(-nx, -nz)
        for o in range(-half, half + 2):
            px, pz = int(round(x + nx * o)), int(round(z + nz * o))
            if not (0 < px < SIZE - 1 and 0 < pz < SIZE - 1): continue
            g = top_at(px, pz)
            if o <= half: col(px, pz, g - 3, top)
            else:
                setb(px, top - 1, pz, f"stone_brick_stairs[facing={inward},half=top]"); fill(px, top, pz, px, top + 1, pz, "stone_bricks")
                if (i // 2) % 2 == 0: setb(px, top + 2, pz, "stone_bricks")
        if i % 16 == 0: setb(int(round(x - nx * half)), top + 1, int(round(z - nz * half)), "lantern")
    # вежі вздовж муру
    last = -999
    for i, (x, z, a) in enumerate(ring):
        if not keep(a) or i - last < tower_every * 1.2: continue
        if any(min(abs(i - j), n - abs(i - j)) < 14 for j in gates): continue
        tx, tz = int(round(x + math.cos(a) * 2)), int(round(z + math.sin(a) * 2))
        if square: square_tower(tx, tz, 4, Wt[i] - top_at(tx, tz) + 6, *square)
        else: round_tower(tx, tz, tower_r, Wt[i] - top_at(tx, tz) + 6, roof=roof, flag_c=flag_c)
        last = i
    # брами: прохід 5×7 з аркою, ґрати, дві вежі, міст через рів, знак міста за брамою
    for gi in gates:
        x, z, a = ring[gi]; nx, nz = math.cos(a), math.sin(a); tx, tz = -nz, nx
        g0 = top_at(int(round(x - nx * (half + 3))), int(round(z - nz * (half + 3))))
        if not keep(a):
            continue
        for t in range(-2, 3):
            for o in range(-half - 1, half + 2):
                px, pz = int(round(x + nx * o + tx * t)), int(round(z + nz * o + tz * t))
                fill(px, g0 + 1, pz, px, g0 + (6 if abs(t) == 2 else 7), pz, "air")
                fill(px, g0 - 3, pz, px, g0 - 1, pz, "stone_bricks"); setb(px, g0, pz, rng.choice(PAVE))
            px, pz = int(round(x + nx * half + tx * t)), int(round(z + nz * half + tz * t))
            bars = "iron_bars[east=true,west=true]" if abs(tx) > abs(tz) else "iron_bars[north=true,south=true]"
            fill(px, g0 + 6, pz, px, g0 + (6 if abs(t) == 2 else 7), pz, bars)
        for s_ in (-1, 1):
            gx_, gz_ = int(round(x + nx * 1 + tx * 7 * s_)), int(round(z + nz * 1 + tz * 7 * s_))
            if square: square_tower(gx_, gz_, 3, hgt + 5, *square)
            else: round_tower(gx_, gz_, 3.5, hgt + 5, roof=roof, flag_c=flag_c, base=g0)
            for yy in range(g0 + 2, g0 + 7):   # прапори на брамі
                px, pz = int(round(x + nx * (half + 1) + tx * 3 * s_)), int(round(z + nz * (half + 1) + tz * 3 * s_))
                setb(px, yy + 3, pz, f"{flag_c}_wool")
        if moat_out:   # міст через рів
            for o in range(half + 1, moat_out + 2):
                for t in range(-2, 3):
                    px, pz = int(round(x + nx * o + tx * t)), int(round(z + nz * o + tz * t))
                    setb(px, g0, pz, rng.choice(("stone_bricks", "stone_bricks", "polished_andesite")) if abs(t) < 2 else "stone_bricks")
                    setb(px, g0 - 1, pz, "stone_brick_slab[type=top]"); fill(px, g0 + 1, pz, px, g0 + 4, pz, "air")
                for s_ in (-3, 3):
                    px, pz = int(round(x + nx * o + tx * s_)), int(round(z + nz * o + tz * s_))
                    fill(px, g0 - 1, pz, px, g0, pz, "stone_bricks"); setb(px, g0 + 1, pz, "stone_brick_wall")
                    if o % 4 == 0: fill(px, TOP[min(max(pz, 0), SIZE - 1), min(max(px, 0), SIZE - 1)], pz, px, g0 - 2, pz, "stone_bricks")
        if name:
            sx_, sz_ = int(round(x - nx * (half + 5) + tx * 4)), int(round(z - nz * (half + 5) + tz * 4))
            if not STREETMASK[sz_, sx_]: sign(sx_, sz_, *name)
    return Wt

def square_tower(cx, cz, r, hgt, roof=("acacia_stairs", "acacia_planks"), flag_c="green", spire=True):
    """Квадратна вежа з високим пірамідальним шпилем і прапорцем на жердині (Detlas)."""
    fy = platform(cx - r, cz - r, cx + r, cz + r, "cobblestone")
    for x in range(cx - r, cx + r + 1):
        for z in range(cz - r, cz + r + 1):
            if x in (cx - r, cx + r) or z in (cz - r, cz + r): col(x, z, fy - 2, fy + hgt)
    for yv in range(fy + 4, fy + hgt, 6):
        for a, b in ((0, -r), (0, r), (-r, 0), (r, 0)): setb(cx + a, yv, cz + b, "air"); setb(cx + a, yv + 1, cz + b, "air")
    fill(cx - r + 1, fy + hgt, cz - r + 1, cx + r - 1, fy + hgt, cz + r - 1, "spruce_planks")
    for x in range(cx - r - 1, cx + r + 2):   # карниз
        for z in (cz - r - 1, cz + r + 1): setb(x, fy + hgt, z, "stone_bricks")
    for z in range(cz - r, cz + r + 1):
        for x in (cx - r - 1, cx + r + 1): setb(x, fy + hgt, z, "stone_bricks")
    y, k = fy + hgt + 1, 0
    stairs, solid = roof
    while r + 1 - k >= 0:   # крутий шпиль: на кожен крок угору 2 блоки
        a, b, c, d = cx - r - 1 + k, cx + r + 1 - k, cz - r - 1 + k, cz + r + 1 - k
        if a >= b: fill(a, y, c, a, y + 2, c, solid); y += 3; break
        for yy in (y, y + 1):
            st = yy == y + 1
            for x in range(a, b + 1):
                setb(x, yy, c, f"{stairs}[facing=south]" if st else solid); setb(x, yy, d, f"{stairs}[facing=north]" if st else solid)
            for z in range(c + 1, d):
                setb(a, yy, z, f"{stairs}[facing=east]" if st else solid); setb(b, yy, z, f"{stairs}[facing=west]" if st else solid)
        y += 2 if spire else 1; k += 1
    if flag_c: flag(cx, y, cz, flag_c, side="east", tall=4)
    return fy

def palisade(cx, cz, R, name=None):
    """Частокіл із колод навколо села, брами з двох високих стовпів і перекладини там, де дорога."""
    ring = ring_pts(cx, cz, R, 0.06, cx * 0.01)
    gates = ring_gates(ring); n = len(ring)
    for i, (x, z, a) in enumerate(ring):
        if any(min(abs(i - j), n - abs(i - j)) <= 4 for j in gates): continue
        px, pz = int(round(x)), int(round(z))
        if not is_land(px, pz) or STREETMASK[pz, px]: continue
        g = top_at(px, pz); hh = 4 + (i * 7 + px) % 3
        fill(px, g - 1, pz, px, g + hh - 1, pz, rng.choice(("spruce_log", "spruce_log", "stripped_spruce_log", "dark_oak_log")))
        setb(px, g + hh, pz, "spruce_fence")
        if i % 9 == 0: setb(int(round(x - math.cos(a))), g + 1, int(round(z - math.sin(a))), "spruce_log")   # підпори
    for gi in gates:
        x, z, a = ring[gi]; tx, tz = -math.sin(a), math.cos(a); g = top_at(int(x), int(z))
        for s_ in (-4, 4):
            px, pz = int(round(x + tx * s_)), int(round(z + tz * s_)); fill(px, g - 1, pz, px, g + 7, pz, "spruce_log")
            setb(px, g + 8, pz, "lantern")
        for t in range(-4, 5): setb(int(round(x + tx * t)), g + 7, int(round(z + tz * t)), f"spruce_log[axis={'x' if abs(tx) > abs(tz) else 'z'}]")
        if name: sign(int(round(x - math.cos(a) * 5 + tx * 5)), int(round(z - math.sin(a) * 5 + tz * 5)), *name)

def retaining(cx, cz, Rin):
    """Підпірні стінки на краях терас міста: обличчя з каменю, нагорі низька огорожа."""
    M = int(Rin); a0, b0 = cx - M, cz - M
    T_ = TOP[b0:b0 + 2 * M + 1, a0:a0 + 2 * M + 1]
    mn = np.minimum.reduce([np.roll(T_, 1, 0), np.roll(T_, -1, 0), np.roll(T_, 1, 1), np.roll(T_, -1, 1)])
    X, Z = np.meshgrid(np.arange(2 * M + 1) - M, np.arange(2 * M + 1) - M)
    edge = (T_ - mn >= 3) & (np.hypot(X, Z) < Rin - 2) & ~STREETMASK[b0:b0 + 2 * M + 1, a0:a0 + 2 * M + 1]
    for zz, xx in zip(*np.nonzero(edge)):
        x, z = a0 + xx, b0 + zz; col(x, z, int(mn[zz, xx]) - 1, int(T_[zz, xx]))
        if (x + z) % 2 == 0: setb(x, int(T_[zz, xx]) + 1, z, "stone_brick_wall")
def tower(cx, cz, r=3, hgt=16, mat="stone_bricks", roof=("deepslate_tile_stairs", "deepslate_tiles"), door=True):
    x0, z0, x1, z1 = cx - r, cz - r, cx + r, cz + r
    fy = platform(x0, z0, x1, z1, "cobblestone")
    hollow(x0, fy, z0, x1, fy + hgt, z1, mat)
    for y in range(fy + 3, fy + hgt, 5):
        setb(cx, y, z0, "air"); setb(cx, y, z1, "air"); setb(x0, y, cz, "air"); setb(x1, y, cz, "air")
    if door: fill(cx, fy, z1, cx, fy + 1, z1, "air")
    fill(x0 + 1, fy + hgt, z0 + 1, x1 - 1, fy + hgt, z1 - 1, "spruce_planks")
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1):
                setb(x, fy + hgt + 1, z, mat);
                if (x + z) % 2 == 0: setb(x, fy + hgt + 2, z, mat)
    fill(x0 - 1, fy + hgt, z0 - 1, x1 + 1, fy + hgt, z1 + 1, mat)
    if roof: pyramid_roof(x0, z0, x1, z1, fy + hgt + 3, roof)
    setb(cx, fy + hgt + 1, cz, "lantern")
    return fy
def lamp(x, z, tall=3):
    y = top_at(x, z) + 1
    fill(x, y, z, x, y + tall - 1, z, "spruce_fence"); setb(x, y + tall, z, "lantern")
def well(x, z):
    fy = platform(x - 2, z - 2, x + 2, z + 2, "cobblestone")
    hollow(x - 2, fy, z - 2, x + 2, fy, z + 2, "cobblestone"); fill(x - 1, fy - 6, z - 1, x + 1, fy - 1, z + 1, "water")
    for a, b in ((x - 2, z - 2), (x + 2, z - 2), (x - 2, z + 2), (x + 2, z + 2)):
        fill(a, fy + 1, b, a, fy + 3, b, "spruce_fence")
    gable_roof(x - 2, z - 2, x + 2, z + 2, fy + 4, ("spruce_stairs", "spruce_planks"))
def fountain(x, z):
    fy = platform(x - 4, z - 4, x + 4, z + 4, "stone_bricks")
    hollow(x - 4, fy, z - 4, x + 4, fy, z + 4, "stone_brick_wall"); fill(x - 3, fy - 1, z - 3, x + 3, fy - 1, z + 3, "water")
    fill(x, fy - 1, z, x, fy + 3, z, "chiseled_stone_bricks"); setb(x, fy + 4, z, "water")
    for a, b in ((x - 4, z - 4), (x + 4, z - 4), (x - 4, z + 4), (x + 4, z + 4)): setb(a, fy + 1, b, "lantern")
def stall(x, z, wool):
    y = top_at(x, z) + 1
    for a, b in ((x - 2, z - 1), (x + 2, z - 1), (x - 2, z + 1), (x + 2, z + 1)): fill(a, y, b, a, y + 2, b, "oak_fence")
    fill(x - 2, y + 3, z - 1, x + 2, y + 3, z + 1, f"{wool}_wool"); fill(x - 1, y, z - 1, x + 1, y, z - 1, "barrel")
    setb(x, y, z + 1, "composter")
def boulder(x, z):
    y = top_at(x, z); r = rng.uniform(1.2, 2.6)
    sphere(x, y + r * 0.4, z, r, rng.choice(("cobblestone", "mossy_cobblestone", "andesite", "stone")), ry=r * 0.8)
def fallen_log(x, z):
    y = top_at(x, z) + 1; n = rng.randint(4, 7); kind = rng.choice(("oak", "birch", "spruce"))
    if rng.random() < 0.5:
        fill(x, y, z, x + n, y, z, f"{kind}_log[axis=x]"); fill(x + 1, y + 1, z, x + n - 1, y + 1, z, "moss_carpet")
    else:
        fill(x, y, z, x, y, z + n, f"{kind}_log[axis=z]"); fill(x, y + 1, z + 1, x, y + 1, z + n - 1, "moss_carpet")
def tent(x, z, wool="white"):
    y = top_at(x, z) + 1
    for k in range(3):
        fill(x - 2 + k, y + k, z - 2, x - 2 + k, y + k, z + 2, f"{wool}_wool"); fill(x + 2 - k, y + k, z - 2, x + 2 - k, y + k, z + 2, f"{wool}_wool")
    fill(x - 1, y, z - 2, x + 1, y + 1, z + 2, "air"); setb(x, y + 2, z - 2, "air")
def campfire(x, z):
    y = top_at(x, z) + 1; setb(x, y, z, "campfire[signal_fire=true]")
    for dx, dz in ((2, 0), (-2, 0), (0, 2)): setb(x + dx, y, z + dz, "spruce_log[axis=x]" if dz == 0 else "spruce_log[axis=z]")

# ---------- вписування шаблонів у рельєф (нотатки 14) ----------
SPORUDY = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "sporudy.json"), encoding="utf-8"))
SLOTMASK = np.zeros((SIZE, SIZE), bool)     # зайнято спорудами: природа обходить
STREETMASK = np.zeros((SIZE, SIZE), bool)   # вулиці й площі міст
BE_EXTRA = {}                               # (x, y, z) -> додаткові поля даних блока (LootTable)
DIRS = ["north", "east", "south", "west"]

def fit_rect(x0, z0, x1, z1, level, blend=8):
    """Рельєф під прямокутником = level; навколо плавний перехід неправильної форми (як beard_thin у грі)."""
    level = max(int(level), SEA)
    pad = int(blend * 2.2) + 2
    a0, b0, a1, b1 = max(x0 - pad, 1), max(z0 - pad, 1), min(x1 + pad, SIZE - 2), min(z1 + pad, SIZE - 2)
    X, Z = np.meshgrid(np.arange(a0, a1 + 1), np.arange(b0, b1 + 1))
    d = np.hypot(np.maximum(np.maximum(x0 - X, X - x1), 0), np.maximum(np.maximum(z0 - Z, Z - z1), 0))
    ang = np.arctan2(Z - (z0 + z1) / 2, X - (x0 + x1) / 2)
    reg = TOP[b0:b1 + 1, a0:a1 + 1]
    w = blend * (1 + 0.35 * np.sin(ang * 3 + x0) + 0.2 * np.sin(ang * 7 + z0)) * np.clip(np.abs(reg - level) / 6, 0.7, 1.8)
    t = np.clip(d / np.maximum(w, 2), 0, 1); t = t * t * (3 - 2 * t)
    keep = np.isin(cat[b0:b1 + 1, a0:a1 + 1], [RIVERBED, SEABED, LAVA]) | (reg < SEA - 1) | STREETMASK[b0:b1 + 1, a0:a1 + 1]
    new = np.round(level + (reg - level) * t).astype(np.int32)
    TOP[b0:b1 + 1, a0:a1 + 1] = np.where(keep, reg, new)
    flat = ~keep & (t < 1) & (TOPB[b0:b1 + 1, a0:a1 + 1] != WATER)
    green = np.isin(cat[b0:b1 + 1, a0:a1 + 1], [GRASS, MEADOW, FORESTC, HIGH, BANK, FIELD, TOWN, PATH, STONE])
    TOPB[b0:b1 + 1, a0:a1 + 1] = np.where(flat & green, sid("grass_block"), TOPB[b0:b1 + 1, a0:a1 + 1])
    return level

def fit_circle(x, z, R, level, blend=14):
    return fit_rect(x - R // 2, z - R // 2, x + R // 2, z + R // 2, level, blend + R // 2)

def stamp_tpl(src, entry, x0, z0, k, base=None, fit=True, blend=8):
    """Ставить шаблон з поворотом k×90°: рельєф під ним вирівнюється, шар y=0 шаблону лягає на рівень землі."""
    t = stamp.load(src, entry); sx, sy, sz = t["size"]
    rsx, rsz = (sx, sz) if k % 2 == 0 else (sz, sx)
    if base is None: base = max(int(np.median(TOP[z0:z0 + rsz, x0:x0 + rsx])), SEA)
    if fit: fit_rect(x0, z0, x0 + rsx - 1, z0 + rsz - 1, base, blend)
    groups = {}
    for x, y, z, st, nbt in t["blocks"]:
        rx, rz = stamp.rot_pos(x, z, sx, sz, k)
        X, Y, Z = x0 + rx, base + y, z0 + rz
        g = groups.setdefault(stamp.rot_state(st, k), ([], [], []))
        g[0].append(X); g[1].append(Y); g[2].append(Z)
        if nbt and isinstance(nbt.get("LootTable"), str): BE_EXTRA[(X, Y, Z)] = {"LootTable": nbt["LootTable"]}
    for st, (xs, ys, zs) in groups.items(): put(xs, ys, zs, st)
    SLOTMASK[max(z0 - 3, 0):z0 + rsz + 3, max(x0 - 3, 0):x0 + rsx + 3] = True
    return base, rsx, rsz

def resample(pts, step=1.0):
    out = [pts[0]]
    for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
        n = max(1, int(math.hypot(x1 - x0, z1 - z0) / step))
        out += [(x0 + (x1 - x0) * k / n, z0 + (z1 - z0) * k / n) for k in range(1, n + 1)]
    return out

def paint_street(pts, mats, width=1):
    ids = [sid(m) for m in mats]
    for x, z in pts:
        for ox in range(-width, width + 1):
            for oz in range(-width, width + 1):
                px, pz = int(x) + ox, int(z) + oz
                if 0 <= px < SIZE and 0 <= pz < SIZE and is_land(px, pz) and cat[pz, px] != PATH and not STREETMASK[pz, px]:
                    STREETMASK[pz, px] = True; TOPB[pz, px] = rng.choice(ids)

# ---------- поселення: спершу вулиці, потім ділянки вздовж них (нотатки 15) ----------
def settle(cx, cz, rad, count, styles, center_free=16, sizes=((7, 7), (9, 7), (7, 9), (11, 9), (9, 9)), pool=None,
           street_mats=("dirt_path", "dirt_path", "coarse_dirt", "gravel"), gap=2, floors=None, jetty=0.0, trim=None, extra=(),
           ring=0.55, plaza=9, side_every=16, maxdiff=8, rmin=0, offs=(0, 3), lamp=0.0, use_roads=True):
    main = []
    for d in (ROADS if use_roads else ()):
        pts = [q for q in resample(list(world_path(d))) if rmin - 6 < math.hypot(q[0] - cx, q[1] - cz) < rad + 6]
        if len(pts) > 6: main.append(pts)
    for pts in extra: main.append(resample(list(pts)))
    side = []
    for pts in main:   # бічні вулиці від доріг, трохи вигнуті
        for i in range(8, len(pts) - 8, side_every):
            (ax_, az_), (bx_, bz_) = pts[i - 2], pts[i + 2]; L = math.hypot(bx_ - ax_, bz_ - az_) or 1
            tx, tz = (bx_ - ax_) / L, (bz_ - az_) / L
            for sgn in (1, -1):
                if rng.random() < 0.15: continue
                Ls, bend, line = rng.randint(20, 40), rng.uniform(-0.3, 0.3), []
                for st_ in range(3, Ls):
                    px = pts[i][0] - tz * sgn * st_ + tx * bend * st_ * st_ / Ls
                    pz = pts[i][1] + tx * sgn * st_ + tz * bend * st_ * st_ / Ls
                    if math.hypot(px - cx, pz - cz) > rad or not is_land(int(px), int(pz)): break
                    line.append((px, pz))
                if len(line) > 8: side.append(line)
    if ring:
        ring_ = [(cx + rad * ring * math.cos(a), cz + rad * ring * math.sin(a)) for a in np.linspace(0, 6.283, int(rad * 3.5))]
        side.append([q for q in ring_ if is_land(int(q[0]), int(q[1]))])   # кільцева вулиця
    for pts in side: paint_street(pts, street_mats, 1)
    if plaza: paint_street([(cx + ox, cz + oz) for ox in range(-plaza, plaza + 1) for oz in range(-plaza, plaza + 1) if ox * ox + oz * oz <= plaza * plaza],
                           ("stone_bricks", "cobblestone", "andesite", "polished_andesite"), 0)        # площа
    cand = []
    for is_main, group in ((True, main), (False, side)):
        for pts in group:
            for i in range(2, len(pts) - 2, 3):
                (ax_, az_), (bx_, bz_) = pts[i - 2], pts[i + 2]; L = math.hypot(bx_ - ax_, bz_ - az_) or 1
                nx, nz = -(bz_ - az_) / L, (bx_ - ax_) / L
                for sgn in (1, -1):
                    cand.append((math.hypot(pts[i][0] - cx, pts[i][1] - cz) + rng.uniform(0, 12), pts[i][0], pts[i][1], nx * sgn, nz * sgn, is_main))
    cand.sort(key=lambda c: c[0])
    rects, placed = [], 0
    for _, x, z, nx, nz, is_main, extra_ in [c + (e,) for c in cand for e in offs]:
        if placed >= count: break
        want = ("west" if nx > 0 else "east") if abs(nx) > abs(nz) else ("north" if nz > 0 else "south")   # двері до вулиці
        if pool:
            it = rng.choice(pool); t = stamp.load(it["source"], it["entry"]); sx, _, sz = t["size"]
            k = (DIRS.index(want) - DIRS.index(t["front"] or "south")) % 4
            w, d = (sx, sz) if k % 2 == 0 else (sz, sx)
        else:
            w, d = rng.choice(sizes); k = None
        depth = w if abs(nx) > abs(nz) else d
        off = depth / 2 + (4 if is_main else 3) + extra_
        px, pz = x + nx * off, z + nz * off
        x0, z0 = int(round(px - w / 2)), int(round(pz - d / 2)); x1, z1 = x0 + w - 1, z0 + d - 1
        if math.hypot(px - cx, pz - cz) > rad or math.hypot(px - cx, pz - cz) < max(center_free, rmin): continue
        if not (2 < x0 and x1 < SIZE - 3 and 2 < z0 and z1 < SIZE - 3): continue
        if any(not is_land(a, b) for a, b in ((x0, z0), (x1, z0), (x0, z1), (x1, z1), (int(px), int(pz)))): continue
        if STREETMASK[z0:z1 + 1, x0:x1 + 1].any() or (cat[z0:z1 + 1, x0:x1 + 1] == PATH).any() or (dRiver[z0:z1 + 1, x0:x1 + 1] < 7).any(): continue
        if any(x0 - gap - 1 <= b1 and a0 <= x1 + gap + 1 and z0 - gap - 1 <= c1 and c0 <= z1 + gap + 1 for a0, c0, b1, c1 in rects): continue
        if SLOTMASK[z0:z1 + 1, x0:x1 + 1].any() or (cat[z0:z1 + 1, x0:x1 + 1] == RIVERBED).any(): continue
        if reserved(int(px), int(pz)) and not is_main and rng.random() < 0.5: continue
        reg = TOP[z0:z1 + 1, x0:x1 + 1]
        if reg.max() - reg.min() > maxdiff: continue
        rects.append((x0, z0, x1, z1)); placed += 1
        if pool: stamp_tpl(it["source"], it["entry"], x0, z0, k, fit=True, blend=4)
        else: house(int(px), int(pz), w, d, rng.choice(styles), door_side=want, floors=rng.choice(floors) if floors else None,
                    jetty=is_main and rng.random() < jetty, trim=trim, lamp=is_main and rng.random() < lamp)
        # стежка від дверей до вулиці
        paint_street([(px - nx * (depth / 2 + s_), pz - nz * (depth / 2 + s_)) for s_ in range(1, int(off - depth / 2) + 1)], ("dirt_path",), 0)
    log(f"будинків: {placed} із {count}")
    return rects

def waystone(x, z, name):
    """Камінь переходу: точка швидкого переміщення хабу (відкривається плагіном при першому відвідуванні)."""
    y = top_at(x, z)
    fill(x - 1, y, z - 1, x + 1, y, z + 1, "stone_bricks"); setb(x, y + 1, z, "chiseled_stone_bricks"); setb(x, y + 2, z, "lodestone")
    for a, b in ((-1, -1), (1, 1)): setb(x + a, y + 1, z + b, "stone_brick_wall"); setb(x + a, y + 2, z + b, "soul_lantern")
    ANCHORS.append({"role": "waystone", "hub": name, "x": x, "y": y + 3, "z": z})
    sign(x + 2, z - 2, "Камінь переходу", name)
ANCHORS = []

POI = []  # (назва, x, z) для звіту й карти
def poi(name, x, z, *lines, sign_dx=6, sign_dz=6, r=14):
    POI.append((name, x, z)); reserve(x, z, r)
    sx_, sz_ = x + sign_dx, z + sign_dz
    if is_land(sx_, sz_): sign(sx_, sz_, *(lines or (name,)))

# v5: траса акведука від підніжжя вулкана до стартового міста (правка 14): спершу позначаємо, щоб будинки її обходили
AQ_SRC = (726, 590)   # v10: вода виходить зі схилу хребта, а не з повітря над озером (правка користувача)
AQ_WAY = [(734, 540), AQ_SRC, (716, 632), (703, 670), (690, 722), (690, 770), (688, 836), (640, 902), (592, 940), (545, 972)]   # v7: від озера-джерела
_ae = math.atan2(AQ_WAY[-1][1] - TZ5, AQ_WAY[-1][0] - TX5)
AQ_END = (int(round(TX5 + (START_R + 1) * math.cos(_ae))), int(round(TZ5 + (START_R + 1) * math.sin(_ae))))
AQ = resample(list(world_path(cr_path(AQ_WAY + [AQ_END]))), 0.55)   # густо, щоб канал був суцільним
AQ_MASK = np.zeros((SIZE, SIZE), bool)
for x_, z_ in AQ[::2]: AQ_MASK[max(int(z_) - 6, 0):int(z_) + 7, max(int(x_) - 6, 0):int(x_) + 7] = True
SLOTMASK |= AQ_MASK
log("будую міста-фортеці")
def find_lot(x, z, w, d, r=30, maxdiff=3):
    """Вільна рівна ділянка w×d біля (x, z): без вулиць, споруд і води."""
    for k in range(500):
        rr = r * (k / 500) ** 0.5; a = rng.uniform(0, 6.283)
        px, pz = int(x + rr * math.cos(a)), int(z + rr * math.sin(a))
        x0, z0 = px - w // 2 - 1, pz - d // 2 - 1; x1, z1 = x0 + w + 1, z0 + d + 1
        if not (2 < x0 and x1 < SIZE - 3 and 2 < z0 and z1 < SIZE - 3): continue
        reg = TOP[z0:z1 + 1, x0:x1 + 1]
        if STREETMASK[z0:z1 + 1, x0:x1 + 1].any() or SLOTMASK[z0:z1 + 1, x0:x1 + 1].any() or (cat[z0:z1 + 1, x0:x1 + 1] == RIVERBED).any(): continue
        if reg.max() - reg.min() > maxdiff or not all(is_land(a_, b_) for a_, b_ in ((x0, z0), (x1, z0), (x0, z1), (x1, z1))): continue
        return px, pz
    return None
def block_lot(x, z, w, d, pad=2):
    SLOTMASK[max(z - d // 2 - pad, 0):z + d // 2 + pad + 1, max(x - w // 2 - pad, 0):x + w // 2 + pad + 1] = True
def disc_pts(cx, cz, r):
    return [(cx + ox, cz + oz) for ox in range(-r, r + 1) for oz in range(-r, r + 1) if ox * ox + oz * oz <= r * r]
def pavilion(x, z, r=5, roof=("deepslate_tile_stairs", "deepslate_tiles"), flag_c="red"):
    """Круглий павільйон на площі: 8 колон, кам'яний пояс, конічний дах із прапором, чаша з водою посередині."""
    fy = platform(x - r - 1, z - r - 1, x + r + 1, z + r + 1, "stone_bricks")
    disc(x, fy - 1, z, r + 1.3, "polished_andesite"); disc(x, fy - 1, z, 2.3, "water")
    for k in range(16):
        a = k / 16 * 6.283; setb(int(round(x + 2.9 * math.cos(a))), fy, int(round(z + 2.9 * math.sin(a))), "stone_brick_slab")
    fill(x, fy - 1, z, x, fy + 1, z, "chiseled_stone_bricks"); setb(x, fy + 2, z, "lantern")
    for k in range(8):
        a = k / 8 * 6.283 + 0.39; px, pz = int(round(x + r * math.cos(a))), int(round(z + r * math.sin(a)))
        fill(px, fy, pz, px, fy + 4, pz, "stripped_dark_oak_log"); setb(px, fy + 5, pz, "chiseled_stone_bricks")
    R_ = r + 1; X_, Z_ = np.mgrid[-R_:R_ + 1, -R_:R_ + 1]; D_ = np.hypot(X_, Z_)
    for a_, b_ in zip(X_[(D_ > r - 0.8) & (D_ <= r + 0.6)], Z_[(D_ > r - 0.8) & (D_ <= r + 0.6)]): setb(x + a_, fy + 5, z + b_, "stone_bricks")
    top_ = cone_roof(x, z, fy + 6, r + 1.8, roof)
    flag(x, top_, z, flag_c)
    return fy
def crystal_lamp(x, z):
    """Світильник-орієнтир посеред ринку: аметистовий кристал на постаменті (як у Detlas)."""
    g = top_at(x, z); fill(x - 1, g, z - 1, x + 1, g + 1, z + 1, "polished_blackstone_bricks"); setb(x, g + 2, z, "chiseled_polished_blackstone")
    for dy, pl in ((3, ((0, 0),)), (4, ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))), (5, ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))), (6, ((0, 0),))):
        for a, b in pl: setb(x + a, g + dy, z + b, "amethyst_block")
    setb(x, g + 7, z, "amethyst_cluster[facing=up]"); setb(x, g + 8, z, "light[level=15]")
    for a, b in ((-1, -1), (1, 1), (-1, 1), (1, -1)): setb(x + a, g + 2, z + b, "amethyst_cluster[facing=up]")
def clock_tower(x, z, r=4, hgt=36):
    """Вежа-годинник Узлісся: найвища в місті, циферблати на чотирьох боках, помаранчевий шпиль, зелений прапор."""
    fy = square_tower(x, z, r, hgt, ("acacia_stairs", "acacia_planks"), "green")
    cy = fy + hgt - 5
    for side in ("north", "south", "east", "west"):
        dx, dz = DV[side]
        for u in range(-3, 4):
            for v in range(-3, 4):
                if u * u + v * v > 10: continue
                px, pz = (x + dx * (r + 1), z + u) if dx else (x + u, z + dz * (r + 1))
                setb(px, cy + v, pz, "black_concrete" if (u == 0 and 0 <= v <= 2) or (v == 0 and 0 <= u <= 1) else ("gold_block" if u * u + v * v > 7 else "white_concrete"))
    return fy
def stalls_ring(cx, cz, r, n, a0=0.0, avoid=()):
    out = []
    for i in range(n):
        a = a0 + i / n * 6.283; x, z = int(cx + r * math.cos(a)), int(cz + r * math.sin(a))
        if dRoad[z, x] < 5 or any(path_info(np.array([x + 0.5]), np.array([z + 0.5]), p)[0][0] < 5 for p in avoid): continue
        stall(x, z, ("red", "yellow", "blue", "green", "orange", "purple", "white", "lime")[i % 8]); out.append((x, z))
    return out

# ---------- Стартове місто: кам'яна фортеця за зразком Ragni (правка 15; нотатки 17, розд. 10): тема «камінь і прапори» ----------
TX, TZ = TX5, TZ5
STYLE_START = [("white_terracotta", "stripped_dark_oak_log", "spruce_planks", ("deepslate_tile_stairs", "deepslate_tiles")),
               ("stone_bricks", "stripped_spruce_log", "spruce_planks", ("deepslate_tile_stairs", "deepslate_tiles")),
               ("calcite", "dark_oak_log", "oak_planks", ("dark_oak_stairs", "dark_oak_planks")),
               ("polished_andesite", "stripped_dark_oak_log", "spruce_planks", ("deepslate_tile_stairs", "deepslate_tiles")),
               ("white_terracotta", "spruce_log", "spruce_planks", ("brick_stairs", "bricks"))]
RW_S, PH_S = 0.035, 0.7
ring_s = ring_pts(TX, TZ, START_R, RW_S, PH_S)
a_castle = math.atan2(CAZ - TZ, CAX - TX)
keep_moat = lambda a: abs((a - a_castle + math.pi) % (2 * math.pi) - math.pi) > math.radians(62)
moat(TX, TZ, START_R, RW_S, PH_S, 6, 12, keep_moat)                      # рів із трьох боків
CASTLE_ST = [(TX, TZ), (CAX + 13, CAZ - 13)]                             # головна вулиця: площа → брама замку
city_streets(TX, TZ, START_R - 3, extra=[CASTLE_ST])
paint_street(disc_pts(TX, TZ, 13), PAVE, 0)                              # кругла площа
paint_street([(TX + (START_R - 8) * math.cos(a), TZ + (START_R - 8) * math.sin(a)) for a in np.linspace(0, 6.283, 700)], PAVE[:4], 1)
retaining(TX, TZ, START_R - 3)
# замок на найвищому краю: внутрішній мур на краю пагорба, зала з Т-дахом, кутові башточки, донжон
SLOTMASK[np.hypot(wx - CAX, wz - CAZ) < 25] = True
cg = top_at(CAX, CAZ)
ring_k = ring_pts(CAX, CAZ, 20, 0.02, 0.3)
gi_k = int(np.argmin([abs((a - (a_castle + math.pi) + math.pi) % (2 * math.pi) - math.pi) for _, _, a in ring_k]))
city_wall(CAX, CAZ, ring_k, hgt=8, thick=3, gates=[gi_k], tower_every=32, tower_r=3.5, flag_c="red")
HX_, HZ_ = CAX + 3, CAZ - 3
CASTLE_STYLE = ("stone_bricks", "stripped_dark_oak_log", "dark_oak_planks", ("deepslate_tile_stairs", "deepslate_tiles"))
house(HX_, HZ_, 13, 19, CASTLE_STYLE, floors=3, door_side="east", trim=TRIM, pitch=2, cross=True, chimney=True)
for a, b in ((-7, -10), (7, -10), (-7, 10), (7, 10)): round_tower(HX_ + a, HZ_ + b, 2.6, 17, flag_c=rng.choice(("red", "yellow")), base=cg)
round_tower(CAX - 13, CAZ + 4, 5.5, 34, flag_c="red", base=cg)      # донжон: видно з усього острова
for v in (-4, 0, 4):   # прапори на фасаді зали
    for yy in range(cg + 5, cg + 10): setb(HX_ + 7, yy, HZ_ + v, "red_wool" if v else "yellow_wool")
POI.append(("Замок старту", CAX, CAZ))
# кругла площа з павільйоном, ятки й дерева по краях
pavilion(TX, TZ)
stalls_ring(TX, TZ, 10, 6, 0.0, avoid=[CASTLE_ST])
# будинки впритул рядами вздовж головних вулиць, 2–3 поверхи, верхні поверхи нависають над вулицею
settle(TX, TZ, START_R - 12, 44, STYLE_START, center_free=17, sizes=((7, 9), (9, 9), (7, 11), (9, 11), (11, 9), (5, 9)),
       street_mats=PAVE[:4], gap=0, floors=(2, 2, 3), jetty=0.55, trim=TRIM, extra=[CASTLE_ST], ring=0.66, plaza=0, side_every=26,
       maxdiff=3, offs=(1, 2), lamp=0.4)
gates_s = ring_gates(ring_s)
city_wall(TX, TZ, ring_s, hgt=14, gates=gates_s, tower_every=40, flag_c="red", name=("Стартове місто", "Lv 1-5"), moat_out=12)
log(f"старт: брам {len(gates_s)}")
reserve(TX, TZ, START_R + 14)
POI.append(("Стартове місто", TX, TZ)); sign(TX + 6, TZ + 12, "Стартове місто", "Lv 1", "острів 1")
SPAWN = (TX, top_at(TX, TZ + 9) + 1, TZ + 9)

# ---------- v9: Узлісся — компактний ринок перед брамою, без старого міста-фортеці ----------
VX, VZ = ELF_MARKET
paint_street([(VX + ox, VZ + oz) for ox in range(-17, 18) for oz in range(-12, 13)],
             ("dirt_path", "birch_planks", "mossy_cobblestone"), 0)
for ox, oz, wool in ((-11, -9, "green"), (0, -9, "white"), (11, -9, "lime"),
                     (-11, 9, "white"), (0, 9, "green"), (11, 9, "lime")):
    stall(VX + ox, VZ + oz, wool)
reserve(VX, VZ, 25)
POI.append(("Узлісся", VX, VZ)); sign(VX - 18, VZ + 14, "Узлісся", "ринок біля брами", "Lv 5-10")

def forest_gate(x, z):
    """Поверхневий прототип: розгалужені дерев'яні опори поперек алеї на схід."""
    g = top_at(x, z) + 1
    for side in (-1, 1):
        limb(bezier3((x, g, z + side * 6), (x, g + 7, z + side * 6),
                     (x, g + 12, z + side * 3), (x, g + 14, z), 25), 1.6, 0.65, "stripped_birch_wood")
        setb(x - 2, g + 3, z + side * 6, "lantern")
    reserve(x, z, 10)

forest_gate(*ELF_GATE)
POI.append(("Брама ельфійського лісу", *ELF_GATE))
sign(ELF_GATE[0] - 5, ELF_GATE[1] + 10, "Ельфійський ліс", "до серця під кронами", "Lv 22-28")
paint_street(resample([ELF_MARKET, ELF_GATE, (798, 908), (ELF_POND[0] - 20, ELF_POND[1])]),
             ("dirt_path", "mossy_cobblestone", "birch_planks"), 2)
# Ставок у вікні крон перед Великим Деревом.
_px, _pz = ELF_POND
_pool = ((wx - _px) / 15) ** 2 + ((wz - _pz) / 9) ** 2 < 1
_pool_y = top_at(_px, _pz) - 1
TOP[_pool] = _pool_y - 2; WL[_pool] = _pool_y; POND |= _pool
cat[_pool] = RIVERBED; TOPB[_pool] = sid("clay"); SUBB[_pool] = sid("gravel")
reserve(_px, _pz, 23); POI.append(("Ставок-сад", _px, _pz))
for qx in range(758, 828, 18):
    for qz in (900, 920): lamp(qx, qz); reserve(qx, qz, 3)
log("v9: ринок Узлісся, брама, алея й ставок-сад")

# ---------- Рибальське село: частокіл, кам'яна вежа-шпиль у центрі (Elkurn), причали, сіті й дим ----------
FVX, FVZ = S(172, 478)
lot = find_lot(FVX - 8, FVZ + 6, 7, 7, r=12, maxdiff=4)
if lot: block_lot(*lot, 7, 7); round_tower(lot[0], lot[1], 3, 26, roof=("dark_oak_stairs", "dark_oak_planks"), flag_c="cyan"); POI.append(("Вежа-шпиль", *lot))
settle(FVX, FVZ, 36, 10, [("spruce_planks", "spruce_log", "spruce_planks", ("spruce_stairs", "spruce_planks")), STYLES[1]], center_free=8, sizes=((7, 7), (7, 9)),
       floors=(1, 1, 2), trim=None)
for k in range(4):  # причали в річку й море
    x0 = FVX - 30 + k * 6
    fill(x0, SEA, FVZ - 4, x0 + 1, SEA, FVZ + 10, "spruce_planks"); fill(x0, SEA - 6, FVZ + 10, x0, SEA - 1, FVZ + 10, "spruce_log")
for k in range(5): setb(FVX - 28 + k * 5, SEA + 1, FVZ + 4, "barrel")
for k in range(3):   # рами із сітями
    nx_, nz_ = FVX - 20 + k * 6, FVZ - 12; g_ = top_at(nx_, nz_)
    if not is_land(nx_, nz_): continue
    fill(nx_, g_ + 1, nz_, nx_, g_ + 3, nz_, "spruce_fence"); fill(nx_ + 4, g_ + 1, nz_, nx_ + 4, g_ + 3, nz_, "spruce_fence")
    fill(nx_ + 1, g_ + 3, nz_, nx_ + 3, g_ + 3, nz_, "spruce_fence[east=true,west=true]"); fill(nx_ + 1, g_ + 1, nz_, nx_ + 3, g_ + 2, nz_, "cobweb")
    setb(nx_ + 2, g_ + 1, nz_ + 2, "campfire[lit=true]")
palisade(FVX, FVZ, 42, ("Рибальське село", "Lv 5"))
reserve(FVX, FVZ, 48)
POI.append(("Рибальське село", FVX, FVZ)); sign(FVX + 5, FVZ - 6, "Рибальське село", "Lv 5")

# ---------- Порт v6: відкрите місто терасами до води (план 16, розд. 17; кадри 41–44 гри, 15 відео): кам'яна набережна з ґратами,
# дощатий бульвар, широкий пірс із бортами з колод, причали на палях, крани, висока кам'яна вежа; мурів немає ----------
def gothic_tower(cx, cz, w=4, hgt=46, mats=("stone_bricks", "cracked_stone_bricks", "stone_bricks", "andesite"), trim="polished_andesite",
                 roof=("deepslate_tile_stairs", "deepslate_tiles"), win="glass", flag_c=None, wall_cap="stone_brick_wall"):
    """Висока готична вежа: три яруси, що звужуються, контрфорси з пінаклями на кутах, карнизи, високі вузькі вікна, гострий шпиль."""
    fy = platform(cx - w - 2, cz - w - 2, cx + w + 2, cz + w + 2, "cobblestone")
    y = fy
    for r, h_ in ((w, int(hgt * 0.45)), (w - 1, int(hgt * 0.3)), (w - 2, hgt - int(hgt * 0.45) - int(hgt * 0.3))):
        for x in range(cx - r, cx + r + 1):
            for z in range(cz - r, cz + r + 1):
                if max(abs(x - cx), abs(z - cz)) == r: fill(x, y, z, x, y + h_ - 1, z, rng.choice(mats))
                else: fill(x, y, z, x, y + h_ - 2, z, "air"); setb(x, y + h_ - 1, z, "spruce_planks")
        for sx_ in (-1, 1):
            for sz_ in (-1, 1):
                px, pz = cx + sx_ * (r + 1), cz + sz_ * (r + 1)
                fill(px, y, pz, px, y + h_ - 2, pz, trim); fill(px, y + h_ - 1, pz, px, y + h_ + 1, pz, wall_cap); setb(px, y + h_ + 2, pz, "lantern")
        for k in range(-r, r + 1):
            for px, pz in ((cx + k, cz - r - 1), (cx + k, cz + r + 1), (cx - r - 1, cz + k), (cx + r + 1, cz + k)): setb(px, y + h_ - 1, pz, trim)
        for dx_, dz_ in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            for o_ in ((0,) if r < 3 else (-2, 0, 2)):
                px, pz = (cx + o_, cz + dz_ * r) if dx_ == 0 else (cx + dx_ * r, cz + o_)
                fill(px, y + 3, pz, px, y + h_ - 4, pz, win)
        y += h_
    stairs, solid = roof; R = w - 1; k = 0
    while True:
        a, b, c, d = cx - R + k, cx + R - k, cz - R + k, cz + R - k
        if a >= b: fill(a, y, c, a, y + 3, c, solid); y += 4; break
        for yy in range(y, y + 3):
            st = yy == y + 2
            for x in range(a, b + 1):
                setb(x, yy, c, f"{stairs}[facing=south]" if st else solid); setb(x, yy, d, f"{stairs}[facing=north]" if st else solid)
            for z in range(c + 1, d):
                setb(a, yy, z, f"{stairs}[facing=east]" if st else solid); setb(b, yy, z, f"{stairs}[facing=west]" if st else solid)
        y += 3; k += 1
    if flag_c: flag(cx, y, cz, flag_c, tall=4)
    return fy, y

def build_port(PX, PZ, name, lines, hub, roads=True):
    """Порт терасами до води (вода на півночі в кадрі функції)."""
    QZ = PZ - 48
    STYLE_PORT = [("dark_oak_planks", "dark_oak_log", "spruce_planks", ("dark_oak_stairs", "dark_oak_planks")),
                  ("spruce_planks", "dark_oak_log", "spruce_planks", ("dark_oak_stairs", "dark_oak_planks")),
                  ("mud_bricks", "dark_oak_log", "spruce_planks", ("spruce_stairs", "spruce_planks")),
                  ("dark_oak_planks", "stripped_spruce_log", "dark_oak_planks", ("spruce_stairs", "spruce_planks")),
                  ("stone_bricks", "dark_oak_log", "spruce_planks", ("dark_oak_stairs", "dark_oak_planks"))]
    # гавань: від набережної на північ вода (басейн у березі)
    for x in range(PX - 72, PX + 73):
        for z in range(QZ - 48, QZ):
            if TOP[z, x] > SEA - 6: TOP[z, x] = SEA - 6 - (x * 7 + z) % 2
            WL[z, x] = SEA - 1; cat[z, x] = SEABED; TOPB[z, x] = sid("gravel") if (x + z) % 3 else sid("sand")
    # кам'яна набережна-стінка з ґратами й дощатий бульвар із бочками, ящиками, сіном
    PIERS = (-50, -25, 0, 25, 50)
    for x in range(PX - 72, PX + 73):
        if TOP[QZ + 2, x] < SEA: continue
        col(x, QZ, SEA - 8, SEA + 1); setb(x, SEA + 2, QZ, "stone_bricks")
        TOP[QZ, x] = SEA + 2; TOPB[QZ, x] = sid("stone_bricks"); cat[QZ, x] = PATH; STREETMASK[QZ, x] = True
        if all(abs(x - (PX + d)) > (3 if d == 0 else 1) for d in PIERS): setb(x, SEA + 3, QZ, "iron_bars[east=true,west=true]")
        for z in range(QZ + 1, QZ + 6):
            TOP[z, x] = SEA + 2; cat[z, x] = PATH; STREETMASK[z, x] = True
            TOPB[z, x] = sid(rng.choice(("spruce_planks", "dark_oak_planks", "spruce_planks", "oak_planks")))
        if x % 4 == 0 and rng.random() < 0.6: setb(x, SEA + 3, QZ + 5, rng.choice(("barrel", "barrel", "hay_block", "chest", "composter")))
    def pier(xc, half, z_end, main=False):
        for z in range(z_end, QZ):
            for x in range(xc - half, xc + half + 1):
                setb(x, SEA + 2, z, rng.choice(("spruce_planks", "dark_oak_planks", "spruce_planks"))); fill(x, SEA + 3, z, x, SEA + 5, z, "air")
            if (QZ - z) % 5 == 0:
                for x in (xc - half, xc + half): fill(x, int(TOP[z, x]) + 1, z, x, SEA + 1, z, "spruce_log")   # палі
            for x in (xc - half - 1, xc + half + 1):
                if main and (QZ - z) % 9 != 4: setb(x - (1 if x > xc else -1), SEA + 3, z, "stripped_dark_oak_log[axis=z]")   # борти з колод
                elif (QZ - z) % 9 == 4 or (not main and (QZ - z) % 6 == 0):
                    fill(x, SEA, z, x, SEA + 3, z, "spruce_log")                                                       # тумби
                    if main: setb(x, SEA + 4, z, "lantern")
        if not main:   # Т-подібна голова причалу
            for z in range(z_end, z_end + 3):
                for x in range(xc - 6, xc + 7): setb(x, SEA + 2, z, "spruce_planks")
            for x in (xc - 6, xc + 6): fill(x, int(TOP[z_end, x]) + 1, z_end, x, SEA + 1, z_end, "spruce_log"); setb(x, SEA + 3, z_end, "spruce_log")
    pier(PX, 3, QZ - 62, main=True)
    for d in PIERS:
        if d: pier(PX + d, 1, QZ - 34)
    def crane(x, z):   # дерев'яний кран-«журавель» на набережній
        fill(x, SEA + 3, z, x, SEA + 13, z, "dark_oak_log"); fill(x - 1, SEA + 3, z, x + 1, SEA + 3, z, "dark_oak_log[axis=x]")
        fill(x, SEA + 13, z - 7, x, SEA + 13, z - 1, "spruce_fence[north=true,south=true]"); setb(x, SEA + 12, z - 1, "dark_oak_stairs[facing=south,half=top]")
        fill(x, SEA + 9, z - 7, x, SEA + 12, z - 7, "chain[axis=y]"); setb(x, SEA + 8, z - 7, rng.choice(("barrel", "hay_block", "chest")))
    for d in (-13, 14, -38, 38): crane(PX + d, QZ + 1)
    # тераси: вулиці вздовж кожної тераси й три пандуси до набережної
    TIER_Z = [QZ + 12, PZ - 20, PZ - 6, PZ + 11, PZ + 28, PZ + 45]   # вулиці вздовж північного краю терас (над уступом), будинки за ними
    PORT_LINES = [[(PX - 64, zt), (PX + 64, zt)] for zt in TIER_Z] + [[(PX + d, QZ + 5), (PX + d, PZ + 62)] for d in (-45, 0, 45)]
    city_streets(PX, PZ, 74)
    for pts_ in PORT_LINES: paint_street(resample(pts_), PAVE[:5], 1)
    paint_street(disc_pts(PX, QZ + 13, 7), PAVE, 0)                            # ринок біля пірса
    stalls_ring(PX, QZ + 13, 5, 4, 0.4)
    # висока кам'яна вежа над містом — орієнтир із моря
    lot = find_lot(PX - 40, PZ + 46, 11, 11, r=16, maxdiff=4)
    if lot: block_lot(*lot, 11, 11); gothic_tower(lot[0], lot[1], w=4, hgt=44, flag_c="blue"); POI.append(("Вежа: " + name, *lot))
    global MOSS_P
    MOSS_P = 0.3   # дахи обросли мохом і листям
    settle(PX, PZ + 10, 70, 40, STYLE_PORT, center_free=0, sizes=((7, 9), (7, 7), (9, 9), (5, 7), (9, 7), (7, 11)), street_mats=PAVE[:4],
           gap=0, floors=(1, 2, 2, 3), jetty=0.35, trim=None, extra=PORT_LINES, ring=0, plaza=0, side_every=999, maxdiff=2, offs=(0, 1), lamp=0.3, use_roads=roads)
    MOSS_P = 0.0
    # мол із маяком-вежею
    MLX = PX - 70
    for z in range(PZ - 100, QZ + 1):
        for x in range(MLX - 2, MLX + 3):
            if TOP[z, x] <= SEA: fill(x, TOP[z, x], z, x, SEA + 2, z, "stone_bricks" if abs(x - MLX) < 2 else rng.choice(("cobblestone", "mossy_cobblestone", "stone")))
    lt = round_tower(MLX, PZ - 104, 4.5, 26, roof=None, flag_c=None, base=SEA + 2)
    disc(MLX, lt + 1, PZ - 104, 3.4, "glass"); disc(MLX, lt + 1, PZ - 104, 2.4, "air"); disc(MLX, lt + 2, PZ - 104, 3.4, "glass"); disc(MLX, lt + 2, PZ - 104, 2.4, "air")
    fill(MLX - 1, lt + 1, PZ - 105, MLX + 1, lt + 2, PZ - 103, "sea_lantern"); disc(MLX, lt + 3, PZ - 104, 3.6, "stone_bricks")
    cone_roof(MLX, PZ - 104, lt + 4, 4.4, ("deepslate_tile_stairs", "deepslate_tiles"))
    POI.append(("Маяк: " + name, MLX, PZ - 104))
    def ship(x, z):  # корабель уздовж осі z
        for k in range(0, 4):
            w = 3 - (1 if k == 0 else 0)
            fill(x - w, SEA - 3 + k, z - 14 + (3 - k), x + w, SEA - 3 + k, z + 14 - (3 - k), "dark_oak_planks")
        fill(x - 2, SEA + 1, z - 12, x + 2, SEA + 1, z + 12, "spruce_planks")
        hollow(x - 3, SEA + 2, z - 13, x + 3, SEA + 2, z + 13, "spruce_fence")
        fill(x, SEA + 2, z - 2, x, SEA + 22, z - 2, "spruce_log"); fill(x, SEA + 2, z + 6, x, SEA + 16, z + 6, "spruce_log")
        fill(x - 6, SEA + 10, z - 2, x + 6, SEA + 20, z - 2, "white_wool"); fill(x - 5, SEA + 8, z + 6, x + 5, SEA + 15, z + 6, "white_wool")
        fill(x - 2, SEA + 2, z + 9, x + 2, SEA + 4, z + 13, "dark_oak_planks"); setb(x, SEA + 23, z - 2, "blue_wool")
    ship(PX + 12, PZ - 72); ship(PX - 12, PZ - 98); ship(PX + 37, PZ - 66)
    reserve(PX, PZ, 82)
    POI.append((name, PX, PZ)); sign(PX + 6, QZ + 7, *lines)
    ANCHORS.append({"role": "captain", "hub": hub, "x": PX, "y": SEA + 3, "z": QZ - 56})

def build_mirrored(fn, *args, **kw):
    """Будує fn у дзеркальному кадрі (x <-> z): усі масиви карти транспонуються, put() міняє x і z та стани блоків."""
    global TRANSPOSE, wx, wz
    names = ["TOP", "WL", "cat", "TOPB", "STREETMASK", "SLOTMASK", "RES_MASK", "dRoad", "dRiver", "river", "tL", "noise_col", "POND"]
    saved = {n: globals()[n] for n in names}; sw = (wx, wz)
    n_sign, n_poi, n_anc = len(SIGNS), len(POI), len(ANCHORS)
    for n in names: globals()[n] = saved[n].T
    wx, wz = sw[1].T, sw[0].T; TRANSPOSE = True
    try: fn(*args, **kw)
    finally:
        TRANSPOSE = False
        for n in names: globals()[n] = saved[n]
        wx, wz = sw
    for k in range(n_sign, len(SIGNS)): x_, y_, z_, l_ = SIGNS[k]; SIGNS[k] = (z_, y_, x_, l_)
    for k in range(n_poi, len(POI)): nm_, x_, z_ = POI[k]; POI[k] = (nm_, z_, x_)
    for k in range(n_anc, len(ANCHORS)): ANCHORS[k]["x"], ANCHORS[k]["z"] = ANCHORS[k]["z"], ANCHORS[k]["x"]

PX, PZ = S(410, 151)
build_port(PX, PZ, "Порт", ("Порт", "кораблі на острів 2", "Lv 15-20"), "port")
QZ = PZ - 48
WPX, WPZ = S(167, 580)
def west_port(PX, PZ):
    """v10: Західний порт — рибальська гавань, не копія північного (правка користувача): природний берег без басейну,
    кам'яна набережна вздовж лінії берега, три дерев'яні пірси з човнами, сушарки для сітей, рибний ринок,
    білі будинки з цегляними й глиняними дахами."""
    shore = {}
    for z in range(PZ - 70, PZ + 71):
        for x in range(PX - 140, PX + 80):
            if WL[z, x] <= TOP[z, x] and TOP[z, x] >= SEA - 1: shore[z] = x; break
    PIER_Z = (PZ - 34, PZ + 2, PZ + 38)
    for z, xs in shore.items():                                   # набережна: стінка в воду й мощений прохід
        col(xs - 1, z, SEA - 7, SEA + 1)
        for x in range(xs - 1, xs + 4):
            TOP[z, x] = SEA + 1; WL[z, x] = -999; cat[z, x] = PATH; STREETMASK[z, x] = True
            TOPB[z, x] = sid("stone_bricks" if x == xs - 1 else rng.choice(("cobblestone", "andesite", "stone_bricks", "gravel")))
        if all(abs(z - pz_) > 2 for pz_ in PIER_Z): setb(xs - 1, SEA + 2, z, "stone_brick_wall" if z % 6 else "lantern")
        if z % 7 == 0 and rng.random() < 0.6: setb(xs + 3, SEA + 2, z, rng.choice(("barrel", "barrel", "hay_block", "composter")))
    for k, pz_ in enumerate(PIER_Z):                              # пірси на палях; середній — довгий, з Т-краєм
        if pz_ not in shore: continue
        xs = shore[pz_]; L = 30 if k == 1 else 20
        for x in range(xs - 1 - L, xs - 1):
            for z in range(pz_ - 1, pz_ + 2): setb(x, SEA + 1, z, rng.choice(("spruce_planks", "oak_planks", "spruce_planks")))
            if x % 4 == 0:
                for z in (pz_ - 2, pz_ + 2): fill(x, top_at(x, z), z, x, SEA + 2, z, "spruce_log"); setb(x, SEA + 3, z, "lantern" if x % 8 == 0 else "spruce_fence")
        xe = xs - 1 - L
        if k == 1:
            fill(xe - 2, SEA + 1, pz_ - 6, xe, SEA + 1, pz_ + 6, "spruce_planks")
            ANCHORS.append({"role": "captain", "hub": "port_w", "x": xe, "y": SEA + 2, "z": pz_})
        for j, dz in enumerate((-5, 4)):                          # човни біля пірса
            bx, bz = xs - 6 - L // 2 - j * 5, pz_ + dz
            fill(bx, SEA - 1, bz, bx + 4, SEA - 1, bz + 1, "spruce_planks"); fill(bx + 1, SEA, bz, bx + 3, SEA, bz + 1, "air")
            setb(bx, SEA, bz, "spruce_slab"); setb(bx + 4, SEA, bz + 1, "spruce_slab"); setb(bx + 2, SEA, bz, "barrel")
    for k in range(6):                                            # сушарки для сітей на березі
        z = PZ - 56 + k * 22
        if z not in shore: continue
        x = shore[z] + 6; g = top_at(x, z)
        for dz in (0, 4): fill(x, g + 1, z + dz, x, g + 3, z + dz, "spruce_fence")
        fill(x, g + 4, z, x, g + 4, z + 4, "spruce_slab"); fill(x, g + 3, z + 1, x, g + 3, z + 3, "white_carpet" if k % 2 else "cobweb")
    mz = PZ + 18                                                  # рибний ринок біля набережної
    if mz in shore:
        for j, wool in enumerate(("light_blue", "white", "blue", "white")): stall(shore[mz] + 9, mz - 12 + j * 7, wool)
    STYLE_W = [("white_terracotta", "stripped_oak_log", "oak_planks", ("brick_stairs", "bricks")),
               ("calcite", "spruce_log", "oak_planks", ("mud_brick_stairs", "mud_bricks")),
               ("smooth_sandstone", "stripped_birch_log", "birch_planks", ("brick_stairs", "bricks")),
               ("white_terracotta", "dark_oak_log", "spruce_planks", ("polished_granite_stairs", "polished_granite"))]
    cx_ = (shore.get(PZ, PX) + PX) // 2 + 34
    settle(cx_, PZ, 62, 28, STYLE_W, center_free=10, sizes=((7, 7), (7, 9), (9, 7), (5, 7), (9, 9)),
           street_mats=("gravel", "cobblestone", "sand", "andesite"), floors=(1, 1, 2), lamp=0.4, maxdiff=4)
    reserve(PX, PZ, 60)
    POI.append(("Західний порт", shore.get(PZ, PX) + 4, PZ)); sign(shore.get(PZ, PX) + 5, PZ + 5, "Західний порт", "рибальська гавань", "Lv 5-10")
    log(f"західний порт: берег {len(shore)} рядків, пірсів {sum(p_ in shore for p_ in PIER_Z)}, центр міста ({cx_}, {PZ})")
west_port(WPX, WPZ)
log("порти: північний і західний")

# ---------- Морське кладовище з готичною вежею на мисі біля порту (рішення користувача, кадри 15 і 18 відео) ----------
best_ = None
for x in range(PX + 85, PX + 240, 3):
    for z in range(PZ - 120, PZ + 50, 3):
        if not is_land(x, z) or reserved(x, z) or not (0.86 < tL[z, x] < 0.97): continue
        reg = TOP[z - 12:z + 13, x - 12:x + 13]
        if reg.min() < SEA - 1 or reg.max() - reg.min() > 7: continue
        dd = math.hypot(x - (PX + 140), z - (PZ - 50))
        if best_ is None or dd < best_[0]: best_ = (dd, x, z)
if best_:
    _, CMX, CMZ = best_
    lvl = int(np.median(TOP[CMZ - 14:CMZ + 15, CMX - 14:CMX + 15])); lvl = max(lvl, SEA + 1)
    fit_rect(CMX - 17, CMZ - 17, CMX + 17, CMZ + 17, lvl, blend=10)
    for x in range(CMX - 17, CMX + 18):
        for z in range(CMZ - 17, CMZ + 18): TOPB[z, x] = sid(rng.choice(("podzol", "coarse_dirt", "grass_block", "grass_block", "gravel")))
    SLOTMASK[CMZ - 19:CMZ + 20, CMX - 19:CMX + 20] = True
    gothic_tower(CMX, CMZ, w=4, hgt=52, mats=("deepslate_bricks", "cracked_deepslate_bricks", "polished_blackstone_bricks", "deepslate_bricks"),
                 trim="polished_blackstone", win="black_stained_glass", flag_c="black", wall_cap="polished_blackstone_wall")
    for gx in range(CMX - 15, CMX + 16, 3):   # могили рядами
        for gz in range(CMZ - 15, CMZ + 16, 4):
            if max(abs(gx - CMX), abs(gz - CMZ)) < 9 or rng.random() > 0.8: continue
            g_ = top_at(gx, gz)
            setb(gx, g_ + 1, gz, rng.choice(("cobblestone_wall", "mossy_stone_brick_wall", "stone_brick_wall", "polished_blackstone_wall", "mossy_cobblestone_wall")))
            setb(gx, g_, gz + 1, rng.choice(("coarse_dirt", "podzol", "rooted_dirt")))
            r_ = rng.random()
            if r_ < 0.15: setb(gx, g_ + 2, gz, "candle[candles=2,lit=true]")
            elif r_ < 0.22: setb(gx, g_ + 1, gz + 1, "skeleton_skull[rotation=8]")
    g_ = top_at(CMX, CMZ - 18)
    for t in range(-17, 18):   # огорожа з ґрат і брама до порту (на захід)
        for px, pz in ((CMX + t, CMZ - 17), (CMX + t, CMZ + 17), (CMX + 17, CMZ + t), (CMX - 17, CMZ + t)):
            if px == CMX - 17 and abs(pz - CMZ) <= 1: continue
            setb(px, top_at(px, pz) + 1, pz, "iron_bars[east=true,west=true]" if pz in (CMZ - 17, CMZ + 17) else "iron_bars[north=true,south=true]")
            if (t % 6) == 0: setb(px, top_at(px, pz) + 1, pz, "polished_blackstone_wall"); setb(px, top_at(px, pz) + 2, pz, "soul_lantern")
    for _ in range(5):   # руїни й мертві білі дерева
        rx_, rz_ = CMX + rng.choice((-1, 1)) * rng.randint(10, 15), CMZ + rng.choice((-1, 1)) * rng.randint(10, 15)
        gg = top_at(rx_, rz_)
        for t in range(rng.randint(3, 6)): fill(rx_ + t, gg, rz_, rx_ + t, gg + rng.randint(1, 4), rz_, rng.choice(("mossy_stone_bricks", "cracked_stone_bricks", "cobbled_deepslate")))
    for _ in range(4): white_dead_tree(CMX + rng.randint(-14, 14), CMZ + rng.choice((-16, 16)))
    reserve(CMX, CMZ, 26)
    POI.append(("Готична вежа", CMX, CMZ)); POI.append(("Морське кладовище", CMX + 10, CMZ + 10))
    sign(CMX - 19, CMZ + 2, "Морське кладовище", "і готична вежа")
    log(f"морське кладовище: {CMX}, {CMZ}")

# ---------- Хвойне село: частокіл, кам'яний донжон на найвищій точці («дерево й сніг») ----------
HX, HZ = S(313, 243)
cands = [(HX + ox, HZ + oz) for ox in range(-24, 25, 4) for oz in range(-24, 25, 4) if 10 < math.hypot(ox, oz) < 26 and dRoad[HZ + oz, HX + ox] > 8]
dx_, dz_ = max(cands, key=lambda q: top_at(*q))
block_lot(dx_, dz_, 9, 9); square_tower(dx_, dz_, 4, 20, ("spruce_stairs", "spruce_planks"), "white"); POI.append(("Донжон Хвойного", dx_, dz_))
well(HX, HZ)
settle(HX, HZ, 44, 14, STYLES, center_free=11, pool=SPORUDY["pools"]["taiga"], street_mats=("dirt_path", "coarse_dirt", "podzol", "dirt_path"))
palisade(HX, HZ, 48, ("Хвойне село", "Lv 10-15"))
reserve(HX, HZ, 54)
POI.append(("Хвойне село", HX, HZ)); sign(HX + 6, HZ + 6, "Хвойне село", "Lv 10-15")
# ---------- Село Пшеничне: частокіл, кам'яна вежа, поля довкола («колоски й частокіл») ----------
WVX, WVZ = S(590, 840)
cands = [(WVX + ox, WVZ + oz) for ox in range(-26, 27, 4) for oz in range(-26, 27, 4) if 12 < math.hypot(ox, oz) < 28 and dRoad[WVZ + oz, WVX + ox] > 8 and dRiver[WVZ + oz, WVX + ox] > 12]
dx_, dz_ = max(cands, key=lambda q: top_at(*q))
block_lot(dx_, dz_, 9, 9); round_tower(dx_, dz_, 3.5, 17, roof=("spruce_stairs", "spruce_planks"), flag_c="yellow"); POI.append(("Вежа Пшеничного", dx_, dz_))
fountain(WVX, WVZ)
settle(WVX, WVZ, 46, 16, STYLES, center_free=12, pool=SPORUDY["pools"]["plains"])
palisade(WVX, WVZ, 52, ("Село Пшеничне", "Lv 10-15"))
for k in range(5):
    a = k / 5 * 6.283 + 0.5; fx_, fz_ = int(WVX + 64 * math.cos(a)), int(WVZ + 64 * math.sin(a))
    if not is_land(fx_, fz_) or reserved(fx_, fz_) or dRiver[fz_, fx_] < 12: continue
    g_ = top_at(fx_, fz_)
    for ox in range(-6, 7):
        for oz in range(-4, 5):
            if is_land(fx_ + ox, fz_ + oz) and abs(top_at(fx_ + ox, fz_ + oz) - g_) <= 1:
                if ox == 0 and abs(oz) < 4: setb(fx_ + ox, g_, fz_ + oz, "water")
                else: setb(fx_ + ox, g_, fz_ + oz, "farmland[moisture=7]"); setb(fx_ + ox, g_ + 1, fz_ + oz, rng.choice(("wheat[age=7]", "carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]")))
    for _ in range(4): setb(fx_ + rng.randint(-8, 8), top_at(fx_, fz_ + 6) + 1, fz_ + 6, "hay_block")
    reserve(fx_, fz_, 8)
reserve(WVX, WVZ, 58)
POI.append(("Село Пшеничне", WVX, WVZ)); sign(WVX + 6, WVZ + 6, "Село Пшеничне", "Lv 10-15")
for name_, hx_, hz_ in (("Стартове місто", TX, TZ + 20), ("Узлісся", VX, VZ + 18), ("Рибальське село", FVX, FVZ),
                         ("Порт", PX, PZ + 24), ("Західний порт", WPX + 24, WPZ), ("Хвойне село", HX, HZ), ("Село Пшеничне", WVX, WVZ)):
    wx_, wz_ = hx_ + 11, hz_ - 6
    for _ in range(60):
        if is_land(wx_, wz_) and not STREETMASK[wz_, wx_] and not SLOTMASK[wz_, wx_]: break
        wx_, wz_ = hx_ + rng.randint(-18, 18), hz_ + rng.randint(-18, 18)
    waystone(wx_, wz_, name_)

# ---------- Акведук: суцільна аркада за рельєфом, канал плавно спадає, опори до землі, арки над дорогами й річками ----------
log("акведук")
aqx = np.array([p[0] for p in AQ]); aqz = np.array([p[1] for p in AQ]); n_aq = len(AQ)
tgx, tgz = np.gradient(aqx), np.gradient(aqz); tl = np.hypot(tgx, tgz) + 1e-9; anx, anz = -tgz / tl, tgx / tl
aq_xy = lambda i, o: (int(round(aqx[i] + anx[i] * o)), int(round(aqz[i] + anz[i] * o)))
G_aq = np.array([max(top_at(*aq_xy(i, o)) for o in range(-2, 3)) for i in range(n_aq)])
road_aq = np.array([dRoad[int(aqz[i]), int(aqx[i])] < 5 for i in range(n_aq)])
y_end = top_at(*AQ_END) + 15
_k0 = int(np.argmin([math.hypot(aqx[i] - AQ_SRC[0], aqz[i] - AQ_SRC[1]) for i in range(n_aq)]))
y_start = max(int(G_aq[_k0]) + 1, y_end + 6)
i0 = _k0
while i0 > 0 and G_aq[i0] < y_start - 1: i0 -= 1   # портал у схилі: зсуваємо його вгору, поки земля не дійде до каналу
y_lin = y_start + (y_end - y_start) * np.clip((np.arange(n_aq) - i0) / (n_aq - 1 - i0), 0, 1)
need = np.where(road_aq, G_aq + 10, G_aq + 4)
need[i0:i0 + 10] = G_aq[i0:i0 + 10] + 1   # біля джерела канал лежить на схилі, а не висить
Y_aq = np.zeros(n_aq, int)
Y_aq[i0:] = np.ceil(np.maximum(y_lin[i0:], np.maximum.accumulate(need[i0:][::-1])[::-1])).astype(int)
pier = np.zeros(n_aq, bool)
for i in range(i0, n_aq, 13):
    if not road_aq[max(i - 3, 0):i + 6].any(): pier[i:i + 3] = True
pier[i0:i0 + 3] = True; pier[-3:] = True
runs, i = [], i0
while i < n_aq:   # прольоти між опорами
    if pier[i]: i += 1; continue
    j = i
    while j < n_aq and not pier[j]: j += 1
    runs.append((i, j - 1)); i = j
run_of = {}
for s_, e_ in runs:
    for i in range(s_, e_ + 1): run_of[i] = (s_, e_)
AQ_STONE = ("stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks")
for i in range(i0, n_aq):
    y = int(Y_aq[i])
    for o in range(-2, 3):
        px, pz = aq_xy(i, o); g = top_at(px, pz)
        setb(px, y - 1, pz, "stone_bricks")
        if abs(o) <= 1: setb(px, y, pz, "water"); fill(px, y + 1, pz, px, y + 2, pz, "air")
        else: setb(px, y, pz, rng.choice(AQ_STONE)); setb(px, y + 1, pz, "stone_brick_slab")
        if pier[i] or y - g < 7: col(px, pz, g - 2, y - 2); continue
        s_, e_ = run_of[i]; span = e_ - s_ + 1; r = span / 2; dx = i - s_ - (span - 1) / 2
        bands = [y - 2] + ([int(g + (y - g) * 0.5)] if y - g > 22 else [])
        for yb in bands:   # арка: півколо під кожним поясом (двоярусна там, де високо)
            lo = int(math.ceil(yb - r - 1 + math.sqrt(max(r * r - dx * dx, 0))))
            fill(px, max(lo, g), pz, px, yb, pz, rng.choice(AQ_STONE))
# початок: кам'яний портал у схилі вулкана, звідки виходить вода
y0_ = int(Y_aq[i0])
for i in range(max(i0 - 4, 0), i0 + 1):
    for o in range(-3, 4):
        px, pz = aq_xy(i, o)
        col(px, pz, top_at(px, pz) - 2 if i == i0 else y0_ - 2, y0_ + 5)
        if abs(o) <= 1: fill(px, y0_, pz, px, y0_ + 2, pz, "air"); setb(px, y0_, pz, "water")
px, pz = aq_xy(i0, 0); setb(px, y0_ + 3, pz, "chiseled_stone_bricks")
for o in (-3, 3): px, pz = aq_xy(i0, o); setb(px, y0_ + 6, pz, "lantern")
# кінець: цистерна-вежа в мурі стартового міста
ct = round_tower(AQ_END[0], AQ_END[1], 4, y_end - top_at(*AQ_END), roof=None, flag_c="blue")
disc(AQ_END[0], ct, AQ_END[1], 2.6, "water")
tall_i = int(np.argmax(np.where(np.arange(n_aq) >= i0, Y_aq - G_aq, -99)))
POI.append(("Акведук", int(aqx[tall_i]), int(aqz[tall_i])))
for i in range(i0, n_aq, 4): reserve(aqx[i], aqz[i], 6)
SLOTMASK &= ~AQ_MASK   # траву й квіти під арками лишаємо, дерева не ростуть (резерв)
for i in range(i0, n_aq): SLOTMASK[int(aqz[i]) - 3:int(aqz[i]) + 4, int(aqx[i]) - 3:int(aqx[i]) + 4] = True
for k_ in range(30):
    sx_, sz_ = aq_xy(min(tall_i + k_, n_aq - 1), 9)
    if is_land(sx_, sz_) and not reserved(sx_, sz_): sign(sx_, sz_, "Акведук", "вода з вулкана", "до стартового міста"); break
log(f"акведук: {n_aq - i0} блоків траси, найвища арка {int((Y_aq - G_aq)[tall_i])} над землею")

# ---------- дороги: мости, ліхтарі, огорожі ----------
log("дороги й мости")
for d in ROADS:
    pts = world_path(d)
    # рівномірні точки з кроком ~1 блок
    seq = []
    for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
        n = max(1, int(math.hypot(x1 - x0, z1 - z0)))
        for k in range(n): seq.append((x0 + (x1 - x0) * k / n, z0 + (z1 - z0) * k / n, x1 - x0, z1 - z0))
    i = 0
    while i < len(seq):
        x, z, vx, vz = seq[i]
        if river[int(z), int(x)]:
            j = i
            while j < len(seq) and river[int(seq[j][1]), int(seq[j][0])]: j += 1
            a, b = max(0, i - 4), min(len(seq) - 1, j + 4)
            deck = max(TOP0[int(seq[a][1]), int(seq[a][0])], TOP0[int(seq[b][1]), int(seq[b][0])]) + 1
            for k in range(a, b + 1):   # v5: кам'яний міст зі склепінням, парапетом і устоями
                bx, bz, vx, vz = seq[k]; L = math.hypot(vx, vz) or 1; nx, nz = -vz / L, vx / L
                t_ = (k - a) / max(b - a, 1); under = int(round(1 + 2.5 * (1 - math.sin(math.pi * t_))))
                for o in range(-3, 4):
                    px, pz = int(round(bx + nx * o)), int(round(bz + nz * o))
                    if abs(o) == 3: setb(px, deck, pz, "stone_bricks"); setb(px, deck + 1, pz, "stone_brick_wall")
                    else: setb(px, deck, pz, rng.choice(("stone_bricks", "polished_andesite", "stone_bricks", "andesite"))); fill(px, deck + 1, pz, px, deck + 3, pz, "air")
                    fill(px, deck - under, pz, px, deck - 1, pz, rng.choice(("stone_bricks", "mossy_stone_bricks", "stone_bricks")))
                    if k - a < 2 or b - k < 2: col(px, pz, TOP[pz, px] - 1, deck - 1)
                if k in (a, b):
                    for o in (-3, 3): setb(int(round(bx + nx * o)), deck + 2, int(round(bz + nz * o)), "lantern")
            i = j + 4; continue
        if i % 34 == 0:
            L = math.hypot(vx, vz) or 1; px, pz = int(round(x - vz / L * 4)), int(round(z + vx / L * 4))
            if is_land(px, pz) and not reserved(px, pz) and RIDGEH[pz, px] < 6: lamp(px, pz)
        i += 1
# огорожі біля доріг поблизу поселень
rz, rx = np.nonzero(roadside & (dRoad > 2.9))
for x, z in zip(rx[::1], rz[::1]):
    near = min(math.hypot(x - S(a, b)[0], z - S(a, b)[1]) - r_ for a, b, r_, *_ in TOWNS)
    if 20 < near < 95 and (x + z) % 3 != 0 and not reserved(x, z) and cat[z, x] != RIVERBED: setb(x, TOP[z, x] + 1, z, "oak_fence")

# ---------- v10: міста живіші й орієнтири вздовж доріг (правка користувача, пункти 7–8) ----------
HUBN = {"start": "Стартове місто", "village": "Узлісся", "fishing": "Рибальське село", "port": "Порт", "port_w": "Західний порт",
        "hills": "Хвойне село", "south": "Село Пшеничне"}
TOWN_C = [(S(a, b)[0], S(a, b)[1], r_, HUBN[k_]) for a, b, r_, _, k_ in TOWNS]
def flat_ok(x, z, r=2, d=3):
    a = TOP[max(z - r, 0):z + r + 1, max(x - r, 0):x + r + 1]
    return bool(a.size) and int(a.max()) - int(a.min()) <= d and is_land(x, z) and not river[z, x] and not reserved(x, z) and not SLOTMASK[z, x] and not STREETMASK[z, x]
def town_dress(cx, cz, rad, kind):
    """Вуличні дрібниці вздовж вулиць: лавки, клумби, бочки, ліхтарі; криниця на майдані села."""
    n = {"lamp": 0, "bed": 0, "bench": 0, "goods": 0}; lamps = []
    lots = SLOTMASK | np.roll(SLOTMASK, 1, 0) | np.roll(SLOTMASK, -1, 0) | np.roll(SLOTMASK, 1, 1) | np.roll(SLOTMASK, -1, 1)   # будинки з відступом 1
    for z in range(max(cz - rad, 1), min(cz + rad, SIZE - 2)):
        for x in range(max(cx - rad, 1), min(cx + rad, SIZE - 2)):
            if (x - cx) ** 2 + (z - cz) ** 2 > rad * rad or STREETMASK[z, x] or lots[z, x] or USED[z, x] or not is_land(x, z) or river[z, x]: continue
            if not (STREETMASK[z - 1, x] or STREETMASK[z + 1, x] or STREETMASK[z, x - 1] or STREETMASK[z, x + 1]): continue
            r_ = rng.random(); g = top_at(x, z)
            if r_ < 0.035 and all((x - a) ** 2 + (z - b) ** 2 > 144 for a, b in lamps):
                lamp(x, z); lamps.append((x, z)); n["lamp"] += 1
            elif r_ < 0.09:
                setb(x, g, z, "grass_block"); setb(x, g + 1, z, rng.choice(("poppy", "cornflower", "oxeye_daisy", "allium", "red_tulip", "azure_bluet")))
                n["bed"] += 1
            elif r_ < 0.11:
                sx = 1 if STREETMASK[z, x + 1] else -1 if STREETMASK[z, x - 1] else 0
                sz = 1 if STREETMASK[z + 1, x] else -1 if STREETMASK[z - 1, x] else 0
                setb(x, g + 1, z, f"spruce_stairs[facing={card(-sx, -sz) if (sx or sz) else 'north'}]"); n["bench"] += 1
            elif r_ < 0.135:
                setb(x, g + 1, z, rng.choice(("barrel", "barrel", "hay_block", "composter", "chest", "potted_red_tulip", "potted_fern"))); n["goods"] += 1
            else: continue
            USED[max(z - 1, 0):z + 2, max(x - 1, 0):x + 2] = True
    if kind in ("hills", "south", "fishing", "port_w") and not SLOTMASK[cz - 3:cz + 4, cx - 3:cx + 4].any() and np.ptp(TOP[cz - 3:cz + 4, cx - 3:cx + 4]) <= 2:
        well(cx, cz); reserve(cx, cz, 5); n["well"] = 1
    return n
def town_gates(cx, cz, rad, name):
    """Брама на в'їзді: два стовпи з ліхтарями й табличка з назвою там, де дорога перетинає край поселення."""
    k = 0
    for d in ROADS:
        pts = resample(list(world_path(d)), 1.0)
        for i in range(1, len(pts) - 1):
            x, z = pts[i]
            if abs(math.hypot(x - cx, z - cz) - (rad + 4)) > 0.7: continue
            vx, vz = pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1]; L = math.hypot(vx, vz) or 1; nx, nz = -vz / L, vx / L
            posts = [(int(round(x + nx * o)), int(round(z + nz * o))) for o in (-4, 4)]
            if any(not is_land(px, pz) or river[pz, px] or SLOTMASK[pz, px] or STREETMASK[pz, px] or USED[pz, px] for px, pz in posts): continue
            for px, pz in posts:
                g = top_at(px, pz); fill(px, g, pz, px, g + 3, pz, "stone_bricks"); setb(px, g + 4, pz, "chiseled_stone_bricks"); setb(px, g + 5, pz, "lantern")
                USED[pz - 2:pz + 3, px - 2:px + 3] = True
            sx, sz = int(round(x + nx * 6)), int(round(z + nz * 6))
            if is_land(sx, sz): sign(sx, sz, name, "ласкаво просимо")
            k += 1; break
    return k
def shrine(x, z):
    g = top_at(x, z)
    fill(x - 1, g, z - 1, x + 1, g, z + 1, "mossy_stone_bricks"); fill(x - 1, g + 1, z + 1, x + 1, g + 3, z + 1, "stone_bricks")
    setb(x, g + 2, z + 1, "chiseled_stone_bricks"); fill(x - 1, g + 4, z - 1, x + 1, g + 4, z + 1, "stone_brick_slab")
    for a in (-1, 1): fill(x + a, g + 1, z - 1, x + a, g + 3, z - 1, "stone_brick_wall")
    setb(x, g + 1, z, "candle[candles=3,lit=true]"); setb(x - 1, g + 1, z, "potted_poppy"); setb(x, g + 3, z - 1, "lantern[hanging=true]")
def milestone(x, z, lines):
    g = top_at(x, z)
    fill(x, g + 1, z, x, g + 2, z, "mossy_stone_bricks"); setb(x, g + 3, z, "chiseled_stone_bricks"); setb(x, g + 4, z, "stone_brick_wall")
    sign(x + 1, z + 1, *lines)
def rest_spot(x, z):
    g = top_at(x, z)
    setb(x, g + 1, z, "campfire"); setb(x + 2, g + 1, z, "spruce_stairs[facing=east]"); setb(x - 2, g + 1, z, "spruce_stairs[facing=west]")
    setb(x, g + 1, z + 2, "barrel"); oak(x + 4, z + 3, "oak")
def obelisk(x, z):
    g = top_at(x, z)
    fill(x - 1, g, z - 1, x + 1, g, z + 1, "polished_andesite")
    for k in range(1, 10): setb(x, g + k, z, "stone_bricks" if k % 3 else "chiseled_stone_bricks")
    setb(x, g + 10, z, "lightning_rod"); setb(x + 1, g + 1, z, "stone_brick_wall"); setb(x - 1, g + 1, z, "stone_brick_wall"); setb(x, g + 1, z + 1, "soul_lantern")
def ahead_town(x, z, vx, vz):
    """Найближче поселення попереду по ходу дороги (у секторі ±70°)."""
    L = math.hypot(vx, vz) or 1; best = None
    for tx, tz, r_, nm in TOWN_C:
        dx, dz = tx - x, tz - z; D = math.hypot(dx, dz) or 1
        if (dx * vx + dz * vz) / (D * L) > 0.34 and (best is None or D < best[0]): best = (D, nm)
    return best
log("v10: міста й орієнтири")
USED = np.zeros((SIZE, SIZE), bool)   # клітинки, вже зайняті міським декором
_dress = {}
for (tx, tz, r_, nm), (_, _, _, _, k_) in zip(TOWN_C, TOWNS):
    if k_ == "village": continue
    _dress[nm] = town_dress(tx, tz, r_ + 6, k_)
    if k_ in ("hills", "south", "fishing", "port_w"): _dress[nm]["gates"] = town_gates(tx, tz, r_, nm)
log("облаштування міст: " + "; ".join(f"{k}: {v}" for k, v in _dress.items()))
# перехрестя: дороговказ із трьома найближчими поселеннями й сторонами світу
def SIDE(dx, dz): return ("пн" if dz < -abs(dx) * 0.5 else "пд" if dz > abs(dx) * 0.5 else "") + ("сх" if dx > abs(dz) * 0.5 else "зх" if dx < -abs(dz) * 0.5 else "")
_rp = [np.asarray(resample(list(world_path(d)), 3.0)) for d in ROADS]; JUNC = []
for i in range(len(_rp)):
    for j in range(i + 1, len(_rp)):
        D = np.hypot(_rp[i][:, None, 0] - _rp[j][None, :, 0], _rp[i][:, None, 1] - _rp[j][None, :, 1])
        a, b = np.unravel_index(np.argmin(D), D.shape)
        if D[a, b] > 6: continue
        x, z = (_rp[i][a] + _rp[j][b]) / 2
        if any(math.hypot(x - tx, z - tz) < r_ + 12 for tx, tz, r_, _ in TOWN_C) or any(math.hypot(x - a_, z - b_) < 25 for a_, b_ in JUNC): continue
        JUNC.append((x, z))
n_sign = 0
for x, z in JUNC:
    spot_ = None
    for o in range(6, 14):
        for px, pz in ((int(x) + o, int(z) + o // 2), (int(x) - o, int(z) - o // 2), (int(x) + o // 2, int(z) - o)):
            if flat_ok(px, pz, 1, 2): spot_ = (px, pz); break
        if spot_: break
    if not spot_: continue
    px, pz = spot_; near = sorted(TOWN_C, key=lambda t: math.hypot(t[0] - px, t[1] - pz))[:3]
    g = top_at(px, pz); fill(px, g + 1, pz, px, g + 3, pz, "spruce_fence"); setb(px, g + 4, pz, "lantern")
    sign(px + 1, pz, "Перехрестя", *[f"{t[3][:11]} {SIDE(t[0] - px, t[1] - pz)}" for t in near])
    reserve(px, pz, 3); POI.append(("Перехрестя", px, pz)); n_sign += 1
# уздовж доріг: каплички, верстові стовпи, місця відпочинку, обеліски — кожні ~130 блоків поза поселеннями
_marks = 0; KINDS = ("milestone", "shrine", "rest", "milestone", "obelisk")
for d in ROADS:
    pts = resample(list(world_path(d)), 1.0); acc = 60
    for i in range(1, len(pts) - 1):
        x, z = pts[i]; acc += 1
        if acc < 130 or any(math.hypot(x - tx, z - tz) < r_ + 18 for tx, tz, r_, _ in TOWN_C): continue
        vx, vz = pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1]; L = math.hypot(vx, vz) or 1
        px, pz = int(round(x - vz / L * 6)), int(round(z + vx / L * 6))
        if not flat_ok(px, pz, 2, 3): continue
        kind = KINDS[_marks % len(KINDS)]
        if kind == "milestone":
            t_ = ahead_town(x, z, vx, vz)
            if t_ is None: kind = "shrine"
            else: milestone(px, pz, (t_[1][:15], f"{int(t_[0])} кроків"))
        if kind == "shrine": shrine(px, pz)
        elif kind == "rest": rest_spot(px, pz)
        elif kind == "obelisk": obelisk(px, pz)
        reserve(px, pz, 6); acc = 0; _marks += 1
log(f"орієнтири: перехресть {len(JUNC)} (дороговказів {n_sign}), придорожніх знаків {_marks}")

def zone_arch(x, z, nx, nz):
    """Кам'яна арка над дорогою на межі зони (кадри 52–53 гри): два стовпи, перекладина, ліхтар; іноді напівзруйнована."""
    cols = [(int(round(x + nx * 5 * s_)), int(round(z + nz * 5 * s_))) for s_ in (-1, 1)]
    gt = max(top_at(*c) for c in cols) + 8; ruined = rng.random() < 0.35
    for px, pz in cols: col(px, pz, top_at(px, pz) - 2, gt)
    for t in range(-5, 6):
        if ruined and t > 1: continue
        setb(int(round(x + nx * t)), gt, int(round(z + nz * t)), rng.choice(("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks")))
        if abs(t) <= 3 and not (ruined and t > 0): setb(int(round(x + nx * t)), gt + 1, int(round(z + nz * t)), "stone_brick_slab")
    if not ruined: setb(int(round(x)), gt - 1, int(round(z)), "lantern[hanging=true]")
# знаки на межах зон: дорога перетинає межу — табличка з назвою й рівнями нової зони
zone_signs = 0
for d in ROADS:
    pts = resample(list(world_path(d)), 2.0); prev = zone_at(*pts[0])
    for i, (x, z) in enumerate(pts[1:], 1):
        zn = zone_at(x, z)
        if zn != prev and zn > 0 and prev > 0:
            (ax_, az_), (bx_, bz_) = pts[max(i - 2, 0)], pts[min(i + 2, len(pts) - 1)]; L = math.hypot(bx_ - ax_, bz_ - az_) or 1
            for sgn in (1, -1):
                px, pz = int(x - (bz_ - az_) / L * 4 * sgn), int(z + (bx_ - ax_) / L * 4 * sgn)
                if is_land(px, pz) and not reserved(px, pz):
                    _, zname, zlv, _, _ = ZONES[zn - 1]
                    sign(px, pz, zname, "Lv " + zlv.replace("–", "-")); lamp(px + 1, pz + 1, 2); zone_signs += 1
                    zone_arch(x, z, -(bz_ - az_) / L, (bx_ - ax_) / L); break
        prev = zn
log(f"знаків зон: {zone_signs}")

# ---------- точки інтересу ----------
log("точки інтересу")
# 1. Маяк
LX, LZ = S(140, 560)
fy = platform(LX - 4, LZ - 4, LX + 4, LZ + 4, "stone_bricks")
for k in range(9):
    fill(LX - 3, fy + k * 4, LZ - 3, LX + 3, fy + k * 4 + 3, LZ + 3, "white_concrete" if k % 2 == 0 else "red_concrete")
fill(LX - 2, fy, LZ - 2, LX + 2, fy + 35, LZ + 2, "air"); fill(LX, fy, LZ + 3, LX, fy + 1, LZ + 3, "air")
fill(LX - 4, fy + 36, LZ - 4, LX + 4, fy + 36, LZ + 4, "stone_bricks"); hollow(LX - 4, fy + 37, LZ - 4, LX + 4, fy + 37, LZ + 4, "iron_bars")
fill(LX - 1, fy + 37, LZ - 1, LX + 1, fy + 39, LZ + 1, "sea_lantern"); pyramid_roof(LX - 2, LZ - 2, LX + 2, LZ + 2, fy + 40, ("deepslate_tile_stairs", "deepslate_tiles"))
poi("Маяк", LX, LZ, "Маяк", "", "вхід у морський грот", "поруч", sign_dx=7, sign_dz=0)

# 2. Ферма з вітряком
WX, WZ = S(232, 548)
fy = house(WX, WZ, 9, 9, STYLES[1], hgt=10)
for k in range(-11, 12):
    setb(WX + k, fy + 7, WZ - 5, "white_wool"); setb(WX, fy + 7 + k, WZ - 5, "white_wool")
setb(WX, fy + 7, WZ - 5, "spruce_log[axis=z]")
for k in range(14): setb(WX + 8 + k % 4, top_at(WX + 8 + k % 4, WZ + 6 + k // 4) + 1, WZ + 6 + k // 4, "hay_block")
for k in range(10): setb(WX - 10 + rng.randint(-3, 3), top_at(WX - 10, WZ + 8) + 1, WZ + 8 + rng.randint(-3, 3), "pumpkin")
poi("Ферма з вітряком", WX, WZ, "Ферма", "з вітряком", "квести «принеси»", sign_dx=8, sign_dz=8)

# 3. v6: Обсидіановий собор — арена боса (рішення користувача: на сході замість фортеці; кадр 16 відео):
# білий собор із червоним куполом і смугастим дахом серед темно-фіолетових щупалець на білій землі
FTX, FTZ = S(840, 440)
def cathedral(cx, cz):
    W, H = 9, 17
    x0, x1 = cx - 32, cx + 6                       # нава від західного фасаду до куполу
    DX_, R = cx + 16, 12                           # купол над середохрестям
    fy = platform(x0 - 7, cz - 17, DX_ + R + 3, cz + 17, "smooth_quartz")
    fill(x0 - 7, fy - 1, cz - 17, DX_ + R + 3, fy - 1, cz + 17, "smooth_quartz")
    WALL = ("quartz_bricks", "smooth_quartz", "calcite", "quartz_block", "quartz_bricks")
    for x in range(x0, x1 + 1):
        for z in (cz - W, cz + W): fill(x, fy, z, x, fy + H, z, rng.choice(WALL))
        for z in range(cz - W + 1, cz + W): setb(x, fy - 1, z, "polished_blackstone" if (x + z) % 2 else "quartz_block")
    for z in range(cz - W, cz + W + 1): fill(x0, fy, z, x0, fy + H, z, "quartz_bricks")
    fill(x0 + 1, fy, cz - W + 1, x1 - 1, fy + H, cz + W - 1, "air")
    for x in range(x0 + 3, x1 - 1, 5):            # контрфорси й високі вікна
        for s_ in (-1, 1):
            z = cz + s_ * W
            fill(x, fy, z + s_, x, fy + H - 2, z + s_, "quartz_pillar"); fill(x, fy, z + 2 * s_, x, fy + H - 8, z + 2 * s_, "quartz_pillar")
            setb(x, fy + H - 7, z + 2 * s_, "quartz_slab")
            fill(x + 2, fy + 4, z, x + 3, fy + H - 4, z, "purple_stained_glass"); fill(x + 2, fy + H - 3, z, x + 3, fy + H - 3, z, "chiseled_quartz_block")
    gable2(x0, cz - W, x1, cz + W, fy + H + 1, ("red_nether_brick_stairs", "red_nether_bricks"), along_x=True, ov=1, pitch=1,
           trim=("quartz_stairs", "quartz_block"), gable="quartz_bricks")
    for x in range(x0 + 2, x1, 4):                 # білі ребра на червоному даху
        for k in range(W + 2):
            zn, zs, yy = cz - W - 1 + k, cz + W + 1 - k, fy + H + 1 + k
            if zn >= zs: break
            setb(x, yy, zn, "quartz_stairs[facing=south]"); setb(x, yy, zs, "quartz_stairs[facing=north]")
    # західний фасад: велика арка-вхід, кругле вікно-троянда, дві вежі з червоними конусами
    for z in range(cz - 3, cz + 4):
        fill(x0, fy, z, x0, fy + (7 if abs(z - cz) < 3 else 5), z, "air")
    for z in range(cz - 4, cz + 5):
        for yy in range(fy + 9, fy + 17):
            d_ = math.hypot(z - cz, yy - (fy + 13))
            if d_ <= 3.6: setb(x0, yy, z, "purple_stained_glass" if d_ > 0.8 else "crying_obsidian")
            elif d_ <= 4.4: setb(x0, yy, z, "chiseled_quartz_block")
    for s_ in (-1, 1): round_tower(x0 - 1, cz + s_ * (W + 2), 3.5, H + 12, roof=("red_nether_brick_stairs", "red_nether_bricks"), flag_c="purple", pal="quartz_bricks", base=fy)
    # купол: барабан з вікнами, півкуля з білими ребрами, ліхтар зі шпилем
    X_, Y_, Z_ = np.mgrid[-R - 1:R + 2, 0:R + 2, -R - 1:R + 2]; D2 = np.hypot(X_, Z_)
    for a_, b_ in zip(X_[:, 0, :][(D2[:, 0, :] <= R + 0.5) & (D2[:, 0, :] > R - 0.7)], Z_[:, 0, :][(D2[:, 0, :] <= R + 0.5) & (D2[:, 0, :] > R - 0.7)]):
        fill(DX_ + a_, fy, cz + b_, DX_ + a_, fy + H + 4, cz + b_, rng.choice(WALL))
        if int((math.atan2(b_, a_) + 4) * 4) % 3 == 0: fill(DX_ + a_, fy + 6, cz + b_, DX_ + a_, fy + H, cz + b_, "purple_stained_glass")
    inner = D2[:, 0, :] <= R - 0.7
    for a_, b_ in zip(X_[:, 0, :][inner], Z_[:, 0, :][inner]):
        fill(DX_ + a_, fy, cz + b_, DX_ + a_, fy + H + 4, cz + b_, "air"); setb(DX_ + a_, fy - 1, cz + b_, "polished_blackstone" if (a_ + b_) % 2 else "quartz_block")
    fill(x1, fy, cz - 5, x1, fy + 12, cz + 5, "air")   # арка з нави під купол
    yb = fy + H + 5; DS = np.sqrt(X_ ** 2 + Z_ ** 2 + (Y_ * 1.0) ** 2)
    shell = (DS <= R + 0.5) & (DS > R - 0.9)
    ang = (np.arctan2(Z_, X_) + math.pi) / (2 * math.pi) * 16
    rib = np.abs(ang - np.round(ang)) < 0.16
    for m_, st in ((shell & rib, "quartz_block"), (shell & ~rib, "red_nether_bricks")):
        put(X_[m_] + DX_, Y_[m_] + yb, Z_[m_] + cz, st)
    lt = yb + R
    for a_, b_ in ((-2, -2), (2, -2), (-2, 2), (2, 2)): fill(DX_ + a_, lt, cz + b_, DX_ + a_, lt + 4, cz + b_, "quartz_pillar")
    disc(DX_, lt + 5, cz, 3.2, "quartz_block"); top_ = cone_roof(DX_, cz, lt + 6, 3.0, ("quartz_stairs", "quartz_block"), h=6)
    fill(DX_, top_, cz, DX_, top_ + 2, cz, "end_rod")
    # всередині: вівтар з плачучого обсидіану під куполом, душові ліхтарі, приховане світло
    fill(DX_ - 2, fy - 1, cz - 2, DX_ + 2, fy - 1, cz + 2, "crying_obsidian"); setb(DX_, fy, cz, "soul_campfire")
    for k in range(8):
        a = k / 8 * 6.283; setb(int(DX_ + 8 * math.cos(a)), fy, int(cz + 8 * math.sin(a)), "soul_lantern")
    for x in range(x0 + 4, x1, 6):
        for z in (cz - 5, cz + 5): setb(x, fy + 8, z, "light[level=12]")
    return fy, DX_
cfy, CDX = cathedral(FTX, FTZ)
ANCHORS.append({"role": "boss_arena", "hub": "port", "x": CDX, "y": cfy, "z": FTZ})
reserve(FTX - 8, FTZ, 50)
# темно-фіолетові щупальця, що здіймаються з білої землі й загинаються до собору
TENT = ("obsidian", "obsidian", "crying_obsidian", "obsidian", "amethyst_block", "crying_obsidian", "purple_concrete")
tent_pts = []
for _ in range(1500):
    if len(tent_pts) >= 75: break
    a = rng.uniform(0, 6.283); d = rng.uniform(34, 125); x, z = int(FTX - 8 + d * math.cos(a)), int(FTZ + d * 0.85 * math.sin(a))
    if not (20 < x < SIZE - 20 and 20 < z < SIZE - 20) or not is_land(x, z) or cat[z, x] != FORTC or dRoad[z, x] < 6: continue
    if any((x - p) ** 2 + (z - q) ** 2 < 11 ** 2 for p, q in tent_pts): continue
    g = top_at(x, z); Ht = rng.uniform(14, 34) * (1.25 - d / 160); ux, uz = (FTX - 8 - x) / d, (FTZ - z) / d
    tip = (x + ux * Ht * 0.7, g + Ht, z + uz * Ht * 0.7)
    curve = bezier3((x, g - 2, z), (x - ux * 4, g + Ht * 0.45, z - uz * 4), (tip[0] - ux * 2, tip[1] + 4, tip[2] - uz * 2), tip, int(Ht * 1.5) + 6)
    n_ = len(curve)
    for i, (px, py, pz) in enumerate(curve):
        sphere(px, py, pz, max(0.7, 2.8 * (1 - i / n_) + 0.4), rng.choice(TENT), ry=0.9)
    tent_pts.append((x, z))
log(f"щупалець: {len(tent_pts)}")
poi("Обсидіановий собор", FTX, FTZ, "Обсидіановий собор", "арена боса", "Lv 20", sign_dx=-44, sign_dz=4, r=10)

# v7: брами тунелю під хребтом (референси: нотатки 19, розд. 12): з півдня темний фасад у скелі, з півночі біло-блакитна брама
def tunnel_gate(x, z, face, light):
    dz = 1 if face == "south" else -1            # куди дивиться фасад
    g = int(tun_floor(z)) - 1                    # v10: поріг брами на рівні підлоги тунелю
    mats = (("calcite", "diorite", "calcite", "packed_ice", "polished_diorite") if light else ("deepslate_bricks", "polished_blackstone_bricks", "deepslate_bricks", "cracked_deepslate_bricks"))
    trim = "blue_ice" if light else "chiseled_deepslate"
    for xx in range(x - 11, x + 12):
        top_ = g + 18 - (abs(xx - x) // 3) * (1 if light else 0)
        for t in range(0, 4): fill(xx, g - 2, z - dz * t, xx, top_, z - dz * t, rng.choice(mats))
    for cx_ in (-9, -5, 5, 9):                   # колони-пілястри з виносом
        fill(x + cx_, g, z + dz, x + cx_, g + 14, z + dz, "quartz_pillar" if light else "polished_basalt")
        setb(x + cx_, g + 15, z + dz, trim)
    for xx in range(x - 3, x + 4):               # арка-прохід
        hgt = 10 if abs(xx - x) < 3 else 8
        fill(xx, g + 1, z + dz, xx, g + hgt, z - dz * 8, "air"); setb(xx, g, z - dz * 2, rng.choice(("polished_andesite", "stone_bricks")))
    for xx in (x - 4, x + 4): fill(xx, g + 1, z + dz, xx, g + 9, z + dz, trim)
    fill(x - 3, g + 11, z + dz, x + 3, g + 11, z + dz, trim)
    for xx in (x - 2, x + 2): setb(xx, g + 9, z + dz * 2, "lantern[hanging=false]")
    if light: fill(x - 11, g + 19, z, x + 11, g + 19, z - dz * 3, "snow[layers=2]")
    for k in range(1, 7):                        # алея жердин із ліхтарями (кадри 131–132)
        for s_ in (-6, 6):
            px, pz = x + s_, z + dz * (6 + k * 6); gg = top_at(px, pz)
            fill(px, gg + 1, pz, px, gg + 7, pz, "spruce_log"); setb(px, gg + 8, pz, "spruce_fence"); setb(px, gg + 9, pz, "lantern" if light else "soul_lantern")
    reserve(x, z + dz * 18, 22)
tunnel_gate(TUN_S[0], TUN_S[1], "south", False)
tunnel_gate(TUN_N[0], TUN_N[1], "north", True)
def well_shaft():
    """v10: Колодязь світла як давня шахта (правка користувача: «не просто дірка»): облицювання з поясами,
    гвинтові сходи по стіні від мосту до гребеня, парапет, стовпи з ліхтарями на ланцюгах."""
    WX, WZ = TUN_WELL; R = WELL_R; X = TUN_S[0]
    ybot = _wf - 12
    LINE = ("stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks", "mossy_stone_bricks")
    ring = []
    for x in range(int(WX) - R - 3, int(WX) + R + 4):
        for z in range(int(WZ) - R - 3, int(WZ) + R + 4):
            d = math.hypot(x - WX, z - WZ)
            if not (R <= d < R + 2): continue
            g = top_at(x, z); ring.append((x, z, d, g)); fz = int(tun_floor(z))
            for y in range(ybot - 1, g + 1):
                if abs(x - X) <= 8 and fz - 1 <= y <= fz + 12: continue      # не закладати отвори тунелю
                setb(x, y, z, "chiseled_stone_bricks" if (y - ybot) % 16 == 0 else LINE[(x * 7 + y * 3 + z * 5) % len(LINE)])
    # майданчик від мосту до початку сходів (схід, трохи на північ від центру)
    zl0, zl1 = int(WZ) - 6, int(WZ) - 2; yl = int(tun_floor(zl1))
    fill(X + 3, yl - 1, zl0, X + 12, yl - 1, zl1, "stone_bricks")
    for z in range(zl0, zl1 + 1): setb(X + 3, int(tun_floor(z)), z, "air")    # прохід у перилах мосту
    a0 = -0.35; s = 0.0; cells, rail = {}, []
    while True:
        k = int(s // 2); y = yl + k; a = a0 + s / 11.0; first = (s % 2) < 1
        ox, oz = math.cos(a), math.sin(a)
        if y >= top_at(int(round(WX + (R + 1.5) * ox)), int(round(WZ + (R + 1.5) * oz))): break
        fac = card(-oz, ox)
        for r in (10.0, 11.0, 12.0):
            key = (int(round(WX + r * ox)), y, int(round(WZ + r * oz)))
            if key not in cells: cells[key] = f"stone_brick_stairs[facing={fac}]" if first else LINE[k % len(LINE)]
        rail.append((int(round(WX + 9.0 * ox)), int(round(WZ + 9.0 * oz)), y if first else y + 1, s))
        s += 0.25
    a_exit = a0 + s / 11.0
    for (x, y, z), st in cells.items():
        setb(x, y, z, st)
        if (x, y - 1, z) not in cells: setb(x, y - 1, z, "stone_bricks")
    seen = set()
    for x, z, yw, s_ in rail:
        if (x, z, yw) in seen or s_ < 4: continue                    # біля майданчика перила не ставимо
        seen.add((x, z, yw)); setb(x, yw - 1, z, "stone_bricks"); setb(x, yw, z, "stone_brick_wall")
        if int(s_ * 4) % 48 == 0: setb(x, yw + 1, z, "lantern")
    # парапет по краю й чотири стовпи з балками, з яких у шахту звисають ліхтарі на ланцюгах
    for x, z, d, g in ring:
        da = (math.atan2(z - WZ, x - WX) - a_exit + math.pi) % (2 * math.pi) - math.pi
        if d < R + 1 and abs(da) > 0.35: setb(x, g + 1, z, "stone_brick_wall")
    for k in range(4):
        a = a_exit + math.pi / 4 + k * math.pi / 2; ox, oz = math.cos(a), math.sin(a)
        px, pz = int(round(WX + (R + 1) * ox)), int(round(WZ + (R + 1) * oz)); g = top_at(px, pz)
        fill(px, g + 1, pz, px, g + 4, pz, "stone_bricks"); setb(px, g + 5, pz, "chiseled_stone_bricks"); setb(px, g + 6, pz, "lantern")
        for r in range(R - 5, R):
            setb(int(round(WX + r * ox)), g + 4, int(round(WZ + r * oz)), "stone_brick_slab[type=top]")
        hx, hz = int(round(WX + (R - 5) * ox)), int(round(WZ + (R - 5) * oz))   # ланцюг усередині перил, не над сходами
        fill(hx, g - 6, hz, hx, g + 3, hz, "chain"); setb(hx, g - 7, hz, "lantern[hanging=true]")
    log(f"колодязь світла: сходи {len(cells)} блоків, підйом {yl} -> {yl + int(s // 2)}, облицювання {len(ring)} колон")
def road_tunnel():
    """v8: прямий рівний тунель (нотатка 24, розд. 4 і 7): темний коридор → колонада з лавовими каналами за ґратами →
    міст над світловим колодязем → смугастий крижаний коридор (знімки 123–139). Будується правками після вирізання печер."""
    X = TUN_S[0]; zS, zN = TUN_S[1] - 4, TUN_N[1] + 4
    WX, WZ = TUN_WELL; wz0, wz1 = int(WZ) - WELL_R, int(WZ) + WELL_R
    in_well = lambda x, z: (x - WX) ** 2 + (z - WZ) ** 2 < (WELL_R - 0.6) ** 2   # із запасом: край ями округлюється інакше
    colN, colS = wz1 + 1, min(zS - 30, wz1 + 90)
    STRIPES = ("sandstone", "calcite", "smooth_sandstone", "packed_ice", "calcite", "blue_ice", "cut_sandstone", "diorite")
    for z in range(zN, zS + 1):
        y0 = int(tun_floor(z))
        wide = colN <= z <= colS; dark = z > colS; ice = z < wz0
        hw, hh = (7, 11) if wide else (3, 8)
        for dx in range(-hw - 1, hw + 2):
            x = X + dx
            if in_well(x, z): continue                              # v10: шахта колодязя — без стін, лише міст
            top = y0 + hh - max(0, abs(dx) - (hw - 2))
            if wide: mat = "polished_blackstone_bricks" if (top + z) % 5 else "chiseled_polished_blackstone"
            elif dark: mat = rng.choice(("deepslate_bricks", "deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles"))
            else: mat = None
            if abs(dx) == hw + 1:                                   # стіни
                for y in range(y0, top + 1):
                    setb(x, y, z, mat or STRIPES[((y + z // 6) // 2) % len(STRIPES)])
                continue
            fill(x, y0, z, x, top, z, "air")
            setb(x, top + 1, z, mat or STRIPES[((top + 1 + z // 6) // 2) % len(STRIPES)])   # склепіння
            if abs(dx) <= 2: setb(x, y0 - 1, z, "polished_andesite" if (z + dx) % 4 else "stone_bricks")
            else: setb(x, y0 - 1, z, "polished_deepslate" if not ice else "packed_ice")
        if int(tun_floor(z + 1)) > y0:                              # v10: сходинка (підлога росте на південь)
            for dx in range(-hw, hw + 1):
                if not in_well(X + dx, z): setb(X + dx, y0, z, "stone_brick_stairs[facing=south]")
        if abs(z - WZ) < WELL_R + 1: continue                       # біля шахти — без ліхтарів і каналів
        if wide:
            if abs(z - colN) % 7 == 3:                              # колони
                for sx_ in (-4, 4): fill(X + sx_, y0, z, X + sx_, y0 + hh - 1, z, "polished_basalt"); setb(X + sx_, y0 + hh, z, "chiseled_polished_blackstone")
            else:
                for sx_ in (-3, 3): setb(X + sx_, y0, z, "polished_blackstone_brick_wall")   # парапет доріжки
            for sx_ in (-6, 6): setb(X + sx_, y0 - 1, z, "lava"); setb(X + sx_, y0, z, "iron_bars")   # лавові канали за ґратами
            if z % 9 == 0: setb(X, y0 + hh, z, "lantern[hanging=true]")
        elif dark:
            if z % 8 == 0: setb(X, y0 + hh, z, "lantern[hanging=true]")
        else:
            if z % 9 == 0:
                for sx_ in (-hw - 1, hw + 1): setb(X + sx_, y0 + 4, z, "sea_lantern")   # вогні в нішах
    for z in range(wz0, wz1 + 1):                                   # міст через колодязь
        y0 = int(tun_floor(z))
        fill(X - 2, y0 - 1, z, X + 2, y0 - 1, z, "stone_bricks" if z % 6 else "chiseled_stone_bricks")
        setb(X - 2, y0 - 2, z, "stone_brick_stairs[facing=west,half=top]"); setb(X + 2, y0 - 2, z, "stone_brick_stairs[facing=east,half=top]")
        if int(tun_floor(z + 1)) > y0: fill(X - 2, y0, z, X + 2, y0, z, "stone_brick_stairs[facing=south]")
        for sx_ in (-3, 3):
            setb(X + sx_, y0 - 1, z, "stone_bricks"); setb(X + sx_, y0, z, "stone_brick_wall")
            if z % 6 == 0: setb(X + sx_, y0 + 1, z, "lantern")
    well_shaft()
    reserve(int(TUN_WELL[0]), int(TUN_WELL[1]), 20)
    POI.append(("Колодязь світла", int(TUN_WELL[0]), int(TUN_WELL[1])))
    log(f"тунель: коридор {zN}..{zS}, колонада {colN}..{colS}, міст {wz0}..{wz1}")
road_tunnel()
POI.append(("Тунель під хребтом", (TUN_S[0] + TUN_N[0]) // 2, (TUN_S[1] + TUN_N[1]) // 2))
sign(TUN_S[0] + 9, TUN_S[1] + 9, "Тунель під хребтом", "до порту")
# v9: поверхнева Брама Джерел; зв'язок із новим підземеллям ще не будується.
def spring_gate(x, z):
    g = platform(x - 9, z - 2, x + 9, z + 4, "mossy_stone_bricks")
    for dx in range(-8, 9):
        arch_y = g + 13 - abs(dx) // 2
        if abs(dx) >= 6:
            fill(x + dx, g, z, x + dx, arch_y, z + 2, "mossy_stone_bricks")
        else:
            fill(x + dx, arch_y, z, x + dx, arch_y + 1, z + 2, "calcite")
    for dx in (-9, 9):
        fill(x + dx, g, z + 4, x + dx, g + 3, z + 4, "chiseled_stone_bricks")
        setb(x + dx, g + 4, z + 4, "soul_lantern")
    reserve(x, z, 15)
    POI.append(("Брама Джерел", x, z))
    if not DUNGEONS_OPEN:   # v10: брама зачинена ґратами
        for dx in range(-5, 6): fill(x + dx, g, z + 1, x + dx, g + 12 - abs(dx) // 2, z + 1, "iron_bars")
    sign(x + 12, z + 5, "Брама Джерел", "Русло Джерел", "Lv 12-16", "макет входу" if DUNGEONS_OPEN else "зачинено")
spring_gate(*SPRING_GATE)
log(f"v9: струмок {RIVER_SURF[0]} -> {RIVER_SURF[-1]}, вода {_spring_levels.tolist()}; Брама Джерел {SPRING_GATE}")
# озеро-джерело з острівцем і руїною вежі посередині
_ix, _iz = LAKE_C[0] + 8, LAKE_C[1] - 2; _g = top_at(_ix, _iz)
for k in range(24):
    a_ = k / 24 * 6.283; px, pz = int(round(_ix + 3.5 * math.cos(a_))), int(round(_iz + 3.5 * math.sin(a_)))
    fill(px, _g, pz, px, _g + int(8 + 9 * abs(math.sin(a_ * 1.5))), pz, rng.choice(("mossy_stone_bricks", "stone_bricks", "cracked_stone_bricks")))
setb(_ix, _g + 1, _iz, "soul_campfire"); oak(_ix + 5, _iz + 4, "cherry")
POI.append(("Озеро Джерел", *LAKE_C)); sign(LAKE_C[0] - 66, LAKE_C[1] + 6, "Озеро Джерел", "тут народжуються річки")
# пара там, де лава прорізає сніговий комір вулкана
_pz, _px = np.nonzero(LAVASTREAM & np.roll(VCOLLAR, 2, 1))
for k_ in range(0, _pz.size, max(1, _pz.size // 16)):
    x, z = int(_px[k_]) + 2, int(_pz[k_])
    if 0 < x < SIZE and cat[z, x] == SNOW: setb(x, top_at(x, z) + 1, z, "campfire[signal_fire=true]")
# 4. Вхід у лавові глибини (підземелля)
DX, DZ = S(*DUNGEON)
fy = top_at(DX, DZ)
for dx in range(-5, 6):
    for y in range(0, 9):
        if abs(dx) == 5 or y == 8 or (abs(dx) == 4 and y == 7): setb(DX + dx, fy + y, DZ - 3, rng.choice(("polished_blackstone_bricks", "cracked_polished_blackstone_bricks")))
for dx in (-5, 5): setb(DX + dx, fy + 9, DZ - 3, "soul_lantern")
fill(DX - 4, fy, DZ - 4, DX + 4, fy + 7, DZ + 4, "air")
if not DUNGEONS_OPEN: fill(DX - 4, fy, DZ - 3, DX + 4, fy + 6, DZ - 3, "iron_bars")   # v10: хід зачинено
poi("Чорний хід у глибини", DX, DZ, "Чорний хід", "у лавові глибини", sign_dx=7, sign_dz=-2)
# v6: вхід у «Лавові глибини» через кратер вулкана (план 16, розд. 17): каньйон, над яким сходяться чорні шипи-ребра,
# міст над лавою, острів із порталом, димові вогнища на краю кратера
(cax, caz), (cbx, cbz) = CAN_A, CAN_B
Lc = math.hypot(cbx - cax, cbz - caz); cux, cuz = (cbx - cax) / Lc, (cbz - caz) / Lc; cnx, cnz = -cuz, cux
for t in np.arange(0.1, 0.97, 0.085):
    px, pz = cax + (cbx - cax) * t, caz + (cbz - caz) * t; fl = int(TOP[int(pz), int(px)])
    for s_ in (-1, 1):
        bx, bz = px + cnx * 8 * s_, pz + cnz * 8 * s_; by = max(top_at(int(bx), int(bz)), fl + 2)
        apex = (px - cnx * 1.5 * s_, fl + 17, pz - cnz * 1.5 * s_)
        curve = bezier3((bx, by - 1, bz), (bx - cnx * s_, by + 6, bz - cnz * s_), (px + cnx * s_ * 3, fl + 19, pz + cnz * s_ * 3), apex, 24)
        for i, (qx, qy, qz) in enumerate(curve):
            sphere(qx, qy, qz, max(0.6, 1.9 * (1 - i / 24) + 0.3), "red_nether_bricks" if i > 20 else rng.choice(("blackstone", "basalt", "polished_blackstone", "blackstone")), ry=0.9)
    if int(t * 100) % 17 < 9:
        lx_, lz_ = int(px + cnx * 3), int(pz + cnz * 3); setb(lx_, top_at(lx_, lz_) + 1, lz_, "polished_blackstone_wall"); setb(lx_, top_at(lx_, lz_) + 2, lz_, "soul_lantern")
# міст над лавою до острова
bend_x = VCX - 10
nb = int(bend_x - cbx)
for k in range(nb + 1):
    x = int(cbx) + k
    fill(x, ISL_Y + 1, VCZ - 2, x, ISL_Y + 4, VCZ + 2, "air")   # v10: край кратера не перекриває вхід на міст
    for o in (-1, 0, 1): setb(x, ISL_Y, VCZ + o, rng.choice(("polished_blackstone_bricks", "polished_blackstone_bricks", "cracked_polished_blackstone_bricks")))
    for o in (-2, 2):
        setb(x, ISL_Y, VCZ + o, "polished_blackstone_bricks")
        if k % 3 == 0: setb(x, ISL_Y + 1, VCZ + o, "polished_blackstone_wall")
        if k % 9 == 0: setb(x, ISL_Y + 2, VCZ + o, "soul_lantern")
    if k % 8 == 4:
        for o in (-2, 2): fill(x, int(TOP[VCZ + o, x]), VCZ + o, x, ISL_Y - 1, VCZ + o, "basalt")
# острів: базальтові стовпи по колу, портал (рамка з плачучого обсидіану, усередині фіолетове скло), вогнища
for k in range(10):
    a = k / 10 * 6.283; px, pz = int(round(VCX + 8 * math.cos(a))), int(round(VCZ + 8 * math.sin(a)))
    if abs(pz - VCZ) <= 2 and px < VCX: continue
    fill(px, ISL_Y - 3, pz, px, ISL_Y + rng.randint(3, 8), pz, rng.choice(("basalt", "blackstone", "polished_basalt")))
for z in range(VCZ - 3, VCZ + 4):
    for yy in range(ISL_Y + 1, ISL_Y + 11):
        edge = abs(z - VCZ) == 3 or yy in (ISL_Y + 1, ISL_Y + 10)
        setb(VCX + 3, yy, z, "crying_obsidian" if edge else "purple_stained_glass")
setb(VCX + 2, ISL_Y + 6, VCZ, "light[level=15]"); setb(VCX + 4, ISL_Y + 6, VCZ, "light[level=15]")
for o in (-5, 5): setb(VCX + 3, ISL_Y + 1, VCZ + o, "soul_campfire")
# димові вогнища по краю кратера
for k in range(9):
    a = k / 9 * 6.283 + 0.2; px, pz = int(VCX + (CR(a) + 4) * math.cos(a)), int(VCZ + (CR(a) + 4) * math.sin(a)); g_ = top_at(px, pz)
    setb(px, g_ + 1, pz, "hay_block"); setb(px, g_ + 2, pz, "campfire[signal_fire=true]")
reserve(VCX, VCZ, 12)
POI.append(("Лавові глибини", VCX, VCZ)); reserve(int(cax), int(caz), 10)
sign(int(cax) + 2, int(caz) + 5, "Лавові глибини", "через кратер", "Lv 12-15" if DUNGEONS_OPEN else "зачинено")
log(f"кратер: острів Y {ISL_Y}, каньйон {int(Lc)} блоків")


# 5-11. Входи в печери
CAVE_NAMES = ["Перша печера", "Грибна печера", "Північна печера", "Стара копальня", "Печера біля порту", "Південна печера", "Морський грот"]
for (cx_, cz_), nm in zip([], CAVE_NAMES):   # v9: старі входи печер прибрано (план 16, розд. 28)
    x, z = S(cx_, cz_); y = top_at(x, z)
    for k in range(18):
        a = k / 18 * 6.283; px, pz = int(x + 5 * math.cos(a)), int(z + 5 * math.sin(a))
        fill(px, top_at(px, pz), pz, px, top_at(px, pz) + rng.randint(0, 3), pz, rng.choice(("mossy_cobblestone", "cobblestone", "stone", "andesite")))
    fill(x - 3, y - 1, z - 3, x + 3, y + 5, z + 3, "air")
    lamp(x + 6, z - 2, 2); lamp(x - 6, z + 2, 2)
    poi(nm, x, z, nm, "вхід у печери", sign_dx=7, sign_dz=4, r=9)
# копальня: копер над входом
MX, MZ = S(750, 760); y = top_at(MX, MZ)
for a, b in ():   # v9: копер старої копальні прибрано
    pass
if False:   # v9: копер прибрано
    fill(MX - 4, y + 10, MZ - 4, MX + 4, y + 10, MZ + 4, "spruce_planks"); fill(MX - 1, y + 11, MZ, MX + 1, y + 11, MZ, "spruce_fence")
    for k in range(6): setb(MX + 8 + k, top_at(MX + 8 + k, MZ) + 1, MZ, "rail[shape=east_west]")

# 12. Сторожова вежа на пагорбах
x, z = S(330, 300); tower(x, z, r=4, hgt=24)
poi("Сторожова вежа", x, z, "Сторожова вежа", "оглядовий пункт", sign_dx=7, sign_dz=7)
# 13. Табір розбійників
x, z = S(700, 360)
for dx, dz, c in ((-8, 0, "red"), (8, 2, "black"), (0, -9, "red"), (0, 9, "brown")): tent(x + dx, z + dz, c)
campfire(x, z)
for k in range(12):
    a = k / 12 * 6.283; px, pz = int(x + 16 * math.cos(a)), int(z + 16 * math.sin(a)); y = top_at(px, pz)
    fill(px, y + 1, pz, px, y + 3, pz, "spruce_fence");
    if k % 3 == 0: setb(px, y + 4, pz, "skeleton_skull")
poi("Табір розбійників", x, z, "Табір розбійників", "Lv 12", sign_dx=18, sign_dz=0)
# 14. Кладовище з каплицею
x, z = S(395, 820)
fy = house(x, z - 10, 7, 11, ("stone_bricks", "stone_bricks", "stone_bricks", ("stone_brick_stairs", "stone_bricks")), hgt=6)
fill(x, fy + 7, z - 15, x, fy + 12, z - 15, "stone_bricks"); setb(x, fy + 13, z - 15, "bell")
for i in range(5):
    for j in range(3):
        gx, gz = x - 8 + i * 4, z + 3 + j * 4; y = top_at(gx, gz)
        setb(gx, y + 1, gz, rng.choice(("mossy_cobblestone_wall", "cobblestone_wall", "stone_brick_wall"))); setb(gx, y, gz + 1, "coarse_dirt")
hollow(x - 11, top_at(x, z) + 1, z - 17, x + 11, top_at(x, z) + 1, z + 15, "iron_bars")
poi("Кладовище", x, z, "Кладовище", "і каплиця", sign_dx=13, sign_dz=0, r=20)
# 15. Кам'яне коло
x, z = S(365, 495)
for k in range(12):
    a = k / 12 * 6.283; px, pz = int(x + 10 * math.cos(a)), int(z + 10 * math.sin(a)); y = top_at(px, pz)
    fill(px, y, pz, px + 1, y + rng.randint(4, 6), pz + 1, rng.choice(("stone", "andesite", "mossy_cobblestone")))
fill(x - 1, top_at(x, z), z - 1, x + 1, top_at(x, z) + 1, z + 1, "chiseled_stone_bricks"); setb(x, top_at(x, z) + 2, z, "amethyst_cluster")
poi("Кам'яне коло", x, z, "Кам'яне коло", "давнє святилище", sign_dx=13, sign_dz=3)
# 16. Хатина відьми на палях біля річки
x, z = S(905, 655); y = max(top_at(x, z), SEA) + 3
for a, b in ((-3, -3), (3, -3), (-3, 3), (3, 3)): fill(x + a, top_at(x + a, z + b) - 2, z + b, x + a, y, z + b, "dark_oak_log")
fill(x - 3, y, z - 3, x + 3, y, z + 3, "spruce_planks"); hollow(x - 3, y + 1, z - 3, x + 3, y + 3, z + 3, "dark_oak_planks")
fill(x, y + 1, z + 3, x, y + 2, z + 3, "air"); setb(x, y + 1, z, "cauldron"); setb(x + 2, y + 1, z - 2, "brewing_stand")
gable_roof(x - 3, z - 3, x + 3, z + 3, y + 4, ("dark_oak_stairs", "dark_oak_planks"))
for k in range(6): setb(x - 2 + k, top_at(x - 2 + k, z + 6) + 1, z + 6, rng.choice(("brown_mushroom", "red_mushroom", "fern")))
poi("Хатина відьми", x, z, "Хатина відьми", "зілля й квести", sign_dx=-6, sign_dz=7)
# 17. Мисливський табір у лісі
x, z = S(720, 650)
tent(x - 5, z, "brown"); tent(x + 5, z, "green"); campfire(x, z + 5)
for k in range(3): fill(x - 8 + k * 3, top_at(x, z + 9) + 1, z + 9, x - 8 + k * 3, top_at(x, z + 9) + 3, z + 9, "spruce_fence")
poi("Мисливський табір", x, z, "Мисливський табір", "Lv 8", sign_dx=0, sign_dz=-8)
# 18. v10: поселення ельфів на деревах (правка користувача, знімки на кшталт Alekin; нотатка 23 §16 — дослідження Codex).
# Не столиця: зала в кроні Великого Дерева, оселі на розвилках п'яти сусідніх дерев, гвинтові сходи навколо стовбурів,
# містки між кронами, гриби-полиці, ліхтарі на ланцюгах; унизу утоптаний майдан і три будиночки біля коріння.
TX_, TZ_ = ELF_TREE
VG = top_at(TX_, TZ_) + 1                     # перший блок повітря над землею
KEEP = set()                                  # прохід гравця й сходинки: сюди не ставимо ні листя, ні декору
VCHK = []                                     # точки маршруту для автоперевірки прохідності
WOODS = ("dark_oak_wood", "dark_oak_wood", "spruce_wood", "dark_oak_wood", "stripped_dark_oak_wood")
def vset(x, y, z, st):
    x, y, z = int(x), int(y), int(z)
    if (x, y, z) not in KEEP: setb(x, y, z, st)
def angd(a, b): return abs((a - b + math.pi) % (2 * math.pi) - math.pi)
def v_limb(pts, r0, r1, st):
    n = len(pts)
    for i, (x, y, z) in enumerate(pts):
        r = r0 + (r1 - r0) * i / max(n - 1, 1); R = int(math.ceil(r))
        for dx in range(-R, R + 1):
            for dy in range(-R, R + 1):
                for dz in range(-R, R + 1):
                    if dx * dx + dy * dy + dz * dz <= r * r + 0.3: vset(round(x) + dx, round(y) + dy, round(z) + dz, st)
def v_trunk(x, z, r0, r1, H, g):
    for y in range(g - 3, g + H):
        f = max(0.0, (y - g) / H); r = r0 + (r1 - r0) * f + 1.6 * max(0.0, (g + 2 - y) / 5)
        R = int(math.ceil(r)) + 1
        for dx in range(-R, R + 1):
            for dz in range(-R, R + 1):
                if math.hypot(dx, dz) <= r + 0.35 * math.sin(3 * math.atan2(dz, dx) + y * 0.25):
                    setb(x + dx, y, z + dz, WOODS[(dx * 3 + dz * 5 + y) % len(WOODS)])
def v_roots(x, z, r, g, skip_a):
    for k in range(7):
        a = k / 7 * 6.283 + 0.2
        if angd(a, skip_a) < 0.8: continue
        L = r + rng.uniform(4, 7); c, s = math.cos(a), math.sin(a)
        limb(bezier3((x + r * 0.7 * c, g + 4, z + r * 0.7 * s), (x + (r + 1) * c, g + 1, z + (r + 1) * s), (x + L * 0.8 * c, g, z + L * 0.8 * s), (x + L * c, g - 1, z + L * s), 12), 1.8, 0.7, "dark_oak_wood")
def spiral_start(r_in, g, y_top, a_end): return a_end - (y_top - g + 1) / (r_in + 1.0)
def v_spiral(x, z, r_in, g, y_top, a_end, name):
    """Гвинтові сходи в 3 смуги від землі до рівня y_top: 1 блок підйому на 1 блок дуги, перила й ліхтарі зовні."""
    rm = r_in + 1.0; n = y_top - g + 1; a0 = a_end - n / rm; stairs = {}
    for k in range(n):
        for sub in range(4):
            a = a0 + (k + sub / 4) / rm; c, s = math.cos(a), math.sin(a); fac = card(-s, c)
            for r in (r_in, r_in + 1, r_in + 2):
                stairs.setdefault((int(round(x + r * c)), g + k, int(round(z + r * s))), f"spruce_stairs[facing={fac}]")
            if sub == 0:
                rx, rz = int(round(x + (r_in + 3) * c)), int(round(z + (r_in + 3) * s))
                setb(rx, g + k, rz, "spruce_slab[type=top]"); setb(rx, g + k + 1, rz, "spruce_fence")
                if k % 7 == 3: setb(rx, g + k + 2, rz, "lantern")
                VCHK.append((name, int(round(x + (r_in + 1) * c)), int(round(z + (r_in + 1) * s)), g + k + 1))
    for (sx, sy, sz), st in stairs.items():
        setb(sx, sy, sz, st)
        if (sx, sy - 1, sz) not in stairs and sy > g: setb(sx, sy - 1, sz, "spruce_slab[type=top]")
    for (sx, sy, sz) in stairs:                   # голова вільна: прорізаємо кору; сходинки більше нічим не перекрити
        KEEP.add((sx, sy, sz))
        for h in (1, 2, 3):
            if (sx, sy + h, sz) not in stairs: KEEP.add((sx, sy + h, sz)); setb(sx, sy + h, sz, "air")
def v_shelf(x, z, r_tr, y, a, w=3, red=False):
    """Гриб-полиця на стовбурі: напівдиск назовні, під краєм ніжка."""
    c, s = math.cos(a), math.sin(a)
    for dx in range(-w - r_tr - 1, w + r_tr + 2):
        for dz in range(-w - r_tr - 1, w + r_tr + 2):
            d = math.hypot(dx, dz)
            if r_tr - 0.5 < d <= r_tr + w and (dx * c + dz * s) > d * 0.55:
                vset(x + dx, y, z + dz, "red_mushroom_block" if red else "brown_mushroom_block")
                if d > r_tr + w - 1.2 and not red: vset(x + dx, y - 1, z + dz, "mushroom_stem")
def v_hang(x, y, z, L):                          # ліхтар на ланцюгу
    for k in range(L): vset(x, y - k, z, "chain")
    vset(x, y - L, z, "lantern[hanging=true]")
def v_lobe(cx, cy, cz, rx, ry, kinds, avoid):
    R, Ry = int(rx) + 1, int(ry) + 1
    for dx in range(-R, R + 1):
        for dz in range(-R, R + 1):
            for dy in range(-Ry, Ry + 1):
                q = (dx * dx + dz * dz) / (rx * rx) + dy * dy / (ry * ry)
                if q > 1 or (q > 0.6 and rng.random() < 0.3): continue
                x, y, z = int(cx + dx), int(cy + dy), int(cz + dz)
                if avoid(x, y, z): continue
                vset(x, y, z, leaves(kinds[(dx * 7 + dz * 3 + dy * 5 + int(cx)) % len(kinds)]))
def v_platform(x, z, y, R, r_tr, gaps):
    for k in range(6):                             # підкоси до стовбура: опори видно знизу
        a = k / 6 * 6.283 + 0.3; c, s = math.cos(a), math.sin(a)
        v_limb(bezier3((x + r_tr * c, y - 7, z + r_tr * s), (x + (r_tr + 2) * c, y - 4, z + (r_tr + 2) * s), (x + (R - 2) * c, y - 2, z + (R - 2) * s), (x + (R - 0.5) * c, y - 1, z + (R - 0.5) * s), 12), 0.9, 0.6, "stripped_dark_oak_log")
    for dx in range(-R, R + 1):
        for dz in range(-R, R + 1):
            d = math.hypot(dx, dz)
            if d > R + 0.4 or d <= r_tr + 0.4: continue
            vset(x + dx, y, z + dz, "spruce_planks" if (dx + dz) % 5 else "stripped_spruce_log")
            if d > R - 0.6 and all(angd(math.atan2(dz, dx), ga) > 0.3 for ga in gaps): vset(x + dx, y + 1, z + dz, "spruce_fence")
def v_house(cx, cz, y, w, d, face, roof="red", trunk=None, inside=("barrel", "potted_fern", "crafting_table")):
    """Оселя: ялиновий каркас, дубове заповнення, вікна, двері в бік face; дах — червона чи коричнева грибна шапка."""
    x0, x1, z0, z1 = cx - w // 2, cx + w // 2, cz - d // 2, cz + d // 2
    in_tr = lambda x, z: trunk is not None and math.hypot(x - trunk[0], z - trunk[1]) <= trunk[2] + 0.5
    fdx, fdz = DV[face]; door = (cx + fdx * (w // 2), cz + fdz * (d // 2))
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if in_tr(x, z): continue
            vset(x, y, z, "spruce_planks")
            edge = x in (x0, x1) or z in (z0, z1); corner = x in (x0, x1) and z in (z0, z1)
            for yy in range(y + 1, y + 5):
                if not edge: vset(x, yy, z, "air"); continue
                if (x, z) == door and yy <= y + 2: setb(x, yy, z, "air"); KEEP.add((x, yy, z)); continue
                post = corner or (z in (z0, z1) and (x - x0) % 3 == 0) or (x in (x0, x1) and (z - z0) % 3 == 0)
                win = not post and yy in (y + 2, y + 3) and (x, z) != door
                vset(x, yy, z, "stripped_spruce_log" if post else "glass_pane" if win else "spruce_planks" if yy == y + 4 else "oak_planks")
            vset(x, y + 5, z, "spruce_planks")
    for k in (1, 2): KEEP.add((door[0] + fdx, y + k, door[1] + fdz)); setb(door[0] + fdx, y + k, door[1] + fdz, "air")
    for k, (ix, iz) in enumerate(((x0 + 1, z0 + 1), (x1 - 1, z0 + 1), (x0 + 1, z1 - 1))):
        if not in_tr(ix, iz) and k < len(inside): vset(ix, y + 1, iz, inside[k])
    lx, lz = (cx, cz) if not in_tr(cx, cz) else (door[0] - fdx * 2, door[1] - fdz * 2)
    vset(lx, y + 4, lz, "lantern[hanging=true]")
    rr = max(w, d) / 2 + 2.2
    for ddx in range(-int(rr) - 1, int(rr) + 2):
        for ddz in range(-int(rr) - 1, int(rr) + 2):
            r2 = math.hypot(ddx, ddz)
            if r2 > rr or in_tr(cx + ddx, cz + ddz): continue
            if roof == "red":                       # купол червоного гриба
                h = int(round(4.5 * math.sqrt(max(0.0, 1 - (r2 / rr) ** 2))))
                for k in range(max(0, h - 1), h + 1): vset(cx + ddx, y + 5 + k, cz + ddz, "red_mushroom_block")
            else:                                   # парасолька коричневого гриба з опущеним краєм
                vset(cx + ddx, y + 7 - (1 if r2 > rr - 1.3 else 0), cz + ddz, "brown_mushroom_block")
    if roof != "red":
        for ddx in range(-1, 2):
            for ddz in range(-1, 2):
                if not in_tr(cx + ddx, cz + ddz): vset(cx + ddx, y + 6, cz + ddz, "mushroom_stem")
def v_bridge(a, b, name):
    """Місток шириною 3 між двома майданчиками; a, b = (x, y, z) — рівень настилу; підйом сходинками, перила, ліхтарі."""
    if a[1] > b[1]: a, b = b, a
    (ax, ay, az), (bx, by, bz) = a, b
    L = math.hypot(bx - ax, bz - az); n = max(2, int(L * 3)); ux, uz = (bx - ax) / L, (bz - az) / L; px, pz = -uz, ux
    fac = card(ux, uz); cells = {}; last = {o: ay for o in (-1, 0, 1)}
    for i in range(n + 1):
        t = i / n; cx, cz = ax + (bx - ax) * t, az + (bz - az) * t
        y = ay + int(round((by - ay) * min(1.0, t * 1.15)))      # останній відрізок рівний
        for o in (-1, 0, 1):
            x, z = int(round(cx + px * o)), int(round(cz + pz * o))
            if (x, z) in cells: continue
            yy = min(y, last[o] + 1)
            cells[(x, z)] = (yy, f"spruce_stairs[facing={fac}]" if yy > last[o] else "spruce_planks"); last[o] = yy
        for o in (-2, 2):
            x, z = int(round(cx + px * o)), int(round(cz + pz * o)); vset(x, y + 1, z, "spruce_fence")
            if i % 24 == 12: vset(x, y + 2, z, "lantern")
        if i % 3 == 0: VCHK.append((name, int(round(cx)), int(round(cz)), last[0] + 1))
    for (x, z), (y, st) in cells.items():
        setb(x, y, z, st); KEEP.add((x, y, z))
        for h in (1, 2, 3): KEEP.add((x, y + h, z)); setb(x, y + h, z, "air")
    mx, mz = int((ax + bx) / 2), int((az + bz) / 2); v_hang(mx, (ay + by) // 2 - 1, mz, 2)

log("v10: поселення ельфів на деревах")
YH = VG + 28                                     # підлога зали в кроні
TR_MAIN = 6
A_MAIN_END = math.pi + 29 / 8.0                   # сходи Дерева починаються із заходу (від брами) і виходять у залу
LIVING = [(0.35, 38, 20, "red"), (1.45, 40, 24, "brown"), (2.45, 37, 19, "red"), (3.95, 39, 22, "brown"), (5.0, 38, 25, "red")]
TREES_L = [(int(TX_ + d * math.cos(a)), int(TZ_ + d * math.sin(a)), a, VG + hp, roof) for a, d, hp, roof in LIVING]
DOORS = [math.atan2(tz - TZ_, tx - TX_) for tx, tz, *_ in TREES_L]
reserve(TX_, TZ_, 62)
for tx, tz, *_ in TREES_L: reserve(tx, tz, 14)
# --- майдан під кронами: утоптана земля, плями трави й моху (як на знімках) ---
paint_street([(TX_ + ox, TZ_ + oz) for ox in range(-33, 34) for oz in range(-33, 34) if math.hypot(ox, oz) <= 33 - 3 * rng.random()],
             ("coarse_dirt", "dirt_path", "rooted_dirt", "packed_mud", "coarse_dirt", "grass_block", "moss_block", "dirt_path"), 0)
# --- Велике Дерево: стовбур, корені, сходи, зала ---
a0_main = spiral_start(7, VG, YH, A_MAIN_END)
v_trunk(TX_, TZ_, TR_MAIN, 3.0, YH - VG + 16, VG)
v_roots(TX_, TZ_, TR_MAIN, VG, a0_main)
for k in range(6):                                # опорні гілки під залою
    a = k / 6 * 6.283 + 0.5; c, s = math.cos(a), math.sin(a)
    v_limb(bezier3((TX_ + 3 * c, YH - 10, TZ_ + 3 * s), (TX_ + 6 * c, YH - 6, TZ_ + 6 * s), (TX_ + 10 * c, YH - 3, TZ_ + 10 * s), (TX_ + 13 * c, YH - 1, TZ_ + 13 * s), 16), 1.4, 0.7, "dark_oak_wood")
v_spiral(TX_, TZ_, 7, VG, YH, A_MAIN_END, "сходи Великого Дерева")
in_main = lambda x, z, y: math.hypot(x - TX_, z - TZ_) <= TR_MAIN - 3.0 * (y - VG) / (YH - VG + 16) + 0.6
for dx in range(-18, 19):                         # підлога зали й балкон
    for dz in range(-18, 19):
        d = math.hypot(dx, dz); x, z = TX_ + dx, TZ_ + dz
        if d > 17.5 or in_main(x, z, YH): continue
        vset(x, YH, z, "spruce_planks" if d <= 14.5 else "stripped_spruce_log" if (dx + dz) % 4 == 0 else "spruce_planks")
        a = math.atan2(dz, dx); door = min(angd(a, da) for da in DOORS) < 0.12
        if 13.5 < d <= 14.5:                      # стіни зали з каркасом, вікнами й дверима до містків
            post = int((a + math.pi) / (math.pi / 8) * 3) % 3 == 0
            for yy in range(YH + 1, YH + 7):
                if door and yy <= YH + 3: setb(x, yy, z, "air"); KEEP.add((x, yy, z)); continue
                win = not post and YH + 2 <= yy <= YH + 4
                vset(x, yy, z, "stripped_dark_oak_log" if post else "glass_pane" if win else "spruce_planks")
        elif d <= 13.5:
            for yy in range(YH + 1, YH + 7):
                if not in_main(x, z, yy): vset(x, yy, z, "air")
        elif d > 16.6 and min(angd(a, da) for da in DOORS) > 0.19: vset(x, YH + 1, z, "spruce_fence")
        if d <= 16: vset(x, YH + 7, z, "spruce_planks" if d <= 14.5 else "spruce_slab")   # стеля й виступ даху
for k in range(16):                               # всередині: лавки, ліхтарі на ланцюгах, книги, килим
    a = k / 16 * 6.283; c, s = math.cos(a), math.sin(a)
    free_a = angd(a, A_MAIN_END) > 0.8 and min(angd(a, da) for da in DOORS) > 0.3
    if k % 2 == 0: v_hang(int(round(TX_ + 10 * c)), YH + 6, int(round(TZ_ + 10 * s)), 1)
    if free_a:
        vset(int(round(TX_ + 12 * c)), YH + 1, int(round(TZ_ + 12 * s)), f"spruce_stairs[facing={card(c, s)}]")
        if k % 4 == 1:
            for yy in (YH + 1, YH + 2): vset(int(round(TX_ + 13 * c)), yy, int(round(TZ_ + 13 * s)), "bookshelf")
for dx in range(-9, 10):
    for dz in range(-9, 10):
        if 10.5 < math.hypot(dx, dz) <= 11.5: vset(TX_ + dx, YH + 1, TZ_ + dz, "green_carpet")
# крона над залою: п'ять лопатей і верх; прорізи між лопатями
avoid_hall = lambda x, y, z: math.hypot(x - TX_, z - TZ_) <= 18 and YH - 1 <= y <= YH + 8
for k in range(7):
    a = k / 7 * 6.283 + 0.6; c, s = math.cos(a), math.sin(a)
    v_limb(bezier3((TX_, YH + 9, TZ_), (TX_ + 4 * c, YH + 11, TZ_ + 4 * s), (TX_ + 9 * c, YH + 13, TZ_ + 9 * s), (TX_ + 17 * c, YH + 14, TZ_ + 17 * s), 18), 1.6, 0.7, "dark_oak_wood")
    v_lobe(TX_ + 19 * c, YH + 16, TZ_ + 19 * s, 12.5, 6.5, ("oak", "dark_oak", "azalea", "oak", "flowering_azalea"), avoid_hall)
    for j in range(3): v_hang(int(round(TX_ + (20 + 3 * j) * math.cos(a + 0.3 * j))), YH + 10, int(round(TZ_ + (20 + 3 * j) * math.sin(a + 0.3 * j))), 3 + j)
v_lobe(TX_, YH + 21, TZ_, 17, 7, ("oak", "dark_oak", "azalea", "oak"), avoid_hall)
for k in range(7):                                # гриби-полиці на стовбурі
    v_shelf(TX_, TZ_, TR_MAIN + 1, VG + 5 + k * 3, k * 2.2 + 0.7, w=3 if k % 2 else 2, red=(k % 3 == 2))
poi("Велетенське дерево", TX_, TZ_, "Велике Дерево", "зала в кроні", "Lv 22-28", sign_dx=-10, sign_dz=4, r=30)
POI.append(("Зала в кроні", TX_ + 11, TZ_))
# --- житлові дерева: оселя на майданчику, містки до зали й між сусідами ---
LINKS = [(0, 1), (3, 4)]
for i, (tx, tz, a, yp, roof) in enumerate(TREES_L):
    g = top_at(tx, tz) + 1; c, s = math.cos(a), math.sin(a)
    to_c = a + math.pi
    gaps = [to_c] + [math.atan2(TREES_L[j][1] - tz, TREES_L[j][0] - tx) for l in LINKS for j in l if i in l and j != i]
    stair = i in (0, 2, 4)
    a_end = to_c + 1.2; a0 = spiral_start(4, g, yp, a_end)
    v_trunk(tx, tz, 3.2, 1.5, yp - g + 16, g)
    v_roots(tx, tz, 3, g, a0 if stair else a + 1.0)
    if stair: v_spiral(tx, tz, 4, g, yp, a_end, f"сходи дерева {i + 1}")
    v_platform(tx, tz, yp, 8, 2.0, gaps)
    hx, hz = int(round(tx + 3.5 * c)), int(round(tz + 3.5 * s))
    v_house(hx, hz, yp, 7, 7, card(math.cos(a + 1.57), math.sin(a + 1.57)), roof, trunk=(tx, tz, 2.0))
    avoid_l = lambda x, y, z, tx=tx, tz=tz, yp=yp: math.hypot(x - tx, z - tz) <= 10 and yp - 1 <= y <= yp + 10
    for k in range(4):
        b = k / 4 * 6.283 + i; v_lobe(tx + 5 * math.cos(b), yp + 13, tz + 5 * math.sin(b), 7.5, 4, ("oak", "azalea", "dark_oak", "flowering_azalea"), avoid_l)
    v_lobe(tx, yp + 16, tz, 7, 3.5, ("oak", "azalea", "dark_oak"), avoid_l)
    for k in range(3): v_hang(int(round(tx + 7 * math.cos(to_c + k * 0.9 - 0.9))), yp - 1, int(round(tz + 7 * math.sin(to_c + k * 0.9 - 0.9))), 2)
    v_shelf(tx, tz, 3, g + 5, a + 2.5, w=2); v_shelf(tx, tz, 3, g + 9, a - 2.0, w=2, red=True)
    v_bridge((TX_ + 17 * math.cos(DOORS[i]), YH, TZ_ + 17 * math.sin(DOORS[i])), (tx - 7.6 * c, yp, tz - 7.6 * s), f"місток до дерева {i + 1}")
    POI.append((f"Оселя на дереві {i + 1}", tx, tz))
for i, j in LINKS:
    (ax_, az_, _, ay_, _), (bx_, bz_, _, by_, _) = TREES_L[i], TREES_L[j]
    L_ = math.hypot(bx_ - ax_, bz_ - az_); ux_, uz_ = (bx_ - ax_) / L_, (bz_ - az_) / L_
    v_bridge((ax_ + 7.6 * ux_, ay_, az_ + 7.6 * uz_), (bx_ - 7.6 * ux_, by_, bz_ - 7.6 * uz_), f"місток {i + 1}–{j + 1}")
# --- біля коріння: кузня, комора, кухня; ятки; ліхтарі; квіти ---
for a, nm, roof, inside in ((1.95, "Кузня", "brown", ("anvil", "blast_furnace", "barrel")), (4.45, "Комора", "red", ("barrel", "barrel", "chest")),
                            (5.75, "Кухня", "brown", ("smoker", "barrel", "composter"))):
    hx, hz = int(TX_ + 22 * math.cos(a)), int(TZ_ + 22 * math.sin(a))
    fy = platform(hx - 4, hz - 4, hx + 4, hz + 4, "coarse_dirt")
    v_house(hx, hz, fy - 1, 7, 7, card(-math.cos(a), -math.sin(a)), roof, inside=inside)
    POI.append((f"{nm} ельфів", hx, hz))
for j, (a, wool) in enumerate(((2.95, "green"), (3.35, "lime"))): stall(int(TX_ + 19 * math.cos(a)), int(TZ_ + 19 * math.sin(a)), wool)
for k in range(10):
    a = k / 10 * 6.283 + 0.15; lamp(int(TX_ + 25 * math.cos(a)), int(TZ_ + 25 * math.sin(a)))
for _ in range(160):
    a, d = rng.uniform(0, 6.283), rng.uniform(16, 36); fx, fz = int(TX_ + d * math.cos(a)), int(TZ_ + d * math.sin(a))
    if math.hypot(fx - TX_, fz - TZ_) < 14 or any(math.hypot(fx - t[0], fz - t[1]) < 11 for t in TREES_L): continue
    vset(fx, top_at(fx, fz) + 1, fz, rng.choice(("fern", "allium", "lily_of_the_valley", "red_mushroom", "brown_mushroom", "azure_bluet", "fern")))
sign(TX_ - 34, TZ_ + 4, "Поселення ельфів", "живуть у кронах", "Lv 22-28")
# --- v10: довкола поселення — городи, сад, пасіка, ягідник і поле (правка користувача: «місто пусте, подокидувати сади й ферми») ---
CROPS = ("carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]", "wheat[age=7]")
def elf_plot(cx, cz, kind, rx=13, rz=10):
    reserve(cx, cz, max(rx, rz) + 5)
    for dx in range(-rx - 1, rx + 2):
        for dz in range(-rz - 1, rz + 2):
            q = (dx / (rx + 0.5)) ** 2 + (dz / (rz + 0.5)) ** 2; x, z = cx + dx, cz + dz; g = top_at(x, z)
            if q > 1.08: continue
            if q > 0.86:                                           # тин із двома хвіртками
                if abs(dx) > 1 and abs(dz) > 1: setb(x, g + 1, z, "spruce_fence")
                continue
            fill(x, g + 1, z, x, g + 4, z, "air")
            if abs(dx) <= 1 or abs(dz) <= 0: setb(x, g, z, rng.choice(("dirt_path", "moss_block", "dirt_path"))); continue   # стежки хрестом
            if kind in ("veg", "wheat"):
                if dz % 4 == 0: setb(x, g, z, "water"); continue
                setb(x, g, z, "farmland[moisture=7]")
                setb(x, g + 1, z, "wheat[age=7]" if kind == "wheat" else CROPS[(dz // 4 + dx // 5) % 3])
            elif kind == "orchard":
                setb(x, g, z, rng.choice(("grass_block", "grass_block", "moss_block")))
                if dx % 5 == 0 and dz % 5 == 0: oak(x, z, rng.choice(("oak", "birch")))
                elif rng.random() < 0.12: setb(x, g + 1, z, rng.choice(("flowering_azalea", "azalea", "lily_of_the_valley")))
            elif kind == "bees":
                setb(x, g, z, "grass_block")
                if rng.random() < 0.55: setb(x, g + 1, z, rng.choice(("allium", "cornflower", "azure_bluet", "oxeye_daisy", "pink_tulip", "lily_of_the_valley")))
                if dx % 6 == 0 and dz % 5 == 0: setb(x, g + 1, z, "spruce_fence"); setb(x, g + 2, z, "beehive[facing=south]")
            elif kind == "berries":
                setb(x, g, z, "grass_block")
                if dz % 3 != 0: setb(x, g + 1, z, "sweet_berry_bush[age=3]")
    for sx, sz in ((cx - rx, cz - rz), (cx + rx, cz + rz)): lamp(sx, sz)
    return cx, cz
PLOTS = [(0.05, 60, "veg", "Городи"), (1.15, 72, "wheat", "Поле"), (2.05, 60, "orchard", "Сад"), (4.2, 60, "bees", "Пасіка"), (5.3, 62, "berries", "Ягідник")]
for a, d, kind, nm in PLOTS:
    px_, pz_ = int(TX_ + d * math.cos(a)), int(TZ_ + d * math.sin(a))
    elf_plot(px_, pz_, kind, rx=16 if kind == "wheat" else 12, rz=11 if kind == "wheat" else 9)
    paint_street(resample([(TX_ + 34 * math.cos(a), TZ_ + 34 * math.sin(a)), (px_, pz_)]), ("dirt_path", "moss_block", "coarse_dirt"), 1)
    if kind in ("veg", "wheat"):                                   # повітка з грибним дахом біля городу
        hx, hz = int(px_ + 18 * math.cos(a + 0.6)), int(pz_ + 18 * math.sin(a + 0.6))
        fy = platform(hx - 3, hz - 3, hx + 3, hz + 3, "coarse_dirt"); v_house(hx, hz, fy - 1, 5, 5, card(px_ - hx, pz_ - hz), "brown", inside=("composter", "barrel", "chest"))
    POI.append((f"{nm} ельфів", px_, pz_))
log(f"v10: поселення на деревах — зала Y {YH}, дерев {len(TREES_L)}, містків {len(TREES_L) + len(LINKS)}, точок маршруту {len(VCHK)}")
# вигнанці — навмисно прості намети довкола вогнища
ex_, ez_ = 1012, 768
for j, c_ in enumerate(("brown", "white", "gray", "brown", "white")):
    a = j / 5 * 6.283; tent(int(ex_ + 9 * math.cos(a)), int(ez_ + 9 * math.sin(a)), c_)
campfire(ex_, ez_); reserve(ex_, ez_, 14); POI.append(("Табір вигнанців", ex_, ez_))
log("v10: табір вигнанців")
# 19. Придорожнє святилище
x, z = S(415, 645); fy = platform(x - 3, z - 3, x + 3, z + 3, "stone_bricks")
fill(x - 3, fy - 1, z - 3, x + 3, fy - 1, z + 3, "polished_andesite")
fill(x, fy, z, x, fy + 2, z, "polished_diorite"); setb(x, fy + 3, z, "carved_pumpkin[facing=south]")
for a, b in ((-3, -3), (3, -3), (-3, 3), (3, 3)): setb(x + a, fy, z + b, "candle[candles=3,lit=true]")
poi("Святилище", x, z, "Придорожнє", "святилище", sign_dx=5, sign_dz=0, r=6)
# 20. Будка варти біля мосту
x, z = S(495, 450)
house(x + 10, z, 5, 5, STYLES[0], hgt=3); lamp(x + 6, z + 4)
poi("Пост варти", x + 10, z, "Пост варти", "міст на північ", sign_dx=-4, sign_dz=5, r=6)
# 21. Вежа мага на скелях
x, z = S(190, 830); fy = tower(x, z, r=4, hgt=30, mat="deepslate_bricks", roof=("purpur_stairs", "purpur_block"))
fill(x - 1, fy + 31, z - 1, x + 1, fy + 31, z + 1, "amethyst_block")
poi("Вежа мага", x, z, "Вежа мага", "на скелях", sign_dx=7, sign_dz=-6)
# 22. Розбитий віз на дорозі
x, z = S(600, 505); y = top_at(x, z) + 1
fill(x - 1, y, z - 2, x + 1, y, z + 2, "spruce_slab"); setb(x - 2, y, z - 1, "dark_oak_trapdoor[facing=east,open=true]")
setb(x + 2, y, z + 1, "dark_oak_trapdoor[facing=west,open=true]"); setb(x, y + 1, z, "barrel"); setb(x + 1, y + 1, z - 1, "hay_block")
setb(x - 3, y, z + 3, "barrel[facing=up]")
poi("Розбитий віз", x, z, "Розбитий віз", "міні-квест", sign_dx=4, sign_dz=4, r=6)
# 23. Загін для тварин
x, z = S(290, 610); y0 = top_at(x, z)
for k in range(-9, 10):
    for a, b in ((k, -7), (k, 7)): setb(x + a, top_at(x + a, z + b) + 1, z + b, "oak_fence")
    if -7 <= k <= 7:
        for a, b in ((-9, k), (9, k)): setb(x + a, top_at(x + a, z + b) + 1, z + b, "oak_fence")
setb(x, top_at(x, z - 7) + 1, z - 7, "oak_fence_gate[facing=south]")
for k in range(4): setb(x - 6 + k * 4, top_at(x - 6 + k * 4, z) + 1, z, "hay_block")
setb(x + 6, top_at(x + 6, z + 4) + 1, z + 4, "water_cauldron[level=3]")
poi("Загін", x, z, "Загін для тварин", sign_dx=11, sign_dz=0)
# 24. Корабель, викинутий на берег
x, z = S(470, 935); y = SEA - 2
for k in range(0, 4):
    w = 3 - (1 if k == 0 else 0)
    fill(x - 14 + (3 - k), y + k, z - w, x + 14 - (3 - k), y + k, z + w, rng.choice(("dark_oak_planks", "spruce_planks")))
fill(x - 10, y + 4, z - 2, x + 6, y + 4, z + 2, "air"); fill(x - 3, y + 4, z, x - 3, y + 9, z, "spruce_log")
fill(x + 2, y + 3, z - 2, x + 5, y + 6, z + 2, "air"); setb(x - 6, y + 4, z, "chest")
poi("Розбитий корабель", x, z, "Розбитий корабель", "скарб на березі", sign_dx=0, sign_dz=-8)
# 25. Оглядовий майданчик на кратері
x, z = S(VOLC[0], VOLC[1] - 52); y = top_at(x, z)
fill(x - 4, y, z - 3, x + 4, y, z + 3, "polished_blackstone_bricks"); hollow(x - 4, y + 1, z - 3, x + 4, y + 1, z + 3, "polished_blackstone_wall")
fill(x - 1, y + 1, z + 3, x + 1, y + 1, z + 3, "air"); setb(x - 4, y + 2, z - 3, "soul_lantern"); setb(x + 4, y + 2, z - 3, "soul_lantern")
poi("Край кратера", x, z, "Край кратера", "найвища точка", "острова", sign_dx=0, sign_dz=5, r=6)
# Вогняна вежа на краю кратера: глобальний орієнтир, видно з усього острова, вночі світиться
fx_, fz_ = x + 14, z - 6; g_ = top_at(fx_, fz_)
fill(fx_ - 4, g_ - 3, fz_ - 4, fx_ + 4, g_, fz_ + 4, "blackstone")
for yy in range(1, 34):
    r_ = 4 if yy < 26 else 3
    hollow(fx_ - r_, g_ + yy, fz_ - r_, fx_ + r_, g_ + yy, fz_ + r_, rng.choice(("polished_blackstone_bricks", "polished_blackstone_bricks", "cracked_polished_blackstone_bricks", "blackstone")))
    if yy % 7 == 3:
        for a, b in ((0, -r_), (0, r_), (-r_, 0), (r_, 0)): setb(fx_ + a, g_ + yy, fz_ + b, "magma_block")
fill(fx_ - 5, g_ + 34, fz_ - 5, fx_ + 5, g_ + 34, fz_ + 5, "polished_blackstone_bricks")
hollow(fx_ - 5, g_ + 35, fz_ - 5, fx_ + 5, g_ + 36, fz_ + 5, "polished_blackstone_wall")
fill(fx_ - 2, g_ + 34, fz_ - 2, fx_ + 2, g_ + 34, fz_ + 2, "lava")
for a, b in ((-4, -4), (4, -4), (-4, 4), (4, 4)): setb(fx_ + a, g_ + 35, fz_ + b, "campfire[signal_fire=true]")
poi("Вогняна вежа", fx_, fz_, "Вогняна вежа", "її видно звідусіль", sign_dx=7, sign_dz=7, r=10)
# оглядові майданчики на вершинах хребтів
for k_, (path_, _, _) in enumerate(RIDGES):
    pts = resample(list(world_path(path_)), 2.0)
    ox_, oz_ = max(((int(a), int(b)) for a, b in pts), key=lambda q: top_at(*q))
    g_ = top_at(ox_, oz_) + 1
    fill(ox_ - 3, g_ - 4, oz_ - 3, ox_ + 3, g_ - 1, oz_ + 3, "stone"); fill(ox_ - 3, g_ - 1, oz_ - 3, ox_ + 3, g_ - 1, oz_ + 3, "polished_andesite")
    fill(ox_ - 3, g_, oz_ - 3, ox_ + 3, g_ + 8, oz_ + 3, "air")
    hollow(ox_ - 3, g_, oz_ - 3, ox_ + 3, g_, oz_ + 3, "spruce_fence"); setb(ox_, g_, oz_ + 3, "air")
    setb(ox_ - 1, g_, oz_ - 2, "spruce_stairs[facing=north]"); setb(ox_ + 1, g_, oz_ - 2, "spruce_stairs[facing=north]")
    fill(ox_ + 2, g_, oz_ + 2, ox_ + 2, g_ + 2, oz_ + 2, "spruce_fence"); setb(ox_ + 2, g_ + 3, oz_ + 2, "lantern")
    nm_ = "Оглядовий майданчик " + ("(схід)" if k_ == 0 else "(захід)")
    POI.append((nm_, ox_, oz_)); reserve(ox_, oz_, 8); sign(ox_, oz_ + 5, nm_)
# шипи червоного краю на вулкані: чорні, вищі ближче до кратера, нахилені й вигнуті до нього (кадри 58–72)
_cz, _cx = np.nonzero((corr > 0) & np.isin(cat, [CORRUPT, STONE, BASALT]) & (tC > 1.2) & ~CANYON & ~CANYON_NEAR)
spikes_ = 0
for k_ in nrng.permutation(_cz.size)[:3000]:
    if spikes_ >= 60: break   # v8: менше, але більші (нотатка 24)
    x, z = int(_cx[k_]), int(_cz[k_])
    if reserved(x, z) or dRoad[z, x] < 6 or SLOTMASK[z, x] or STREETMASK[z, x] or LAVASTREAM[z, x]: continue
    g_ = top_at(x, z); c_ = corr[z, x]; hgt = int(10 + c_ * 30 * rng.uniform(0.6, 1.25))
    d_ = math.hypot(x - VCX, z - VCZ) or 1; ux, uz = (VCX - x) / d_, (VCZ - z) / d_
    bend = rng.uniform(0.15, 0.45); jx, jz = rng.uniform(-0.15, 0.15), rng.uniform(-0.15, 0.15)
    for yy in range(hgt):
        f_ = yy / hgt; r_ = max(0.7, (2.2 + c_ * 1.8) * (1 - f_))
        mat_ = "red_nether_bricks" if f_ > 0.82 else rng.choice(("blackstone", "blackstone", "polished_blackstone", "basalt"))
        sphere(x + (ux * bend + jx) * yy * f_, g_ + yy, z + (uz * bend + jz) * yy * f_, r_, mat_, ry=0.8)
    spikes_ += 1
log(f"шипів на вулкані: {spikes_}")
# 26. Курган
x, z = S(245, 340)
sphere(x, top_at(x, z), z, 9, "grass_block", ry=5); sphere(x, top_at(x, z) - 1, z, 8, "dirt", ry=4)
fill(x - 1, top_at(x, z) + 1, z + 7, x + 1, top_at(x, z) + 3, z + 10, "air")
fill(x - 2, top_at(x, z) + 1, z + 10, x + 2, top_at(x, z) + 4, z + 10, "mossy_stone_bricks"); fill(x - 1, top_at(x, z) + 1, z + 10, x + 1, top_at(x, z) + 3, z + 10, "air")
poi("Курган", x, z, "Курган", "давня гробниця", sign_dx=4, sign_dz=13)
# 27. Табір археологів біля ярів
x, z = S(670, 755)
tent(x, z, "white"); tent(x + 7, z + 2, "yellow"); campfire(x + 3, z + 7)
for k in range(5): setb(x - 6 + k, top_at(x - 6 + k, z + 6), z + 6, "gravel" if k % 2 else "sand")
for k in range(3): setb(x - 4, top_at(x - 4, z - 4 + k) + 1, z - 4 + k, "decorated_pot")
poi("Табір археологів", x, z, "Табір археологів", "розкопки", sign_dx=-6, sign_dz=-6)
# 28. Лісопилка біля річки
x, z = S(785, 585); fy = house(x, z, 11, 7, STYLES[0], hgt=5)
for k in range(5): fill(x - 8, fy + k // 2, z + 6 + k % 2, x - 3, fy + k // 2, z + 6 + k % 2, "oak_log[axis=x]")
setb(x + 2, fy, z, "stonecutter")
poi("Лісопилка", x, z, "Лісопилка", sign_dx=0, sign_dz=-8)
# 29. Сад біля села
x, z = S(535, 665)
for i in range(4):
    for j in range(3): oak(x - 12 + i * 8, z - 6 + j * 7, "oak")
for k in range(-14, 15): setb(x + k, top_at(x + k, z - 11) + 1, z - 11, "oak_fence")
poi("Сад", x, z, "Яблуневий сад", sign_dx=0, sign_dz=-13)
# 30. Хатина відлюдника на схилі вулкана
x, z = S(612, 445); house(x, z, 7, 7, ("cobblestone", "spruce_log", "spruce_planks", ("spruce_stairs", "spruce_planks")), hgt=4)
poi("Хатина відлюдника", x, z, "Хатина відлюдника", sign_dx=6, sign_dz=6)
# 31. Вежа розбійників у ярах
x, z = S(795, 835); tower(x, z, r=3, hgt=18, mat="cobblestone", roof=("spruce_stairs", "spruce_planks"))
poi("Вежа в ярах", x, z, "Вежа розбійників", "Lv 15", sign_dx=6, sign_dz=-6)
# 32. Пасіка
x, z = S(300, 470)
for k in range(6): setb(x - 6 + k * 2, top_at(x - 6 + k * 2, z) + 1, z, "beehive[facing=south]")
for k in range(30):
    px, pz = x + rng.randint(-8, 8), z + rng.randint(2, 9); setb(px, top_at(px, pz) + 1, pz, rng.choice(("cornflower", "allium", "oxeye_daisy", "poppy")))
poi("Пасіка", x, z, "Пасіка", sign_dx=0, sign_dz=-4, r=10)
# 33. Рибальський пірс на річці
x, z = S(330, 455); y = WL[z, x] if WL[z, x] > 0 else SEA - 1
fill(x - 1, y + 1, z - 6, x + 1, y + 1, z + 6, "spruce_planks"); setb(x, y + 2, z + 6, "barrel")
poi("Рибальський пірс", x, z, "Рибальський пірс", sign_dx=4, sign_dz=-8, r=7)
# 34. Скеля-арка на сході
x, z = S(955, 560)
for t in range(-12, 13):
    ay = int(SEA + 14 * math.cos(t / 12 * math.pi / 2))
    fill(x + t, ay, z - 3, x + t, ay + 4, z + 3, rng.choice(("stone", "andesite", "stone", "mossy_cobblestone")))
fill(x - 13, SEA - 6, z - 4, x - 9, SEA + 4, z + 4, "stone"); fill(x + 9, SEA - 6, z - 4, x + 13, SEA + 4, z + 4, "stone")
POI.append(("Скеля-арка", x, z))
# 35. Затонулі руїни біля порту
x, z = S(610, 70); y = top_at(x, z)
for k in range(10):
    px, pz = x + rng.randint(-14, 14), z + rng.randint(-10, 10)
    fill(px, top_at(px, pz), pz, px, top_at(px, pz) + rng.randint(1, 5), pz, rng.choice(("prismarine_bricks", "mossy_stone_bricks", "cracked_stone_bricks")))
for k in range(4): setb(x + rng.randint(-10, 10), y + 1, z + rng.randint(-8, 8), "sea_lantern")
POI.append(("Затонулі руїни", x, z))
# 36. Квітучий луг (поле квітів)
x, z = S(400, 540)
for k in range(220):
    px, pz = x + rng.randint(-25, 25), z + rng.randint(-18, 18)
    if is_land(px, pz) and cat_at(px, pz) in (GRASS, MEADOW): setb(px, top_at(px, pz) + 1, pz, rng.choice(("poppy", "dandelion", "cornflower", "azure_bluet", "red_tulip", "pink_tulip", "oxeye_daisy")))
POI.append(("Квітучий луг", x, z))
# 37. Покинутий хутір на північному заході
x, z = S(250, 230); house(x, z, 9, 7, STYLES[4], hgt=4)
for k in range(8): setb(x + rng.randint(-6, 6), top_at(x, z) + 1, z + rng.randint(5, 9), "cobweb")
poi("Покинутий хутір", x, z, "Покинутий хутір", sign_dx=8, sign_dz=0)
# 38. Міст-переправа над ярами
x, z = S(720, 830); y = top_at(x - 20, z) + 2
fill(x - 22, y, z - 1, x + 22, y, z + 1, "spruce_planks"); fill(x - 22, y + 1, z - 2, x + 22, y + 1, z - 2, "spruce_fence"); fill(x - 22, y + 1, z + 2, x + 22, y + 1, z + 2, "spruce_fence")
fill(x - 22, y + 1, z - 1, x + 22, y + 3, z + 1, "air")
poi("Підвісний міст", x, z, "Міст над ярами", sign_dx=-24, sign_dz=4, r=6)
# 39. Табір біля підніжжя гори (з таблиці схеми)
x, z = S(610, 205); tent(x, z, "light_gray"); campfire(x + 5, z)
poi("Табір під горою", x, z, "Табір під горою", sign_dx=0, sign_dz=7, r=8)
# 40. Пам'ятник героєві на площі старту
MNX, MNZ = TX, TZ - 10   # v5: на площі між ятками, не на вулиці до замку
fy = top_at(MNX, MNZ) + 1
fill(MNX - 2, fy, MNZ - 2, MNX + 2, fy, MNZ + 2, "polished_andesite"); fill(MNX, fy + 1, MNZ, MNX, fy + 3, MNZ, "polished_andesite")
setb(MNX, fy + 4, MNZ, "player_head")
POI.append(("Пам'ятник героєві", MNX, MNZ))
# ---------- нові орієнтири (власні задуми за мотивами RPG-карт) ----------
def spot(x, z, r=40, flat=4, clear=10):
    """Найближче вільне й рівне місце біля (x, z)."""
    for k in range(600):
        rr = r * (k / 600) ** 0.5
        a = rng.uniform(0, 6.283); px, pz = int(x + rr * math.cos(a)), int(z + rr * math.sin(a))
        if not (clear < px < SIZE - clear and clear < pz < SIZE - clear): continue
        if not is_land(px, pz) or reserved(px, pz) or dRoad[pz, px] < clear * 0.6 + 3 or dRiver[pz, px] < 9: continue
        area = TOP[pz - clear // 2:pz + clear // 2 + 1, px - clear // 2:px + clear // 2 + 1]
        if area.max() - area.min() <= flat: return px, pz
    return int(x), int(z)
# Меч героя, встромлений у скелю
x, z = spot(*S(430, 585), r=60, flat=6, clear=14); y = top_at(x, z)
sphere(x, y, z, 7, "stone", ry=3.5); sphere(x, y + 1, z, 5.5, "mossy_cobblestone", ry=2.6, chance=0.6)
fill(x - 1, y - 3, z, x + 1, y + 26, z, "iron_block"); fill(x, y + 27, z, x, y + 27, z, "iron_block")
fill(x - 5, y + 27, z - 1, x + 5, y + 28, z + 1, "gold_block"); fill(x - 6, y + 28, z, x - 6, y + 29, z, "gold_block"); fill(x + 6, y + 28, z, x + 6, y + 29, z, "gold_block")
fill(x, y + 29, z, x, y + 35, z, "stripped_dark_oak_log"); fill(x - 1, y + 36, z - 1, x + 1, y + 37, z + 1, "gold_block"); setb(x, y + 38, z, "diamond_block")
for k in range(10): setb(x + rng.randint(-6, 6), top_at(x, z) + 2, z + rng.randint(-6, 6), "candle[candles=2,lit=true]")
poi("Меч героя", x, z, "Меч героя", "легенда острова", sign_dx=9, sign_dz=0, r=12)

# Літаючі острови з водоспадами
def floating_island(x, z, y, r):
    depth = int(r * 1.6)
    for k in range(depth):
        rr = r * (1 - k / depth) ** 0.75 + rng.uniform(-0.6, 0.6)
        disc(x, y - k, z, max(rr, 0.8), "grass_block" if k == 0 else ("dirt" if k < 3 else rng.choice(("stone", "andesite", "stone", "moss_block"))))
    for _ in range(max(1, int(r / 4))):
        a, d = rng.uniform(0, 6.283), rng.uniform(0, r * 0.5)
        oak(int(x + d * math.cos(a)), int(z + d * math.sin(a)), rng.choice(("oak", "birch", "cherry")), y=y + 1)
    for k in range(int(r * 2)):
        a, d = rng.uniform(0, 6.283), rng.uniform(0, r * 0.85)
        setb(int(x + d * math.cos(a)), y + 1, int(z + d * math.sin(a)), rng.choice(("short_grass", "poppy", "cornflower", "dandelion")))
    wx_, wz_ = int(x + r - 1), int(z); g = top_at(wx_, wz_)
    fill(wx_, g + 1, wz_, wx_, y, wz_, "water"); fill(wx_ - 1, y, wz_ - 1, wx_ - 1, y, wz_ + 1, "water")
for (fx_, fz_), fy_, fr in ((S(300, 255), 165, 11), (S(345, 330), 180, 7), (S(255, 330), 150, 6), (S(700, 235), 205, 9)):
    fy_ = max(fy_, top_at(fx_, fz_) + 45)   # не врізатися в гори
    floating_island(fx_, fz_, fy_, fr); reserve(fx_ + int(fr), fz_, 4)
POI.append(("Літаючі острови", *S(300, 255)))
sign(*spot(*S(300, 270), r=30, clear=4), "Літаючі острови", "дивись угору")

# Селище на деревах у лісі
TVX, TVZ = S(590, 650); TVX, TVZ = spot(TVX, TVZ, r=30, flat=8, clear=8)
plats = []
for k in range(5):
    a = k / 5 * 6.283; x, z = int(TVX + 17 * math.cos(a)), int(TVZ + 17 * math.sin(a)); g = top_at(x, z); py = g + 13
    fill(x, g - 1, z, x + 1, py + 9, z + 1, "dark_oak_log")
    sphere(x + 0.5, py + 10, z + 0.5, 6, leaves("dark_oak"), ry=4, chance=0.9)
    disc(x + 0.5, py, z + 0.5, 5, "spruce_planks"); fill(x, py, z, x + 1, py + 3, z + 1, "dark_oak_log")
    for t in range(16):
        b = t / 16 * 6.283; setb(int(round(x + 0.5 + 5.5 * math.cos(b))), py + 1, int(round(z + 0.5 + 5.5 * math.sin(b))), "spruce_fence")
    fill(x - 1, g, z, x - 1, py, z, "ladder[facing=west]"); setb(x + 3, py + 1, z + 3, "lantern")
    if k % 2 == 0:
        hollow(x + 2, py + 1, z - 4, x + 5, py + 3, z - 1, "spruce_planks"); fill(x + 3, py + 1, z - 1, x + 3, py + 2, z - 1, "air")
        gable_roof(x + 2, z - 4, x + 5, z - 1, py + 4, ("dark_oak_stairs", "dark_oak_planks"))
    plats.append((x, z, py)); reserve(x, z, 8)
for (x0_, z0_, y0_), (x1_, z1_, y1_) in zip(plats, plats[1:] + plats[:1]):  # підвісні містки
    n = int(math.hypot(x1_ - x0_, z1_ - z0_))
    for t in range(n + 1):
        px, pz = int(round(x0_ + (x1_ - x0_) * t / n)), int(round(z0_ + (z1_ - z0_) * t / n)); py = int(round(y0_ + (y1_ - y0_) * t / n - math.sin(math.pi * t / n)))
        setb(px, py, pz, "spruce_slab"); setb(px, py + 1, pz, "air")
reserve(TVX, TVZ, 24)
POI.append(("Селище на деревах", TVX, TVZ)); sign(TVX, TVZ, "Селище на деревах", "лісові мисливці")

# Голова колоса, напівзасипана в пагорбі
x, z = spot(*S(420, 280), r=50, flat=10, clear=16); y = top_at(x, z) - 4
fill(x - 7, y - 3, z - 7, x + 7, y + 13, z + 7, "stone")
for a, b, c, d in ((-7, -7, 7, -7), (-7, 7, 7, 7)):
    fill(x + a, y - 3, z + b, x + c, y + 13, z + d, "andesite")
fill(x - 5, y + 7, z + 7, x - 2, y + 9, z + 9, "air"); fill(x + 2, y + 7, z + 7, x + 5, y + 9, z + 9, "air")   # очниці
fill(x - 4, y + 8, z + 6, x - 3, y + 8, z + 6, "shroomlight"); fill(x + 3, y + 8, z + 6, x + 4, y + 8, z + 6, "shroomlight")
fill(x - 1, y + 3, z + 8, x + 1, y + 7, z + 8, "stone"); fill(x - 4, y + 1, z + 8, x + 4, y + 1, z + 8, "air")      # ніс і рот
fill(x - 7, y + 13, z - 7, x + 7, y + 15, z + 7, "stone"); fill(x - 6, y + 16, z - 6, x + 6, y + 16, z + 6, "chiseled_stone_bricks")
sphere(x, y + 15, z, 8, "moss_block", ry=3, chance=0.45); sphere(x - 5, y + 4, z - 4, 4, "mossy_cobblestone", ry=3, chance=0.5)
for k in range(30): setb(x + rng.randint(-7, 7), y + rng.randint(0, 13), z + rng.choice((-8, 8)), "cracked_stone_bricks")
poi("Голова колоса", x, z, "Голова колоса", "давній страж", sign_dx=0, sign_dz=12, r=14)

# Водоспад зі святилищем
x, z = spot(*S(305, 395), r=50, flat=10, clear=16); g = top_at(x, z)
for k in range(6): sphere(x + rng.randint(-6, 6), g + 6 + k * 3, z - 9 + rng.randint(-2, 2), 7 - k * 0.6, rng.choice(("stone", "andesite", "mossy_cobblestone")), ry=4)
fill(x - 1, g + 1, z - 3, x + 1, g + 24, z - 3, "water")
fill(x - 6, g - 3, z - 2, x + 6, g, z + 5, "water"); hollow(x - 7, g - 3, z - 3, x + 7, g, z + 6, "mossy_cobblestone")
fill(x - 6, g - 4, z - 2, x + 6, g - 4, z + 5, "gravel")
for a, b in ((-9, 2), (9, 2), (-9, 8), (9, 8)): fill(x + a, g + 1, z + b, x + a, g + 5, z + b, "quartz_pillar")
fill(x - 10, g + 6, z + 1, x + 10, g + 6, z + 9, "smooth_quartz_slab"); setb(x, g + 1, z + 8, "enchanting_table")
for a in (-3, 3): setb(x + a, g + 1, z + 8, "soul_lantern")
poi("Водоспад", x, z, "Водоспад", "і святилище", sign_dx=12, sign_dz=10, r=16)

# Повітряна куля біля порту
x, z = spot(*S(575, 175), r=40, flat=4, clear=10); g = top_at(x, z)
fill(x - 4, g + 1, z - 4, x + 4, g + 1, z + 4, "spruce_planks")
fill(x - 1, g + 10, z - 1, x + 1, g + 11, z + 1, "spruce_planks"); fill(x, g + 11, z, x, g + 11, z, "air")
for a, b in ((-1, -1), (1, -1), (-1, 1), (1, 1)): fill(x + a, g + 12, z + b, x + a, g + 17, z + b, "spruce_fence")
for k in range(-8, 9):
    rr = math.sqrt(max(0, 64 - k * k)) * 1.0
    disc(x, g + 26 + k, z, rr, "red_wool" if (k // 2) % 2 == 0 else "white_wool")
fill(x, g + 2, z, x, g + 9, z, "spruce_fence")
poi("Повітряна куля", x, z, "Повітряна куля", "огляд острова", sign_dx=6, sign_dz=6, r=10)

# Скельна застава над стартом
x, z = spot(*S(225, 772), r=50, flat=8, clear=12)
tower(x, z, r=5, hgt=20, mat="cobblestone", roof=("spruce_stairs", "spruce_planks"))
g = top_at(x + 9, z)
hollow(x + 6, g + 1, z - 4, x + 14, g + 5, z + 4, "stone_bricks")
fill(x, g + 30, z, x, g + 36, z, "spruce_fence"); fill(x + 1, g + 33, z, x + 4, g + 36, z, "blue_wool")
poi("Скельна застава", x, z, "Скельна застава", "варта старту", sign_dx=-8, sign_dz=7, r=16)

# Скелет древнього звіра в ярах
x, z = spot(*S(705, 800), r=40, flat=10, clear=12); g = top_at(x, z)
fill(x - 16, g, z, x + 16, g + 1, z, "bone_block[axis=x]")
for t in range(-12, 13, 3):
    for k in range(0, 19):
        a = math.pi * k / 18; ry = int(round(g + 1 + 7 * math.sin(a))); rz = int(round(z + 7 * math.cos(a)))
        setb(x + t, ry, rz, "bone_block")
sphere(x + 20, g + 3, z, 4, "bone_block", ry=3); fill(x + 21, g + 3, z - 2, x + 22, g + 4, z - 2, "air"); fill(x + 21, g + 3, z + 2, x + 22, g + 4, z + 2, "air")
poi("Скелет звіра", x, z, "Скелет древнього", "звіра", sign_dx=0, sign_dz=11, r=26)

# Острівець із вежею на сході
ix, iz = S(992, 385)
if TOP[iz, ix] < SEA - 1:
    sphere(ix, SEA - 6, iz, 16, "stone", ry=9); sphere(ix, SEA - 2, iz, 14, "sand", ry=5); disc(ix, SEA + 1, iz, 10, "grass_block")
    for k in range(3): oak(ix + rng.randint(-6, 6), iz + rng.randint(-6, 6), "oak", y=SEA + 2)
    for y in range(SEA + 2, SEA + 16):
        for a, b in ((-3, -3), (3, -3), (-3, 3), (3, 3), (0, -3), (-3, 0), (3, 0), (0, 3), (-2, -3), (2, 3)):
            if y < SEA + 10 or rng.random() < 0.5: setb(ix + 4 + a, y, iz + b, rng.choice(("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks")))
    POI.append(("Острівець із вежею", ix, iz))

# Скелі-шпилі на узбережжі й пагорбах
for k in range(16):
    for _ in range(50):
        x, z = rng.randrange(60, SIZE - 60), rng.randrange(60, SIZE - 60)
        if 0.82 < tL[z, x] < 0.97 or (cat[z, x] in (HIGH, STONE) and rng.random() < 0.3): break
    if reserved(x, z) or dRoad[z, x] < 8: continue
    g = top_at(x, z); hgt = rng.randint(14, 30)
    for y in range(0, hgt, 2):
        sphere(x + rng.uniform(-1, 1), g + y, z + rng.uniform(-1, 1), max(1.2, 4.5 * (1 - y / hgt)), rng.choice(("stone", "andesite", "stone", "mossy_cobblestone")), ry=1.6)
    reserve(x, z, 6)

# v6: темні отвори печер у схилах гір і скель (кадри 02, 17 відео), не на вулкані
_gzT, _gxT = np.gradient(TOP.astype(float)); _sl = np.hypot(_gxT, _gzT) + 1e-6
_cand = np.argwhere((_sl > 1.3) & np.isin(cat, [STONE, SNOW, HIGH, TERRA]) & (tM > 1.0) & (tL < 0.93) & ~RES_MASK & ~SLOTMASK & (dRoad > 12) & (dRiver > 12))
holes_ = []
for k_ in nrng.permutation(len(_cand))[:4000]:
    if len(holes_) >= 0: break   # v9: випадкові отвори печер прибрано
    z, x = map(int, _cand[k_])
    if any((x - a) ** 2 + (z - b) ** 2 < 50 ** 2 for a, b in holes_): continue
    r_ = rng.uniform(4.5, 8.0); ix_, iz_ = _gxT[z, x] / _sl[z, x], _gzT[z, x] / _sl[z, x]
    sphere(x + ix_ * r_ * 0.7, TOP[z, x] + r_ * 0.15, z + iz_ * r_ * 0.7, r_, "air", ry=r_ * 0.8)
    for _ in range(6): setb(int(x + ix_ * r_ * 0.5 + rng.randint(-3, 3)), int(TOP[z, x] - r_ * 0.6), int(z + iz_ * r_ * 0.5 + rng.randint(-3, 3)), rng.choice(("moss_carpet", "gravel", "cobblestone")))
    reserve(x, z, r_ + 5); holes_.append((x, z))
log(f"отворів печер: {len(holes_)}")
# ---------- ділянки під споруди з відкритих наборів (ставить сервер; список готує prep_sporudy.py) ----------
CMDS_LOAD, CMDS_PLACE, CMDS_FIX = [], [], []
for t in SPORUDY["templates"]:
    sx_, sy_, sz_ = t["size"]; ax_, az_ = S(*t["anchor"])
    if t["mode"] == "ground":
        cx_, cz_ = spot(ax_, az_, r=80, flat=12, clear=max(sx_, sz_) + 4)
        best = None
        for k in range(4):   # поворот, за якого найменше земляних робіт
            rsx, rsz = (sx_, sz_) if k % 2 == 0 else (sz_, sx_)
            x0, z0 = cx_ - rsx // 2, cz_ - rsz // 2; reg = TOP[max(z0, 0):z0 + rsz, max(x0, 0):x0 + rsx]
            if reg.size == 0: continue
            base_ = max(int(np.median(reg)), SEA); cost = int(np.abs(reg - base_).sum()) + rng.randint(0, 20)
            if best is None or cost < best[0]: best = (cost, k, x0, z0, base_)
        _, k, x0, z0, y = best
        _, rsx, rsz = stamp_tpl(t["source"], t["entry"], x0, z0, k, base=y, fit=True, blend=8)
    else:
        k = rng.randrange(4); rsx, rsz = (sx_, sz_) if k % 2 == 0 else (sz_, sx_)
        x0, z0 = ax_ - rsx // 2, az_ - rsz // 2
        y = int(TOP[max(z0, 0):z0 + rsz, max(x0, 0):x0 + rsx].max()) + int(t["mode"].split(":")[1])
        stamp_tpl(t["source"], t["entry"], x0, z0, k, base=y, fit=False)
    reserve(x0 + rsx // 2, z0 + rsz // 2, max(rsx, rsz) // 2 + 6)
    if t["name"]:
        POI.append((t["name"], x0 + rsx // 2, z0 + rsz // 2))
        if t["mode"] == "ground": sign(x0 - 5, z0 + rsz // 2, t["name"])
        else: sign(*spot(x0 + rsx // 2, z0 + rsz // 2, r=30, clear=4), t["name"], "дивись угору")
for st_ in SPORUDY["structures"]:
    ax_, az_ = S(*st_["anchor"]); R = st_["flatten"]
    if R:
        x, z = spot(ax_, az_, r=90, flat=16, clear=min(2 * R, 40))
        reg = TOP[z - R:z + R, x - R:x + R]
        y = max(int(np.median(reg[reg >= SEA - 1])) if (reg >= SEA - 1).any() else SEA, SEA)
        fit_circle(x, z, 2 * R, y, blend=12)
        SLOTMASK[max(z - R - 6, 0):z + R + 6, max(x - R - 6, 0):x + R + 6] = True
        reserve(x, z, R + 10); y += 1
    else:
        x, z, y = ax_, az_, SEA - 1
    CMDS_LOAD.append(f"forceload add {x - 80} {z - 80} {x + 80} {z + 80}")
    CMDS_PLACE.append(f"place structure {st_['id']} {x} {y} {z}")
    if st_["name"]:
        POI.append((st_["name"], x, z))
        if R: sign(x + R + 3, z, st_["name"])
log(f"ділянки під споруди: шаблонів {len(SPORUDY['templates'])}, споруд {len(SPORUDY['structures'])}")

# вишневі дерева біля великих місць: їх видно здалеку, як підказку
for name, x, z in list(POI):
    if name not in ("Меч героя", "Велетенське дерево", "Голова колоса", "Водоспад", "Руїни фортеці", "Вежа мага", "Кам'яне коло", "Акведук", "Скельна застава"): continue
    for k in range(5):
        a, d = rng.uniform(0, 6.283), rng.uniform(16, 30); px, pz = int(x + d * math.cos(a)), int(z + d * math.sin(a))
        if is_land(px, pz) and not reserved(px, pz) and not SLOTMASK[pz, px] and dRoad[pz, px] > 4: oak(px, pz, "cherry")

# ---------- дрібні місця по всьому острову ----------
def ruin_wall(x, z):
    g = top_at(x, z)
    for t in range(rng.randint(6, 10)):
        fill(x + t, g, z, x + t, g + rng.randint(1, 5), z, rng.choice(("mossy_stone_bricks", "cracked_stone_bricks", "cobblestone", "mossy_cobblestone")))
    for t in range(rng.randint(3, 6)): fill(x, g, z + t, x, g + rng.randint(1, 4), z + t, "mossy_cobblestone")
def mini_shrine(x, z):
    g = top_at(x, z); fill(x, g + 1, z, x, g + 2, z, "polished_andesite"); setb(x, g + 3, z, "candle[candles=4,lit=true]")
    for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)): setb(x + a, g + 1, z + b, rng.choice(("poppy", "cornflower", "oxeye_daisy")))
def hay_cart(x, z):
    g = top_at(x, z) + 1; fill(x, g, z, x + 2, g, z + 1, "spruce_slab"); setb(x + 1, g + 1, z, "hay_block"); setb(x, g + 1, z + 1, "hay_block")
    setb(x - 1, g, z, "dark_oak_trapdoor[facing=east,open=true]"); setb(x + 3, g, z + 1, "dark_oak_trapdoor[facing=west,open=true]")
def scarecrow(x, z):
    g = top_at(x, z) + 1; fill(x, g, z, x, g + 1, z, "oak_fence"); setb(x, g + 2, z, "hay_block"); setb(x, g + 3, z, "carved_pumpkin[facing=south]")
    setb(x - 1, g + 2, z, "oak_fence"); setb(x + 1, g + 2, z, "oak_fence")
def log_pile(x, z):
    g = top_at(x, z) + 1
    for k in range(3): fill(x, g + k, z + k // 2, x + 4, g + k, z + 2 - k // 2, f"{rng.choice(('oak', 'spruce', 'birch'))}_log[axis=x]")
    setb(x + 6, g, z, "chipped_anvil[facing=north]")
def camp(x, z):
    tent(x, z, rng.choice(("white", "brown", "green", "light_gray"))); campfire(x + 5, z); setb(x + 5, top_at(x + 5, z + 2) + 1, z + 2, "barrel")
def statue(x, z):
    g = top_at(x, z); fill(x - 1, g, z - 1, x + 1, g + 1, z + 1, "stone_bricks"); fill(x, g + 2, z, x, g + 4, z, "polished_andesite")
    setb(x - 1, g + 4, z, "andesite_wall"); setb(x + 1, g + 4, z, "andesite_wall"); setb(x, g + 5, z, rng.choice(("chiseled_stone_bricks", "carved_pumpkin[facing=south]")))
def small_well(x, z):
    g = top_at(x, z); hollow(x - 1, g, z - 1, x + 1, g + 1, z + 1, "cobblestone"); fill(x, g - 4, z, x, g + 1, z, "water")
    setb(x - 1, g + 2, z - 1, "oak_fence"); setb(x + 1, g + 2, z + 1, "oak_fence"); fill(x - 1, g + 3, z - 1, x + 1, g + 3, z + 1, "spruce_slab")
def lookout(x, z):
    g = top_at(x, z)
    for a, b in ((0, 0), (3, 0), (0, 3), (3, 3)): fill(x + a, g, z + b, x + a, g + 7, z + b, "spruce_log")
    fill(x, g + 8, z, x + 3, g + 8, z + 3, "spruce_planks"); hollow(x, g + 9, z, x + 3, g + 9, z + 3, "spruce_fence")
    fill(x + 1, g + 1, z - 1, x + 1, g + 8, z - 1, "ladder[facing=north]"); setb(x + 1, g + 9, z - 1, "air")
def graves(x, z):
    for k in range(rng.randint(2, 4)):
        g = top_at(x + k * 2, z); setb(x + k * 2, g + 1, z, rng.choice(("cobblestone_wall", "mossy_cobblestone_wall", "stone_brick_wall"))); setb(x + k * 2, g, z + 1, "coarse_dirt")
def berry_patch(x, z):
    for _ in range(14):
        px, pz = x + rng.randint(-4, 4), z + rng.randint(-4, 4)
        if is_land(px, pz): setb(px, top_at(px, pz) + 1, pz, "sweet_berry_bush[age=3]")
def archery(x, z):
    for k in range(3):
        g = top_at(x + k * 3, z); setb(x + k * 3, g + 1, z, "hay_block"); setb(x + k * 3, g + 2, z, "target")
    setb(x + 3, top_at(x + 3, z + 8) + 1, z + 8, "barrel")
def standing_stones(x, z):
    for k in range(rng.randint(2, 4)):
        px, pz = x + rng.randint(-4, 4), z + rng.randint(-4, 4); fill(px, top_at(px, pz), pz, px, top_at(px, pz) + rng.randint(2, 4), pz, rng.choice(("stone", "andesite", "tuff")))
def ruined_house(x, z):
    g = top_at(x, z)
    for px in range(x, x + 7):
        for pz in range(z, z + 6):
            if px in (x, x + 6) or pz in (z, z + 5):
                hh_ = rng.choice((0, 1, 2, 3, 3))
                if hh_: fill(px, g, pz, px, g + hh_, pz, rng.choice(("cobblestone", "mossy_cobblestone", "oak_planks", "stripped_oak_log")))
    for _ in range(4): setb(x + rng.randint(1, 5), g + 1, z + rng.randint(1, 4), rng.choice(("cobweb", "short_grass", "oak_slab")))
def sheep_pen(x, z):
    for k in range(7):
        for a, b in ((k, 0), (k, 6), (0, k), (6, k)): setb(x + a, top_at(x + a, z + b) + 1, z + b, "oak_fence")
    setb(x + 3, top_at(x + 3, z + 3) + 1, z + 3, "hay_block")
def fairy_ring(x, z):
    for k in range(12):
        a = k / 12 * 6.283; px, pz = int(x + 4 * math.cos(a)), int(z + 4 * math.sin(a)); setb(px, top_at(px, pz) + 1, pz, rng.choice(("red_mushroom", "brown_mushroom")))
def fishing_spot(x, z):
    g = top_at(x, z) + 1; setb(x, g, z, "barrel"); fill(x + 1, g, z, x + 1, g + 2, z, "oak_fence"); setb(x - 1, g, z, "spruce_stairs[facing=east]")
def beacon_post(x, z):
    g = top_at(x, z); fill(x - 1, g, z - 1, x + 1, g, z + 1, "cobblestone"); fill(x, g + 1, z, x, g + 3, z, "cobblestone_wall"); setb(x, g + 4, z, "campfire")
# v5: між містами — хутори, вози, зламані огорожі, кістки, мертві дерева, руїни круглих веж (нотатки 19, розд. 5 і 9)
def ruined_round_tower(x, z):
    g = top_at(x, z); r = rng.uniform(3, 4.5); hgt = rng.randint(8, 16)
    R = int(r) + 1; X, Z = np.mgrid[-R:R + 1, -R:R + 1]; D = np.hypot(X, Z)
    for a, b in zip(X[(D <= r + 0.5) & (D > r - 0.7)], Z[(D <= r + 0.5) & (D > r - 0.7)]):
        hh = int(hgt * (0.35 + 0.65 * abs(math.sin(math.atan2(b, a) * 1.5 + x))))
        fill(x + a, g - 1, z + b, x + a, g + hh, z + b, rng.choice(("mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks", "cobbled_deepslate", "mossy_cobblestone")))
    for _ in range(6): setb(x + rng.randint(-R - 2, R + 2), g + 1, z + rng.randint(-R - 2, R + 2), rng.choice(("mossy_cobblestone", "cobblestone_slab", "mossy_stone_brick_slab")))
    for _ in range(4): setb(x + rng.randint(-R, R), g + rng.randint(2, hgt), z + R + 1, "vine[north=true]")
def wagon(x, z):
    y = top_at(x, z) + 1; fill(x - 1, y, z - 2, x + 1, y, z + 2, "spruce_slab[type=top]")
    for a, b in ((-2, -1), (2, -1), (-2, 1), (2, 1)): setb(x + a, y - 1 if rng.random() < 0.3 else y, z + b, "dark_oak_trapdoor[facing=east,open=true]" if a < 0 else "dark_oak_trapdoor[facing=west,open=true]")
    for k in (-2, 0, 2):   # біле полотно на дугах
        setb(x - 1, y + 1, z + k, "spruce_fence"); setb(x + 1, y + 1, z + k, "spruce_fence")
    fill(x - 1, y + 2, z - 2, x + 1, y + 2, z + 2, "white_wool"); setb(x, y + 1, z, "barrel"); setb(x, y, z + 3, "hay_block")
def broken_fence(x, z):
    for t in range(rng.randint(6, 12)):
        if rng.random() < 0.7: setb(x + t, top_at(x + t, z) + 1, z, rng.choice(("oak_fence[east=true,west=true]", "oak_fence", "spruce_fence")))
def bones(x, z):
    g = top_at(x, z); fill(x - 2, g + 1, z, x + 2, g + 1, z, "bone_block[axis=x]")
    for t in (-1, 1): setb(x + t, g + 2, z, "bone_block"); setb(x + t, g + 1, z + 1, "bone_block[axis=z]")
    setb(x + 3, g + 1, z, "skeleton_skull[rotation=4]"); white_dead_tree(x + 5, z + 3)
def farmstead(x, z):
    house(x, z, 9, 7, ("spruce_planks", "dark_oak_log", "spruce_planks", ("acacia_stairs", "acacia_planks")), floors=1, trim=TRIM)
    for k in range(10):
        px, pz = x - 4 + k, z + 6
        if is_land(px, pz): setb(px, top_at(px, pz) + 1, pz, "oak_fence[east=true,west=true]")
    setb(x + 7, top_at(x + 7, z) + 1, z, "hay_block"); setb(x + 7, top_at(x + 7, z + 1) + 1, z + 1, "hay_block")
MICRO = [ruin_wall, mini_shrine, hay_cart, scarecrow, log_pile, camp, statue, small_well, lookout, graves, berry_patch,
         archery, standing_stones, ruined_house, sheep_pen, fairy_ring, beacon_post,
         ruined_round_tower, wagon, wagon, broken_fence, broken_fence, bones, farmstead, farmstead, white_dead_tree]
micro_pts = []
for _ in range(6000):
    if len(micro_pts) >= 210: break
    x, z = rng.randrange(40, SIZE - 40), rng.randrange(40, SIZE - 40)
    if not is_land(x, z) or reserved(x, z) or SLOTMASK[z, x] or dRoad[z, x] < 6 or dRiver[z, x] < 10 or cat[z, x] in (SAND, LAVA, BASALT, TOWN): continue
    if any((x - a) ** 2 + (z - b) ** 2 < 42 ** 2 for _, a, b in POI) or any((x - a) ** 2 + (z - b) ** 2 < 32 ** 2 for a, b in micro_pts): continue
    area = TOP[z - 4:z + 9, x - 4:x + 9]
    if area.max() - area.min() > 4: continue
    rng.choice(MICRO)(x, z); micro_pts.append((x, z)); reserve(x + 3, z + 3, 8)
near_water = [(x, z) for x, z in zip(*np.nonzero(POND)[::-1])][::97]
for x, z in near_water[:12]:
    if is_land(x + 3, z): fishing_spot(x + 3, z)
log(f"дрібних місць: {len(micro_pts)}")

# ---------- стежки від доріг до точок ----------
TRAIL = np.zeros((SIZE, SIZE), bool)
for name, x, z in list(POI):
    d = ROAD_PTS - (x, z); j = int(np.argmin(np.hypot(d[:, 0], d[:, 1]))); rx_, rz_ = ROAD_PTS[j]
    L = math.hypot(rx_ - x, rz_ - z)
    if L < 14 or L > 260: continue
    ph = rng.uniform(0, 6.28)
    for t in range(int(L)):
        f = t / L; wob = 6 * math.sin(f * math.pi) * math.sin(f * 9 + ph)
        px, pz = x + (rx_ - x) * f - (rz_ - z) / L * wob, z + (rz_ - z) * f + (rx_ - x) / L * wob
        for ox, oz in ((0, 0), (1, 0), (0, 1)):
            qx, qz = int(px) + ox, int(pz) + oz
            if 0 <= qx < SIZE and 0 <= qz < SIZE and is_land(qx, qz) and not reserved(qx, qz) and cat[qz, qx] not in (TOWN, PATH):
                TRAIL[qz, qx] = True
tz_, tx_ = np.nonzero(TRAIL)
pick = nrng.random(tz_.size)
put(tx_[pick < 0.7], TOP[tz_[pick < 0.7], tx_[pick < 0.7]], tz_[pick < 0.7], "dirt_path")
put(tx_[pick >= 0.7], TOP[tz_[pick >= 0.7], tx_[pick >= 0.7]], tz_[pick >= 0.7], "coarse_dirt")
TRAIL_NEAR = TRAIL.copy()
for dz_ in (-2, -1, 0, 1, 2):
    for dx_ in (-2, -1, 0, 1, 2): TRAIL_NEAR |= np.roll(np.roll(TRAIL, dz_, 0), dx_, 1)
log(f"стежки: {int(TRAIL.sum())} блоків")
log(f"точок інтересу: {len(POI)}")

# ---------- природа ----------
log("дерева й декор")
n_struct = len(EDITS)   # природа має лягти під споруди, тож потім переставимо її наперед
town_mask_pts = [(S(a, b), r + 8) for a, b, r, _, _ in TOWNS]
def free(x, z, road_gap=5):
    if not is_land(x, z) or dRoad[z, x] < road_gap or dRiver[z, x] < 8 or TRAIL_NEAR[z, x] or POND[z, x] or SLOTMASK[z, x] or STREETMASK[z, x] or reserved(x, z): return False
    return all((x - p[0]) ** 2 + (z - p[1]) ** 2 > r * r for p, r in town_mask_pts)
trees = 0
# v5: дерева-велетні у великому й темному лісі (нотатки 19, розд. 5 і 8); західна частина великого лісу темніша й вища
FOREST_W = S(650, 590)[0] - 30
mega_pts = []
# Велетні ядра: голі стовбури-колони й широкі високі крони; кущі не заповнюють прохід.
elf_giants = 0
for z0 in range(ELF_TREE[1] - ELF_CORE_R, ELF_TREE[1] + ELF_CORE_R, 23):
    for x0 in range(ELF_TREE[0] - ELF_CORE_R, ELF_TREE[0] + ELF_CORE_R, 23):
        x, z = x0 + rng.randint(-6, 6), z0 + rng.randint(-6, 6)
        if not ELF_CORE[z, x] or cat[z, x] != FORESTC or not free(x, z, 7): continue
        if any((x - a) ** 2 + (z - b) ** 2 < 21 ** 2 for a, b in mega_pts): continue
        if np.ptp(TOP[z - 4:z + 5, x - 4:x + 5]) > 6: continue
        mega_tree(x, z, dark=False, height=rng.randint(40, 55), crown_scale=1.35)
        mega_pts.append((x, z)); reserve(x, z, 8); trees += 1; elf_giants += 1
log(f"v9: велетнів ядра-собору {elf_giants}, Велике Дерево 78 блоків + крона")
_old_giants = 0
for _ in range(9000):
    if _old_giants >= 34: break
    x, z = rng.randrange(40, SIZE - 40), rng.randrange(40, SIZE - 40)
    fk_ = FOREST_KIND[z, x]
    if ELF_MASK[z, x] or cat[z, x] != FORESTC or fk_ not in (1, 3) or not free(x, z, 9) or any((x - a) ** 2 + (z - b) ** 2 < 30 ** 2 for a, b in mega_pts): continue
    if fk_ == 1 and x > FOREST_W + 60 and rng.random() < 0.6: continue
    area = TOP[z - 5:z + 6, x - 5:x + 6]
    if area.max() - area.min() > 5: continue
    mega_tree(x, z, dark=(fk_ == 3 or x < FOREST_W or rng.random() < 0.4)); mega_pts.append((x, z)); reserve(x, z, 11); trees += 1; _old_giants += 1
log(f"дерев-велетнів: {len(mega_pts)}")
for gz_ in range(0, SIZE, 5):
    for gx_ in range(0, SIZE, 5):
        x, z = min(gx_ + rng.randint(0, 4), SIZE - 1), min(gz_ + rng.randint(0, 4), SIZE - 1)
        c = cat[z, x]
        p = {FORESTC: 0.7, GRASS: 0.045, MEADOW: 0.015, HIGH: 0.2, STONE: 0.015, BANK: 0.03, CORRUPT: 0.05}.get(int(c), 0)
        if ELF_MASK[z, x] and c == FORESTC:
            p = 0.06 if ELF_CORE[z, x] else 0.40 if ELF_EDGE[z, x] else float(np.clip(0.45 + fnoise[z, x] * 0.5, 0.20, 0.70))
        if p and rng.random() < p and free(x, z):
            if c == CORRUPT:   # мертве дерево без листя
                g_ = top_at(x, z) + 1; hgt = rng.randint(4, 8); fill(x, g_, z, x, g_ + hgt, z, rng.choice(("dark_oak_log", "stripped_dark_oak_log")))
                for _ in range(rng.randint(1, 3)):
                    dx_, dz_ = rng.choice(((1, 0), (-1, 0), (0, 1), (0, -1))); yy = g_ + rng.randint(hgt // 2, hgt)
                    for q in range(1, rng.randint(2, 4)): setb(x + dx_ * q, yy + q // 2, z + dz_ * q, "dark_oak_log[axis=x]" if dx_ else "dark_oak_log[axis=z]")
                trees += 1; continue
            if c == FORESTC:
                r_ = rng.random()
                jx, jz = min(max(x + rng.randint(-8, 8), 0), SIZE - 1), min(max(z + rng.randint(-8, 8), 0), SIZE - 1)
                fk = FOREST_KIND[jz, jx] or FOREST_KIND[z, x]   # плямистий перехід між видами лісу
                if ELF_MASK[z, x]:
                    if ELF_CORE[z, x]: oak(x, z, "birch")
                    elif ELF_EDGE[z, x]:
                        if r_ < 0.72: big_oak(x, z, "birch", hgt=rng.randint(9, 14), crown=3.5)
                        else: bush(x, z)
                    else: big_oak(x, z, "oak" if r_ < 0.8 else "birch", hgt=rng.randint(22, 30), crown=rng.uniform(5.5, 8))
                elif fk == 2: (oak(x, z, "birch") if r_ < 0.75 else flat_tree(x, z, "birch") if r_ < 0.9 else oak(x, z, "oak"))
                elif fk == 1 and x < FOREST_W:   # західна частина: темніша й вища
                    if r_ < 0.35: big_oak(x, z, "dark_oak", hgt=rng.randint(11, 16), crown=5.5)
                    elif r_ < 0.5: big_oak(x, z, "oak", crown=4.5)
                    elif r_ < 0.75: oak(x, z, "oak")
                    elif r_ < 0.85: oak(x, z, "birch")
                    else: spruce(x, z)
                elif fk == 3:
                    if r_ < 0.45: big_oak(x, z, "dark_oak", hgt=rng.randint(10, 15), crown=5.5)
                    elif r_ < 0.75: big_oak(x, z, "dark_oak", crown=4.0)
                    else: spruce(x, z)
                elif fk == 4: spruce(x, z) if r_ < 0.9 else big_oak(x, z, "spruce", crown=4.0)
                elif r_ < 0.12: big_oak(x, z, "dark_oak")
                elif r_ < 0.2: big_oak(x, z, "oak", crown=4.0)
                elif r_ < 0.55: oak(x, z, "birch")
                else: oak(x, z, "oak")
            elif c in (HIGH, STONE): spruce(x, z)
            elif c == MEADOW: flat_tree(x, z, "birch") if rng.random() < 0.5 else oak(x, z, "birch")
            else: flat_tree(x, z, "oak") if rng.random() < 0.65 else oak(x, z, rng.choice(("oak", "oak", "birch")))   # пласкі крони на луках
            trees += 1
for _ in range(260):  # гаї: дерева купками, а не рівномірно
    x, z = rng.randrange(60, SIZE - 60), rng.randrange(60, SIZE - 60)
    if cat[z, x] not in (GRASS, MEADOW, HIGH) or not free(x, z): continue
    kind = rng.choice(("oak", "birch", "spruce", "oak", "cherry"))
    for _ in range(rng.randint(6, 14)):
        px, pz = x + rng.randint(-14, 14), z + rng.randint(-14, 14)
        if 0 < px < SIZE and 0 < pz < SIZE and free(px, pz):
            (spruce(px, pz) if kind == "spruce" else flat_tree(px, pz, kind) if kind == "oak" and rng.random() < 0.6 else oak(px, pz, kind)); trees += 1
for _ in range(500):
    x, z = rng.randrange(SIZE), rng.randrange(SIZE)
    if cat[z, x] in (GRASS, HIGH, STONE, MEADOW) and free(x, z): boulder(x, z)
for _ in range(400):
    x, z = rng.randrange(SIZE), rng.randrange(SIZE)
    if cat[z, x] in (GRASS, FORESTC) and free(x, z) and free(x + 7, z + 7): fallen_log(x, z)
for _ in range(3000):
    x, z = rng.randrange(SIZE), rng.randrange(SIZE)
    if ELF_CORE[z, x] and rng.random() < 0.85: continue
    if cat[z, x] in (GRASS, MEADOW, FORESTC, HIGH) and free(x, z, 3): bush(x, z)
# Кущовий пояс на краю лісу, а не рівномірний підлісок під високим наметом.
_edge_z, _edge_x = np.nonzero(ELF_EDGE)
for k in rng.sample(range(len(_edge_x)), min(500, len(_edge_x))):
    x, z = int(_edge_x[k]), int(_edge_z[k])
    if free(x, z, 3): bush(x, z)
# пляж: плавник, сухі кущі, каміння з пісковику
for _ in range(6000):
    x, z = rng.randrange(SIZE), rng.randrange(SIZE)
    if cat[z, x] != SAND or not is_land(x, z) or reserved(x, z) or SLOTMASK[z, x]: continue
    r_ = rng.random(); y = top_at(x, z)
    if r_ < 0.03: fill(x, y + 1, z, x + rng.randint(2, 4), y + 1, z, "stripped_oak_log[axis=x]")
    elif r_ < 0.05: sphere(x, y, z, rng.uniform(1.2, 2.2), rng.choice(("sandstone", "smooth_sandstone", "cut_sandstone")), ry=1.2)
    elif r_ < 0.2: setb(x, y + 1, z, "dead_bush")
    elif r_ < 0.24: setb(x, y + 1, z, "short_grass")
# мілина: морська трава, ламінарія, морські огірки
sea_ok = (cat == SEABED) & (TOP >= SEA - 14) & (TOP <= SEA - 3) & ~SLOTMASK
r2 = nrng.random((SIZE, SIZE))
zz, xx = np.nonzero(sea_ok & (r2 < 0.22)); put(xx, TOP[zz, xx] + 1, zz, "seagrass")
zz, xx = np.nonzero(sea_ok & (r2 >= 0.22) & (r2 < 0.24)); put(xx, TOP[zz, xx] + 1, zz, "sea_pickle[pickles=3]")
kz, kx = np.nonzero(sea_ok & (r2 >= 0.24) & (r2 < 0.30) & (TOP <= SEA - 6))
kh = np.minimum(SEA - 3 - TOP[kz, kx], nrng.integers(3, 12, kz.size))
for k in range(1, 12):
    sel = kh >= k
    put(kx[sel], TOP[kz[sel], kx[sel]] + k, kz[sel], "kelp_plant" if k < 11 else "kelp[age=20]")
top_sel = kh >= 1
put(kx[top_sel], TOP[kz[top_sel], kx[top_sel]] + kh[top_sel] + 1, kz[top_sel], "kelp[age=20]")
# трава й квіти: векторно
plant_ok = np.isin(cat, [GRASS, MEADOW, FORESTC, HIGH]) & (dRoad > 3) & ~river & ~TRAIL & ~POND & ~SLOTMASK & ~STREETMASK & (TOPB == sid("grass_block"))
for (a, b), r in town_mask_pts:
    plant_ok &= np.hypot(wx - a, wz - b) > r * 0.6
rnd = nrng.random((SIZE, SIZE))
FLOWERS = ["poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy", "allium", "red_tulip", "orange_tulip", "lily_of_the_valley"]
grass_m = plant_ok & (rnd < 0.30)
flower_m = plant_ok & (rnd >= 0.30) & (rnd < np.where(cat == MEADOW, 0.40, 0.318))
# v5: квіти одного кольору плямами (нотатки 19, розд. 8): вид квітки береться з плями шуму, лише п'ята частина випадкова
_fp = fbm(wx, wz, 91, 1, f0=0.04)
FLOWER_IDX = np.clip(((_fp + 0.9) / 1.8 * len(FLOWERS)).astype(int), 0, len(FLOWERS) - 1)
clump_m = plant_ok & (np.abs(fbm(wx, wz, 92, 1, f0=0.06)) > 0.45) & (rnd >= 0.45) & (rnd < 0.62) & np.isin(cat, [GRASS, MEADOW])
flower_m |= clump_m
tall_m = plant_ok & np.isin(cat, [GRASS, MEADOW]) & (rnd >= 0.40) & (rnd < 0.45)
tz2, tx2 = np.nonzero(tall_m)
put(tx2, TOP[tz2, tx2] + 1, tz2, "tall_grass[half=lower]"); put(tx2, TOP[tz2, tx2] + 2, tz2, "tall_grass[half=upper]")
fern_m = plant_ok & np.isin(cat, [FORESTC, HIGH]) & (rnd >= 0.40) & (rnd < 0.45)
mush_m = plant_ok & (cat == FORESTC) & (rnd >= 0.45) & (rnd < 0.456)
cz_, cx_ = np.nonzero((cat == CORRUPT) & (rnd < 0.06) & ~SLOTMASK & ~STREETMASK & (dRoad > 3))
cp_ = nrng.integers(0, 3, cz_.size)
for k_, st_ in enumerate(("dead_bush", "crimson_roots", "dead_bush")): put(cx_[cp_ == k_], TOP[cz_[cp_ == k_], cx_[cp_ == k_]] + 1, cz_[cp_ == k_], st_)
for m, states in ((grass_m, ["short_grass"]), (flower_m, FLOWERS), (fern_m, ["fern", "large_fern[half=lower]"]), (mush_m, ["brown_mushroom", "red_mushroom"])):
    zz, xx = np.nonzero(m)
    pick = nrng.integers(0, len(states), zz.size)
    if states is FLOWERS: pick = np.where(nrng.random(zz.size) < 0.8, FLOWER_IDX[zz, xx], pick)
    for k, st in enumerate(states):
        sel = pick == k
        put(xx[sel], TOP[zz[sel], xx[sel]] + 1, zz[sel], st)
        if st == "large_fern[half=lower]": put(xx[sel], TOP[zz[sel], xx[sel]] + 2, zz[sel], "large_fern[half=upper]")
# культури на грядках: кожна ділянка поля своя (v5)
fz, fx = np.nonzero((cat == FIELD) & (TOPB == sid("farmland[moisture=7]")))
for k, st in ((1, "wheat[age=7]"), (2, "carrots[age=7]"), (3, "potatoes[age=7]"), (4, "beetroots[age=3]")):
    sel = FIELD_CROP[fz, fx] == k; put(fx[sel], TOP[fz[sel], fx[sel]] + 1, fz[sel], st)
# гарбузи й дині, соняшники, перелог із сіном
oz_, ox_ = np.nonzero(field & (FIELD_CROP >= 5) & ~SLOTMASK & ~STREETMASK)
r5 = nrng.random(oz_.size); k5 = FIELD_CROP[oz_, ox_]
for kk, prob, st in ((5, 0.12, "pumpkin"), (5, 0.06, "melon"), (7, 0.05, "hay_block[axis=x]")):
    sel = (k5 == kk) & (r5 < prob); put(ox_[sel], TOP[oz_[sel], ox_[sel]] + 1, oz_[sel], st); r5 = np.where(sel, 1, r5 - prob)
sel = (k5 == 6) & (ox_ % 2 == 0)
put(ox_[sel], TOP[oz_[sel], ox_[sel]] + 1, oz_[sel], "sunflower[half=lower]"); put(ox_[sel], TOP[oz_[sel], ox_[sel]] + 2, oz_[sel], "sunflower[half=upper]")
# живоплоти між ділянками
hz_, hx_ = np.nonzero(HEDGE & ~SLOTMASK & ~STREETMASK & ~river & (cat != RIVERBED))
hk = nrng.random(hz_.size)
put(hx_[hk < 0.85], TOP[hz_[hk < 0.85], hx_[hk < 0.85]] + 1, hz_[hk < 0.85], "oak_leaves[persistent=true]")
put(hx_[hk < 0.25], TOP[hz_[hk < 0.25], hx_[hk < 0.25]] + 2, hz_[hk < 0.25], "azalea_leaves[persistent=true]")
# півонії й бузок купками біля ставків
pz2, px2 = np.nonzero(POND)
for k in range(0, pz2.size, 41):
    cx_, cz_ = int(px2[k]) + rng.randint(-6, 6), int(pz2[k]) + rng.randint(-6, 6)
    st_ = rng.choice(("peony", "lilac", "rose_bush"))
    for _ in range(rng.randint(3, 7)):
        qx, qz = cx_ + rng.randint(-2, 2), cz_ + rng.randint(-2, 2)
        if 0 < qx < SIZE and 0 < qz < SIZE and is_land(qx, qz) and not POND[qz, qx] and not SLOTMASK[qz, qx] and TOPB[qz, qx] == sid("grass_block"):
            setb(qx, TOP[qz, qx] + 1, qz, f"{st_}[half=lower]"); setb(qx, TOP[qz, qx] + 2, qz, f"{st_}[half=upper]")
# латаття на річках
lz, lx = np.nonzero((river | POND) & (rnd < 0.03))
put(lx, WL[lz, lx] + 1, lz, "lily_pad")
EDITS = EDITS[n_struct:] + EDITS[:n_struct]
log(f"дерев: {trees}")

# ---------- печери ----------
log("печери")
def surf(x, y):
    X, Z = S(x, y); return top_at(X, Z) - 1
def hall_pt(name):
    h_ = next(t for t in HALLS if t[0] == name); return (h_[1], h_[2], h_[4] + 3)
TUNNELS = [   # v9: лишилися тільки сюжетні зали своїх зон (Лавові глибини, склеп собору)
    [(DUNGEON[0], DUNGEON[1], surf(*DUNGEON)), (805, 320, 20), (815, 290, 0), hall_pt("Лавові глибини")],
    [(870, 440, surf(870, 440)), hall_pt("Склеп під собором")],
]
TUNNELS.append([(VCX / K, VCZ / K, ISL_Y - 2), (843, 269, 112), (848, 287, 60), (833, 272, 10), hall_pt("Лавові глибини")])   # v6: з острова в кратері
SEGS = []  # (x0, y0, z0, x1, y1, z1) у блоках
for path in TUNNELS:
    for (a, b, c), (d, e, f) in zip(path, path[1:]):
        (x0, z0), (x1, z1) = S(a, b), S(d, e)
        SEGS.append((x0, c, z0, x1, f, z1))
HALLS_W = [(n, *S(x, y), r * K, fy, hh, kind) for n, x, y, r, fy, hh, kind in HALLS]
sand_z, sand_x = np.nonzero(cat == SAND)
for hx_, hz_ in ():   # v9: схованки прибрано
    X, Z = S(hx_, hz_); j = int(np.argmin((sand_x - X) ** 2 + (sand_z - Z) ** 2)); ex, ez = int(sand_x[j]), int(sand_z[j])
    HALLS_W.append(("Схованка контрабандистів", X, Z, 13.0, SEA - 3, 6, "stone"))
    SEGS.append((ex, TOP[ez, ex] + 1, ez, X, SEA - 1, Z))
    setb(X, SEA - 3, Z, "chest"); setb(X + 2, SEA - 3, Z + 1, "barrel"); setb(X - 2, SEA - 3, Z, "lantern"); setb(X + 1, SEA - 3, Z - 2, "lantern")
THEME = {"stone": mix("stone", "andesite", "coal_ore", "stone", "copper_ore", "iron_ore"), "crystal": mix("amethyst_block", "calcite", "smooth_basalt", "amethyst_block", "budding_amethyst"),
         "lake": mix("stone", "clay", "stone", "dripstone_block"), "mushroom": mix("moss_block", "mycelium", "moss_block", "stone", "shroomlight"),
         "lava": mix("blackstone", "basalt", "magma_block", "blackstone", "gilded_blackstone"), "mine": mix("stone", "iron_ore", "coal_ore", "gold_ore", "stone", "copper_ore"),
         "grotto": mix("prismarine", "stone", "dark_prismarine", "sea_lantern", "stone"), "crypt": mix("mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks", "mossy_cobblestone"),
         "abyss": mix("deepslate", "sculk", "sculk", "cobbled_deepslate", "deepslate_diamond_ore", "deepslate")}
LIGHT = sid("light[level=13]")
# ---------- v9: підземний шар — авторські об'єми, без природної мережі (план 16, розд. 28) ----------
HALLS_W = [h for h in HALLS_W if h[0] in ("Лавові глибини", "Склеп під собором")]
BOXDIM, POOL_LVL, RIVER_SEGS = {}, {}, []
def v9_room(name, x, z, w, d, fy, h, kind, pool=None):
    BOXDIM[name] = (w / 2, d / 2); HALLS_W.append((name, x, z, max(w, d) / 2 + 2, fy, h, kind))
    if pool is not None: POOL_LVL[name] = pool
def v9_chain(pts):   # ходи між кімнатами: (x, z, y) центрів, капсула радіуса 3.2
    for (x0, z0, y0), (x1, z1, y1) in zip(pts, pts[1:]): SEGS.append((x0, y0, z0, x1, y1, z1))
THEME.update({"root_box": mix("stone", "andesite", "tuff", "moss_block", "rooted_dirt", "stone"),
              "coast_box": mix("stone", "andesite", "mossy_cobblestone", "stone", "tuff"),
              "crypt_box": mix("stone_bricks", "cracked_stone_bricks", "andesite", "deepslate_bricks", "mossy_stone_bricks"),
              "spring_box": mix("stone", "calcite", "tuff", "mossy_stone_bricks", "stone"),
              "pool_box": mix("calcite", "amethyst_block", "smooth_basalt", "stone", "calcite"),
              "grotto_box": mix("stone", "andesite", "prismarine", "mossy_cobblestone", "stone")})
# Русло Джерел (рівні 12–16): кімнати 1–7 за маршрутом, підлоги спускаються до гроту
RUSLO = [(1, "Провал", 10, 10, 84, 14, "spring_box"), (2, "Зала Відлуння", 20, 18, 80, 10, "spring_box"), (3, "Галерея порогів", 30, 9, 79, 8, "spring_box"),
         (4, "Кришталевий плес", 34, 24, 72, 14, "pool_box"), (5, "Затоплена зала", 20, 18, 70, 10, "spring_box"), (6, "Останній міст", 16, 12, 66, 9, "spring_box"),
         (7, "Морський грот", 22, 18, 50, 20, "grotto_box")]
_rp = {r[0]: r[2] for r in RUSLO_ROOMS}; _rp[7] = (268, 590)   # грот углиб суші: над ним вищий берег (товща ≥ 5)
for k, nm, w, d, fy, h, kind in RUSLO:
    x, z = _rp[k]; v9_room(nm, x, z, w, d, fy, h, kind, pool=62 if k == 7 else None)
gx_, gz_ = _rp[0]
v9_chain([(gx_, gz_, top_at(gx_, gz_) + 1), (_rp[1][0], _rp[1][1] + 3, 92)])
v9_chain([(_rp[k][0], _rp[k][1], RUSLO[k - 1][4] + 2) for k in range(1, 8)])
RIVER_Y9 = [97, 86, 80, 77, 75, 70, 64, 62, 62]   # річка під маршрутом; у гроті падає в басейн
for (x0, z0), (x1, z1), y0, y1 in zip(RIVER_UG[:-1], RIVER_UG[1:], RIVER_Y9[:-1], RIVER_Y9[1:]): RIVER_SEGS.append((x0, z0, y0, x1, z1, y1))
RIVER_SEGS.append((_rp[7][0], _rp[7][1], 63, RUSLO_MOUTH[0] - 6, RUSLO_MOUTH[1], 63))   # горло до моря: вода до Y 62
_bk = [(268, 590)] + list(RUSLO_BACK[1:]); _by = [64, 66, 72, 80, 88, 90]
v9_chain([(x, z, y) for (x, z), y in zip(_bk[:-1], _by[:-1])] + [(_rp[1][0] - 4, _rp[1][1] + 4, 86)])
# мініпідземелля — перший набір (схеми А, Б, В нотатки 22)
MINI9 = [("Льох під корінням", "2–5", (610, 1120), "root_box",
          [("горло", 610, 1128, 4, 10, 62, 5), ("тунель", 618, 1140, 4, 14, 54, 5), ("бойове ядро", 628, 1158, 14, 18, 44, 9), ("схованка", 642, 1150, 6, 8, 46, 5), ("хід назад", 616, 1146, 3, 12, 52, 4)]),
         ("Трюм у скелі", "8–13", (292, 668), "coast_box",
          [("вхід-розлом", 292, 668, 4, 8, 58, 6), ("верхній балкон", 282, 660, 10, 8, 46, 6), ("корінь-міст", 272, 652, 6, 14, 44, 4), ("нижня зала", 276, 668, 18, 22, 34, 13), ("головна скриня", 268, 676, 6, 6, 35, 5)]),
         ("Забута усипальниця", "36–40", (1335, 830), "crypt_box",
          [("сходи з руїни", 1335, 830, 4, 10, 58, 5), ("вид на скриню", 1335, 846, 8, 6, 50, 6), ("коридор ніш", 1335, 866, 6, 24, 48, 7), ("бічна зала", 1350, 878, 10, 10, 48, 8), ("поховальна камера", 1335, 892, 12, 12, 46, 10)])]
for nm, lv, (ex, ez), kind, rooms in MINI9:
    for rn, x, z, w, d, fy, h in rooms: v9_room(f"{nm}: {rn}", x, z, w, d, fy, h, kind)
    r0 = rooms[0]; g0 = top_at(ex, ez); drop = g0 - (r0[5] + 2)
    v9_chain([(ex, ez - drop - 2, top_at(ex, ez - drop - 2) + 1), (r0[1], r0[2], r0[5] + 2)])   # пологий спуск із поверхні (≤ 1 на блок)
    v9_chain([(r[1], r[2], r[5] + 2) for r in rooms])
    fy0 = platform(ex - 4, ez - drop - 7, ex + 4, ez - drop + 1, "mossy_stone_bricks")   # наземна причина: цоколь руїни
    for a, b in ((-4, -drop - 7), (4, -drop - 7), (-4, -drop + 1), (4, -drop + 1)): fill(ex + a, fy0, ez + b, ex + a, fy0 + 2 + rng.randint(0, 2), ez + b, "cracked_stone_bricks")
    lx, lz, lfy = rooms[-1][1], rooms[-1][2], rooms[-1][5]; setb(lx, lfy, lz, "chest") if DUNGEONS_OPEN else None
    POI.append((nm, ex, ez)); sign(ex + 5, ez - drop - 3, nm, "мініпідземелля", f"Lv {lv}" if DUNGEONS_OPEN else "зачинено")
# Русло: сходи в Провалі, міст, балкон гроту, ґрати, двері потаємного ходу
if DUNGEONS_OPEN:   # v10: поки данжі запечатані, підземних прикрас і дверей немає
    px_, pz_ = _rp[1]
    _per = [(px_ - 4 + i, pz_ - 4) for i in range(9)] + [(px_ + 4, pz_ - 4 + i) for i in range(1, 9)] + [(px_ + 4 - i, pz_ + 4) for i in range(1, 9)]
    for i, (sx_, sz_) in enumerate(_per[:14]): fill(sx_, 84, sz_, sx_, 97 - i - 1, sz_, "mossy_stone_bricks")
    bx_, bz_ = _rp[6]; fill(bx_ - 2, 65, bz_ - 6, bx_ + 2, 65, bz_ + 6, "stone_bricks")
    for xx in (bx_ - 3, bx_ + 3): fill(xx, 66, bz_ - 6, xx, 66, bz_ + 6, "stone_brick_wall")
    gx7, gz7 = _rp[7]; fill(gx7 + 3, 50, gz7 - 8, gx7 + 10, 62, gz7 + 8, "stone_bricks"); setb(gx7 + 8, 63, gz7, "chest")
    for xb in (RUSLO_MOUTH[0] + 8, RUSLO_MOUTH[0] + 10):   # ґрати на всю арку з обох боків: з моря не пройти
        for zb in range(RUSLO_MOUTH[1] - 5, RUSLO_MOUTH[1] + 6):
            for yb in range(55, 68): setb(xb, yb, zb, "iron_bars")
    (dx0, dz0), (dx1, dz1) = RUSLO_BACK[-2], (_rp[1][0] - 4, _rp[1][1] + 4)
    drx, drz = int(dx0 + (dx1 - dx0) * 0.8), int(dz0 + (dz1 - dz0) * 0.8); dry = 89
    fill(drx - 3, dry - 1, drz, drx + 3, dry + 4, drz, "stone_bricks"); fill(drx, dry, drz, drx, dry + 1, drz, "air")
    setb(drx, dry, drz, "iron_door[facing=north,half=lower]"); setb(drx, dry + 1, drz, "iron_door[facing=north,half=upper]")
    setb(drx, dry, drz + 1, "stone_pressure_plate")   # плита лише з боку ходу (зсередини)
    for k, nm, *_ in RUSLO: POI.append((f"Русло: {nm}", *_rp[k]))
    log(f"v9: підземний шар — залів {len(HALLS_W)}, ходів {len(SEGS)}, русло {len(RIVER_SEGS)} відрізків; двері ходу назад ({drx}, {dry}, {drz})")
else:
    HALLS_W, SEGS, RIVER_SEGS = [], [], []   # нічого не вирізаємо під землею
    log("v10: данжі запечатані — підземний шар не вирізається")
# прикраси залів
for n, X, Z, R, fy, hh, kind in HALLS_W:
    for _ in range(int(R * R / 30)):
        a, d = rng.uniform(0, 6.283), rng.uniform(0, R * 0.75); x, z = int(X + d * math.cos(a)), int(Z + d * math.sin(a))
        y = fy
        if kind == "crystal": fill(x, y, z, x, y + rng.randint(0, 3), z, "amethyst_block"); setb(x, y + 4, z, "amethyst_cluster")
        elif kind == "mushroom":
            if rng.random() < 0.3:
                hgt = rng.randint(4, 8); fill(x, y, z, x, y + hgt, z, "mushroom_stem")
                sphere(x, y + hgt + 1, z, 3.2, rng.choice(("red_mushroom_block", "brown_mushroom_block")), ry=1.6)
            else: setb(x, y, z, rng.choice(("moss_carpet", "brown_mushroom", "red_mushroom", "glow_lichen[down=true]")))
        elif kind == "mine" and rng.random() < 0.4:
            fill(x - 2, y, z, x - 2, y + 3, z, "oak_fence"); fill(x + 2, y, z, x + 2, y + 3, z, "oak_fence"); fill(x - 2, y + 4, z, x + 2, y + 4, z, "spruce_planks")
            setb(x, y + 3, z, "lantern[hanging=true]")
        elif kind == "crypt" and rng.random() < 0.3:
            fill(x, y, z, x, y + 6, z, "chiseled_stone_bricks"); setb(x, y + 7, z, "soul_lantern")
        elif kind == "abyss" and rng.random() < 0.5: setb(x, y, z, rng.choice(("sculk_sensor", "sculk_shrieker", "candle[candles=4,lit=true]")))
        elif kind == "stone" and rng.random() < 0.3: setb(x, y, z, rng.choice(("torch", "chest", "crafting_table")))
    for gx_ in range(int(X - R), int(X + R), 9):
        for gz_ in range(int(Z - R), int(Z + R), 9):
            if (gx_ - X) ** 2 + (gz_ - Z) ** 2 < (R * 0.8) ** 2: setb(gx_, fy + int(hh * 0.5) + 2, gz_, LIGHT)
    sign(int(X), int(Z), n, "", y=fy) if kind not in ("lake", "lava") else None
for x0, y0, z0, x1, y1, z1 in SEGS:  # невидимі джерела світла вздовж тунелів
    L = math.hypot(x1 - x0, y1 - y0, z1 - z0); n = max(1, int(L / 14))
    for k in range(1, n):
        t = k / n; setb(int(x0 + (x1 - x0) * t), int(y0 + (y1 - y0) * t), int(z0 + (z1 - z0) * t), LIGHT)

def cave_mask(x0, z0, b, T):
    """Вирізає печери в масиві чанка b[y, z, x]; повертає маску вирізаного."""
    ys = np.arange(YMIN, YMIN + NY)[:, None, None]
    xs = (np.arange(16) + x0)[None, None, :]; zs = (np.arange(16) + z0)[None, :, None]
    carved = np.zeros(b.shape, bool)
    for n, X, Z, R, fy, hh, kind in HALLS_W:
        if abs(X - (x0 + 8)) > R + 14 or abs(Z - (z0 + 8)) > R + 14: continue
        if n in BOXDIM:   # v9: кімнати-коробки з пласкою стелею
            hw_, hd_ = BOXDIM[n]
            d = np.maximum(np.abs(xs - X) / hw_, np.abs(zs - Z) / hd_) ** 2 + 0.04 * np.sin(xs * 0.5 + zs * 0.3) + 0 * ys
            roof = fy + hh - 1 + 0 * d
        else:
            d = ((xs - X) ** 2 + (zs - Z) ** 2) / (R * R) + 0.12 * np.sin(xs * 0.35) * np.cos(zs * 0.29)
            roof = fy + hh * np.sqrt(np.clip(1 - d, 0, 1)) + 2
        inside = (d < 1) & (ys >= fy) & (ys <= roof) & (ys < T[None] - 4)
        shell = (d < 1.3) & (ys >= fy - 3) & (ys <= roof + 3) & ~inside & (ys < T[None] - 2) & (b != AIR) & (b != WATER)
        if shell.any():
            th = np.array(THEME[kind], np.uint16)
            idx = np.broadcast_to((ys * 7 + xs * 13 + zs * 5 + (xs * zs) % 11).astype(np.int64), b.shape)
            b[shell] = th[idx[shell] % len(th)]
        b[inside] = AIR; carved |= inside
        if kind == "lake": b[inside & (ys < fy + 4)] = WATER
        if n in POOL_LVL: b[inside & (ys <= POOL_LVL[n])] = WATER
        if kind == "grotto": b[inside & (ys < SEA)] = WATER
        if kind == "lava": b[inside & (ys < fy + 2)] = LAVA_ID
    for sx0, sy0, sz0, sx1, sy1, sz1 in SEGS:
        if max(sx0, sx1) < x0 - 5 or min(sx0, sx1) > x0 + 20 or max(sz0, sz1) < z0 - 5 or min(sz0, sz1) > z0 + 20: continue
        vx, vy, vz = sx1 - sx0, sy1 - sy0, sz1 - sz0; L2 = vx * vx + vy * vy + vz * vz or 1
        t = np.clip(((xs - sx0) * vx + (ys - sy0) * vy + (zs - sz0) * vz) / L2, 0, 1)
        dist = np.sqrt((xs - sx0 - t * vx) ** 2 + ((ys - sy0 - t * vy) * 1.3) ** 2 + (zs - sz0 - t * vz) ** 2)
        tun = dist < 3.2
        b[tun] = AIR; carved |= tun
    for rx0, rz0, ry0, rx1, rz1, ry1 in RIVER_SEGS:   # v9: підземна річка — жолоб із водою
        if max(rx0, rx1) < x0 - 5 or min(rx0, rx1) > x0 + 20 or max(rz0, rz1) < z0 - 5 or min(rz0, rz1) > z0 + 20: continue
        vx, vz = rx1 - rx0, rz1 - rz0; L2 = vx * vx + vz * vz or 1
        t = np.clip(((xs - rx0) * vx + (zs - rz0) * vz) / L2, 0, 1); dh = np.hypot(xs - rx0 - t * vx, zs - rz0 - t * vz)
        yl = ry0 + (ry1 - ry0) * t; m = (dh < 2.6) & (ys >= yl - 3) & (ys <= yl + 2) & (ys < T[None] - 3)
        b[m] = AIR; b[m & (ys <= yl - 1)] = WATER; carved |= m
    return carved

# ---------- запис світу ----------
class Byte(int): pass
class Short(int): pass
class Int(int): pass
class Long(int): pass
class Float(float): pass
class LongArray:
    def __init__(s, a): s.a = a
class ListTag:
    def __init__(s, tag, items): s.tag, s.items = tag, items
def _payload(v):
    if isinstance(v, Byte): return struct.pack(">b", v)
    if isinstance(v, Short): return struct.pack(">h", v)
    if isinstance(v, Int): return struct.pack(">i", v)
    if isinstance(v, Long): return struct.pack(">q", v)
    if isinstance(v, Float): return struct.pack(">f", v)
    if isinstance(v, str):
        b = v.encode("utf-8"); return struct.pack(">H", len(b)) + b
    if isinstance(v, LongArray): return struct.pack(">i", len(v.a)) + v.a.astype(">i8").tobytes()
    if isinstance(v, ListTag): return struct.pack(">bi", v.tag if v.items else 0, len(v.items)) + b"".join(_payload(i) for i in v.items)
    if isinstance(v, dict):
        return b"".join(struct.pack(">b", _tid(x)) + _payload(k) + _payload(x) for k, x in v.items()) + b"\x00"
    raise TypeError(type(v))
def _tid(v):
    for cls, t in ((Byte, 1), (Short, 2), (Int, 3), (Long, 4), (Float, 5), (str, 8), (ListTag, 9), (dict, 10), (LongArray, 12)):
        if isinstance(v, cls): return t
    raise TypeError(type(v))
def nbt(root):
    return b"\x0a" + _payload("") + _payload(root)

def state_nbt(state):
    name, _, props = state.partition("[")
    tag = {"Name": name}
    if props: tag["Properties"] = {k: v for k, v in (p.split("=") for p in props.rstrip("]").split(","))}
    return tag

def pack(idx, n):
    bits = max(4, int(math.ceil(math.log2(n))))
    per = 64 // bits; cnt = (4096 + per - 1) // per
    v = np.zeros(cnt * per, np.uint64); v[:4096] = idx.astype(np.uint64)
    v = v.reshape(cnt, per) << (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    return np.bitwise_or.reduce(v, axis=1).view(np.int64)

BIOMES = ["minecraft:plains", "minecraft:meadow", "minecraft:forest", "minecraft:birch_forest", "minecraft:dark_forest", "minecraft:taiga",
          "minecraft:old_growth_spruce_taiga", "minecraft:snowy_slopes", "minecraft:jagged_peaks", "minecraft:stony_peaks", "minecraft:badlands",
          "minecraft:savanna_plateau", "minecraft:beach", "minecraft:river", "minecraft:ocean", "minecraft:deep_ocean", "minecraft:lukewarm_ocean",
          "minecraft:lush_caves", "minecraft:dripstone_caves", "minecraft:deep_dark", "minecraft:flower_forest", "minecraft:windswept_forest",
          "minecraft:crimson_forest", "minecraft:the_end"]
BI = {b.split(":")[1]: i for i, b in enumerate(BIOMES)}
def build_biomes():
    """Біом поверхні для кожної клітинки 4×4: з категорії, виду лісу, висоти й «зіпсованості»."""
    c4 = cat[2::4, 2::4]; t4 = TOP[2::4, 2::4]; fk = FOREST_KIND[2::4, 2::4]; co = corr[2::4, 2::4]
    b = np.full(c4.shape, BI["plains"], np.uint8)
    b[c4 == MEADOW] = BI["meadow"]; b[c4 == HIGH] = BI["windswept_forest"]
    for k_, nm_ in ((1, "forest"), (2, "birch_forest"), (3, "dark_forest"), (4, "taiga")): b[(c4 == FORESTC) & (fk == k_)] = BI[nm_]
    b[(c4 == FORESTC) & (fk == 4) & (t4 > 110)] = BI["old_growth_spruce_taiga"]
    b[np.isin(c4, [STONE, BASALT, LAVA])] = BI["stony_peaks"]
    b[c4 == SNOW] = BI["snowy_slopes"]; b[(c4 == SNOW) & (t4 > 168)] = BI["jagged_peaks"]
    b[c4 == TERRA] = BI["badlands"]
    b[(co > 0.35) & ~np.isin(c4, [SEABED, SAND, RIVERBED])] = BI["crimson_forest"]   # v6: червоний край вулкана (червоний туман, спори)
    b[(c4 == FORTC) & (tF[2::4, 2::4] < 0.95)] = BI["the_end"]                       # серце обсидіанового краю
    b[c4 == SAND] = BI["beach"]; b[c4 == RIVERBED] = BI["river"]
    sea_ = c4 == SEABED
    b[sea_] = BI["ocean"]; b[sea_ & (t4 < SEA - 18)] = BI["deep_ocean"]
    PX_, PZ_ = S(512, 150)
    b[sea_ & (np.hypot(np.arange(0, SIZE, 4)[None, :] + 2 - PX_, np.arange(0, SIZE, 4)[:, None] + 2 - PZ_) < 260)] = BI["lukewarm_ocean"]
    fx_, fz_ = S(400, 540)
    b[(c4 == MEADOW) & (np.hypot(np.arange(0, SIZE, 4)[None, :] + 2 - fx_, np.arange(0, SIZE, 4)[:, None] + 2 - fz_) < 60)] = BI["flower_forest"]
    return b
CAVE_BIOME = {"mushroom": BI["lush_caves"], "lake": BI["dripstone_caves"], "abyss": BI["deep_dark"], "crystal": BI["dripstone_caves"]}
def pack_biomes(idx64, n):
    bits = max(1, int(math.ceil(math.log2(n)))); per = 64 // bits; cnt = (64 + per - 1) // per
    v = np.zeros(cnt * per, np.uint64); v[:64] = idx64.astype(np.uint64)
    v = v.reshape(cnt, per) << (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    return np.bitwise_or.reduce(v, axis=1).view(np.int64)

def biome_of(c):
    return {SEABED: "minecraft:ocean", SAND: "minecraft:beach", FORESTC: "minecraft:forest", STONE: "minecraft:stony_peaks",
            BASALT: "minecraft:stony_peaks", LAVA: "minecraft:stony_peaks", TERRA: "minecraft:badlands", HIGH: "minecraft:windswept_forest",
            MEADOW: "minecraft:meadow"}.get(c, "minecraft:plains")

# v5: сніг пласкими латками на скелях під сніговою лінією, трохи пухкого снігу на вершинах (нотатки 19, розд. 8); кладемо під споруди
_n0 = len(EDITS)
_sn = fbm(wx, wz, 97, 1, f0=0.05)
_snl = np.where(dRidgeW < 100 * K, SNOWLINE_W, 140)
_zz, _xx = np.nonzero((cat == STONE) & (TOP > _snl - 18) & (_sn > 0.25) & (tM > 0.75) & ~SLOTMASK & ~RES_MASK)
_lay = nrng.integers(1, 4, _zz.size)
for L_ in (1, 2, 3): _sel = _lay == L_; put(_xx[_sel], TOP[_zz[_sel], _xx[_sel]] + 1, _zz[_sel], f"snow[layers={L_}]")
_moved = EDITS[_n0:]; del EDITS[_n0:]; EDITS[:0] = _moved
TOPB[(cat == SNOW) & (_sn > 0.5) & (noise_col % 5 == 0)] = sid("snow_block")   # v10: без розсипчастого снігу
# v5: гори зі смугами (синус по висоті з шумом) і кольором за нахилом: на освітлених схилах (сонце з південного заходу)
# кальцит і андезит, у тіні туф і глибинний сланець (нотатки 19, розд. 8)
_gz2, _gx2 = np.gradient(TOP.astype(float))
_sun = (_gx2 - _gz2) / 1.414 + 0.35 * fbm(wx, wz, 95, 1, f0=0.03)
ROCK = np.where(np.isin(cat, [STONE, SNOW]) & (tL < 0.97) & ~CLIFF_STRIP & ~STREETMASK, np.where(_sun > 0, 1, 2), 0).astype(np.uint8)
ROCK[(tM < 0.8) & np.isin(cat, [STONE, BASALT]) & ~ISLAND] = 3   # v6: вулкан — бордові й чорні смуги
STRIPE_PH = np.round(6 * fbm(wx, wz, 96, 1, f0=0.012)).astype(np.int64)
SUNNY = np.array(mix("stone", "andesite", "stone", "calcite", "stone", "andesite", "andesite", "calcite", "stone", "stone"), np.uint16)
SHADY = np.array(mix("tuff", "stone", "deepslate", "tuff", "stone", "cobbled_deepslate", "tuff", "andesite", "deepslate", "stone"), np.uint16)
VOLC = np.array(mix("blackstone", "basalt", "netherrack", "blackstone", "smooth_basalt", "netherrack", "netherrack", "basalt", "blackstone", "magma_block"), np.uint16)
log(f"збираю правки: {sum(e[0].size for e in EDITS)} блоків")
EX = np.concatenate([e[0] for e in EDITS]); EY = np.concatenate([e[1] for e in EDITS])
EZ = np.concatenate([e[2] for e in EDITS]); EI = np.concatenate([e[3] for e in EDITS])
ok = (EX >= 0) & (EX < SIZE) & (EZ >= 0) & (EZ < SIZE) & (EY >= YMIN) & (EY < YMIN + NY)
EX, EY, EZ, EI = EX[ok], EY[ok], EZ[ok], EI[ok]
chunk_of = (EZ // 16) * (SIZE // 16) + (EX // 16)
order = np.argsort(chunk_of, kind="stable")
EX, EY, EZ, EI, chunk_of = EX[order], EY[order], EZ[order], EI[order], chunk_of[order]
bounds = np.searchsorted(chunk_of, np.arange((SIZE // 16) ** 2 + 1))
sign_by_chunk = {}
for s_ in SIGNS: sign_by_chunk.setdefault((s_[0] // 16, s_[2] // 16), []).append(s_)
be_by_chunk = {}
BIOME2D = build_biomes()
log("біоми готові")
sign_pos = {(x, y, z) for x, y, z, _ in SIGNS}
for e_ in {(x, y, z): (x, y, z, i) for x, y, z, i in BLOCK_ENTITIES if (x, y, z) not in sign_pos}.values():
    if 0 <= e_[0] < SIZE and 0 <= e_[2] < SIZE: be_by_chunk.setdefault((e_[0] // 16, e_[2] // 16), []).append(e_)

STATE_NBT = None
NC = SIZE // 16
MAP = np.zeros((SIZE, SIZE), np.uint16)   # верхній блок для карти згори
region_dir = os.path.join(OUT, "region"); os.makedirs(region_dir, exist_ok=True)
for f in os.listdir(region_dir): os.remove(os.path.join(region_dir, f))
regions = {}
# v10: автоперевірка прохідності — у тунелі, каньйоні й на мосту до кратера ноги й голова вільні, під ногами не лава
CHK, BLOCKED = {}, {}
def _chk(name, x, z, y): CHK.setdefault((x // 16, z // 16), []).append((name, x, z, y))
for z_ in range(TUN_N[1] - 10, TUN_S[1] + 1): _chk("тунель", TUN_S[0], z_, int(tun_floor(z_)))
for t_ in np.linspace(0.03, 0.97, 200):
    x_, z_ = int(cax + (cbx - cax) * t_), int(caz + (cbz - caz) * t_); _chk("каньйон", x_, z_, int(TOP[z_, x_]) + 1)
for x_ in range(int(cbx) + 1, int(bend_x)): _chk("міст до кратера", x_, int(VCZ), ISL_Y + 1)
for nm_, x_, z_, y_ in VCHK: _chk(nm_, x_, z_, y_)
PASS_OK = {"air", "cave_air", "light", "snow", "water", "chain", "vine", "short_grass", "tall_grass", "fern", "large_fern",
           "red_mushroom", "brown_mushroom", "dead_bush", "glow_lichen", "moss_carpet"}
PASS_SUB = ("stairs", "slab", "carpet", "lantern", "torch", "rail", "pressure_plate", "sign", "button", "sapling", "petals", "flower", "tulip", "poppy", "dandelion", "orchid")
def _pass(st):
    nm_ = st.split(":")[-1].split("[")[0]
    return nm_ in PASS_OK or any(k_ in nm_ for k_ in PASS_SUB)
ys_all = np.arange(YMIN, YMIN + NY)[:, None, None]
for cz in range(NC):
    for cx in range(NC):
        x0, z0 = cx * 16, cz * 16
        T = TOP[z0:z0 + 16, x0:x0 + 16]; Wl = WL[z0:z0 + 16, x0:x0 + 16]; Ll = LL[z0:z0 + 16, x0:x0 + 16]
        b = np.zeros((NY, 16, 16), np.uint16)
        solid = ys_all <= T[None]
        b[solid] = STONE_ID
        b[solid & (ys_all < 0)] = DEEP
        sub = (ys_all > T[None] - 5) & (ys_all < T[None])
        b = np.where(sub, SUBB[z0:z0 + 16, x0:x0 + 16][None], b)
        terr = (cat[z0:z0 + 16, x0:x0 + 16] == TERRA)[None] & (ys_all > T[None] - 30) & (ys_all <= T[None])
        if terr.any():
            bands = np.array(BANDS, np.uint16)[(ys_all // 2 % len(BANDS)).astype(np.int64)]
            b = np.where(terr, np.broadcast_to(bands, b.shape), b)
        b = np.where(ys_all == T[None], TOPB[z0:z0 + 16, x0:x0 + 16][None], b)
        rk_ = ROCK[z0:z0 + 16, x0:x0 + 16]
        if rk_.any():
            idx_ = ((ys_all + STRIPE_PH[z0:z0 + 16, x0:x0 + 16][None]) // 3) % len(SUNNY)
            stone_top = np.isin(cat[z0:z0 + 16, x0:x0 + 16], [STONE, BASALT])[None]
            m_ = (rk_ > 0)[None] & (ys_all > T[None] - np.where(rk_ == 3, 170, 90)[None]) & ((ys_all < T[None]) | ((ys_all == T[None]) & stone_top))
            b = np.where(m_, np.where(rk_[None] == 1, SUNNY[idx_], np.where(rk_[None] == 2, SHADY[idx_], VOLC[idx_])), b)
        b[(ys_all > T[None]) & (ys_all <= Wl[None])] = WATER
        b[(ys_all > T[None]) & (ys_all <= Ll[None])] = LAVA_ID
        b[0] = BEDROCK
        cave_mask(x0, z0, b, T)
        lo, hi = bounds[cz * NC + cx], bounds[cz * NC + cx + 1]
        if hi > lo:
            b[EY[lo:hi] - YMIN, EZ[lo:hi] - z0, EX[lo:hi] - x0] = EI[lo:hi]
        for nm_, x_, z_, y_ in CHK.get((cx, cz), ()):
            for yy, part in ((y_ - 1, "floor"), (y_, "feet"), (y_ + 1, "head")):
                st = STATES[b[yy - YMIN, z_ - z0, x_ - x0]]
                if ("lava" in st) if part == "floor" else not _pass(st): BLOCKED.setdefault(nm_, []).append((x_, yy, z_, st.split(":")[-1][:28]))
        nz = b != AIR
        topi = NY - 1 - np.argmax(nz[::-1], axis=0)
        MAP[z0:z0 + 16, x0:x0 + 16] = np.take_along_axis(b, topi[None], 0)[0]
        bsurf = BIOME2D[cz * 4:cz * 4 + 4, cx * 4:cx * 4 + 4]
        cave_cells = []
        for n_, X_, Z_, R_, fy_, hh_, kind_ in HALLS_W:
            if kind_ in CAVE_BIOME and abs(X_ - (x0 + 8)) < R_ + 16 and abs(Z_ - (z0 + 8)) < R_ + 16: cave_cells.append((X_, Z_, R_ + 6, fy_ - 6, fy_ + hh_ + 6, CAVE_BIOME[kind_]))
        sections = []
        for s in range(NY // 16):
            sec = b[s * 16:(s + 1) * 16].reshape(-1)
            pal, inv = np.unique(sec, return_inverse=True)
            bs = {"palette": ListTag(10, [state_nbt(STATES[p]) for p in pal])}
            if len(pal) > 1: bs["data"] = LongArray(pack(inv, len(pal)))
            b3 = np.broadcast_to(bsurf, (4, 4, 4)).copy()   # y, z, x
            ybase = YMIN + s * 16
            for X_, Z_, R_, y0_, y1_, bid_ in cave_cells:
                for yy in range(4):
                    yc = ybase + yy * 4 + 2
                    if not (y0_ <= yc <= y1_): continue
                    xs_ = x0 + np.arange(4) * 4 + 2; zs_ = z0 + np.arange(4) * 4 + 2
                    m_ = np.hypot(xs_[None, :] - X_, zs_[:, None] - Z_) < R_
                    b3[yy][m_] = bid_
            upal, uinv = np.unique(b3.reshape(-1), return_inverse=True)
            bio = {"palette": ListTag(8, [BIOMES[i] for i in upal])}
            if len(upal) > 1: bio["data"] = LongArray(pack_biomes(uinv, len(upal)))
            sections.append({"Y": Byte(s - 4), "block_states": bs, "biomes": bio})
        bes = []
        for x, y, z, lines in sign_by_chunk.get((cx, cz), []):
            msgs = [json.dumps({"text": l}, ensure_ascii=False) for l in (lines + [""] * 4)[:4]]
            text = {"messages": ListTag(8, msgs), "color": "black", "has_glowing_text": Byte(0)}
            bes.append({"id": "minecraft:sign", "x": Int(x), "y": Int(y), "z": Int(z), "keepPacked": Byte(0),
                        "front_text": text, "back_text": dict(text), "is_waxed": Byte(1)})
        for x, y, z, be in be_by_chunk.get((cx, cz), []):
            base = STATES[b[y - YMIN, z - z0, x - x0]].split("[")[0].replace("minecraft:", "")
            if be_id(base) != be.replace("minecraft:", ""): continue  # блок перекрито
            ent = {"id": be, "x": Int(x), "y": Int(y), "z": Int(z), "keepPacked": Byte(0)}
            ent.update(BE_EXTRA.get((x, y, z), {}))
            bes.append(ent)
        chunk = {"DataVersion": Int(DATA_VERSION), "xPos": Int(cx), "zPos": Int(cz), "yPos": Int(-4), "Status": "minecraft:full",
                 "LastUpdate": Long(0), "InhabitedTime": Long(0), "isLightOn": Byte(0), "sections": ListTag(10, sections),
                 "block_entities": ListTag(10, bes), "block_ticks": ListTag(10, []), "fluid_ticks": ListTag(10, []),
                 "PostProcessing": ListTag(9, [ListTag(2, []) for _ in range(NY // 16)]), "Heightmaps": {},
                 "structures": {"References": {}, "starts": {}}}
        regions.setdefault((cx >> 5, cz >> 5), {})[(cx & 31, cz & 31)] = zlib.compress(nbt(chunk), 6)
    if cz % 8 == 7: log(f"чанки: рядок {cz + 1}/{NC}")
for (rx, rz), chunks in regions.items():
    header, ts, body, sector = bytearray(4096), bytearray(4096), bytearray(), 2
    for (lx, lz), data in chunks.items():
        payload = struct.pack(">ib", len(data) + 1, 2) + data
        payload += b"\x00" * (-len(payload) % 4096); n = len(payload) // 4096
        struct.pack_into(">i", header, 4 * (lx + lz * 32), (sector << 8) | n)
        struct.pack_into(">i", ts, 4 * (lx + lz * 32), int(time.time()))
        body += payload; sector += n
    with open(os.path.join(region_dir, f"r.{rx}.{rz}.mca"), "wb") as f: f.write(bytes(header) + bytes(ts) + bytes(body))
log("регіони записано")

# ---------- level.dat ----------
flat_layers = ListTag(10, [{"block": "minecraft:bedrock", "height": Int(1)}, {"block": "minecraft:stone", "height": Int(96)},
                           {"block": "minecraft:sand", "height": Int(6)}, {"block": "minecraft:water", "height": Int(24)}])
level = {"Data": {
    "DataVersion": Int(DATA_VERSION), "version": Int(19133), "initialized": Byte(1), "LevelName": "Острів 1 (макет v10)",
    "Version": {"Id": Int(DATA_VERSION), "Name": VERSION_NAME, "Series": "main", "Snapshot": Byte(0)},
    "GameType": Int(1), "Difficulty": Byte(0), "hardcore": Byte(0), "allowCommands": Byte(1), "DifficultyLocked": Byte(0),
    "SpawnX": Int(SPAWN[0]), "SpawnY": Int(SPAWN[1]), "SpawnZ": Int(SPAWN[2]), "SpawnAngle": Float(180.0),
    "Time": Long(6000), "DayTime": Long(6000), "LastPlayed": Long(int(time.time() * 1000)),
    "raining": Byte(0), "thundering": Byte(0), "rainTime": Int(1000000), "thunderTime": Int(1000000), "clearWeatherTime": Int(1000000),
    "GameRules": {"doDaylightCycle": "false", "doWeatherCycle": "false", "doMobSpawning": "false", "randomTickSpeed": "0",
                  "doFireTick": "false", "spawnRadius": "0", "keepInventory": "true", "doTileDrops": "false"},
    "DataPacks": {"Enabled": ListTag(8, ["vanilla"]), "Disabled": ListTag(8, [])},
    "WorldGenSettings": {"seed": Long(20261103), "generate_features": Byte(0), "bonus_chest": Byte(0), "dimensions": {
        "minecraft:overworld": {"type": "minecraft:overworld", "generator": {"type": "minecraft:flat", "settings": {
            "biome": "minecraft:ocean", "features": Byte(0), "lakes": Byte(0), "layers": flat_layers, "structure_overrides": ListTag(8, [])}}},
        "minecraft:the_nether": {"type": "minecraft:the_nether", "generator": {"type": "minecraft:noise", "settings": "minecraft:nether",
            "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}}},
        "minecraft:the_end": {"type": "minecraft:the_end", "generator": {"type": "minecraft:noise", "settings": "minecraft:end",
            "biome_source": {"type": "minecraft:the_end"}}}}},
}}
for nm_ in sorted({n_ for v_ in CHK.values() for n_, *_ in v_}):
    bl_ = BLOCKED.get(nm_, []); log(f"прохідність, {nm_}: " + ("OK" if not bl_ else f"{len(bl_)} перешкод, напр. {bl_[:8]}"))
with gzip.open(os.path.join(OUT, "level.dat"), "wb") as f: f.write(nbt(level))

# ---------- карта згори (PNG) ----------
def color_of(state):
    n = state.split("[")[0].replace("minecraft:", "")
    table = [("water", (52, 98, 188)), ("lava", (230, 90, 20)), ("magma", (150, 60, 30)), ("grass_block", (98, 152, 60)), ("moss", (90, 130, 50)),
             ("white_terracotta", (210, 180, 165)), ("birch_leaves", (110, 150, 70)), ("azalea", (90, 140, 50)), ("leaves", (60, 110, 40)),
             ("acacia", (190, 100, 50)), ("deepslate_tile", (62, 62, 72)), ("dark_oak", (70, 50, 30)), ("spruce", (105, 78, 48)), ("brick_stairs", (150, 80, 60)),
             ("red_sandstone", (185, 95, 40)), ("sand", (220, 205, 150)), ("dirt_path", (160, 130, 80)),
             ("farmland", (110, 75, 45)), ("wheat", (200, 180, 70)), ("carrots", (110, 170, 60)), ("potatoes", (90, 150, 60)),
             ("terracotta", (170, 100, 70)), ("blackstone", (40, 36, 40)), ("basalt", (70, 70, 75)), ("gravel", (130, 125, 120)),
             ("stone_bricks", (125, 125, 125)), ("cobblestone", (115, 115, 115)), ("stone", (125, 125, 125)), ("andesite", (135, 135, 135)),
             ("planks", (160, 120, 75)), ("stairs", (110, 75, 50)), ("log", (100, 75, 45)), ("wool", (230, 230, 230)), ("concrete", (220, 60, 50)),
             ("podzol", (110, 80, 40)), ("coarse_dirt", (120, 90, 60)), ("snow", (240, 240, 240)), ("glass", (200, 220, 230)),
             ("short_grass", (98, 152, 60)), ("fern", (80, 130, 50)), ("lily_pad", (40, 120, 40)), ("calcite", (225, 225, 220)),
             ("tiles", (60, 60, 70)), ("bricks", (150, 80, 60)), ("acacia", (190, 100, 50)), ("red_sandstone", (185, 95, 40)),
             ("tuff", (110, 110, 100)), ("deepslate", (75, 75, 80)), ("sunflower", (220, 190, 40)), ("hay", (210, 180, 60)),
             ("pumpkin", (220, 130, 30)), ("birch_wood", (210, 205, 190)), ("_wood", (80, 60, 40)), ("mud", (90, 80, 70))]
    for key, col in table:
        if key in n: return col
    if any(f in n for f in ("poppy", "tulip", "dandelion", "cornflower", "bluet", "daisy", "allium", "lily_of")): return (98, 152, 60)
    return (150, 150, 140)
lut = np.array([color_of(s) for s in STATES], np.uint8)
img = lut[MAP]
shade = np.clip(1 + np.gradient(TOP.astype(float), axis=1) * 0.08, 0.75, 1.25)
img = np.clip(img * shade[..., None], 0, 255).astype(np.uint8)
for name, x, z in POI:
    img[max(z - 3, 0):z + 4, max(x - 3, 0):x + 4] = (230, 30, 30)
def write_png(path, a):
    raw = b"".join(b"\x00" + a[r].tobytes() for r in range(a.shape[0]))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", a.shape[1], a.shape[0], 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))
write_png(os.path.join(OUT, "karta-zgory.png"), img)
with open(os.path.join(OUT, "sporudy_komandy.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(["#LOAD"] + CMDS_LOAD + ["#PLACE"] + CMDS_PLACE + ["#FIX"] + CMDS_FIX) + "\n")
import base64, hashlib
LANDMARKS = {"Обсидіановий собор", "Готична вежа", "Вежа порту", "Лавові глибини", "Вогняна вежа", "Велетенське дерево", "Меч героя", "Руїни фортеці", "Маяк", "Вежа мага", "Літаючі острови", "Голова колоса",
             "Акведук", "Скельна застава", "Північний маяк", "Небесна вежа", "Небесний острів", "Кам'яне коло", "Водоспад"}
SECRETS = {"Безодня", "Схованка контрабандистів", "Морський грот", "Затонулі руїни", "Курган"}
HUB_NAMES = {"start": "Стартове місто", "village": "Узлісся", "fishing": "Рибальське село", "port": "Порт",
             "hills": "Хвойне село", "south": "Село Пшеничне"}
poi_rows, seen_ = [], set()
for i, (name, x, z) in enumerate(POI):
    if (name, x, z) in seen_: continue
    seen_.add((name, x, z))
    tier = "hub" if name in HUB_NAMES.values() else "landmark" if name in LANDMARKS else "secret" if name in SECRETS else "hidden"
    poi_rows.append({"id": f"p{i:03d}", "name": name, "x": int(x), "y": int(top_at(x, z) + 1), "z": int(z), "zone": ZONES[zone_at(x, z) - 1][0] if zone_at(x, z) else "sea",
                     "tier": tier, "radius": 24 if tier == "landmark" else 14})
by_name = {r["name"]: r["id"] for r in poi_rows}
manifest = {
    "version": 10, "world": "Ostriv1", "size": SIZE, "spawn": list(SPAWN),
    "preview": {"scope": "v9 surface; v8 caves retained", "template_placement": "requires separate vanilla server step",
                "elf_forest": ELF_FOREST, "cathedral_center": ELF_TREE, "cathedral_radius": ELF_CORE_R,
                "gate": ELF_GATE, "market": ELF_MARKET, "garden_pond": ELF_POND,
                "spring_gate": SPRING_GATE, "surface_stream": RIVER_SURF,
                "dungeon_rooms_built": False, "elf_city_built": False, "elf_tree_village_built": True},
    "zones": [{"id": zid, "name": zn, "levels": zl, "hub": zh, "index": i + 1} for i, (zid, zn, zl, zh, _) in enumerate(ZONES)],
    "zone_raster": {"cell": ZCELL, "width": ZN, "encoding": "uint8 base64 (0 = море, інакше index зони)",
                    "data": base64.b64encode(ZONE2D.tobytes()).decode()},
    "hubs": [{"id": k, "name": v, "poi": by_name.get(v)} for k, v in HUB_NAMES.items()],
    "pois": poi_rows, "anchors": ANCHORS,
    "quests": [{"hub": h, "order": n + 1, "title": q, "target": by_name.get(t), "target_name": t, "breadcrumb": t in HUB_NAMES.values()}
               for h, qs in QUESTS.items() for n, (q, t) in enumerate(qs)],
}
manifest["hash"] = hashlib.sha256(json.dumps(manifest, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
with open(os.path.join(OUT, "ostriv-1.map.json"), "w", encoding="utf-8") as f: json.dump(manifest, f, ensure_ascii=False, indent=1)
missing_ = [q["target_name"] for q in manifest["quests"] if not q["target"]]
log(f"маніфест: зон {len(ZONES)}, точок {len(poi_rows)}, якорів {len(ANCHORS)}, квестів {len(manifest['quests'])}, без цілі {missing_}")
with open(os.path.join(OUT, "tochky.txt"), "w", encoding="utf-8") as f:
    for name, x, z in POI: f.write(f"{name}\t/tp @s {x} {top_at(x, z) + 3} {z}\n")
log(f"готово: точок {len(POI)}, блоків-станів {len(STATES)}, спавн {SPAWN}")
