package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.loader.AzLoaderBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AzLoaderRenderer extends GeoBlockRenderer<AzLoaderBlockEntity> {
    private static final Map<String, Integer> SLOT_BONE_INDEX = new HashMap<>();
    private static final Set<String> CASING_BONES = Set.of(AzLoaderModel.CASING_BONES);

    static {
        for (int i = 0; i < AzLoaderModel.SHELL_BONES.length; i++) {
            SLOT_BONE_INDEX.put(AzLoaderModel.SHELL_BONES[i], i);
            SLOT_BONE_INDEX.put(AzLoaderModel.PROP_CHARGE_BONES[i], i);
        }
    }

    public AzLoaderRenderer(BlockEntityRendererProvider.Context context) {
        super(new AzLoaderModel());
    }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
    }

    @Override
    public void preRender(PoseStack poseStack, AzLoaderBlockEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
        Direction facing = animatable.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        float yaw = switch (facing) {
            case NORTH -> 180.0F;
            case SOUTH -> 0.0F;
            case WEST -> -90.0F;
            case EAST -> 90.0F;
            default -> 0.0F;
        };
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(-AzLoaderModel.CENTER_X / 16.0, 0.0,
                -AzLoaderModel.CENTER_Z / 16.0);
    }

    @Override
    public void renderRecursively(PoseStack poseStack, AzLoaderBlockEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick,
                                  int packedLight, int packedOverlay, int colour) {
        if (bone.getName().startsWith("Armor-piercing_projectile_placement")
                || bone.getName().startsWith("Placement_of_propellant")) {
            bone.setHidden(false);
        }
        Integer slot = SLOT_BONE_INDEX.get(bone.getName());
        if (slot != null && (animatable.getSlots() & (1 << slot)) == 0) {
            bone.setHidden(true);
        }
        if (CASING_BONES.contains(bone.getName())) {
            bone.setHidden(false);
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, colour);
    }
}
