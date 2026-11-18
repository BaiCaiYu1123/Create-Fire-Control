package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.FireControlMissileCommandGuard;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileGuidanceHandler;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseGuidanceHandler;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftGuidanceHandler;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodProjectileHandler;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mianbao missiles execute silent global forceload commands every tick and
 * execute `forceload remove all` when they explode. Those commands can remove
 * the forced chunks backing the Sable vehicle that launched the missile.
 * Suppress only the two exact silent commands while a bound launcher is being
 * invoked or while a fire-control adopted missile is alive. Player commands
 * always retain their vanilla behavior.
 */
@Mixin(value = Commands.class, priority = 2000)
public abstract class CommandsMixin {
    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void createFireControl$suppressNativeMissileForceload(
            CommandSourceStack source,
            String command,
            CallbackInfo ci
    ) {
        if (source.getEntity() != null
                || (!command.equals("forceload add ~ ~ ~ ~")
                && !command.equals("forceload remove all"))) {
            return;
        }
        if (FireControlMissileCommandGuard.isLaunching()
                || MianbaoMissileGuidanceHandler.hasAnyGuidedMissile()
                || MianbaoAircraftGuidanceHandler.hasAnyGuidedMissile()
                || MianbaoAirDefenseGuidanceHandler.hasAnyGuidedMissile()
                || MianbaoRocketPodProjectileHandler.hasAnyActiveRocket()
                || CruiseMissileGuidance.hasAnyGuidedMissile()) {
            ci.cancel();
        }
    }
}
