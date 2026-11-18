package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes the generic long-range proxy behave like an in-flight ballistic missile. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
        remap = false, priority = 2000)
public abstract class MianbaoBallisticMissileRemoteVisualMixin {
    private static final String REMOTE_VISUAL_TAG = "CreateFireControlRemoteVisual";

    @Inject(method = "tickDocked", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$tickDf26Rail(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!MianbaoBallisticMissileCompat.isDf26(self)) return;
        Vec3 mounted = MianbaoBallisticMissileCompat.mountedWorldPosition(self);
        Quaternionf rotation = MianbaoBallisticMissileCompat.renderRotation(self);
        if (mounted != null && rotation != null) {
            Vector3f localForward = rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
            Vec3 forward = new Vec3(
                    localForward.x, localForward.y, localForward.z).normalize();
            double railDistance = createFireControl$railDistance(
                    MianbaoBallisticMissileCompat.launchAge(self));
            Vec3 position = mounted.add(forward.scale(railDistance));
            self.setPos(position.x, position.y, position.z);
            self.setDeltaMovement(Vec3.ZERO);
            self.noPhysics = true;
            if (!self.level().isClientSide() && railDistance >= 5.5) {
                MianbaoBallisticMissileCompat.detachDf26(self);
            }
        }
        ci.cancel();
    }

    private static double createFireControl$railDistance(int age) {
        double distance = 0.0;
        for (int tick = 33; tick <= Math.min(age, 160); tick++) {
            double elapsed = tick - 32.0;
            distance += Math.min(0.36, 0.00012 * elapsed * elapsed);
        }
        return Math.min(5.5, distance);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$freezeRemoteProxy(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide()
                && self.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) {
            ci.cancel();
        }
    }

    @Inject(method = "isDocked", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$markProxyDetached(
            CallbackInfoReturnable<Boolean> cir
    ) {
        Entity self = (Entity) (Object) this;
        if (self.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "renderRotation", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$orientRemoteProxy(
            CallbackInfoReturnable<Quaternionf> cir
    ) {
        Entity self = (Entity) (Object) this;
        if (!self.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) return;
        Quaternionf synchronizedRotation =
                MianbaoBallisticMissileCompat.remoteRenderRotation(self);
        if (synchronizedRotation != null) {
            cir.setReturnValue(synchronizedRotation);
            return;
        }
        Vec3 velocity = self.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0e-8) return;
        Vec3 direction = velocity.normalize();
        cir.setReturnValue(new Quaternionf().rotationTo(
                new Vector3f(0.0F, 1.0F, 0.0F),
                new Vector3f((float) direction.x, (float) direction.y,
                        (float) direction.z)));
    }
}
