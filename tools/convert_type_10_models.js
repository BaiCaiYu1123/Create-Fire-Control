// Usage: node tools/convert_type_10_models.js <尾仓.bbmodel> <10式off.bbmodel>
// Geometry conventions match bbmodel_to_geckolib.py and the existing OBJ breeches.
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const assetRoot = path.resolve(__dirname, '../src/main/resources/assets/create_fire_control');
function write(file, data) {
    const dest = path.join(assetRoot, file);
    fs.mkdirSync(path.dirname(dest), {recursive:true});
    fs.writeFileSync(dest, data);
}
function json(file, data) { write(file, JSON.stringify(data, null, 2) + '\n'); }
function texture(model, name) {
    assert.equal(model.textures.length, 1);
    const match = /^data:image\/png;base64,(.+)$/.exec(model.textures[0].source);
    assert(match, 'Expected embedded PNG');
    write(`textures/block/${name}.png`, Buffer.from(match[1], 'base64'));
}
const negXY = v => [-v[0], -v[1], v[2]];
const nonzero = v => v.some(x=>Math.abs(x)>1e-7);
function loader(file) {
    const source = JSON.parse(fs.readFileSync(file, 'utf8'));
    texture(source, 'type_10_loader');
    const groups = new Map(source.groups.map(g=>[g.uuid,g]));
    const elements = new Map(source.elements.map(e=>[e.uuid,e]));
    const bones=[];
    function walk(nodes, parent) {
        for(const node of nodes) {
            assert.equal(typeof node, 'object', 'Loader cubes must belong to groups');
            const g = groups.get(node.uuid);
            const bone={name:g.name};
            // Bone positions reflect X only. Bone rotations reflect X and Y.
            bone.pivot=[-g.origin[0], g.origin[1], g.origin[2]];
            if(parent) bone.parent=parent;
            if(nonzero(g.rotation??[0,0,0])) bone.rotation=negXY(g.rotation);
            const cubes=[];
            const children=[];
            for(const child of node.children??[]) {
                if(typeof child!=='string') { children.push(child); continue; }
                const e=elements.get(child), size=e.to.map((x,i)=>x-e.from[i]);
                const cube={origin:[-e.to[0], e.from[1],e.from[2]],size};
                if(nonzero(e.rotation??[0,0,0])) {
                    cube.pivot=[-e.origin[0],e.origin[1],e.origin[2]];
                    cube.rotation=negXY(e.rotation);
                }
                if(e.inflate) cube.inflate=e.inflate;
                if(e.box_uv) {
                    cube.uv=e.uv_offset;
                    if(e.mirror_uv) cube.mirror=true;
                } else {
                    cube.uv={};
                    for(const [name,f] of Object.entries(e.faces??{})) {
                        if(f.texture===null||f.texture===undefined) continue;
                        assert(!f.rotation,'Unexpected rotated loader UV');
                        let [u,v,u2,v2]=f.uv, w=u2-u, h=v2-v;
                        if(name==='up'||name==='down') {u+=w;v+=h;w=-w;h=-h;}
                        cube.uv[name]={uv:[u,v],uv_size:[w,h]};
                    }
                }
                cubes.push(cube);
            }
            if(cubes.length) bone.cubes=cubes;
            bones.push(bone);
            walk(children,g.name);
        }
    }
    walk(source.outliner,null);
    assert.equal(bones.reduce((n,b)=>n+(b.cubes?.length??0),0),410);
    assert.equal(new Set(bones.map(b=>b.name)).size,bones.length);
    json('geo/type_10_loader.geo.json', {format_version:'1.12.0', 'minecraft:geometry': [{
        description:{identifier:'geometry.type_10_loader',texture_width:source.resolution.width,
            texture_height:source.resolution.height,visible_bounds_width:8,visible_bounds_height:5,
            visible_bounds_offset:[0,1,0]},bones
    }]});
    const sourceAnim=source.animations[0];
    assert.equal(sourceAnim.length,4.4);
    const base={loop:'hold_on_last_frame',animation_length:4.4,
        sound_effects:{'0':{effect:'create_fire_control:type_10_loader'}},bones:{}};
    for(const [uuid,animator] of Object.entries(sourceAnim.animators)) {
        if(!groups.has(uuid)) continue;
        const channels={};
        for(const frame of animator.keyframes??[]) {
            if(!['position','rotation','scale'].includes(frame.channel)) continue;
            assert.equal(frame.interpolation,'linear');
            const dp=frame.data_points[0];
            let v=[dp.x,dp.y,dp.z].map(Number);
            if(frame.channel==='rotation') v=negXY(v);
            (channels[frame.channel]??={})[String(frame.time)]=v;
        }
        for(const [channel,frames] of Object.entries(channels)) {
            // GeckoLib 4.9 reads JSON order directly. Blockbench stores keyframes
            // in editing order, while JS moves integer keys such as "3" first.
            // Sort numerically and use "3.0" so JSON keeps chronological order.
            channels[channel]=Object.fromEntries(Object.entries(frames)
                .sort(([a],[b])=>Number(a)-Number(b))
                .map(([time,vector])=>[Number.isInteger(Number(time)) ? Number(time).toFixed(1) : time, vector]));
            const times=Object.keys(channels[channel]).map(Number);
            assert(times.every((time,index)=>index===0||time>=times[index-1]),'Keyframe timestamps must increase');
        }
        if(Object.keys(channels).length) base.bones[groups.get(uuid).name]=channels;
    }
    const ammo=bones.filter(b=>b.parent==='apfsds').sort((a,b)=>
        Number(a.name.match(/\d+$/)[0])-Number(b.name.match(/\d+$/)[0]));
    assert.equal(ammo.length,13);
    for(let i=0;i<13;i++) {
        assert.equal(Number(ammo[i].name.match(/\d+$/)[0]),i+1);
    }
    // Inventory removal is independent of the authored animation. Keep its original
    // targets and keyframes, including APFSDS-1; do not retarget a stocked round.
    assert.equal(sourceAnim.name,'animation');
    const animations={[sourceAnim.name]:base};
    json('animations/type_10_loader.animation.json',{format_version:'1.8.0',animations});
    console.log(`Loader: ${bones.length} bones, 410 cubes, original animation unchanged`);
}

