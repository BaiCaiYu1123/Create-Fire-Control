"""Read-only textured QA of the supplied model and exported OBJ inventory mesh."""
import ast
import base64
import hashlib
import io
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/create_fire_control'
source_path = ROOT / 'reference/pierce_everything/source.bbmodel'
source = json.loads(source_path.read_text(encoding='utf-8'))
namespace = dict(np=np, Image=Image, math=math, FACE={
    'north':[0,3,2,1], 'south':[4,5,6,7], 'west':[0,4,7,3],
    'east':[1,2,6,5], 'down':[0,1,5,4], 'up':[3,7,6,2]})
renderer = ast.parse((ROOT / 'tools/render_type_10_models.py').read_text(encoding='utf-8'))
functions = [node for node in renderer.body if isinstance(node, ast.FunctionDef)
             and node.name in ('rotate', 'mesh', 'render')]
exec(compile(ast.Module(body=functions, type_ignores=[]), '<existing-model-renderer>', 'exec'), namespace)

texture_bytes = base64.b64decode(source['textures'][1]['source'].split(',', 1)[1])
texture_path = ASSETS / 'textures/item/annihilation_cartridge.png'
assert hashlib.sha256(texture_bytes).digest() == hashlib.sha256(texture_path.read_bytes()).digest()
texture = np.array(Image.open(io.BytesIO(texture_bytes)).convert('RGBA'))
vertices, uvs, triangles = [], [], []
for line in (ASSETS / 'models/item/annihilation_medium_cannon_cartridge.obj').read_text().splitlines():
    fields = line.split()
    if not fields:
        continue
    if fields[0] == 'v':
        vertices.append(np.array([float(x) * 16 for x in fields[1:]]))
    elif fields[0] == 'vt':
        uvs.append(np.array([float(fields[1]), 1 - float(fields[2])]))
    elif fields[0] == 'f':
        corners = [tuple(int(x) - 1 for x in field.split('/')) for field in fields[1:]]
        for index in range(1, len(corners) - 1):
            selected = [corners[0], corners[index], corners[index + 1]]
            triangles.append((np.array([vertices[v] for v, uv in selected]),
                              np.array([uvs[uv] for v, uv in selected])))
assert len(vertices) == 19 * 8 and len(triangles) == 19 * 6 * 2
source_mesh = namespace['mesh'](source, count=99)
source_vertices = np.concatenate([p for p, uv in source_mesh])
exported_vertices = np.array(vertices)
assert max(np.min(np.linalg.norm(source_vertices - vertex, axis=1))
           for vertex in exported_vertices) < 1e-5

preview = Image.new('RGB', (1000, 550), (28, 32, 38))
draw = ImageDraw.Draw(preview)
for index, (title, mesh) in enumerate((('Supplied Blockbench model', source_mesh), ('Exported inventory OBJ', triangles))):
    tile = namespace['render'](mesh, texture, size=480, eye=(1, .5, -1.4))
    preview.paste(tile, (index * 500 + 10, 45), tile)
    draw.text((index * 500 + 20, 20), title, fill='white')
draw.text((20, 525), '19 cubes; original embedded texture; flight and loader meshes keep their existing assets', fill='white')
output = ROOT / 'build/pierce-everything-model-preview.png'
preview.save(output)
print('PASS: 19 cubes / 228 triangles; source vertices and embedded texture preserved')
print(output)
