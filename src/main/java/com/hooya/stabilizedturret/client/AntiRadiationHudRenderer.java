package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.AntiRadiationGeometry;
import com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting;
import com.hooya.stabilizedturret.content.controller.AntiRadiationSearchFrame;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.hooya.stabilizedturret.network.AntiRadiationFramePayload;
import com.hooya.stabilizedturret.network.AntiRadiationStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3d;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class AntiRadiationHudRenderer {
    private static AntiRadiationStatePayload state = AntiRadiationStatePayload.inactive();
    private static Vec3 previousTarget;
    private static net.minecraft.client.multiplayer.ClientLevel receivedLevel;
    private static long receivedTick = Long.MIN_VALUE;
    private static long searchStartedAt;
    private static long sentFrameAt = Long.MIN_VALUE;

    public static void accept(AntiRadiationStatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != receivedLevel || !state.selected() || !payload.selected()) sentFrameAt = Long.MIN_VALUE;
        if (mc.level != receivedLevel || !state.enabled() || state.target() != null && payload.target() == null)
            searchStartedAt = mc.level == null ? 0 : mc.level.getGameTime();
        previousTarget = mc.level == receivedLevel && state.target() != null && payload.target() != null
                && state.target().distanceToSqr(payload.target()) < 128 * 128 ? state.target() : payload.target();
        state = payload;
        receivedLevel = mc.level;
        receivedTick = mc.level == null ? Long.MIN_VALUE : mc.level.getGameTime();
    }

    private static boolean fresh() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level == receivedLevel && mc.player != null && mc.player.isPassenger()
                && mc.level.getGameTime() >= receivedTick && mc.level.getGameTime() - receivedTick <= 10;
    }
    public static boolean selected() { return fresh() && state.selected(); }
    public static int ammo() { return fresh() ? state.ammo() : 0; }

    private static AntiRadiationTargeting.Frame cameraFrame(Minecraft mc, float partial) {
        var camera = mc.gameRenderer.getMainCamera();
        double fov = ((GameRendererAccessor) mc.gameRenderer).stabilizedTurret$getFov(camera, partial, true);
        return new AntiRadiationTargeting.Frame(camera.getPosition(), new Vec3(camera.getLookVector()),
                new Vec3(camera.getLeftVector()).scale(-1), new Vec3(camera.getUpVector()),
                Math.tan(Math.toRadians(Math.clamp(fov, 0.01, 179)) * 0.5),
                (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderGuiEvent.Post event) {
        if (ClientWeaponViewState.isActive()) return;
        var mc = Minecraft.getInstance();
        if (!selected() || mc.screen != null || ClientTelevisionGuidanceState.isActive()) return;
        var g = event.getGuiGraphics();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        var frame = MouseFlightSeekerCompat.frame(event, cameraFrame(mc, partial));
        long now = mc.level.getGameTime();
        if (sentFrameAt != now) {
            PacketDistributor.sendToServer(new AntiRadiationFramePayload(frame, false));
            sentFrameAt = now;
        }
        if (mc.options.hideGui) return;
        Vec3 target = state.target() == null ? null : previousTarget == null
                ? state.target() : previousTarget.lerp(state.target(), partial);
        renderFrames(g, frame, renderBasis(state.searchFrame(), partial), target,
                state.enabled(), now - searchStartedAt + partial);
        if (!ClientRadarState.isActive()) {
            g.drawString(mc.font, Component.translatable("hud.create_fire_control.aircraft.weapon_selected",
                    Component.translatable("hud.create_fire_control.aircraft.air_to_ground"),
                    WeaponType.ANTI_RADIATION.displayName(), ammo(), ammo()), 8, 50, 0xEEDDE5E8, false);
        }
    }

    static AntiRadiationGeometry.Basis renderBasis(AntiRadiationSearchFrame mount, float partial) {
        if (mount == null) return null;
        var level = Minecraft.getInstance().level;
        if (level != null && mount.bodyId() != null) {
            var body = SubLevelContainer.getContainer(level).getSubLevel(mount.bodyId());
            if (body instanceof ClientSubLevel client && !body.isRemoved()) {
                var pose = client.renderPose(partial);
                var f = mount.localForward(); var u = mount.localUp();
                Vector3d forward = pose.transformNormal(new Vector3d(f.x, f.y, f.z));
                Vector3d up = pose.transformNormal(new Vector3d(u.x, u.y, u.z));
                return AntiRadiationGeometry.Basis.of(new Vec3(forward.x, forward.y, forward.z),
                        new Vec3(up.x, up.y, up.z));
            }
        }
        return mount.basis();
    }

    static void renderFrames(GuiGraphics g, AntiRadiationTargeting.Frame frame, AntiRadiationGeometry.Basis aperture, Vec3 target,
                             boolean enabled, double elapsedTicks) {
        if (!enabled) return;
        float scale = g.guiHeight() / (float) AntiRadiationGeometry.HEIGHT;
        if (aperture != null && AntiRadiationGeometry.searchFrameVisible(enabled, target != null, elapsedTicks))
            drawAcquisitionFrame(g, frame, aperture);
        if (target != null) {
            Vec3 screen = frame.project(target);
            if (screen != null && screen.x >= 0 && screen.x <= 1 && screen.y >= 0 && screen.y <= 1) {
                g.pose().pushPose();
                g.pose().translate(screen.x * g.guiWidth(), screen.y * g.guiHeight(), 0);
                g.pose().scale(scale, scale, 1);
                drawTargetFrame(g);
                g.pose().popPose();
            }
        }
    }

    static void drawAcquisitionFrame(GuiGraphics g, AntiRadiationTargeting.Frame camera, AntiRadiationGeometry.Basis aperture) {
        corner(g, camera, aperture, AntiRadiationGeometry.LEFT, AntiRadiationGeometry.TOP, 1, 1);
        corner(g, camera, aperture, AntiRadiationGeometry.RIGHT, AntiRadiationGeometry.TOP, -1, 1);
        corner(g, camera, aperture, AntiRadiationGeometry.LEFT, AntiRadiationGeometry.BOTTOM, 1, -1);
        corner(g, camera, aperture, AntiRadiationGeometry.RIGHT, AntiRadiationGeometry.BOTTOM, -1, -1);
    }

    private static void corner(GuiGraphics g, AntiRadiationTargeting.Frame camera, AntiRadiationGeometry.Basis aperture,
                               int x, int y, int dx, int dy) {
        Vec3 corner = aperture.ray(x, y);
        segment(g, camera, corner, aperture.ray(x + dx * AntiRadiationGeometry.ARM, y), dx * dy);
        // Join without overlapping translucent strokes, matching the reference corner opacity.
        segment(g, camera, aperture.ray(x, y + dy * AntiRadiationGeometry.STROKE),
                aperture.ray(x, y + dy * AntiRadiationGeometry.ARM), -dx * dy);
    }

    /** Clip direction rays before projection, then clip screen segments before issuing GPU coordinates. */
    private static void segment(GuiGraphics g, AntiRadiationTargeting.Frame camera, Vec3 a, Vec3 b, int side) {
        double za = a.dot(camera.forward()), zb = b.dot(camera.forward()), near = 1e-4;
        if (za <= near && zb <= near) return;
        if (za < near) a = a.lerp(b, (near - za) / (zb - za));
        if (zb < near) b = b.lerp(a, (near - zb) / (a.dot(camera.forward()) - zb));
        Vec3 start = camera.projectDirection(a), end = camera.projectDirection(b);
        if (start == null || end == null) return;
        double x = start.x, y = start.y, dx = end.x - x, dy = end.y - y;
        double lo = 0, hi = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {x, 1 - x, y, 1 - y};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) { if (q[i] < 0) return; }
            else {
                double r = q[i] / p[i];
                if (p[i] < 0) lo = Math.max(lo, r); else hi = Math.min(hi, r);
                if (lo > hi) return;
            }
        }
        float x1 = (float) ((x + lo * dx) * g.guiWidth()), y1 = (float) ((y + lo * dy) * g.guiHeight());
        float x2 = (float) ((x + hi * dx) * g.guiWidth()), y2 = (float) ((y + hi * dy) * g.guiHeight());
        float thickness = (float) (AntiRadiationGeometry.STROKE * g.guiHeight() / AntiRadiationGeometry.HEIGHT);
        float length = (float) Math.hypot(x2 - x1, y2 - y1);
        if (length < 1e-5) return;
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(new Quaternionf().rotationZ((float) Math.atan2(y2 - y1, x2 - x1)));
        g.pose().scale(length, thickness, 1);
        g.fill(0, side < 0 ? -1 : 0, 1, side < 0 ? 0 : 1, AntiRadiationGeometry.RANGE_COLOR);
        g.pose().popPose();
    }

    static void drawTargetFrame(GuiGraphics g) {
        int x = AntiRadiationGeometry.TARGET_WIDTH / 2, y = AntiRadiationGeometry.TARGET_HEIGHT / 2;
        int c = AntiRadiationGeometry.TARGET_COLOR;
        g.fill(-x, -y, x, -y + 1, c); g.fill(-x, y - 1, x, y, c);
        g.fill(-x, -y + 1, -x + 1, y - 1, c); g.fill(x - 1, -y + 1, x, y - 1, c);
    }
    private AntiRadiationHudRenderer() {}
}
