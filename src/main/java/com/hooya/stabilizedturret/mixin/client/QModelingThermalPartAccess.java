package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "qwxon.qmodeling.block.entity.WorkspacePartData", remap = false)
public interface QModelingThermalPartAccess {
    @Invoker("isTransientForceDynamicRender") boolean createFireControl$thermalForceDynamic();
    @Invoker("isBillboard") boolean createFireControl$thermalBillboard();
    @Invoker("getBlockEntityData") CompoundTag createFireControl$thermalBlockEntityData();
    @Invoker("isBonePart") boolean createFireControl$thermalBone();
    @Invoker("isTextDisplayPart") boolean createFireControl$thermalText();
    @Invoker("isImageDisplayPart") boolean createFireControl$thermalImage();
    @Invoker("isItemDisplayPart") boolean createFireControl$thermalItem();
    @Invoker("isParticleEmitterPart") boolean createFireControl$thermalParticle();
    @Invoker("isSoundEmitterPart") boolean createFireControl$thermalSound();
}
