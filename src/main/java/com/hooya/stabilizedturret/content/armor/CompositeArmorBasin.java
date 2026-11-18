package com.hooya.stabilizedturret.content.armor;

import com.hooya.stabilizedturret.registry.ModItems;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

public final class CompositeArmorBasin {
    private CompositeArmorBasin() {
    }

    public static boolean process(BasinBlockEntity basin, HeatCondition heat, boolean simulate) {
        var level = basin.getLevel();
        if (level == null || !basin.canContinueProcessing()) return false;
        var heatLevel = BasinBlockEntity.getHeatLevelOf(level.getBlockState(basin.getBlockPos().below()));
        if (!heat.testBlazeBurner(heatLevel)) return false;

        var input = basin.getInputInventory();
        int plateSlot = findPlateSlot(input);
        if (plateSlot < 0) return false;

        for (int slot = 0; slot < input.getSlots(); slot++) {
            ItemStack armor = input.extractItem(slot, 1, true);
            ItemStack upgraded = ArmorUpgrade.upgraded(armor, 1);
            if (upgraded.isEmpty() || !basin.getFilter().test(upgraded)) continue;

            var output = List.of(upgraded);
            if (!basin.acceptOutputs(output, List.of(), true)) continue;
            if (simulate) return true;
            if (!basin.acceptOutputs(output, List.of(), false)) return false;

            input.extractItem(slot, 1, false);
            input.extractItem(plateSlot, 1, false);
            basin.notifyChangeOfContents();
            basin.setChanged();
            basin.sendData();
            return true;
        }
        return false;
    }

    private static int findPlateSlot(IItemHandler inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.extractItem(slot, 1, true).is(ModItems.COMPOSITE_ARMOR_PLATE.get())) return slot;
        }
        return -1;
    }
}
