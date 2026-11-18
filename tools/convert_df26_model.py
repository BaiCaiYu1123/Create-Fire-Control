#!/usr/bin/env python3
"""Convert the complete free-format DF-26 Blockbench mesh to one NeoForge OBJ."""

from __future__ import annotations

import argparse
import base64
import json
import math
from pathlib import Path


TEXTURE_NAMES = ("green", "red", "black", "basalt")
# Blockbench's model grid uses the same pixel convention as Minecraft models:
# 16 grid units are one in-game block.  Keep this fixed so conversion never
# silently resizes an authored model to an arbitrary target length.
GRID_UNITS_PER_BLOCK = 16.0
TEXTURE_SIZE = 16.0
TEXTURE_EDGE_INSET = 0.25


def rotate_xyz(point: tuple[float, float, float], degrees: list[float]) -> tuple[float, float, float]:
    x, y, z = point
    rx, ry, rz = (math.radians(float(value)) for value in degrees)

    cos_x, sin_x = math.cos(rx), math.sin(rx)
    y, z = y * cos_x - z * sin_x, y * sin_x + z * cos_x
    cos_y, sin_y = math.cos(ry), math.sin(ry)
    x, z = x * cos_y + z * sin_y, -x * sin_y + z * cos_y
    cos_z, sin_z = math.cos(rz), math.sin(rz)
    x, y = x * cos_z - y * sin_z, x * sin_z + y * cos_z
    return x, y, z


def transformed_vertices(element: dict) -> dict[str, tuple[float, float, float]]:
    origin = tuple(float(value) for value in element.get("origin", (0, 0, 0)))
    rotation = element.get("rotation", (0, 0, 0))
    result: dict[str, tuple[float, float, float]] = {}
    for key, raw in element["vertices"].items():
        # Free-mesh vertices are stored in model coordinates.  `origin` is the
        # rotation pivot, rather than a translation to add after rotation.
        relative = tuple(float(raw[index]) - origin[index] for index in range(3))
        rotated = rotate_xyz(relative, rotation)
        result[key] = tuple(rotated[index] + origin[index] for index in range(3))
    return result


def is_buried_attachment_face(
    element: dict,
    vertices: dict[str, tuple[float, float, float]],
    face: dict,
) -> bool:
    """Drop the radial inner face of a fin that is buried in the missile body."""
    if element.get("name") != "cube":
        return False
    keys = face.get("vertices", ())
    if len(keys) < 3:
        return False
    element_radii = [math.hypot(point[0], point[2]) for point in vertices.values()]
    minimum_radius = min(element_radii)
    return all(
        abs(math.hypot(vertices[key][0], vertices[key][2]) - minimum_radius) < 1.0e-4
        for key in keys
    )


def safe_face_uvs(face: dict) -> dict[str, tuple[float, float]]:
    keys = face.get("vertices", ())
    raw = {
        key: tuple(float(value) for value in face.get("uv", {}).get(key, (8, 8)))
        for key in keys
    }
    signed_double_area = sum(
        raw[keys[index]][0] * raw[keys[(index + 1) % len(keys)]][1]
        - raw[keys[(index + 1) % len(keys)]][0] * raw[keys[index]][1]
        for index in range(len(keys))
    )
    if abs(signed_double_area) < 1.0e-8:
        # Several imported cylinder faces collapse all UVs onto the texture
        # atlas edge.  Sample the sprite center instead, where mipmaps cannot
        # bleed into a neighbouring atlas entry while the camera moves.
        return {key: (TEXTURE_SIZE * 0.5, TEXTURE_SIZE * 0.5) for key in keys}
    return {
        key: tuple(
            max(TEXTURE_EDGE_INSET, min(TEXTURE_SIZE - TEXTURE_EDGE_INSET, value))
            for value in uv
        )
        for key, uv in raw.items()
    }


