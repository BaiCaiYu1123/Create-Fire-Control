package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MianbaoNativeGunfire;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Overrides only the assembled gun's pose, before native tracer velocity is synchronized. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.HeavyMachineGunRedstoneFireProcedure", remap = false)
public abstract class MianbaoNativeMountedGunfireMixin {
    @Unique
    private static final boolean createFireControl$nativePoseReady =
            MianbaoNativeGunfire.mountedHookReady();

    @ModifyVariable(method = "execute", at = @At("STORE"), ordinal = 0)
    private static Vec3 createFireControl$muzzle(Vec3 muzzle) {
        return MianbaoNativeGunfire.mountedMuzzle(muzzle);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/mcreator/myfirstmod/entity/AutocannontansheEntity;shoot(DDDFF)V"))
    private static void createFireControl$aim(@Coerce Projectile projectile,
            double x, double y, double z, float speed, float scatter, Operation<Void> original) {
        Vec3 direction = MianbaoNativeGunfire.mountedDirection(new Vec3(x, y, z));
        original.call(projectile, direction.x, direction.y, direction.z, speed, scatter);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean createFireControl$spawn(ServerLevel level, Entity shell,
            Operation<Boolean> original) {
        MianbaoNativeGunfire.beforeMountedSpawn(level, shell);
        boolean accepted = original.call(level, shell);
        MianbaoNativeGunfire.afterMountedSpawn(accepted);
        return accepted;
    }
}
