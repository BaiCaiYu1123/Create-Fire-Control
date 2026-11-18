package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.content.controller.MianbaoBombCompat;
import com.hooya.stabilizedturret.network.BombSightPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/** The selected bomb's world-space ground sight, shared by every camera. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientBombSight {
    private static BombSightPayload state = BombSightPayload.inactive();
    private static Vec3 previousImpact;
    private static long receivedTick = Long.MIN_VALUE;
    private static long impactTick = Long.MIN_VALUE;
    private static net.minecraft.client.multiplayer.ClientLevel receivedLevel;
    private static long meshTick = Long.MIN_VALUE;
    private static Vec3 meshAnchor;
    private static double meshRadius;
    private static java.util.List<BombSightGeometry.Segment> mesh = java.util.List.of();

    public static void clear() {
        state = BombSightPayload.inactive();
        previousImpact = null;
        receivedTick = impactTick = Long.MIN_VALUE;
        receivedLevel = null;
        meshTick = Long.MIN_VALUE;
        meshAnchor = null;
        meshRadius = 0;
        mesh = java.util.List.of();
    }

    public static void accept(BombSightPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.flight() != null && payload.impact() == null && minecraft.level != null) {
            Vec3 lod = payload.flight().equals(state.flight()) ? state.impact()
                    : com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster.clipBombBallistic(minecraft.level, payload.flight());
            if (lod != null) payload = new BombSightPayload(payload.selectedWeapon(), payload.selectedAmmo(), payload.totalAmmo(), payload.flight(), lod);
        }
        long now = minecraft.level == null ? Long.MIN_VALUE : minecraft.level.getGameTime();
        boolean changed = state.selectedWeapon() != payload.selectedWeapon() || !java.util.Objects.equals(state.impact(), payload.impact());
        if (changed) {
            previousImpact = state.selectedWeapon() == payload.selectedWeapon() && state.impact() != null
                && payload.impact() != null && state.impact().distanceToSqr(payload.impact()) < 128.0 * 128.0
                ? state.impact() : payload.impact();
            impactTick = now;
        }
        state = payload;
        receivedLevel = minecraft.level;
        receivedTick = receivedLevel == null ? Long.MIN_VALUE : receivedLevel.getGameTime();
    }

    public static @Nullable WeaponType selectedBomb() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientAimState.isActive() || minecraft.level == null || minecraft.level != receivedLevel
                || minecraft.level.getGameTime() < receivedTick || minecraft.level.getGameTime() - receivedTick > 10) return null;
        int selected = state.selectedWeapon();
        if (selected < 0 || selected >= WeaponType.values().length) return null;
        WeaponType type = WeaponType.values()[selected];
        return MianbaoBombCompat.isBomb(type) ? type : null;
    }

    static String weaponLine() {
        WeaponType type = selectedBomb();
        String category = Component.translatable("hud.create_fire_control.aircraft.bombs").getString();
        return type == null ? "  " + category : Component.translatable("hud.create_fire_control.aircraft.weapon_selected",
                category, type.displayName(), state.selectedAmmo(), state.totalAmmo()).getString();
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientVisualConfig.showAircraftHud() || selectedBomb() == null || minecraft.options.hideGui || minecraft.screen != null
                || ClientTelevisionGuidanceState.isActive()) return;
        GuiGraphics graphics = event.getGuiGraphics();
        // Aircraft with racks alone still need a weapon indication with their radar switched off.
        if (!ClientRadarState.isActive()) graphics.drawString(minecraft.font, weaponLine(), 8, 50,
                ClientVisualConfig.hudTextColor(0xEEF2F2F2), true);
    }

    @SubscribeEvent
    public static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!ClientVisualConfig.showBombIndicator()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (selectedBomb() == null || state.selectedAmmo() <= 0 || state.impact() == null
                || minecraft.options.hideGui || minecraft.screen != null || ClientTelevisionGuidanceState.isActive()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float interpolation = Mth.clamp((minecraft.level.getGameTime() - impactTick + partial) / 2.0F, 0, 1);
        Vec3 impact = previousImpact == null ? state.impact() : previousImpact.lerp(state.impact(), interpolation);
        Vec3 camera = event.getCamera().getPosition();
        double radius = BombSightScale.worldRadius(impact.add(0, BombSightGeometry.SURFACE_LIFT, 0).subtract(camera),
                event.getModelViewMatrix(), event.getProjectionMatrix(), (double) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight());
        if (radius <= 0 || !Double.isFinite(radius)) return;
        long now = minecraft.level.getGameTime();
        if (meshAnchor == null || meshTick != now || meshAnchor.distanceToSqr(impact) > 1 || Math.abs(radius - meshRadius) > meshRadius * 0.01) {
            meshAnchor = impact;
            meshRadius = radius;
            meshTick = now;
            mesh = BombSightGeometry.create(impact, radius, ClientBombSight::groundPoint);
        }
        // Follow camera/FOV changes every frame while retaining the tick-cached terrain samples.
        double scale = radius / meshRadius;
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack();
            pose.mulPose(event.getModelViewMatrix());
            var buffers = minecraft.renderBuffers().bufferSource();
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (var segment : mesh) {
                Vec3 from = impact.add(segment.from().subtract(meshAnchor).scale(scale)).subtract(camera);
                Vec3 to = impact.add(segment.to().subtract(meshAnchor).scale(scale)).subtract(camera);
                Vec3 normal = to.subtract(from).normalize();
                lines.addVertex(pose.last().pose(), (float) from.x, (float) from.y, (float) from.z)
                        .setColor(245, 245, 245, 240).setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
                lines.addVertex(pose.last().pose(), (float) to.x, (float) to.y, (float) to.z)
                        .setColor(245, 245, 245, 240).setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
            }
            buffers.endBatch(RenderType.lines());
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static Vec3 groundPoint(Vec3 point) {
        return groundPoint(Minecraft.getInstance().level, point);
    }

    static Vec3 groundPoint(@Nullable net.minecraft.world.level.Level level, Vec3 point) {
        // Loaded terrain bends the ring over slopes; LOD-only areas retain the predicted ground plane.
        if (level != null && level.hasChunkAt(BlockPos.containing(point))) {
            var context = new ClipContext(point.add(0, BombSightGeometry.SAMPLE_DEPTH, 0), point.add(0, -BombSightGeometry.SAMPLE_DEPTH, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
            // Sable's projected clip can return an aircraft hit in plot-local coordinates.
            ((ClipContextExtension) context).sable$setDoNotProject(true);
            var hit = level.clip(context);
            if (hit.getType() != HitResult.Type.MISS) return hit.getLocation().add(0, BombSightGeometry.SURFACE_LIFT, 0);
        }
        return point.add(0, BombSightGeometry.SURFACE_LIFT, 0);
    }

    private ClientBombSight() {}
}
