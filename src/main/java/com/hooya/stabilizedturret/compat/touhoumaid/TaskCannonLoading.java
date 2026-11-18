package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public final class TaskCannonLoading implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "loading");
    private static final ResourceLocation CARTRIDGE_ID = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "apfsds_medium_cannon_cartridge");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        if (!BuiltInRegistries.ITEM.containsKey(CARTRIDGE_ID)) return Items.AIR.getDefaultInstance();
        return BuiltInRegistries.ITEM.get(CARTRIDGE_ID).getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // The actual behavior is a conditional CORE behavior so the maid remains stationary
        // during idle/rest schedule periods as well as during normal work periods.
        return Collections.emptyList();
    }

    @Override
    public boolean isEnable(EntityMaid maid) {
        return ModList.get().isLoaded("cbcmodernwarfare")
                && BuiltInRegistries.ITEM.containsKey(CARTRIDGE_ID)
                && BuiltInRegistries.ITEM.get(CARTRIDGE_ID) != Items.AIR;
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false;
    }

    @Override
    public boolean enableEating(EntityMaid maid) {
        return false;
    }

    @Override
    public float searchRadius(EntityMaid maid) {
        return 5.0F;
    }

    public String getMaidActionSummary() {
        return "Sit on the Create seat nearest the closest loaded depot, then automatically load compatible ammunition";
    }
}
