package com.hooya.stabilizedturret.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;

@Pseudo
@Mixin(targets = "qwxon.qmodeling.block.entity.WorkspaceBlockEntity", remap = false)
public interface QModelingThermalWorkspaceAccess {
    @Invoker("getFlywheelRenderRevision") long createFireControl$thermalRevision();
    @Invoker("getRenderableAnimationSlot") int createFireControl$thermalAnimationSlot();
    @Invoker("isAnimationPlaying") boolean createFireControl$thermalAnimationPlaying();
    @Invoker("getRenderedPartsView") List<?> createFireControl$thermalParts();
}
