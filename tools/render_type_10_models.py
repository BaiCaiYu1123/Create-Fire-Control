"""Deterministic textured model QA and an empty-rack inventory icon (no new artwork)."""
import sys, json, math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/create_fire_control'
OUT = ROOT / 'build/type-10-preview'
OUT.mkdir(parents=True, exist_ok=True)
FACE = {'north':[0,3,2,1], 'south':[4,5,6,7], 'west':[0,4,7,3],
        'east':[1,2,6,5], 'down':[0,1,5,4], 'up':[3,7,6,2]}

def rotate(points, origin, degrees):
    p = np.array(points, dtype=float) - origin
    for axis, angle in enumerate(degrees):
        c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        matrix = (np.array([[1,0,0],[0,c,-s],[0,s,c]]) if axis == 0 else
                  np.array([[c,0,s],[0,1,0],[-s,0,c]]) if axis == 1 else
                  np.array([[c,-s,0],[s,c,0],[0,0,1]]))
        p = p @ matrix.T
    return p + origin

def mesh(source, count=0, opened=False):
    elements = {e['uuid']:e for e in source['elements']}
    groups = {g['uuid']:g for g in source['groups']}
    triangles = []
    def walk(nodes, parents):
        for node in nodes:
            if isinstance(node, dict):
                group = groups[node['uuid']]
                if group['name'].startswith(('APFSDS','APFSD-')):
                    number = int(''.join(c for c in group['name'] if c.isdigit()))
                    if number > count: continue
                walk(node.get('children', []), parents+[group])
                continue
            e = elements[node]
            x0,y0,z0=e['from'];x1,y1,z1=e['to']
            p=np.array([[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],
                        [x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]], dtype=float)
            p=rotate(p,e.get('origin',[0,0,0]),e.get('rotation',[0,0,0]))
            for group in reversed(parents):
                p=rotate(p,group.get('origin',[0,0,0]),group.get('rotation',[0,0,0]))
            if opened and any(g['name']=='group' for g in parents): p[:,1]-=5
            for name, face in e.get('faces',{}).items():
                if face.get('texture') is None: continue
                u1,v1,u2,v2=face['uv']
                uv={'north':[(u1,v2),(u1,v1),(u2,v1),(u2,v2)],
                    'south':[(u1,v2),(u2,v2),(u2,v1),(u1,v1)],
                    'west':[(u1,v2),(u2,v2),(u2,v1),(u1,v1)],
                    'east':[(u1,v2),(u1,v1),(u2,v1),(u2,v2)],
                    'down':[(u1,v1),(u2,v1),(u2,v2),(u1,v2)],
                    'up':[(u1,v1),(u1,v2),(u2,v2),(u2,v1)]}[name]
                turns=int(face.get('rotation',0)/90)%4
                uv=uv[turns:]+uv[:turns]
                uv=np.array(uv)/[source['resolution']['width'],source['resolution']['height']]
                points=p[FACE[name]]
                for ids in [[0,1,2],[0,2,3]]: triangles.append((points[ids],uv[ids]))
    walk(source['outliner'],[])
    return triangles

def render(triangles, texture, size=480, eye=(1,.8,-1.4), extent=None):
    eye=np.array(eye,dtype=float);eye/=np.linalg.norm(eye)
    right=np.cross([0,1,0],eye);right/=np.linalg.norm(right)
    up=np.cross(eye,right); basis=np.array([right,up,eye]).T
    all_points=np.concatenate([p for p,uv in triangles])@basis
    center=(all_points[:,:2].min(axis=0)+all_points[:,:2].max(axis=0))/2
    scale=size*.86/(extent or np.ptp(all_points[:,:2],axis=0).max())
    canvas=np.zeros((size,size,4),dtype=np.uint8)
    depths=np.full((size,size),-np.inf)
    for points, texuv in triangles:
        q=points@basis;q[:,:2]=(q[:,:2]-center)*[scale,-scale]+size/2
        x0=max(0,math.floor(q[:,0].min()));x1=min(size-1,math.ceil(q[:,0].max()))
        y0=max(0,math.floor(q[:,1].min()));y1=min(size-1,math.ceil(q[:,1].max()))
        den=(q[1,1]-q[2,1])*(q[0,0]-q[2,0])+(q[2,0]-q[1,0])*(q[0,1]-q[2,1])
        if x0>x1 or y0>y1 or abs(den)<1e-8: continue
        x,y=np.meshgrid(np.arange(x0,x1+1)+.5,np.arange(y0,y1+1)+.5)
        a=((q[1,1]-q[2,1])*(x-q[2,0])+(q[2,0]-q[1,0])*(y-q[2,1]))/den
        b=((q[2,1]-q[0,1])*(x-q[2,0])+(q[0,0]-q[2,0])*(y-q[2,1]))/den;c=1-a-b
        z=a*q[0,2]+b*q[1,2]+c*q[2,2];sub=depths[y0:y1+1,x0:x1+1]
        mask=(a>=-1e-7)&(b>=-1e-7)&(c>=-1e-7)&(z>sub)
        normal=np.cross(points[1]-points[0],points[2]-points[0]);length=np.linalg.norm(normal)
        if length<1e-8:continue
        shade=.60+.40*abs(np.dot(normal/length,[-.4,.8,-.4]))
        uv=a[:,:,None]*texuv[0]+b[:,:,None]*texuv[1]+c[:,:,None]*texuv[2]
        tx=np.clip((uv[:,:,0]*texture.shape[1]).astype(int),0,texture.shape[1]-1)
        ty=np.clip((uv[:,:,1]*texture.shape[0]).astype(int),0,texture.shape[0]-1)
        colors=texture[ty,tx].copy();colors[:,:,:3]=(colors[:,:,:3]*shade).astype(np.uint8)
        mask &= colors[:,:,3]>0
        canvas[y0:y1+1,x0:x1+1][mask]=colors[mask];sub[mask]=z[mask]
    return Image.fromarray(canvas)

loader=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
breech=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8'))
loader_tex=np.array(Image.open(ASSETS/'textures/block/type_10_loader.png').convert('RGBA'))
breech_tex=np.array(Image.open(ASSETS/'textures/block/type_10_cannon_breech.png').convert('RGBA'))
icon=render(mesh(loader),loader_tex,size=512)
(ASSETS/'textures/item').mkdir(exist_ok=True)
icon.resize((128,128),Image.Resampling.LANCZOS).save(ASSETS/'textures/item/type_10_loader.png')
(ASSETS/'models/item/type_10_loader.json').write_text(json.dumps({
    'parent':'minecraft:item/generated','textures':{'layer0':'create_fire_control:item/type_10_loader'}
},indent=2)+'\n')
preview=Image.new('RGB',(1440,1050),(28,32,38));draw=ImageDraw.Draw(preview)
for i,count in enumerate([0,1,13]):
    tile=render(mesh(loader,count),loader_tex)
    preview.paste(tile,(480*i,35),tile)
    draw.text((480*i+20,18),f'Type 10 bustle rack: {count}/13 rounds',fill='white')
for i,opened in enumerate([False,True]):
    tile=render(mesh(breech,opened=opened),breech_tex,extent=30)
    preview.paste(tile,(240+480*i,550),tile)
    draw.text((260+480*i,530),'Breech: '+('open, group Y -5px' if opened else 'closed'),fill='white')
preview.save(OUT/'models.png')
print(OUT/'models.png')
