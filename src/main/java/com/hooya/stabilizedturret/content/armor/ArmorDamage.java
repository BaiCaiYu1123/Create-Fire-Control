package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Exact persistent bores, with an editable solid once the player applies a mesh edit. */
public final class ArmorDamage {
    public static final String TAG="FireControlArmorBores";
    public static final String EDITED_TAG="FireControlArmorEditedMesh";
    private List<ArmorHole> holes=List.of();
    private EditableArmorMesh edited;
    private ArmorMesh clientPreview;
    private int stamp;
    private Polyhedron cachedBase;
    private ArmorMesh mesh;
    private List<AABB> boxes;
    private VoxelShape shape;
    private EditableArmorMesh editableCache;
    public List<ArmorHole> holes(){return holes;}
    public boolean damaged(){return edited!=null||!holes.isEmpty();}
    public int stamp(){return stamp;}

    public boolean clearHoles() {
        if (holes.isEmpty()) return false;
        holes = List.of();
        invalidate();
        return true;
    }

    public EditableArmorMesh editable(Polyhedron base){return editable(base,com.caca.copypoly.api.PolyShape.fromGeometry(base));}
    public EditableArmorMesh editable(Polyhedron base,com.caca.copypoly.api.PolyShape document){
        mesh(base);if(editableCache==null){boolean legacy=edited!=null&&edited.kinds().contains(-1);
            editableCache=edited!=null&&holes.isEmpty()&&!legacy?edited:EditableArmorMesh.from(mesh,edited==null||legacy?EditableArmorMesh.reference(document):edited);}
        return editableCache;
    }
    public void install(EditableArmorMesh value){value.validate();edited=value;holes=List.of();invalidate();}
    public void setClientPreview(ArmorMesh value){clientPreview=value;}
    public ArmorMesh renderMesh(Polyhedron base){return clientPreview==null?mesh(base):clientPreview;}
    public boolean add(ArmorHole hole) {
        if(holes.stream().anyMatch(existing->existing.covers(hole)))return false;
        var oldBase=cachedBase;var oldMesh=mesh;var next=new ArrayList<>(holes);next.add(hole);holes=List.copyOf(next);invalidate();
        if(oldBase!=null&&oldMesh!=null){cachedBase=oldBase;mesh=edited!=null||oldBase.closed()?oldMesh.subtract(hole):oldMesh;}return true;
    }
    /** A broad-phase neighbor must lose actual material before it receives damage or an update. */
    public boolean perforate(Polyhedron base,ArmorHole hole){
        if(holes.stream().anyMatch(existing->existing.covers(hole))||edited==null&&!base.closed())return false;
        var previous=mesh(base);var carved=previous.subtract(hole);
        if(previous.volume()-carved.volume()<=1e-12)return false;
        var next=new ArrayList<>(holes);next.add(hole);holes=List.copyOf(next);invalidate();
        cachedBase=base;mesh=carved;return true;
    }
    public void invalidate(){cachedBase=null;mesh=null;boxes=null;shape=null;editableCache=null;stamp++;}
    ArmorMesh meshWithoutHoles(Polyhedron base) { return edited==null?ArmorMesh.of(base):edited.mesh(); }
    EditableArmorMesh controlsWithoutHoles(com.caca.copypoly.api.PolyShape reference) {
        return edited==null?EditableArmorMesh.reference(reference):edited;
    }

