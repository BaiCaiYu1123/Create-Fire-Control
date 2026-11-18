package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Controlled motion isolates range cleanup; production restoration owns timed expiry. Never shipped. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MathematicalMissileLifetimeIntegration {
    private record Shot(String name, Entity missile, Vec3 origin, int lifetime) {}
    private static final List<Shot> bare = new ArrayList<>(), managed = new ArrayList<>();
    private static final List<String> report = new ArrayList<>(List.of("type,lifetime_ticks,removed_at,distance"));
    private static ServerLevel level;
    private static Entity orphan;
    private static boolean done;
    private static int ticks;

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        try {
            for (var type : BuiltInRegistries.ENTITY_TYPE) {
                var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (!key.getNamespace().equals("mianbaos_modernwarfare")) continue;
                if (!key.getPath().contains("tanshe") && !key.getPath().startsWith("agmmissile_")) continue;
                Entity candidate = type.create(level);
                if (!GuidedMissileMode.eligible(candidate)) continue;
                spawn(candidate, key.getPath(), false, 240);
                spawn(type.create(level), key.getPath() + "@Y1000", false, 1000);
                if (MianbaoAirDefenseCompat.isHighSpeedMissile(candidate)
                        || MianbaoAircraftCompat.typeOf(candidate) != null) {
                    spawn(type.create(level), key.getPath(), true, 240);
                    spawn(type.create(level), key.getPath() + "@Y1000", true, 1000);
                }
            }
            require(bare.size() >= 44 && managed.size() >= 28, "Missing installed missile fixtures");
            verifyNativeLimits();
        } catch (Throwable failure) { finish(failure); }
    }

    private static void spawn(Entity missile, String name, boolean restore, double altitude) {
        int index = bare.size() + managed.size();
        Vec3 origin = new Vec3(100000.5, altitude, 100000.5 + index * 64);
        missile.setPos(origin); missile.setDeltaMovement(34.03, 0, 0);
        missile.setNoGravity(true); missile.noPhysics = true;
        var data = missile.getPersistentData();
        data.putString(GuidedMissileMode.ROUTE, "math");
        data.putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
        boolean aa = MianbaoAirDefenseCompat.isHighSpeedMissile(missile);
        boolean cruise = MianbaoCruiseMissileCompat.isMissile(missile);
        data.putBoolean(aa ? "CreateFireControlAaGuided" : cruise
                ? "CreateFireControlCruiseGuided" : "CreateFireControlAircraftGuided", true);
        int lifetime = 0;
        if (restore) {
            data.putLong(aa ? "CreateFireControlAaController" : "CreateFireControlAircraftController", new BlockPos(0, 200, 0).asLong());
            data.putBoolean(aa ? "CreateFireControlAaControllerSubLevelKnown" : "CreateFireControlAircraftControllerSubLevelKnown", true);
            data.putUUID(aa ? "CreateFireControlAaTarget" : "CreateFireControlAircraftRadarTarget", UUID.randomUUID());
            data.putDouble(aa ? "CreateFireControlAaFuse" : "CreateFireControlAircraftFuse", 0);
            lifetime = aa ? MianbaoAirDefenseCompat.getLifetimeTicks(missile)
                    : MianbaoAircraftCompat.lifetimeTicks(MianbaoAircraftCompat.typeOf(missile));
        }
        require(!level.addFreshEntity(missile) && MathematicalMissileManager.contains(missile), name + " not mathematical");
        require(level.getEntity(missile.getUUID()) == null, name + " registered as world entity");
        (restore ? managed : bare).add(new Shot(name, missile, origin, lifetime));
    }

    private static void verifyNativeLimits() {
        // Actual installed entity ticks exercise the native base-class Mixins,
        // independently of the detached mathematical movement manager.
        for (Shot shot : bare) {
            if (shot.name.endsWith("@Y1000")) continue;
            Entity nativeShot = shot.missile.getType().create(level);
            nativeShot.setPos(8.5, 380, 8.5); nativeShot.setDeltaMovement(0, 10, 0);
            nativeShot.setNoGravity(true); nativeShot.noPhysics = true;
            nativeShot.getPersistentData().putString(GuidedMissileMode.ROUTE, "entity");
            nativeShot.getPersistentData().putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
            nativeShot.getPersistentData().putBoolean(MianbaoCruiseMissileCompat.isMissile(nativeShot)
                    ? "CreateFireControlCruiseGuided" : MianbaoAirDefenseCompat.isMissile(nativeShot)
                    ? "CreateFireControlAaGuided" : "CreateFireControlAircraftGuided", true);
            nativeShot.getPersistentData().putBoolean("jamed", true);
            nativeShot.getPersistentData().putDouble("time", 100);
            nativeShot.getPersistentData().putDouble("fadongji", 100);
            nativeShot.getPersistentData().putDouble("fadongji1", 100);
            nativeShot.tickCount = 20;
            nativeShot.tick();
            require(!nativeShot.isRemoved() && nativeShot.getY() >= 390, shot.name + " native shot removed above Y384");
            nativeShot.setPos(6000, 1000, 8.5); nativeShot.setDeltaMovement(Vec3.ZERO);
            nativeShot.tick();
            require(!nativeShot.isRemoved() && nativeShot.getY() == 1000, shot.name + " native range/height cleanup remains");
            nativeShot.tickCount = 1201;
            nativeShot.tick();
            require(nativeShot.isRemoved(), shot.name + " native time cleanup suppressed");
        }
        Entity control = bare.getFirst().missile.getType().create(level);
        control.setPos(8.5, 1000, 8.5); control.noPhysics = true; control.setNoGravity(true);
        control.tick();
        require(control.isRemoved(), "Unadopted native cleanup changed");
        for (boolean belowWorld : new boolean[]{true, false}) {
            Entity invalid = bare.getFirst().missile.getType().create(level);
            invalid.setPos(belowWorld ? 8.5 : level.getWorldBorder().getMaxX() + 100,
                    belowWorld ? level.getMinBuildHeight() - 65 : 1000, 8.5);
            invalid.noPhysics = true; invalid.setNoGravity(true);
            invalid.getPersistentData().putBoolean("CreateFireControlAircraftGuided", true);
            invalid.getPersistentData().putBoolean("CreateFireControlAaGuided", true);
            invalid.tick();
            require(invalid.isRemoved(), "Native void/world-border cleanup changed");
        }
        // A real hit must still run its existing detonation/removal path.
        Entity hit = bare.getFirst().missile.getType().create(level);
        hit.setPos(8.5, 1000, 8.5); hit.tickCount = 30;
        hit.getPersistentData().putBoolean("CreateFireControlAaGuided", true);
        hit.getPersistentData().putBoolean("CreateFireControlAircraftGuided", true);
        hit.getPersistentData().putInt("CreateFireControlSafetyTicks", 0);
        ((com.hooya.stabilizedturret.mixin.MathematicalMissileImpactAccess) hit).createFireControl$impact(
                new net.minecraft.world.phys.BlockHitResult(hit.position(), net.minecraft.core.Direction.DOWN,
                        hit.blockPosition(), false));
        require(hit.isRemoved(), "Normal adopted impact removal changed");
        StabilizedTurret.LOGGER.info("NATIVE LIMITS PASS: adopted native ticks cross Y384/range, timed cleanup and unadopted/impact cleanup preserved");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void before(ServerTickEvent.Pre event) {
        if (level == null || done) return;
        ticks++;
        for (var group : List.of(bare, managed)) for (Shot shot : group) {
            if (shot.missile.isRemoved()) continue;
            // Constant controlled motion reaches 4096 before even the shortest
            // lifetime. Guidance, fuzes and expiration still run normally.
            shot.missile.setDeltaMovement(34.03, 0, 0);
            shot.missile.setNoGravity(true);
            shot.missile.getPersistentData().putBoolean("jamed", true);
            shot.missile.getPersistentData().putDouble("fadongji", 100);
            shot.missile.getPersistentData().putDouble("fadongji1", 100);
            shot.missile.getPersistentData().putDouble("time", 100);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void moveBare(ServerTickEvent.Post event) {
        if (level == null || done) return;
        try {
            for (Shot shot : bare) if (!shot.missile.isRemoved()) MathematicalMissileManager.advance(shot.missile);
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void verify(ServerTickEvent.Post event) {
        if (level == null || done) return;
        try {
            for (Shot shot : managed) {
                require(shot.missile.isRemoved() == (ticks >= shot.lifetime),
                        shot.name + " wrong expiry at tick " + ticks + ", expected " + shot.lifetime);
                if (ticks == shot.lifetime - 1) require(shot.missile.position().distanceTo(shot.origin) > 4096,
                        shot.name + " did not travel beyond old radius");
                if (ticks == shot.lifetime) report.add(shot.name + "," + shot.lifetime + "," + ticks + ","
                        + shot.missile.position().distanceTo(shot.origin));
            }
            if (ticks == 150) {
                for (Shot shot : bare) require(!shot.missile.isRemoved()
                                && shot.missile.position().distanceTo(shot.origin) > 5000,
                        shot.name + " still limited by 4096 metres");
                orphan = bare.getFirst().missile;
                bare.stream().filter(shot -> shot.missile != orphan).forEach(shot -> shot.missile.discard());
                orphan.tickCount = 1199;
                StabilizedTurret.LOGGER.info("MISSILE RANGE PASS: {} installed mathematical types alive beyond 5000 metres", bare.size());
            }
            if (ticks == 151) require(!orphan.isRemoved() && orphan.tickCount == 1200, "Fallback expired early");
            if (ticks == 152) require(orphan.isRemoved() && orphan.tickCount == 1201, "Timed fallback lost");
            if (ticks == 401) {
                for (Shot shot : managed) {
                    var pos = shot.missile.blockPosition();
                    require(level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null,
                            "Mathematical flight loaded a remote chunk");
                }
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void finish(Throwable failure) {
        done = true;
        for (var group : List.of(bare, managed)) group.forEach(shot -> shot.missile.discard());
        String result = failure == null ? "PASS: " + bare.size() + " types beyond 5000m; " + managed.size()
                + " production guidance/recovery lifetimes; 60s fallback; no flight chunk loading" : "FAIL: " + failure;
        if (failure != null) StabilizedTurret.LOGGER.error("MISSILE LIFETIME FAILED", failure);
        else StabilizedTurret.LOGGER.info("MISSILE LIFETIME {}", result);
        try {
            Files.write(Path.of("missile-lifetime-scenarios.csv"), report);
            Files.writeString(Path.of("missile-lifetime-result.txt"), result);
        } catch (Exception error) { throw new RuntimeException(error); }
        finally { level.getServer().halt(false); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
