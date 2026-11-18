package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.armor.ArmorUpgrade;
import com.hooya.stabilizedturret.content.armor.CompositeArmorSmithingRecipe;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SmithingMenu.class)
public abstract class CompositeArmorSmithingMenuMixin extends ItemCombinerMenu {
    @Shadow private RecipeHolder<SmithingRecipe> selectedRecipe;

    protected CompositeArmorSmithingMenuMixin(MenuType<?> type, int id, Inventory inventory, ContainerLevelAccess access) {
        super(type, id, inventory, access);
    }

    @WrapOperation(method = "createResult", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/SmithingRecipe;assemble(Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fireControl$previewBatch(SmithingRecipe recipe, RecipeInput input,
                                                HolderLookup.Provider registries, Operation<ItemStack> original) {
        if (recipe instanceof CompositeArmorSmithingRecipe upgrade && input instanceof SmithingRecipeInput smithing) {
            return upgrade.assemble(smithing, smithing.addition().getCount());
        }
        return original.call(recipe, input, registries);
    }

    @Inject(method = "onTake", at = @At("HEAD"))
    private void fireControl$countPlates(Player player, ItemStack result, CallbackInfo ci,
                                         @Share("platesUsed") LocalIntRef platesUsed) {
        if (selectedRecipe != null && selectedRecipe.value() instanceof CompositeArmorSmithingRecipe) {
            platesUsed.set(ArmorUpgrade.platesUsed(inputSlots.getItem(SmithingMenu.BASE_SLOT),
                    inputSlots.getItem(SmithingMenu.ADDITIONAL_SLOT).getCount()));
        }
    }

    @WrapOperation(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/SmithingMenu;shrinkStackInSlot(I)V"))
    private void fireControl$consumeBatch(SmithingMenu menu, int slot, Operation<Void> original,
                                           @Share("platesUsed") LocalIntRef platesUsed) {
        if (slot == SmithingMenu.ADDITIONAL_SLOT && platesUsed.get() > 0) {
            ItemStack plates = inputSlots.getItem(slot);
            plates.shrink(platesUsed.get());
            inputSlots.setItem(slot, plates.isEmpty() ? ItemStack.EMPTY : plates);
        } else {
            original.call(menu, slot);
        }
    }
}
