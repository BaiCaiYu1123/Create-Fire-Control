"""Convert a Blockbench GeckoLib .bbmodel into GeckoLib 4 .geo.json / .animation.json / texture atlas PNG.

Conventions (verified against Blockbench bedrock.js compileCube/compileGroup and GeckoLib 4.9 bytecode):
- geo.json cube origin.x = -(from.x + size.x); pivot.x = -pivot.x; rotation X/Y negated.
- geo.json bone pivot.x = -origin.x; bone rotation X/Y negated.
- per-face UV: uv=[u1,v1], uv_size=[u2-u1,v2-v1]; up/down faces flipped (uv+=uv_size, uv_size*=-1).
- mirror_uv is NOT exported for per-face UV (uv data already encodes it).
- animation rotation keyframes: X/Y negated; position/scale raw.
- Multiple module textures are packed into a single atlas and face UVs are remapped,
  because GeckoLib renders one texture per model and ignores material_instance.
"""
import json
import os
import sys
import base64
import struct
import zlib

MODE = "az" if len(sys.argv) > 1 and sys.argv[1].lower() == "az" else "mz"
CANDIDATES = ([
    r"C:\Users\hooya\Downloads\AZ装弹机 - Converted.bbmodel",
] if MODE == "az" else [
    r"F:\我的世界文件\模型\MZ装弹机.geo - Converted - Converted.bbmodel",
    r"C:\Users\hooya\Downloads\MZ装弹机.geo - Converted - Converted.bbmodel",
])
ASSETS = r"F:\codex\create_fire_control\src\main\resources\assets\create_fire_control"
ASSET_NAME = "az_loader" if MODE == "az" else "mz_loader"
GEOMETRY_NAME = "geometry.az_loader" if MODE == "az" else "geometry.mz_loader"
BASE_ANIMATION = "AZ" if MODE == "az" else "MZ2"

def choose_model():
    existing = [p for p in CANDIDATES if os.path.exists(p)]
    if not existing:
        raise FileNotFoundError("bbmodel not found")
    # Use the first existing candidate (canonical project location wins over stale Downloads copies).
    return existing[0]

BBMODEL = choose_model()
print("using model:", BBMODEL)

with open(BBMODEL, "r", encoding="utf-8") as f:
    data = json.load(f)

gmap = {g["uuid"]: g for g in data["groups"]}
emap = {e["uuid"]: e for e in data["elements"]}

bones = {}
order = []

def walk(node, parent_uuid):
    if isinstance(node, str):
        if parent_uuid is not None and parent_uuid in bones:
            bones[parent_uuid]["cubes"].append(node)
    elif isinstance(node, dict):
        uid = node["uuid"]
        g = gmap.get(uid, {})
        bones[uid] = {
            "name": g.get("name") or node.get("name") or uid,
            "origin": g.get("origin") or [0.0, 0.0, 0.0],
            "rotation": g.get("rotation") or [0.0, 0.0, 0.0],
            "parent": parent_uuid,
            "cubes": [],
        }
        order.append(uid)
        for c in node.get("children", []):
            walk(c, uid)

for n in data["outliner"]:
    walk(n, None)

assert len(bones) == len(data["groups"])
assert sum(len(b["cubes"]) for b in bones.values()) == len(data["elements"])

# ---- decode textures ----
def load_png(path_or_bytes):
    data = path_or_bytes if isinstance(path_or_bytes, bytes) else open(path_or_bytes, "rb").read()
    pos = 8; idat = b""; w = h = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        payload = data[pos+8:pos+8+ln]
        if typ == b"IHDR":
            w, h = struct.unpack(">II", payload[:8])
        elif typ == b"IDAT":
            idat += payload
        elif typ == b"IEND":
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    out = bytearray(w * h * 4)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(4, stride): line[i] = (line[i] + line[i-4]) & 255
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-4] if i >= 4 else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-4] if i >= 4 else 0
                b = prev[i]; c = prev[i-4] if i >= 4 else 0
                pa = abs(b-c); pb = abs(a-c); pc = abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, out

def write_png(path, w, h, rgba):
    def chunk(typ, d):
        c = struct.pack(">I", len(d)) + typ + d
        c += struct.pack(">I", zlib.crc32(typ + d) & 0xffffffff)
        return c
    raw = b"".join(b"\x00" + bytes(rgba[y*w*4:(y+1)*w*4]) for y in range(h))
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)

# Only keep textures actually referenced by faces (Blockbench keeps unused textures as project data).
used_tex = set()
for e in data["elements"]:
    for fname, f in (e.get("faces") or {}).items():
        used_tex.add(f.get("texture", 0))

