const fs = require("fs");
const path = require("path");

if (process.argv.length !== 3) {
    throw new Error("Usage: node convert_gas_turbine_bbmodel.js <source.bbmodel>");
}

const sourcePath = path.resolve(process.argv[2]);
const projectRoot = path.resolve(__dirname, "..");
const assetRoot = path.join(
    projectRoot, "src", "main", "resources", "assets", "create_fire_control"
);
const blockModelDirectory = path.join(assetRoot, "models", "block", "gas_turbine");
const itemModelDirectory = path.join(assetRoot, "models", "item");
const textureDirectory = path.join(assetRoot, "textures", "block");
const javaDirectory = path.join(
    projectRoot, "src", "main", "java", "com", "hooya", "stabilizedturret",
    "content", "turbine"
);

for (const directory of [
    blockModelDirectory, itemModelDirectory, textureDirectory, javaDirectory
]) {
    fs.mkdirSync(directory, { recursive: true });
}

const source = JSON.parse(fs.readFileSync(sourcePath, "utf8"));
if (source?.meta?.model_format !== "java_block") {
    throw new Error(`Expected a java_block Blockbench project: ${sourcePath}`);
}
if (!Array.isArray(source.textures) || source.textures.length !== 1) {
    throw new Error("The gas turbine converter expects exactly one texture");
}

