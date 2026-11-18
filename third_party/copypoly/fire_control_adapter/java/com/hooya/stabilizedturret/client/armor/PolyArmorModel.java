// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// Copyright (c) 2026 BaiCaiYu1123 (Fire Control adaptation)
// Material projection adapted from CopyPoly's PolyModel; see third_party/copypoly/LICENSE.
package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.PolyItem;
import com.caca.copypoly.client.PolyModel;
import com.hooya.stabilizedturret.content.armor.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.util.*;

/** Keeps CopyPoly's material rendering, with a separate, unrestricted runtime damage mesh. */
public final class PolyArmorModel extends PolyModel {
    private final ArmorMesh itemMesh;
    private final Map<ArmorMesh,Map<BlockState,Map<RenderType,List<BakedQuad>>>> cache=new IdentityHashMap<>();
    public PolyArmorModel(BakedModel original){this(original,null);}
    private PolyArmorModel(BakedModel original,ArmorMesh itemMesh){super(original);this.itemMesh=itemMesh;}
    public static BakedModel damagedItem(BakedModel original,ItemStack stack){
        var damage=new ArmorDamage();damage.read(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag());
        return damage.damaged()?new PolyArmorModel(original,damage.mesh(PolyItem.readShape(stack))):null;
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType type){
        ArmorMesh mesh=data.get(PolyArmorAccess.MESH);if(mesh==null)mesh=itemMesh;
        if(mesh==null)return super.getQuads(state,side,random,data,type);if(side!=null)return List.of();
        BlockState material=data.has(PolyArmorAccess.MESH)?CopycatModel.getMaterial(data):AllBlocks.COPYCAT_BASE.getDefaultState();
        Map<RenderType,List<BakedQuad>> layers;
        synchronized(cache){if(cache.size()>64)cache.clear();layers=cache.computeIfAbsent(mesh,k->new HashMap<>()).get(material);}
        if(layers==null){layers=bake(mesh,material);synchronized(cache){cache.computeIfAbsent(mesh,k->new HashMap<>()).put(material,layers);}}
        return type==null?layers.values().stream().flatMap(Collection::stream).toList():layers.getOrDefault(type,List.of());
    }
    private Map<RenderType,List<BakedQuad>> bake(ArmorMesh mesh,BlockState material){
        var source=Minecraft.getInstance().getBlockRenderer().getBlockModel(material);
        var faces=new EnumMap<Direction,List<ArmorMesh.Face>>(Direction.class);
        for (var face : mesh.faces()) {
            var n=face.normal();
            faces.computeIfAbsent(Direction.getNearest(n.x,n.y,n.z),direction -> new ArrayList<>()).add(face);
        }
        return bakeMaterial(faces,material,source,ModelData.EMPTY,42);
    }

