const fs = require("fs");
const path = require("path");

if (process.argv.length !== 6) {
    throw new Error(
        "Usage: node convert_item_bbmodel_to_obj.js <source.bbmodel> "
        + "<item_model_name> <texture_name> <vertical|projectile>"
    );
}

const sourcePath = path.resolve(process.argv[2]);
const modelName = process.argv[3];
const textureName = process.argv[4];
const mode = process.argv[5];
if (mode !== "vertical" && mode !== "projectile") {
    throw new Error(`Unknown conversion mode: ${mode}`);
}

const projectRoot = path.resolve(__dirname, "..");
const assetRoot = path.join(
    projectRoot,
    "src",
    "main",
    "resources",
    "assets",
    "stabilized_turret"
);
const modelDirectory = path.join(assetRoot, "models", "item");
const textureDirectory = path.join(assetRoot, "textures", "item");
fs.mkdirSync(modelDirectory, { recursive: true });
fs.mkdirSync(textureDirectory, { recursive: true });

const source = JSON.parse(fs.readFileSync(sourcePath, "utf8"));
if (source?.meta?.model_format !== "java_block") {
    throw new Error(`Expected java_block model: ${sourcePath}`);
}
if (!Array.isArray(source.textures) || source.textures.length !== 1) {
    throw new Error(`Expected exactly one embedded texture: ${sourcePath}`);
}

const texture = source.textures[0];
const textureMatch = /^data:image\/png;base64,(.+)$/.exec(texture.source);
if (!textureMatch) {
    throw new Error(`Texture is not an embedded PNG: ${sourcePath}`);
}
fs.writeFileSync(
    path.join(textureDirectory, `${textureName}.png`),
    Buffer.from(textureMatch[1], "base64")
);

const textureWidth = Number(texture.uv_width ?? source.resolution.width);
const textureHeight = Number(texture.uv_height ?? source.resolution.height);
const radians = degrees => degrees * Math.PI / 180;
const number = value => {
    const rounded = Math.abs(value) < 1e-10 ? 0 : value;
    return Number(rounded.toFixed(8)).toString();
};

function rotatePoint(point, origin, rotation) {
    let [x, y, z] = [
        point[0] - origin[0],
        point[1] - origin[1],
        point[2] - origin[2]
    ];
    const [rx, ry, rz] = rotation.map(radians);

    // Blockbench cube previews use Euler order ZYX, which applies X, then Y,
    // then Z to a point.
    let cos = Math.cos(rx);
    let sin = Math.sin(rx);
    [y, z] = [y * cos - z * sin, y * sin + z * cos];

    cos = Math.cos(ry);
    sin = Math.sin(ry);
    [x, z] = [x * cos + z * sin, -x * sin + z * cos];

    cos = Math.cos(rz);
    sin = Math.sin(rz);
    [x, y] = [x * cos - y * sin, x * sin + y * cos];

    return [x + origin[0], y + origin[1], z + origin[2]];
}

function elementVertices(element) {
    const [x0, y0, z0] = element.from.map(Number);
    const [x1, y1, z1] = element.to.map(Number);
    let vertices = [
        [x0, y0, z0],
        [x1, y0, z0],
        [x1, y1, z0],
        [x0, y1, z0],
        [x0, y0, z1],
        [x1, y0, z1],
        [x1, y1, z1],
        [x0, y1, z1]
    ];
    const rotation = Array.isArray(element.rotation)
        ? element.rotation.map(Number)
        : [0, 0, 0];
    if (rotation.some(value => Math.abs(value) > 1e-8)) {
        const origin = element.origin.map(Number);
        vertices = vertices.map(point => rotatePoint(point, origin, rotation));
    }
    return vertices;
}

const exportedElements = (source.elements ?? []).filter(
    element => element.type === "cube" && element.export !== false
);
const sourceVertices = exportedElements.flatMap(elementVertices);
const projectileAxisSum = mode === "projectile"
    ? Math.min(...sourceVertices.map(point => point[1]))
        + Math.max(...sourceVertices.map(point => point[1]))
    : 0;

