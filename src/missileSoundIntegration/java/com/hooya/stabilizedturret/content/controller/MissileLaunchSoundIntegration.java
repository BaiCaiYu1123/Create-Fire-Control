package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Compares actual native sound events against real display-owned launches in both flight modes. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MissileLaunchSoundIntegration {
    private record Fixture(MianbaoCruiseMissileCompat.Type type, String block, String entity, String procedure) { }
    private record Sound(ResourceLocation id, SoundSource source, float volume, float pitch, Vec3 position, long time) { }
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(MianbaoCruiseMissileCompat.Type.CRUISE, "cruisemissileblock", "cruisemissiletanshe", "CruisemissilegoProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.LOW_ALTITUDE_CRUISE, "cruisemissilelowblock", "cruisemissilelowtanshe", "CruisemissilelowgoProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.NUCLEAR_CRUISE, "nuclearcruisemissile", "nuclearcruisemissiletanshe", "Nuclear_cruise_missile_goProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.GROUND_HIGH_EXPLOSIVE, "groundmissile_1", "groundmissiletanshe", "Groundmissile1goProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.GROUND_CLUSTER, "groundmissile_2", "groundmissile_2tanshe", "Groundmissile2goProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.GROUND_CRUISE, "groundmissile_3", "groundmissile_3tanshe", "Groundmissile3goProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.GROUND_LOW_ALTITUDE, "groundmissile_4", "groundmissile_4tanshe", "Groundmissile4goProcedure"),
            new Fixture(MianbaoCruiseMissileCompat.Type.GROUND_NUCLEAR, "groundnuclearcruisemissile", "groundnuclearcruisemissiletanshe", "Ground_nuclear_cruise_missile_goProcedure"));
    private static final List<Sound> SOUNDS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static ServerLevel level;
    private static Entity captured;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void sound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getLevel() != level || event.getSound() == null) return;
        ResourceLocation id = event.getSound().value().getLocation();
        if (!id.equals(id("missilelaunch")) && !id.equals(id("missile_launcher"))
                && !id.equals(ResourceLocation.withDefaultNamespace("block.metal_pressure_plate.click_off"))) return;
        SOUNDS.add(new Sound(id, event.getSource(), event.getNewVolume(), event.getNewPitch(),
                event.getPosition(), level.getGameTime()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel() == level && MianbaoCruiseMissileCompat.isMissile(event.getEntity())) captured = event.getEntity();
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        long originalTime = level.getGameTime();
        Throwable failure = null;
        try {
            int index = 0;
            for (Fixture fixture : FIXTURES) {
                BlockPos pos = new BlockPos(8 + index++ * 8, 240, 8);
                level.getChunkAt(pos);
                placeBlock(pos, fixture);
                SOUNDS.clear(); captured = null;
                try (var ignored = FireControlMissileCommandGuard.enter()) {
                    Class<?> procedure = Class.forName("net.mcreator.myfirstmod.procedures." + fixture.procedure());
                    if (fixture.type().isGroundLaunchType()) {
                        procedure.getMethod("execute", LevelAccessor.class, double.class, double.class, double.class, BlockState.class)
                                .invoke(null, level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), level.getBlockState(pos));
                    } else {
                        procedure.getMethod("execute", LevelAccessor.class, double.class, double.class, double.class)
                                .invoke(null, level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
                    }
                }
                require(SOUNDS.size() == 1, fixture + " native release sound count: " + SOUNDS);
                Sound nativeRelease = SOUNDS.getFirst();
                if (captured != null) captured.discard();
                Sound nativeIgnition = nativeIgnition(fixture);
                for (boolean math : new boolean[]{false, true}) {
                    checkLaunch(pos, fixture, nativeRelease, nativeIgnition, math, false);
                    checkLaunch(pos, fixture, null, nativeIgnition, math, true);
                }
            }
            REPORT.add("PASS: eight cruise/ground families; native sound-event parity for block and VLS launchers in math/entity flight; exact ignition timing; no duplicate ignition after timer rewind; one-round consumption");
            StabilizedTurret.LOGGER.info(REPORT.getLast());
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("MISSILE LAUNCH SOUND CHECK FAILED", error);
        } finally {
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
            try {
                Files.write(Path.of("missile-launch-sound-details.txt"), REPORT);
                Files.writeString(Path.of("missile-launch-sound-result.txt"), failure == null ? "PASS" : "FAIL: " + failure);
            } catch (Exception error) { throw new RuntimeException(error); }
            level.getServer().halt(false);
        }
    }

    private static Sound nativeIgnition(Fixture fixture) {
        Entity nativeMissile = BuiltInRegistries.ENTITY_TYPE.get(id(fixture.entity())).create(level);
        require(nativeMissile != null, "native entity exists");
        nativeMissile.setPos(8.5, 250, 8.5); nativeMissile.setDeltaMovement(2, .1, 0);
        nativeMissile.getPersistentData().putDouble(timer(fixture), ignitionTick(fixture) - 1);
        MianbaoCruiseMissileCompat.writeNativeTarget(nativeMissile, fixture.type(), new Vec3(10000, 250, 8.5));
        SOUNDS.clear();
        try (var ignored = FireControlMissileCommandGuard.enter()) {
            MianbaoCruiseMissileCompat.runNativeFlight(nativeMissile, fixture.type());
        }
        require(SOUNDS.size() == 1 && SOUNDS.getFirst().id().equals(id("missile_launcher")), "native ignition: " + fixture + " " + SOUNDS);
        return SOUNDS.getFirst();
    }

    private static void checkLaunch(BlockPos pos, Fixture fixture, Sound nativeRelease, Sound nativeIgnition,
                                    boolean math, boolean silo) throws Exception {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(),
                "cfc missile mode " + (math ? "math" : "entity"));
        level.removeBlock(pos, false);
        if (silo) placeSilo(pos, fixture); else placeBlock(pos, fixture);
        BlockPos screen = pos.south(4);
        level.removeBlock(screen, false);
        level.setBlockAndUpdate(screen, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
        var display = (ConnectedDisplayBlockEntity) level.getBlockEntity(screen);
        BindingRef launcher = BindingRef.capture(level, pos);
        display.bind(BindingSlot.CRUISE_MISSILE_LAUNCHER, launcher);
        Vec3 target = new Vec3(10000, 250, 8.5);
        CruiseMissileGuidance.armNext(display, launcher, fixture.type(), null, target, target);
        SOUNDS.clear(); captured = null;
        require(MianbaoCruiseMissileCompat.fire(level, launcher, fixture.type(), target), "display launches " + fixture);
        require(captured != null && MathematicalMissileManager.contains(captured) == math, "correct flight route");
        require(SOUNDS.size() == 1, "one release event per launch");
        Sound release = SOUNDS.getFirst();
        if (silo) {
            require(release.id().equals(id("missilelaunch")) && release.source() == SoundSource.NEUTRAL
                    && release.volume() == 6 && release.pitch() == .8F
                    && release.position().equals(Vec3.atCenterOf(pos)), "native VLS release: " + release);
            require(MianbaoCruiseMissileCompat.availableType(level, launcher) == null, "silo consumes exactly one missile");
        } else {
            sameSound(release, nativeRelease, fixture.type().isGroundLaunchType());
            require(release.position().equals(nativeRelease.position()), "native block release position");
        }
        long start = level.getGameTime();
        Entity missile = captured;
        for (int age = 1; age <= 25; age++) {
            level.getServer().getWorldData().overworldData().setGameTime(start + age);
            if (math) MathematicalMissileManager.advance(missile);
            else try (var ignored = FireControlMissileCommandGuard.enter()) {
                MianbaoCruiseMissileCompat.runNativeFlight(missile, fixture.type());
            }
            require(SOUNDS.size() == (age < ignitionTick(fixture) ? 1 : 2), "exact ignition tick/count " + fixture + " age=" + age + " sounds=" + SOUNDS);
        }
        Sound ignition = SOUNDS.get(1);
        sameSound(ignition, nativeIgnition, false);
        require(ignition.time() - start == ignitionTick(fixture), "native ignition delay");
        if (math) {
            missile.getPersistentData().putDouble(timer(fixture), ignitionTick(fixture) - 1);
            level.getServer().getWorldData().overworldData().setGameTime(start + 26);
            MathematicalMissileManager.advance(missile);
            require(SOUNDS.size() == 2, "timer reconciliation cannot replay ignition");
        }
        REPORT.add("PASS " + fixture.type().key() + " " + (silo ? "VLS" : "block") + " " + (math ? "math" : "entity")
                + " release=" + release.id() + "/" + release.volume() + "/" + release.pitch()
                + " ignition=" + ignition.id() + "/" + ignition.volume() + "/" + ignition.pitch() + " at tick " + (ignition.time() - start));
        missile.discard();
    }

    private static void placeBlock(BlockPos pos, Fixture fixture) {
        level.removeBlock(pos, false);
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(id(fixture.block())).defaultBlockState());
    }

    private static void placeSilo(BlockPos pos, Fixture fixture) {
        BlockState head = BuiltInRegistries.BLOCK.get(id("groundmissilelauncherhead")).defaultBlockState();
        Direction facing = head.hasProperty(BlockStateProperties.FACING) ? head.getValue(BlockStateProperties.FACING) : Direction.UP;
        BlockState tail = BuiltInRegistries.BLOCK.get(id("groundmissilelaunchertail")).defaultBlockState();
        if (tail.hasProperty(BlockStateProperties.FACING)) tail = tail.setValue(BlockStateProperties.FACING, facing);
        for (int i = 1; i <= 2; i++) level.setBlockAndUpdate(pos.relative(facing.getOpposite(), i), tail);
        level.setBlockAndUpdate(pos, head);
        var blockEntity = level.getBlockEntity(pos);
        if (fixture.type().usesCruiseFlightData()) {
            blockEntity.getPersistentData().putString("CreateFireControlCruiseMissileType", fixture.type().key());
        } else {
            String item = fixture.type() == MianbaoCruiseMissileCompat.Type.GROUND_NUCLEAR
                    ? "groundnuclearcruisemissileitem" : fixture.block() + "item";
            ((Container) blockEntity).setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(id(item))));
        }
    }

    private static String timer(Fixture fixture) { return fixture.type().isGroundLaunchType() ? "time" : "fadongji"; }
    private static int ignitionTick(Fixture fixture) { return fixture.type().isGroundLaunchType() ? 10 : 15; }
    private static void sameSound(Sound actual, Sound nativeSound, boolean randomizedPitch) {
        require(actual.id().equals(nativeSound.id()) && actual.source() == nativeSound.source()
                && actual.volume() == nativeSound.volume(), "native sound event/source/volume: " + actual + " vs " + nativeSound);
        require(randomizedPitch ? actual.pitch() >= .8F && actual.pitch() <= 1F : actual.pitch() == nativeSound.pitch(), "native pitch: " + actual);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
