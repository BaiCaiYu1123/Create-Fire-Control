package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseLauncher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes the ordinary-ammo extension at the native redstone firing entry. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.procedures.AntiairmissilelaunchergoProcedure",
        "net.mcreator.myfirstmod.procedures.Anti_air_missile_launcher_second_fireProcedure"
}, remap = false)
public abstract class MianbaoOrdinaryAaLaunchMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$ordinaryRedstone(LevelAccessor world, double x, double y,
                                                          double z, CallbackInfo ci) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world instanceof Level level && OrdinaryAirDefenseLauncher.hasRound(level.getBlockEntity(pos))) {
            OrdinaryAirDefenseLauncher.fireFromRedstone(level, pos);
            ci.cancel();
        }
    }
}
