const fs = require("fs");
const path = require("path");

if (process.argv.length !== 3) {
    throw new Error("Usage: node convert_az_cannon_breech.js <AZ breech.bbmodel>");
}

const sourcePath = path.resolve(process.argv[2]);
const projectRoot = path.resolve(__dirname, "..");
const assetRoot = path.join(projectRoot, "src", "main", "resources", "assets", "create_fire_control");
const modelDir = path.join(assetRoot, "models", "block", "az_cannon_breech");
const itemDir = path.join(assetRoot, "models", "item");
const textureDir = path.join(assetRoot, "textures", "block");
for (const dir of [modelDir, itemDir, textureDir]) fs.mkdirSync(dir, {recursive: true});

const project = JSON.parse(fs.readFileSync(sourcePath, "utf8"));
if (project?.meta?.model_format !== "java_block") throw new Error("Expected java_block model");
if (project.elements?.length !== 141) throw new Error(`Expected 141 cubes, got ${project.elements?.length}`);
if (project.textures?.length !== 1) throw new Error("Expected one embedded texture");

const texture = project.textures[0];
const textureMatch = /^data:image\/png;base64,(.+)$/.exec(texture.source ?? "");
if (!textureMatch) throw new Error("AZ breech texture is not embedded");
fs.writeFileSync(path.join(textureDir, "az_cannon_breech.png"), Buffer.from(textureMatch[1], "base64"));
const textureWidth = Number(texture.uv_width ?? project.resolution.width);
const textureHeight = Number(texture.uv_height ?? project.resolution.height);

const elements = new Map(project.elements.map((element, index) => [element.uuid, {element, index}]));
const groups = new Map(project.groups.map(group => [group.uuid, group]));
const entries = [];
function walk(children, groupPath) {
    for (const child of children ?? []) {
        if (typeof child === "string") {
            const found = elements.get(child);
            if (!found) throw new Error(`Unknown element ${child}`);
            entries[found.index] = {element: found.element, groupPath};
            continue;
        }
        const group = groups.get(child.uuid);
        if (!group) throw new Error(`Unknown group ${child.uuid}`);
        walk(child.children, `${groupPath}/${group.name}`);
    }
}
walk(project.outliner, "");
if (entries.length !== project.elements.length || entries.some(entry => !entry)) {
    throw new Error("Not every cube is present in the outliner");
}

const isPath = (entry, prefix) =>
    entry.groupPath === prefix || entry.groupPath.startsWith(prefix + "/");
const recoilIndices = [];
const coIndices = [];
const openingIndices = [];
const fixedIndices = [];
const allIndices = entries.map((ignored, index) => index);
for (let index = 0; index < entries.length; index++) {
    const entry = entries[index];
    if (isPath(entry, "/group/group")) recoilIndices.push(index);
    else if (isPath(entry, "/co")) coIndices.push(index);
    else if (isPath(entry, "/group/11") || isPath(entry, "/f")) openingIndices.push(index);
    else fixedIndices.push(index);
}
if (recoilIndices.length !== 78 || coIndices.length !== 4
        || openingIndices.length !== 55 || fixedIndices.length !== 4) {
    throw new Error(`Unexpected groups: fixed=${fixedIndices.length}, recoil=${recoilIndices.length}, `
        + `co=${coIndices.length}, opening=${openingIndices.length}`);
}

