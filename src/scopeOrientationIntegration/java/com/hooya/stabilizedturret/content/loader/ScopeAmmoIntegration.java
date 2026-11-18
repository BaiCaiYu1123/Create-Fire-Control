package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

/** Real assembled CBC chambers and all three stock-bearing loader implementations. */
public final class ScopeAmmoIntegration {
    public static void check(ServerLevel level) throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            check(level, kind);
            checkReloadContinuity(level, kind);
        }
    }

    private static void check(ServerLevel level, int kind) throws Exception {
        BlockPos origin = new BlockPos(16 + kind * 48, 220, 48);
        Block loaderBlock = kind == 0 ? ModBlocks.MZ_LOADER.get()
                : kind == 1 ? ModBlocks.AZ_LOADER.get() : ModBlocks.TYPE_10_LOADER.get();
        Block breechBlock = kind == 0 ? ModCbcContent.MZ_CANNON_BREECH.get()
                : kind == 1 ? ModCbcContent.AZ_CANNON_BREECH.get() : ModCbcContent.TYPE_10_CANNON_BREECH.get();
        var gun = cannon(level, origin, breechBlock);
        var otherGun = cannon(level, origin.east(16), breechBlock);
        var binding = BindingRef.capture(level, origin);
        var primary = loader(level, origin.north(4), loaderBlock, binding);
        var secondary = loader(level, origin.north(6), loaderBlock, binding);
        var unrelated = loader(level, origin.north(8), loaderBlock, BindingRef.capture(level, origin.east(16)));
        ItemStack standard = ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
        ItemStack other = ModCbcContent.ANNIHILATION_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
        require(insert(primary, standard) && insert(primary, other) && insert(primary, standard), "mixed stock failed");
        for (int i = 0; i < 4; i++) require(insert(secondary, standard), "secondary stock failed");
        for (int i = 0; i < 8; i++) require(insert(unrelated, standard), "unrelated stock failed");
        require(primary.getSlotCount() == 3 && primary.getApfsdsCount() == 2, "loader counts wrong cartridge types");
        gun.breech.setInputBuffer(standard.copy());
        equal(7, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "include chamber and matching APFSDS reserves");
        var consume = primary.getClass().getDeclaredMethod("consumeRound");
        consume.setAccessible(true); consume.invoke(primary);
        equal(6, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "stock removal not reflected");
        consume.invoke(primary);
        equal(6, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "other ammunition removal changed APFSDS count");

        BlockEntity old = (BlockEntity) primary;
        var restored = BlockEntity.loadStatic(old.getBlockPos(), old.getBlockState(),
                old.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        require(restored instanceof CannonAutoLoader, "loader persistence failed");
        old.setRemoved(); level.setBlockEntity(restored);
        equal(6, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "reloaded loader double-counted or lost");
        gun.breech.clearInputBuffer(); gun.breech.setInputBuffer(other.copy());
        equal(-1, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "other cartridge should hide HUD");
        gun.breech.clearInputBuffer();
        equal(5, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "APFSDS supply remains visible with empty chamber");
        gun.breech.setInputBuffer(ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get().getDefaultInstance());
        equal(5, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "spent casing contributes no live round");
        gun.breech.clearInputBuffer(); gun.breech.setInputBuffer(standard.copy());
        level.removeBlock(origin.north(4), false); level.removeBlock(origin.north(6), false);
        equal(1, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "last chambered round remains counted");
        level.removeBlock(origin, false);
        equal(-1, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "removed mount should hide HUD");
        level.removeBlock(origin.north(8), false); level.removeBlock(origin.east(16), false);
        otherGun.entity.discard(); gun.entity.discard();
    }

    private static void checkReloadContinuity(ServerLevel level, int kind) throws Exception {
        BlockPos origin = new BlockPos(16 + kind * 48, 220, 80);
        Block loaderBlock = kind == 0 ? ModBlocks.MZ_LOADER.get()
                : kind == 1 ? ModBlocks.AZ_LOADER.get() : ModBlocks.TYPE_10_LOADER.get();
        Block breechBlock = kind == 0 ? ModCbcContent.MZ_CANNON_BREECH.get()
                : kind == 1 ? ModCbcContent.AZ_CANNON_BREECH.get() : ModCbcContent.TYPE_10_CANNON_BREECH.get();
        int duration = kind == 0 ? MzLoaderBlockEntity.ANIM_TICKS
                : kind == 1 ? AzLoaderBlockEntity.ANIM_TICKS : Type10LoaderBlockEntity.ANIM_TICKS;
        int insertion = kind == 0 ? MzLoaderBlockEntity.INSERT_TICKS
                : kind == 1 ? AzLoaderBlockEntity.INSERT_TICKS : Type10LoaderBlockEntity.INSERT_TICKS;
        var gun = cannon(level, origin, breechBlock);
        var binding = BindingRef.capture(level, origin);
        var loader = loader(level, origin.north(4), loaderBlock, binding);
        ItemStack standard = ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
        for (int i = 0; i < 3; i++) require(insert(loader, standard), "reload fixture stock failed");
        equal(3, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "opening scope during first load shows APFSDS supply");
        gun.breech.setInputBuffer(standard.copy());
        equal(4, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "initial chambered round counted once");
        for (int shot = 0; shot < 3; shot++) {
            gun.breech.clearInputBuffer();
            gun.breech.setInputBuffer(ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get().getDefaultInstance());
            MzLoaderAutomation.onCannonFired(gun.entity.getContraption(), gun.entity);
            require(loader.isBusy(), "actual firing callback starts reload");
            int expectedTotal = 3 - shot;
            equal(expectedTotal, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "firing removes one live round");
            for (int tick = 1; tick <= duration; tick++) {
                level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
                gun.breech.tickFromContraption(level, gun.entity, BlockPos.ZERO);
                tickLoader(level, loader);
                equal(expectedTotal, MzLoaderAutomation.getScopeApfsdsReserve(level, binding),
                        "reload count changed at kind=" + kind + ", shot=" + shot + ", tick=" + tick);
                if (shot == 0 && (tick == insertion - 1 || tick == insertion + 1 || tick == 121)) {
                    loader = reload(level, loader);
                    equal(expectedTotal, MzLoaderAutomation.getScopeApfsdsReserve(level, binding),
                            "saved reload lost or duplicated a live round");
                }
            }
            require(LoaderAmmunition.isStandard(gun.breech.getInputBuffer()), "reload failed to chamber APFSDS");
            equal(2 - shot, loader.getApfsdsCount(), "completed reload reserve");
        }
        equal(1, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "last live round is in the chamber");
        gun.breech.clearInputBuffer();
        gun.breech.setInputBuffer(ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get().getDefaultInstance());
        MzLoaderAutomation.onCannonFired(gun.entity.getContraption(), gun.entity);
        equal(0, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "last shot keeps zero visible");
        gun.breech.clearInputBuffer();
        equal(0, MzLoaderAutomation.getScopeApfsdsReserve(level, binding), "empty depleted cannon keeps zero visible");
        gun.entity.discard();
        level.removeBlock(origin.north(4), false);
        level.removeBlock(origin, false);
    }

    private static void tickLoader(ServerLevel level, CannonAutoLoader loader) {
        var blockEntity = (BlockEntity) loader;
        if (loader instanceof MzLoaderBlockEntity mz) {
            MzLoaderBlockEntity.serverTick(level, mz.getBlockPos(), mz.getBlockState(), mz);
        } else if (loader instanceof AzLoaderBlockEntity az) {
            AzLoaderBlockEntity.serverTick(level, az.getBlockPos(), az.getBlockState(), az);
        } else {
            Type10LoaderBlockEntity.serverTick(level, blockEntity.getBlockPos(), blockEntity.getBlockState(),
                    (Type10LoaderBlockEntity) loader);
        }
    }

    private static CannonAutoLoader reload(ServerLevel level, CannonAutoLoader loader) {
        var old = (BlockEntity) loader;
        var restored = BlockEntity.loadStatic(old.getBlockPos(), old.getBlockState(),
                old.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        require(restored instanceof CannonAutoLoader, "loading rack failed to restore");
        old.setRemoved();
        level.setBlockEntity(restored);
        return (CannonAutoLoader) restored;
    }

    private static CannonAutoLoader loader(ServerLevel level, BlockPos pos, Block block, BindingRef binding) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var loader = (CannonAutoLoader) level.getBlockEntity(pos);
        loader.bindCannonMount(binding);
        return loader;
    }
    private static boolean insert(CannonAutoLoader loader, ItemStack cartridge) {
        if (loader instanceof MzLoaderBlockEntity mz) return mz.insertRound(cartridge);
        if (loader instanceof AzLoaderBlockEntity az) return az.insertRound(cartridge);
        return ((Type10LoaderBlockEntity) loader).insertRound(cartridge);
    }
    private static Fixture cannon(ServerLevel level, BlockPos pos, Block breechBlock) throws Exception {
        level.getChunk(pos);
        var mountBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(pos, mountBlock.defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(pos);
        var contraption = new MountedMediumcannonContraption();
        contraption.anchor = pos.above();
        field(AbstractMountedCannonContraption.class, contraption, "initialOrientation", Direction.SOUTH);
        field(AbstractMountedCannonContraption.class, contraption, "startPos", BlockPos.ZERO);
        field(MountedMediumcannonContraption.class, contraption, "cannonMaterial", CBCModernWarfareMediumcannonMaterials.NETHERSTEEL);
        MediumcannonBreechBlockEntity breech = null;
        for (int z = 0; z <= 3; z++) {
            var local = new BlockPos(0, 0, z);
            Block part = z == 0 ? breechBlock : BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("cbcmodernwarfare:nethersteel_mediumcannon_barrel"));
            var state = part.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
            var be = ((EntityBlock) part).newBlockEntity(local, state); be.setLevel(level);
            contraption.getBlocks().put(local, new StructureBlockInfo(local, state, null));
            contraption.presentBlockEntities.put(local, be);
            if (z == 0) breech = (MediumcannonBreechBlockEntity) be;
        }
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 4);
        var entity = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        entity.moveTo(Vec3.atCenterOf(pos).add(0, 1, 0)); mount.attach(entity);
        return new Fixture(entity, breech);
    }
    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void equal(int expected, int actual, String message) { require(expected == actual, message + ": " + actual); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Fixture(PitchOrientedContraptionEntity entity, MediumcannonBreechBlockEntity breech) {}
}
