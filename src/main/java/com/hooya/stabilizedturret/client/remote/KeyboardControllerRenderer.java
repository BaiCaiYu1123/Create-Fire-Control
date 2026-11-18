package com.hooya.stabilizedturret.client.remote;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.*;
import com.hooya.stabilizedturret.content.remote.ControllerSettings;

public final class KeyboardControllerRenderer extends BlockEntityWithoutLevelRenderer {
    public KeyboardControllerRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                       MultiBufferSource buffer, int light, int overlay) {
        boolean held = context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        boolean enabled = held && WirelessControllerClient.activeFor(stack);
        double time=held?WirelessControllerClient.modelFoldTime(stack):KeyboardControllerModel.animationLength();
        KeyboardControllerModel.renderAt(pose, buffer, light, overlay, time,
                enabled&&WirelessControllerClient.modelElapsedSeconds()>=KeyboardControllerModel.animationLength(),
                WirelessControllerClient.modelPressed(),ControllerSettings.color(ControllerSettings.data(stack)));
    }
}
