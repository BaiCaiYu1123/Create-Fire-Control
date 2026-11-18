package com.hooya.stabilizedturret.compat.cbc;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.compat.sable.SableCompat;
import rbasamoyai.createbigcannons.compat.sable.SableForceGroupsCompat;

/** Only loaded by the optional CBC recoil mixin. */
public final class CannonRecoilCompat {
    public static boolean applyBoundRecoil(
            AbstractContraptionEntity cannon, Vec3 point, Vec3 recoil
    ) {
        if (!(cannon.level() instanceof ServerLevel level)) return false;
        StabilizerControllerBlockEntity controller = ControllerTracker.findForCannonRecoil(cannon);
        if (controller == null) return false;
        double magnitude = controller.getCannonRecoilMagnitude();
        if (magnitude > 0.0) {
            // Replace CBC's global Sable multiplier, retaining the original
            // shot vector, application point and physics-step queue.
            SableCompat.enqueueForce(level, point, recoil.scale(magnitude), 1,
                    SableForceGroupsCompat.RECOIL.get());
        }
        return true;
    }

    private CannonRecoilCompat() {}
}
