package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Common server-side contract for loaders that require a specific assembled breech. */
public interface CannonAutoLoader {
    @Nullable Level getLevel();

    BlockPos getBlockPos();

    boolean isRemoved();

    boolean isBusy();

    int getSlotCount();

    int getApfsdsCount();

    ItemStack getNextCartridge();

    @Nullable BindingRef getCannonMountBinding();

    void bindCannonMount(BindingRef binding);

    boolean startAutomaticCycle(UUID cannonId);

    boolean startAutomaticCycle(UUID cannonId, int openDelayTicks);

    ResourceLocation requiredBreechId();

    boolean beginDetonation();

    void cancelDetonation();
}
