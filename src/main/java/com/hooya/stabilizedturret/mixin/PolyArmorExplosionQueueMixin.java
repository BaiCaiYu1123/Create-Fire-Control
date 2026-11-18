package com.hooya.stabilizedturret.mixin;
import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
@Mixin(value=ProjectileContext.class,remap=false)
public abstract class PolyArmorExplosionQueueMixin {
    @Inject(method="queueExplosion",at=@At("HEAD"),cancellable=true)
    private void fireControl$noWholeBlockBlast(BlockPos pos,float power,CallbackInfo ci){
        if(PolyArmorImpacts.activeAt(((ProjectileContext)(Object)this).projectile.level(),pos))ci.cancel();
    }
}
