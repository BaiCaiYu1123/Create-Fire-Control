"""Export the supplied Blockbench keyboard without changing the source projects.

Vanilla baked parts retain each face's UV and cube rotation. The animation hierarchy
and Bezier handles stay separate, so the closed model can play backwards before
switching to the independently movable keycaps.
"""
import argparse
import base64
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/create_fire_control"
KEYS = {
    "esc": 256, "tab": 258, "enter": 257, "backspace": 259,
    "caps lock": 280, "space": 32, "l-shift": 340, "r-shift": 344,
    "l-ctrl": 341, "r-ctrl": 345, "l-alt": 342, "r-alt": 346,
    "l-win": 343, "r-win": 347, "<": 44, ">": 46,
    "up": 265, "down": 264, "left": 263, "right": 262,
}
KEYS.update({chr(code).lower(): code for code in range(65, 91)})
KEYS.update({chr(code): code for code in [39, 45, 47, 59, 61, 91, 92, 93, 96] + list(range(48, 58))})
KEYS.update({f"f{i}": 289 + i for i in range(1, 13)})


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def key_channel(element, group):
    name = element["name"].lower()
    if group == "buttons2":
        if name in ("mb1", "mb2", "mb4", "mb5"):
            return f"mouse_{int(name[2:]) - 1}"
        if name in KEYS:
            return f"key_{KEYS[name]}"
    if group in ("buttons", "右边") and name in ("up", "down", "left", "right"):
        return f"key_{KEYS[name]}"
    return ""


def export(source, variant, split):
    groups = {g["uuid"]: g for g in source["groups"]}
    elements = {e["uuid"]: e for e in source["elements"]}
    shift = [0, 0, 0] if split else [8, 0, 8]
    texture = f"create_fire_control:item/keyboard_{variant}"
    texture_path = ASSETS / f"textures/item/keyboard_{variant}.png"
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    texture_path.write_bytes(base64.b64decode(source["textures"][0]["source"].split(",", 1)[1]))
    animation = source.get("animations", [{}])[0]
    animators = animation.get("animators", {})
    counter = 0
    exported = set()
    models = []

    def vector(point):
        return [round(float(a) + b, 6) for a, b in zip(point, shift)]

    def cube(e):
        result = {"from": vector(e["from"]), "to": vector(e["to"]), "faces": {}}
        for name, f in e["faces"].items():
            if f.get("texture") is None:
                continue
            # Vanilla's baked-model UV space is always 16 units, even for a 32px
            # texture. The GeckoLib project already halved these coordinates.
            factor = 0.5 if split else 1
            result["faces"][name] = {"uv": [float(v) * factor for v in f["uv"]], "texture": "#all"}
            if f.get("rotation"):
                result["faces"][name]["rotation"] = f["rotation"]
        rotation = e.get("rotation", [0, 0, 0])
        nonzero = [(i, r) for i, r in enumerate(rotation) if r]
        if nonzero:
            assert len(nonzero) == 1, (e["name"], rotation)
            axis, angle = nonzero[0]
            result["rotation"] = {"origin": vector(e["origin"]), "axis": "xyz"[axis],
                                  "angle": angle, "rescale": False}
        return result

    def part(cubes, channel="", glow=False):
        nonlocal counter
        counter += 1
        model = f"item/keyboard/{variant}_{counter:03}"
        save(ASSETS / f"models/{model}.json", {
            "ambientocclusion": False, "textures": {"all": texture, "particle": texture},
            "elements": [cube(elements[uid]) for uid in cubes],
        })
        models.append(model)
        for uid in cubes:
            assert uid not in exported
            exported.add(uid)
        return {"model": model, "input": channel, "depth": 0.08 if channel.startswith("mouse_") else 0.3,
                "glow": glow, "elements": cubes}

    def walk(node):
        g = groups[node["uuid"]]
        result = {"name": g["name"], "pivot": vector(g["origin"]),
                  "rotation": g.get("rotation", [0, 0, 0]), "parts": [], "children": [], "tracks": {}}
        stationary = []
        for child in node["children"]:
            if isinstance(child, dict):
                result["children"].append(walk(child))
                continue
            e = elements[child]
            glow = e["uuid"] == "ab8bed0b-2e7d-c66e-fe47-e679473c6b1e"
            channel = key_channel(e, g["name"]) if split else ""
            if glow or channel:
                result["parts"].append(part([child], channel, glow))
            else:
                stationary.append(child)
        if stationary:
            result["parts"].insert(0, part(stationary))
        for k in animators.get(node["uuid"], {}).get("keyframes", []):
            point = k["data_points"][0]
            track = result["tracks"].setdefault(k["channel"], [])
            track.append({"time": k["time"], "value": [float(point[c]) for c in "xyz"],
                          "interpolation": k["interpolation"],
                          "left_time": k.get("bezier_left_time", [0, 0, 0]),
                          "left_value": k.get("bezier_left_value", [0, 0, 0]),
                          "right_time": k.get("bezier_right_time", [0, 0, 0]),
                          "right_value": k.get("bezier_right_value", [0, 0, 0])})
        for track in result["tracks"].values():
            track.sort(key=lambda k: k["time"])
        return result

    nodes = [walk(n) for n in source["outliner"]]
    assert exported == set(elements), "Every source cube must be exported exactly once"
    return {"models": models, "nodes": nodes, "length": animation.get("length", 0)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("keyboard", type=Path)
    parser.add_argument("animation", type=Path)
    args = parser.parse_args()
    active = json.loads(args.keyboard.read_text(encoding="utf-8"))
    folded = json.loads(args.animation.read_text(encoding="utf-8"))
    # At time zero both projects describe the same appearance, apart from the screen.
    for a, b in zip(active["elements"], folded["elements"]):
        assert a["uuid"] == b["uuid"]
        for field in ("from", "to", "origin"):
            assert all(abs(x - y - s) < 0.00001 for x, y, s in zip(a[field], b[field], [8, 0, 8]))
        assert a.get("rotation") == b.get("rotation")
        for name, face in a["faces"].items():
            assert face["uv"] == [v * 2 for v in b["faces"][name]["uv"]]
    result = {"active": export(active, "active", True), "folded": export(folded, "folded", False)}
    export_colors(result)
    save(ASSETS / "keyboard/controller.json", result)
    print("Exported", sum(len(v["models"]) for v in result.values()), "models; animation", result["folded"]["length"], "seconds")


def export_colors(catalog):
    # The same supplied atlas covers both the animated groups and independent caps.
    for color in ("red", "green", "blue"):
        assert (ASSETS / f"textures/item/keyboard_{color}.png").is_file()
        texture = f"create_fire_control:item/keyboard_{color}"
        for variant in catalog.values():
            for model in variant["models"]:
                save(ASSETS / f"models/{model}_{color}.json", {
                    "parent": f"create_fire_control:{model}",
                    "textures": {"all": texture, "particle": texture},
                })


if __name__ == "__main__":
    main()
