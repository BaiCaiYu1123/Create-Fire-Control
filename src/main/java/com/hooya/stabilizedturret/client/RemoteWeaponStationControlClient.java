package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.RemoteWeaponStationInputPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Captures direct-control mouse input and supplies smooth hold-to-zoom. */
// Legacy client retained unregistered; Mianbao now owns the optical camera and input.
public final class RemoteWeaponStationControlClient {
    private static final double ZOOM_MAGNIFICATION = 1.75;
    private static final double ZOOM_TIME_CONSTANT_SECONDS = 0.18;
    private static boolean active;
    private static boolean lastFiring;
    private static boolean zoomHeld;
    private static double displayedFovFactor = 1.0;
    private static long lastZoomUpdateNanos;

    public static void accept(boolean nextActive) {
        active = nextActive;
        if (!active) {
            lastFiring = false;
            zoomHeld = false;
        }
        lastZoomUpdateNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active || (!event.isAttack() && !event.isUseItem())) return;
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            active = false;
            lastFiring = false;
            zoomHeld = false;
            return;
        }
        boolean canCapture = active && minecraft.screen == null
                && minecraft.getWindow() != null;
        long window = minecraft.getWindow().getWindow();
        boolean firing = canCapture && !com.hooya.stabilizedturret.client.remote.WirelessControllerClient.blocks(true, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && GLFW.glfwGetMouseButton(
                window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        zoomHeld = canCapture && !com.hooya.stabilizedturret.client.remote.WirelessControllerClient.blocks(true, GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                && GLFW.glfwGetMouseButton(
                window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (active && (firing != lastFiring || minecraft.player.tickCount % 5 == 0)) {
            PacketDistributor.sendToServer(new RemoteWeaponStationInputPayload(firing));
        }
        lastFiring = firing;
    }

    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        long now = System.nanoTime();
        if (lastZoomUpdateNanos == 0L) lastZoomUpdateNanos = now;
        double elapsed = Math.min(0.1,
                Math.max(0.0, (now - lastZoomUpdateNanos) / 1_000_000_000.0));
        lastZoomUpdateNanos = now;
        double target = active && zoomHeld ? 1.0 / ZOOM_MAGNIFICATION : 1.0;
        double alpha = 1.0 - Math.exp(-elapsed / ZOOM_TIME_CONSTANT_SECONDS);
        displayedFovFactor += (target - displayedFovFactor) * alpha;
        if (Math.abs(target - displayedFovFactor) < 1.0e-4) {
            displayedFovFactor = target;
        }
        if (active || Math.abs(displayedFovFactor - 1.0) > 1.0e-4) {
            event.setNewFovModifier((float) (event.getNewFovModifier()
                    * displayedFovFactor));
        }
    }

    private RemoteWeaponStationControlClient() {
    }
}
