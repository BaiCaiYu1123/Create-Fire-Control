package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.iff.IffTransponderBlock;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Draws the live channel and same-channel transponder count on the front CRT. */
public final class IffTransponderRenderer
        implements BlockEntityRenderer<IffTransponderBlockEntity> {
    private static final float PIXEL_SCALE = 0.008F;
    private static final float SCREEN_WIDTH_PIXELS = 84.0F;
    private static final int TEXT_GREEN = 0xFF20FF20;
    private static final int FULL_BRIGHT = 15728880;
    private final Font font;

    public IffTransponderRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    @Override
    public void render(
            IffTransponderBlockEntity transponder,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        BlockState state = transponder.getBlockState();
        if (!state.hasProperty(IffTransponderBlock.FACING)) return;

        pose.pushPose();
        orient(pose, state.getValue(IffTransponderBlock.FACING));
        // The Blockbench screen spans approximately x=2.6..13.4 and
        // y=7..13.5. Start at its visual upper-left and use screen pixels.
        pose.translate(13.35 / 16.0, -2.65 / 16.0, 1.45 / 16.0);
        pose.scale(-PIXEL_SCALE, -PIXEL_SCALE, PIXEL_SCALE);
        Matrix4f matrix = pose.last().pose();

        String channel = transponder.getChannel();
        if (channel.isEmpty()) {
            long ticks = transponder.getLevel() == null
                    ? 0L : transponder.getLevel().getGameTime();
            // Windows' default caret blink is roughly half a second.
            if ((ticks / 11L) % 2L == 0L) {
                draw(Component.literal(">_"), 2.0F, 2.0F, matrix, buffers);
            }
        } else {
            drawCentered(Component.literal(channel), 2.0F, matrix, buffers);
        }
        drawCentered(Component.translatable("screen.create_fire_control.iff_transponder.friendly_count",
                        transponder.getFriendlyTransponderCount()),
                27.0F, matrix, buffers);
        pose.popPose();
    }

    private void drawCentered(
            Component text, float y, Matrix4f matrix, MultiBufferSource buffers) {
        float x = (SCREEN_WIDTH_PIXELS - font.width(text)) * 0.5F;
        draw(text, x, y, matrix, buffers);
    }

    private void draw(
            Component text, float x, float y,
            Matrix4f matrix, MultiBufferSource buffers) {
        font.drawInBatch(text, x, y, TEXT_GREEN, false, matrix, buffers,
                Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
    }

    private static void orient(PoseStack pose, Direction facing) {
        switch (facing) {
            case NORTH -> pose.translate(0.0, 1.0, -0.002);
            case SOUTH -> {
                pose.translate(1.0, 1.0, 1.002);
                pose.mulPose(new Quaternionf().rotationY((float) Math.PI));
            }
            case EAST -> {
                pose.translate(1.002, 1.0, 0.0);
                pose.mulPose(new Quaternionf().rotationY(-(float) Math.PI / 2.0F));
            }
            case WEST -> {
                pose.translate(-0.002, 1.0, 1.0);
                pose.mulPose(new Quaternionf().rotationY((float) Math.PI / 2.0F));
            }
            default -> {
            }
        }
    }
}
