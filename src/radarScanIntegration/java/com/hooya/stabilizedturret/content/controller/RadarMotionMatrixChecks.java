package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;

/** All relative-motion combinations, exercising production controller and seeker state. */
final class RadarMotionMatrixChecks {
    static void check(ServerLevel level) throws Exception {
        long before = level.getGameTime();
        var worlds = RadarVisibility.class.getDeclaredField("WORLDS"); worlds.setAccessible(true);
        try {
            int scenario = 0;
            for (boolean sourceMoving : new boolean[]{false, true}) for (boolean targetMoving : new boolean[]{false, true}) {
                for (boolean aircraft : new boolean[]{true, false})
                    radar(level, worlds, scenario++, sourceMoving, targetMoving, aircraft);
                locks(level, worlds, scenario++, sourceMoving, targetMoving);
            }
        } finally {
            ((Map<?, ?>) worlds.get(null)).remove(level);
            level.getServer().getWorldData().overworldData().setGameTime(before);
        }
    }
    private static void radar(ServerLevel level, Field worlds, int scenario, boolean sourceMoving,
                              boolean targetMoving, boolean aircraft) throws Exception {
        ((Map<?, ?>) worlds.get(null)).remove(level);
        Vec3 start = new Vec3(2_100_000.5 + scenario * 8192, 200.75, 2_100_000.5), point = start.add(0, 0, 4096);
        SubLevel body = RadarVisibilityIntegrationChecks.body(level, point);
        try {
            var radar = new Computer(start, aircraft); radar.setLevel(level);
            set(radar, "aircraftRadarRange", 20_000.0); set(radar, "radarRange", 20_000.0); set(radar, "radarDepression", 90.0);
            Method scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
            boolean found = false;
            for (int i = 0; i < 500; i++) {
                next(level); scan.invoke(radar);
                if (radar.getRadarContact(body.getUniqueId()) != null) { found = true; break; }
                Thread.sleep(3);
            }
            require(found, "matrix radar initial acquisition"); set(radar, "radarLockedSubLevelId", body.getUniqueId());
            for (int i = 1; i <= 60; i++) {
                radar.origin = start.add(sourceMoving ? i * 9.75 : 0, 0, 0);
                move(body, point.add(targetMoving ? i * 6.5 : 0, 0, 0));
                next(level); scan.invoke(radar); Thread.sleep(25);
                require(body.getUniqueId().equals(radar.getRadarLockedSubLevelId()),
                        "radar matrix " + aircraft + " sourceMoving=" + sourceMoving + " targetMoving=" + targetMoving + " scan=" + i);
            }
            BlockPos wall = BlockPos.containing(radar.origin.lerp(StabilizerControllerBlockEntity.subLevelWorldBounds(body).getCenter(), .5));
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            for (int i = 0; i < 8; i++) { next(level); scan.invoke(radar); Thread.sleep(10); }
            require(radar.getRadarLockedSubLevelId() == null, "moving matrix must still unlock behind real blocks");
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            StabilizedTurret.LOGGER.info("RADAR MOTION MATRIX PASS: aircraft={} sourceMoving={} targetMoving={} 4km 60 continuous locks and wall unlock", aircraft, sourceMoving, targetMoving);
        } finally { SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED); }
    }
    @SuppressWarnings("unchecked")
    private static void locks(ServerLevel level, Field worlds, int scenario, boolean sourceMoving, boolean targetMoving) throws Exception {
        ((Map<?, ?>) worlds.get(null)).remove(level);
        Vec3 start = new Vec3(2_100_000.5 + scenario * 8192, 200.75, 2_100_000.5), point = start.add(0, 0, 1500);
        SubLevel body = RadarVisibilityIntegrationChecks.body(level, point);
        Computer ir = new Computer(start, true), ag = new Computer(start, true); ir.setLevel(level); ag.setLevel(level);
        Method acquireIr = InfraredTargeting.class.getDeclaredMethod("acquire", StabilizerControllerBlockEntity.class, InfraredTargeting.Frame.class, long.class); acquireIr.setAccessible(true);
        Method acquireAg = StabilizerControllerBlockEntity.class.getDeclaredMethod("findAirToGroundTargetInPodFrame"); acquireAg.setAccessible(true);
        Class<?> stateType = Class.forName(InfraredTargeting.class.getName() + "$State");
        var ctor = stateType.getDeclaredConstructor(); ctor.setAccessible(true); Object state = ctor.newInstance();
        Field states = InfraredTargeting.class.getDeclaredField("STATES"); states.setAccessible(true);
        try {
            var frame = frame(start, StabilizerControllerBlockEntity.subLevelWorldBounds(body).getCenter());
            InfraredSeeker.Track track = null; StabilizerControllerBlockEntity.GroundTargetSnapshot ground = null;
            set(ag, "hasPodStabilizedWorldTarget", true); set(ag, "podStabilizedWorldTarget", point);
            for (int i = 0; i < 400 && (track == null || ground == null); i++) {
                next(level);
                if (track == null) track = (InfraredSeeker.Track) acquireIr.invoke(null, ir, frame, level.getGameTime());
                if (ground == null) ground = (StabilizerControllerBlockEntity.GroundTargetSnapshot) acquireAg.invoke(ag);
                Thread.sleep(3);
            }
            require(track != null && ground != null, "IR and AGM cold acquisition before motion");
            setState(state, "track", track); setState(state, "frame", frame); setState(state, "lastPosition", track.center());
            ((Map<StabilizerControllerBlockEntity, Object>) states.get(null)).put(ir, state);
            set(ag, "airToGroundLockedSubLevelId", ground.id()); set(ag, "airToGroundLockedMembers", ground.memberIds());
            int irLaunches = 0, agLaunches = 0;
            for (int i = 1; i <= 40; i++) {
                ir.origin = ag.origin = start.add(sourceMoving ? i * 9.75 : 0, 0, 0);
                move(body, point.add(targetMoving ? i * 6.5 : 0, 0, 0)); next(level);
                Vec3 center = StabilizerControllerBlockEntity.subLevelWorldBounds(body).getCenter();
                setState(state, "frame", frame(ir.origin, center)); setState(state, "receivedAt", level.getGameTime());
                set(ag, "podStabilizedWorldTarget", center);
                require(InfraredTargeting.target(ir) != null, "IR motion lock sourceMoving=" + sourceMoving + " targetMoving=" + targetMoving + " scan=" + i);
                require(ag.getAirToGroundTargetSnapshot() != null, "AGM motion lock sourceMoving=" + sourceMoving + " targetMoving=" + targetMoving + " scan=" + i);
                if (i % 10 == 0) {
                    if (InfraredTargeting.launchTarget(ir) != null) irLaunches++;
                    if (ag.getAirToGroundLaunchTargetSnapshot() != null) agLaunches++;
                    require(InfraredTargeting.target(ir) != null && ag.getAirToGroundTargetSnapshot() != null,
                            "pending fresh launch checks must not erase unobstructed IR/AGM tracks");
                }
                Thread.sleep(25);
            }
            require(irLaunches > 0 && agLaunches > 0, "both moving IR and AGM launch paths must confirm before stopping");
            // Settle the current path before proving a wall, then test fresh same-tick validation.
            for (int i = 0; i < 8; i++) {
                next(level); setState(state, "receivedAt", level.getGameTime());
                require(InfraredTargeting.target(ir) != null && ag.getAirToGroundTargetSnapshot() != null, "settled IR/AGM tracks"); Thread.sleep(10);
            }
            require(InfraredTargeting.launchTarget(ir) != null && ag.getAirToGroundLaunchTargetSnapshot() != null, "both unobstructed launch paths eventually confirm");
            Vec3 center = StabilizerControllerBlockEntity.subLevelWorldBounds(body).getCenter();
            BlockPos wall = BlockPos.containing(ir.origin.lerp(center, .5));
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            require(InfraredTargeting.launchTarget(ir) == null && ag.getAirToGroundLaunchTargetSnapshot() == null, "same-tick wall prevents both launches");
            next(level); setState(state, "receivedAt", level.getGameTime());
            require(InfraredTargeting.target(ir) == null && ag.getAirToGroundTargetSnapshot() == null, "IR/AGM locks actually end behind wall");
            level.setBlockAndUpdate(wall, Blocks.WATER.defaultBlockState());
            next(level);
            require(acquireIr.invoke(null, ir, frame(ir.origin, center), level.getGameTime()) != null && acquireAg.invoke(ag) != null,
                    "moving IR/AGM can reacquire through water");
            level.setBlockAndUpdate(wall, Blocks.LAVA.defaultBlockState()); next(level);
            require(acquireIr.invoke(null, ir, frame(ir.origin, center), level.getGameTime()) != null && acquireAg.invoke(ag) != null,
                    "moving IR/AGM can acquire through lava");
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            StabilizedTurret.LOGGER.info("MISSILE LOCK MOTION MATRIX PASS: sourceMoving={} targetMoving={} 40 IR/AGM locks, moving launch confirmations IR={} AGM={}, same-tick wall rejection, water/lava reacquisition", sourceMoving, targetMoving, irLaunches, agLaunches);
        } finally {
            InfraredTargeting.clear(ir); SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        }
    }
    private static InfraredTargeting.Frame frame(Vec3 origin, Vec3 center) {
        Vec3 forward = center.subtract(origin).normalize(), right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        return new InfraredTargeting.Frame(origin, forward, right, up, .7, 16.0 / 9, 1280, 720);
    }
    private static void move(SubLevel body, Vec3 point) { body.logicalPose().position().set(point.x, point.y, point.z); body.updateBoundingBox(); body.forceUpdateGlobalBounds(); }
    private static void next(ServerLevel level) { level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 2); }
    private static void set(Object owner, String name, Object value) throws Exception { Field f = StabilizerControllerBlockEntity.class.getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private static void setState(Object owner, String name, Object value) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Computer extends StabilizerControllerBlockEntity {
        Vec3 origin; final boolean aircraft;
        Computer(Vec3 origin, boolean aircraft) { super(BlockPos.containing(origin), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()); this.origin = origin; this.aircraft = aircraft; }
        @Override public boolean isRadarActive() { return true; }
        @Override public boolean isAircraftRadarActive() { return aircraft; }
        @Override public boolean isInfraredWeaponArmed() { return true; }
        @Override public Vec3 getInfraredAircraftOrigin() { return origin; }
        @Override public Vec3 getPodCameraPosition() { return origin; }
        @Override public boolean isAircraftRadarMode() { return aircraft; }
        @Override public Vec3 getRadarOrigin() { return origin; }
        @Override public Vec3 getAircraftRadarForward() { return new Vec3(0, 0, 1); }
        @Override public Vec3 getAircraftRadarUp() { return new Vec3(0, 1, 0); }
    }
}
