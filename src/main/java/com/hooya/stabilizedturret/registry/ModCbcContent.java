package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.cannon.MediumCannonBreechBlock;
import com.hooya.stabilizedturret.content.cannon.MediumCannonBreechBlockEntity;
import com.hooya.stabilizedturret.content.cannon.Type10CannonBreechBlock;
import com.hooya.stabilizedturret.content.cannon.Type10CannonBreechBlockEntity;
import com.hooya.stabilizedturret.content.cannon.AzCannonBreechBlock;
import com.hooya.stabilizedturret.content.cannon.AzCannonBreechBlockEntity;
import com.hooya.stabilizedturret.content.cannon.MzCannonBreechBlock;
import com.hooya.stabilizedturret.content.cannon.MzCannonBreechBlockEntity;
import com.hooya.stabilizedturret.content.cannon.compat.YuushyaMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.content.cannon.compat.YuushyaShowMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsCartridgeItem;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsRoundItem;
import com.hooya.stabilizedturret.content.cannon.munition.AnnihilationCartridgeItem;
import com.hooya.stabilizedturret.content.cannon.munition.AnnihilationRoundItem;
import com.hooya.stabilizedturret.content.cannon.munition.AnnihilationProjectile;
import com.hooya.stabilizedturret.content.cannon.munition.AnnihilationLifecycle;
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

    public static final DeferredBlock<MzCannonBreechBlock> MZ_CANNON_BREECH = BLOCKS.register(
            "mz_cannon_breech",
            () -> new MzCannonBreechBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(5.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<Type10CannonBreechBlock> TYPE_10_CANNON_BREECH = BLOCKS.register(
            "type_10_cannon_breech",
            () -> new Type10CannonBreechBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(5.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<AzCannonBreechBlock> AZ_CANNON_BREECH = BLOCKS.register(
            "az_cannon_breech",
            () -> new AzCannonBreechBlock(BlockBehaviour.Properties.of()
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

    public static final DeferredBlock<YuushyaShowMediumCannonBarrelBlock> YUUSHYA_SHOW_MEDIUM_CANNON_BARREL =
            ModList.get().isLoaded("yuushya_modelling")
                    ? BLOCKS.register("yuushya_show_medium_cannon_barrel", () ->
                    new YuushyaShowMediumCannonBarrelBlock(BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .forceSolidOn()
                            .strength(4.0F)
                            .lightLevel(state -> state.getValue(YuushyaBlockStates.LIT))))
                    : null;

    public static final DeferredItem<MediumcannonBlockItem<MediumCannonBreechBlock>> MEDIUM_CANNON_BREECH_ITEM =
            ITEMS.register("medium_cannon_breech", () ->
                    new MediumcannonBlockItem<>(MEDIUM_CANNON_BREECH.get(), new Item.Properties()));

    public static final DeferredItem<MediumcannonBlockItem<MzCannonBreechBlock>> MZ_CANNON_BREECH_ITEM =
            ITEMS.register("mz_cannon_breech", () ->
                    new MediumcannonBlockItem<>(MZ_CANNON_BREECH.get(), new Item.Properties()));

    public static final DeferredItem<MediumcannonBlockItem<Type10CannonBreechBlock>> TYPE_10_CANNON_BREECH_ITEM =
            ITEMS.register("type_10_cannon_breech", () ->
                    new MediumcannonBlockItem<>(TYPE_10_CANNON_BREECH.get(), new Item.Properties()));

    public static final DeferredItem<MediumcannonBlockItem<AzCannonBreechBlock>> AZ_CANNON_BREECH_ITEM =
            ITEMS.register("az_cannon_breech", () ->
                    new MediumcannonBlockItem<>(AZ_CANNON_BREECH.get(), new Item.Properties()));

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

    public static final DeferredItem<AnnihilationRoundItem> ANNIHILATION_MEDIUM_CANNON_ROUND =
            ITEMS.register("annihilation_medium_cannon_round", () ->
                    new AnnihilationRoundItem(new Item.Properties()));

    public static final DeferredItem<AnnihilationCartridgeItem> ANNIHILATION_MEDIUM_CANNON_CARTRIDGE =
            ITEMS.register("annihilation_medium_cannon_cartridge", () ->
                    new AnnihilationCartridgeItem(new Item.Properties()));

    public static final DeferredHolder<EntityType<?>, EntityType<AnnihilationProjectile>>
            ANNIHILATION_MEDIUM_CANNON_PROJECTILE = ENTITY_TYPES.register("annihilation_medium_shell", () ->
                    EntityType.Builder.<AnnihilationProjectile>of(AnnihilationProjectile::new, MobCategory.MISC)
                            .sized(.2F, .2F).fireImmune().noSave()
                            .clientTrackingRange(16).updateInterval(1)
                            .build(StabilizedTurret.MOD_ID + ":annihilation_medium_shell"));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MediumCannonBreechBlockEntity>>
            MEDIUM_CANNON_BREECH_BLOCK_ENTITY = BLOCK_ENTITIES.register(
                    "medium_cannon_breech",
                    () -> BlockEntityType.Builder.of(
                            MediumCannonBreechBlockEntity::new,
                            MEDIUM_CANNON_BREECH.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MzCannonBreechBlockEntity>>
            MZ_CANNON_BREECH_BLOCK_ENTITY = BLOCK_ENTITIES.register(
                    "mz_cannon_breech",
                    () -> BlockEntityType.Builder.of(
                            MzCannonBreechBlockEntity::new,
                            MZ_CANNON_BREECH.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Type10CannonBreechBlockEntity>>
            TYPE_10_CANNON_BREECH_BLOCK_ENTITY = BLOCK_ENTITIES.register(
                    "type_10_cannon_breech",
                    () -> BlockEntityType.Builder.of(
                            Type10CannonBreechBlockEntity::new,
                            TYPE_10_CANNON_BREECH.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AzCannonBreechBlockEntity>>
            AZ_CANNON_BREECH_BLOCK_ENTITY = BLOCK_ENTITIES.register(
                    "az_cannon_breech",
                    () -> BlockEntityType.Builder.of(
                            AzCannonBreechBlockEntity::new,
                            AZ_CANNON_BREECH.get()
                    ).build(null)
            );

    public static void register(IEventBus modBus) {
        AnnihilationLifecycle.register();
        RegistryMigration.addLegacyAliases(BLOCKS);
        RegistryMigration.addLegacyAliases(ITEMS);
        RegistryMigration.addLegacyAliases(BLOCK_ENTITIES);
        RegistryMigration.addLegacyAliases(ENTITY_TYPES);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITY_TYPES.register(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.hooya.stabilizedturret.client.MediumCannonBreechClient.register(modBus);
            com.hooya.stabilizedturret.client.MzCannonBreechClient.register(modBus);
            com.hooya.stabilizedturret.client.AzCannonBreechClient.register(modBus);
            com.hooya.stabilizedturret.client.Type10CannonBreechClient.register(modBus);
            if (ModList.get().isLoaded("yuushya_modelling")) {
                com.hooya.stabilizedturret.client.compat.YuushyaCannonClient.register(modBus);
            }
        }
    }

    private ModCbcContent() {
    }
}
