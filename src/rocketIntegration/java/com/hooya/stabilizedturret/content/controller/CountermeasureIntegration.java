package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Uses the installed native mod to verify launchers, trail equivalence and skipped ticks. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CountermeasureIntegration {
    private static ServerLevel level;
    private static Entity fallback;
    private static java.util.List<Entity> salvo;
    private static int tick;
    private static boolean finished;
    private static boolean loggedEarlyRemoval;

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("create_fire_control.countermeasureChecks")) return;
        level = event.getServer().overworld();
        try {
            level.getChunk(0, 0);
            verifyNativeTrail();
            fireLauncher("countermeasurelauncher", new BlockPos(3, 200, 3));
            fireLauncher("countermeasurelauncher_2", new BlockPos(8, 200, 3));
            Set<UUID> existing = new java.util.HashSet<>();
            for (var flare : MianbaoCountermeasureManager.snapshotTracked()) existing.add(flare.entity().getUUID());
            require(MianbaoCountermeasureCompat.spawnBurst(level, new Vec3(8, 300, 8), 192) == 192,
                    "192-flare burst failed");
            salvo = MianbaoCountermeasureManager.snapshotTracked().stream().map(value -> value.entity())
                    .filter(entity -> !existing.contains(entity.getUUID())).toList();
            require(salvo.size() == 192, "native burst was not registered in full");
            // Keep lifetime verification separate from native projectile-on-
            // projectile contacts at a common burst origin.
            for (int index = 0; index < salvo.size(); index++) {
                salvo.get(index).setPos(8 + (index % 16) * 3, 300, 8 + (index / 16) * 3);
                salvo.get(index).setDeltaMovement(0, -1, 0);
            }
            fallback = flare();
            fallback.setPos(8, 2000, 8);
            fallback.setDeltaMovement(0.4, 0.5, 0.2);
            fallback.setNoGravity(true);
            Set<UUID> carriers = Set.of(UUID.randomUUID(), UUID.randomUUID());
            // Do not add to world: every flight step must use the missed-tick path.
            MianbaoCountermeasureManager.registerLaunched(fallback, carriers);
            require(MianbaoCountermeasureManager.snapshotTracked().stream().anyMatch(value ->
                    value.entity() == fallback && value.carrierIds().equals(carriers)), "carrier association lost");
            StabilizedTurret.LOGGER.info("FLARE CHECK: native launchers consumed ammo, 192-flare burst registered, carrier association retained");
        } catch (Throwable error) { finish(error); }
    }

    private static void fireLauncher(String id, BlockPos pos) {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", id))
                .defaultBlockState();
        require(!state.isAir(), "native launcher missing: " + id);
        if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, Direction.UP);
        var reload = state.getBlock().getStateDefinition().getProperty("reload");
        if (reload instanceof net.minecraft.world.level.block.state.properties.BooleanProperty lock) {
            state = state.setValue(lock, false);
        }
        level.setBlock(pos, state, 3);
        var blockEntity = level.getBlockEntity(pos);
        require(blockEntity != null, "launcher block entity missing");
        blockEntity.getPersistentData().putDouble("ammo", 2);
        BindingRef ref = BindingRef.capture(level, pos);
        MianbaoCountermeasureCompat.fireOne(level, ref);
        require(MianbaoCountermeasureCompat.getAmmoCount(level, ref) == 1, "native launcher did not consume exactly one: " + id);
    }

    private static void verifyNativeTrail() throws ReflectiveOperationException {
        Entity nativeFlare = flare(), managed = flare();
        nativeFlare.setPos(8, 300, 8);
        managed.setPos(8, 300, 8);
        nativeFlare.setDeltaMovement(0.4, 0.7, -0.2);
        managed.setDeltaMovement(nativeFlare.getDeltaMovement());
        Method procedure = Class.forName("net.mcreator.myfirstmod.procedures.CountermeasureTtrailProcedure")
                .getMethod("execute", net.minecraft.world.level.LevelAccessor.class,
                        double.class, double.class, double.class, Entity.class);
        procedure.invoke(null, level, 8.0, 300.0, 8.0, nativeFlare);
        MianbaoCountermeasureCompat.runManagedTrail(managed);
        require(nativeFlare.getDeltaMovement().distanceTo(managed.getDeltaMovement()) < 1e-8,
                "compact managed trail differs from installed native motion");
        require(nativeFlare.isNoGravity() == managed.isNoGravity()
                        && nativeFlare.getPersistentData().getDouble("time") == managed.getPersistentData().getDouble("time"),
                "native gravity/lifetime differs");
        MianbaoCountermeasureCompat.runManagedTrail(managed);
        require(managed.getPersistentData().getDouble("time") == 1, "duplicate tick aged flare twice");
        managed.getPersistentData().putBoolean(MianbaoCountermeasureCompat.TRACKED_TAG, true);
        managed.getPersistentData().remove("CreateFireControlFlareTrailTick");
        procedure.invoke(null, level, 8.0, 300.0, 8.0, managed);
        require(managed.getPersistentData().getDouble("time") == 2, "managed native procedure hook failed");
        Entity nativeTick = flare(), skippedTick = flare();
        nativeTick.setPos(8, 300, 8);
        skippedTick.setPos(8, 300, 8);
        nativeTick.setDeltaMovement(0.4, 0.7, -0.2);
        skippedTick.setDeltaMovement(nativeTick.getDeltaMovement());
        nativeTick.tick();
        MianbaoMissileCompat.advanceWithoutRootChunkCollision(skippedTick);
        MianbaoCountermeasureCompat.applySkippedArrowPhysics(skippedTick);
        MianbaoCountermeasureCompat.runManagedTrail(skippedTick);
        require(nativeTick.position().distanceTo(skippedTick.position()) < 1e-8
                        && nativeTick.getDeltaMovement().distanceTo(skippedTick.getDeltaMovement()) < 1e-7,
                "missed arrow tick differs from native air flight");
        StabilizedTurret.LOGGER.info("FLARE CHECK: PASS installed native motion/lifetime match and once-per-tick guard");
    }

    private static Entity flare() {
        Entity result = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mianbaos_modernwarfare:countermeasure")).create(level);
        require(result != null, "native flare type missing");
        return result;
    }

    @SubscribeEvent
    public static void leave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!loggedEarlyRemoval && !finished && salvo != null && salvo.contains(entity)
                && entity.getRemovalReason() == Entity.RemovalReason.DISCARDED
                && entity.getPersistentData().getDouble("time") < 100) {
            loggedEarlyRemoval = true;
            StabilizedTurret.LOGGER.error("FLARE CHECK unexpected early removal", new Exception("native removal path"));
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || finished) return;
        try {
            level.resetEmptyTime();
            tick++;
            require(tick < 300, "flare lifetime cleanup timed out");
            if (tick == 8) {
                require(fallback.getPersistentData().getDouble("time") >= 7, "missed native ticks did not advance age");
                require(fallback.position().distanceTo(new Vec3(8, 2000, 8)) > 1, "fallback flare stopped moving");
                long trackedSalvo = salvo.stream().filter(entity ->
                        MianbaoCountermeasureManager.isTrackedId(entity.getUUID())).count();
                if (trackedSalvo != 192) {
                    for (Entity flare : salvo) if (flare.isRemoved()) {
                        StabilizedTurret.LOGGER.error("FLARE CHECK early removal reason={} age={} at={}",
                                flare.getRemovalReason(), flare.getPersistentData().getDouble("time"), flare.position());
                        break;
                    }
                    throw new AssertionError("burst lost tracked flares: " + trackedSalvo);
                }
            }
            if (tick < 210 || !MianbaoCountermeasureManager.snapshotTracked().isEmpty()) return;
            require(fallback.isRemoved(), "fallback flare did not expire");
            require(!MianbaoCountermeasureManager.isTrackedId(fallback.getUUID()), "UUID index leaked");
            var chunks = MianbaoCountermeasureManager.class.getDeclaredField("CHUNK_REFERENCES");
            chunks.setAccessible(true);
            require(((java.util.Map<?, ?>) chunks.get(null)).isEmpty(), "chunk reference count leaked after expiry");
            StabilizedTurret.LOGGER.info("FLARE CHECK: PASS 192-flare salvo, skipped native ticks, 200-tick expiry, clean UUID/ticket release");
            finish(null);
        } catch (Throwable error) { finish(error); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void finish(Throwable error) {
        finished = true;
        if (error != null) StabilizedTurret.LOGGER.error("FLARE CHECK FAILED", error);
        try { Files.writeString(Path.of("countermeasure-integration-result.txt"), error == null ? "PASS" : "FAIL: " + error); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        finally { if (level != null) level.getServer().halt(false); }
    }
}
