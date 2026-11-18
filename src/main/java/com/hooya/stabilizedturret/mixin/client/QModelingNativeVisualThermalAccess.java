package com.hooya.stabilizedturret.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "qwxon.qmodeling.client.render.WorkspaceNativeVisual", remap = false)
public interface QModelingNativeVisualThermalAccess {
    @Invoker("visible")
    void createFireControl$setThermalVisible(boolean visible);
}
