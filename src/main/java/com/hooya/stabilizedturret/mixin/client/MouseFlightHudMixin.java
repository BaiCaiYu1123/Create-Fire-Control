package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import com.hooya.stabilizedturret.client.ClientWeaponViewState;
import com.hooya.stabilizedturret.client.MouseFlightSeekerCompat;
import com.hooya.stabilizedturret.client.InfraredHudRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shares the actual mouse-flight target circle with the infrared and anti-radiation seekers. */
@Pseudo
@Mixin(targets = "ace.flight.neoforge.client.MouseFlightControllerClient", remap = false)
public abstract class MouseFlightHudMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void fireControl$suspendObserverFlightInput(
            net.neoforged.neoforge.client.event.ClientTickEvent.Post event, CallbackInfo ci) {
        if (ClientWeaponViewState.isActive()) ci.cancel();
    }

    @Inject(method = "cameraAngles", at = @At("HEAD"), cancellable = true, require = 1)
    private static void fireControl$suspendObserverFlightCamera(
            net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event, CallbackInfo ci) {
        if (ClientWeaponViewState.isActive()) ci.cancel();
    }

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true, require = 1)
    private static void fireControl$observerOwnsView(CallbackInfoReturnable<Boolean> cir) {
        if (ClientWeaponViewState.isActive()) cir.setReturnValue(false);
    }

    @Inject(method = {"finalShoulderFollowWorldView", "sableCameraCenterOffset"},
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void fireControl$ignoreObserverFlightOverrides(
            net.minecraft.client.Camera camera, CallbackInfoReturnable<Object> cir) {
        if (ClientWeaponViewState.isActive()) cir.setReturnValue(null);
    }
    @Shadow private static boolean viewLockEnabled;
    @Shadow private static org.joml.Vector3d freeLookDirection;
    @Shadow public static boolean isActive() { throw new AssertionError(); }
    @Shadow private static org.joml.Vector3d targetFromCurrentView(Minecraft minecraft) { throw new AssertionError(); }
    @Shadow private static org.joml.Vector3d targetForObservedCamera(Minecraft minecraft, org.joml.Vector3d direction) { throw new AssertionError(); }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 1)
    private static void stabilizedTurret$hideMouseFlightHud(
            RenderGuiEvent.Post event, CallbackInfo ci
    ) {
        MouseFlightSeekerCompat.begin(event);
        if (ClientTelevisionGuidanceState.isActive() || ClientWeaponViewState.isActive()) {
            MouseFlightSeekerCompat.end(event);
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private static void stabilizedTurret$finishMouseFlightHud(RenderGuiEvent.Post event, CallbackInfo ci) {
        // The flight mod has already drawn its own, unmodified direction circle.
        // Free-look supplies a second projection exclusively for the IR seeker.
        if (InfraredHudRenderer.selected() && !viewLockEnabled && isActive()) {
            var minecraft = Minecraft.getInstance();
            var direction = freeLookDirection == null ? targetFromCurrentView(minecraft)
                    : targetForObservedCamera(minecraft, freeLookDirection);
            MouseFlightSeekerCompat.captureInfraredAim(direction);
        } else {
            MouseFlightSeekerCompat.clearInfraredAim();
        }
        MouseFlightSeekerCompat.end(event);
    }

    @Inject(method = "resolveAircraftFrame", at = @At("RETURN"), require = 1)
    private static void stabilizedTurret$captureAircraftNose(CallbackInfoReturnable<Object> cir) {
        MouseFlightSeekerCompat.captureAircraftFrame(cir.getReturnValue());
    }

    @Inject(method = "drawTargetCircle", at = @At("HEAD"), require = 1)
    private static void stabilizedTurret$captureMouseFlightTarget(
            GuiGraphics graphics, int x, int y, CallbackInfo ci
    ) {
        MouseFlightSeekerCompat.capture(graphics, x, y);
    }
}
