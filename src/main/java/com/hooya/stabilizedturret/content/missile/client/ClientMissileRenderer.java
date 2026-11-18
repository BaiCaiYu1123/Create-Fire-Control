package com.hooya.stabilizedturret.content.missile.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import com.hooya.stabilizedturret.client.renderer.Df26BallisticMissileEntityVisualRenderer;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.content.missile.client.depth.SceneDepthProvider;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Lightweight world renderer that remains independent of entity tracking. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientMissileRenderer {
    private static final ResourceLocation[] MIANBAO_MODELS = {
            mianbaoModel("ballistic_missile_engine"),
            mianbaoModel("ballistic_missile_tail"),
            mianbaoModel("ballistic_missile_middle"),
            mianbaoModel("ballistic_missile_upper"),
            mianbaoModel("ballistic_missile_head")
    };
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        // AFTER_LEVEL has an empty event pose and LevelRenderer has already
        // popped its camera rotation. Apply the supplied view exactly once.
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            renderWorld(event, minecraft);
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    static PoseStack cameraPose(Matrix4f view) {
        PoseStack pose = new PoseStack();
        pose.mulPose(view);
        return pose;
    }

    private static void renderWorld(RenderLevelStageEvent event, Minecraft minecraft) {
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = cameraPose(event.getModelViewMatrix());
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        SceneDepthProvider depth = SceneDepthProvider.current();
        float partialTick = Mth.clamp(
                event.getPartialTick().getGameTimeDeltaTicks(), 0.0F, 1.0F);

        for (ClientMissileManager.Missile missile : ClientMissileManager.missiles()) {
            Vec3 world = missile.position();
            double distance = world.distanceTo(camera);
            if (distance > ClientMissileConfig.MAX_DISTANCE.get()
                    || depth.isOccluded(world)) continue;
            int alpha = visualAlpha(minecraft, world);
            if (distance < ClientMissileConfig.LOD_NEAR.get()) {
                renderNativeModel(minecraft, buffers, poseStack, world.subtract(camera),
                        missile.direction().normalize(), null, partialTick);
            }
            // cutout and lines share a fallback buffer. Switching to the model
            // ends the old line builder, so acquire it only after the model.
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            drawTrail(lines, poseStack, camera, missile.trail(), alpha);
            drawMissile(lines, poseStack, camera, world,
                    missile.direction(), distance, alpha);
        }

        // Native ballistic proxies are intentionally absent from ClientLevel's
        // chunk entity sections. Render the same five Mianbao model parts from
        // their smoothly corrected world state so crossing the player's view
        // boundary cannot remove and recreate the visual.
        for (Entity missile : MissileLongRangeEffects.detachedBallisticVisuals()) {
            Vec3 world = new Vec3(
                    Mth.lerp(partialTick, missile.xo, missile.getX()),
                    Mth.lerp(partialTick, missile.yo, missile.getY()),
                    Mth.lerp(partialTick, missile.zo, missile.getZ()));
            if (world.distanceTo(camera) > ClientMissileConfig.MAX_DISTANCE.get()) continue;
            Vec3 velocity = missile.getDeltaMovement();
            Vec3 direction = velocity.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 1.0, 0.0) : velocity.normalize();
            Vec3 cameraRelative = world.subtract(camera);
            if (com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance.isOrdinaryType(missile.getType())) {
                renderOrdinaryAa(minecraft, missile, cameraRelative, poseStack, buffers, partialTick);
            } else if (MissileLongRangeEffects.isDf26Visual(missile)) {
                Df26BallisticMissileEntityVisualRenderer.render(
                        missile, cameraRelative.x, cameraRelative.y, cameraRelative.z,
                        poseStack, buffers, LightTexture.FULL_BRIGHT);
            } else {
                renderNativeModel(minecraft, buffers, poseStack,
                        cameraRelative, direction, missile, partialTick);
            }
        }

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        long now = minecraft.level.getGameTime();
        for (ClientMissileManager.Flare flare : ClientMissileManager.flares()) {
            Vec3 world = flare.position(now);
            if (world.distanceTo(camera) > ClientMissileConfig.FLARE_DISTANCE.get()
                    || depth.isOccluded(world)) continue;
            int alpha = visualAlpha(minecraft, world);
            drawFlare(lines, poseStack, camera, flare, world, now, alpha);
        }
        buffers.endBatch(RenderType.lines());
        buffers.endBatch(RenderType.cutout());
    }

    private static void renderOrdinaryAa(Minecraft minecraft, Entity missile, Vec3 relative,
                                        PoseStack pose, MultiBufferSource.BufferSource buffers, float partialTick) {
        // Extend only this draw's far plane. Keep the native entity transform
        // and angular size intact, including shader integrations.
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        double farPlane = projection.m32() / (projection.m22() + 1.0);
        double distance = relative.length();
        boolean extendProjection = Double.isFinite(farPlane) && farPlane > 1 && distance + 32 > farPlane;
        if (extendProjection) {
            // The terrain depth buffer uses the original projection. Check its
            // loaded foreground explicitly before drawing beyond that range.
            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            Vec3 foregroundEnd = camera.add(relative.scale(Math.min(1, farPlane / Math.max(1, distance))));
            if (minecraft.level.clip(new net.minecraft.world.level.ClipContext(camera, foregroundEnd,
                    net.minecraft.world.level.ClipContext.Block.VISUAL,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, minecraft.player))
                    .getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        }
        buffers.endBatch(); // Finish other missiles before applying this round's fog policy.
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        pose.pushPose();
        try {
            if (extendProjection) {
                double near = projection.m32() / (projection.m22() - 1.0);
                double far = distance + 64;
                Matrix4f extended = new Matrix4f(projection)
                        .m22((float) (-(far + near) / (far - near)))
                        .m32((float) (-2 * far * near / (far - near)));
                RenderSystem.setProjectionMatrix(extended, RenderSystem.getVertexSorting());
            }
            RenderSystem.setShaderFogStart(1_000_000);
            RenderSystem.setShaderFogEnd(2_000_000);
            minecraft.getEntityRenderDispatcher().render(missile,
                    relative.x, relative.y, relative.z, missile.getYRot(), partialTick,
                    pose, extendProjection ? type -> buffers.getBuffer(DistantOrdinaryAaRenderType.TYPE) : buffers,
                    LightTexture.FULL_BRIGHT);
            if (extendProjection) {
                // Native particle positions beyond the far plane cannot be
                // drawn; retain a short exhaust segment at the same true scale.
                Vec3 tail = relative.subtract(missile.getDeltaMovement().scale(.75));
                line(buffers.getBuffer(RenderType.lines()), pose, relative, tail, 255, 175, 65, 220);
            }
            buffers.endBatch(); // Draw while the projectile's fog policy is active.
        } finally {
            if (extendProjection) RenderSystem.setProjectionMatrix(projection, RenderSystem.getVertexSorting());
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            pose.popPose();
        }
    }

    /** Foreground visibility was checked before extending the terrain projection. */
    private static final class DistantOrdinaryAaRenderType extends RenderType {
        private static final RenderType TYPE = create("create_fire_control_ordinary_aa_remote",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                        .setTextureState(new TextureStateShard(ResourceLocation.fromNamespaceAndPath(
                                "mianbaos_modernwarfare", "textures/entities/dan_bing_fang_kong_dao_dan_shi_ti_.png"), false, false))
                        .setDepthTestState(NO_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                        .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                        .createCompositeState(false));

        private DistantOrdinaryAaRenderType() {
            super("ordinary_aa_remote", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                    256, false, false, () -> {}, () -> {});
        }
    }

    private static int visualAlpha(Minecraft minecraft, Vec3 world) {
        if (!ClientMissileConfig.UNKNOWN_LOW_ALTITUDE_FADE.get()
                || world.y >= ClientMissileConfig.LOW_ALTITUDE.get()
                || minecraft.level == null
                || minecraft.level.hasChunkAt(BlockPos.containing(world))) return 255;
        return 128;
    }

    private static void drawTrail(VertexConsumer consumer, PoseStack poseStack, Vec3 camera,
                                  List<Vec3> trail, int alpha) {
        if (trail.size() < 2) return;
        poseStack.pushPose();
        int segments = trail.size() - 1;
        for (int index = 1; index < trail.size(); index++) {
            Vec3 from = trail.get(index - 1).subtract(camera);
            Vec3 to = trail.get(index).subtract(camera);
            int segmentAlpha = Math.max(12, alpha * index / Math.max(1, segments));
            line(consumer, poseStack, from, to,
                    255, 70 + 120 * index / Math.max(1, segments), 20, segmentAlpha);
        }
        poseStack.popPose();
    }

    private static void drawMissile(VertexConsumer consumer, PoseStack poseStack, Vec3 camera,
                                    Vec3 world, Vec3 direction, double distance, int alpha) {
        Vec3 center = world.subtract(camera);
        Vec3 normalized = direction.lengthSqr() < 1.0e-8
                ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        double length = distance < ClientMissileConfig.LOD_NEAR.get() ? 2.2
                : distance < ClientMissileConfig.LOD_MID.get() ? 1.2 : 0.55;
        Vec3 nose = center.add(normalized.scale(length * 0.65));
        Vec3 tail = center.subtract(normalized.scale(length * 0.35));
        poseStack.pushPose();
        line(consumer, poseStack, tail, nose, 255, 210, 120, alpha);
        Vec3 side = normalized.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() < 1.0e-8) side = new Vec3(1.0, 0.0, 0.0);
        side = side.normalize().scale(distance < ClientMissileConfig.LOD_NEAR.get() ? 0.32 : 0.12);
        line(consumer, poseStack, center.subtract(side), center.add(side),
                255, 90, 20, alpha);
        poseStack.popPose();
    }

    /** Draws the five original Mianbao baked model parts without an Entity. */
    private static void renderNativeModel(Minecraft minecraft,
                                          MultiBufferSource.BufferSource buffers,
                                          PoseStack poseStack, Vec3 center, Vec3 direction,
                                          Entity missile, float partialTick) {
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        Quaternionf rotation = MianbaoBallisticMissileCompat.remoteRenderRotation(missile);
        if (rotation == null && missile != null) {
            rotation = MianbaoBallisticMissileCompat.renderRotation(missile);
        }
        if (rotation == null) {
            rotation = new Quaternionf().rotationTo(
                    new Vector3f(0.0F, 1.0F, 0.0F),
                    new Vector3f((float) direction.x, (float) direction.y,
                            (float) direction.z));
        }
        poseStack.pushPose();
        poseStack.translate(center.x, center.y, center.z);
        poseStack.mulPose(rotation);
        for (int part = 0; part < MIANBAO_MODELS.length; part++) {
            poseStack.pushPose();
            poseStack.translate(-0.5D, part - 0.5D, -0.5D - 3.0D / 16.0D);
            if (part == 0) {
                BallisticMissileWingRenderer.render(
                        poseStack, buffers, blockRenderer, LightTexture.FULL_BRIGHT,
                        missile, partialTick);
            } else {
                BakedModel model = minecraft.getModelManager().getModel(
                        ModelResourceLocation.standalone(MIANBAO_MODELS[part]));
                blockRenderer.getModelRenderer().renderModel(
                        poseStack.last(), buffers.getBuffer(RenderType.cutout()), null,
                        model, 1.0F, 1.0F, 1.0F, LightTexture.FULL_BRIGHT, 0);
            }
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static ResourceLocation mianbaoModel(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                "mianbaos_modernwarfare", "block/" + name);
    }

    private static void drawFlare(VertexConsumer consumer, PoseStack poseStack, Vec3 camera,
                                  ClientMissileManager.Flare flare, Vec3 world,
                                  long now, int alpha) {
        Vec3 current = world.subtract(camera);
        Vec3 previous = flare.position(Math.max(flare.birth(), now - 2L)).subtract(camera);
        poseStack.pushPose();
        line(consumer, poseStack, previous, current, 255, 70, 10, Math.max(32, alpha / 2));
        line(consumer, poseStack, current.add(-0.16, 0.0, 0.0),
                current.add(0.16, 0.0, 0.0), 255, 210, 80, alpha);
        poseStack.popPose();
    }

    private static void line(VertexConsumer consumer, PoseStack poseStack, Vec3 from, Vec3 to,
                             int red, int green, int blue, int alpha) {
        Vec3 normal = to.subtract(from);
        if (normal.lengthSqr() < 1.0e-10) return;
        normal = normal.normalize();
        consumer.addVertex(poseStack.last().pose(), (float) from.x, (float) from.y, (float) from.z)
                .setColor(red, green, blue, alpha)
                .setNormal(poseStack.last(), (float) normal.x, (float) normal.y, (float) normal.z);
        consumer.addVertex(poseStack.last().pose(), (float) to.x, (float) to.y, (float) to.z)
                .setColor(red, green, blue, alpha)
                .setNormal(poseStack.last(), (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private ClientMissileRenderer() {}
}
