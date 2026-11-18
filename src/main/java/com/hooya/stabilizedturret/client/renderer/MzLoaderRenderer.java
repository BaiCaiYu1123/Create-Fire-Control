package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.loader.MzLoaderBlock;
import com.hooya.stabilizedturret.content.loader.MzLoaderBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class MzLoaderRenderer extends GeoBlockRenderer<MzLoaderBlockEntity> {
    /** Map shell/propellant bone name -> rack slot index, for render-time hiding. */
    private static final Map<String, Integer> SLOT_BONE_INDEX = new HashMap<>();

    static {
        for (int i = 0; i < MzLoaderModel.SHELL_BONES.length; i++) {
            SLOT_BONE_INDEX.put(MzLoaderModel.SHELL_BONES[i], i);
            SLOT_BONE_INDEX.put(MzLoaderModel.PROP_CHARGE_BONES[i], i);
        }
    }

    public MzLoaderRenderer(BlockEntityRendererProvider.Context context) {
        super(new MzLoaderModel());
    }

    /** GeckoLib's facing rotation is handled manually in preRender (around the model's center). */
    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
    }

    @Override
    public void preRender(PoseStack poseStack, MzLoaderBlockEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
        // Rotate the model around the lower roulette's center, then place that center on the
        // block's horizontal center. The protruding rear lift is intentionally ignored.
        // GeckoLib already put the model origin at the block center, so
        // mulPose + translate(-CENTER/16) maps model point v to R(v - CENTER/16) + (0.5, 0.5),
        // which keeps the model's middle exactly on the collision box for every placement direction.
        Direction facing = animatable.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        float yaw = switch (facing) {
            case NORTH -> 180.0F;
            case SOUTH -> 0.0F;
            case WEST -> -90.0F;
            case EAST -> 90.0F;
            default -> 0.0F;
        };
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(-MzLoaderModel.CENTER_X / 16.0, 0.0, -MzLoaderModel.CENTER_Z / 16.0);
    }

    /**
     * Force-hide shell bodies / propellants of empty slots at the moment they are rendered.
     * This cannot be undone by GeckoLib's bone snapshot resets after an animation finishes.
     */
    @Override
    public void renderRecursively(PoseStack poseStack, MzLoaderBlockEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick,
                                  int packedLight, int packedOverlay, int colour) {
        Integer slot = SLOT_BONE_INDEX.get(bone.getName());
        if (slot != null && (animatable.getSlots() & (1 << slot)) == 0) {
            bone.setHidden(true);
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, colour);
    }
}
