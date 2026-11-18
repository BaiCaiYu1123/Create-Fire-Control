const fs = require("fs");
const path = require("path");

if (process.argv.length !== 4) {
    throw new Error(
        "Usage: node convert_mz_cannon_breech.js <closed.bbmodel> <open.bbmodel>"
    );
}

const closedPath = path.resolve(process.argv[2]);
const openPath = path.resolve(process.argv[3]);
const projectRoot = path.resolve(__dirname, "..");
const assetRoot = path.join(
    projectRoot, "src", "main", "resources", "assets", "create_fire_control"
);
const blockModelDirectory = path.join(
    assetRoot, "models", "block", "mz_cannon_breech"
);
const itemModelDirectory = path.join(assetRoot, "models", "item");
const textureDirectory = path.join(assetRoot, "textures", "block");

for (const directory of [blockModelDirectory, itemModelDirectory, textureDirectory]) {
    fs.mkdirSync(directory, {recursive: true});
}

function readProject(file) {
    const project = JSON.parse(fs.readFileSync(file, "utf8"));
    if (project?.meta?.model_format !== "java_block") {
        throw new Error(`Expected a java_block Blockbench project: ${file}`);
    }
    if (!Array.isArray(project.textures) || project.textures.length !== 1) {
        throw new Error(`Expected exactly one embedded texture: ${file}`);
    }
    if (!Array.isArray(project.elements) || project.elements.length !== 124) {
        throw new Error(`Expected 124 cubes in the MZ breech project: ${file}`);
    }
    return project;
}

const closed = readProject(closedPath);
const opened = readProject(openPath);
const texture = closed.textures[0];
const textureMatch = /^data:image\/png;base64,(.+)$/.exec(texture.source);
if (!textureMatch) {
    throw new Error("The MZ breech texture is not embedded as a PNG");
}
fs.writeFileSync(
    path.join(textureDirectory, "mz_cannon_breech.png"),
    Buffer.from(textureMatch[1], "base64")
);

const textureWidth = Number(texture.uv_width ?? closed.resolution.width);
const textureHeight = Number(texture.uv_height ?? closed.resolution.height);
const FRAME_COUNT = 13;
const radians = degrees => degrees * Math.PI / 180;
const cleanNumber = value => {
    const cleaned = Math.abs(value) < 1e-9 ? 0 : value;
    return Number(cleaned.toFixed(8)).toString();
};

// Blockbench cube and group Euler rotations use ZYX order: X, then Y, then Z.
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

function interpolateArray(left, right, amount) {
    return left.map((value, index) =>
        Number(value) + (Number(right[index]) - Number(value)) * amount
    );
}

function optionalVector(value) {
    return (value ?? [0, 0, 0]).map(Number);
}

function collectEntries(project) {
    const elements = new Map(project.elements.map((element, index) => [
        element.uuid, {element, index}
    ]));
    const groups = new Map(project.groups.map(group => [group.uuid, group]));
    const entries = [];

    function walk(children, groupPath, groupChain) {
        for (const child of children ?? []) {
            if (typeof child === "string") {
                const found = elements.get(child);
                if (!found) throw new Error(`Unknown element UUID: ${child}`);
                entries[found.index] = {
                    element: found.element,
                    groupPath,
                    groupChain
                };
                continue;
            }
            const group = groups.get(child.uuid);
            if (!group) throw new Error(`Unknown group UUID: ${child.uuid}`);
            const nextPath = `${groupPath}/${group.name}`;
            walk(child.children, nextPath, [...groupChain, group]);
        }
    }

    walk(project.outliner, "", []);
    if (entries.some(entry => !entry)) {
        throw new Error("Not every Blockbench cube is present in the outliner");
    }
    return entries;
}

const closedEntries = collectEntries(closed);
const openedEntries = collectEntries(opened);
for (let index = 0; index < closedEntries.length; index++) {
    if (closedEntries[index].groupPath !== openedEntries[index].groupPath) {
        throw new Error(`Outliner mismatch at cube ${index}`);
    }
}

function isOpening(entry) {
    return entry.groupPath.startsWith("/MZ/group/11/");
}

function isCo(entry) {
    return entry.groupPath === "/MZ/co";
}

const openingCount = closedEntries.filter(isOpening).length;
const coCount = closedEntries.filter(isCo).length;
if (openingCount !== 20 || coCount !== 4) {
    throw new Error(`Expected 20 opening cubes and 4 co cubes, found ${openingCount} and ${coCount}`);
}

function isRecoil(entry) {
    return entry.groupPath === "/MZ/group/group"
        || entry.groupPath.startsWith("/MZ/group/group/");
}

const recoilCount = closedEntries.filter(isRecoil).length;
if (recoilCount !== 78) {
    throw new Error(`Expected 78 recoil cubes in MZ/group/group, found ${recoilCount}`);
}

function interpolatedElement(index, amount) {
    const left = closedEntries[index].element;
    const right = openedEntries[index].element;
    // Group 11 is one rigid linkage. The open project contains the same cubes
    // after a 67.5-degree group rotation, but Blockbench has rebaked/reordered
    // their individual cube rotations. Keep the closed cubes intact here and
    // apply the one group rotation after their own transforms are baked.
    if (closedEntries[index].groupPath.startsWith("/MZ/group/11/")) {
        return {
            from: left.from.map(Number),
            to: left.to.map(Number),
            origin: optionalVector(left.origin),
            rotation: optionalVector(left.rotation),
            faces: left.faces
        };
    }
    return {
        from: interpolateArray(left.from, right.from, amount),
        to: interpolateArray(left.to, right.to, amount),
        origin: interpolateArray(
            optionalVector(left.origin), optionalVector(right.origin), amount
        ),
        rotation: interpolateArray(
            optionalVector(left.rotation), optionalVector(right.rotation), amount
        ),
        faces: amount < 0.5 ? left.faces : right.faces
    };
}

