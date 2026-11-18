package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.armor.*;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Stops use the entrance contact; thickness queries must never move a stopped shot to the exit. */
@Mixin(value=AbstractCannonProjectile.class,remap=false)
public abstract class EquivalentArmorProjectileMixin implements EquivalentArmorStopAccess {
    @Unique private BlockPos fireControl$stopBlock;
    @Unique private Vec3 fireControl$stopPoint;
    @Override public void fireControl$pinStop(BlockHitResult hit){
        var projectile=(AbstractCannonProjectile)(Object)this;
        fireControl$stopBlock=hit.getBlockPos().immutable();
        fireControl$stopPoint=PolyArmorImpacts.localPoint(projectile.level(),fireControl$stopBlock,hit.getLocation());
    }
    @Inject(method="startFalling",at=@At("HEAD"))
    private void fireControl$releaseRemovedSupport(CallbackInfo ci){
        fireControl$clearStop();
    }
    @Override
    public void fireControl$clearStop() {
        fireControl$stopBlock=null;fireControl$stopPoint=null;
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void fireControl$holdActualEntrance(CallbackInfo ci){
        if(fireControl$stopBlock==null)return;
        var projectile=(AbstractCannonProjectile)(Object)this;var level=projectile.level();
        if(!EquivalentArmor.enabled()||projectile.isRemoved()||level.getBlockState(fireControl$stopBlock).isAir()){
            fireControl$clearStop();return;
        }
        var body=Sable.HELPER.getContaining(level,fireControl$stopBlock);
        var point=body==null||Sable.HELPER.getContaining(level,projectile.blockPosition())==body?fireControl$stopPoint:body.logicalPose().transformPosition(fireControl$stopPoint);
        projectile.setPos(point);projectile.setGroundPos(point);
        if(projectile.isInGround())projectile.setDeltaMovement(Vec3.ZERO);
    }
}
