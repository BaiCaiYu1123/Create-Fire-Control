package com.hooya.stabilizedturret.content.armor;

import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import net.minecraft.world.item.crafting.RecipeSerializer;

public final class CompositeArmorMixingRecipe extends MixingRecipe {
    public CompositeArmorMixingRecipe(ProcessingRecipeParams params) {
        super(params);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModArmorUpgrades.MIXING.get();
    }
}