function elementVertices(element) {
    const [x0, y0, z0] = element.from.map(Number);
    const [x1, y1, z1] = element.to.map(Number);
    let vertices = [
        [x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
        [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]
    ];
    if (element.rotation.some(value => Math.abs(value) > 1e-9)) {
        vertices = vertices.map(vertex =>
            rotatePoint(vertex, element.origin, element.rotation)
        );
    }
    // In java_block projects, outliner groups are editing/selection containers.
    // Their transforms have already been baked into the child cubes. Applying a
    // group rotation again would throw MZ/group/11/Shell_casing21 far outside the
    // breech, so only each cube's own origin/rotation is exported here.
    return vertices;
}

function applyGroup11Rotation(vertices, index, amount, angle = 67.5) {
    const entry = closedEntries[index];
    if (!entry.groupPath.startsWith("/MZ/group/11/")) return vertices;
    const pivot = [1.9, 9.05, 29.15];
    return vertices.map(vertex =>
        rotatePoint(vertex, pivot, [0, 0, angle * amount])
    );
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

function writeObj(name, selectedIndices, amount) {
    const lines = [
        "# MZ breech mesh baked from Blockbench by Create: Fire Control",
        "mtllib common.mtl",
        "usemtl layer0",
        ""
    ];
    let nextIndex = 1;
    let triangleCount = 0;

    for (const index of selectedIndices) {
        const element = interpolatedElement(index, amount);
        const vertices = applyGroup11Rotation(
            elementVertices(element), index, amount
        );
        for (const [direction, indices] of Object.entries(faceVertices)) {
            const face = element.faces?.[direction];
            if (!face || face.texture === null || face.texture === undefined) continue;
            const uv = faceUvCorners(face, direction);
            for (const triangleIndices of [[0, 1, 2], [0, 2, 3]]) {
                for (const corner of triangleIndices) {
                    const position = vertices[indices[corner]].map(value => value / 16);
                    lines.push(`v ${position.map(cleanNumber).join(" ")}`);
                }
                for (const corner of triangleIndices) {
                    lines.push(`vt ${uv[corner].map(cleanNumber).join(" ")}`);
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
    return triangleCount;
}

function objModel(name) {
    return {
        parent: "minecraft:block/block",
        ambientocclusion: true,
        textures: {
            layer0: "create_fire_control:block/mz_cannon_breech",
            particle: "create_fire_control:block/mz_cannon_breech"
        },
        loader: "neoforge:obj",
        flip_v: true,
        automatic_culling: false,
        shade_quads: true,
        model: `create_fire_control:models/block/mz_cannon_breech/${name}.obj`
    };
}

function writeModel(name) {
    fs.writeFileSync(
        path.join(blockModelDirectory, `${name}.json`),
        `${JSON.stringify(objModel(name), null, 2)}\n`,
        "utf8"
    );
}

fs.writeFileSync(
    path.join(blockModelDirectory, "common.mtl"),
    "newmtl layer0\nmap_Kd #layer0\n",
    "utf8"
);

const fixedIndices = [];
const openingIndices = [];
const coIndices = [];
const recoilIndices = [];
const allIndices = [];
for (let index = 0; index < closedEntries.length; index++) {
    allIndices.push(index);
    const entry = closedEntries[index];
    if (isOpening(entry)) {
        openingIndices.push(index);
    } else if (isCo(entry)) {
        coIndices.push(index);
    } else if (isRecoil(entry)) {
        recoilIndices.push(index);
    } else {
        fixedIndices.push(index);
    }
}

console.log(`body: ${writeObj("body", fixedIndices, 0)} rendered triangles`);
writeModel("body");
console.log(`recoil: ${writeObj("recoil", recoilIndices, 0)} rendered triangles`);
writeModel("recoil");
for (let frame = 0; frame < FRAME_COUNT; frame++) {
    const amount = frame / (FRAME_COUNT - 1);
    for (const [prefix, indices] of [["frame", openingIndices], ["co_frame", coIndices]]) {
        const name = `${prefix}_${String(frame).padStart(2, "0")}`;
        console.log(`${name}: ${writeObj(name, indices, amount)} rendered triangles`);
        writeModel(name);
    }
}

console.log(`item: ${writeObj("item", allIndices, 0)} rendered triangles`);
const itemModel = {
    ...objModel("item"),
    display: {
        thirdperson_righthand: {
            rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.36, 0.36, 0.36]
        },
        thirdperson_lefthand: {
            rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.36, 0.36, 0.36]
        },
        firstperson_righthand: {
            rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.38, 0.38, 0.38]
        },
        firstperson_lefthand: {
            rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.38, 0.38, 0.38]
        },
        ground: {translation: [0, 2, 0], scale: [0.28, 0.28, 0.28]},
        gui: {rotation: [25, 135, 0], translation: [0, 0, 0], scale: [0.36, 0.36, 0.36]},
        fixed: {rotation: [0, 90, 0], scale: [0.34, 0.34, 0.34]}
    }
};
fs.writeFileSync(
    path.join(itemModelDirectory, "mz_cannon_breech.json"),
    `${JSON.stringify(itemModel, null, 2)}\n`,
    "utf8"
);

console.log(
    `Generated ${FRAME_COUNT} animated frames from ${openingCount} opening cubes, `
    + `${coCount} co cubes, and ${recoilCount} recoil cubes.`
);
