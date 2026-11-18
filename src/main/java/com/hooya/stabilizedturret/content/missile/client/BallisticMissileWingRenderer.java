package com.hooya.stabilizedturret.content.missile.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Renders the ballistic engine body and its four independently hinged wings. */
public final class BallisticMissileWingRenderer {
    private static final ResourceLocation ORIGINAL_ENGINE = ResourceLocation.fromNamespaceAndPath(
            MianbaoBallisticMissileCompat.MOD_ID, "block/ballistic_missile_engine");
    private static final ResourceLocation BODY = model("ballistic_missile_engine_body");
    private static final ResourceLocation NORTH_WING = model("ballistic_missile_engine_wing_north");
    private static final ResourceLocation WEST_WING = model("ballistic_missile_engine_wing_west");
    private static final ResourceLocation SOUTH_WING = model("ballistic_missile_engine_wing_south");
    private static final ResourceLocation EAST_WING = model("ballistic_missile_engine_wing_east");
    private static final ResourceLocation[] MODELS = {
            BODY, NORTH_WING, WEST_WING, SOUTH_WING, EAST_WING
    };
    private static final String REMOTE_VISUAL_TAG = "CreateFireControlRemoteVisual";
    private static final float DEPLOY_TICKS = 16.0F;
    // Mianbao's launch profile reaches the 5.5-block rail clearance at age 84.
    private static final int DETACH_LAUNCH_AGE = 84;

    public static void register(IEventBus modBus) {
        modBus.addListener(BallisticMissileWingRenderer::registerModels);
    }

    private static void registerModels(ModelEvent.RegisterAdditional event) {
        for (ResourceLocation model : MODELS) {
            event.register(ModelResourceLocation.standalone(model));
        }
    }

    public static boolean isOriginalEngineModel(BakedModel model) {
        if (model == null) return false;
        BakedModel original = Minecraft.getInstance().getModelManager().getModel(
                ModelResourceLocation.standalone(ORIGINAL_ENGINE));
        return model == original;
    }

    /** The pose must already be translated into the original engine model's block space. */
    public static void render(
            PoseStack poseStack,
            MultiBufferSource buffer,
            BlockRenderDispatcher blockRenderer,
            int light,
            Entity missile,
            float partialTick
    ) {
        renderModel(poseStack, buffer, blockRenderer, BODY, light);

        float folded = 1.0F - deploymentProgress(missile, partialTick);
        renderWing(poseStack, buffer, blockRenderer, NORTH_WING, light,
                8.0F, 8.5F, -1.0F, Axis.XP, 90.0F * folded);
        renderWing(poseStack, buffer, blockRenderer, WEST_WING, light,
                -1.0F, 8.5F, 8.0F, Axis.ZP, -90.0F * folded);
        renderWing(poseStack, buffer, blockRenderer, SOUTH_WING, light,
                8.0F, 8.5F, 17.0F, Axis.XP, -90.0F * folded);
        renderWing(poseStack, buffer, blockRenderer, EAST_WING, light,
                17.0F, 8.5F, 8.0F, Axis.ZP, 90.0F * folded);
    }

    private static float deploymentProgress(Entity missile, float partialTick) {
        // Chunk-independent proxies only represent missiles already in flight.
        if (missile == null
                || missile.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) return 1.0F;

        MianbaoBallisticMissileCompat.Telemetry telemetry =
                MianbaoBallisticMissileCompat.telemetry(missile);
        if (telemetry != null && telemetry.docked()) return 0.0F;

        int launchAge = MianbaoBallisticMissileCompat.launchAge(missile);
        if (launchAge < 0) {
            // If an optional API changed, an undocked missile should remain usable visually.
            return telemetry == null ? 0.0F : 1.0F;
        }
        float linear = Mth.clamp(
                (launchAge + Mth.clamp(partialTick, 0.0F, 1.0F) - DETACH_LAUNCH_AGE)
                        / DEPLOY_TICKS,
                0.0F, 1.0F);
        return linear * linear * (3.0F - 2.0F * linear);
    }

    private static void renderWing(
            PoseStack poseStack,
            MultiBufferSource buffer,
            BlockRenderDispatcher blockRenderer,
            ResourceLocation model,
            int light,
            float pivotX,
            float pivotY,
            float pivotZ,
            Axis axis,
            float angle
    ) {
        poseStack.pushPose();
        poseStack.translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        poseStack.mulPose(axis.rotationDegrees(angle));
        poseStack.translate(-pivotX / 16.0F, -pivotY / 16.0F, -pivotZ / 16.0F);
        renderModel(poseStack, buffer, blockRenderer, model, light);
        poseStack.popPose();
    }

    private static void renderModel(
            PoseStack poseStack,
            MultiBufferSource buffer,
            BlockRenderDispatcher blockRenderer,
            ResourceLocation model,
            int light
    ) {
        BakedModel baked = Minecraft.getInstance().getModelManager().getModel(
                ModelResourceLocation.standalone(model));
        blockRenderer.getModelRenderer().renderModel(
                poseStack.last(), buffer.getBuffer(RenderType.cutout()), null,
                baked, 1.0F, 1.0F, 1.0F, light, 0);
    }

    private static ResourceLocation model(String path) {
        return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "block/" + path);
    }

    private BallisticMissileWingRenderer() {
    }
}
