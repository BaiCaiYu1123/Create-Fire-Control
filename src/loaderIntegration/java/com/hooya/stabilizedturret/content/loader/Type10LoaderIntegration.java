package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.network.Type10LoaderAnimationPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Runs the production loader against real registered CBC mounts, contraptions and breeches. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class Type10LoaderIntegration {
    private static final BlockPos LOADER_POS = new BlockPos(8, 200, 0);

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        String result;
        try {
            check(event.getServer().overworld());
            result = "PASS: empty placement; 13-round capacity; dedicated breech; 13 automatic insertions; "
                    + "descending inventory; reload before/after insertion; blocked insertion; redstone; payload codec";
        } catch (Throwable error) {
            result = "FAIL: " + error;
            StabilizedTurret.LOGGER.error("TYPE 10 LOADER CHECK FAILED", error);
        }
        try {
            Files.writeString(Path.of("type-10-integration-result.txt"), result);
            System.out.println(result);
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void check(ServerLevel level) throws Exception {
        level.setBlockAndUpdate(LOADER_POS, ModBlocks.TYPE_10_LOADER.get().defaultBlockState());
        var loader = (Type10LoaderBlockEntity) level.getBlockEntity(LOADER_POS);
        require(loader.getSlotCount() == 0, "Placed rack must be empty");
        require(!loader.startAutomaticCycle(UUID.randomUUID()), "Empty rack cannot cycle");
        for (int i = 1; i <= 13; i++) {
            require(loader.insertRound(), "Insert round " + i);
            require(loader.getSlots() == (1 << i) - 1, "Ascending visible slots");
        }
        require(!loader.insertRound(), "Reject fourteenth round");
        var wrong = cannon(level, new BlockPos(0, 200, 8), false);
        loader.bindCannonMount(BindingRef.capture(level, wrong.mountPos));
        tick(level, loader, wrong, 1);
        require(!loader.isBusy() && loader.getSlotCount() == 13, "AZ breech cannot claim Type 10 ammunition");
        var cannon = cannon(level, new BlockPos(0, 200, 0), true);
        loader.bindCannonMount(BindingRef.capture(level, cannon.mountPos));
        tick(level, loader, cannon, 1);
        require(loader.isBusy(), "Bound empty chamber starts automatically");

        for (int shot = 0; shot < 13; shot++) {
            long start = level.getGameTime();
            int before = 13 - shot;
            if (shot != 0) {
                cannon.breech.setInputBuffer(ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get().getDefaultInstance());
                MzLoaderAutomation.onCannonFired(cannon.contraption, cannon.entity);
                require(cannon.breech.getInputBuffer().isEmpty(), "Extract automatic casing after claimed cycle");
            }
            require(loader.isBusy(), "Cycle starts after shot " + shot);
            require(!loader.insertRound(), "Cannot top up a cycling rack");
            for (int t = 1; t <= Type10LoaderBlockEntity.ANIM_TICKS; t++) {
                tick(level, loader, cannon, 1);
                if (shot == 0 && (t == 25 || t == 54)) loader = reload(level, loader);
                int expected = before - (t >= Type10LoaderBlockEntity.INSERT_TICKS ? 1 : 0);
                require(loader.getSlots() == (1 << expected) - 1, "Consume highest slot once at insertion, shot=" + shot + ",t=" + t);
                if (t >= Type10LoaderBlockEntity.INSERT_TICKS) {
                    require(cannon.breech.getInputBuffer().is(ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get()),
                            "Chamber receives the actual APFSDS cartridge");
                }
            }
            require(level.getGameTime() == start + Type10LoaderBlockEntity.ANIM_TICKS, "Complete source timeline");
            require(!loader.isBusy(), "Cycle finishes after closing");
            require(!cannon.breech.isOpen(), "Loader closes its breech");
        }
        cannon.breech.clearInputBuffer();
        tick(level, loader, cannon, 2);
        require(loader.getSlotCount() == 0 && !loader.isBusy(), "Empty rack stops automatic loading");
        require(loader.insertRound(), "Empty rack can be refilled");

        // An occupied chamber at the insertion keyframe must retain the rack round.
        tick(level, loader, cannon, 1);
        cannon.breech.setInputBuffer(ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance());
        tick(level, loader, cannon, 90);
        require(loader.getSlotCount() == 1, "Rejected insertion must not spend a round");
        require(!loader.isBusy(), "Rejected insertion releases the cycle");

        // A standalone redstone edge still drives the same visual/inventory cycle as MZ/AZ.
        level.removeBlock(LOADER_POS, false);
        level.setBlockAndUpdate(LOADER_POS, ModBlocks.TYPE_10_LOADER.get().defaultBlockState());
        loader = (Type10LoaderBlockEntity) level.getBlockEntity(LOADER_POS);
        require(loader.insertRound(), "Fill standalone loader");
        tick(level, loader, cannon, 1);
        level.setBlockAndUpdate(LOADER_POS.east(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
        tick(level, loader, cannon, 1);
        require(loader.isBusy(), "Rising redstone edge starts a cycle");
        tick(level, loader, cannon, 90);
        require(loader.getSlotCount() == 0 && !loader.isBusy(), "Redstone consumes exactly one round");

        var payload = new Type10LoaderAnimationPayload(LOADER_POS, UUID.randomUUID(),
                (long) Integer.MAX_VALUE + 1000, 8191, 13);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            Type10LoaderAnimationPayload.STREAM_CODEC.encode(buffer, payload);
            require(payload.equals(Type10LoaderAnimationPayload.STREAM_CODEC.decode(buffer)), "Animation payload round trip");
        } finally { buffer.release(); }
    }

    private static Type10LoaderBlockEntity reload(ServerLevel level, Type10LoaderBlockEntity loader) {
        var tag = loader.saveWithFullMetadata(level.registryAccess());
        var restored = (Type10LoaderBlockEntity) BlockEntity.loadStatic(LOADER_POS,
                loader.getBlockState(), tag, level.registryAccess());
        require(restored != null, "Saved loader restores via the real block entity registry");
        loader.setRemoved();
        level.setBlockEntity(restored);
        return restored;
    }

    private static void tick(ServerLevel level, Type10LoaderBlockEntity loader, Fixture cannon, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
            cannon.breech.tickFromContraption(level, cannon.entity, BlockPos.ZERO);
            Type10LoaderBlockEntity.serverTick(level, LOADER_POS, loader.getBlockState(), loader);
        }
    }

    private static Fixture cannon(ServerLevel level, BlockPos pos, boolean dedicated) throws Exception {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(pos);
        var contraption = new MountedMediumcannonContraption();
        field(AbstractMountedCannonContraption.class, contraption, "initialOrientation", Direction.SOUTH);
        field(AbstractMountedCannonContraption.class, contraption, "startPos", BlockPos.ZERO);
        field(MountedMediumcannonContraption.class, contraption, "cannonMaterial", CBCModernWarfareMediumcannonMaterials.NETHERSTEEL);
        MediumcannonBreechBlockEntity breech = null;
        for (int z = 0; z <= 3; z++) {
            BlockPos local = new BlockPos(0, 0, z);
            var part = z == 0 ? (dedicated ? ModCbcContent.TYPE_10_CANNON_BREECH.get() : ModCbcContent.AZ_CANNON_BREECH.get())
                    : BuiltInRegistries.BLOCK.get(ResourceLocation.parse("cbcmodernwarfare:nethersteel_mediumcannon_barrel"));
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
        return new Fixture(pos, contraption, entity, breech);
    }

    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(BlockPos mountPos, MountedMediumcannonContraption contraption,
                           PitchOrientedContraptionEntity entity, MediumcannonBreechBlockEntity breech) { }
}
