# Готує споруди варіанту А для острова 1: відбирає шаблони з завантажених наборів, складає датапак для /place template
# і записує sporudy.json (розміри й місця) для island1_world.py та CREDITS.md з авторами й ліцензіями.
# Запуск: python prep_sporudy.py <templates.json> <папка завантажень> <сервер.jar> <вихідна папка датапака>
import sys, os, json, zipfile, shutil

TEMPLATES, DOWNLOADS, VANILLA_JAR, PACK_OUT = sys.argv[1:5]
HERE = os.path.dirname(os.path.abspath(__file__))

SETS = {  # набір: (назва, ліцензія, посилання)
    "vanilla": ("Minecraft (Mojang)", "частина гри", "https://www.minecraft.net/"),
    "moogs-voyager-structures": ("MVS - Moog's Voyager Structures", "MIT (Modrinth) / LGPL-3.0 (GitHub)", "https://modrinth.com/mod/moogs-voyager-structures"),
    "mss-moogs-soaring-structures": ("MSS - Moog's Soaring Structures", "GPL-3.0 (Modrinth) / LGPL-3.0 (GitHub)", "https://modrinth.com/mod/mss-moogs-soaring-structures"),
    "mo-structures": ("Mo' Structures", "LGPL-3.0", "https://modrinth.com/mod/mo-structures"),
    "yungs-extras": ("YUNG's Extras", "LGPL-3.0", "https://modrinth.com/mod/yungs-extras"),
    "better-archeology": ("Better Archeology", "MIT", "https://modrinth.com/mod/better-archeology"),
    "trek": ("Trek", "MIT", "https://modrinth.com/datapack/trek"),
    "unwrecked-ships": ("Unwrecked Ships", "MIT", "https://modrinth.com/datapack/unwrecked-ships"),
    "hopo-better-ruined-portals": ("Hopo Better Ruined Portals", "LGPL-3.0", "https://modrinth.com/datapack/hopo-better-ruined-portals"),
}
DATAPACKS = ["trek", "unwrecked-ships", "hopo-better-ruined-portals"]   # ставляться в світ цілими

