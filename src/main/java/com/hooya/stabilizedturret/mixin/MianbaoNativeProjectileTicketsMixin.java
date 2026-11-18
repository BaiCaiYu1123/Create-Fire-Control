package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.FireControlMissileCommandGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The new upstream native tickets yield to fire control's own projectile ticket lifecycle. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.world.NativeProjectileChunkLoading", remap = false)
public abstract class MianbaoNativeProjectileTicketsMixin {
    @Shadow
    public static void release(LevelAccessor world, Entity entity) { throw new AssertionError(); }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createFireControl$ownNativeTickets(LevelAccessor world, Entity entity, CallbackInfo ci) {
        if (entity == null) return;
        var data = entity.getPersistentData();
        if (FireControlMissileCommandGuard.isLaunching()
                || data.getBoolean("CreateFireControlGuided")
                || data.getBoolean("CreateFireControlAaGuided")
                || data.getBoolean("CreateFireControlAircraftGuided")
                || data.getBoolean("CreateFireControlCruiseGuided")
                || data.getBoolean("CreateFireControlDisplayGuided")
                || data.getBoolean("CreateFireControlOpticalGuided")
                || data.getBoolean("CreateFireControlRocket")) {
            release(world, entity);
            ci.cancel();
        }
    }
}
