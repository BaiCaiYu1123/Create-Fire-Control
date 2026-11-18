package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Real launches with the launch radar removed before the real target manoeuvres. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ActiveRadarMissileIntegration {
    private static ServerLevel level;
    private static long started;
    private static final List<Fixture> FIXTURES = new ArrayList<>();
    private static final List<String> CSV = new ArrayList<>(List.of("scenario,age,entityX,entityY,entityZ,mathX,mathY,mathZ,targetX,targetY,targetZ,closest"));
    private static boolean finished;
    private static final Set<UUID> PAIRED_SHOTS = new HashSet<>();
    private static final class Fixture {
        String name;
        BlockPos rack;
        MianbaoAircraftCompat.WeaponType type;
        boolean display;
        Entity entityTarget;
        Set<ChunkPos> forcedEntityChunks = new HashSet<>();
        ServerSubLevel body;
        Vector3d bodyOrigin;
        Vec3 targetOrigin;
        Vec3 previousTarget;
        Vec3[] previousMissile = new Vec3[2];
        Vec3[] previousVelocity = new Vec3[2];
        Entity[] missiles = new Entity[2];
        double closest = Double.POSITIVE_INFINITY;
        boolean completed;
    }

    static void start(ServerLevel root) throws Exception {
        level = root; started = root.getGameTime();
        var types = new MianbaoAircraftCompat.WeaponType[]{MianbaoAircraftCompat.WeaponType.FAR,
                MianbaoAircraftCompat.WeaponType.MEDIUM,
                MianbaoAircraftCompat.WeaponType.MICA_EM, MianbaoAircraftCompat.WeaponType.AIM_120D, null, null};
        int index = 0;
        for (boolean structure : new boolean[]{false, true}) for (int i = 0; i < types.length; i++) {
            Fixture fixture = new Fixture();
            fixture.type = types[i]; fixture.display = i == types.length - 1;
            fixture.name = (fixture.type == null ? fixture.display ? "AA-display" : "AA-computer" : fixture.type.name())
                    + (structure ? "-Sable" : "-entity-target");
            fixture.rack = new BlockPos(3500, 220, 6500 + index++ * 512);
            fixture.targetOrigin = new Vec3(4700, 220, fixture.rack.getZ() + 300);
            UUID target;
            Set<UUID> members;
            if (structure) {
                BlockPos anchor = BlockPos.containing(fixture.targetOrigin);
                level.setBlockAndUpdate(anchor, Blocks.OBSIDIAN.defaultBlockState());
                var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(
                        level, anchor.below(), anchor, false, false);
                require(assembly != null, "active target structure assembly");
                fixture.body = (ServerSubLevel) assembly.subLevel();
                fixture.bodyOrigin = new Vector3d(fixture.body.logicalPose().position());
                ((ServerSubLevelContainer) SubLevelContainer.getContainer(level)).addForceLoadTicket(
                        fixture.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                fixture.body.forceUpdateGlobalBounds();
                fixture.targetOrigin = StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter();
                target = fixture.body.getUniqueId(); members = Set.of(target);
            } else {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                require(stand != null, "entity target creation");
                stand.setPos(fixture.targetOrigin); stand.setNoGravity(true); stand.noPhysics = true; stand.setInvulnerable(true);
                // Keep the entire evasion area available before moving the
                // target. Just forcing its destination on the movement tick
                // leaves an async entity-store visibility gap at chunk edges.
                for (int x = ((int) fixture.targetOrigin.x - 32) >> 4; x <= ((int) fixture.targetOrigin.x) >> 4; x++)
                    for (int z = ((int) fixture.targetOrigin.z - 64) >> 4; z <= ((int) fixture.targetOrigin.z + 64) >> 4; z++) {
                        ChunkPos chunk = new ChunkPos(x, z); fixture.forcedEntityChunks.add(chunk);
                        level.setChunkForced(x, z, true); level.getChunk(x, z);
                    }
                level.getChunk(stand.blockPosition().getX() >> 4, stand.blockPosition().getZ() >> 4);
                require(level.addFreshEntity(stand), "register real seeker target");
                fixture.entityTarget = stand; target = stand.getUUID(); members = Set.of();
            }
            FIXTURES.add(fixture);
            var cue = new GuidedMissileLaunchIntegration.ActiveCue(target, fixture.targetOrigin, Vec3.ZERO, members);
            for (int mode = 0; mode < 2; mode++) {
                fixture.missiles[mode] = fixture.type == null
                        ? GuidedMissileLaunchIntegration.launchActiveAa(fixture.rack, mode == 1, fixture.display, cue)
                        : GuidedMissileLaunchIntegration.launchActiveAircraft(fixture.rack, mode == 1, fixture.type, cue);
                fixture.missiles[mode].setInvulnerable(true);
                if (fixture.missiles[mode] instanceof AbstractArrow arrow) arrow.setNoPhysics(true);
                PAIRED_SHOTS.add(fixture.missiles[mode].getUUID());
                fixture.previousMissile[mode] = MianbaoMissileCompat.getWorldPosition(fixture.missiles[mode]);
                fixture.previousVelocity[mode] = MianbaoMissileCompat.getWorldVelocity(fixture.missiles[mode]);
            }
            fixture.previousTarget = targetPosition(fixture);
        }
        StabilizedTurret.LOGGER.info("ACTIVE RADAR CHECK: {} real launches, four radar AAM families, AA computer/display, entity/Sable targets", FIXTURES.size() * 2);
    }

    @SubscribeEvent
    public static void protectPairedShots(ExplosionEvent.Detonate event) {
        // Co-located comparisons must not knock back or damage each other
        // when the first native warhead reaches the target.
        event.getAffectedEntities().removeIf(entity -> PAIRED_SHOTS.contains(entity.getUUID()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void before(ServerTickEvent.Pre event) throws Exception {
        if (level == null || finished) return;
        int age = (int) (level.getGameTime() - started) + 1;
        for (Fixture fixture : FIXTURES) if (!fixture.completed) {
            // The radar is removed first; the target only starts manoeuvring later.
            if (age >= 2) level.removeBlock(fixture.rack.west(6), false);
            if (age >= 5) level.removeBlock(fixture.rack.west(4), false);
            Vec3 displacement = displacement(age);
            if (fixture.body == null) {
                Vec3 point = fixture.targetOrigin.add(displacement);
                fixture.entityTarget.setPos(point);
                fixture.entityTarget.setDeltaMovement(Vec3.ZERO);
            }
            if (age == 40) for (Entity missile : fixture.missiles) restoreGuidanceWithoutOwner(missile, fixture);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void moveStructures(ServerTickEvent.Post event) {
        if (level == null || finished) return;
        int age = (int) (level.getGameTime() - started);
        Vec3 displacement = displacement(age);
        var pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
        for (Fixture fixture : FIXTURES) if (!fixture.completed && fixture.body != null) {
            Vector3d point = new Vector3d(fixture.bodyOrigin).add(displacement.x, displacement.y, displacement.z);
            pipeline.teleport(fixture.body, point, fixture.body.logicalPose().orientation());
            pipeline.resetVelocity(fixture.body);
            fixture.body.logicalPose().position().set(point);
            fixture.body.updateLastPose(); fixture.body.forceUpdateGlobalBounds();
        }
    }

    static void tick(int age) throws Exception {
        if (finished) return;
        for (Fixture fixture : FIXTURES) if (!fixture.completed) {
            Vec3 target = targetPosition(fixture);
            Vec3[] position = new Vec3[2];
            for (int mode = 0; mode < 2; mode++) {
                Entity missile = fixture.missiles[mode];
                position[mode] = MianbaoMissileCompat.getWorldPosition(missile);
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                double turn = Math.acos(Math.max(-1, Math.min(1, velocity.normalize().dot(fixture.previousVelocity[mode].normalize()))));
                double limit = fixture.type == null ? MianbaoAirDefenseCompat.maxTurnRadians(velocity.length())
                        : MianbaoAircraftCompat.interceptMaxTurnRadians(fixture.type, velocity.length());
                if (!missile.isRemoved()) require(turn <= limit + 1e-7, fixture.name + " active seeker exceeded angular ceiling");
                if (!missile.isRemoved() && fixture.type != null) require(velocity.length() * Math.tan(turn)
                        <= MianbaoAircraftCompat.maxLateralAcceleration(fixture.type) + 1e-6,
                        fixture.name + " active seeker exceeded overload ceiling");
                fixture.previousVelocity[mode] = velocity;
                Vec3 relative = fixture.previousTarget.subtract(fixture.previousMissile[mode]);
                Vec3 step = target.subtract(position[mode]).subtract(relative);
                double f = Math.max(0, Math.min(1, -relative.dot(step) / Math.max(1e-12, step.lengthSqr())));
                double distance = relative.add(step.scale(f)).length();
                if (mode == 0) fixture.closest = Math.min(fixture.closest, distance);
                // A successful terminal segment can already put the target
                // behind the nose at its endpoint; the swept hit wins first.
                if (age > 10 && !missile.isRemoved() && distance >= (fixture.body == null ? .5 : 3)) {
                    CompoundTag memory = missile.getPersistentData().getCompound(ActiveRadarSeeker.TAG);
                    require(memory.getBoolean("Locked") && memory.getLong("Tick") >= level.getGameTime() - 1,
                            fixture.name + " stopped observing after the launcher was removed at " + age
                                    + " targetPresent=" + (fixture.entityTarget == null || level.getEntity(fixture.entityTarget.getUUID()) != null));
                    if (age == 35) {
                        Vec3 measured = new Vec3(memory.getDouble("PX"), memory.getDouble("PY"), memory.getDouble("PZ"));
                        require(measured.distanceTo(fixture.targetOrigin) > 15 && measured.distanceTo(target) < 1e-4,
                                fixture.name + " did not measure the live manoeuvring target");
                    }
                }
                require(!missile.isRemoved() || distance < 3, fixture.name + " removed before target interception at " + age + " distance=" + distance);
                fixture.previousMissile[mode] = position[mode];
            }
            require(position[0].distanceTo(position[1]) < 1e-5, fixture.name + " entity/math active trajectory mismatch at " + age
                    + " entity=" + position[0] + " math=" + position[1]
                    + " removed=" + fixture.missiles[0].isRemoved() + "/" + fixture.missiles[1].isRemoved());
            CSV.add(fixture.name + "," + age + "," + csv(position[0]) + "," + csv(position[1]) + "," + csv(target) + "," + fixture.closest);
            if (fixture.closest < (fixture.body == null ? .5 : 3)) {
                fixture.completed = true;
                for (Entity missile : fixture.missiles) missile.discard();
                if (fixture.entityTarget != null) {
                    fixture.entityTarget.discard();
                    for (ChunkPos chunk : fixture.forcedEntityChunks) level.setChunkForced(chunk.x, chunk.z, false);
                }
                if (fixture.body != null) ((ServerSubLevelContainer) SubLevelContainer.getContainer(level)).removeForceLoadTicket(
                        fixture.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            }
            fixture.previousTarget = target;
        }
        Files.write(Path.of("active-radar-launch-trajectories.csv"), CSV);
        if (FIXTURES.stream().allMatch(value -> value.completed)) {
            finished = true;
            Files.writeString(Path.of("active-radar-integration-result.txt"), "PASS: 24 real radar-guided launches, radar removed at tick 2, controller removed at tick 5, target manoeuvres after tick 20, ownerless guidance restore at tick 40; infrared covered separately\n");
            StabilizedTurret.LOGGER.info("ACTIVE RADAR CHECK PASS: 24 real radar-guided launches intercept after launch radar/controller removal and independent guidance restoration");
        } else require(age < 190, "active seeker full-flight interception timed out");
    }

    private static Vec3 targetPosition(Fixture fixture) {
        return fixture.body == null ? MianbaoMissileCompat.getWorldPosition(fixture.entityTarget)
                : StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter();
    }

    private static Vec3 displacement(int age) {
        if (age <= 20) return Vec3.ZERO;
        // Continuous evasive motion crossing chunk boundaries in a bounded area.
        double phase = (age - 20) * .05;
        return new Vec3(-12 * (1 - Math.cos(phase)), 0, 48 * Math.sin(phase));
    }

    private static void restoreGuidanceWithoutOwner(Entity missile, Fixture fixture) throws Exception {
        Class<?> handler = fixture.type != null ? MianbaoAircraftGuidanceHandler.class
                : fixture.display ? AutoDefenseMissileGuidance.class : MianbaoAirDefenseGuidanceHandler.class;
        var field = handler.getDeclaredField(fixture.display && fixture.type == null ? "guided" : "GUIDED");
        field.setAccessible(true);
        Object old = ((Map<?, ?>) field.get(null)).remove(missile);
        require(old != null, "guidance state exists before restore");
        var tickets = old.getClass().getDeclaredField("chunkTickets"); tickets.setAccessible(true);
        ((MissileChunkTickets) tickets.get(old)).release();
        var joined = new EntityJoinLevelEvent(missile, missile.level());
        if (fixture.type != null) MianbaoAircraftGuidanceHandler.onJoin(joined);
        else if (fixture.display) AutoDefenseMissileGuidance.onJoin(joined);
        else MianbaoAirDefenseGuidanceHandler.onEntityJoin(joined);
        require(((Map<?, ?>) field.get(null)).containsKey(missile), "active missile must restore without the launch controller");
    }

    private static String csv(Vec3 v) { return v.x + "," + v.y + "," + v.z; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    static void abort() {
        finished = true;
        for (Fixture fixture : FIXTURES) {
            for (Entity missile : fixture.missiles) if (missile != null) missile.discard();
            if (fixture.entityTarget != null) fixture.entityTarget.discard();
            for (ChunkPos chunk : fixture.forcedEntityChunks) level.setChunkForced(chunk.x, chunk.z, false);
            if (fixture.body != null) ((ServerSubLevelContainer) SubLevelContainer.getContainer(level)).removeForceLoadTicket(
                    fixture.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        }
    }
}
