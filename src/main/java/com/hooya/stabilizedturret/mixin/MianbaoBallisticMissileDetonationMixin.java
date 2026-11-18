package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import com.hooya.stabilizedturret.content.display.BallisticMissileGuidance;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Relays the native 2x ballistic nuclear visual to viewers outside particle range. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
        remap = false, priority = 2000)
public abstract class MianbaoBallisticMissileDetonationMixin {
    private static final String OWNER_TAG = "CreateFireControlBallisticOwner";
    private static final String VISUAL_SENT_TAG =
            "CreateFireControlBallisticDetonationVisualSent";

    @Inject(method = "detonateAt", at = @At("HEAD"), require = 0)
    private void createFireControl$relayRemoteBallisticVisual(
            Vec3 impact, CallbackInfo ci) {
        Entity missile = (Entity) (Object) this;
        if (missile.level().isClientSide()
                || !missile.getPersistentData().getBoolean(OWNER_TAG)
                || missile.getPersistentData().getBoolean(VISUAL_SENT_TAG)
                || impact == null) {
            return;
        }
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        Vec3 worldImpact = MianbaoMissileCompat.getWorldPosition(missile, impact);
        BallisticMissileGuidance.noteNativeImpact(missile, worldImpact);
        missile.getPersistentData().putBoolean(VISUAL_SENT_TAG, true);
        MianbaoMissileEffects.broadcastDetonationVisual(
                root, worldImpact,
                MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR,
                root.getRandom().nextLong(), true);
    }
}
