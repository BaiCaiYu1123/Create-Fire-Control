package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.Copypoly;
import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;

public final class ArmorUpgrade {
    public static final int DEFAULT_LEVEL = 1;
    public static final int MAX_LEVEL = 100;
    public static final String LEVEL_TAG = "FireControlArmorLevel";

    private ArmorUpgrade() {
    }

    public static int clamp(int level) {
        return Mth.clamp(level, DEFAULT_LEVEL, MAX_LEVEL);
    }

    public static int level(ItemStack armor) {
        return clamp(armor.getOrDefault(ModArmorUpgrades.ARMOR_LEVEL.get(), DEFAULT_LEVEL));
    }

    public static int level(BlockGetter world, BlockPos pos) {
        return world != null && world.getBlockEntity(pos) instanceof PolyArmorAccess armor
                ? armor.fireControl$armorLevel() : DEFAULT_LEVEL;
    }

    public static void writeLevel(ItemStack armor, int level) {
        if (level <= DEFAULT_LEVEL) {
            armor.remove(ModArmorUpgrades.ARMOR_LEVEL.get());
        } else {
            armor.set(ModArmorUpgrades.ARMOR_LEVEL.get(), clamp(level));
        }
    }

    public static boolean canUpgrade(ItemStack armor) {
        return armor.is(Copypoly.ITEM.get()) && level(armor) < MAX_LEVEL;
    }

    public static int platesUsed(ItemStack armor, int available) {
        return canUpgrade(armor) ? Mth.clamp(available, 0, MAX_LEVEL - level(armor)) : 0;
    }

    public static ItemStack upgraded(ItemStack armor, int plates) {
        int used = platesUsed(armor, plates);
        if (used == 0) return ItemStack.EMPTY;
        ItemStack result = armor.copyWithCount(1);
        writeLevel(result, level(armor) + used);
        return result;
    }
}