def triangulated_faces(model: dict):
    for element in model["elements"]:
        vertices = transformed_vertices(element)
        for face in element["faces"].values():
            keys = face.get("vertices", ())
            if (len(keys) < 3
                    or face.get("texture") is None
                    or is_buried_attachment_face(element, vertices, face)):
                continue
            texture = int(face["texture"])
            uv_map = safe_face_uvs(face)
            for index in range(1, len(keys) - 1):
                triangle_keys = (keys[0], keys[index], keys[index + 1])
                yield texture, tuple(
                    (vertices[key], uv_map[key])
                    for key in triangle_keys
                )


def decode_textures(model: dict, texture_dir: Path) -> None:
    texture_dir.mkdir(parents=True, exist_ok=True)
    for index, texture in enumerate(model["textures"]):
        source = texture.get("source", "")
        marker = "base64,"
        if marker not in source:
            raise ValueError(f"Texture {index} is not embedded in the bbmodel")
        name = TEXTURE_NAMES[index] if index < len(TEXTURE_NAMES) else f"texture_{index}"
        (texture_dir / f"df_26_{name}.png").write_bytes(
            base64.b64decode(source.split(marker, 1)[1])
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("model_dir", type=Path)
    parser.add_argument("texture_dir", type=Path)
    args = parser.parse_args()

    model = json.loads(args.source.read_text(encoding="utf-8"))
    triangles = list(triangulated_faces(model))
    if not triangles:
        raise ValueError("The source model contains no renderable mesh faces")

    points = [point for _, triangle in triangles for point, _ in triangle]
    min_x = min(point[0] for point in points)
    max_x = max(point[0] for point in points)
    min_y = min(point[1] for point in points)
    max_y = max(point[1] for point in points)
    min_z = min(point[2] for point in points)
    max_z = max(point[2] for point in points)
    scale = 1.0 / GRID_UNITS_PER_BLOCK
    model_length = (max_y - min_y) * scale
    occupied_length = math.ceil(model_length)
    center_x = (min_x + max_x) * 0.5
    center_z = (min_z + max_z) * 0.5

    def normalize(point: tuple[float, float, float]) -> tuple[float, float, float]:
        return (
            0.5 + (point[0] - center_x) * scale,
            (point[1] - min_y) * scale,
            0.5 + (point[2] - center_z) * scale,
        )

    args.model_dir.mkdir(parents=True, exist_ok=True)
    obj_lines = ["# Complete DF-26 mesh converted from Blockbench", "mtllib df_26.mtl"]
    vertex_index = 1
    current_material = None
    for texture, triangle in triangles:
        material = f"texture_{texture}"
        if material != current_material:
            obj_lines.append(f"usemtl {material}")
            current_material = material
        face_indices = []
        for point, uv in triangle:
            x, y, z = normalize(point)
            obj_lines.append(f"v {x:.8f} {y:.8f} {z:.8f}")
            obj_lines.append(
                f"vt {uv[0] / TEXTURE_SIZE:.8f} {uv[1] / TEXTURE_SIZE:.8f}"
            )
            face_indices.append(f"{vertex_index}/{vertex_index}")
            vertex_index += 1
        obj_lines.append("f " + " ".join(face_indices))
    (args.model_dir / "df_26.obj").write_text("\n".join(obj_lines) + "\n", encoding="utf-8")

    mtl_lines = []
    for index in range(len(model["textures"])):
        name = TEXTURE_NAMES[index] if index < len(TEXTURE_NAMES) else f"texture_{index}"
        mtl_lines.extend((f"newmtl texture_{index}", f"map_Kd #df_26_{name}", ""))
    (args.model_dir / "df_26.mtl").write_text("\n".join(mtl_lines), encoding="utf-8")
    decode_textures(model, args.texture_dir)

    metadata = {
        "source_bounds": [min_x, min_y, min_z, max_x, max_y, max_z],
        "scale": scale,
        "normalized_bounds": [
            0.5 + (min_x - center_x) * scale,
            0.0,
            0.5 + (min_z - center_z) * scale,
            0.5 + (max_x - center_x) * scale,
            model_length,
            0.5 + (max_z - center_z) * scale,
        ],
        "fixed_collision_cross_section": [3.0, 3.0],
        "occupied_length": occupied_length,
        "triangles": len(triangles),
    }
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