# extract textures
textures = []
for idx, t in enumerate(data.get("textures", [])):
    if idx not in used_tex:
        continue
    src = t.get("source") or ""
    if src.startswith("data:image/png;base64,"):
        png = base64.b64decode(src.split(",", 1)[1])
        w, h, rgba = load_png(png)
    else:
        p = t.get("path")
        if p and os.path.exists(p):
            w, h, rgba = load_png(p)
        else:
            continue
    textures.append({"name": t.get("name"), "w": w, "h": h, "rgba": rgba})
print("textures (used only):", [(x["name"], x["w"], x["h"]) for x in textures])

# pack into single-row atlas
# Blockbench exports texture_width/height from Project.resolution, NOT the PNG size.
# This model's PNG is a 2x upscale of a 128x128 UV layout (content bbox ~= 2x max UV),
# so the geo description must say 128 to sample the content correctly.
RES = data.get("resolution") or {}
if len(textures) == 1:
    RES_W = int(RES.get("width", textures[0]["w"]))
    RES_H = int(RES.get("height", textures[0]["h"]))
else:
    RES_W = RES_H = None  # computed from atlas below

ATLAS_H = max(t["h"] for t in textures)
atlas_w = sum(t["w"] for t in textures)
atlas = bytearray([0, 0, 0, 0]) * (atlas_w * ATLAS_H)
offsets = []
x = 0
for t in textures:
    for yy in range(t["h"]):
        src = t["rgba"][yy*t["w"]*4:(yy+1)*t["w"]*4]
        dst = ((yy) * atlas_w + x) * 4
        atlas[dst:dst+len(src)] = src
    offsets.append((x, 0))
    x += t["w"]
print("atlas size:", atlas_w, "x", ATLAS_H)

tex_out = os.path.join(ASSETS, "textures", "block", ASSET_NAME + ".png")
os.makedirs(os.path.dirname(tex_out), exist_ok=True)
write_png(tex_out, atlas_w, ATLAS_H, bytes(atlas))
print("wrote", tex_out)

# Map original face texture indices -> position in the filtered textures list.
orig_to_used = {}
j = 0
for idx in range(len(data.get("textures", []))):
    if idx in used_tex:
        orig_to_used[idx] = j
        j += 1

def tex_offset(face_tex):
    return offsets[orig_to_used[face_tex]]

def neg_xy(v):
    return [-v[0], -v[1], v[2]]

def nonzero(v):
    return any(abs(x) > 1e-6 for x in v)

# ---- geo.json ----
geo_bones = []
for uid in order:
    b = bones[uid]
    parent_name = bones[b["parent"]]["name"] if (b["parent"] and b["parent"] in bones) else None
    cubes = []
    for cu in b["cubes"]:
        e = emap[cu]
        fr, to = e["from"], e["to"]
        size = [to[0] - fr[0], to[1] - fr[1], to[2] - fr[2]]
        if any(abs(s) < 1e-9 for s in size):
            continue
        cube = {
            "origin": [-(fr[0] + size[0]), float(fr[1]), float(fr[2])],
            "size": [float(x) for x in size],
        }
        rot = e.get("rotation")
        if rot and nonzero(rot):
            origin = e.get("origin") or [0.0, 0.0, 0.0]
            cube["pivot"] = [-float(origin[0]), float(origin[1]), float(origin[2])]
            cube["rotation"] = [float(x) for x in neg_xy(rot)]
        faces = {}
        for fname, f in (e.get("faces") or {}).items():
            ft = f.get("texture", 0)
            if ft not in orig_to_used:
                continue
            ox, oy = tex_offset(ft)
            uv = f["uv"]
            u = float(uv[0]) + ox
            v = float(uv[1]) + oy
            w = float(uv[2]) - float(uv[0])
            h = float(uv[3]) - float(uv[1])
            if fname in ("up", "down"):
                u += w; v += h; w = -w; h = -h
            faces[fname] = {"uv": [u, v], "uv_size": [w, h]}
        if faces:
            cube["uv"] = faces
        cubes.append(cube)

    bone = {
        "name": b["name"],
        "pivot": [-float(b["origin"][0]), float(b["origin"][1]), float(b["origin"][2])],
    }
    if nonzero(b["rotation"]):
        bone["rotation"] = [float(x) for x in neg_xy(b["rotation"])]
    if parent_name:
        bone["parent"] = parent_name
    if cubes:
        bone["cubes"] = cubes
    geo_bones.append(bone)