    /** Project the same copied material onto any set of cell-local faces. */
    static Map<RenderType,List<BakedQuad>> bakeMaterial(Map<Direction,List<ArmorMesh.Face>> faces,
            BlockState material,BakedModel source,ModelData data,long seed) {
        if (faces.isEmpty()) return Map.of();
        var random=RandomSource.create(seed);
        var layers=new LinkedHashMap<RenderType,List<BakedQuad>>();
        for(var layer:source.getRenderTypes(material,random,data)){
            var result=new ArrayList<BakedQuad>();
            for(var direction:Direction.values()){
                var surface=faces.get(direction);
                if (surface==null) continue;
                random.setSeed(seed);var quads=new ArrayList<>(source.getQuads(material,direction,random,data,layer));
                random.setSeed(seed);for(var q:source.getQuads(material,null,random,data,layer))if(q.getDirection()==direction)quads.add(q);
                for(var face:surface)for(var q:quads)project(result,face,q,direction);
            }
            layers.put(layer,List.copyOf(result));
        }
        return Collections.unmodifiableMap(layers);
    }
    private static double a(Vec3 p,Direction dir){return dir.getAxis()==Direction.Axis.X?p.z:p.x;}
    private static double b(Vec3 p,Direction dir){return dir.getAxis()==Direction.Axis.Y?p.z:p.y;}
    private static Vec3 vertex(int[] data,int stride,int i){return new Vec3(Float.intBitsToFloat(data[i*stride]),Float.intBitsToFloat(data[i*stride+1]),Float.intBitsToFloat(data[i*stride+2]));}
    private static List<Vec3> clip(List<Vec3> poly,Direction dir,boolean axis,double boundary,boolean greater){
        var out=new ArrayList<Vec3>();if(poly.isEmpty())return out;
        Vec3 prev=poly.getLast();double pd=(axis?a(prev,dir):b(prev,dir))-boundary;
        for(Vec3 next:poly){double nd=(axis?a(next,dir):b(next,dir))-boundary;boolean pin=greater?pd>=-1e-7:pd<=1e-7,nin=greater?nd>=-1e-7:nd<=1e-7;
            if(pin!=nin)out.add(prev.lerp(next,pd/(pd-nd)));if(nin)out.add(next);prev=next;pd=nd;}return out;
    }
    private static void project(List<BakedQuad> output,ArmorMesh.Face face,BakedQuad source,Direction dir){
        int[] src=source.getVertices();int stride=src.length/4;Vec3 p0=vertex(src,stride,0),p1=vertex(src,stride,1),p3=vertex(src,stride,3);
        double a0=a(p0,dir),b0=b(p0,dir),da1=a(p1,dir)-a0,db1=b(p1,dir)-b0,da3=a(p3,dir)-a0,db3=b(p3,dir)-b0,det=da1*db3-da3*db1;
        if(Math.abs(det)<1e-9)return;double amin=2,amax=-1,bmin=2,bmax=-1;
        for(int i=0;i<4;i++){var p=vertex(src,stride,i);amin=Math.min(amin,a(p,dir));amax=Math.max(amax,a(p,dir));bmin=Math.min(bmin,b(p,dir));bmax=Math.max(bmax,b(p,dir));}
        var polygon=clip(clip(clip(clip(face.vertices(),dir,true,amin,true),dir,true,amax,false),dir,false,bmin,true),dir,false,bmax,false);
        double u0=Float.intBitsToFloat(src[4]),v0=Float.intBitsToFloat(src[5]),u1=Float.intBitsToFloat(src[stride+4])-u0,v1=Float.intBitsToFloat(src[stride+5])-v0,
                u3=Float.intBitsToFloat(src[3*stride+4])-u0,v3=Float.intBitsToFloat(src[3*stride+5])-v0;
        var n=face.normal();int normal=((byte)Math.round(n.x*127)&255)|(((byte)Math.round(n.y*127)&255)<<8)|(((byte)Math.round(n.z*127)&255)<<16);
        for(int t=1;t<polygon.size()-1;t++){
            Vec3[] triangle={polygon.getFirst(),polygon.get(t),polygon.get(t+1),polygon.get(t+1)};
            if(triangle[1].subtract(triangle[0]).cross(triangle[2].subtract(triangle[0])).lengthSqr()<1e-16)continue;
            int[] out=new int[src.length];for(int i=0;i<4;i++){
                System.arraycopy(src,0,out,i*stride,stride);var p=triangle[i];double da=a(p,dir)-a0,db=b(p,dir)-b0,s=(da*db3-da3*db)/det,tv=(da1*db-da*db1)/det;
                out[i*stride]=Float.floatToRawIntBits((float)p.x);out[i*stride+1]=Float.floatToRawIntBits((float)p.y);out[i*stride+2]=Float.floatToRawIntBits((float)p.z);
                out[i*stride+4]=Float.floatToRawIntBits((float)(u0+s*u1+tv*u3));out[i*stride+5]=Float.floatToRawIntBits((float)(v0+s*v1+tv*v3));if(stride>7)out[i*stride+7]=normal;
            }
            output.add(new BakedQuad(out,source.getTintIndex(),dir,source.getSprite(),source.isShade(),source.hasAmbientOcclusion()));
        }
    }
    @Override public ItemOverrides getOverrides(){var fallback=super.getOverrides();return new ItemOverrides(){
        @Override public BakedModel resolve(BakedModel model,ItemStack stack,ClientLevel level,LivingEntity entity,int seed){
            var damage=new ArmorDamage();damage.read(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag());
            return damage.damaged()?new PolyArmorModel(originalModel,damage.mesh(PolyItem.readShape(stack))):fallback.resolve(model,stack,level,entity,seed);
        }};}
}