function toObjCoordinates(point) {
    // NeoForge OBJ models use Minecraft block coordinates. The center of an
    // item is (0.5, 0.5, 0.5), not the mathematical origin.
    const x = point[0] / 16;
    if (mode === "projectile") {
        // CBC-MW renders projectile items with the NONE display context. Keep
        // the transverse origin on the entity itself and mirror the source
        // model along its length: the fins trail at positive Z while the nose
        // points toward negative Z, matching CBC-MW's own APFSDS model.
        return [
            x,
            (16 - point[2]) / 16,
            (projectileAxisSum - point[1]) / 16 + 0.5
        ];
    }
    return [x, point[1] / 16, point[2] / 16];
}

const faceVertices = {
    north: [0, 3, 2, 1],
    south: [4, 5, 6, 7],
    west: [0, 4, 7, 3],
    east: [1, 2, 6, 5],
    down: [0, 1, 5, 4],
    up: [3, 7, 6, 2]
};

const obj = [
    "# Converted from Blockbench by Create: Fire Control",
    `mtllib ${modelName}.mtl`,
    ""
];
let vertexOffset = 0;
let uvOffset = 0;
let exportedCubes = 0;
const convertedVertices = [];

for (const element of exportedElements) {
    const vertices = elementVertices(element).map(toObjCoordinates);
    convertedVertices.push(...vertices);

    obj.push(`o cube_${exportedCubes}`);
    obj.push("usemtl layer0");
    for (const vertex of vertices) {
        obj.push(`v ${vertex.map(number).join(" ")}`);
    }

    for (const [direction, indices] of Object.entries(faceVertices)) {
        const face = element.faces?.[direction];
        if (!face || face.texture === null || face.texture === undefined) {
            continue;
        }
        const [u1, v1, u2, v2] = face.uv.map(Number);
        let corners = [
            [u1, v2],
            [u1, v1],
            [u2, v1],
            [u2, v2]
        ];
        const turns = (((Number(face.rotation ?? 0) / 90) % 4) + 4) % 4;
        if (turns) {
            corners = corners.map((_, index) => corners[(index + turns) % 4]);
        }
        for (const [u, v] of corners) {
            // Blockbench writes standard OBJ V coordinates. The item model
            // enables flip_v so NeoForge converts them back to Minecraft UVs.
            obj.push(`vt ${number(u / textureWidth)} ${number(1 - v / textureHeight)}`);
        }
        let references = indices.map((vertexIndex, index) =>
            `${vertexOffset + vertexIndex + 1}/${uvOffset + index + 1}`
        );
        if (mode === "projectile") {
            // Mirroring the dart along its length changes handedness. Reverse
            // complete vertex/UV pairs to restore front-facing polygons while
            // keeping every texture corner attached to its original vertex.
            references = references.reverse();
        }
        obj.push(`f ${references.join(" ")}`);
        uvOffset += 4;
    }
    obj.push("");
    vertexOffset += 8;
    exportedCubes++;
}

if (exportedCubes === 0) {
    throw new Error(`No exported cubes in ${sourcePath}`);
}

function rotateDisplayPoint(point, rotation) {
    let [x, y, z] = point;
    const [rx, ry, rz] = rotation.map(radians);

    // ItemTransform uses JOML Quaternionf.rotationXYZ. With Minecraft's pose
    // matrix multiplication, vertices receive Z, then Y, then X rotation.
    let cos = Math.cos(rz);
    let sin = Math.sin(rz);
    [x, y] = [x * cos - y * sin, x * sin + y * cos];

    cos = Math.cos(ry);
    sin = Math.sin(ry);
    [x, z] = [x * cos + z * sin, -x * sin + z * cos];

    cos = Math.cos(rx);
    sin = Math.sin(rx);
    [y, z] = [y * cos - z * sin, y * sin + z * cos];
    return [x, y, z];
}

