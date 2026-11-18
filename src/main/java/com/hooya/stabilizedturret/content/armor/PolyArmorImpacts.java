package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import com.hooya.stabilizedturret.mixin.PolyArmorProjectileAccessor;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import java.util.function.Supplier;
import static rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult.KinematicOutcome.PENETRATE;

/** Scoped per impact, so ordinary blocks and nested impacts never inherit the armor exception. */
public final class PolyArmorImpacts {
    private static final class Contact {
        final Entity projectile;final BlockPos pos;final Vec3 velocity;boolean completed;
        Contact(Entity projectile,BlockPos pos,Vec3 velocity){this.projectile=projectile;this.pos=pos;this.velocity=velocity;}
    }
    private static final ThreadLocal<Contact> CURRENT=new ThreadLocal<>();
    public static boolean active(){return CURRENT.get()!=null;}
    public static boolean activeAt(Level level,BlockPos pos){var c=CURRENT.get();return c!=null&&c.projectile.level()==level&&c.pos.equals(pos);}
    public static AbstractCannonProjectile.ImpactResult calculate(AbstractCannonProjectile projectile,ProjectileContext context,BlockState state,BlockHitResult hit,Supplier<AbstractCannonProjectile.ImpactResult> original){
        if(!(state.getBlock() instanceof PolyBlock)&&!EquivalentArmor.enabled())return original.get();
        try(var scope=begin(projectile,state,hit)){
            var result=ArmorProjectileBores.isAutocannon(projectile)&&!net.neoforged.fml.ModList.get().isLoaded("cbc_terminal_ballistics")
                    ? ArmorAutocannonImpact.calculate(projectile,context,state,hit) : original.get();
            scope.complete(result,hit);return result;
        }
    }
    public static Scope begin(Entity projectile,BlockState state,BlockHitResult hit){
        Vec3 velocity=projectile.getDeltaMovement();
        if(projectile instanceof AbstractCannonProjectile)velocity=velocity.add(((PolyArmorProjectileAccessor)projectile).fireControl$forces(projectile.position(),velocity));
        var previous=CURRENT.get();
        var next=state.getBlock() instanceof PolyBlock?
                previous!=null&&previous.projectile==projectile&&previous.pos.equals(hit.getBlockPos())?previous:
                        new Contact(projectile,hit.getBlockPos().immutable(),velocity):null;
        var scope=new Scope(projectile,CURRENT.get(),next,EquivalentArmor.impact(projectile,hit,velocity));CURRENT.set(next);return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final Contact previous,contact;
        private final Entity projectile;private final EquivalentArmor.Scope equivalent;
        private Scope(Entity projectile,Contact previous,Contact contact,EquivalentArmor.Scope equivalent){this.projectile=projectile;this.previous=previous;this.contact=contact;this.equivalent=equivalent;}
        public void complete(Object result,BlockHitResult hit){
            if(EquivalentArmor.enabled()&&result instanceof AbstractCannonProjectile.ImpactResult outcome){
                if(outcome.kinematics()==AbstractCannonProjectile.ImpactResult.KinematicOutcome.STOP&&projectile instanceof EquivalentArmorStopAccess access)access.fireControl$pinStop(hit);
                else if(outcome.kinematics()==PENETRATE)ArmorProjectilePassage.allow(projectile,hit.getBlockPos());
            }
            if(contact==null||contact.completed||!(result instanceof AbstractCannonProjectile.ImpactResult impact)||impact.kinematics()!=PENETRATE)return;
            contact.completed=true;
            var projectile=contact.projectile;var pos=contact.pos;ArmorProjectilePassage.allow(projectile,pos);
            // The client runs the same multi-contact ray loop before the BE's carved mesh arrives.
            // It must ignore this processed contact too, or it repeatedly charges the same armor
            // until its predicted mass becomes zero and CBC queues an embedded projectile.
            if(projectile instanceof AbstractCannonProjectile
                    &&projectile.level().getBlockEntity(pos) instanceof PolyArmorAccess){
                Vec3 point=localPoint(projectile.level(),pos,hit.getLocation()).subtract(Vec3.atLowerCornerOf(pos));
                Vec3 direction=localVector(projectile.level(),pos,contact.velocity,projectile);
                var bore=ArmorHole.along(point,direction,ArmorProjectileBores.width(projectile));
                var level=projectile.level();var body=Sable.HELPER.getContaining(level,pos);
                for(var cut:ArmorBoreFootprint.cuts(bore)){
                    var target=pos.offset(cut.offset());
                    if(!level.hasChunkAt(target)||Sable.HELPER.getContaining(level,target)!=body)continue;
                    if(!(level.getBlockEntity(target) instanceof PolyBlockEntity be)||!(be instanceof PolyArmorAccess armor))continue;
                    // Client prediction skips this shot's covered contacts while the server's
                    // per-block meshes arrive. Rendering and saved damage remain server-owned.
                    ArmorProjectilePassage.allow(projectile,target);
                    if(!level.isClientSide&&armor.fireControl$armorDamage().perforate(be.poly(),cut.hole()))armor.fireControl$armorChanged();
                }
            }
        }
        @Override public void close(){equivalent.close();if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static Vec3 localPoint(Level level,BlockPos pos,Vec3 point){
        var local=point.subtract(Vec3.atLowerCornerOf(pos));if(Math.abs(local.x)<2&&Math.abs(local.y)<2&&Math.abs(local.z)<2)return point;
        var body=Sable.HELPER.getContaining(level,pos);return body==null?point:body.logicalPose().transformPositionInverse(point);
    }
    public static Vec3 localVector(Level level,BlockPos pos,Vec3 vector,Entity projectile){
        var body=Sable.HELPER.getContaining(level,pos);
        if(body==null||Sable.HELPER.getContaining(level,projectile.blockPosition())==body)return vector;
        return body.logicalPose().transformNormalInverse(vector);
    }
    public static Vec3 surfaceNormal(Level level,BlockHitResult hit){
        if(!(level.getBlockEntity(hit.getBlockPos()) instanceof PolyBlockEntity be))return null;
        if(hit instanceof ArmorHitResult exact){
            var body=Sable.HELPER.getContaining(level,hit.getBlockPos());
            return body==null?exact.normal():body.logicalPose().transformNormal(exact.normal());
        }
        var c=CURRENT.get();if(c==null)return null;
        Vec3 local=localPoint(level,hit.getBlockPos(),hit.getLocation()).subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
        Vec3 dir=localVector(level,hit.getBlockPos(),c.velocity,c.projectile).normalize();
        var mesh=((PolyArmorAccess)be).fireControl$armorDamage().mesh(be.poly());
        var cross=mesh.clip(local.subtract(dir.scale(.25)),local.add(dir.scale(.25)));if(cross==null)return null;
        var body=Sable.HELPER.getContaining(level,hit.getBlockPos());
        return body==null?cross.normal():body.logicalPose().transformNormal(cross.normal());
    }
    private PolyArmorImpacts(){}
}
