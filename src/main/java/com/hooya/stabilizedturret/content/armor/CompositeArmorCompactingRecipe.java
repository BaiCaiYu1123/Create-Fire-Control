package com.hooya.stabilizedturret.content.armor;

import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import com.simibubi.create.content.kinetics.mixer.CompactingRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import net.minecraft.world.item.crafting.RecipeSerializer;

public final class CompositeArmorCompactingRecipe extends CompactingRecipe {
    public CompositeArmorCompactingRecipe(ProcessingRecipeParams params) {
        super(params);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModArmorUpgrades.COMPACTING.get();
    }
}