function fittedGuiTransform(rotation, translation, targetSpan) {
    const rotated = convertedVertices.map(point => rotateDisplayPoint(point, rotation));
    const minX = Math.min(...rotated.map(point => point[0]));
    const maxX = Math.max(...rotated.map(point => point[0]));
    const minY = Math.min(...rotated.map(point => point[1]));
    const maxY = Math.max(...rotated.map(point => point[1]));
    const scale = targetSpan / Math.max(maxX - minX, maxY - minY);
    return {
        rotation,
        // Match CBC-MW's proven inventory transforms. The OBJ coordinates are
        // now in the same 0..1 block space as its vanilla JSON elements.
        translation,
        scale: [scale, scale, scale].map(value => Number(value.toFixed(4)))
    };
}

const mtl = [
    "# Converted from Blockbench by Create: Fire Control",
    "",
    "newmtl layer0",
    "map_Kd #layer0",
    ""
].join("\n");

const verticalDisplay = {
    thirdperson_righthand: {
        translation: [0, 3, 1],
        scale: [0.55, 0.55, 0.55]
    },
    thirdperson_lefthand: {
        translation: [0, 3, 1],
        scale: [0.55, 0.55, 0.55]
    },
    firstperson_righthand: {
        translation: [0, 3, 1],
        scale: [0.6, 0.6, 0.6]
    },
    firstperson_lefthand: {
        translation: [0, 3, 1],
        scale: [0.6, 0.6, 0.6]
    },
    ground: {
        rotation: [-90, 0, 0],
        translation: [0, 0, 2.5],
        scale: [0.5, 0.5, 0.5]
    },
    gui: modelName.includes("casing")
        ? fittedGuiTransform([-50, 0, 20], [2, -2, 0], 0.78)
        : fittedGuiTransform([-50, 0, 30], [1.25, -1.25, 0], 0.9),
    fixed: {
        translation: [0, -4, 0],
        scale: [0.7, 0.7, 0.7]
    }
};

const projectileDisplay = {
    thirdperson_righthand: {
        rotation: [88, 1, 2],
        translation: [0, 0.75, 1],
        scale: [0.55, 0.55, 0.55]
    },
    thirdperson_lefthand: {
        rotation: [88, 1, 2],
        translation: [0, 0.75, 1],
        scale: [0.55, 0.55, 0.55]
    },
    firstperson_righthand: {
        rotation: [88, 1, 2],
        translation: [0, 0.75, 1],
        scale: [0.6, 0.6, 0.6]
    },
    firstperson_lefthand: {
        rotation: [88, 1, 2],
        translation: [0, 0.75, 1],
        scale: [0.6, 0.6, 0.6]
    },
    ground: {
        rotation: [-90, 0, 0],
        translation: [0, 0, 2.5],
        scale: [0.55, 0.55, 0.55]
    },
    gui: fittedGuiTransform([50, 40, -30], [-3.75, 3.75, 0], 0.9),
    fixed: {
        rotation: [90, 0, 0],
        scale: [0.8, 0.8, 0.8]
    }
};

const model = {
    parent: "minecraft:block/block",
    ambientocclusion: false,
    textures: {
        layer0: `stabilized_turret:item/${textureName}`,
        particle: `stabilized_turret:item/${textureName}`
    },
    loader: "neoforge:obj",
    flip_v: true,
    automatic_culling: false,
    shade_quads: true,
    model: `stabilized_turret:models/item/${modelName}.obj`,
    display: mode === "projectile" ? projectileDisplay : verticalDisplay
};

fs.writeFileSync(
    path.join(modelDirectory, `${modelName}.obj`),
    `${obj.join("\n")}\n`,
    "utf8"
);
fs.writeFileSync(
    path.join(modelDirectory, `${modelName}.mtl`),
    mtl,
    "utf8"
);
fs.writeFileSync(
    path.join(modelDirectory, `${modelName}.json`),
    `${JSON.stringify(model, null, 2)}\n`,
    "utf8"
);

console.log(
    `Converted ${path.basename(sourcePath)} to ${modelName}: `
    + `${exportedCubes} cubes, ${vertexOffset} vertices, ${uvOffset} UV vertices`
);