# Шаблони: id, місце на схемі (x, y), режим (ground / sky:<висота над рельєфом>), назва для таблички або None
G = "ground"
T = [
    ("mvs:1_21_0/ruins/castle_ruins", (880, 585), G, "Руїни замку"),
    ("mvs:1_21_0/ruins/tree_monument", (520, 790), G, "Кам'яне дерево"),
    ("mvs:1_21_0/ruins/ruined_beacon", (625, 855), G, None),
    ("mvs:1_21_0/ruins/stone_pillars", (300, 385), G, None),
    ("mvs:1_21_0/ruins/small_ruin", (770, 700), G, None),
    ("mvs:1_21_0/ruins/log_ruin", (430, 470), G, None),
    ("mvs:1_21_0/other_decoration/campsite", (560, 240), G, None),
    ("mvs:1_21_0/other_decoration/villager_statue", (520, 585), G, None),
    ("mvs:1_21_0/other_decoration/wooden_wheat_farm", (270, 530), G, None),
    ("mvs:1_21_0/other_decoration/lecturn_garden", (420, 600), G, None),
    ("mvs:1_21_0/gallows", (795, 480), G, None),
    ("mvs:1_21_0/small_pillager_tower", (725, 310), G, "Вежа розвідників"),
    ("mvs:1_21_0/well/rare_well/rare_well_upper", (450, 640), G, None),
    ("mvs:1_21_0/out_house/out_house", (215, 605), G, None),
    ("mvs:1_21_0/carts/large_cart_1", (535, 555), G, None),
    ("mvs:1_21_0/beach_bar", (620, 905), G, "Пляжний бар"),
    ("mvs:1_21_0/nature/medium_cherry_tree", (455, 560), G, None),
    ("mvs:1_21_0/nature/medium_cherry_tree", (640, 540), G, None),
    ("mvs:1_21_0/nature/big_oak_tree", (330, 640), G, None),
    ("mvs:1_21_0/nature/large_mushroom", (700, 700), G, None),
    ("mvs:1_21_0/nature/small_oak_pond", (600, 600), G, None),
    ("mvs:1_21_0/houses/azelea_house", (850, 770), G, "Азалієва хата"),
    ("mvs:1_21_0/houses/house", (375, 880), G, None),
    ("mvs:1_21_0/houses/diorite_tower", (860, 365), G, "Діоритова вежа"),
    ("mvs:1_21_9/houses/deepslate_house", (285, 335), G, None),
    ("mvs:1_21_0/cartographer_tower/base", (480, 725), G, "Вежа картографа"),
    ("mvs:1_21_9/houses/large_floating_island", (610, 455), "sky:70", "Небесний острів"),
    ("mss:1_21_4/small_oak_house", (385, 525), "sky:60", None),
    ("mss:1_21_4/mushroom", (700, 640), "sky:65", None),
    ("mss:1_21_5/castle_tower", (830, 255), "sky:45", "Небесна вежа"),
    ("mss:1_21_5/palm_island", (130, 505), "sky:40", None),
    ("mostructures:air/airballoon1", (520, 120), "sky:40", None),
    ("mostructures:air/airballoon3", (330, 690), "sky:45", None),
    ("mostructures:air/airballoon5", (560, 610), "sky:50", None),
    ("mostructures:barn_house/barnhouse_1", (290, 555), G, None),
    ("mostructures:lighthouse/lighthouse_1", (900, 250), G, "Північний маяк"),
    ("mostructures:tavern/tavern_1", (470, 650), G, "Таверна"),
    ("mostructures:tavern/wagon", (455, 665), G, None),
    ("mostructures:villager/tower_1", (640, 365), G, None),
    ("mostructures:moai/villager_moai", (960, 520), G, None),
    ("mostructures:moai/villager_moai", (120, 700), G, None),
    ("mostructures:abandoned/village/plains_temple1", (560, 820), G, "Покинута церква"),
    ("mostructures:market/grill", (360, 725), G, None),
    ("mostructures:market/weaponsmith", (245, 700), G, None),
    ("mostructures:mines/exterior_front_a", (795, 780), G, None),
    ("yungsextras:desert/wells/wishing_well_lg", (400, 560), G, None),
    ("betterarcheology:stonehenge_grassy", (520, 500), G, None),
    ("betterarcheology:tumulus_grassy", (280, 450), G, None),
    ("betterarcheology:ruins_sand", (850, 860), G, None),
    ("minecraft:village/plains/houses/plains_big_house_1", (600, 300), G, None),
    ("minecraft:village/plains/houses/plains_library_1", (400, 420), G, None),
    ("minecraft:village/plains/houses/plains_fisher_cottage_1", (880, 680), G, None),
    ("minecraft:village/taiga/houses/taiga_medium_house_1", (230, 300), G, None),
    ("minecraft:village/taiga/houses/taiga_shepherds_house_1", (190, 360), G, None),
    ("minecraft:pillager_outpost/watchtower_overgrown", (930, 430), G, "Покинута сторожа"),
    ("minecraft:ruined_portal/giant_portal_1", (420, 300), G, "Зруйнований портал"),
    ("minecraft:ruined_portal/portal_8", (880, 830), G, None),
    ("minecraft:shipwreck/rightsideup_full_degraded", (110, 640), G, None),
]
# Цілі споруди для /place structure (з датапаків і гри): id, місце, вирівняти радіус, назва
P = [
    ("minecraft:pillager_outpost", (760, 335), 22, "Аванпост розбійників"),
    ("hopo:portal/overworld/portal_lush", (300, 800), 12, None),
    ("hopo:portal/overworld/portal_deepslate", (655, 245), 12, None),
    ("minecraft:trail_ruins", (380, 515), 15, None),
    ("minecraft:shipwreck_beached", (985, 600), 0, None),
    ("minecraft:shipwreck", (700, 985), 0, None),
    ("minecraft:ocean_ruin_warm", (420, 55), 0, None),
    ("minecraft:ocean_ruin_cold", (1000, 300), 0, None),
    ("unwrecked_ships:ship", (470, 55), 0, "Корабель у порту"),
    ("unwrecked_ships:ship", (580, 40), 0, None),
    ("unwrecked_ships:ship", (90, 380), 0, None),
]

