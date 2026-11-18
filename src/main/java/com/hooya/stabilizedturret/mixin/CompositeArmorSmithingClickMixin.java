package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.Copypoly;
import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractContainerMenu.class)
public abstract class CompositeArmorSmithingClickMixin {
    @WrapOperation(method = "doClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;isSameItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean fireControl$takeOneUpgrade(ItemStack next, ItemStack moved, Operation<Boolean> original,
                                               @Local(argsOnly = true, ordinal = 0) int slot) {
        if ((Object) this instanceof SmithingMenu && slot == SmithingMenu.RESULT_SLOT
                && moved.is(Copypoly.ITEM.get()) && moved.has(ModArmorUpgrades.ARMOR_LEVEL.get())) {
            return false;
        }
        return original.call(next, moved);
    }
}
