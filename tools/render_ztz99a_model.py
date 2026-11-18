"""Deterministic textured orthographic QA preview of exported OBJ and shaft geometry."""
import pathlib, math, json
import numpy as np
from PIL import Image, ImageDraw
root=pathlib.Path('src/main/resources/assets/create_fire_control')
verts=[];uvs=[];faces=[]
for line in (root/'models/block/ztz99a_diesel_engine/item.obj').read_text().splitlines():
    p=line.split()
    if not p: continue
    if p[0]=='v': verts.append([float(v)*16 for v in p[1:]])
    if p[0]=='vt': uvs.append([float(v) for v in p[1:]])
    if p[0]=='f': faces.append([(int(t.split('/')[0])-1,int(t.split('/')[1])-1) for t in p[1:]])
# Inventory OBJ is centered for Minecraft's item renderer. Restore block coordinates for shaft QA.
manifest=json.loads((root/'ztz99a/geometry_manifest.json').read_text())
bounds=np.array(manifest['bounds'])
inventory_translation=np.array([8,8,8])-bounds.mean(axis=1)
verts=(np.array(verts)-inventory_translation).tolist()
texture=np.array(Image.open(root/'textures/block/ztz99a_diesel_engine.png').convert('RGB'))
eye=np.array([-1.,.80,1.]);eye/=np.linalg.norm(eye)
right=np.cross(np.array([0.,1.,0.]),eye);right/=np.linalg.norm(right)
up=np.cross(eye,right)
size=850; canvas=np.full((size,size,3),[24,30,35],dtype=np.uint8); depths=np.full((size,size),-np.inf)
vertices=np.array(verts); projected=np.column_stack([vertices@right,vertices@up,vertices@eye])
scale=10.;center=(projected[:,:2].min(axis=0)+projected[:,:2].max(axis=0))/2
def draw_triangle(points, texuv=None, color=None):
    p=np.array(points);q=np.column_stack([p@right,p@up,p@eye]);q[:,:2]=(q[:,:2]-center)*[scale,-scale]+[size/2,size*.49]
    minx,maxx=max(0,int(q[:,0].min())),min(size-1,int(math.ceil(q[:,0].max())))
    miny,maxy=max(0,int(q[:,1].min())),min(size-1,int(math.ceil(q[:,1].max())))
    if minx>maxx or miny>maxy: return
    den=(q[1,1]-q[2,1])*(q[0,0]-q[2,0])+(q[2,0]-q[1,0])*(q[0,1]-q[2,1])
    if abs(den)<1e-8:return
    x,y=np.meshgrid(np.arange(minx,maxx+1)+.5,np.arange(miny,maxy+1)+.5)
    a=((q[1,1]-q[2,1])*(x-q[2,0])+(q[2,0]-q[1,0])*(y-q[2,1]))/den
    b=((q[2,1]-q[0,1])*(x-q[2,0])+(q[0,0]-q[2,0])*(y-q[2,1]))/den;c=1-a-b
    z=a*q[0,2]+b*q[1,2]+c*q[2,2];sub=depths[miny:maxy+1,minx:maxx+1]
    mask=(a>=-1e-7)&(b>=-1e-7)&(c>=-1e-7)&(z>sub)
    normal=np.cross(p[1]-p[0],p[2]-p[0]);norm=np.linalg.norm(normal)
    if norm<1e-8:return
    shade=.65+.35*abs(float(np.dot(normal/norm,np.array([-.4,.8,.4]))))
    if color is None:
        t=np.array(texuv);u=a*t[0,0]+b*t[1,0]+c*t[2,0];v=a*t[0,1]+b*t[1,1]+c*t[2,1]
        tx=np.clip((u*texture.shape[1]).astype(int),0,texture.shape[1]-1)
        ty=np.clip(((1-v)*texture.shape[0]).astype(int),0,texture.shape[0]-1)
        colors=(texture[ty,tx]*shade).astype(np.uint8)
    else: colors=np.broadcast_to((np.array(color)*shade).astype(np.uint8),(*a.shape,3))
    canvas[miny:maxy+1,minx:maxx+1][mask]=colors[mask];sub[mask]=z[mask]
for face in faces:
    draw_triangle([verts[v] for v,t in face],[uvs[t] for v,t in face])
for x0,x1 in [(-16,-8),(24,32)]:
    box=np.array([[x,y,z] for x in [x0,x1] for y in [6,10] for z in [6,10]])
    for quad in [(0,1,3,2),(4,6,7,5),(0,4,5,1),(2,3,7,6),(0,2,6,4),(1,5,7,3)]:
        for ids in [(quad[0],quad[1],quad[2]),(quad[0],quad[2],quad[3])]:
            draw_triangle(box[list(ids)],color=[175,164,126])
image=Image.fromarray(canvas);draw=ImageDraw.Draw(image)
draw.text((30,25),'150HB original geometry + Create half shafts (QA preview)',fill='white')
draw.text((30,800),'Model: scale 1.0 | transmission pivot aligned to Y=8, Z=8 | 18 cells',fill='white')
path=pathlib.Path('reference/ztz99a/model_preview.png');image.save(path);print(path.resolve())
