package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.mixin.MathematicalMissileImpactAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real native rack releases and the native bridge; only carrier motion/radar observations are seeded. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class FarIgnitionHeadingIntegration {
    private static ServerLevel level;
    private static Entity captured;
    private static boolean capturing;
    private static final List<String> report = new ArrayList<>();
    private static final List<String> csv = new ArrayList<>(List.of("scenario,tick,phase,x,y,z,vx,vy,vz"));

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void join(EntityJoinLevelEvent event) {
        if (capturing && MianbaoAircraftCompat.typeOf(event.getEntity()) == MianbaoAircraftCompat.WeaponType.FAR)
            captured = event.getEntity();
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        long originalTime = level.getGameTime();
        Throwable failure = null;
        try {
            int index = 0;
            for (String rack : List.of("far_missile_1rack", "far_missile_2rack"))
                for (boolean math : new boolean[]{false, true})
                    for (int pose = 0; pose < 3; pose++) run(rack, math, pose, index++);
            checkMetadata();
            report.add("PASS: 12 real launches; both far variants and flight routes; level low-altitude, tilted/inherited-motion and spinning releases; first powered displacement, clearance heading, speed, one-shot correction, restore and math handoff; ignition-based 0.5s safety, native impacts, math obstacle collisions and tickCount-reset persistence");
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("FAR IGNITION HEADING CHECK FAILED", error);
        } finally {
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
            try {
                Files.write(Path.of("far-ignition-heading-details.txt"), report);
                Files.write(Path.of("far-ignition-heading-trajectories.csv"), csv);
                Files.writeString(Path.of("far-ignition-heading-result.txt"), failure == null ? "PASS" : "FAIL: " + failure);
            } catch (Exception error) { throw new RuntimeException(error); }
            if (captured != null) captured.discard();
            level.getServer().halt(false);
        }
    }

    private static void run(String rackPath, boolean math, int pose, int index) throws Exception {
        String name = rackPath + "/" + (math ? "math" : "entity") + "/pose" + pose;
        BlockPos rackPos = new BlockPos(32 + index * 32, pose == 0 ? 7 : 80, 32);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            level.getChunk(rackPos.getX() / 16 + dx, rackPos.getZ() / 16 + dz);
        BlockPos computerPos = rackPos.west(4), radarPos = rackPos.west(6);
        level.setBlockAndUpdate(computerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(radarPos, BuiltInRegistries.BLOCK.get(id("jetradar")).defaultBlockState());
        var computer = (StabilizerControllerBlockEntity) level.getBlockEntity(computerPos);
        var rackState = BuiltInRegistries.BLOCK.get(id(rackPath)).defaultBlockState();
        rackState = rackState.hasProperty(BlockStateProperties.FACING)
                ? rackState.setValue(BlockStateProperties.FACING, Direction.EAST)
                : rackState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlockAndUpdate(rackPos, rackState);
        BindingRef rack = BindingRef.capture(level, rackPos);
        computer.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, rack);
        computer.bind(BindingSlot.AIRCRAFT_RADAR, BindingRef.capture(level, radarPos));
        UUID target = UUID.randomUUID();
        Vec3 targetPoint = Vec3.atCenterOf(rackPos).add(3000, 100, 2000);
        var contact = new StabilizerControllerBlockEntity.RadarContact(target, targetPoint, Vec3.ZERO,
                new AABB(targetPoint, targetPoint).inflate(2), Set.of(target));
        set(computer, "radarContacts", List.of(contact));
        set(computer, "aircraftSelectedTargetId", target);
        set(computer, "lastRadarScanTick", level.getGameTime());
        set(computer, "aircraftProximityFuseRange", 0.0);
        set(computer, "aircraftFuseSafetySeconds", .5);
        ControllerTracker.add(computer);
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(),
                "cfc missile mode " + (math ? "math" : "entity"));
        require(computer.isAircraftWeaponGuidanceReady(MianbaoAircraftCompat.WeaponType.FAR), name + " radar readiness");
        capturing = true; captured = null;
        try { require(MianbaoAircraftCompat.fireOne(level, rack, MianbaoAircraftCompat.WeaponType.FAR), name + " native fire"); }
        finally { capturing = false; }
        Entity missile = captured;
        require(missile != null && missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided"), name + " adoption");
        require(!MathematicalMissileManager.contains(missile), name + " drop remains a native entity");
        CompoundTag release = missile.getPersistentData().getCompound(FarMissileIgnitionHeading.RELEASE_TAG);
        require(!release.isEmpty() && !release.getBoolean("SoundPlayed"), name + " native delayed release metadata");
        Quaterniond q = new Quaterniond().rotationY(1.5 * Math.PI).rotateX(pose == 0 ? 0 : .2).rotateZ(pose == 0 ? 0 : .5);
        release.putDouble("QX", q.x); release.putDouble("QY", q.y);
        release.putDouble("QZ", q.z); release.putDouble("QW", q.w);
        Vec3 angular = pose == 2 ? new Vec3(.01, .015, .02) : Vec3.ZERO;
        release.putDouble("WX", angular.x); release.putDouble("WY", angular.y); release.putDouble("WZ", angular.z);
        Vec3 inherited = pose == 0 ? Vec3.ZERO : new Vec3(2.5, -.35, 1.2);
        missile.setDeltaMovement(inherited);
        Vec3 releasePosition = missile.position();
        Object state = guided().get(missile);
        require(state != null, name + " production state");
        int dropTicks = 0;
        while (!release.getBoolean("SoundPlayed")) {
            require(dropTicks++ < 20, name + " ignition timeout");
            advanceClock();
            MianbaoAircraftGuidanceHandler.beforeTick(pre());
            require(!missile.getPersistentData().getBoolean(FarMissileIgnitionHeading.ALIGNED_TAG), name + " no unpowered correction");
            // Exercise native ticking and the bridge's off-screen integrator on alternating releases.
            if (pose != 2) { missile.tickCount++; missile.tick(); }
            MianbaoAircraftGuidanceHandler.tick(post());
            record(name, dropTicks, "drop", missile);
            require((int) get(state, "age") == 0, name + " powered guidance clock paused");
            if (!release.getBoolean("SoundPlayed")) {
                require(!MissileFuseSafety.armed(missile, 0), name + " even zero-delay fuzes wait for ignition");
                require(!MissileFuseSafety.armed(missile, 10), name + " free fall does not consume safety");
            }
        }
        require(missile.getY() < releasePosition.y, name + " actual free fall preserved");
        require(!missile.getPersistentData().getBoolean(FarMissileIgnitionHeading.ALIGNED_TAG), name + " bridge reached ignition");
        int ignitionAge = release.contains("IgnitionDropAge") ? release.getInt("IgnitionDropAge") : release.getInt("Age");
        Vector3d initial = q.transform(new Vector3d(0, 0, -1));
        Vec3 expected = new Vec3(initial.x, initial.y, initial.z);
        if (angular.lengthSqr() > 0) {
            // Rodrigues on the forward vector independently checks world-space spin order/sign.
            Vec3 axis = angular.normalize();
            double angle = angular.length() * ignitionAge;
            expected = expected.scale(Math.cos(angle)).add(axis.cross(expected).scale(Math.sin(angle)))
                    .add(axis.scale(axis.dot(expected) * (1 - Math.cos(angle))));
        }
        double ignitionSpeed = missile.getDeltaMovement().length();
        require(MissileFuseSafety.armed(missile, 0) && !MissileFuseSafety.armed(missile, 10), name + " ignition starts safety at zero");
        Vec3 beforeCorrection = missile.getDeltaMovement();
        require(beforeCorrection.normalize().distanceTo(expected) > .02, name + " reproduces inherited downward/sideways heading");
        // The registered Pre handler must run before native powered movement.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(pre());
        near(missile.getDeltaMovement(), expected.scale(ignitionSpeed), name + " ignition heading and scalar speed");
        near((Vec3) get(state, "worldVelocity"), missile.getDeltaMovement(), name + " guidance state velocity");
        near((Vec3) get(state, "flightDirection"), expected, name + " guidance state direction");
        require(Math.abs((double) get(state, "initialSpeed") - ignitionSpeed) < 1e-9, name + " acceleration starts at ignition speed");
        require(missile.getPersistentData().getBoolean(FarMissileIgnitionHeading.ALIGNED_TAG), name + " persisted one-shot marker");
        Vec3 ignitionPosition = missile.position();
        for (int powered = 1; powered <= 12; powered++) {
            advanceClock();
            MianbaoAircraftGuidanceHandler.beforeTick(pre());
            Vec3 from = missile.position();
            if (!MathematicalMissileManager.contains(missile)) { missile.tickCount++; missile.tick(); }
            MianbaoAircraftGuidanceHandler.tick(post());
            require(!missile.isRemoved(), name + " survives powered flight " + powered);
            require(MissileFuseSafety.armed(missile, 10) == (powered >= 10), name + " exact ignition-based safety boundary " + powered);
            if (powered < 10) {
                impact(missile);
                require(!missile.isRemoved(), name + " native collision stays safe through powered tick " + powered);
            }
            if (powered == 3 || powered == 10) {
                Entity restored = missile.getType().create(level);
                restored.setPos(missile.position());
                restored.getPersistentData().merge(missile.getPersistentData().copy());
                restored.tickCount = 0;
                require(MissileFuseSafety.armed(restored, 10) == (powered == 10), name + " saved ignition clock survives entity tickCount reset");
                impact(restored);
                require(restored.isRemoved() == (powered == 10), name + " actual collision at safety boundary after restoration");
                restored.discard();
            }
            Vec3 movement = missile.position().subtract(from);
            if (powered <= 9) {
                near(movement.normalize(), expected, name + " powered clearance movement " + powered);
                near(missile.getDeltaMovement().normalize(), expected, name + " powered clearance velocity " + powered);
                if (pose == 0) require(Math.abs(missile.getY() - ignitionPosition.y) < 1e-8, name + " low-altitude clearance does not descend");
            }
            record(name, powered, "powered", missile);
        }
        require(MathematicalMissileManager.contains(missile) == math, name + " native math/entity handoff");
        Vec3 guidedVelocity = missile.getDeltaMovement();
        MianbaoAircraftGuidanceHandler.beforeTick(pre());
        near(missile.getDeltaMovement(), guidedVelocity, name + " correction does not restart after guidance");
        // Re-enter via the production save-restoration branch; the persistent marker prevents a second snap.
        MianbaoAircraftGuidanceHandler.onJoin(new EntityJoinLevelEvent(missile, level, true));
        MianbaoAircraftGuidanceHandler.beforeTick(pre());
        near(missile.getDeltaMovement(), guidedVelocity, name + " restore does not realign a guided missile");
        if (math && pose == 0) checkMathCollisionClock(missile);
        report.add("PASS " + name + " drop=" + dropTicks + " ignitionSpeed=" + ignitionSpeed + " before=" + beforeCorrection + " heading=" + expected);
        missile.discard();
        MianbaoAircraftGuidanceHandler.tick(post());
        MathematicalMissileManager.sync(post());
        ControllerTracker.remove(computer);
    }

    private static void checkMetadata() {
        require(FarMissileIgnitionHeading.direction(new CompoundTag()) == null, "legacy launches without release data");
        CompoundTag bad = new CompoundTag(); bad.putBoolean("SoundPlayed", true);
        require(FarMissileIgnitionHeading.direction(bad) == null, "missing attitude ignored");
        for (String key : List.of("QX", "QY", "QZ", "QW")) bad.putDouble(key, 0);
        require(FarMissileIgnitionHeading.direction(bad) == null, "zero quaternion ignored");
        bad.putDouble("QW", Double.NaN);
        require(FarMissileIgnitionHeading.direction(bad) == null, "invalid quaternion ignored");
        for (int age : new int[]{10, 15}) {
            CompoundTag release = new CompoundTag();
            release.putBoolean("SoundPlayed", true);
            for (String key : List.of("QX", "QY", "QZ")) release.putDouble(key, 0);
            release.putDouble("QW", 1); release.putDouble("WX", .01);
            release.putInt("Age", age);
            Vec3 expected = new Vec3(0, Math.sin(.01 * age), -Math.cos(.01 * age));
            near(FarMissileIgnitionHeading.direction(release), expected, "10/15-tick release compatibility");
            release.putInt("IgnitionDropAge", age); release.putInt("Age", age + 100);
            near(FarMissileIgnitionHeading.direction(release), expected, "ignition attitude remains fixed after free fall");
        }
        Entity legacy = BuiltInRegistries.ENTITY_TYPE.get(id("farmissile_1tanshe")).create(level);
        legacy.tickCount = 9;
        require(!MissileFuseSafety.armed(legacy, 10), "immediate-launch legacy safety at tick 9");
        legacy.tickCount = 10;
        require(MissileFuseSafety.armed(legacy, 10), "immediate-launch legacy safety at tick 10");
    }

    private static void checkMathCollisionClock(Entity source) {
        Entity missile = source.getType().create(level);
        Vec3 origin = new Vec3(8.5, 90.5, 8.5);
        level.getChunkAt(BlockPos.containing(origin));
        BlockPos obstacle = new BlockPos(9, 90, 8);
        level.setBlockAndUpdate(obstacle, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        missile.setPos(origin); missile.setDeltaMovement(2, 0, 0); missile.setNoGravity(true);
        missile.tickCount = 100;
        missile.getPersistentData().merge(source.getPersistentData().copy());
        missile.getPersistentData().putInt("CreateFireControlSafetyTicks", 10);
        missile.getPersistentData().getCompound(FarMissileIgnitionHeading.RELEASE_TAG).putLong("LastStep", level.getGameTime() - 9);
        var join = new EntityJoinLevelEvent(missile, level, false);
        MathematicalMissileManager.capture(join);
        require(join.isCanceled() && MathematicalMissileManager.contains(missile), "math safety fixture captured");
        MathematicalMissileManager.advance(missile);
        require(!missile.isRemoved(), "math collision ignores obstacle before full insurance interval");
        advanceClock();
        missile.setPos(origin); missile.setDeltaMovement(2, 0, 0);
        MathematicalMissileManager.advance(missile);
        require(missile.isRemoved(), "math collision arms on exact powered tick 10");
        MathematicalMissileManager.sync(post());
        level.removeBlock(obstacle, false);
    }

    private static void impact(Entity missile) {
        ((MathematicalMissileImpactAccess) missile).createFireControl$impact(
                new BlockHitResult(missile.position(), Direction.WEST, BlockPos.containing(missile.position()), false));
    }

    private static void advanceClock() { level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1); }
    private static ServerTickEvent.Pre pre() { return new ServerTickEvent.Pre(() -> true, level.getServer()); }
    private static ServerTickEvent.Post post() { return new ServerTickEvent.Post(() -> true, level.getServer()); }
    @SuppressWarnings("unchecked") private static Map<Entity, Object> guided() throws Exception {
        return (Map<Entity, Object>) get(MianbaoAircraftGuidanceHandler.class, "GUIDED");
    }
    private static Object get(Object object, String name) throws Exception {
        Class<?> type = object instanceof Class<?> clazz ? clazz : object.getClass();
        var field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(object instanceof Class<?> ? null : object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void record(String name, int tick, String phase, Entity missile) {
        Vec3 p = missile.position(), v = missile.getDeltaMovement();
        csv.add(name + "," + tick + "," + phase + "," + p.x + "," + p.y + "," + p.z + "," + v.x + "," + v.y + "," + v.z);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void near(Vec3 a, Vec3 b, String message) { require(a.distanceTo(b) < 1e-8, message + ": " + a + " vs " + b); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
