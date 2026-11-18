package com.hooya.stabilizedturret.content.armor;
import net.minecraft.world.phys.BlockHitResult;
public interface EquivalentArmorStopAccess {
    void fireControl$pinStop(BlockHitResult hit);
    void fireControl$clearStop();
}
