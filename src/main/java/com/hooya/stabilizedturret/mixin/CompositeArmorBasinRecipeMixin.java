package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.armor.CompositeArmorBasin;
import com.hooya.stabilizedturret.content.armor.CompositeArmorCompactingRecipe;
import com.hooya.stabilizedturret.content.armor.CompositeArmorMixingRecipe;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BasinRecipe.class, remap = false)
public abstract class CompositeArmorBasinRecipeMixin {
    @Inject(method = "match", at = @At("HEAD"), cancellable = true)
    private static void fireControl$matchUpgrade(BasinBlockEntity basin, Recipe<?> recipe,
                                                 CallbackInfoReturnable<Boolean> result) {
        fireControl$processUpgrade(basin, recipe, result, true);
    }

    @Inject(method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void fireControl$applyUpgrade(BasinBlockEntity basin, Recipe<?> recipe,
                                                CallbackInfoReturnable<Boolean> result) {
        fireControl$processUpgrade(basin, recipe, result, false);
    }

    @Unique
    private static void fireControl$processUpgrade(BasinBlockEntity basin, Recipe<?> recipe,
                                                  CallbackInfoReturnable<Boolean> result, boolean simulate) {
        if (recipe instanceof BasinRecipe upgrade
                && (upgrade instanceof CompositeArmorCompactingRecipe || upgrade instanceof CompositeArmorMixingRecipe)) {
            result.setReturnValue(CompositeArmorBasin.process(basin, upgrade.getRequiredHeat(), simulate));
        }
    }
}