def source_of(m):
    if m["set"] == "vanilla": return VANILLA_JAR
    d = os.path.join(DOWNLOADS, m["set"]); return os.path.join(d, os.listdir(d)[0])
meta = {}
for x in json.load(open(TEMPLATES, encoding="utf-8")):
    if "error" not in x: meta[x["id"]] = x
if os.path.exists(PACK_OUT): shutil.rmtree(PACK_OUT)
os.makedirs(PACK_OUT)
out, used = {"templates": [], "structures": []}, set()
for tid, (ax, az), mode, name in T:
    m = meta.get(tid)
    if not m: print("НЕМАЄ", tid); continue
    if m["modded"]: print("модові блоки, пропуск", tid); continue
    if m["set"] != "vanilla":
        src = os.path.join(DOWNLOADS, m["set"]); src = os.path.join(src, os.listdir(src)[0])
        ns, path = tid.split(":", 1)
        dst = os.path.join(PACK_OUT, "data", ns, "structure", path + ".nbt")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with zipfile.ZipFile(src) as z: open(dst, "wb").write(z.read(m["path"]))
    used.add(m["set"])
    out["templates"].append({"id": tid, "size": m["size"], "anchor": [ax, az], "mode": mode, "name": name, "set": m["set"], "jigsaw": m["jigsaw"],
                             "source": source_of(m), "entry": m["path"]})
# будинки для міст-хабів: ванільні будинки сіл (стоять уздовж вулиць генератора)
out["pools"] = {}
for pool, prefix in (("plains", "minecraft:village/plains/houses/"), ("taiga", "minecraft:village/taiga/houses/")):
    items = [m for i, m in meta.items() if i.startswith(prefix) and m["set"] == "vanilla" and not m["modded"]
             and "animal_pen" not in i and "accessory" not in i and m["solid"] >= 80 and m["jigsaw"] >= 1]
    out["pools"][pool] = [{"id": m["id"], "size": m["size"], "source": source_of(m), "entry": m["path"]} for m in items]
    used.add("vanilla")
for sid_, (ax, az), flat, name in P:
    out["structures"].append({"id": sid_, "anchor": [ax, az], "flatten": flat, "name": name})
    used.add({"hopo": "hopo-better-ruined-portals", "unwrecked_ships": "unwrecked-ships"}.get(sid_.split(":")[0], "vanilla"))
used.add("trek")  # Trek змінює ванільні аванпости
# pack.mcmeta беремо з датапака під 26.1, щоб формат збігався з сервером
hopo = os.path.join(DOWNLOADS, "hopo-better-ruined-portals"); hopo = os.path.join(hopo, os.listdir(hopo)[0])
with zipfile.ZipFile(hopo) as z: mc = json.loads(z.read("pack.mcmeta"))
mc["pack"]["description"] = "Ostriv 1: templates from open-licensed sets (see CREDITS.md)"
json.dump(mc, open(os.path.join(PACK_OUT, "pack.mcmeta"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
out["datapacks"] = [os.path.join(DOWNLOADS, d, os.listdir(os.path.join(DOWNLOADS, d))[0]) for d in DATAPACKS]
json.dump(out, open(os.path.join(HERE, "sporudy.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
with open(os.path.join(HERE, "CREDITS.md"), "w", encoding="utf-8") as f:
    f.write("# Подяки: споруди острова 1\n\nСпоруди з цих наборів поставлено у світ без змін (блоки шаблонів). Ліцензії перевірено 03.11.2026.\n\n| Набір | Ліцензія | Джерело |\n|---|---|---|\n")
    for s in sorted(used): f.write(f"| {SETS[s][0]} | {SETS[s][1]} | {SETS[s][2]} |\n")
print("шаблонів", len(out["templates"]), "споруд", len(out["structures"]), "будинків", {k: len(v) for k, v in out["pools"].items()}, "наборів", len(used))
