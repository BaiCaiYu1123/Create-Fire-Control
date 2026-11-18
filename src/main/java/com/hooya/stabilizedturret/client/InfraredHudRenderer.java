package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.InfraredGeometry;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.hooya.stabilizedturret.network.InfraredFramePayload;
import com.hooya.stabilizedturret.network.InfraredStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class InfraredHudRenderer {
    private static InfraredStatePayload state = InfraredStatePayload.inactive();
    private static net.minecraft.client.multiplayer.ClientLevel receivedLevel;
    private static long receivedTick = Long.MIN_VALUE;
    private static long selectedAt;
    private static long sentFrameAt = Long.MIN_VALUE;
    private static Vec3 previousAircraftOrigin = Vec3.ZERO, previousAircraftForward = new Vec3(0, 0, -1);

    public static void accept(InfraredStatePayload payload) {
        var mc = Minecraft.getInstance();
        boolean sameSession = mc.level == receivedLevel && state.selected() && payload.selected();
        previousAircraftOrigin = sameSession ? state.aircraftOrigin() : payload.aircraftOrigin();
        previousAircraftForward = sameSession ? state.aircraftForward() : payload.aircraftForward();
        if (!payload.selected() || !state.selected() || state.epoch() != payload.epoch() || receivedLevel != mc.level) {
            selectedAt = mc.level == null ? 0 : mc.level.getGameTime();
            sentFrameAt = Long.MIN_VALUE;
            MouseFlightSeekerCompat.clearInfraredAim();
        }
        state = payload;
        receivedLevel = mc.level;
        receivedTick = mc.level == null ? Long.MIN_VALUE : mc.level.getGameTime();
    }
    public static boolean selected() {
        var mc = Minecraft.getInstance();
        return state.selected() && state.ammo() > 0 && mc.level != null && mc.level == receivedLevel && mc.player != null && mc.player.isPassenger()
                && mc.level.getGameTime() >= receivedTick && mc.level.getGameTime() - receivedTick <= 10;
    }
    private static InfraredTargeting.Frame cameraFrame(Minecraft mc, float partial) {
        var camera = mc.gameRenderer.getMainCamera();
        double fov = ((GameRendererAccessor) mc.gameRenderer).stabilizedTurret$getFov(camera, partial, true);
        var frame = new InfraredTargeting.Frame(camera.getPosition(), new Vec3(camera.getLookVector()),
                new Vec3(camera.getLeftVector()).scale(-1), new Vec3(camera.getUpVector()),
                Math.tan(Math.toRadians(Math.clamp(fov, .01, 179)) * .5),
                (double) mc.getWindow().getGuiScaledWidth() / Math.max(1, mc.getWindow().getGuiScaledHeight()));
        if (!state.selected() || receivedLevel != mc.level) return frame;
        Vec3 axis = previousAircraftForward.lerp(state.aircraftForward(), partial);
        if (axis.lengthSqr() < 1e-8) axis = state.aircraftForward();
        return frame.withAircraft(previousAircraftOrigin.lerp(state.aircraftOrigin(), partial), axis.normalize());
    }
    @SubscribeEvent
    public static void crosshair(RenderGuiLayerEvent.Pre event) {
        if (selected() && VanillaGuiLayers.CROSSHAIR.equals(event.getName())) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderGuiEvent.Post event) {
        if (ClientWeaponViewState.isActive()) return;
        var mc = Minecraft.getInstance();
        if (!selected() || mc.screen != null || ClientTelevisionGuidanceState.isActive()) return;
        var g = event.getGuiGraphics();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        long now = mc.level.getGameTime();
        InfraredTargeting.Frame frame = MouseFlightSeekerCompat.frame(event, cameraFrame(mc, partial));
        if (sentFrameAt != now) {
            PacketDistributor.sendToServer(new InfraredFramePayload(frame, state.epoch()));
            sentFrameAt = now;
        }
        if (mc.options.hideGui) return;
        boolean blinking = InfraredGeometry.blinking(now, state.lostAt());
        boolean visible = InfraredGeometry.pulseVisible(now + partial - (blinking ? state.lostAt() : selectedAt));
        double sx = Double.NaN, sy = Double.NaN;
        if (visible) {
            double aspect = frame.aspect();
            if (frame.seekerActive()) { sx = frame.seekerX(); sy = frame.seekerY(); }
            if (state.target() != null && (state.locked() || blinking)) {
                Vec3 target = state.target().add(state.velocity().scale(Math.min(2, now - receivedTick + partial)));
                Vec3 projected = frame.project(target);
                if (projected != null) {
                    sx = InfraredGeometry.referenceX(projected.x, aspect);
                    sy = projected.y * InfraredGeometry.HEIGHT;
                } else {
                    sx = Double.NaN;
                    sy = Double.NaN;
                }
            }
        }
        InfraredHudMesh.render(g, frame, state.locked() || blinking ? InfraredGeometry.RED : InfraredGeometry.GREEN,
                sx, sy, visible, false);
        if (!ClientRadarState.isActive()) g.drawString(mc.font,
                Component.translatable("hud.create_fire_control.aircraft.weapon_selected",
                        Component.translatable("hud.create_fire_control.aircraft.air_to_air"),
                        MianbaoAircraftCompat.WeaponType.CLOSE.displayName(), state.ammo(), state.ammo()), 8, 50, 0xEEDDE5E8, false);
    }

    /** Height-based sizing preserves the reference circles through resolution and GUI-scale changes. */
    public static void renderRings(GuiGraphics g, int color, double seekerX, double seekerY, InfraredTargeting.Frame frame) {
        InfraredHudMesh.render(g, frame, color, seekerX, seekerY, true, false);
    }
    private InfraredHudRenderer() {}
}
