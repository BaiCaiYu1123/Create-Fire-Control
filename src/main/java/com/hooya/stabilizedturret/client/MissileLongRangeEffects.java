package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Adds remote missile models and only the far-distance portion of their trails. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class MissileLongRangeEffects {
    private static final String REMOTE_VISUAL_TAG = "CreateFireControlRemoteVisual";
    private static final double NATIVE_PARTICLE_DISTANCE_SQR = 32.0 * 32.0;
    private static final double FORCED_EXPLOSION_DISTANCE_SQR = 512.0 * 512.0;
    private static final int REMOTE_TIMEOUT_TICKS = 6;
    private static final Map<Integer, Long> LAST_TRAIL_TICK = new HashMap<>();
    private static final Map<Integer, Long> CLIENT_FIRST_SEEN_TICK = new HashMap<>();
    private static final Map<UUID, RemoteVisual> REMOTE_VISUALS = new HashMap<>();
    private static Set<Long> missileRenderBlocks = Set.of();
    private static ClientLevel trackedLevel;
    private static int nextProxyId = -1_000_000;

    /** Accepts authoritative root-world state without asking the client for its terrain chunk. */
    public static void acceptRemoteState(MissileRemoteStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;
        ensureLevel(level);

        RemoteVisual visual = REMOTE_VISUALS.get(payload.uuid());
        if (visual == null || visual.entity.isRemoved()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(payload.entityType()).orElse(null);
            if (type == null) return;
            Entity proxy = type.create(level);
            if (proxy == null) return;
            proxy.setId(nextProxyId--);
            proxy.setUUID(UUID.nameUUIDFromBytes(
                    ("create_fire_control:" + payload.uuid()).getBytes(StandardCharsets.UTF_8)));
            proxy.getPersistentData().putBoolean(REMOTE_VISUAL_TAG, true);
            proxy.noPhysics = true;
            proxy.setNoGravity(true);
            proxy.setSilent(true);
            Vec3 velocity = velocity(payload);
            float yaw = yaw(velocity);
            float pitch = pitch(velocity);
            proxy.absMoveTo(payload.x(), payload.y(), payload.z(), yaw, pitch);
            proxy.setOldPosAndRot();
            proxy.setDeltaMovement(velocity);
            level.addEntity(proxy);
            visual = new RemoteVisual(proxy, level.getGameTime());
            REMOTE_VISUALS.put(payload.uuid(), visual);
        } else {
            Entity proxy = visual.entity;
            Vec3 velocity = velocity(payload);
            float oldYaw = proxy.getYRot();
            float oldPitch = proxy.getXRot();
            float newYaw = yaw(velocity);
            float newPitch = pitch(velocity);
            proxy.setOldPosAndRot();
            proxy.absMoveTo(payload.x(), payload.y(), payload.z(), newYaw, newPitch);
            // Mianbao's missile renderers linearly interpolate these fields without
            // wrapping angles. Keep the previous rotation on the nearest equivalent
            // turn so crossing -180/180 degrees cannot flip the model for one frame.
            proxy.yRotO = newYaw - Mth.wrapDegrees(newYaw - oldYaw);
            proxy.xRotO = newPitch - Mth.wrapDegrees(newPitch - oldPitch);
            proxy.setDeltaMovement(velocity);
            proxy.noPhysics = true;
            proxy.setNoGravity(true);
            visual.lastUpdateTick = level.getGameTime();
        }
    }

    /** The authoritative proxy replaces any intermittently paired vanilla copy. */
    public static boolean shouldSuppressNativeMissile(Entity entity) {
        return entity != null
                && !entity.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)
                && MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)
                && REMOTE_VISUALS.containsKey(entity.getUUID());
    }

    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel == level) return;
        REMOTE_VISUALS.clear();
        LAST_TRAIL_TICK.clear();
        CLIENT_FIRST_SEEN_TICK.clear();
        missileRenderBlocks = Set.of();
        trackedLevel = level;
        nextProxyId = -1_000_000;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            missileRenderBlocks = Set.of();
            REMOTE_VISUALS.clear();
            LAST_TRAIL_TICK.clear();
            CLIENT_FIRST_SEEN_TICK.clear();
            trackedLevel = null;
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        REMOTE_VISUALS.entrySet().removeIf(entry -> {
            RemoteVisual visual = entry.getValue();
            boolean expired = visual.entity.isRemoved()
                    || now - visual.lastUpdateTick > REMOTE_TIMEOUT_TICKS;
            if (expired && !visual.entity.isRemoved()) visual.entity.discard();
            return expired;
        });

        Set<Long> blocks = new HashSet<>();
        Set<Integer> activeIds = new HashSet<>();
        List<Entity> expired = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)) continue;
            activeIds.add(entity.getId());
            blocks.add(entity.blockPosition().asLong());
            double nativeAge = entity.getPersistentData().getDouble("fadongji1");
            if (nativeAge > 100.0) entity.getPersistentData().putDouble("fadongji1", 100.0);
            if (!entity.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) {
                long firstSeen = CLIENT_FIRST_SEEN_TICK.computeIfAbsent(entity.getId(), id -> now);
                if (now - firstSeen > clientLifetime(entity) + 20L) expired.add(entity);
            }
        }
        for (Entity entity : expired) entity.discard();
        CLIENT_FIRST_SEEN_TICK.keySet().retainAll(activeIds);
        LAST_TRAIL_TICK.keySet().retainAll(activeIds);
        missileRenderBlocks = Set.copyOf(blocks);
    }

    public static boolean shouldBypassSectionCompilation(BlockPos pos) {
        return pos != null && missileRenderBlocks.contains(pos.asLong());
    }

    public static void onMissileRendered(Entity missile) {
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)
                || ClientAimState.isThermal()) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.gameRenderer.getMainCamera() == null) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        if (minecraft.gameRenderer.getMainCamera().getPosition().distanceToSqr(position)
                <= NATIVE_PARTICLE_DISTANCE_SQR) return;
        long tick = level.getGameTime();
        Long previous = LAST_TRAIL_TICK.put(missile.getId(), tick);
        if (previous != null && previous == tick) return;

        ParticleOptions flame = particle("rocketmissiletrail_3").orElse(null);
        ParticleOptions smoke = particle("newsmoke_2").orElse(null);
        if (flame == null && smoke == null) return;
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        int samples = Mth.clamp((int) Math.ceil(velocity.length()), 1, 5);
        RandomSource random = level.random;
        for (int i = 0; i < samples; i++) {
            double fraction = (i + 0.25) / samples;
            Vec3 sample = position.subtract(velocity.scale(fraction));
            if (flame != null) level.addAlwaysVisibleParticle(
                    flame, true, sample.x, sample.y, sample.z, 0.0, 0.0, 0.0);
            if (smoke != null) level.addAlwaysVisibleParticle(
                    smoke, true, sample.x, sample.y, sample.z,
                    (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.35);
        }
        if (LAST_TRAIL_TICK.size() > 512) {
            LAST_TRAIL_TICK.entrySet().removeIf(entry -> entry.getValue() < tick - 40);
        }
    }

    public static void renderExplosion(MissileExplosionPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;
        if (payload.nativeParticlesExpected()
                && minecraft.gameRenderer.getMainCamera().getPosition().distanceToSqr(
                        payload.x(), payload.y(), payload.z()) <= FORCED_EXPLOSION_DISTANCE_SQR) return;
        ParticleOptions explosion = particle("explosionsmall").orElse(null);
        if (explosion == null) return;
        RandomSource random = level.random;
        int count = Mth.clamp(Math.round(payload.strength() * 14.0F), 28, 90);
        double radius = Math.max(1.5, payload.strength() * 0.75);
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radial = Math.sqrt(random.nextDouble()) * radius;
            double height = random.nextDouble() * radius * 1.7;
            double x = payload.x() + Math.cos(angle) * radial;
            double y = payload.y() + height;
            double z = payload.z() + Math.sin(angle) * radial;
            level.addAlwaysVisibleParticle(explosion, true, x, y, z,
                    (random.nextDouble() - 0.5) * 0.25,
                    random.nextDouble() * 0.25,
                    (random.nextDouble() - 0.5) * 0.25);
        }
    }

    private static Vec3 velocity(MissileRemoteStatePayload payload) {
        return new Vec3(payload.velocityX(), payload.velocityY(), payload.velocityZ());
    }

    private static float yaw(Vec3 velocity) {
        if (velocity.horizontalDistanceSqr() < 1.0e-12) return 0.0F;
        return (float) (Mth.atan2(velocity.x, velocity.z) * Mth.RAD_TO_DEG);
    }

    private static float pitch(Vec3 velocity) {
        if (velocity.lengthSqr() < 1.0e-12) return 0.0F;
        // Match Projectile#updateRotation and Mianbao's renderer: positive X rotation
        // points the missile upward. Negating this value mirrored its vertical heading.
        return (float) (Mth.atan2(velocity.y, velocity.horizontalDistance()) * Mth.RAD_TO_DEG);
    }

    private static int clientLifetime(Entity missile) {
        MianbaoAircraftCompat.WeaponType aircraft = MianbaoAircraftCompat.typeOf(missile);
        if (aircraft != null) return MianbaoAircraftCompat.lifetimeTicks(aircraft);
        if (MianbaoAirDefenseCompat.isMissile(missile)) {
            return MianbaoAirDefenseCompat.getLifetimeTicks(missile);
        }
        return 20 * 20;
    }

    private static Optional<ParticleOptions> particle(String path) {
        Object type = BuiltInRegistries.PARTICLE_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path));
        return type instanceof ParticleOptions options ? Optional.of(options) : Optional.empty();
    }

    private static final class RemoteVisual {
        private final Entity entity;
        private long lastUpdateTick;

        private RemoteVisual(Entity entity, long lastUpdateTick) {
            this.entity = entity;
            this.lastUpdateTick = lastUpdateTick;
        }
    }

    private MissileLongRangeEffects() {
    }
}