const radians = value => value * Math.PI / 180;
const optionalVector = value => (value ?? [0, 0, 0]).map(Number);
const clean = value => Number((Math.abs(value) < 1e-9 ? 0 : value).toFixed(8)).toString();
function rotateVector(vector, rotation) {
    let [x, y, z] = vector;
    const [rx, ry, rz] = rotation.map(radians);
    let c = Math.cos(rx), s = Math.sin(rx);
    [y, z] = [y * c - z * s, y * s + z * c];
    c = Math.cos(ry); s = Math.sin(ry);
    [x, z] = [x * c + z * s, -x * s + z * c];
    c = Math.cos(rz); s = Math.sin(rz);
    [x, y] = [x * c - y * s, x * s + y * c];
    return [x, y, z];
}
function rotatePoint(point, origin, rotation) {
    const local = point.map((value, axis) => value - origin[axis]);
    return rotateVector(local, rotation).map((value, axis) => value + origin[axis]);
}
function elementVertices(element) {
    const [x0, y0, z0] = element.from.map(Number);
    const [x1, y1, z1] = element.to.map(Number);
    let vertices = [
        [x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
        [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]
    ];
    const rotation = optionalVector(element.rotation);
    if (rotation.some(value => Math.abs(value) > 1e-9)) {
        const origin = optionalVector(element.origin);
        vertices = vertices.map(vertex => rotatePoint(vertex, origin, rotation));
    }
    return vertices;
}
const fEntries = entries.filter(entry => isPath(entry, "/f"));
const fPivot = optionalVector(fEntries[0]?.element.origin);
if (fEntries.length !== 15 || fEntries.some(entry =>
        optionalVector(entry.element.origin).some((value, axis) =>
            Math.abs(value - fPivot[axis]) > 1e-9))) {
    throw new Error("The f components do not share one rotation axis");
}
const group11Entries = entries.filter(entry => isPath(entry, "/group/11"));
const group11Pivot = optionalVector(group11Entries[0]?.element.origin);
if (group11Entries.length !== 40 || group11Entries.some(entry =>
        optionalVector(entry.element.origin).some((value, axis) =>
            Math.abs(value - group11Pivot[axis]) > 1e-9))) {
    throw new Error("The group/11 components do not share one rotation axis");
}
function animatedVertices(index, amount) {
    const entry = entries[index];
    let vertices = elementVertices(entry.element);
    if (isPath(entry, "/co")) {
        vertices = vertices.map(([x, y, z]) => [x - 8 * amount, y, z]);
    } else if (isPath(entry, "/group/11")) {
        vertices = vertices.map(vertex =>
            rotatePoint(vertex, group11Pivot, [-30 * amount, 0, 0]));
    } else if (isPath(entry, "/f")) {
        // Rotate around f's own Blockbench pivot and positive local Z axis.
        vertices = vertices.map(vertex => rotatePoint(vertex, fPivot, [0, 0, 90 * amount]));
    }
    return vertices;
}

const faceVertices = {
    north: [0, 3, 2, 1], south: [4, 5, 6, 7], west: [0, 4, 7, 3],
    east: [1, 2, 6, 5], down: [0, 1, 5, 4], up: [3, 7, 6, 2]
};
function faceUvCorners(face, direction) {
    const [u1, v1, u2, v2] = face.uv.map(Number);
    const raw = {
        north: [[u1, v2], [u1, v1], [u2, v1], [u2, v2]],
        south: [[u1, v2], [u2, v2], [u2, v1], [u1, v1]],
        west: [[u1, v2], [u2, v2], [u2, v1], [u1, v1]],
        east: [[u1, v2], [u1, v1], [u2, v1], [u2, v2]],
        down: [[u1, v1], [u2, v1], [u2, v2], [u1, v2]],
        up: [[u1, v1], [u1, v2], [u2, v2], [u2, v1]]
    }[direction].map(([u, v]) => [u / textureWidth, 1 - v / textureHeight]);
    const turns = (((Number(face.rotation ?? 0) / 90) % 4) + 4) % 4;
    return turns ? raw.map((ignored, index) => raw[(index + turns) % 4]) : raw;
}

function writeObj(name, indices, amount) {
    const lines = ["# AZ breech mesh", "mtllib common.mtl", "usemtl layer0", ""];
    let nextIndex = 1;
    let triangles = 0;
    for (const index of indices) {
        const element = entries[index].element;
        const vertices = animatedVertices(index, amount);
        for (const [direction, corners] of Object.entries(faceVertices)) {
            const face = element.faces?.[direction];
            if (!face || face.texture === null || face.texture === undefined) continue;
            const uv = faceUvCorners(face, direction);
            for (const triangle of [[0, 1, 2], [0, 2, 3]]) {
                for (const corner of triangle) {
                    lines.push(`v ${vertices[corners[corner]].map(value => clean(value / 16)).join(" ")}`);
                }
                for (const corner of triangle) lines.push(`vt ${uv[corner].map(clean).join(" ")}`);
                lines.push(`f ${nextIndex}/${nextIndex} ${nextIndex + 1}/${nextIndex + 1} `
                    + `${nextIndex + 2}/${nextIndex + 2}`);
                nextIndex += 3;
                triangles++;
            }
        }
    }
    fs.writeFileSync(path.join(modelDir, `${name}.obj`), lines.join("\n") + "\n", "utf8");
    return triangles;
}
function objModel(name) {
    return {
        parent: "minecraft:block/block", ambientocclusion: true,
        textures: {layer0: "create_fire_control:block/az_cannon_breech",
            particle: "create_fire_control:block/az_cannon_breech"},
        loader: "neoforge:obj", flip_v: true, automatic_culling: false,
        shade_quads: true,
        model: `create_fire_control:models/block/az_cannon_breech/${name}.obj`
    };
}
function writeModel(name) {
    fs.writeFileSync(path.join(modelDir, `${name}.json`), JSON.stringify(objModel(name), null, 2) + "\n");
}
fs.writeFileSync(path.join(modelDir, "common.mtl"), "newmtl layer0\nmap_Kd #layer0\n");
for (const [name, indices] of [["body", fixedIndices], ["recoil", recoilIndices]]) {
    console.log(`${name}: ${writeObj(name, indices, 0)} triangles`);
    writeModel(name);
}
const FRAME_COUNT = 13;
for (let frame = 0; frame < FRAME_COUNT; frame++) {
    const suffix = String(frame).padStart(2, "0");
    const amount = frame / (FRAME_COUNT - 1);
    for (const [prefix, indices] of [["co_frame", coIndices], ["opening_frame", openingIndices]]) {
        const name = `${prefix}_${suffix}`;
        console.log(`${name}: ${writeObj(name, indices, amount)} triangles`);
        writeModel(name);
    }
}
writeObj("item", allIndices, 0);
const itemModel = {
    ...objModel("item"),
    display: {
        thirdperson_righthand: {rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.36, 0.36, 0.36]},
        thirdperson_lefthand: {rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.36, 0.36, 0.36]},
        firstperson_righthand: {rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.38, 0.38, 0.38]},
        firstperson_lefthand: {rotation: [0, 45, 0], translation: [0, 1.5, 0], scale: [0.38, 0.38, 0.38]},
        ground: {translation: [0, 2, 0], scale: [0.28, 0.28, 0.28]},
        gui: {rotation: [25, 135, 0], scale: [0.36, 0.36, 0.36]},
        fixed: {rotation: [0, 90, 0], scale: [0.34, 0.34, 0.34]}
    }
};
fs.writeFileSync(path.join(itemDir, "az_cannon_breech.json"), JSON.stringify(itemModel, null, 2) + "\n");
console.log(`Generated AZ breech: fixed=${fixedIndices.length}, recoil=${recoilIndices.length}, `
    + `co=${coIndices.length}, opening=${openingIndices.length}`);