if MODE == "az":
    carousel = next(b for b in geo_bones
                    if b["name"] == "Artillery shell placement position")
    # Keep accumulated carousel steps separate from the source animation. The child
    # bone remains fully controlled by the exported Blockbench keyframes, while this
    # parent retains one completed 15-degree step after each cycle.
    geo_bones.insert(0, {
        "name": "AZ_carousel_offset",
        "pivot": list(carousel["pivot"]),
    })
    carousel["parent"] = "AZ_carousel_offset"

geo = {
    "format_version": "1.12.0",
    "minecraft:geometry": [
        {
            "description": {
                "identifier": GEOMETRY_NAME,
                "texture_width": RES_W if RES_W is not None else atlas_w,
                "texture_height": RES_H if RES_H is not None else ATLAS_H,
                "visible_bounds_width": 8 if MODE == "az" else 14,
                "visible_bounds_height": 8 if MODE == "az" else 30,
                "visible_bounds_offset": [0.0, 1.0, 0.0] if MODE == "az" else [0.4, 14.6, -14.7],
            },
            "bones": geo_bones,
        }
    ],
}

geo_out = os.path.join(ASSETS, "geo", ASSET_NAME + ".geo.json")
os.makedirs(os.path.dirname(geo_out), exist_ok=True)
with open(geo_out, "w", encoding="utf-8") as f:
    json.dump(geo, f, indent=2, ensure_ascii=False)
print("wrote", geo_out, "bones:", len(geo_bones), "cubes:", sum(len(b.get("cubes", [])) for b in geo_bones))

# ---- animation.json ----
LOOP_MAP = {"hold": "hold_on_last_frame", "loop": "loop", "once": "once", "true": "loop", "false": "once"}

def fmt_time(t):
    s = "%.4f" % t
    s = s.rstrip("0").rstrip(".")
    return s if s else "0"

animations = {}
for anim in data.get("animations", []):
    name = BASE_ANIMATION if MODE == "az" else anim["name"]
    a = {
        "loop": LOOP_MAP.get(str(anim.get("loop")).lower(), "hold_on_last_frame"),
        "animation_length": anim.get("length", 0.0),
        "bones": {},
    }
    sound_frames = {}
    for uuid, info in anim.get("animators", {}).items():
        for kf in info.get("keyframes", []):
            dps = kf.get("data_points") or []
            if not dps:
                continue
            dp = dps[0]
            if "effect" in dp or "file" in dp:
                t = float(kf.get("time", 0.0))
                sound_frames[fmt_time(t)] = {"effect": "create_fire_control:" + ASSET_NAME}
    if sound_frames:
        a["sound_effects"] = sound_frames
    for uuid, info in anim.get("animators", {}).items():
        kfs = info.get("keyframes")
        if not kfs:
            continue
        bone_name = info.get("name")
        if not bone_name:
            continue
        channels = {}
        for kf in kfs:
            ch = kf.get("channel")
            if ch not in ("rotation", "position", "scale"):
                continue
            dps = kf.get("data_points") or []
            if not dps:
                continue
            dp = dps[0]
            if not all(k in dp for k in ("x", "y", "z")):
                continue
            vec = [float(dp["x"]), float(dp["y"]), float(dp["z"])]
            if ch == "rotation":
                vec = neg_xy(vec)
            t = float(kf.get("time", 0.0))
            channels.setdefault(ch, {})[t] = vec
        if not channels:
            continue
        bone_anim = {}
        for ch, tv in channels.items():
            bone_anim[ch] = {fmt_time(t): [round(x, 6) for x in vec] for t, vec in sorted(tv.items())}
        a["bones"][bone_name] = bone_anim
    animations[name] = a


# ---- per-rack animation variants ----
# Each variant retargets the lift keyframes from rack 1's parts to that rack's parts,
# so every shot lifts the shell of the rack currently at the loading position.
def rack_parts():
    g = gmap

    def nm(node):
        if isinstance(node, dict):
            gg = g.get(node.get("uuid"), {})
            return gg.get("name") or node.get("name") or node.get("uuid")
        return None

    root = next(n for n in data["outliner"] if isinstance(n, dict) and nm(n) == "1")
    parts = []
    for rack in root["children"]:
        holder = fan = None
        for c in rack["children"]:
            cn = nm(c)
            if cn and "Ammunition_to_be_issued" in cn:
                holder = cn
            elif cn and cn.startswith("fan"):
                fan = cn
        holder_node = next((x for x in rack["children"] if nm(x) == holder), None)
        shell = next((nm(c) for c in holder_node["children"] if nm(c) and nm(c).startswith("APFSDS")), None)
        fan_node = next((x for x in rack["children"] if nm(x) == fan), None)
        bone = next((nm(c) for c in fan_node["children"] if nm(c) and nm(c).startswith("bone")), None)
        bone_node = next((x for x in fan_node["children"] if nm(x) == bone), None) if bone else None
        prop = casing = None
        if bone_node:
            for c in bone_node["children"]:
                cn = nm(c)
                if cn and "Propellant" in cn:
                    prop = cn
                elif cn and "Shell_casing" in cn:
                    casing = cn
        parts.append({"holder": holder, "shell": shell, "fan": fan, "prop": prop, "casing": casing})
    return parts

