package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.PolyBlock;
import com.caca.copypoly.PolyBlockEntity;
import com.hooya.stabilizedturret.config.FireControlSettings;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesProvider;
import java.util.*;
import java.lang.ref.WeakReference;

/** A ray-local view of registered 1 m CBC properties. Never rewrites shared material data. */
public final class EquivalentArmor {
    private static final ThreadLocal<Trace> CURRENT=new ThreadLocal<>();
    // Edited/damaged meshes can be large. A bounded ray cache must not keep old mesh
    // generations alive after their block entity has replaced them.
    private static final class Key extends WeakReference<Object> {
        final long x,y,z,dx,dy,dz;final int hash;
        Key(Object geometry,long x,long y,long z,long dx,long dy,long dz){
            super(geometry);this.x=x;this.y=y;this.z=z;this.dx=dx;this.dy=dy;this.dz=dz;
            hash=Objects.hash(System.identityHashCode(geometry),x,y,z,dx,dy,dz);
        }
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object o){
            if(this==o)return true;Object geometry=get();
            return geometry!=null&&o instanceof Key k&&geometry==k.get()&&x==k.x&&y==k.y&&z==k.z&&dx==k.dx&&dy==k.dy&&dz==k.dz;
        }
    }
    private static final ThreadLocal<Map<Key,ArmorRayEnvelope>> CACHE=ThreadLocal.withInitial(()->new LinkedHashMap<>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Key,ArmorRayEnvelope> entry){return size()>128;}
    });
    private static final class Trace {
        final Level level;final BlockPos pos;final Vec3 point,direction;final boolean impact;final double incidence;Double length;
        Trace(Level level,BlockPos pos,Vec3 point,Vec3 direction,boolean impact,double incidence){this.level=level;this.pos=pos.immutable();this.point=point;this.direction=direction;this.impact=impact;this.incidence=incidence;}
    }
    public static boolean enabled(){return FireControlSettings.equivalentArmorEnabled();}
    public static final class Scope implements AutoCloseable {
        private final Trace previous;
        private Scope(Trace trace){previous=CURRENT.get();if(trace!=null)CURRENT.set(trace);}
        @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static Scope impact(Entity projectile,BlockHitResult hit,Vec3 velocity){
        if(!enabled())return new Scope(null);
        var level=projectile.level();var pos=hit.getBlockPos();var previous=CURRENT.get();
        if(previous!=null&&previous.impact&&previous.level==level&&previous.pos.equals(pos))return new Scope(previous);
        var direction=PolyArmorImpacts.localVector(level,pos,velocity,projectile).normalize();
        var point=PolyArmorImpacts.localPoint(level,pos,hit.getLocation()).subtract(Vec3.atLowerCornerOf(pos));
        Vec3 normal=hit instanceof ArmorHitResult exact?exact.normal():net.minecraft.world.phys.Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        return new Scope(new Trace(level,pos,point,direction,true,Math.max(1e-6,Math.abs(direction.dot(normal)))));
    }
    public static Scope inspect(Level level,BlockPos pos,Vec3 eye,Vec3 hit){
        if(!enabled())return new Scope(null);
        var body=Sable.HELPER.getContaining(level,pos);var localEye=body==null?eye:body.logicalPose().transformPositionInverse(eye);
        var localHit=PolyArmorImpacts.localPoint(level,pos,hit);
        return new Scope(new Trace(level,pos,localHit.subtract(Vec3.atLowerCornerOf(pos)),localHit.subtract(localEye).normalize(),false,1));
    }
    public static boolean activeAt(Level level,BlockPos pos){var trace=CURRENT.get();return enabled()&&trace!=null&&trace.level==level&&trace.pos.equals(pos);}
    public static double length(Level level,BlockPos pos){
        var trace=CURRENT.get();if(!activeAt(level,pos))return 1;
        if(trace.length==null)trace.length=measure(level,pos,trace.point,trace.direction);
        return trace.length;
    }
    public static double factor(Level level,BlockPos pos,boolean toughness){
        var trace=CURRENT.get();double length=length(level,pos);
        // CBC/CTB already multiply attack and mass-loss velocity by cos(incidence).
        // Supply projected toughness to count the geometric LOS increase exactly once.
        return toughness&&activeAt(level,pos)&&trace.impact?length*trace.incidence:length;
    }
    public static double measure(Level level,BlockPos pos,Vec3 point,Vec3 direction){
        if(direction.lengthSqr()<1e-12)return 0;
        return envelope(level,pos,level.getBlockState(pos),point,direction.normalize()).length();
    }

    /** Ray distances measured from a block-local point along a unit direction. */
    static ArmorRayEnvelope envelope(Level level,BlockPos pos,BlockState state,Vec3 point,Vec3 direction){
        Object geometry;
        if (state.getBlock() instanceof PolyBlock && level.getBlockEntity(pos) instanceof PolyBlockEntity be) {
            geometry = ((PolyArmorAccess) be).fireControl$armorDamage().mesh(be.poly());
        } else {
            geometry = state.getCollisionShape(level, pos, CollisionContext.empty());
        }
        double offset=point.dot(direction);
        Vec3 anchor=point.subtract(direction.scale(offset));
        var key=new Key(geometry,q(anchor.x),q(anchor.y),q(anchor.z),q(direction.x),q(direction.y),q(direction.z));
        // Cache relative to the perpendicular anchor, so all origins on this ray share
        // the same entry/exit while callers still get distances from their own origin.
        var envelope=CACHE.get().computeIfAbsent(key,k->geometry instanceof ArmorMesh mesh
                ? ArmorRayEnvelope.mesh(mesh,anchor,direction)
                : ArmorRayEnvelope.boxes(((VoxelShape)geometry).toAabbs(),anchor,direction));
        return new ArmorRayEnvelope(envelope.entry()-offset,envelope.exit()-offset);
    }
    public static BlockArmorPropertiesProvider wrap(BlockArmorPropertiesProvider provider){
        if(!enabled()||materialLookup()||provider instanceof Provider)return provider;
        return new Provider(provider);
    }
    private record Provider(BlockArmorPropertiesProvider original) implements BlockArmorPropertiesProvider {
        @Override public double hardness(Level level,net.minecraft.world.level.block.state.BlockState state,BlockPos pos,boolean recurse){
            double value;try(var ignored=baseMaterial()){value=original.hardness(level,state,pos,recurse);}return value*factor(level,pos,false);
        }
        @Override public double toughness(Level level,net.minecraft.world.level.block.state.BlockState state,BlockPos pos,boolean recurse){
            double value;try(var ignored=baseMaterial()){value=original.toughness(level,state,pos,recurse);}return value*factor(level,pos,true);
        }
        @Override public List<net.minecraft.world.level.block.state.BlockState> containedBlockStates(Level level,net.minecraft.world.level.block.state.BlockState state,BlockPos pos,boolean recurse){return original.containedBlockStates(level,state,pos,recurse);}
    }
    // Recursive providers may query a different copied material at the same position.
    private static final ThreadLocal<Integer> BASE_DEPTH=ThreadLocal.withInitial(()->0);
    public static boolean materialLookup(){return BASE_DEPTH.get()>0;}
    public static AutoCloseableBase baseMaterial(){BASE_DEPTH.set(BASE_DEPTH.get()+1);return new AutoCloseableBase();}
    public static final class AutoCloseableBase implements AutoCloseable {public void close(){BASE_DEPTH.set(BASE_DEPTH.get()-1);}}
    public static double materialToughness(Level level,net.minecraft.world.level.block.state.BlockState material,BlockPos pos){
        try(var ignored=baseMaterial()){return rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler.getProperties(material).toughness(level,material,pos,true);}
    }
    private static long q(double value){return Math.round(value*1e7);}
    private EquivalentArmor() {}
}
