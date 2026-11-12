# Карта згори з готового світу (формат 26.x): верхній непрозорий блок кожного стовпця.
import sys, os, zlib, struct, math, glob
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbtscan import read_nbt

REGION, OUT, SIZE = sys.argv[1], sys.argv[2], int(sys.argv[3])
SKIP = {"minecraft:air", "minecraft:cave_air", "minecraft:light", "minecraft:short_grass", "minecraft:tall_grass", "minecraft:barrier"}
COL = [("oxidized", (88, 168, 140)), ("weathered", (104, 150, 112)), ("copper", (190, 112, 80)), ("froglight", (150, 230, 160)), ("white_concrete", (235, 235, 235)), ("water", (52, 98, 188)), ("seagrass", (52, 98, 188)), ("kelp", (40, 90, 150)), ("lava", (230, 90, 20)), ("magma", (150, 60, 30)),
       ("grass_block", (98, 152, 60)), ("moss", (90, 130, 50)), ("cherry_leaves", (235, 160, 200)), ("birch_leaves", (110, 150, 70)),
       ("spruce_leaves", (45, 85, 50)), ("dark_oak_leaves", (40, 80, 30)), ("leaves", (60, 110, 40)),
       ("acacia", (190, 100, 50)), ("red_sandstone", (185, 95, 40)), ("white_terracotta", (210, 180, 165)), ("deepslate_tile", (62, 62, 72)),
       ("tuff", (110, 110, 100)), ("deepslate", (75, 75, 80)), ("sunflower", (220, 190, 40)), ("carrots", (110, 170, 60)), ("potatoes", (90, 150, 60)), ("beetroots", (120, 140, 60)), ("birch_wood", (210, 205, 190)),
       ("_wood", (80, 60, 40)), ("pumpkin", (220, 130, 30)), ("crying_obsidian", (70, 25, 110)), ("obsidian", (28, 20, 40)),
       ("purple", (120, 50, 170)), ("white_concrete", (232, 232, 232)), ("netherrack", (110, 40, 40)), ("red_nether", (90, 22, 22)), ("crimson", (130, 30, 30)),
       ("quartz", (235, 230, 225)), ("smooth_basalt", (60, 60, 65)), ("snow", (240, 240, 245)),
       ("sand", (220, 205, 150)), ("dirt_path", (160, 130, 80)), ("farmland", (110, 75, 45)), ("wheat", (200, 180, 70)),
       ("terracotta", (170, 100, 70)), ("blackstone", (40, 36, 40)), ("basalt", (70, 70, 75)), ("gravel", (130, 125, 120)),
       ("wool", (225, 225, 225)), ("concrete", (220, 60, 50)), ("planks", (160, 120, 75)), ("stairs", (110, 75, 50)), ("slab", (140, 110, 80)),
       ("log", (100, 75, 45)), ("bricks", (130, 110, 105)), ("cobble", (115, 115, 115)), ("stone", (125, 125, 125)), ("andesite", (135, 135, 135)),
       ("podzol", (110, 80, 40)), ("coarse_dirt", (120, 90, 60)), ("glass", (200, 220, 230)), ("calcite", (225, 225, 220)), ("tiles", (60, 60, 70)),
       ("mud", (120, 100, 80)), ("hay", (210, 180, 60)), ("prismarine", (90, 160, 150)), ("quartz", (235, 230, 225)), ("bone", (230, 225, 200)),
       ("amethyst", (150, 100, 200)), ("mushroom", (170, 60, 50)), ("lily", (40, 120, 40))]
FLOWERS = ("poppy", "tulip", "dandelion", "cornflower", "bluet", "daisy", "allium", "lily_of", "fern", "bush", "sapling", "berry", "pickle")
def color(name):
    n = name.replace("minecraft:", "")
    if any(f in n for f in FLOWERS): return (98, 152, 60)
    for k, c in COL:
        if k in n: return c
    return (150, 150, 140)

img = np.zeros((SIZE, SIZE, 3), np.uint8); hgt = np.zeros((SIZE, SIZE), np.int32); cache = {}
for path in glob.glob(os.path.join(REGION, "r.*.*.mca")):
    d = open(path, "rb").read()
    for i in range(1024):
        off = struct.unpack(">i", d[i * 4:i * 4 + 4])[0]
        if not off: continue
        sec = off >> 8; ln, ct = struct.unpack(">ib", d[sec * 4096:sec * 4096 + 5])
        if ct != 2: continue
        ch = read_nbt(zlib.decompress(d[sec * 4096 + 5:sec * 4096 + 4 + ln]))
        cx, cz = ch["xPos"], ch["zPos"]
        if not (0 <= cx * 16 < SIZE and 0 <= cz * 16 < SIZE): continue
        done = np.zeros((16, 16), bool)
        for s in sorted(ch.get("sections", []), key=lambda s: -s["Y"]):
            bs = s.get("block_states")
            if not bs: continue
            pal = [p["Name"] for p in bs["palette"]]
            if len(pal) == 1:
                idx = np.zeros(4096, np.int64)
            else:
                bits = max(4, math.ceil(math.log2(len(pal)))); per = 64 // bits
                longs = np.array(bs["data"], np.int64).view(np.uint64)
                sh = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
                idx = ((longs[:, None] >> sh) & np.uint64((1 << bits) - 1)).reshape(-1)[:4096].astype(np.int64)
            solid = np.array([p not in SKIP for p in pal])
            blk = idx.reshape(16, 16, 16)   # y, z, x
            for y in range(15, -1, -1):
                layer = blk[y]; m = solid[layer] & ~done
                if m.any():
                    zz, xx = np.nonzero(m)
                    for z_, x_ in zip(zz, xx):
                        nm = pal[layer[z_, x_]]
                        img[cz * 16 + z_, cx * 16 + x_] = cache.setdefault(nm, color(nm)); hgt[cz * 16 + z_, cx * 16 + x_] = s["Y"] * 16 + y
                    done |= m
            if done.all(): break
_gz, _gx = np.gradient(hgt.astype(float))
shade = np.clip(1 + (-_gx - _gz) * 0.11, 0.5, 1.45)   # світло з північного заходу по обох осях
elev = np.clip(0.82 + (hgt - 64) / 260.0, 0.78, 1.25)   # вище — світліше, щоб гори читались згори
img = np.clip(img * (shade * elev)[..., None], 0, 255).astype(np.uint8)
raw = b"".join(b"\x00" + img[r].tobytes() for r in range(SIZE))
def chunk(t, x): return struct.pack(">I", len(x)) + t + x + struct.pack(">I", zlib.crc32(t + x) & 0xffffffff)
open(OUT, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))
print("ok")
