"""Bake the supplied Java-block V8 model and its UVs with a 90 degree Y rotation."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/create_fire_control"
FACES = {
    "north": [0, 3, 2, 1], "south": [4, 5, 6, 7],
    "west": [0, 4, 7, 3], "east": [1, 2, 6, 5],
    "down": [0, 1, 5, 4], "up": [3, 7, 6, 2],
}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def rotated(point: list[float], element: dict) -> list[float]:
    origin = element.get("origin", [0, 0, 0])
    x, y, z = [point[i] - origin[i] for i in range(3)]
    rx, ry, rz = map(math.radians, element.get("rotation", [0, 0, 0]))
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    x, y, z = x + origin[0], y + origin[1], z + origin[2]
    # Positive 90 degrees around Y, pivot (8,8,8), preserving source scale.
    return [z, y, 16 - x]


def uv_corners(face: dict, direction: str, width: float, height: float) -> list[list[float]]:
    u1, v1, u2, v2 = face["uv"]
    corners = {
        "north": [[u1, v2], [u1, v1], [u2, v1], [u2, v2]],
        "south": [[u1, v2], [u2, v2], [u2, v1], [u1, v1]],
        "west": [[u1, v2], [u2, v2], [u2, v1], [u1, v1]],
        "east": [[u1, v2], [u1, v1], [u2, v1], [u2, v2]],
        "down": [[u1, v1], [u2, v1], [u2, v2], [u1, v2]],
        "up": [[u1, v1], [u1, v2], [u2, v2], [u2, v1]],
    }[direction]
    turns = int(face.get("rotation", 0) / 90) % 4
    corners = corners[turns:] + corners[:turns]
    return [[u / width, 1 - v / height] for u, v in corners]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    source = json.loads(source_bytes)
    # Static organizational groups in the new model do not transform their cubes.
    if any(any(abs(v)>1e-6 for v in g.get("rotation",[0,0,0])) or not g.get("export",True)
           for g in source.get("groups",[])) or not all(e["type"] == "cube" for e in source["elements"]):
        raise ValueError("This converter expects static cubes without transformed or excluded groups")
    if len(source["textures"]) != 1:
        raise ValueError("Expected the supplied single embedded PNG texture")
    texture = source["textures"][0]
    if not texture["source"].startswith("data:image/png;base64,"):
        raise ValueError("Embedded PNG is missing")
    png = base64.b64decode(texture["source"].split(",", 1)[1], validate=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Embedded texture is not PNG")
    width, height = texture.get("uv_width", source["resolution"]["width"]), texture.get("uv_height", source["resolution"]["height"])
    model_dir = ASSETS / "models/block/tiger_i_engine"
    model_dir.mkdir(parents=True, exist_ok=True)
    texture_path = ASSETS / "textures/block/tiger_i_engine.png"
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    texture_path.write_bytes(png)
    archive = ROOT / "reference/tiger_i/model" / args.source.name
    archive.parent.mkdir(parents=True, exist_ok=True)
    if args.source.resolve() != archive.resolve():
        shutil.copyfile(args.source, archive)

    lines = ["# Supplied V8 model; original scale and UVs; Y +90 around (8,8,8)",
             "mtllib center.mtl", "usemtl layer0"]
    index = 1
    all_points = []
    cubes = []
    faces = 0
    for element in source["elements"]:
        if element.get("export", True) is False:
            continue
        x0, y0, z0 = element["from"]
        x1, y1, z1 = element["to"]
        points = [rotated(p, element) for p in [
            [x0,y0,z0], [x1,y0,z0], [x1,y1,z0], [x0,y1,z0],
            [x0,y0,z1], [x1,y0,z1], [x1,y1,z1], [x0,y1,z1]]]
        all_points.extend(points)
        cubes.append({"uuid": element["uuid"], "name": element["name"], "rotated_vertices": points})
        lines.append("g cube_" + element["uuid"].replace("-", "_"))
        for direction, ids in FACES.items():
            face = element["faces"].get(direction)
            if face is None or face.get("texture") is None:
                continue
            if face["texture"] != 0:
                raise ValueError("Unexpected face texture")
            uvs = uv_corners(face, direction, width, height)
            for corners in [[0,1,2], [0,2,3]]:
                for corner in corners:
                    lines.append("v " + " ".join(f"{c / 16:.9f}" for c in points[ids[corner]]))
                for corner in corners:
                    lines.append("vt " + " ".join(f"{c:.9f}" for c in uvs[corner]))
                lines.append(f"f {index}/{index} {index+1}/{index+1} {index+2}/{index+2}")
                index += 3
            faces += 1
    bounds = [[min(p[i] for p in all_points), max(p[i] for p in all_points)] for i in range(3)]
    if any(low < -1e-6 or high > 16 + 1e-6 for low, high in bounds):
        raise ValueError(f"Rotated model exceeds the controller cell: {bounds}")
    (model_dir / "center.obj").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (model_dir / "center.mtl").write_text("newmtl layer0\nmap_Kd #layer0\n", encoding="utf-8")
    write_json(model_dir / "center.json", {
        "parent": "minecraft:block/block", "ambientocclusion": source.get("ambientocclusion", True),
        "textures": {"layer0": "create_fire_control:block/tiger_i_engine", "particle": "create_fire_control:block/tiger_i_engine"},
        "loader": "neoforge:obj", "flip_v": True, "automatic_culling": False,
        "shade_quads": True, "model": "create_fire_control:models/block/tiger_i_engine/center.obj",
    })
    write_json(ASSETS / "models/item/tiger_i_engine.json", {
        "parent": "create_fire_control:block/tiger_i_engine/center",
        "display": {"gui": {"rotation": [30,225,0], "translation": [0,1,0], "scale": [.85,.85,.85]}},
    })
    # Earlier saved LEFT/RIGHT states remain resolvable, but have no visible mesh.
    for part in ["left", "right"]:
        path = model_dir / f"{part}.json"
        model = {"textures": {"particle": "create_fire_control:block/tiger_i_engine"}, "elements": []}
        write_json(path, model)
    write_json(ASSETS / "tiger_i_engine/geometry_manifest.json", {
        "source": args.source.name, "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "texture_sha256": hashlib.sha256(png).hexdigest(), "texture_size": [width,height],
        "source_cube_count": len(source["elements"]), "exported_cube_count": len(cubes),
        "face_count": faces, "triangle_count": faces * 2,
        "rotation_axis": "Y", "rotation_degrees": 90, "rotation_pivot": [8,8,8], "scale": 1,
        "bounds": bounds, "output_centers_relative_to_controller": [[8,8,8],[8,8,8]], "cubes": cubes,
    })
    print(f"Converted all {len(cubes)} cubes / {faces * 2} triangles; original {width}x{height} PNG; Y +90")
    print("Controller-cell bounds:", bounds)


if __name__ == "__main__":
    main()
