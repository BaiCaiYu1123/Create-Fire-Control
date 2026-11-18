package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Gui.class)
public interface GuiOverlayMessageAccessor {
    @Accessor("overlayMessageString")
    Component stabilizedTurret$getOverlayMessage();

    @Accessor("overlayMessageTime")
    int stabilizedTurret$getOverlayMessageTime();
}
