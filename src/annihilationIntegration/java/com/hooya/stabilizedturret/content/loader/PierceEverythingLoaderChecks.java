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
import net.minecraft.world.item.Items;
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

/** Mixed ammunition must survive reload and reach the real CBC chamber in rack order. */
public final class PierceEverythingLoaderChecks {
    public static void check(ServerLevel level) throws Exception {
        for (int kind = 0; kind < 3; kind++) check(level, kind);
    }

    private static void check(ServerLevel level, int kind) throws Exception {
        BlockPos pos = new BlockPos(12 + kind * 32, 205, -32);
        Block block = switch (kind) {
            case 0 -> ModBlocks.MZ_LOADER.get();
            case 1 -> ModBlocks.AZ_LOADER.get();
            default -> ModBlocks.TYPE_10_LOADER.get();
        };
        Block breechBlock = switch (kind) {
            case 0 -> ModCbcContent.MZ_CANNON_BREECH.get();
            case 1 -> ModCbcContent.AZ_CANNON_BREECH.get();
            default -> ModCbcContent.TYPE_10_CANNON_BREECH.get();
        };
        int duration = kind == 0 ? MzLoaderBlockEntity.ANIM_TICKS : kind == 1
                ? AzLoaderBlockEntity.ANIM_TICKS : Type10LoaderBlockEntity.ANIM_TICKS;
        int insertion = kind == 0 ? MzLoaderBlockEntity.INSERT_TICKS : kind == 1
                ? AzLoaderBlockEntity.INSERT_TICKS : Type10LoaderBlockEntity.INSERT_TICKS;
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        CannonAutoLoader loader = (CannonAutoLoader) level.getBlockEntity(pos);
        ItemStack piercing = ModCbcContent.ANNIHILATION_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
        ItemStack standard = ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
        require(!insert(loader, new ItemStack(Items.DIAMOND)), "non-ammunition accepted");
        require(insert(loader, piercing) && insert(loader, standard) && insert(loader, standard), "mixed rack rejected");
        require(loader.getSlotCount() == 3, "wrong visible slot count");
        // Old saves have no type mask and must retain their original APFSDS rounds.
        var legacyTag = ((BlockEntity) loader).saveWithFullMetadata(level.registryAccess());
        legacyTag.remove("AnnihilationSlots");
        var legacy = (CannonAutoLoader) BlockEntity.loadStatic(pos, block.defaultBlockState(), legacyTag, level.registryAccess());
        require(legacy != null && legacy.getNextCartridge().is(standard.getItem()), "legacy ammo migration failed");
        Fixture cannon = cannon(level, pos.west(8), breechBlock);
        loader.bindCannonMount(BindingRef.capture(level, cannon.pos));
        for (int cycle = 0; cycle < 3; cycle++) {
            int index = kind == 2 ? 2 - cycle : cycle;
            ItemStack expected = index == 0 ? piercing : standard;
            require(loader.getNextCartridge().is(expected.getItem()), "mixed rack order changed");
            require(loader.startAutomaticCycle(cannon.entity.getUUID()), "cycle failed to start");
            for (int t = 1; t <= duration; t++) {
                level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
                cannon.breech.tickFromContraption(level, cannon.entity, BlockPos.ZERO);
                tick(level, pos, loader);
                if (cycle == 0 && (t == 10 || t == insertion + 1)) loader = reload(level, pos, loader);
                if (t >= insertion) require(cannon.breech.getInputBuffer().is(expected.getItem()),
                        "wrong cartridge inserted, kind=" + kind + ", cycle=" + cycle + ", tick=" + t);
            }
            require(loader.getSlotCount() == 2 - cycle, "round was not consumed exactly once");
            require(!loader.isBusy() && !cannon.breech.isOpen(), "cycle did not finish normally");
            // Keep the chamber occupied until the next cycle is explicitly started.
            cannon.breech.clearInputBuffer();
        }
        require(insert(loader, standard), "empty rack did not unlock");
        require(loader.getNextCartridge().is(standard.getItem()), "used piercing type leaked into refilled slot");
        level.removeBlock(pos, false);
    }

    private static boolean insert(CannonAutoLoader loader, ItemStack cartridge) {
        if (loader instanceof MzLoaderBlockEntity mz) return mz.insertRound(cartridge);
        if (loader instanceof AzLoaderBlockEntity az) return az.insertRound(cartridge);
        return ((Type10LoaderBlockEntity) loader).insertRound(cartridge);
    }

    private static void tick(ServerLevel level, BlockPos pos, CannonAutoLoader loader) {
        if (loader instanceof MzLoaderBlockEntity mz) MzLoaderBlockEntity.serverTick(level, pos, mz.getBlockState(), mz);
        else if (loader instanceof AzLoaderBlockEntity az) AzLoaderBlockEntity.serverTick(level, pos, az.getBlockState(), az);
        else {
            var type10 = (Type10LoaderBlockEntity) loader;
            Type10LoaderBlockEntity.serverTick(level, pos, type10.getBlockState(), type10);
        }
    }

    private static CannonAutoLoader reload(ServerLevel level, BlockPos pos, CannonAutoLoader loader) {
        var old = (BlockEntity) loader;
        var restored = BlockEntity.loadStatic(pos, old.getBlockState(),
                old.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        require(restored instanceof CannonAutoLoader, "typed loader failed to restore");
        old.setRemoved();
        level.setBlockEntity(restored);
        return (CannonAutoLoader) restored;
    }

    private static Fixture cannon(ServerLevel level, BlockPos pos, Block breechBlock) throws Exception {
        var mountBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(pos, mountBlock.defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(pos);
        var contraption = new MountedMediumcannonContraption();
        field(AbstractMountedCannonContraption.class, contraption, "initialOrientation", Direction.SOUTH);
        field(AbstractMountedCannonContraption.class, contraption, "startPos", BlockPos.ZERO);
        field(MountedMediumcannonContraption.class, contraption, "cannonMaterial", CBCModernWarfareMediumcannonMaterials.NETHERSTEEL);
        MediumcannonBreechBlockEntity breech = null;
        for (int z = 0; z <= 3; z++) {
            BlockPos local = new BlockPos(0, 0, z);
            Block part = z == 0 ? breechBlock : BuiltInRegistries.BLOCK.get(ResourceLocation.parse("cbcmodernwarfare:nethersteel_mediumcannon_barrel"));
            var state = part.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
            var be = ((EntityBlock) part).newBlockEntity(local, state);
            be.setLevel(level);
            contraption.getBlocks().put(local, new StructureBlockInfo(local, state, null));
            contraption.presentBlockEntities.put(local, be);
            if (z == 0) breech = (MediumcannonBreechBlockEntity) be;
        }
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 4);
        var entity = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        entity.moveTo(Vec3.atCenterOf(pos).add(0, 1, 0));
        mount.attach(entity);
        return new Fixture(pos, entity, breech);
    }

    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private record Fixture(BlockPos pos, PitchOrientedContraptionEntity entity, MediumcannonBreechBlockEntity breech) {}
}