    public ArmorMesh mesh(Polyhedron base) {
        if(cachedBase!=base||mesh==null){mesh=meshWithoutHoles(base);if(edited!=null||base.closed())for(var hole:holes)mesh=mesh.subtract(hole);cachedBase=base;boxes=null;shape=null;editableCache=null;}
        return mesh;
    }
    public double volume(Polyhedron base){return mesh(base).volume();}
    public List<AABB> boxes(Polyhedron base) {
        mesh(base);
        if(boxes==null){
            if(edited==null&&holes.stream().allMatch(h->h.axisAlignedBox()!=null)){
                var result=base.collisionBoxes().stream().map(b->new AABB(b.x0()/16,b.y0()/16,b.z0()/16,b.x1()/16,b.y1()/16,b.z1()/16)).toList();
                for(var hole:holes){var next=new ArrayList<AABB>();for(var box:result)subtractBox(box,hole.axisAlignedBox(),next);result=next;}
                boxes=result.size()<512&&coordinateLimit(result)?List.copyOf(result):ArmorMeshContacts.build(mesh);
            }else boxes=ArmorMeshContacts.build(mesh);
        }
        return boxes;
    }
    private static boolean coordinateLimit(List<AABB> boxes){var x=new HashSet<Double>();var y=new HashSet<Double>();var z=new HashSet<Double>();for(var b:boxes){x.add(b.minX);x.add(b.maxX);y.add(b.minY);y.add(b.maxY);z.add(b.minZ);z.add(b.maxZ);}return x.size()<=65&&y.size()<=65&&z.size()<=65;}
    private static void subtractBox(AABB b,AABB hole,List<AABB> out){
        if(!b.intersects(hole)){out.add(b);return;}var i=b.intersect(hole);
        addBox(out,b.minX,b.minY,b.minZ,i.minX,b.maxY,b.maxZ);addBox(out,i.maxX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);
        addBox(out,i.minX,b.minY,b.minZ,i.maxX,i.minY,b.maxZ);addBox(out,i.minX,i.maxY,b.minZ,i.maxX,b.maxY,b.maxZ);
        addBox(out,i.minX,i.minY,b.minZ,i.maxX,i.maxY,i.minZ);addBox(out,i.minX,i.minY,i.maxZ,i.maxX,i.maxY,b.maxZ);
    }
    private static void addBox(List<AABB> boxes,double x0,double y0,double z0,double x1,double y1,double z1){if(x1-x0>1e-9&&y1-y0>1e-9&&z1-z0>1e-9)boxes.add(new AABB(x0,y0,z0,x1,y1,z1));}
    public VoxelShape shape(Polyhedron base) {
        var geometry=mesh(base);
        if(shape==null)shape=new ArmorVoxelShape(boxes(base),geometry);
        return shape;
    }
    public void transform(com.simibubi.create.content.contraptions.StructureTransform transform){
        holes=holes.stream().map(h->h.transform(transform)).toList();
        if(edited!=null){var o=transform.applyWithoutOffset(Vec3.ZERO);var x=transform.applyWithoutOffset(new Vec3(1,0,0)).subtract(o);var y=transform.applyWithoutOffset(new Vec3(0,1,0)).subtract(o);var z=transform.applyWithoutOffset(new Vec3(0,0,1)).subtract(o);
            edited=edited.transform(transform::applyWithoutOffset,x.cross(y).dot(z)<0);}
        invalidate();
    }
    public void write(CompoundTag tag) {
        var list=new ListTag();for(var hole:holes){var row=new ListTag();for(var p:List.of(hole.center(),hole.u(),hole.v()))for(double n:new double[]{p.x,p.y,p.z})row.add(DoubleTag.valueOf(n));row.add(DoubleTag.valueOf(hole.width()));list.add(row);}if(holes.isEmpty())tag.remove(TAG);else tag.put(TAG,list);
        if(edited==null)tag.remove(EDITED_TAG);else tag.put(EDITED_TAG,edited.save());
    }
    public void read(CompoundTag tag) {
        var next=new ArrayList<ArmorHole>();var list=tag.getList(TAG,Tag.TAG_LIST);
        for(int i=0;i<list.size();i++){var row=list.getList(i);if(row.size()!=9&&row.size()!=10)continue;
            try{var hole=new ArmorHole(vec(row,0),vec(row,3),vec(row,6),row.size()==9?ArmorHole.LEGACY_WIDTH:row.getDouble(9));if(hole.center().lengthSqr()<16)next.add(hole);}catch(IllegalArgumentException ignored){}}
        EditableArmorMesh loaded=null;try{if(tag.contains(EDITED_TAG))loaded=EditableArmorMesh.load(tag.getCompound(EDITED_TAG));}catch(IllegalArgumentException invalid){/* Ignore malformed imported NBT. */}
        if(!holes.equals(next)||!Objects.equals(edited,loaded)){
            boolean append=Objects.equals(edited,loaded)&&next.size()>=holes.size()&&next.subList(0,holes.size()).equals(holes);int previous=holes.size();var base=cachedBase;var old=mesh;
            holes=List.copyOf(next);edited=loaded;invalidate();
            if(append&&base!=null&&old!=null){cachedBase=base;mesh=old;if(edited!=null||base.closed())for(int i=previous;i<holes.size();i++)mesh=mesh.subtract(holes.get(i));}
        }
    }
    private static Vec3 vec(ListTag row,int i){return new Vec3(row.getDouble(i),row.getDouble(i+1),row.getDouble(i+2));}
}
