package com.hooya.stabilizedturret.client.compat.mianbao;

import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCarrier;
import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Draws fractional-offset attachments in the cannon's own transform. */
public final class MountedHeavyMachineGunRenderer {
    /** A lateral reflection reverses quad winding, so the mirrored copy cannot use CULL. */
    private static final RenderType MIRRORED_SOLID = RenderType.create(
            "create_fire_control_mirrored_mounted_heavy_machine_gun",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            RenderType.BIG_BUFFER_SIZE,
            true,
            false,
            RenderType.CompositeState.builder()
                    .setLightmapState(RenderType.LIGHTMAP)
                    .setShaderState(RenderType.RENDERTYPE_SOLID_SHADER)
                    .setTextureState(RenderType.BLOCK_SHEET_MIPPED)
                    .setCullState(RenderType.NO_CULL)
                    .createCompositeState(true));

    public static void render(
            PitchOrientedContraptionEntity entity,
            ContraptionMatrices matrices,
            MultiBufferSource buffers
    ) {
        if (!(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)
                || !(cannon instanceof MountedHeavyMachineGunCarrier carrier)) return;
        Direction forward = cannon.initialOrientation();
        if (forward == null || !forward.getAxis().isHorizontal()) return;
        Block block = BuiltInRegistries.BLOCK.get(
                MountedHeavyMachineGunCompat.HEAVY_MACHINE_GUN);
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, forward);
        }
        int light = LevelRenderer.getLightColor(entity.level(), entity.blockPosition());
        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            MountedHeavyMachineGunCarrier.Attachment attachment =
                    carrier.createFireControl$getMountedHeavyMachineGun(side);
            if (attachment == null) continue;
            Vec3 center = MountedHeavyMachineGunCompat.localGunCenter(
                    cannon, side, attachment);
            SuperByteBuffer model = CachedBuffers.block(state);
            model.transform(matrices.getModel())
                    .translate(center.x - 0.5D, center.y - 0.5D, center.z - 0.5D);
            if (side == MountedHeavyMachineGunCarrier.Side.RIGHT) {
                model.center();
                if (forward.getAxis() == Direction.Axis.Z) {
                    model.scale(-1.0F, 1.0F, 1.0F);
                } else {
                    model.scale(1.0F, 1.0F, -1.0F);
                }
                model.uncenter();
            }
            model.light(light)
                    .useLevelLight(entity.level(), matrices.getWorld())
                    .renderInto(
                            matrices.getViewProjection(),
                            buffers.getBuffer(side == MountedHeavyMachineGunCarrier.Side.RIGHT
                                    ? MIRRORED_SOLID : RenderType.solid()));
        }
    }

    private MountedHeavyMachineGunRenderer() {
    }
}
