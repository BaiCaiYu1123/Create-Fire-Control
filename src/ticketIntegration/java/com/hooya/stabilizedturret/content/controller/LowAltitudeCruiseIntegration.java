package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Exercises the transformed Mianbao classes, not a copy of their steering math. */
final class LowAltitudeCruiseIntegration {
    private static final String GUIDED = "CreateFireControlCruiseGuided";
    private static final MianbaoCruiseMissileCompat.Type LOW =
            MianbaoCruiseMissileCompat.Type.LOW_ALTITUDE_CRUISE;

    static void check(ServerLevel level) throws Exception {
        Entity low = create(level, "cruisemissilelowtanshe");
        Entity nativeLow = create(level, "cruisemissilelowtanshe");
        Entity ordinary = create(level, "cruisemissiletanshe");
        low.getPersistentData().putBoolean(GUIDED, true);
        ordinary.getPersistentData().putBoolean(GUIDED, true);
        var bridge = Class.forName("net.mcreator.myfirstmod.procedures.FireControlGuidanceCompat")
                .getMethod("setNativeDeltaMovement", Entity.class, Vec3.class);
        Vec3 command = new Vec3(2, 1, 3);
        bridge.invoke(null, low, command);
        require(low.getDeltaMovement().equals(Vec3.ZERO), "native steering must not compete with rate-limited low cruise");
        bridge.invoke(null, ordinary, command);
        require(ordinary.getDeltaMovement().equals(Vec3.ZERO), "ordinary cruise must retain fire-control steering ownership");
        require(low.getPersistentData().getBoolean(GUIDED), "native steering must not clear ownership");

        // These fixtures are never added to the world; run the actual native
        // procedure in a loaded, unoccupied region with deterministic probes.
        BlockPos floor = new BlockPos(4100, 230, 4100);
        BlockPos obstacle = new BlockPos(4105, 260, 4100);
        var oldFloor = level.getBlockState(floor);
        var oldObstacle = level.getBlockState(obstacle);
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 2);
            Vec3 target = new Vec3(5100, 260, 5100);
            checkNativeIsolation(low, nativeLow, 15, new Vec3(0, 2, 0), target);
            checkNativeIsolation(low, nativeLow, 40, new Vec3(17.015, 0, 0), target);
            level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 2);
            checkNativeIsolation(low, nativeLow, 40, new Vec3(17.015, 0, 0), target);
            level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(floor, Blocks.AIR.defaultBlockState(), 2);
            checkNativeIsolation(low, nativeLow, 40, new Vec3(17.015, 0, 0), target);
            checkNativeIsolation(low, nativeLow, 40, new Vec3(17.015, 0, 0),
                    new Vec3(4120, 240, 4120));
            // A native procedure call must not synchronously create remote
            // terrain, including its terminal target isEmptyBlock query.
            int remote = 800000;
            require(level.getChunkSource().getChunkNow(remote >> 4, remote >> 4) == null,
                    "remote fixture already loaded");
            low.setPos(remote, 260, remote);
            low.getPersistentData().remove("CreateFireControlLowTrailTick");
            MianbaoCruiseMissileCompat.writeNativeTarget(low, LOW, new Vec3(remote + 1000, 60, remote));
            MianbaoCruiseMissileCompat.runNativeFlight(low, LOW);
            require(Double.isNaN(CruiseMissileGuidance.loadedGroundHeight(level, remote, remote)),
                    "unknown terrain must stay unknown");
            require(level.getChunkSource().getChunkNow(remote >> 4, remote >> 4) == null,
                    "terrain probes must never synchronously load chunks");
        } finally {
            level.setBlock(floor, oldFloor, 2);
            level.setBlock(obstacle, oldObstacle, 2);
        }
        StabilizedTurret.LOGGER.info("LOW CRUISE CHECK: PASS native steering isolation, one trail per tick, non-loading terrain probes and ordinary-cruise isolation");
    }

    private static void checkNativeIsolation(Entity guided, Entity nativeMissile, int age,
                                      Vec3 velocity, Vec3 target) {
        for (Entity missile : new Entity[]{guided, nativeMissile}) {
            missile.setPos(4100.5, 260, 4100.5);
            missile.setDeltaMovement(velocity);
            missile.getPersistentData().putDouble("fadongji", age);
            missile.getPersistentData().remove("CreateFireControlLowTrailTick");
            MianbaoCruiseMissileCompat.writeNativeTarget(missile, LOW, target);
            MianbaoCruiseMissileCompat.runNativeFlight(missile, LOW);
        }
        Vec3 actual = guided.getDeltaMovement();
        require(actual.distanceToSqr(velocity) < 1.0e-16,
                "native push/setDeltaMovement must not disturb the rate-limited command");
        require(nativeMissile.getDeltaMovement().distanceToSqr(velocity) > 1.0e-8,
                "uncontrolled low cruise must retain native flight");
        require(guided.getPersistentData().getDouble("fadongji") == age + 1,
                "native engine age must advance exactly once");
        MianbaoCruiseMissileCompat.runNativeFlight(guided, LOW);
        require(guided.getPersistentData().getDouble("fadongji") == age + 1,
                "fallback after native tick must not execute the trail twice");
    }

    private static Entity create(ServerLevel level, String id) {
        Entity result = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(
                "mianbaos_modernwarfare", id)).create(level);
        require(result != null, "missing native fixture " + id);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
