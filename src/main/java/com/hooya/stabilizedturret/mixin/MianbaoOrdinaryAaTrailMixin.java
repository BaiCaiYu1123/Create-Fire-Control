package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The adopted round's motor, beam and timeout have one server-side writer. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.PortableantiairmissiletrailProcedure", remap = false)
public abstract class MianbaoOrdinaryAaTrailMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$beamFlight(LevelAccessor level, double x, double y, double z,
                                                    Entity missile, CallbackInfo ci) {
        if (missile != null && !missile.level().isClientSide() && OrdinaryAirDefenseGuidance.isManaged(missile)) ci.cancel();
    }
}