function breech(file) {
    const source=JSON.parse(fs.readFileSync(file,'utf8'));
    assert.equal(source.elements.length,36);
    texture(source,'type_10_cannon_breech');
    const movingIds=new Set();
    const groups=new Map(source.groups.map(g=>[g.uuid,g]));
    function walk(nodes, moving=false) {
        for(const node of nodes) {
            if(typeof node==='string') { if(moving) movingIds.add(node); }
            else {
                const g=groups.get(node.uuid);
                assert(!nonzero(g.rotation??[0,0,0]),'Unexpected breech group rotation');
                walk(node.children,moving||g.name==='group');
            }
        }
    }
    walk(source.outliner);
    assert(movingIds.size>0&&movingIds.size<36);
    const modelDir='models/block/type_10_cannon_breech';
    const faceVertices={north:[0,3,2,1],south:[4,5,6,7],west:[0,4,7,3],east:[1,2,6,5],down:[0,1,5,4],up:[3,7,6,2]};
    const textureWidth=source.resolution.width,textureHeight=source.resolution.height;
    // Reuse the established breech OBJ vertex/UV winding and independent cube pivots.
    const old=fs.readFileSync(path.join(__dirname,'convert_az_cannon_breech.js'),'utf8');
    const helpers=old.slice(old.indexOf('const radians ='),old.indexOf('const fEntries ='));
    const uvHelper=old.slice(old.indexOf('function faceUvCorners'),old.indexOf('function writeObj'));
    const makeVertices=new Function(helpers+'return elementVertices;')();
    const faceUv=new Function('textureWidth','textureHeight',uvHelper+'return faceUvCorners;')(textureWidth,textureHeight);
    function obj(name,elements) {
        const lines=['# Type 10 breech','mtllib common.mtl','usemtl layer0'];let next=1;
        for(const e of elements) {
            const vertices=makeVertices(e);
            for(const [direction,corners] of Object.entries(faceVertices)) {
                const face=e.faces?.[direction];
                if(face?.texture==null) continue;
                const uv=faceUv(face,direction);
                for(const triangle of [[0,1,2],[0,2,3]]) {
                    for(const c of triangle) lines.push('v '+vertices[corners[c]].map(x=>Number((x/16).toFixed(8))).join(' '));
                    for(const c of triangle) lines.push('vt '+uv[c].join(' '));
                    lines.push(`f ${next}/${next} ${next+1}/${next+1} ${next+2}/${next+2}`);next+=3;
                }
            }
        }
        write(`${modelDir}/${name}.obj`,lines.join('\n')+'\n');
        return {parent:'minecraft:block/block',ambientocclusion:true,
            textures:{layer0:'create_fire_control:block/type_10_cannon_breech',particle:'create_fire_control:block/type_10_cannon_breech'},
            loader:'neoforge:obj',flip_v:true,automatic_culling:false,shade_quads:true,
            model:`create_fire_control:${modelDir}/${name}.obj`};
    }
    write(`${modelDir}/common.mtl`,'newmtl layer0\nmap_Kd #layer0\n');
    json(`${modelDir}/body.json`,obj('body',source.elements.filter(e=>!movingIds.has(e.uuid))));
    json(`${modelDir}/sliding_block.json`,obj('sliding_block',source.elements.filter(e=>movingIds.has(e.uuid))));
    const item=obj('item',source.elements);
    item.display=source.display??{gui:{rotation:[25,135,0],scale:[0.7,0.7,0.7]}};
    json('models/item/type_10_cannon_breech.json',item);
    // Both body and wedge render in the BE renderer so both follow the recoil transform.
    json('models/block/type_10_cannon_breech.json',{parent:'minecraft:block/block',
        textures:{particle:'create_fire_control:block/type_10_cannon_breech'},elements:[]});
    json('blockstates/type_10_cannon_breech.json',{variants:{'':{model:'create_fire_control:block/type_10_cannon_breech'}}});
    console.log(`Breech: ${36-movingIds.size} body cubes, ${movingIds.size} sliding group cubes`);
}
assert.equal(process.argv.length,4,'Expected loader and breech bbmodel paths');
loader(process.argv[2]);
breech(process.argv[3]);
