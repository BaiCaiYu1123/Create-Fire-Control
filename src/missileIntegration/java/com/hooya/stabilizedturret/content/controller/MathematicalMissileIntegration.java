package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Compares actual installed native ticks against the detached integrator, not a duplicated reference. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MathematicalMissileIntegration {
    private static ServerLevel level;
    private static final List<Pair> PAIRS = new ArrayList<>();
    private static int ticks;
    private static boolean done;
    private static Throwable deferredFailure;
    private static final boolean PARITY_ONLY = Boolean.getBoolean("create_fire_control.missileParityOnly");
    private static int settleUntil;
    private static Entity actualCruise;
    private static final List<Entity> NATIVE_ONLY = new ArrayList<>();
    private static final Map<String, String> MODEL_FAILURES = new LinkedHashMap<>();
    private record Pair(Entity nativeMissile, Entity mathematical, Vec3 origin) {}
    private static final Vec3 ORIGIN = new Vec3(8.5, 240, 8.5);
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if (Boolean.getBoolean("create_fire_control.missileClientChecks")) return;
        level = event.getServer().overworld();
        try {
            level.getChunk(0, 0);
            command("cfc missile mode math");
            List<String> types = List.of("antiairmissiletanshe", "farmissile_1tanshe", "farmissile_2tanshe",
                    "missile_2tanshe", "medium_aim_missile_2tanshe", "closemissile_1tanshe", "closemissile_2tanshe",
                    "mica_em_tanshe", "aim_120_tanshe", "agm_1tanshe", "agm_2tanshe", "agm_3tanshe", "agm_4tanshe",
                    "agmmissile_3", "agmmissile_4", "missiletanshe", "cruisemissiletanshe", "cruisemissilelowtanshe",
                    "nuclearcruisemissiletanshe", "groundnuclearcruisemissiletanshe", "groundmissiletanshe",
                    "groundmissile_2tanshe", "groundmissile_3tanshe", "groundmissile_4tanshe",
                    "opticalguidedmissiletanshe", "antiradiationmissiletanshe",
                    "antitankmissile_1tanshe", "antitankmissile_2tanshe", "antitankmissile_5tanshe",
                    "antitankmissile_6tanshe", "antitankmissile_7tanshe",
                    "portableantiairmissiletanshe", "portableantiairmissiletanshe_2", "ballistic_missile_entity");
            for (String path : types) {
                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("mianbaos_modernwarfare:" + path)).orElse(null);
                if (type == null && (path.equals("agmmissile_3") || path.equals("agmmissile_4"))) continue;
                require(type != null, "Missing native type " + path);
                Entity nativeMissile = type.create(level), math = type.create(level);
                for (Entity missile : List.of(nativeMissile, math)) {
                    missile.setPos(ORIGIN.x, ORIGIN.y, ORIGIN.z);
                    missile.setDeltaMovement(2, .1, .25);
                    missile.getPersistentData().putBoolean("jamed", true);
                    missile.getPersistentData().putBoolean("CreateFireControlCruiseGuided", MianbaoCruiseMissileCompat.isMissile(missile));
                    missile.getPersistentData().putBoolean("CreateFireControlAircraftGuided", MianbaoAircraftCompat.isMissile(missile));
                    missile.getPersistentData().putBoolean("CreateFireControlAaGuided", MianbaoAirDefenseCompat.isHighSpeedMissile(missile));
                    missile.getPersistentData().putInt("CreateFireControlSafetyTicks", 200);
                    if (MianbaoCruiseMissileCompat.isMissile(missile)) {
                        missile.setNoGravity(true);
                        MianbaoCruiseMissileCompat.writeNativeTarget(missile,
                                MianbaoCruiseMissileCompat.missileType(missile), new Vec3(30000, 240, 8));
                    }
                }
                if (!GuidedMissileMode.eligible(math)) {
                    math.setPos(ORIGIN.x, ORIGIN.y + 40, ORIGIN.z);
                    require(level.addFreshEntity(math), "Native-only missile unexpectedly adopted " + path);
                    math.setNoGravity(true); math.noPhysics = true;
                    NATIVE_ONLY.add(math);
                    continue;
                }
                Vec3 referenceOrigin = ORIGIN.add(0, 0, PAIRS.size() * 128);
                for (Entity missile : List.of(nativeMissile, math)) missile.setPos(referenceOrigin);
                level.setChunkForced(((int) referenceOrigin.x) >> 4, ((int) referenceOrigin.z) >> 4, true);
                level.getChunk(BlockPosFor(referenceOrigin));
                nativeMissile.getPersistentData().putString(GuidedMissileMode.ROUTE, "entity");
                nativeMissile.getPersistentData().putInt(GuidedMissileMode.ROUTE_POLICY_TAG, 1);
                if (MianbaoAircraftCompat.isAntiRadiation(MianbaoAircraftCompat.typeOf(nativeMissile)))
                    nativeMissile.getPersistentData().putBoolean("CreateFireControlAircraftGuided", false);
                require(level.addFreshEntity(nativeMissile), "Native reference must be a real ticking entity " + path);
                require(!level.addFreshEntity(math), "Math shot entered native tracking " + path);
                require(MathematicalMissileManager.contains(math), "Math shot missing " + path);
                PAIRS.add(new Pair(nativeMissile, math, referenceOrigin));
            }
            verifyNativeLaunch();
            verifyClusterAndExpiry();
            if (!PARITY_ONLY) {
                GuidedMissileLaunchIntegration.start(level);
                ActiveRadarMissileIntegration.start(level);
            }
            command("cfc missile mode entity");
            for (Pair pair : PAIRS) require(GuidedMissileMode.mathematical(pair.mathematical), "Switch changed an in-flight shot");
            Entity original = PAIRS.getFirst().nativeMissile.getType().create(level);
            original.setPos(8, 250, 8);
            original.getPersistentData().putBoolean("CreateFireControlAaGuided", true);
            require(level.addFreshEntity(original), "Entity mode no longer registers entities");
            require(!GuidedMissileMode.mathematical(original), "Entity mode classified as math");
            original.discard();
            StabilizedTurret.LOGGER.info("MATH MISSILE CHECK: {} native types captured; command switching preserves in-flight mode", PAIRS.size());
        } catch (Throwable failure) { deferFailure(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || done) return;
        if (deferredFailure != null) {
            if (++ticks >= settleUntil) finish(deferredFailure);
            return;
        }
        try {
            ticks++;
            if (!PARITY_ONLY) {
                GuidedMissileLaunchIntegration.tick(ticks);
                ActiveRadarMissileIntegration.tick(ticks);
            }
            if (ticks <= 120) {
                for (Entity missile : List.copyOf(NATIVE_ONLY)) {
                    try {
                    if (MianbaoBallisticMissileCompat.isMissile(missile) && missile.isRemoved()) {
                        require(!MathematicalMissileManager.contains(missile), "Unmounted ballistic entity entered mathematical flight");
                        NATIVE_ONLY.remove(missile);
                        StabilizedTurret.LOGGER.info("BALLISTIC LIFECYCLE PASS: native API rejects an unmounted projectile; mounted launch-to-impact is outside this fixture");
                        continue;
                    }
                    require(!missile.isRemoved() && !MathematicalMissileManager.contains(missile), "Native-only missile route remains independent " + missile.getType());
                    require(missile.tickCount >= ticks - 2, "Native-only fixture stopped ticking outside loaded terrain");
                    Vec3 point = missile.position();
                    require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z), "Native-only flight stays finite");
                    missile.setPos(ORIGIN.x, ORIGIN.y + 40, ORIGIN.z); // Keep the native fixture ticking, without loading its flight corridor.
                    } catch (Throwable failure) {
                        MODEL_FAILURES.put(BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).toString(), failure.toString());
                        missile.discard(); NATIVE_ONLY.remove(missile);
                    }
                }
            }
            for (Pair pair : ticks <= 120 ? List.copyOf(PAIRS) : List.<Pair>of()) {
                try {
                Entity nativeMissile = pair.nativeMissile, math = pair.mathematical;
                Vec3 before = math.position();
                // The real registered reference has already traversed the world's
                // tick and procedure dispatch. The adapter advances only here.
                MathematicalMissileManager.advance(math);
                String name = BuiltInRegistries.ENTITY_TYPE.getKey(math.getType()).toString() + " tick " + ticks;
                near(nativeMissile.position().subtract(pair.origin), math.position().subtract(before), name + " displacement");
                var cruise = MianbaoCruiseMissileCompat.missileType(math);
                if (cruise == null || !cruise.lowAltitude())
                    near(nativeMissile.getDeltaMovement(), math.getDeltaMovement(), name + " native motor/drag/gravity");
                require(Math.abs(nativeMissile.getYRot() - math.getYRot()) < 1e-5, name + " yaw");
                require(Math.abs(nativeMissile.getXRot() - math.getXRot()) < 1e-5, name + " pitch");
                require(level.getEntity(math.getUUID()) == null, "Detached adapter registered as a world entity");
                var profile = MianbaoMissileFlightProfile.of(BuiltInRegistries.ENTITY_TYPE.getKey(math.getType()).getPath());
                require(nativeMissile.getPersistentData().getDouble(profile.timer()) == math.getPersistentData().getDouble(profile.timer()), name + " engine age native="
                        + nativeMissile.getPersistentData().getDouble(profile.timer()) + " math=" + math.getPersistentData().getDouble(profile.timer()));
                // Actual control profiles reset the native engine clock and command the
                // next speed. Exercise all turns, launch vectors and speed transitions.
                for (Entity missile : List.of(nativeMissile, math)) {
                    if (missile.getPersistentData().getDouble("fadongji1") > 100)
                        missile.getPersistentData().putDouble("fadongji1", 100);
                    var aircraft = MianbaoAircraftCompat.typeOf(missile);
                    double speed = aircraft == null ? 34.03 * Math.min(1, ticks / 100.0)
                            : MianbaoAircraftCompat.speedProfile(aircraft, 2).speedAt(ticks);
                    Vec3 direction = new Vec3(Math.cos(ticks * .035), Math.sin(ticks * .014) * .012, Math.sin(ticks * .035)).normalize();
                    missile.setDeltaMovement(direction.scale(speed));
                }
                nativeMissile.setPos(pair.origin);
                } catch (Throwable failure) {
                    MODEL_FAILURES.put(BuiltInRegistries.ENTITY_TYPE.getKey(pair.mathematical.getType()).toString(), failure.toString());
                    pair.mathematical.discard(); pair.nativeMissile.discard(); PAIRS.remove(pair);
                }
            }
            require(level.getChunkSource().getChunkNow(120, 0) == null, "Mathematical corridor loaded terrain");
            if (ticks == 40) require(actualCruise.isRemoved()
                    && actualCruise.getPersistentData().getBoolean("CreateFireControlCruiseDetonated"), "Native impact callback did not detonate the actual cruise launch");
            if (ticks == 120) {
                for (Pair pair : PAIRS) pair.mathematical.discard();
                for (Entity missile : NATIVE_ONLY) missile.discard();
                StabilizedTurret.LOGGER.info("NATIVE MISSILE ROUTE PASS: {} TV/ATGM/portable-AA/ballistic types, 120 native ticks, finite state, no mathematical adoption; manual camera steering and ballistic launch-to-impact are outside this fixture", NATIVE_ONLY.size());
            }
            // Let ordinary chunk unload work settle after the large launch
            // fixture, before asking Minecraft to drain its stop-time queue.
            if (ticks >= 220) {
                if (!MODEL_FAILURES.isEmpty()) {
                    Files.writeString(Path.of("missile-model-failures.txt"), MODEL_FAILURES.toString());
                    finish(new AssertionError("Per-model native regression failures: " + MODEL_FAILURES));
                } else finish(null);
            }
        } catch (Throwable failure) { deferFailure(failure); }
    }
    private static net.minecraft.core.BlockPos BlockPosFor(Vec3 point) { return net.minecraft.core.BlockPos.containing(point); }
    private static void verifyNativeLaunch() {
        var displayPos = new net.minecraft.core.BlockPos(512, 240, 512);
        var launcherPos = new net.minecraft.core.BlockPos(516, 240, 518);
        level.setChunkForced(displayPos.getX() >> 4, displayPos.getZ() >> 4, true);
        level.getChunk(displayPos);
        level.setBlock(displayPos, com.hooya.stabilizedturret.registry.ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState(), 3);
        var display = (com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mianbaos_modernwarfare:cruisemissileblock")).defaultBlockState();
        if (block.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING))
            block = block.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, net.minecraft.core.Direction.EAST);
        else if (block.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))
            block = block.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST);
        level.setBlock(launcherPos, block, 3);
        var ref = BindingRef.capture(level, launcherPos);
        Vec3 target = new Vec3(2512, 240, 518.5);
        var type = MianbaoCruiseMissileCompat.Type.CRUISE;
        com.hooya.stabilizedturret.content.display.CruiseMissileGuidance.armNext(display, ref, type, null, target, target);
        int before = MathematicalMissileManager.activeCount();
        require(MianbaoCruiseMissileCompat.fire(level, ref, type, target), "Cancelled native registration prevented ammunition consumption");
        require(MathematicalMissileManager.activeCount() == before + 1, "Actual launch did not enter math mode");
        require(level.getBlockState(launcherPos).isAir(), "Actual cruise ammunition block was not consumed");
        var fixtureIds = PAIRS.stream().map(pair -> pair.mathematical.getUUID()).collect(java.util.stream.Collectors.toSet());
        actualCruise = MathematicalMissileManager.missiles(level).stream().filter(missile -> !fixtureIds.contains(missile.getUUID())).findFirst().orElseThrow();
        require(level.getEntity(actualCruise.getUUID()) == null, "Actual cruise launch entered tracking");
        for (int y = 238; y <= 246; y++) for (int z = 5; z <= 12; z++)
            level.setBlock(new net.minecraft.core.BlockPos(552, y, z + 510), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
    }
    private static void verifyClusterAndExpiry() {
        Entity cluster = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mianbaos_modernwarfare:groundmissile_2tanshe")).create(level);
        cluster.setPos(8.5, 280, 8.5); cluster.setDeltaMovement(3, -.2, 0); cluster.setNoGravity(true);
        cluster.getPersistentData().putBoolean("CreateFireControlCruiseGuided", true);
        cluster.getPersistentData().putInt("CreateFireControlSafetyTicks", 200);
        cluster.getPersistentData().putDouble("time", 49);
        MianbaoCruiseMissileCompat.writeNativeTarget(cluster, MianbaoCruiseMissileCompat.Type.GROUND_CLUSTER, new Vec3(80, 270, 8.5));
        require(!level.addFreshEntity(cluster), "Cluster parent registered");
        int before = MianbaoBombProjectileHandler.activeBombCount();
        MathematicalMissileManager.advance(cluster);
        require(cluster.getPersistentData().getBoolean("分裂"), "Cluster split trigger lost");
        require(MianbaoBombProjectileHandler.activeBombCount() == before + 24, "Native 24-round cluster scatter was not retained");
        cluster.discard();
        Entity timed = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mianbaos_modernwarfare:groundmissiletanshe")).create(level);
        timed.setPos(8.5, 280, 8.5); timed.setDeltaMovement(1, 0, 0); timed.setNoGravity(true);
        timed.getPersistentData().putBoolean("CreateFireControlCruiseGuided", true);
        timed.getPersistentData().putInt("CreateFireControlSafetyTicks", 200);
        timed.tickCount = com.hooya.stabilizedturret.config.MissileSettings.lifetimeTicks(timed);
        require(!level.addFreshEntity(timed), "Timeout fixture registered");
        MathematicalMissileManager.advance(timed);
        require(timed.isRemoved(), "Configured ground missile lifetime was not enforced");
    }
    private static void command(String command) { level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), command); }
    private static void near(Vec3 a, Vec3 b, String message) { require(a.distanceToSqr(b) < 1e-18, message + ": " + a + " != " + b); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void deferFailure(Throwable failure) {
        deferredFailure = failure; settleUntil = Math.max(220, ticks + 60);
        if (!MODEL_FAILURES.isEmpty()) try { Files.writeString(Path.of("missile-model-failures.txt"), MODEL_FAILURES.toString()); }
        catch (Exception ignored) {}
        StabilizedTurret.LOGGER.error("MISSILE CHECK failed; releasing fixture tickets before shutdown", failure);
        ActiveRadarMissileIntegration.abort(); GuidedMissileLaunchIntegration.abort();
        for (Pair pair : PAIRS) { pair.nativeMissile.discard(); pair.mathematical.discard(); }
        for (Entity missile : NATIVE_ONLY) missile.discard();
        if (actualCruise != null) actualCruise.discard();
    }
    private static void finish(Throwable failure) {
        done = true;
        if (failure != null) StabilizedTurret.LOGGER.error("MATH MISSILE CHECK FAILED", failure);
        else StabilizedTurret.LOGGER.info("MATH MISSILE CHECK PASS: {} types x {} real native ticks, exact positions/velocities/orientations, no registered entities or corridor loading", PAIRS.size(), Math.min(ticks, 120));
        try { Files.writeString(Path.of("mathematical-missile-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { level.getServer().halt(false); }
    }
}
