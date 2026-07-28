package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.cannon.MediumCannonBreechBlock;
import com.hooya.stabilizedturret.content.cannon.MediumCannonBreechBlockEntity;
import com.hooya.stabilizedturret.content.cannon.compat.YuushyaMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsCartridgeItem;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsRoundItem;
import com.yuushya.modelling.block.blockstate.YuushyaBlockStates;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlockItem;

public final class ModCbcContent {
    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(StabilizedTurret.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(StabilizedTurret.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, StabilizedTurret.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, StabilizedTurret.MOD_ID);

    public static final DeferredBlock<MediumCannonBreechBlock> MEDIUM_CANNON_BREECH = BLOCKS.register(
            "medium_cannon_breech",
            () -> new MediumCannonBreechBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(5.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<YuushyaMediumCannonBarrelBlock> YUUSHYA_MEDIUM_CANNON_BARREL =
            ModList.get().isLoaded("yuushya_modelling")
                    ? BLOCKS.register("yuushya_medium_cannon_barrel", () ->
                    new YuushyaMediumCannonBarrelBlock(BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .forceSolidOn()
                            .strength(4.0F)
                            .lightLevel(state -> state.getValue(YuushyaBlockStates.LIT))))
                    : null;

    public static final DeferredItem<MediumcannonBlockItem<MediumCannonBreechBlock>> MEDIUM_CANNON_BREECH_ITEM =
            ITEMS.register("medium_cannon_breech", () ->
                    new MediumcannonBlockItem<>(MEDIUM_CANNON_BREECH.get(), new Item.Properties()));

    public static final DeferredItem<StabilizedApfsdsRoundItem> APFSDS_MEDIUM_CANNON_ROUND =
            ITEMS.register("apfsds_medium_cannon_round", () ->
                    new StabilizedApfsdsRoundItem(new Item.Properties()));

    public static final DeferredItem<StabilizedApfsdsCartridgeItem> APFSDS_MEDIUM_CANNON_CARTRIDGE =
            ITEMS.register("apfsds_medium_cannon_cartridge", () ->
                    new StabilizedApfsdsCartridgeItem(new Item.Properties()));

    public static final DeferredItem<Item> APFSDS_MEDIUM_CANNON_CASING =
            ITEMS.register("apfsds_medium_cannon_casing", () ->
                    new Item(new Item.Properties()));

    public static final DeferredHolder<EntityType<?>, EntityType<StabilizedApfsdsProjectile>>
            APFSDS_MEDIUM_CANNON_PROJECTILE = ENTITY_TYPES.register(
                    "apfsds_medium_shell",
                    () -> {
                        EntityType<StabilizedApfsdsProjectile> type =
                                EntityType.Builder.<StabilizedApfsdsProjectile>of(
                                                StabilizedApfsdsProjectile::new,
                                                MobCategory.MISC
                                        )
                                        .sized(0.2F, 0.2F)
                                        .fireImmune()
                                        .clientTrackingRange(16)
                                        .updateInterval(1)
                                        .build(StabilizedTurret.MOD_ID + ":apfsds_medium_shell");
                        return type;
                    }
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MediumCannonBreechBlockEntity>>
            MEDIUM_CANNON_BREECH_BLOCK_ENTITY = BLOCK_ENTITIES.register(
                    "medium_cannon_breech",
                    () -> BlockEntityType.Builder.of(
                            MediumCannonBreechBlockEntity::new,
                            MEDIUM_CANNON_BREECH.get()
                    ).build(null)
            );

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITY_TYPES.register(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.hooya.stabilizedturret.client.MediumCannonBreechClient.register(modBus);
            if (ModList.get().isLoaded("yuushya_modelling")) {
                com.hooya.stabilizedturret.client.compat.YuushyaCannonClient.register(modBus);
            }
        }
    }

    private ModCbcContent() {
    }
}
