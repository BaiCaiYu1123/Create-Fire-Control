package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.ArmorUpgrade;
import com.hooya.stabilizedturret.content.armor.CompositeArmorCompactingRecipe;
import com.hooya.stabilizedturret.content.armor.CompositeArmorMixingRecipe;
import com.hooya.stabilizedturret.content.armor.CompositeArmorSmithingRecipe;
import com.mojang.serialization.Codec;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModArmorUpgrades {
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, StabilizedTurret.MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPES =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, StabilizedTurret.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ARMOR_LEVEL =
            COMPONENTS.registerComponentType("armor_level", builder -> builder
                    .persistent(Codec.intRange(ArmorUpgrade.DEFAULT_LEVEL, ArmorUpgrade.MAX_LEVEL))
                    .networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CompositeArmorSmithingRecipe>> SMITHING =
            RECIPES.register("composite_armor_smithing", () -> CompositeArmorSmithingRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CompositeArmorMixingRecipe>> MIXING =
            RECIPES.register("composite_armor_mixing",
                    () -> new StandardProcessingRecipe.Serializer<>(CompositeArmorMixingRecipe::new));
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CompositeArmorCompactingRecipe>> COMPACTING =
            RECIPES.register("composite_armor_compacting",
                    () -> new StandardProcessingRecipe.Serializer<>(CompositeArmorCompactingRecipe::new));

    private ModArmorUpgrades() {
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        RECIPES.register(bus);
    }
}
