# Сканує .nbt споруди в архівах (zip/jar) без запуску їхнього коду.
import sys, os, zipfile, gzip, struct, io, json, re

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

def scan(archive, label):
    out = []
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            m = re.match(r"data/([^/]+)/structures?/(.+)\.nbt$", name)
            if not m: continue
            try:
                raw = z.read(name)
                try: raw = gzip.decompress(raw)
                except OSError: pass
                d = read_nbt(raw)
            except Exception as e:
                out.append({"set": label, "id": f"{m.group(1)}:{m.group(2)}", "error": str(e)}); continue
            if not isinstance(d.get("blocks"), list) or not d["blocks"] or "state" not in d["blocks"][0]:
                out.append({"set": label, "id": f"{m.group(1)}:{m.group(2)}", "error": "not a block template"}); continue
            pal = d.get("palette") or (d.get("palettes") or [[]])[0]
            names = [p.get("Name", "") for p in pal]
            blocks = d.get("blocks", [])
            cnt = {}
            for bl in blocks:
                if isinstance(bl, dict) and "state" in bl: cnt[bl["state"]] = cnt.get(bl["state"], 0) + 1
            nm = lambda s: names[s] if 0 <= s < len(names) else ""
            jig = sum(c for s, c in cnt.items() if nm(s) == "minecraft:jigsaw")
            air = sum(c for s, c in cnt.items() if nm(s) in ("minecraft:air", "minecraft:structure_void", "minecraft:cave_air"))
            modded = sorted({n for n in names if not n.startswith("minecraft:")})
            out.append({"set": label, "id": f"{m.group(1)}:{m.group(2)}", "path": name, "size": d.get("size"),
                        "solid": len(blocks) - air - jig, "jigsaw": jig, "modded": modded, "dv": d.get("DataVersion"),
                        "entities": len(d.get("entities", []))})
    return out

if __name__ == "__main__":
    res = []
    for arg in sys.argv[2:]:
        label, path = arg.split("=", 1)
        res += scan(path, label)
    with open(sys.argv[1], "w", encoding="utf-8") as f: json.dump(res, f, ensure_ascii=False)
    print(len(res), "templates")