def az_rack_parts():
    def nm(node):
        if isinstance(node, dict):
            gg = gmap.get(node.get("uuid"), {})
            return gg.get("name") or node.get("name") or node.get("uuid")
        return None

    root = next(n for n in data["outliner"]
                if isinstance(n, dict) and nm(n) == "Artillery shell placement position")
    parts = []
    for rack in root["children"]:
        armor = next(nm(c) for c in rack["children"]
                     if nm(c) and nm(c).startswith("Armor-piercing_projectile_placement"))
        prop_pos = next(nm(c) for c in rack["children"]
                        if nm(c) and nm(c).startswith("Placement_of_propellant"))
        ammo_rack = next(c for c in rack["children"]
                         if nm(c) and "Ammunition" in nm(c) and "rack" in nm(c).lower())
        holder = next(c for c in ammo_rack["children"]
                      if nm(c) and "Ammunition_to_be_issued" in nm(c))
        shell = next(nm(c) for c in holder["children"] if nm(c).startswith("APFSDS"))
        prop = next(nm(c) for c in holder["children"] if nm(c).startswith("Propellant"))
        casing = next(nm(c) for c in holder["children"] if nm(c).startswith("Shell_casing"))
        parts.append({"armor": armor, "prop_pos": prop_pos, "shell": shell,
                      "prop": prop, "casing": casing})
    return parts

if BASE_ANIMATION in animations:
    base_anim = animations[BASE_ANIMATION]
    if MODE == "az":
        for idx, part in enumerate(az_rack_parts(), 1):
            mapping = {
                "Armor-piercing_projectile_placement4": part["armor"],
                "Placement_of_propellant4": part["prop_pos"],
                "APFSDS2": part["shell"],
                "Propellant19": part["prop"],
                "Shell_casing19": part["casing"],
            }
            variant = json.loads(json.dumps(base_anim))
            variant["bones"] = {mapping.get(k, k): v for k, v in variant["bones"].items()}
            animations[f"AZ_{idx}"] = variant
    else:
        parts = rack_parts()
        mapping_keys = ["Ammunition_to_be_issued1", "APFSDS3", "fan", "Propellant", "Shell_casing"]
        for idx, part in enumerate(parts, 1):
            mapping = {
                "Ammunition_to_be_issued1": part["holder"],
                "APFSDS3": part["shell"],
                "fan": part["fan"],
                "Propellant": part["prop"],
                "Shell_casing": part["casing"],
            }
            variant = json.loads(json.dumps(base_anim))
            new_bones = {}
            for k, v in variant["bones"].items():
                new_bones[mapping.get(k, k)] = v
            variant["bones"] = new_bones
            animations[f"MZ2_{idx}"] = variant

anim_json = {"format_version": "1.8.0", "animations": animations}
anim_out = os.path.join(ASSETS, "animations", ASSET_NAME + ".animation.json")
os.makedirs(os.path.dirname(anim_out), exist_ok=True)
with open(anim_out, "w", encoding="utf-8") as f:
    json.dump(anim_json, f, indent=2, ensure_ascii=False)
print("wrote", anim_out, "animations:", list(animations.keys()))
for bname, b in animations[BASE_ANIMATION]["bones"].items():
    print("   anim bone:", bname, {k: len(v) for k, v in b.items()})
print("sound_effects:", animations[BASE_ANIMATION].get("sound_effects"))

# ---- model bbox (for renderer centering) ----
xs, ys, zs = [], [], []
for e in data["elements"]:
    f, t = e["from"], e["to"]
    xs += [f[0], t[0]]; ys += [f[1], t[1]]; zs += [f[2], t[2]]
print("MODEL BBOX: x", min(xs), max(xs), "| y", min(ys), max(ys), "| z", min(zs), max(zs))
print("MODEL CENTER:", (min(xs)+max(xs))/2, (min(ys)+max(ys))/2, (min(zs)+max(zs))/2)
