package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import com.hooya.stabilizedturret.mixin.MathematicalMissileEntityAccess;
import com.hooya.stabilizedturret.mixin.MathematicalMissileImpactAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** No registered/ticking missile entities. Entity objects are adapters for the existing device APIs. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MathematicalMissileManager {
    private static final Map<UUID, Shot> ACTIVE = new LinkedHashMap<>();
    private static final Map<ServerLevel, RocketTerrainCache> TERRAIN = new IdentityHashMap<>();
    private static final Map<ServerLevel, List<UUID>> REMOVED = new IdentityHashMap<>();

    private static final class Shot {
        final Entity adapter;
        final ServerLevel root;
        final ArrayDeque<GuidedMissileFlight.Step> pending = new ArrayDeque<>();
        Vec3 previous;
        long advanced = Long.MIN_VALUE;
        Shot(Entity adapter, ServerLevel root, Vec3 position) {
            this.adapter = adapter; this.root = root; previous = position;
        }
    }

    public static int activeCount() { return ACTIVE.size(); }
    public static double groundHeight(ServerLevel root, double x, double z) {
        return TERRAIN.computeIfAbsent(root, RocketTerrainCache::new).groundHeight(x, z);
    }
    public static boolean contains(Entity entity) {
        Shot shot = entity == null ? null : ACTIVE.get(entity.getUUID());
        return shot != null && shot.adapter == entity;
    }
    public static ServerLevel root(Entity entity) { return ACTIVE.get(entity.getUUID()).root; }
    public static Entity find(ServerLevel root, UUID id) {
        Shot shot = ACTIVE.get(id);
        return shot != null && shot.root == root && !shot.adapter.isRemoved() ? shot.adapter : null;
    }
    public static List<Entity> missiles(ServerLevel root) {
        return ACTIVE.values().stream().filter(shot -> shot.root == root && !shot.adapter.isRemoved())
                .map(shot -> shot.adapter).toList();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void capture(EntityJoinLevelEvent event) {
        Entity missile = event.getEntity();
        if (event.getLevel().isClientSide() || !GuidedMissileMode.eligible(missile)) return;
        var data = missile.getPersistentData();
        boolean adopted = data.getBoolean("CreateFireControlAircraftGuided")
                || data.getBoolean("CreateFireControlAaGuided")
                || data.getBoolean("CreateFireControlDisplayGuided")
                || data.getBoolean("CreateFireControlCruiseGuided");
        if (!adopted || !GuidedMissileMode.mathematical(missile)) return;
        if (!contains(missile)) {
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
            if (root == null || !finite(position) || !finite(velocity)) return;
            // Capture the final spawn values on this same object. Native ammunition,
            // launch sounds and all device-specific guidance ownership stay intact.
            missile.setPos(position.x, position.y, position.z);
            missile.setDeltaMovement(velocity);
            ((MathematicalMissileEntityAccess) missile).createFireControl$setAdapterLevel(root);
            ACTIVE.put(missile.getUUID(), new Shot(missile, root, position));
        }
        event.setCanceled(true);
    }

    /** Called at the original device's post-tick movement boundary, at most once per tick. */
    public static boolean advance(Entity missile) {
        Shot shot = ACTIVE.get(missile.getUUID());
        long now = shot.root.getGameTime();
        if (shot.advanced == now || missile.isRemoved()) return false;
        shot.advanced = now;
        Vec3 position = missile.position(), velocity = missile.getDeltaMovement();
        if (!finite(position) || !finite(velocity)) { missile.discard(); return false; }
        missile.setOldPosAndRot();
        // Query water only in already-loaded chunks. No getBlockState on remote terrain.
        boolean water = TERRAIN.computeIfAbsent(shot.root, RocketTerrainCache::new).inWater(position);
        var step = GuidedMissileFlight.step(position, velocity, !missile.isNoGravity(), water);
        shot.previous = step.from();
        missile.setPos(step.to().x, step.to().y, step.to().z);
        missile.setDeltaMovement(step.afterArrow());
        missile.tickCount++;
        // Device guidance owns each model's timed lifetime in both flight modes.
        // Do not truncate a live guided shot at the legacy 4096-block radius.
        boolean antiRadiation = MianbaoAircraftCompat.isAntiRadiation(MianbaoAircraftCompat.typeOf(missile));
        // Building height is not an airspace boundary for guided flight.
        int fallbackLifetime = LandAttackMissileFlight.managed(missile)
                ? MissileSettings.lifetimeTicks(missile) : Math.max(MissileSettings.lifetimeTicks(missile), AirToAirMissileFlight.fallbackLifetimeTicks(missile));
        if (missile.tickCount > fallbackLifetime
                || !antiRadiation && step.to().y < shot.root.getMinBuildHeight() - 64
                || !shot.root.getWorldBorder().isWithinBounds(step.to())) missile.discard();
        advanceNativeEngine(missile);
        // Native collision rotation is interpolated by .2 each tick (AbstractArrow).
        double horizontal = Math.hypot(velocity.x, velocity.z);
        float yaw = (float) (net.minecraft.util.Mth.atan2(velocity.x, velocity.z) * 180.0F / (float) Math.PI);
        float pitch = (float) (net.minecraft.util.Mth.atan2(velocity.y, horizontal) * 180.0F / (float) Math.PI);
        if (missile.yRotO == 0 && missile.xRotO == 0) { missile.yRotO = yaw; missile.xRotO = pitch; }
        missile.setYRot(lerpRotation(missile.yRotO, yaw));
        missile.setXRot(lerpRotation(missile.xRotO, pitch));
        int safety = missile.getPersistentData().contains("CreateFireControlSafetyTicks")
                ? missile.getPersistentData().getInt("CreateFireControlSafetyTicks") : 10;
        boolean grace = missile.getPersistentData().getBoolean("CreateFireControlCountermeasureDecoyed")
                && missile.tickCount <= missile.getPersistentData().getInt("CreateFireControlCountermeasureGraceUntil");
        if (MissileFuseSafety.armed(missile, safety) && !grace && !missile.noPhysics) shot.pending.addLast(step);
        collide(shot);
        return true;
    }

    private static void advanceNativeEngine(Entity missile) {
        var data = missile.getPersistentData();
        var aircraft = MianbaoAircraftCompat.typeOf(missile);
        var cruise = MianbaoCruiseMissileCompat.missileType(missile);
        var profile = MianbaoMissileFlightProfile.of(BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).getPath());
        String timer = profile.timer();
        double previousAge = data.getDouble(timer);
        double age = previousAge + 1;
        data.putDouble(timer, age);
        // Cruise native velocity writes are already suppressed by Mianbao's
        // FireControlGuidanceCompat. The device supplies the next velocity.
        if (cruise != null) {
            MianbaoCruiseMissileCompat.playMathematicalIgnition(missile, cruise, previousAge, age);
            // The controlling display owns the shared 120-second lifetime.
            if (cruise.cluster() && age >= 50 && !data.getBoolean("分裂")
                    && missile.getDeltaMovement().y < 0 && data.getBoolean("坐标模式")) {
                Vec3 target = new Vec3(data.getDouble("x_target"), data.getDouble("y_target"), data.getDouble("z_target"));
                if (target.distanceToSqr(missile.position()) <= 150 * 150) splitCluster(missile);
            }
            return;
        }
        missile.setNoGravity(true);
        missile.setDeltaMovement(missile.getDeltaMovement().scale(profile.motorMultiplier(age)));
    }

    /** Same native launch API, velocity, 24 rounds and scatter; no parent flight tick/commands. */
    private static void splitCluster(Entity missile) {
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.ResourceLocation.parse("mianbaos_modernwarfare:littlebomb")).orElse(null);
        if (type == null) return;
        Vec3 velocity = missile.getDeltaMovement();
        for (int index = 0; index < 24; index++) {
            Entity child = type.create(root);
            if (!(child instanceof net.minecraft.world.entity.projectile.Projectile projectile)) continue;
            child.setPos(missile.position()); child.setSilent(true);
            if (child instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow) arrow.setBaseDamage(5);
            try { child.getClass().getMethod("setKnockback", int.class).invoke(child, 1); }
            catch (ReflectiveOperationException ignored) { /* Newer native versions can omit legacy knockback. */ }
            var random = net.minecraft.util.RandomSource.create();
            projectile.shoot(velocity.x + random.nextDouble() * 4 - 2,
                    velocity.y + random.nextDouble() * 4 - 2, velocity.z + random.nextDouble() * 4 - 2,
                    (float) velocity.length(), 10);
            if (!MianbaoBombProjectileHandler.adoptMathematicalCruiseChild(root, child)) root.addFreshEntity(child);
        }
        root.playSound(null, missile.blockPosition(), net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_OFF,
                net.minecraft.sounds.SoundSource.VOICE, 1, 1);
        missile.getPersistentData().putBoolean("分裂", true);
    }

    private static void collide(Shot shot) {
        RocketTerrainCache terrain = TERRAIN.computeIfAbsent(shot.root, RocketTerrainCache::new);
        // Unknown terrain pauses authoritative collision resolution, not rendering.
        // Bounded by the missile's existing maximum lifetime.
        for (int checked = 0; checked < 8 && !shot.pending.isEmpty(); checked++) {
            var step = shot.pending.peekFirst();
            var terrainHit = terrain.sweep(step.from(), step.to());
            if (!terrainHit.ready()) break;
            shot.pending.removeFirst();
            var structure = SableMissileFuze.firstBlockContact(shot.root, step.from(), step.to());
            var access = (MathematicalMissileImpactAccess) shot.adapter;
            var target = ProjectileUtil.getEntityHitResult(shot.root, shot.adapter, step.from(), step.to(),
                    shot.adapter.getBoundingBox().move(step.from().subtract(shot.adapter.position()))
                            .expandTowards(step.incoming()).inflate(1), entity -> access.createFireControl$canHit(entity)
                            && !entity.isSpectator() && !MianbaoCountermeasureCompat.isCountermeasure(entity));
            Vec3 hit = nearest(step.from(), terrainHit.hit(), structure == null ? null : structure.position(),
                    target == null ? null : target.getLocation());
            if (hit != null) {
                Entity missile = shot.adapter;
                net.minecraft.world.phys.HitResult result = target != null && hit == target.getLocation() ? target
                        : new net.minecraft.world.phys.BlockHitResult(hit,
                        net.minecraft.core.Direction.getNearest(-step.incoming().x, -step.incoming().y, -step.incoming().z),
                        net.minecraft.core.BlockPos.containing(hit), false);
                Vec3 after = missile.position(), afterVelocity = missile.getDeltaMovement();
                // Arrow impacts run before its movement. Some native warheads use
                // source position and others use HitResult: preserve that distinction.
                missile.setPos(step.from().x, step.from().y, step.from().z);
                missile.setDeltaMovement(step.incoming());
                if (!net.neoforged.neoforge.event.EventHooks.onProjectileImpact(
                        (net.minecraft.world.entity.projectile.Projectile) missile, result)) access.createFireControl$impact(result);
                if (!missile.isRemoved()) {
                    missile.setPos(after.x, after.y, after.z);
                    missile.setDeltaMovement(afterVelocity);
                }
                terrain.invalidate(hit);
                shot.pending.clear();
                break;
            }
        }
    }

    private static Vec3 nearest(Vec3 origin, Vec3... points) {
        Vec3 best = null;
        for (Vec3 point : points) if (point != null && (best == null
                || point.distanceToSqr(origin) < best.distanceToSqr(origin))) best = point;
        return best;
    }

    private static float lerpRotation(float previous, float current) {
        while (current - previous < -180) previous -= 360;
        while (current - previous >= 180) previous += 360;
        return net.minecraft.util.Mth.lerp(.2F, previous, current);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void sync(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty() && REMOVED.isEmpty()) return;
        Iterator<Shot> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            Shot shot = iterator.next();
            if (shot.adapter.isRemoved()) {
                REMOVED.computeIfAbsent(shot.root, ignored -> new ArrayList<>()).add(shot.adapter.getUUID());
                iterator.remove();
            }
        }
        for (ServerLevel root : event.getServer().getAllLevels()) {
            List<UUID> removed = REMOVED.remove(root);
            if (removed == null) removed = List.of();
            final List<UUID> removals = removed;
            for (var player : event.getServer().getPlayerList().getPlayers()) {
                if (MianbaoMissileCompat.getRootServerLevel(player) != root) continue;
                Vec3 viewer = MianbaoMissileCompat.getWorldPosition(player);
                List<MathematicalMissilePayload.State> states = ACTIVE.values().stream()
                        .filter(shot -> shot.root == root && shot.adapter.position().distanceToSqr(viewer) <= 20000.0 * 20000.0)
                        .map(shot -> {
                            Entity missile = shot.adapter;
                            return new MathematicalMissilePayload.State(missile.getUUID(),
                                    BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()), shot.previous,
                                    missile.position(), missile.getDeltaMovement(), missile.getYRot(), missile.getXRot(),
                                    missile.yRotO, missile.xRotO, missile.tickCount,
                                    AirToAirMissileFlight.managed(missile) ? AirToAirMissileFlight.poweredAge(missile)
                                            : missile.getPersistentData().getDouble("fadongji1"),
                                    missile.getPersistentData().getDouble("fadongji"),
                                    missile.getPersistentData().getDouble("time"));
                        }).toList();
                // Bounded packet size without dropping shots from a large launch wave.
                for (int offset = 0; offset < states.size(); offset += MathematicalMissilePayload.MAX_STATES) {
                    PacketDistributor.sendToPlayer(player, new MathematicalMissilePayload(root.dimension().location(),
                            root.getGameTime(), states.subList(offset, Math.min(states.size(), offset + MathematicalMissilePayload.MAX_STATES)),
                            offset == 0 ? removals : List.of()));
                }
                if (states.isEmpty() && !removals.isEmpty()) PacketDistributor.sendToPlayer(player,
                        new MathematicalMissilePayload(root.dimension().location(), root.getGameTime(), List.of(), removals));
            }
        }
    }

    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        ACTIVE.clear(); TERRAIN.clear(); REMOVED.clear();
    }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private MathematicalMissileManager() {}
}
