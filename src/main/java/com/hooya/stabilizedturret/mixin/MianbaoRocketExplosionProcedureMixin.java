package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces adopted rocket warheads with the deferred terrain queue. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.procedures.Rocket_explodeProcedure",
        "net.mcreator.myfirstmod.procedures.HeavyRocketexplodeProcedure",
        "net.mcreator.myfirstmod.procedures.Heavyrocketfireexplode2Procedure"
}, remap = false, priority = 2100)
public abstract class MianbaoRocketExplosionProcedureMixin {
    private static final String DETONATED = "CreateFireControlRocketDetonated";

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createFireControl$queueRocketTerrainDamage(
            LevelAccessor world, double x, double y, double z,
            Entity source, CallbackInfo ci
    ) {
        if (source == null || source.level().isClientSide()
                || !source.getPersistentData().getBoolean(
                MianbaoRocketPodCompat.ADOPTED_TAG)) {
            return;
        }
        if (source.getPersistentData().getBoolean(DETONATED)) {
            ci.cancel();
            return;
        }
        Vec3 worldPosition = MianbaoMissileCompat.getWorldPosition(
                source, new Vec3(x, y, z));
        if (MianbaoRocketPodCompat.deferDetonation(source, worldPosition)) {
            ci.cancel();
        }
    }
}