const texture = source.textures[0];
const textureMatch = /^data:image\/png;base64,(.+)$/.exec(texture.source);
if (!textureMatch) {
    throw new Error("The gas turbine texture is not embedded as a PNG");
}
fs.writeFileSync(
    path.join(textureDirectory, "gas_turbine.png"),
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

function elementVertices(element) {
    const [x0, y0, z0] = element.from.map(Number);
    const [x1, y1, z1] = element.to.map(Number);
    const vertices = [
        [x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
        [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]
    ];
    const rotation = (element.rotation ?? [0, 0, 0]).map(Number);
    if (rotation.every(value => Math.abs(value) < 1e-9)) {
        return vertices;
    }
    const origin = element.origin.map(Number);
    return vertices.map(vertex => rotatePoint(vertex, origin, rotation));
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

function clipAgainstX(polygon, boundary, keepGreater) {
    const result = [];
    for (let index = 0; index < polygon.length; index++) {
        const current = polygon[index];
        const previous = polygon[(index + polygon.length - 1) % polygon.length];
        const currentInside = keepGreater
            ? current.position[0] >= boundary - 1e-8
            : current.position[0] <= boundary + 1e-8;
        const previousInside = keepGreater
            ? previous.position[0] >= boundary - 1e-8
            : previous.position[0] <= boundary + 1e-8;

        if (currentInside !== previousInside) {
            const amount = (boundary - previous.position[0])
                / (current.position[0] - previous.position[0]);
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
const segments = [
    {name: "negative", minX: -16, maxX: 0, shiftX: 16},
    {name: "center", minX: 0, maxX: 16, shiftX: 0},
    {name: "positive", minX: 16, maxX: 32, shiftX: -16}
];

function writeObj(name, segment) {
    const lines = [
        "# Three-axis rotations baked from Blockbench by Create: Fire Control",
        `mtllib ${name}.mtl`,
        "usemtl layer0",
        ""
    ];
    let nextIndex = 1;
    let triangleCount = 0;

    for (const element of exportedElements) {
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
                polygon = clipAgainstX(polygon, segment.minX, true);
                polygon = clipAgainstX(polygon, segment.maxX, false);
            }
            if (polygon.length < 3) {
                continue;
            }

            for (let index = 1; index + 1 < polygon.length; index++) {
                const triangle = [polygon[0], polygon[index], polygon[index + 1]];
                for (const vertex of triangle) {
                    const shiftX = segment?.shiftX ?? 0;
                    const position = [
                        (vertex.position[0] + shiftX) / 16,
                        vertex.position[1] / 16,
                        vertex.position[2] / 16
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
            layer0: "create_fire_control:block/gas_turbine",
            particle: "create_fire_control:block/gas_turbine_particle"
        },
        loader: "neoforge:obj",
        flip_v: true,
        automatic_culling: false,
        shade_quads: true,
        model: `create_fire_control:models/block/gas_turbine/${name}.obj`
    };
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

// The inventory model uses all three baked slices as one multipart model.
const itemModel = {
    parent: "minecraft:block/block",
    ambientocclusion: true,
    textures: {
        layer0: "create_fire_control:block/gas_turbine",
        particle: "create_fire_control:block/gas_turbine_particle"
    },
    loader: "neoforge:obj",
    flip_v: true,
    automatic_culling: false,
    shade_quads: true,
    model: "create_fire_control:models/block/gas_turbine/item.obj",
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
    path.join(itemModelDirectory, "gas_turbine.json"),
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
        center: rotatePoint(localCenter, origin, rotation),
        half,
        axes: matrixFor(rotation)
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
                    x + 0.5 - segment.shiftX,
                    y + 0.5,
                    z + 0.5
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
        case "EAST": return [x0, y0, z0, x1, y1, z1];
        case "SOUTH": return [16 - z1, y0, x0, 16 - z0, y1, x1];
        case "WEST": return [16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0];
        case "NORTH": return [z0, y0, 16 - x1, z1, y1, 16 - x0];
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

const facingNames = ["NORTH", "SOUTH", "WEST", "EAST"];
const partNames = {
    negative: "NEGATIVE",
    center: "CENTER",
    positive: "POSITIVE"
};
const java = [
    "package com.hooya.stabilizedturret.content.turbine;",
    "",
    "import net.minecraft.core.Direction;",
    "import net.minecraft.world.level.block.Block;",
    "import net.minecraft.world.phys.shapes.Shapes;",
    "import net.minecraft.world.phys.shapes.VoxelShape;",
    "",
    "/** Generated from the baked gas turbine mesh at 1/16-block resolution. */",
    "final class GasTurbineCollisionShapes {"
];
for (const segment of segments) {
    for (const facing of facingNames) {
        const field = `${partNames[segment.name]}_${facing}`;
        java.push(`    private static final VoxelShape ${field} = build(new double[][] {`);
        for (const box of sourceCollisionBoxes[segment.name]) {
            java.push(`            {${rotateBox(box, facing).join(", ")}},`);
        }
        java.push("    });");
        java.push("");
    }
}
java.push(
    "    private GasTurbineCollisionShapes() {",
    "    }",
    "",
    "    static VoxelShape get(GasTurbinePart part, Direction facing) {",
    "        return switch (part) {",
    "            case NEGATIVE -> switch (facing) {",
    "                case NORTH -> NEGATIVE_NORTH;",
    "                case SOUTH -> NEGATIVE_SOUTH;",
    "                case WEST -> NEGATIVE_WEST;",
    "                default -> NEGATIVE_EAST;",
    "            };",
    "            case CENTER -> switch (facing) {",
    "                case NORTH -> CENTER_NORTH;",
    "                case SOUTH -> CENTER_SOUTH;",
    "                case WEST -> CENTER_WEST;",
    "                default -> CENTER_EAST;",
    "            };",
    "            case POSITIVE -> switch (facing) {",
    "                case NORTH -> POSITIVE_NORTH;",
    "                case SOUTH -> POSITIVE_SOUTH;",
    "                case WEST -> POSITIVE_WEST;",
    "                default -> POSITIVE_EAST;",
    "            };",
    "        };",
    "    }",
    "",
    "    private static VoxelShape build(double[][] boxes) {",
    "        VoxelShape shape = Shapes.empty();",
    "        for (double[] box : boxes) {",
    "            shape = Shapes.or(shape, Block.box(",
    "                    box[0], box[1], box[2], box[3], box[4], box[5]));",
    "        }",
    "        return shape.optimize();",
    "    }",
    "}",
    ""
);
fs.writeFileSync(
    path.join(javaDirectory, "GasTurbineCollisionShapes.java"),
    java.join("\n"),
    "utf8"
);

console.log(
    `Converted ${exportedElements.length} cubes; all element Euler rotations are baked`
);
