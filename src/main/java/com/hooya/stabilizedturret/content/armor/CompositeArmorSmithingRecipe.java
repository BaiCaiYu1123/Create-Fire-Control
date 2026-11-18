package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.Copypoly;
import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import com.hooya.stabilizedturret.registry.ModItems;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

public final class CompositeArmorSmithingRecipe implements SmithingRecipe {
    private static final CompositeArmorSmithingRecipe INSTANCE = new CompositeArmorSmithingRecipe();

    public static final RecipeSerializer<CompositeArmorSmithingRecipe> SERIALIZER = new RecipeSerializer<>() {
        @Override
        public MapCodec<CompositeArmorSmithingRecipe> codec() {
            return MapCodec.unit(INSTANCE);
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CompositeArmorSmithingRecipe> streamCodec() {
            return StreamCodec.unit(INSTANCE);
        }
    };

    @Override
    public boolean matches(SmithingRecipeInput input, Level level) {
        return isTemplateIngredient(input.template()) && ArmorUpgrade.canUpgrade(input.base())
                && isAdditionIngredient(input.addition());
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
        return assemble(input, 1);
    }

    public ItemStack assemble(SmithingRecipeInput input, int plates) {
        if (!isTemplateIngredient(input.template()) || !isAdditionIngredient(input.addition())) {
            return ItemStack.EMPTY;
        }
        return ArmorUpgrade.upgraded(input.base(), plates);
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ArmorUpgrade.upgraded(Copypoly.ITEM.get().getDefaultInstance(), 1);
    }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return stack.isEmpty();
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return stack.is(Copypoly.ITEM.get());
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return stack.is(ModItems.COMPOSITE_ARMOR_PLATE.get());
    }

    @Override
    public boolean isIncomplete() {
        return false;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModArmorUpgrades.SMITHING.get();
    }
}
