package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoDeferredDetonation;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures native self-detonations that occur inside Mianbao trail code. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.procedures.BombexplodeProcedure",
        "net.mcreator.myfirstmod.procedures.GroundMissileexplodeProcedure",
        "net.mcreator.myfirstmod.procedures.LittleBombexplodeProcedure"
}, remap = false, priority = 2000)
public abstract class MianbaoCruiseExplosionProcedureMixin {
    private static final String DETONATED = "CreateFireControlCruiseDetonated";

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createFireControl$separateRemoteDetonation(
            LevelAccessor world, double x, double y, double z,
            Entity source, CallbackInfo ci
    ) {
        if (source == null || source.level().isClientSide()
                || source.getPersistentData().getBoolean(DETONATED)) return;
        byte profile;
        if (MianbaoDeferredDetonation.isControlledClusterWarhead(source)) {
            profile = MianbaoDeferredDetonation.PROFILE_LITTLE_BOMB;
        } else {
            MianbaoCruiseMissileCompat.Type type =
                    MianbaoCruiseMissileCompat.missileType(source);
            if (type == null) return;
            profile = type.nuclear()
                    ? MianbaoDeferredDetonation.PROFILE_NUCLEAR
                    : type.usesConventionalCruiseWarhead()
                    ? MianbaoDeferredDetonation.PROFILE_BOMB
                    : MianbaoDeferredDetonation.PROFILE_GROUND;
        }
        source.getPersistentData().putBoolean(DETONATED, true);
        Vec3 position = MianbaoMissileCompat.getWorldPosition(
                source, new Vec3(x, y, z));
        if (MianbaoDeferredDetonation.intercept(source, position, profile)) {
            ci.cancel();
        }
    }
}
