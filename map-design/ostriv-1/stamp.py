# Читання й поворот шаблонів споруд (.nbt) для island1_world.py: генератор сам ставить їх у світ,
# вирівнює рельєф під ними й не покладається на /place у готових чанках.
import gzip, io, struct, zipfile

def read_nbt(data):
    b = io.BytesIO(data)
    def rd(n): return b.read(n)
    def payload(t):
        if t == 1: return struct.unpack(">b", rd(1))[0]
        if t == 2: return struct.unpack(">h", rd(2))[0]
        if t == 3: return struct.unpack(">i", rd(4))[0]
        if t == 4: return struct.unpack(">q", rd(8))[0]
        if t == 5: return struct.unpack(">f", rd(4))[0]
        if t == 6: return struct.unpack(">d", rd(8))[0]
        if t == 7: n = struct.unpack(">i", rd(4))[0]; return rd(n)
        if t == 8: n = struct.unpack(">H", rd(2))[0]; return rd(n).decode("utf-8", "replace")
        if t == 9:
            et, n = struct.unpack(">bi", rd(5)); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = struct.unpack(">b", rd(1))[0]
                if tt == 0: return d
                n = struct.unpack(">H", rd(2))[0]; k = rd(n).decode("utf-8", "replace"); d[k] = payload(tt)
        if t == 11: n = struct.unpack(">i", rd(4))[0]; return list(struct.unpack(">%di" % n, rd(4 * n)))
        if t == 12: n = struct.unpack(">i", rd(4))[0]; return list(struct.unpack(">%dq" % n, rd(8 * n)))
        raise ValueError(t)
    t = struct.unpack(">b", rd(1))[0]; n = struct.unpack(">H", rd(2))[0]; rd(n)
    return payload(t)

LEGACY = {"minecraft:grass": "minecraft:short_grass", "minecraft:grass_path": "minecraft:dirt_path"}   # старі назви зі старих шаблонів

def state_str(p):
    props = p.get("Properties") or {}
    return LEGACY.get(p["Name"], p["Name"]) +("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")

def split_state(s):
    name, _, rest = s.partition("[")
    props = dict(kv.split("=", 1) for kv in rest.rstrip("]").split(",")) if rest else {}
    return name, props

CW = {"north": "east", "east": "south", "south": "west", "west": "north"}
RAIL = {"north_south": "east_west", "east_west": "north_south", "ascending_east": "ascending_south", "ascending_south": "ascending_west",
        "ascending_west": "ascending_north", "ascending_north": "ascending_east", "south_east": "south_west", "south_west": "north_west",
        "north_west": "north_east", "north_east": "south_east"}

def rot_state(s, k):
    """Повертає стан блока на k×90° за годинниковою стрілкою (як Rotation.CLOCKWISE_90 у грі)."""
    if k % 4 == 0 or "[" not in s: return s
    name, p = split_state(s)
    for _ in range(k % 4):
        q = dict(p)
        if p.get("facing") in CW: q["facing"] = CW[p["facing"]]
        if p.get("axis") in ("x", "z"): q["axis"] = "z" if p["axis"] == "x" else "x"
        if "rotation" in p and p["rotation"].isdigit(): q["rotation"] = str((int(p["rotation"]) + 4) % 16)
        if "shape" in p and p["shape"] in RAIL and "rail" in name: q["shape"] = RAIL[p["shape"]]
        if "orientation" in p:
            a, _, b = p["orientation"].partition("_")
            if a in CW: a = CW[a]
            elif b in CW: b = CW[b]
            q["orientation"] = f"{a}_{b}"
        for d in CW:
            if d in p: q[CW[d]] = p[d]
        p = q
    return name + "[" + ",".join(f"{k_}={v}" for k_, v in sorted(p.items())) + "]"

def rot_pos(x, z, sx, sz, k):
    k %= 4
    if k == 0: return x, z
    if k == 1: return sz - 1 - z, x
    if k == 2: return sx - 1 - x, sz - 1 - z
    return z, sx - 1 - x

_cache = {}
def load(source, entry):
    """Блоки шаблону: розмір, список (x, y, z, стан, nbt) і напрям «переду» за з'єднувачем (jigsaw)."""
    key = (source, entry)
    if key in _cache: return _cache[key]
    if source.endswith(".nbt"): raw = open(source, "rb").read()
    else:
        with zipfile.ZipFile(source) as z: raw = z.read(entry)
    try: raw = gzip.decompress(raw)
    except OSError: pass
    d = read_nbt(raw)
    pal = d.get("palette") or (d.get("palettes") or [[]])[0]
    states = [state_str(p) for p in pal]
    blocks, fronts = [], []
    for bl in d.get("blocks", []):
        if "state" not in bl: continue
        x, y, z = bl["pos"]; s = states[bl["state"]]; nbt = bl.get("nbt")
        name = s.split("[")[0]
        if name == "minecraft:jigsaw":
            o = split_state(s)[1].get("orientation", "")
            fronts.append((y, o.split("_")[0], x, z))
            s = (nbt or {}).get("final_state", "minecraft:air") or "minecraft:air"; nbt = None
        elif name in ("minecraft:structure_block", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault"):
            s, nbt = "minecraft:air", None
        elif name == "minecraft:structure_void":
            continue
        blocks.append((x, y, z, s, nbt))
    front = None
    if fronts:
        fronts.sort()
        front = next((f[1] for f in fronts if f[1] in CW), None)
    res = {"size": tuple(d["size"]), "blocks": blocks, "front": front}
    _cache[key] = res
    return res
