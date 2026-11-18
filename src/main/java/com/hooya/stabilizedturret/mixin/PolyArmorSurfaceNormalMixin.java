package com.hooya.stabilizedturret.mixin;
import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.utils.CBCUtils;
@Mixin(value=CBCUtils.class,remap=false)
public abstract class PolyArmorSurfaceNormalMixin {
    @Inject(method="getSurfaceNormalVector(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/phys/Vec3;",at=@At("HEAD"),cancellable=true)
    private static void fireControl$realSurface(Level level,BlockHitResult hit,CallbackInfoReturnable<Vec3> cir){
        var normal=PolyArmorImpacts.surfaceNormal(level,hit);if(normal!=null)cir.setReturnValue(normal);
    }
}
