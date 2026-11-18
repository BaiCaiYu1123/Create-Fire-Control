const fs = require("fs");
const path = require("path");

const animationOnly = process.argv[3] === '--animation';
if (process.argv.length !== (animationOnly ? 4 : 3)) {
    throw new Error("Usage: node convert_ztz99a_bbmodel.js <source.bbmodel> [--animation]");
}

const sourcePath = path.resolve(process.argv[2]);
const projectRoot = path.resolve(__dirname, "..");
const assetRoot = path.join(
    projectRoot, "src", "main", "resources", "assets", "create_fire_control"
);
const blockModelDirectory = path.join(assetRoot, "models", "block", "ztz99a_diesel_engine");
const itemModelDirectory = path.join(assetRoot, "models", "item");
const textureDirectory = path.join(assetRoot, "textures", "block");
const metadataDirectory = path.join(assetRoot, "ztz99a");
const javaDirectory = path.join(
    projectRoot, "src", "main", "java", "com", "hooya", "stabilizedturret",
    "content", "diesel"
);

for (const directory of [
    blockModelDirectory, itemModelDirectory, textureDirectory, metadataDirectory, javaDirectory
]) {
    fs.mkdirSync(directory, { recursive: true });
}

const source = JSON.parse(fs.readFileSync(sourcePath, "utf8"));
if (!source.elements?.every(e => e.type === "cube")) {
    throw new Error(`Expected cube geometry in a Blockbench project: ${sourcePath}`);
}
if (!Array.isArray(source.textures) || source.textures.length !== 1) {
    throw new Error("The gas turbine converter expects exactly one texture");
}

const texture = source.textures[0];
const textureMatch = /^data:image\/png;base64,(.+)$/.exec(texture.source);
if (!textureMatch) {
    throw new Error("The gas turbine texture is not embedded as a PNG");
}
if (!animationOnly) fs.writeFileSync(
    path.join(textureDirectory, "ztz99a_diesel_engine.png"),
    Buffer.from(textureMatch[1], "base64")
);

const textureWidth = Number(texture.uv_width ?? source.resolution.width);
const textureHeight = Number(texture.uv_height ?? source.resolution.height);
const radians = degrees => degrees * Math.PI / 180;
const cleanNumber = value => {
    const cleaned = Math.abs(value) < 1e-9 ? 0 : value;
    return Number(cleaned.toFixed(8)).toString();
};

// Blockbench's cube Euler rotation is ZYX: apply X, then Y, then Z.
function rotateVector(vector, rotation) {
    let [x, y, z] = vector;
    const [rx, ry, rz] = rotation.map(radians);

    let cosine = Math.cos(rx);
    let sine = Math.sin(rx);
    [y, z] = [y * cosine - z * sine, y * sine + z * cosine];

    cosine = Math.cos(ry);
    sine = Math.sin(ry);
    [x, z] = [x * cosine + z * sine, -x * sine + z * cosine];

    cosine = Math.cos(rz);
    sine = Math.sin(rz);
    [x, y] = [x * cosine - y * sine, x * sine + y * cosine];
    return [x, y, z];
}

function rotatePoint(point, origin, rotation) {
    const relative = point.map((value, index) => value - origin[index]);
    return rotateVector(relative, rotation)
        .map((value, index) => value + origin[index]);
}


