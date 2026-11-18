package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real native effects plus mathematical submunitions and an entity-free remote salvo. Never shipped. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class VirtualBombIntegration {
    private static ServerLevel level;
    private static Pig victim;
    private static UUID cluster, phosphorus, penetrator;
    private static int phase, tick;
    private static boolean finished;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("create_fire_control.virtualBombChecks")) return;
        level = event.getServer().overworld();
        try {
            for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) level.getChunk(x, z);
            for (int x = 2; x <= 10; x++) for (int z = 2; z <= 12; z++) level.setBlock(new BlockPos(x, 200, z), Blocks.STONE.defaultBlockState(), 3);
            victim = EntityType.PIG.create(level); victim.setNoAi(true); victim.setNoGravity(true); victim.setPos(7.5, 202, 8.5); level.addFreshEntity(victim);
            release("bombrack", WeaponType.BOMB, new BlockPos(4, 206, 8));
            require(MianbaoBombProjectileHandler.activeBombCount() == 1 && noNativeBombs(), "Native rack shot was not virtualized");
        } catch (Throwable error) { finish(error); }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (level == null || finished) return;
        try {
            level.resetEmptyTime(); tick++;
            require(tick < 200, "Virtual bomb check timed out");
            require(noNativeBombs(), "Mathematical bomb or submunition entered native world tracking");
            if (phase == 0 && tick >= 30) {
                require(MianbaoBombProjectileHandler.activeBombCount() == 0, "Ground impact did not remove mathematical flight");
                require(victim.isDeadOrDying(), "Native bomb explosion lost entity damage");
                require(level.getBlockState(new BlockPos(4, 200, 8)).isAir(), "Native bomb explosion lost terrain damage");
                StabilizedTurret.LOGGER.info("VIRTUAL BOMB PASS: native rack consumption, mathematical contact, original explosion and damage");
                Set<UUID> before = ids();
                release("cluster_bombrack", WeaponType.CLUSTER_BOMB, new BlockPos(4, 300, 8));
                var added = ids(); added.removeAll(before); require(added.size() == 1, "Cluster launch missing"); cluster = added.iterator().next();
                before = ids();
                release("whitephosphorusbombrack", WeaponType.WHITE_PHOSPHORUS_BOMB, new BlockPos(20, 300, 8));
                added = ids(); added.removeAll(before); require(added.size() == 1, "Phosphorus launch missing"); phosphorus = added.iterator().next();
                phase = 1; tick = 0;
            } else if (phase == 1 && tick >= 33) {
                require(MianbaoBombProjectileHandler.activeBombCount() == 27, "Expected cluster mother + 20 bomblets and 6 phosphorus children, got " + MianbaoBombProjectileHandler.activeBombCount());
                require(MianbaoBombProjectileHandler.snapshot(cluster) != null && MianbaoBombProjectileHandler.snapshot(phosphorus) == null,
                        "Native cluster persistence/phosphorus timed removal changed");
                StabilizedTurret.LOGGER.info("VIRTUAL BOMB PASS: native 30-tick cluster/phosphorus separation, 26 mathematical submunitions, zero native flying entities");
                discardFlights(); phase = 2; tick = 0;
            } else if (phase == 2 && tick >= 2) {
                require(MianbaoBombProjectileHandler.activeBombCount() == 0, "Discarded flights did not clean up");
                launchRemoteSalvo(); phase = 3; tick = 0;
            } else if (phase == 3) {
                require(MianbaoBombProjectileHandler.activeBombCount() == 192, "Mathematical salvo lost shots");
                require(level.getChunkSource().getChunkNow(64, 0) == null, "Bombs loaded flight-path chunks");
                if (tick >= 100) {
                    require(ids().stream().map(MianbaoBombProjectileHandler::snapshot).allMatch(s -> s.age() >= 99 && s.position().x > 700), "Salvo did not advance mathematically");
                    for (Object state : states().values()) {
                        var flightField = state.getClass().getDeclaredField("flight"); flightField.setAccessible(true);
                        var flight = (VirtualBombFlight) flightField.get(state);
                        var age = VirtualBombFlight.class.getDeclaredField("age"); age.setAccessible(true); age.setInt(flight, 1199);
                    }
                    phase = 4; tick = 0;
                }
            } else if (phase == 4 && tick >= 3) {
                require(MianbaoBombProjectileHandler.activeBombCount() == 0, "Mathematical lifetime did not expire cleanly");
                StabilizedTurret.LOGGER.info("VIRTUAL BOMB PASS: 192 simultaneous flights, no flight-path chunk loads/native entities, bounded cleanup");
                for (int x = 29; x <= 35; x++) for (int z = 20; z <= 28; z++) level.setBlock(new BlockPos(x, 284, z), Blocks.STONE.defaultBlockState(), 3);
                BlockPos rack = new BlockPos(32, 300, 24);
                level.setBlock(rack, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mianbaos_modernwarfare:earthpenetratorbombrack")).defaultBlockState(), 3);
                level.getBlockEntity(rack).getPersistentData().putDouble("深度", 5);
                require(MianbaoBombCompat.fireOne(level, BindingRef.capture(level, rack), WeaponType.EARTH_PENETRATOR_BOMB), "Penetrator release failed");
                penetrator = ids().iterator().next(); phase = 5; tick = 0;
            } else if (phase == 5 && tick >= 38) {
                require(MianbaoBombProjectileHandler.snapshot(penetrator) != null, "Penetrator detonated instead of drilling ahead");
                Object state = states().get(penetrator); var field = state.getClass().getDeclaredField("source"); field.setAccessible(true);
                require(((Entity) field.get(state)).getPersistentData().getDouble("钻地") >= 1, "Native drilling depth counter was not retained");
                require(level.getBlockState(new BlockPos(32, 284, 23)).isAir(), "Native drilling did not clear the upcoming terrain");
                StabilizedTurret.LOGGER.info("VIRTUAL BOMB PASS: native pre-contact drilling, depth settings and continued mathematical flight");
                discardFlights();
                finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }
    private static void release(String path, WeaponType type, BlockPos pos) {
        level.setBlock(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path)).defaultBlockState(), 3);
        var controllerPos = pos.east(3);
        level.setBlock(controllerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), 3);
        var controller = (StabilizerControllerBlockEntity) level.getBlockEntity(controllerPos);
        var binding = BindingRef.capture(level, pos); controller.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, binding);
        require(MianbaoBombCompat.fireOne(level, binding, type), "Native bound rack did not consume one bomb " + path);
    }
    @SuppressWarnings("unchecked") private static Map<UUID, Object> states() throws Exception {
        var field = MianbaoBombProjectileHandler.class.getDeclaredField("ACTIVE"); field.setAccessible(true); return (Map<UUID, Object>) field.get(null);
    }
    private static Set<UUID> ids() throws Exception { return new HashSet<>(states().keySet()); }
    private static void discardFlights() throws Exception {
        for (Object state : states().values()) { var source = state.getClass().getDeclaredField("source"); source.setAccessible(true); ((Entity) source.get(state)).discard(); }
    }
    @SuppressWarnings("unchecked") private static void launchRemoteSalvo() throws Exception {
        BlockPos rack = new BlockPos(4, 300, 8);
        level.setBlock(rack, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mianbaos_modernwarfare:bombrack")).defaultBlockState(), 3);
        var context = new MianbaoBombCompat.Launch(level, rack, BindingRef.capture(level, rack), WeaponType.BOMB, new net.minecraft.nbt.CompoundTag());
        var field = MianbaoBombCompat.class.getDeclaredField("LAUNCH"); field.setAccessible(true);
        var launch = (ThreadLocal<MianbaoBombCompat.Launch>) field.get(null);
        launch.set(context);
        try {
            for (int i = 0; i < 192; i++) {
                Entity bomb = BuiltInRegistries.ENTITY_TYPE.get(MianbaoBombCompat.entityType(WeaponType.BOMB)).create(level);
                bomb.setPos(8.5, 20000 + i * .01, 8.5); bomb.setDeltaMovement(10, 0, 0);
                require(!level.addFreshEntity(bomb), "Remote salvo entered native tracking");
            }
        } finally { launch.remove(); }
    }
    private static boolean noNativeBombs() {
        for (Entity entity : level.getAllEntities()) if (MianbaoBombCompat.entityWeapon(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())) != null) return false;
        return true;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void finish(Throwable failure) {
        finished = true; if (failure != null) StabilizedTurret.LOGGER.error("VIRTUAL BOMB FAILED", failure);
        try { Files.writeString(Path.of("virtual-bomb-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { if (level != null) level.getServer().halt(false); }
    }
}