const groups = new Map((source.groups ?? []).map(g => [g.uuid, g]));
const ancestors = new Map();
function walk(nodes, parents = []) {
    for (const n of nodes) {
        if (typeof n === 'string') ancestors.set(n, parents);
        else walk(n.children ?? [], [...parents, groups.get(n.uuid) ?? n]);
    }
}
walk(source.outliner ?? []);
const transmission = [...groups.values()].find(g => g.name === 'chuan_dong_ji_gou');
if (!transmission || transmission.rotation.some(v => v !== 0)) throw Error('Unexpected transmission pivot');
const translation = [8, 8 - transmission.origin[1], 8 - transmission.origin[2]];
function bakePoint(p, element) {
    let v = rotatePoint(p, element.origin, element.rotation ?? [0,0,0]);
    for (const g of [...(ancestors.get(element.uuid) ?? [])].reverse())
        v = rotatePoint(v, g.origin ?? [0,0,0], g.rotation ?? [0,0,0]);
    return v.map((c,i) => c + translation[i]);
}
function bakeVector(p, element) {
    let v = rotateVector(p, element.rotation ?? [0,0,0]);
    for (const g of [...(ancestors.get(element.uuid) ?? [])].reverse())
        v = rotateVector(v, g.rotation ?? [0,0,0]);
    return v;
}
function elementVertices(element) {
    const [x0, y0, z0] = element.from.map(Number);
    const [x1, y1, z1] = element.to.map(Number);
    const vertices = [
        [x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
        [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]
    ];
    return vertices.map(v => bakePoint(v, element));
}

const faceVertices = {
    north: [0, 3, 2, 1],
    south: [4, 5, 6, 7],
    west: [0, 4, 7, 3],
    east: [1, 2, 6, 5],
    down: [0, 1, 5, 4],
    up: [3, 7, 6, 2]
};

function faceUvCorners(face, direction) {
    const [u1, v1, u2, v2] = face.uv.map(Number);
    const pixelCorners = {
        north: [[u1, v2], [u1, v1], [u2, v1], [u2, v2]],
        south: [[u1, v2], [u2, v2], [u2, v1], [u1, v1]],
        west: [[u1, v2], [u2, v2], [u2, v1], [u1, v1]],
        east: [[u1, v2], [u1, v1], [u2, v1], [u2, v2]],
        down: [[u1, v1], [u2, v1], [u2, v2], [u1, v2]],
        up: [[u1, v1], [u1, v2], [u2, v2], [u2, v1]]
    };
    let corners = pixelCorners[direction].map(([u, v]) => [
        u / textureWidth,
        1 - v / textureHeight
    ]);
    const turns = (((Number(face.rotation ?? 0) / 90) % 4) + 4) % 4;
    if (turns) {
        corners = corners.map((ignored, index) => corners[(index + turns) % 4]);
    }
    return corners;
}

function interpolateVertex(left, right, amount) {
    return {
        position: left.position.map(
            (value, index) => value + (right.position[index] - value) * amount
        ),
        uv: left.uv.map(
            (value, index) => value + (right.uv[index] - value) * amount
        )
    };
}

function clipAgainstAxis(polygon, boundary, keepGreater, axis) {
    const result = [];
    for (let index = 0; index < polygon.length; index++) {
        const current = polygon[index];
        const previous = polygon[(index + polygon.length - 1) % polygon.length];
        const currentInside = keepGreater
            ? current.position[axis] >= boundary - 1e-8
            : current.position[axis] <= boundary + 1e-8;
        const previousInside = keepGreater
            ? previous.position[axis] >= boundary - 1e-8
            : previous.position[axis] <= boundary + 1e-8;

        if (currentInside !== previousInside) {
            const amount = (boundary - previous.position[axis])
                / (current.position[axis] - previous.position[axis]);
            result.push(interpolateVertex(previous, current, amount));
        }
        if (currentInside) {
            result.push(current);
        }
    }
    return result;
}

const exportedElements = source.elements.filter(
    element => element.type === "cube" && element.export !== false
);
const bounds = [0,1,2].map(i => {
    const v = exportedElements.flatMap(elementVertices).map(p => p[i]);
    return [Math.min(...v), Math.max(...v)];
});
const segments = [];
const itemShift = bounds.map(([minimum, maximum]) => 8 - (minimum + maximum) / 2);
for (let y = Math.floor(bounds[1][0]/16); y <= Math.floor(bounds[1][1]/16); y++)
for (let z = Math.floor(bounds[2][0]/16); z <= Math.floor(bounds[2][1]/16); z++)
for (let x = Math.floor(bounds[0][0]/16); x <= Math.floor(bounds[0][1]/16); x++) {
    const coordinate = [x,y,z];
    const name = coordinate.map(v => v < 0 ? 'm'+(-v) : 'p'+v).join('_');
    segments.push({name, coordinate, shift: coordinate.map(v => -16*v)});
}

function writeObj(name, segment, elements = exportedElements, customShift = null) {
    const lines = [
        "# Three-axis rotations baked from Blockbench by Create: Fire Control",
        `mtllib ${name}.mtl`,
        "usemtl layer0",
        ""
    ];
    let nextIndex = 1;
    let triangleCount = 0;

    for (const element of elements) {
        const vertices = elementVertices(element);
        for (const [direction, indices] of Object.entries(faceVertices)) {
            const face = element.faces?.[direction];
            if (!face || face.texture === null || face.texture === undefined) {
                continue;
            }
            const uvCorners = faceUvCorners(face, direction);
            let polygon = indices.map((vertexIndex, index) => ({
                position: vertices[vertexIndex],
                uv: uvCorners[index]
            }));
            if (segment) {
                for (let axis=0; axis<3; axis++) {
                    polygon = clipAgainstAxis(polygon, -segment.shift[axis], true, axis);
                    polygon = clipAgainstAxis(polygon, 16-segment.shift[axis], false, axis);
                }
            }
            if (polygon.length < 3) {
                continue;
            }

            for (let index = 1; index + 1 < polygon.length; index++) {
                const triangle = [polygon[0], polygon[index], polygon[index + 1]];
                for (const vertex of triangle) {
                    const shift = segment?.shift ?? customShift ?? itemShift;
                    const position = [
                        (vertex.position[0] + shift[0]) / 16,
                        (vertex.position[1] + shift[1]) / 16,
                        (vertex.position[2] + shift[2]) / 16
                    ];
                    lines.push(`v ${position.map(cleanNumber).join(" ")}`);
                }
                for (const vertex of triangle) {
                    lines.push(`vt ${vertex.uv.map(cleanNumber).join(" ")}`);
                }
                lines.push(
                    `f ${nextIndex}/${nextIndex} ${nextIndex + 1}/${nextIndex + 1} `
                    + `${nextIndex + 2}/${nextIndex + 2}`
                );
                nextIndex += 3;
                triangleCount++;
            }
        }
    }
    fs.writeFileSync(
        path.join(blockModelDirectory, `${name}.obj`),
        `${lines.join("\n")}\n`,
        "utf8"
    );
    fs.writeFileSync(
        path.join(blockModelDirectory, `${name}.mtl`),
        "newmtl layer0\nmap_Kd #layer0\n",
        "utf8"
    );
    return triangleCount;
}

function blockModel(name) {
    return {
        parent: "minecraft:block/block",
        ambientocclusion: true,
        textures: {
            layer0: "create_fire_control:block/ztz99a_diesel_engine",
            particle: "create_fire_control:block/ztz99a_diesel_engine"
        },
        loader: "neoforge:obj",
        flip_v: true,
        automatic_culling: false,
        shade_quads: true,
        model: `create_fire_control:models/block/ztz99a_diesel_engine/${name}.obj`
    };
}

// Split only animated geometry out of the existing block slices. Leave inventory,
// texture, part placement and collision code untouched in this mode.
if (animationOnly) {
    const animation = source.animations.find(a => Object.values(a.animators ?? {}).some(b => b.keyframes?.length));
    if (!animation) throw Error('No original animation keyframes');
    const fans = Object.entries(animation.animators).filter(([id, b]) => b.keyframes?.length).map(([id, animator]) => {
        const group = groups.get(id);
        const elements = exportedElements.filter(e => (ancestors.get(e.uuid) ?? []).some(g => g.uuid === id));
        if (!group || !elements.length) throw Error('Missing animated group geometry');
        const frames = animator.keyframes.slice().sort((a,b) => a.time - b.time);
        if (frames.some(k => k.channel !== 'rotation' || k.interpolation !== 'linear'
                || Number(k.data_points[0].y) !== 0 || Number(k.data_points[0].z) !== 0))
            throw Error('Original diesel animation is not a linear local-X rotation');
        const chain = (ancestors.get(elements[0].uuid) ?? []).slice().reverse();
        let pivot = group.origin.slice(), axis = [1,0,0];
        for (const g of chain) {
            pivot = rotatePoint(pivot, g.origin ?? [0,0,0], g.rotation ?? [0,0,0]);
            axis = rotateVector(axis, g.rotation ?? [0,0,0]);
        }
        pivot = pivot.map((v,i) => v + translation[i]);
        const name = 'fan_' + group.name;
        writeObj(name, null, elements, pivot.map(v => -v));
        fs.writeFileSync(path.join(blockModelDirectory, name + '.json'), JSON.stringify({...blockModel(name), ambientocclusion: false},null,2)+'\n');
        return {name, bone: group.name, pivot: pivot.map(v => v/16), axis,
            times: frames.map(k => Number(k.time)), angles: frames.map(k => Number(k.data_points[0].x)),
            elements: elements.map(e => e.uuid)};
    });
    const animatedIds = new Set(fans.flatMap(f => f.elements));
    for (const segment of segments)
        writeObj(segment.name, segment, exportedElements.filter(e => !animatedIds.has(e.uuid)));
    const fArray = a => '{' + a.map(v => cleanNumber(v) + 'F').join(', ') + '}';
    const profile = [
        'package com.hooya.stabilizedturret.content.diesel;',
        '/** Original Blockbench rotation keyframes and baked pivots; no mechanical state changes. */',
        'public final class Ztz99aDieselModelAnimation {',
        '    public static final float LENGTH_SECONDS = ' + cleanNumber(animation.length) + 'F;',
        '    public static final float[][] PIVOTS = {' + fans.map(f => fArray(f.pivot)).join(', ') + '};',
        '    public static final float[][] AXES = {' + fans.map(f => fArray(f.axis)).join(', ') + '};',
        '    private static final float[][] TIMES = {' + fans.map(f => fArray(f.times)).join(', ') + '};',
        '    private static final float[][] ANGLES = {' + fans.map(f => fArray(f.angles)).join(', ') + '};',
        '    public static float angle(int fan, float seconds) {',
        '        float t = (seconds % LENGTH_SECONDS + LENGTH_SECONDS) % LENGTH_SECONDS;',
        '        float[] times = TIMES[fan], angles = ANGLES[fan];',
        '        for (int i = 1; i < times.length; i++) if (t <= times[i])',
        '            return angles[i-1] + (angles[i]-angles[i-1]) * (t-times[i-1]) / (times[i]-times[i-1]);',
        '        return angles[angles.length-1];',
        '    }',
        '    private Ztz99aDieselModelAnimation() {}',
        '}'];
    fs.writeFileSync(path.join(javaDirectory,'Ztz99aDieselModelAnimation.java'),profile.join('\n')+'\n');
    fs.writeFileSync(path.join(metadataDirectory,'animation_manifest.json'),JSON.stringify({source: sourcePath,
        animation: animation.name, originalLoop: animation.loop, runtimeLoop: 'while_engine_enabled',
        seconds: animation.length, fans},null,2)+'\n');
    console.log(`Exported ${fans.length} animated groups (${animatedIds.size} cubes), original ${animation.length}s keyframes`);
    process.exit(0);
}

for (const segment of segments) {
    const triangleCount = writeObj(segment.name, segment);
    fs.writeFileSync(
        path.join(blockModelDirectory, `${segment.name}.json`),
        `${JSON.stringify(blockModel(segment.name), null, 2)}\n`,
        "utf8"
    );
    console.log(`${segment.name}: ${triangleCount} rendered triangles`);
}

// The inventory model uses the complete baked mesh, centered independently of block coordinates.
const itemModel = {
    parent: "minecraft:block/block",
    ambientocclusion: true,
    textures: {
        layer0: "create_fire_control:block/ztz99a_diesel_engine",
        particle: "create_fire_control:block/ztz99a_diesel_engine"
    },
    loader: "neoforge:obj",
    flip_v: true,
    automatic_culling: false,
    shade_quads: true,
    model: "create_fire_control:models/block/ztz99a_diesel_engine/item.obj",
    display: {
        thirdperson_righthand: {
            rotation: [0, 45, 0],
            translation: [0, 2.5, 0],
            scale: [0.28, 0.28, 0.28]
        },
        thirdperson_lefthand: {
            rotation: [0, 45, 0],
            translation: [0, 2.5, 0],
            scale: [0.28, 0.28, 0.28]
        },
        firstperson_righthand: {
            rotation: [0, 45, 0],
            translation: [0, 2, 0],
            scale: [0.3, 0.3, 0.3]
        },
        firstperson_lefthand: {
            rotation: [0, 45, 0],
            translation: [0, 2, 0],
            scale: [0.3, 0.3, 0.3]
        },
        ground: {
            translation: [0, 2, 0],
            scale: [0.24, 0.24, 0.24]
        },
        gui: {
            rotation: [25, 135, 0],
            translation: [0, 0, 0],
            scale: [0.30, 0.30, 0.30]
        },
        fixed: {
            rotation: [0, 90, 0],
            scale: [0.32, 0.32, 0.32]
        }
    }
};
writeObj("item", null);
fs.writeFileSync(
    path.join(itemModelDirectory, "ztz99a_diesel_engine.json"),
    `${JSON.stringify(itemModel, null, 2)}\n`,
    "utf8"
);

// Collision is a conservative 1/16-block voxelization of every rotated cube.
// This preserves thin diagonal blades while avoiding oversized cube AABBs.
function matrixFor(rotation) {
    const columns = [
        rotateVector([1, 0, 0], rotation),
        rotateVector([0, 1, 0], rotation),
        rotateVector([0, 0, 1], rotation)
    ];
    return columns;
}

const orientedBoxes = exportedElements.map(element => {
    const from = element.from.map(Number);
    const to = element.to.map(Number);
    const localCenter = from.map((value, index) => (value + to[index]) / 2);
    const half = from.map((value, index) => Math.abs(to[index] - value) / 2);
    const rotation = (element.rotation ?? [0, 0, 0]).map(Number);
    const origin = element.origin.map(Number);
    return {
        center: bakePoint(localCenter, element),
        half,
        axes: [[1,0,0],[0,1,0],[0,0,1]].map(v => bakeVector(v, element))
    };
});

function dot(left, right) {
    return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
}

function intersectsVoxel(box, voxelCenter) {
    const aHalf = [0.49999, 0.49999, 0.49999];
    const translationWorld = box.center.map(
        (value, index) => voxelCenter[index] - value
    );
    const translation = box.axes.map(axis => dot(translationWorld, axis));
    const rotation = box.axes.map(axis => axis.slice());
    const absoluteRotation = rotation.map(row =>
        row.map(value => Math.abs(value) + 1e-9)
    );

    for (let axis = 0; axis < 3; axis++) {
        const radiusA = box.half[axis];
        const radiusB = aHalf[0] * absoluteRotation[axis][0]
            + aHalf[1] * absoluteRotation[axis][1]
            + aHalf[2] * absoluteRotation[axis][2];
        if (Math.abs(translation[axis]) > radiusA + radiusB) {
            return false;
        }
    }

    for (let axis = 0; axis < 3; axis++) {
        const radiusA = box.half[0] * absoluteRotation[0][axis]
            + box.half[1] * absoluteRotation[1][axis]
            + box.half[2] * absoluteRotation[2][axis];
        const radiusB = aHalf[axis];
        if (Math.abs(translationWorld[axis]) > radiusA + radiusB) {
            return false;
        }
    }

    for (let a = 0; a < 3; a++) {
        for (let b = 0; b < 3; b++) {
            const a1 = (a + 1) % 3;
            const a2 = (a + 2) % 3;
            const b1 = (b + 1) % 3;
            const b2 = (b + 2) % 3;
            const radiusA = box.half[a1] * absoluteRotation[a2][b]
                + box.half[a2] * absoluteRotation[a1][b];
            const radiusB = aHalf[b1] * absoluteRotation[a][b2]
                + aHalf[b2] * absoluteRotation[a][b1];
            const distance = Math.abs(
                translation[a2] * rotation[a1][b]
                - translation[a1] * rotation[a2][b]
            );
            if (distance > radiusA + radiusB) {
                return false;
            }
        }
    }
    return true;
}

function voxelize(segment) {
    const occupied = Array.from({length: 16}, () =>
        Array.from({length: 16}, () => Array(16).fill(false))
    );
    for (let y = 0; y < 16; y++) {
        for (let z = 0; z < 16; z++) {
            for (let x = 0; x < 16; x++) {
                const center = [
                    x + 0.5 - segment.shift[0],
                    y + 0.5 - segment.shift[1],
                    z + 0.5 - segment.shift[2]
                ];
                occupied[y][z][x] = orientedBoxes.some(
                    box => intersectsVoxel(box, center)
                );
            }
        }
    }
    return occupied;
}

function mergeVoxels(occupied) {
    const used = Array.from({length: 16}, () =>
        Array.from({length: 16}, () => Array(16).fill(false))
    );
    const boxes = [];
    for (let y0 = 0; y0 < 16; y0++) {
        for (let z0 = 0; z0 < 16; z0++) {
            for (let x0 = 0; x0 < 16; x0++) {
                if (!occupied[y0][z0][x0] || used[y0][z0][x0]) {
                    continue;
                }
                let x1 = x0 + 1;
                while (x1 < 16 && occupied[y0][z0][x1] && !used[y0][z0][x1]) {
                    x1++;
                }
                let z1 = z0 + 1;
                while (z1 < 16) {
                    let complete = true;
                    for (let x = x0; x < x1; x++) {
                        complete &&= occupied[y0][z1][x] && !used[y0][z1][x];
                    }
                    if (!complete) break;
                    z1++;
                }
                let y1 = y0 + 1;
                while (y1 < 16) {
                    let complete = true;
                    for (let z = z0; z < z1; z++) {
                        for (let x = x0; x < x1; x++) {
                            complete &&= occupied[y1][z][x] && !used[y1][z][x];
                        }
                    }
                    if (!complete) break;
                    y1++;
                }
                for (let y = y0; y < y1; y++) {
                    for (let z = z0; z < z1; z++) {
                        for (let x = x0; x < x1; x++) {
                            used[y][z][x] = true;
                        }
                    }
                }
                boxes.push([x0, y0, z0, x1, y1, z1]);
            }
        }
    }
    return boxes;
}

function rotateBox(box, facing) {
    const [x0, y0, z0, x1, y1, z1] = box;
    switch (facing) {
        case "NORTH": return [x0, y0, z0, x1, y1, z1];
        case "EAST": return [16 - z1, y0, x0, 16 - z0, y1, x1];
        case "SOUTH": return [16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0];
        case "WEST": return [z0, y0, 16 - x1, z1, y1, 16 - x0];
        default: throw new Error(`Unsupported facing: ${facing}`);
    }
}

const sourceCollisionBoxes = {};
for (const segment of segments) {
    const boxes = mergeVoxels(voxelize(segment));
    if (!boxes.length) {
        throw new Error(`No collision voxels generated for ${segment.name}`);
    }
    sourceCollisionBoxes[segment.name] = boxes;
    console.log(`${segment.name}: ${boxes.length} merged collision boxes`);
}


const facingNames = ['NORTH','EAST','SOUTH','WEST'];
const java = [
    'package com.hooya.stabilizedturret.content.diesel;',
    'import net.minecraft.core.Direction;',
    'import net.minecraft.world.level.block.Block;',
    'import net.minecraft.world.phys.shapes.Shapes;',
    'import net.minecraft.world.phys.shapes.VoxelShape;',
    '/** Generated from the fully baked 150HB model. Every collider stays within its own cell. */',
    'final class Ztz99aDieselEngineCollisionShapes {',
    '    private static final VoxelShape[][] SHAPES = new VoxelShape[Ztz99aDieselEnginePart.values().length][4];',
    '    static {'
];
const enumValues = [];
const variants = {};
for (const segment of segments) {
    const [x,y,z] = segment.coordinate;
    const part = segment.name.toUpperCase();
    enumValues.push(part+'("'+segment.name+'", '+[x,y,z].join(', ')+')');
    for (let i=0;i<4;i++) {
        java.push('        SHAPES[Ztz99aDieselEnginePart.'+part+'.ordinal()]['+i+'] = build(new double[][] {');
        for (const box of sourceCollisionBoxes[segment.name]) java.push('            {'+rotateBox(box,facingNames[i]).join(', ')+'},');
        java.push('        });');
        variants['facing='+facingNames[i].toLowerCase()+',part='+segment.name] = {
            model: 'create_fire_control:block/ztz99a_diesel_engine/'+segment.name, y:i*90
        };
    }
}
java.push('    }', '    static VoxelShape get(Ztz99aDieselEnginePart part, Direction facing) {',
    '        int i = switch(facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };',
    '        return SHAPES[part.ordinal()][i];', '    }',
    '    private static VoxelShape build(double[][] boxes) {',
    '        VoxelShape shape = Shapes.empty();',
    '        for(double[] b : boxes) shape = Shapes.or(shape, Block.box(b[0],b[1],b[2],b[3],b[4],b[5]));',
    '        return shape.optimize();', '    }', '}');
fs.writeFileSync(path.join(javaDirectory,'Ztz99aDieselEngineCollisionShapes.java'),java.join('\n')+'\n');
fs.writeFileSync(path.join(javaDirectory,'Ztz99aDieselEnginePart.java'),
    'package com.hooya.stabilizedturret.content.diesel;\n'+
    'import net.minecraft.util.StringRepresentable;\n'+
    'public enum Ztz99aDieselEnginePart implements StringRepresentable {\n    '+enumValues.join(',\n    ')+';\n'+
    '    private final String name; public final int x,y,z;\n'+
    '    Ztz99aDieselEnginePart(String name,int x,int y,int z) { this.name=name; this.x=x; this.y=y; this.z=z; }\n'+
    '    public String getSerializedName() { return name; }\n'+
    '    public boolean isController() { return x==0 && y==0 && z==0; }\n'+
    '    public boolean isOutput() { return Math.abs(x)==1 && y==0 && z==0; }\n}\n');
fs.writeFileSync(path.join(assetRoot,'blockstates','ztz99a_diesel_engine.json'),JSON.stringify({variants},null,2)+'\n');
fs.writeFileSync(path.join(metadataDirectory,'geometry_manifest.json'),JSON.stringify({
    transmissionPivot:transmission.origin, translation, bounds, cells:segments.map(s=>s.coordinate),
    originalSurfaceCenters:[[-18.5,...transmission.origin.slice(1)],[18.5,...transmission.origin.slice(1)]],
    shaftAxisCenters:[[-16,8,8],[32,8,8]], scale:1, elements:exportedElements.length
},null,2)+'\n');
console.log(JSON.stringify({bounds, translation, cells:segments.length}));
