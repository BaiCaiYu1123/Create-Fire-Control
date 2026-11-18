package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import com.hooya.stabilizedturret.network.CountermeasureSalvoPayload;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import com.hooya.stabilizedturret.network.NuclearShockwavePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Adds remote missile models and only the far-distance portion of their trails. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class MissileLongRangeEffects {
    private static final String REMOTE_VISUAL_TAG = "CreateFireControlRemoteVisual";
    private static final ResourceLocation COUNTERMEASURE_TYPE = ResourceLocation.fromNamespaceAndPath(
            "mianbaos_modernwarfare", "countermeasure");
    private static final double NATIVE_PARTICLE_DISTANCE_SQR = 32.0 * 32.0;
    private static final double NATIVE_EXPLOSION_DISTANCE_SQR = 32.0 * 32.0;
    private static final int REMOTE_TIMEOUT_TICKS = 6;
    private static final int TRAIL_PARTICLES_PER_TICK = 128;
    private static final int EXPLOSION_PARTICLES_PER_TICK = 64;
    private static final int MAX_QUEUED_EXPLOSION_VISUALS = 64;
    private static final double EXPLOSION_VISUAL_MERGE_DISTANCE_SQR = 4.0 * 4.0;
    private static final int NUCLEAR_PARTICLES_PER_TICK = 48;
    private static final int SHOCKWAVE_SOURCES_PER_TICK = 10;
    private static final Map<Integer, Long> LAST_TRAIL_TICK = new HashMap<>();
    private static final Map<Integer, Long> CLIENT_FIRST_SEEN_TICK = new HashMap<>();
    private static final Map<UUID, RemoteVisual> REMOTE_VISUALS = new HashMap<>();
    private static final Set<UUID> DF26_MISSILE_IDS = new HashSet<>();
    private static final List<NuclearVisual> NUCLEAR_VISUALS = new ArrayList<>();
    private static final List<NuclearShockwaveVisual> NUCLEAR_SHOCKWAVES = new ArrayList<>();
    private static final List<MianbaoDetonationVisual> MIANBAO_DETONATIONS = new ArrayList<>();
    private static final List<AlwaysVisibleExplosionEmitter> EXPLOSION_EMITTERS = new ArrayList<>();
    private static final List<RocketExplosionVisual> ROCKET_EXPLOSION_VISUALS =
            new ArrayList<>();
    private static Set<Long> missileRenderBlocks = Set.of();
    private static ClientLevel trackedLevel;
    private static int nextProxyId = -1_000_000;
    private static int nuclearFlashTicks;
    private static int trailParticlesRemaining;
    private static final int NUCLEAR_FLASH_DURATION = 40;

    public static void acceptCountermeasureSalvo(CountermeasureSalvoPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())) return;
        ensureLevel(level);
        for (UUID id : payload.removed()) {
            RemoteVisual removed = REMOTE_VISUALS.remove(id);
            if (removed != null) {
                LAST_TRAIL_TICK.remove(removed.entity.getId());
                removed.entity.discard();
            }
        }
        for (var flare : payload.flares()) {
            Vec3 p = flare.position(), v = flare.velocity();
            if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)
                    || !Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)) continue;
            acceptRemoteState(new MissileRemoteStatePayload(flare.id(), COUNTERMEASURE_TYPE, p.x, p.y, p.z,
                    v.x, v.y, v.z, 0, 0, 0, 1, false, payload.serverTick()));
        }
    }

    /** Accepts authoritative root-world state without asking the client for its terrain chunk. */
    public static void acceptRemoteState(MissileRemoteStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;
        ensureLevel(level);
        boolean countermeasure = COUNTERMEASURE_TYPE.equals(payload.entityType());
        if (payload.df26()) DF26_MISSILE_IDS.add(payload.uuid());
        else DF26_MISSILE_IDS.remove(payload.uuid());
        if (!countermeasure) markTrackedDf26(level, payload);
        ClientWeaponViewState.acceptAirAge(payload.uuid(), payload.airPoweredAge());
        ClientWeaponViewState.acceptLandAge(payload.uuid(), payload.landPoweredAge());

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
            AirToAirMissileFlight.acceptAge(proxy, payload.airPoweredAge());
            LandAttackMissileFlight.acceptAge(proxy, payload.landPoweredAge());
            MianbaoBallisticMissileCompat.setDf26(proxy, payload.df26());
            proxy.noPhysics = true;
            proxy.setNoGravity(true);
            proxy.setSilent(true);
            Vec3 velocity = velocity(payload);
            float yaw = yaw(velocity);
            float pitch = pitch(velocity);
            boolean detachedFromClientChunks =
                    MianbaoBallisticMissileCompat.isMissileType(type)
                    || com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance.isOrdinaryType(type)
                    || MianbaoCountermeasureCompat.isCountermeasure(proxy);
            Vec3 authoritativePosition = new Vec3(payload.x(), payload.y(), payload.z());
            Vec3 initialPosition = detachedFromClientChunks && !countermeasure
                    ? nativeRenderedPosition(level, payload.uuid(), authoritativePosition)
                    : authoritativePosition;
            proxy.absMoveTo(initialPosition.x, initialPosition.y, initialPosition.z, yaw, pitch);
            proxy.setOldPosAndRot();
            proxy.setDeltaMovement(velocity);
            if (!countermeasure) applyRemoteRotation(proxy, payload);
            if (!detachedFromClientChunks) level.addEntity(proxy);
            visual = new RemoteVisual(proxy, level.getGameTime(),
                    detachedFromClientChunks, payload);
            REMOTE_VISUALS.put(payload.uuid(), visual);
            AirToAirMissileSounds.update(payload.uuid(), proxy);
            if (detachedFromClientChunks && !MianbaoCountermeasureCompat.isCountermeasure(proxy)) {
                StabilizedTurret.LOGGER.info(
                        "Created chunk-independent missile visual {} at {}",
                        payload.uuid(), proxy.position());
            }
        } else {
            MianbaoBallisticMissileCompat.setDf26(
                    visual.entity, payload.df26());
            AirToAirMissileFlight.acceptAge(visual.entity, payload.airPoweredAge());
            AirToAirMissileSounds.update(payload.uuid(), visual.entity);
            LandAttackMissileFlight.acceptAge(visual.entity, payload.landPoweredAge());
            if (visual.detachedFromClientChunks) {
                visual.acceptDetached(payload, level.getGameTime());
                return;
            }
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
            applyRemoteRotation(proxy, payload);
            proxy.noPhysics = true;
            proxy.setNoGravity(true);
            visual.lastUpdateTick = level.getGameTime();
            visual.lastExtrapolatedTick = visual.lastUpdateTick;
        }
    }

    /** Returns the no-chunk-loading proxy used by television guidance cameras. */
    public static Entity getRemoteVisual(UUID missileId) {
        if (missileId == null) return null;
        RemoteVisual visual = REMOTE_VISUALS.get(missileId);
        return visual == null || visual.entity.isRemoved() ? null : visual.entity;
    }

    public static void removeRemote(com.hooya.stabilizedturret.network.MissileRemoteRemovalPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())) return;
        RemoteVisual visual = REMOTE_VISUALS.remove(payload.uuid());
        if (visual != null) {
            LAST_TRAIL_TICK.remove(visual.entity.getId());
            visual.entity.discard();
        }
    }

    /** Ballistic proxies rendered independently of ClientLevel chunk sections. */
    public static List<Entity> detachedBallisticVisuals() {
        List<Entity> result = new ArrayList<>();
        for (RemoteVisual visual : REMOTE_VISUALS.values()) {
            if (visual.detachedFromClientChunks && !visual.entity.isRemoved()
                    && !MianbaoCountermeasureCompat.isCountermeasure(visual.entity)) {
                result.add(visual.entity);
            }
        }
        return List.copyOf(result);
    }

    /** The authoritative proxy replaces any intermittently paired vanilla copy. */
    public static boolean shouldSuppressNativeMissile(Entity entity) {
        return entity != null
                && !entity.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)
                && (MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)
                || MianbaoCountermeasureCompat.isCountermeasure(entity))
                && REMOTE_VISUALS.containsKey(entity.getUUID());
    }

    public static boolean isDf26Visual(Entity entity) {
        return entity != null && (MianbaoBallisticMissileCompat.isDf26(entity)
                || DF26_MISSILE_IDS.contains(entity.getUUID()));
    }

    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel == level) return;
        REMOTE_VISUALS.clear();
        DF26_MISSILE_IDS.clear();
        NUCLEAR_VISUALS.clear();
        NUCLEAR_SHOCKWAVES.clear();
        MIANBAO_DETONATIONS.clear();
        EXPLOSION_EMITTERS.clear();
        ROCKET_EXPLOSION_VISUALS.clear();
        nuclearFlashTicks = 0;
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
            DF26_MISSILE_IDS.clear();
            NUCLEAR_VISUALS.clear();
            NUCLEAR_SHOCKWAVES.clear();
            MIANBAO_DETONATIONS.clear();
            EXPLOSION_EMITTERS.clear();
            ROCKET_EXPLOSION_VISUALS.clear();
            nuclearFlashTicks = 0;
            trailParticlesRemaining = 0;
            LAST_TRAIL_TICK.clear();
            CLIENT_FIRST_SEEN_TICK.clear();
            trackedLevel = null;
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        trailParticlesRemaining = TRAIL_PARTICLES_PER_TICK;
        if (nuclearFlashTicks > 0) nuclearFlashTicks--;
        NUCLEAR_VISUALS.removeIf(visual -> visual.tick(level));
        NUCLEAR_SHOCKWAVES.removeIf(visual -> visual.tick(level));
        MIANBAO_DETONATIONS.removeIf(visual -> visual.tick(level));
        EXPLOSION_EMITTERS.removeIf(emitter -> emitter.tick(level));
        REMOTE_VISUALS.entrySet().removeIf(entry -> {
            RemoteVisual visual = entry.getValue();
            boolean expired = visual.entity.isRemoved()
                    || now - visual.lastUpdateTick > REMOTE_TIMEOUT_TICKS;
            if (expired && !visual.entity.isRemoved()) visual.entity.discard();
            return expired;
        });
        List<RemoteVisual> tickVisuals = new ArrayList<>(REMOTE_VISUALS.values());
        int trailOffset = tickVisuals.isEmpty() ? 0 : (int) Math.floorMod(now * 32L, tickVisuals.size());
        for (int index = 0; index < tickVisuals.size(); index++) {
            RemoteVisual visual = tickVisuals.get((index + trailOffset) % tickVisuals.size());
            visual.extrapolate(now);
            if (visual.detachedFromClientChunks) {
                onMissileRendered(visual.entity);
            }
        }
        // Vanilla does not consistently submit its current camera entity to
        // EntityRenderDispatcher. Television guidance uses the missile proxy
        // as that camera entity, so relying only on onMissileRendered made its
        // trail disappear precisely while the player was guiding it. Emit the
        // same de-duplicated trail once per client tick from the active camera
        // missile; LAST_TRAIL_TICK prevents a second copy if a renderer also
        // happens to submit the entity in this frame.
        Entity televisionMissile = ClientTelevisionGuidanceState.getMissileVisual();
        if (televisionMissile != null) onMissileRendered(televisionMissile);
        int explosionBudget = EXPLOSION_PARTICLES_PER_TICK;
        int remainingVisuals = ROCKET_EXPLOSION_VISUALS.size();
        Iterator<RocketExplosionVisual> explosionIterator =
                ROCKET_EXPLOSION_VISUALS.iterator();
        while (explosionIterator.hasNext() && explosionBudget > 0) {
            RocketExplosionVisual visual = explosionIterator.next();
            int share = Math.max(1, Math.min(12,
                    explosionBudget / Math.max(1, remainingVisuals)));
            explosionBudget -= visual.emit(level, share);
            remainingVisuals--;
            if (visual.complete()) explosionIterator.remove();
        }
        // Rocket smoke is useful at impact time. Do not build a delayed cloud
        // backlog when a salvo has exhausted the shared live particle limit.
        ROCKET_EXPLOSION_VISUALS.removeIf(visual -> !visual.preservesNativeParticles()
                && now - visual.createdAt > 2L);

        Set<Long> blocks = new HashSet<>();
        Set<Integer> activeIds = new HashSet<>();
        for (RemoteVisual visual : REMOTE_VISUALS.values()) activeIds.add(visual.entity.getId());
        List<Entity> expired = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            boolean countermeasure = MianbaoCountermeasureCompat.isCountermeasure(entity);
            if (!countermeasure
                    && !MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)) continue;
            activeIds.add(entity.getId());
            blocks.add(entity.blockPosition().asLong());
            if (!countermeasure && !AirToAirMissileFlight.managed(entity)) {
                double nativeAge = entity.getPersistentData().getDouble("fadongji1");
                if (nativeAge > 100.0) {
                    entity.getPersistentData().putDouble("fadongji1", 100.0);
                }
            }
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
        if (ClientWeaponViewState.observes(missile.getUUID())) return;
        if (missile.getPersistentData().getBoolean(MathematicalMissileVisuals.VISUAL)) return;
        // Virtual rockets supply their bounded effects directly. Re-entering
        // the native smoke path here would restore expensive Sable collisions.
        if (missile.getPersistentData().getBoolean("CreateFireControlVirtualRocketVisual")) return;
        if (MianbaoCountermeasureCompat.isCountermeasure(missile)) {
            renderCountermeasureTrail(missile);
            return;
        }
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)
                || trailParticlesRemaining <= 0) return;
        if (!com.hooya.stabilizedturret.config.MissileSettings.emitting(missile)) return;
        if (!AirToAirMissileFlight.emitting(missile)) return;
        if (!LandAttackMissileFlight.emitting(missile)) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.gameRenderer.getMainCamera() == null) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        if (minecraft.gameRenderer.getMainCamera().getPosition().distanceToSqr(position)
                <= NATIVE_PARTICLE_DISTANCE_SQR
                && !missile.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) return;
        long tick = level.getGameTime();
        Long previous = LAST_TRAIL_TICK.put(missile.getId(), tick);
        if (previous != null && previous == tick) return;

        if (MianbaoBallisticMissileCompat.isMissile(missile)) {
            // Native ballistic entities are also submitted while still sitting
            // unignited on their rack. Remote trail compensation must begin only
            // after ignition and detachment; otherwise entering tracking range
            // creates exhaust around a completely idle missile.
            if (!missile.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) {
                MianbaoBallisticMissileCompat.Telemetry telemetry =
                        MianbaoBallisticMissileCompat.telemetry(missile);
                if (telemetry == null || !telemetry.ignited() || telemetry.docked()) return;
            }
            renderBallisticLaunchSmoke(level, missile, position,
                    MianbaoMissileCompat.getWorldVelocity(missile));
            renderBallisticTrail(level, position,
                    MianbaoMissileCompat.getWorldVelocity(missile),
                    missile);
            return;
        }

        ParticleOptions flame = particle(com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance
                .isOrdinaryType(missile.getType()) ? "newrocketmissiletrailsmall" : "rocketmissiletrail_3").orElse(null);
        ParticleOptions smoke = particle("newsmoke_2").orElse(null);
        if (flame == null && smoke == null) return;
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        int samples = Mth.clamp((int) Math.ceil(velocity.length()), 1, 5);
        RandomSource random = level.random;
        for (int i = 0; i < samples && trailParticlesRemaining > 0; i++) {
            double fraction = (i + 0.25) / samples;
            Vec3 sample = position.subtract(velocity.scale(fraction));
            if (flame != null && trailParticlesRemaining-- > 0) {
                addMissileTrailParticle(level, missile,
                        flame, sample.x, sample.y, sample.z,
                        0.0, 0.0, 0.0);
            }
            if (smoke != null && trailParticlesRemaining > 0) {
                trailParticlesRemaining--;
                addMissileTrailParticle(level, missile,
                        smoke, sample.x, sample.y, sample.z,
                        (random.nextDouble() - 0.5) * 0.35,
                        (random.nextDouble() - 0.5) * 0.35,
                        (random.nextDouble() - 0.5) * 0.35);
            }
        }
        if (LAST_TRAIL_TICK.size() > 512) {
            LAST_TRAIL_TICK.entrySet().removeIf(entry -> entry.getValue() < tick - 40);
        }
    }

    private static void addMissileTrailParticle(ClientLevel level, Entity missile, ParticleOptions options,
                                                double x, double y, double z,
                                                double dx, double dy, double dz) {
        if (BeamMissileParticles.isManaged(missile)) {
            BeamMissileParticles.spawn(level, options, x, y, z, dx, dy, dz);
        } else {
            level.addAlwaysVisibleParticle(options, true, x, y, z, dx, dy, dz);
        }
    }

    /** Replays Mianbao's full-size ballistic plume beyond native particle range. */
    private static void renderBallisticTrail(ClientLevel level, Vec3 position,
                                             Vec3 velocity, Entity missile) {
        boolean df26 = MianbaoBallisticMissileCompat.isDf26(missile);
        if (df26) Df26BallisticMissileVisuals.beginScaledParticles(missile);
        try {
            renderBallisticTrailParticles(level, position, velocity);
        } finally {
            if (df26) Df26BallisticMissileVisuals.endScaledParticles();
        }
    }

    /** Replays the native downward launch cloud for detached/far visuals. */
    private static void renderBallisticLaunchSmoke(ClientLevel level, Entity missile,
                                                   Vec3 position, Vec3 velocity) {
        if (!MianbaoBallisticMissileCompat.isDf26(missile)
                || velocity == null || velocity.lengthSqr() < 1.0e-8) return;
        int launchAge = MianbaoBallisticMissileCompat.launchAge(missile);
        if (launchAge < 0 || launchAge > 180 || (launchAge & 1) != 0) return;
        Quaternionf rotation = MianbaoBallisticMissileCompat.renderRotation(missile);
        if (rotation == null) return;
        Vector3f forwardVector = rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z).normalize();
        Vector3f nozzleOffset = rotation.transform(
                new Vector3f(0.0F, -0.5F, -3.0F / 16.0F));
        Vec3 nozzle = position.add(nozzleOffset.x, nozzleOffset.y, nozzleOffset.z);
        Vec3 exhaustEnd = nozzle.subtract(forward.scale(
                Math.min(24.0, (launchAge + 1) * 1.35)));
        Df26BallisticMissileVisuals.beginScaledParticles(missile);
        try {
            Df26BallisticMissileVisuals.emitReliableGroundSmoke(
                    missile, level, nozzle, exhaustEnd, forward, launchAge);
        } finally {
            Df26BallisticMissileVisuals.endScaledParticles();
        }
    }

    private static void renderBallisticTrailParticles(
            ClientLevel level, Vec3 position, Vec3 velocity
    ) {
        if (velocity == null || velocity.lengthSqr() < 1.0e-8
                || trailParticlesRemaining <= 0) return;
        Vec3 direction = velocity.normalize();
        ParticleOptions flame = particle("rocketmissiletrail_3").orElse(null);
        ParticleOptions smoke = particle("ballistic_flight_smoke").orElse(null);

        // Match BallisticMissileLaunchEffects: three flame samples near the
        // nozzle and up to eighteen dense smoke samples across the last tick's
        // movement. Keeping that density prevents the plume thinning at Mach 2/3.
        Vec3 nozzle = position.subtract(direction.scale(0.5));
        for (int index = 0; index < 3 && flame != null
                && trailParticlesRemaining > 0; index++) {
            Vec3 sample = nozzle.subtract(direction.scale(
                    Df26BallisticMissileVisuals.FLAME_LENGTH * index / 3.0));
            trailParticlesRemaining--;
            level.addAlwaysVisibleParticle(flame, true,
                    sample.x, sample.y, sample.z, 0.0, 0.0, 0.0);
        }
        if (smoke == null || trailParticlesRemaining <= 0) return;
        int samples = Math.min(18, Math.max(3,
                (int) Math.ceil(velocity.length() / 0.3)));
        RandomSource random = level.random;
        for (int index = 0; index < samples && trailParticlesRemaining > 0; index++) {
            double fraction = (index + 1.0) / samples;
            Vec3 sample = nozzle.subtract(velocity.scale(fraction));
            sample = Df26BallisticMissileVisuals.circularTailPosition(smoke, sample);
            trailParticlesRemaining--;
            level.addAlwaysVisibleParticle(smoke, true,
                    sample.x, sample.y, sample.z,
                    random.nextGaussian() * 0.018,
                    random.nextGaussian() * 0.018,
                    random.nextGaussian() * 0.018);
        }
    }

    private static void markTrackedDf26(
            ClientLevel level, MissileRemoteStatePayload payload
    ) {
        for (Entity candidate : level.entitiesForRendering()) {
            if (candidate.getUUID().equals(payload.uuid())) {
                AirToAirMissileFlight.acceptAge(candidate, payload.airPoweredAge());
                LandAttackMissileFlight.acceptAge(candidate, payload.landPoweredAge());
                if (MianbaoBallisticMissileCompat.isMissile(candidate))
                    MianbaoBallisticMissileCompat.setDf26(candidate, payload.df26());
                return;
            }
        }
    }

    private static void renderCountermeasureTrail(Entity countermeasure) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.gameRenderer.getMainCamera() == null) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(countermeasure);
        boolean remote = countermeasure.getPersistentData().getBoolean(REMOTE_VISUAL_TAG);
        if (!remote && minecraft.gameRenderer.getMainCamera().getPosition()
                .distanceToSqr(position) <= NATIVE_PARTICLE_DISTANCE_SQR) {
            return;
        }
        long tick = level.getGameTime();
        Long previous = LAST_TRAIL_TICK.put(countermeasure.getId(), tick);
        if (previous != null && previous == tick) return;

        if (remote) {
            VirtualRocketParticles.countermeasure(level, position,
                    minecraft.gameRenderer.getMainCamera().getPosition());
            return;
        }
        if (trailParticlesRemaining <= 0) return;

        ParticleOptions bright = particle("countermeasure_trail").orElse(null);
        ParticleOptions ember = particle("countermeasuretrail_1").orElse(null);
        ParticleOptions smoke = particle("newsmoke_5").orElse(null);
        RandomSource random = level.random;
        if (bright != null && trailParticlesRemaining > 0) {
            trailParticlesRemaining--;
            level.addAlwaysVisibleParticle(
                    bright, true, position.x, position.y, position.z, 0.0, 0.0, 0.0);
        }
        if (ember != null && trailParticlesRemaining > 0) {
            trailParticlesRemaining--;
            level.addAlwaysVisibleParticle(
                    ember, true, position.x, position.y, position.z, 0.0, 0.0, 0.0);
        }
        if (trailParticlesRemaining > 0) {
            trailParticlesRemaining--;
            level.addAlwaysVisibleParticle(
                    ParticleTypes.FLAME, true, position.x, position.y, position.z,
                    (random.nextDouble() - 0.5) * 0.4,
                    (random.nextDouble() - 0.5) * 0.4,
                    (random.nextDouble() - 0.5) * 0.4);
        }
        if (smoke != null && trailParticlesRemaining > 0) {
            trailParticlesRemaining--;
            level.addAlwaysVisibleParticle(
                    smoke, true, position.x, position.y, position.z,
                    (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.35);
        }
    }

    public static void renderExplosion(MissileExplosionPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;
        if (payload.nativeParticlesExpected()
                && minecraft.gameRenderer.getMainCamera().getPosition().distanceToSqr(
                        payload.x(), payload.y(), payload.z()) <= NATIVE_EXPLOSION_DISTANCE_SQR) return;
        if (payload.nuclear()) {
            renderNuclearExplosion(level, payload);
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        Vec3 position = new Vec3(payload.x(), payload.y(), payload.z());
        for (RocketExplosionVisual queued : ROCKET_EXPLOSION_VISUALS) {
            if (payload.particleProfile() == MissileExplosionPayload.GENERIC
                    && !queued.preservesNativeParticles()
                    && now - queued.createdAt <= 1L
                    && queued.position.distanceToSqr(position)
                    <= EXPLOSION_VISUAL_MERGE_DISTANCE_SQR) {
                queued.merge(payload.strength());
                return;
            }
        }
        if (ROCKET_EXPLOSION_VISUALS.size()
                >= MAX_QUEUED_EXPLOSION_VISUALS) return;
        ROCKET_EXPLOSION_VISUALS.add(new RocketExplosionVisual(
                position, payload.strength(), payload.particleProfile(),
                now, level.random.nextLong()));
        playRocketExplosionSounds(payload, level.random);
    }

    /** Starts the exact, client-only Mianbao impact effects selected by the server. */
    public static void renderMianbaoDetonation(MianbaoDetonationVisualPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || payload == null) return;
        ensureLevel(level);
        MianbaoDetonationVisual visual = new MianbaoDetonationVisual(payload);
        if (payload.initialEffects()) visual.start(level);
        MIANBAO_DETONATIONS.add(visual);
        if (payload.initialEffects()
                && isNuclearProfile(payload.profile())
                && payload.closePlayerEffects()) {
            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            if (camera.distanceToSqr(payload.x(), payload.y(), payload.z())
                    <= (12.0 * 16.0) * (12.0 * 16.0)) {
                nuclearFlashTicks = NUCLEAR_FLASH_DURATION;
            }
        }
    }

    @SubscribeEvent
    public static void renderNuclearFlash(RenderGuiEvent.Post event) {
        if (nuclearFlashTicks <= 0) return;
        GuiGraphics graphics = event.getGuiGraphics();
        float normalized = nuclearFlashTicks
                / (float) NUCLEAR_FLASH_DURATION;
        // The original nuke overlay is an opaque initial flash followed by a
        // short fade, rather than a persistent white tint.
        float alpha = normalized > 0.45F
                ? 1.0F
                : normalized / 0.45F;
        int packedAlpha = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(),
                (packedAlpha << 24) | 0x00FFFFFF);
    }

    private static void renderNuclearExplosion(ClientLevel level,
                                                MissileExplosionPayload payload) {
        // Use Mianbao's original particle types, positions, velocities and
        // counts. Spreading the 282 emissions over a few client ticks keeps a
        // distant nuclear impact visible without a one-frame particle stall.
        NUCLEAR_VISUALS.add(new NuclearVisual(payload.x(), payload.y(), payload.z(),
                level.random.nextLong()));
    }

    /** Reconstructs one real delayed Mianbao NukeBlock shockwave step. */
    public static void renderNuclearShockwave(NuclearShockwavePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || payload == null) return;
        ensureLevel(level);

        // Nearby players already receive Mianbao's native forced smoke and
        // explosion packets. A remote impact view, or a genuinely distant
        // camera, needs the always-visible reconstruction below.
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double distanceSqr = camera.distanceToSqr(payload.x(), payload.y(), payload.z());
        if (!RemoteImpactRenderManager.hasRemoteView()
                && distanceSqr <= 512.0 * 512.0) return;
        NUCLEAR_SHOCKWAVES.add(new NuclearShockwaveVisual(payload));
    }

    /**
     * Mianbao creates sixty strength-10 ring explosions plus one centre
     * explosion per radius. Vanilla's emitter makes six EXPLOSION particles
     * for eight ticks, but its children use normal distance culling. Replaying
     * those children through addAlwaysVisibleParticle preserves the original
     * fire ring for a remote camera without relying on the missing packets.
     */
    private static final class NuclearShockwaveVisual {
        private static final int SOURCE_LIFETIME = 8;
        private final List<ExplosionSource> sources = new ArrayList<>(61);
        private final ParticleOptions smoke;
        private int nextSource;

        private NuclearShockwaveVisual(NuclearShockwavePayload payload) {
            RandomSource random = RandomSource.create(payload.seed());
            this.smoke = particle("nukesmoke_3")
                    .or(() -> particle("nukesmoke_2"))
                    .or(() -> particle("newsmoke_2"))
                    .orElse(null);

            // Make the centre flash part of the first activation batch. The
            // original procedure triggers it in the same tick as the ring.
            sources.add(new ExplosionSource(
                    payload.x(), payload.y() + payload.radius(), payload.z(),
                    random.nextLong(), false));
            double ringRadius = payload.radius() * 5.0;
            for (int index = 0; index < 60; index++) {
                double angle = index * Math.PI * 2.0 / 60.0;
                sources.add(new ExplosionSource(
                        payload.x() + Math.cos(angle) * ringRadius,
                        payload.y() + random.nextInt(5, 11),
                        payload.z() + Math.sin(angle) * ringRadius,
                        random.nextLong(), true));
            }
        }

        boolean tick(ClientLevel level) {
            int activated = 0;
            while (nextSource < sources.size()
                    && activated++ < SHOCKWAVE_SOURCES_PER_TICK) {
                ExplosionSource source = sources.get(nextSource++);
                source.active = true;
                if (source.ring && smoke != null) {
                    // Command: delta(0,5,0), speed .1, count 0 => velocity
                    // (0,.5,0), matching ClientPacketListener exactly.
                    level.addAlwaysVisibleParticle(smoke, true,
                            source.x, source.y, source.z, 0.0, 0.5, 0.0);
                }
            }

            boolean alive = false;
            for (ExplosionSource source : sources) {
                if (!source.active || source.age >= SOURCE_LIFETIME) continue;
                alive = true;
                for (int particleIndex = 0; particleIndex < 6; particleIndex++) {
                    double x = source.x
                            + (source.random.nextDouble() - source.random.nextDouble()) * 4.0;
                    double y = source.y
                            + (source.random.nextDouble() - source.random.nextDouble()) * 4.0;
                    double z = source.z
                            + (source.random.nextDouble() - source.random.nextDouble()) * 4.0;
                    level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION, true,
                            x, y, z, (double) source.age / SOURCE_LIFETIME, 0.0, 0.0);
                }
                source.age++;
            }
            return nextSource >= sources.size() && !alive;
        }
    }

    private static final class ExplosionSource {
        private final double x;
        private final double y;
        private final double z;
        private final RandomSource random;
        private final boolean ring;
        private boolean active;
        private int age;

        private ExplosionSource(double x, double y, double z, long seed, boolean ring) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.random = RandomSource.create(seed);
            this.ring = ring;
        }
    }

    private static final class NuclearVisual {
        private final double x;
        private final double y;
        private final double z;
        private final RandomSource random;
        private final ParticleOptions explosionOne;
        private final ParticleOptions explosionThree;
        private final ParticleOptions smokeOne;
        private final ParticleOptions smokeTwo;
        private int phase;
        private int index;

        private NuclearVisual(double x, double y, double z, long seed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.random = RandomSource.create(seed);
            this.explosionOne = particle("nukeexplosion_1")
                    .or(() -> particle("nukeexplosion_2"))
                    .or(() -> particle("explosionlarge"))
                    .orElse(ParticleTypes.EXPLOSION_EMITTER);
            this.explosionThree = particle("nukeexplosion_3").orElse(explosionOne);
            this.smokeOne = particle("nukesmoke_1")
                    .or(() -> particle("newsmoke_2")).orElse(null);
            this.smokeTwo = particle("nukesmoke_2").orElse(smokeOne);
        }

        boolean tick(ClientLevel level) {
            int budget = NUCLEAR_PARTICLES_PER_TICK;
            while (budget > 0 && phase < 5) {
                switch (phase) {
                    case 0 -> {
                        // Original loop: 50 x nukeexplosion_1 and
                        // nukeexplosion_3 command pairs.
                        add(level, explosionOne,
                                x + between(-1.0, 1.0) * 20.0,
                                y - 5.0 + between(-0.5, 0.8) * 20.0,
                                z + between(-1.0, 1.0) * 20.0,
                                0.0, 15.0, 0.0);
                        add(level, explosionThree,
                                x + between(-1.0, 1.0) * 16.0,
                                y - 5.0 + between(-0.5, 0.7) * 15.0,
                                z + between(-1.0, 1.0) * 16.0,
                                0.0, 13.0, 0.0);
                        budget -= 2;
                        if (++index >= 50) advancePhase();
                    }
                    case 1 -> {
                        // Original loop: twelve vertical nukeexplosion_3 jets.
                        add(level, explosionThree, x + random.nextInt(-2, 3), y,
                                z + random.nextInt(-2, 3), 0.0, index, 0.0);
                        budget--;
                        if (++index >= 12) advancePhase();
                    }
                    case 2 -> {
                        // Original descending nukeexplosion_1 column.
                        double t2 = index * 0.5;
                        double t3 = index * 0.6;
                        add(level, explosionOne,
                                x + between(-1.0, 1.0) * t3, y + 20.0 - t2,
                                z + between(-1.0, 1.0) * t3,
                                0.0, -1.0, 0.0);
                        budget--;
                        if (++index >= 50) advancePhase();
                    }
                    case 3 -> {
                        // Equivalent to the original count=60 nukesmoke_2
                        // command with delta (30, -5, 30), speed 0.1.
                        add(level, smokeTwo,
                                x + random.nextGaussian() * 30.0,
                                y + random.nextGaussian() * -5.0,
                                z + random.nextGaussian() * 30.0,
                                random.nextGaussian() * 0.1,
                                random.nextGaussian() * 0.1,
                                random.nextGaussian() * 0.1);
                        budget--;
                        if (++index >= 60) advancePhase();
                    }
                    default -> {
                        // Original ring: 60 paired nukesmoke_1 commands.
                        double angle = index * 0.10471975;
                        double radius = random.nextInt(40, 51);
                        double fromX = x + Math.cos(angle) * radius;
                        double fromZ = z + Math.sin(angle) * radius;
                        double horizontalOffset = random.nextInt(-5, 6);
                        double depthOffset = random.nextInt(-5, 6);
                        add(level, smokeOne, fromX + horizontalOffset,
                                y + 50.0 + random.nextInt(-5, 6), fromZ + depthOffset,
                                Math.cos(angle) * 10.0, between(-0.25, 0.25),
                                Math.sin(angle) * 10.0);
                        add(level, smokeOne, fromX + random.nextInt(-5, 6),
                                y + 5.0 + random.nextInt(-5, 6),
                                fromZ + random.nextInt(-5, 6),
                                Math.cos(angle) * 15.0, between(-0.25, 0.25),
                                Math.sin(angle) * 15.0);
                        budget -= 2;
                        if (++index >= 60) advancePhase();
                    }
                }
            }
            return phase >= 5;
        }

        private void advancePhase() {
            phase++;
            index = 0;
        }

        private double between(double min, double max) {
            return min + random.nextDouble() * (max - min);
        }

        private static void add(ClientLevel level, ParticleOptions options,
                                double x, double y, double z,
                                double velocityX, double velocityY, double velocityZ) {
            if (options != null) {
                level.addAlwaysVisibleParticle(options, true, x, y, z,
                        velocityX, velocityY, velocityZ);
            }
        }
    }

    /** Detached reproduction of Mianbao's impact Procedures. It never mutates terrain. */
    private static final class MianbaoDetonationVisual {
        private static final int NUCLEAR_WAVE_INTERVAL = 11;
        private static final int NUCLEAR_WAVES = 30;
        private final byte profile;
        private final double x;
        private final double y;
        private final double z;
        private final long seed;
        private final boolean initialEffects;
        private final boolean closePlayerEffects;
        private final RandomSource random;
        private int age;
        private boolean delayedSmoke;
        private int smokeStart;
        private int smokeInterval;
        private int smokeCount;
        private int smokeEmitted;
        private int nextNuclearWaveRadius = 1;
        private int activeNuclearWaveRadius;
        private int nuclearWaveSource;
        private int nuclearWaveSliceTicks;

        private MianbaoDetonationVisual(MianbaoDetonationVisualPayload payload) {
            this.profile = payload.profile();
            this.x = payload.x();
            this.y = payload.y();
            this.z = payload.z();
            this.seed = payload.seed();
            this.initialEffects = payload.initialEffects();
            this.closePlayerEffects = payload.closePlayerEffects();
            this.random = RandomSource.create(payload.seed());
        }

        private void start(ClientLevel level) {
            switch (profile) {
                case MianbaoDetonationVisualPayload.BOMB -> startLargeBomb(level, false);
                case MianbaoDetonationVisualPayload.GROUND -> startLargeBomb(level, true);
                case MianbaoDetonationVisualPayload.LITTLE_BOMB -> startLittleBomb(level);
                case MianbaoDetonationVisualPayload.NUCLEAR,
                     MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR ->
                        startNuclear(level);
                default -> {
                }
            }
        }

        private void startLargeBomb(ClientLevel level, boolean ground) {
            vanillaExplosion(level, x, y, z, true, random.nextLong());
            playMianbaoSound(level, "bomb_explode_3", 16.0F,
                    between(0.8F, 1.0F));
            playMianbaoSound(level, "explode_far_sound4", 32.0F,
                    between(0.7F, 1.0F));
            emitLargeBombHemisphere(level, ground);
            emitExplosionBlast(level);

            // Explosionblastwork only starts this branch when its random
            // terrain probe succeeds and the following 50% roll passes. The
            // detached visual cannot query an unloaded impact chunk, so the
            // original probability and timing are retained independently.
            RandomSource schedule = RandomSource.create(
                    seed ^ MianbaoDetonationVisualPayload.SCHEDULE_SEED_XOR);
            delayedSmoke = schedule.nextBoolean();
            smokeStart = 320 + schedule.nextInt(61);
            smokeInterval = 18 + schedule.nextInt(5);
            smokeCount = 35 + schedule.nextInt(6);
        }

        private void emitLargeBombHemisphere(ClientLevel level, boolean ground) {
            ParticleOptions large = particle("explosionlarge").orElse(null);
            ParticleOptions small = particle("explosionsmall").orElse(null);
            for (int iy = 0; iy < 12; iy++) {
                for (int ix = -7; ix <= 7; ix++) {
                    for (int iz = -7; iz <= 7; iz++) {
                        double ellipsoid = (ix * ix) / 49.0
                                + (iy * iy) / 144.0 + (iz * iz) / 49.0;
                        if (ellipsoid > 1.0 || Math.sqrt(ix * ix + iz * iz) < 4.0) {
                            continue;
                        }
                        double originX = x + ix + nextIntInclusive(-1, 1);
                        double originY = y + iy + nextIntInclusive(-1, 1);
                        double originZ = z + iz + nextIntInclusive(-1, 1);
                        boolean useLarge = random.nextDouble() < 0.7;
                        ParticleOptions options = useLarge ? large : small;
                        if (ground) {
                            if (useLarge) {
                                addCountOne(level, options, originX, originY, originZ,
                                        0.0, 0.5, 0.0, 0.5);
                            } else {
                                addCountOne(level, options, originX, originY, originZ,
                                        0.0, 1.0, 0.0, 0.5);
                            }
                        } else if (useLarge) {
                            addCountOne(level, options, originX, originY, originZ,
                                    0.0, 1.0, 0.0, 0.5);
                        } else {
                            addCountOne(level, options, originX, originY, originZ,
                                    0.5, 0.5, 0.5, 0.25);
                        }
                    }
                }
            }
        }

        private void emitExplosionBlast(ClientLevel level) {
            ParticleOptions blast = particle("explosionblast").orElse(null);
            for (int index = 0; index < 72; index++) {
                double angle = index * 0.08727;
                double fromX = x + Math.cos(angle) * 5.0;
                double fromY = y + nextIntInclusive(1, 2);
                double fromZ = z + Math.sin(angle) * 5.0;
                addCountZero(level, blast, fromX, fromY + 5.0, fromZ,
                        Math.cos(angle) * 4.0, 0.0,
                        Math.sin(angle) * 4.0, 0.9);
                addCountZero(level, blast, fromX, fromY, fromZ,
                        Math.cos(angle) * 4.5, 0.0,
                        Math.sin(angle) * 4.5, 1.0);
            }
        }

        private void startLittleBomb(ClientLevel level) {
            // LittleBombexplode performs one BLOCK explosion followed by one
            // NONE explosion. Both make the vanilla sound; only the first
            // selects EXPLOSION_EMITTER.
            playMianbaoSound(level, "new_little_bomb_explode", 12.0F,
                    between(0.8F, 1.0F));
            playMianbaoSound(level, "explode_far_sound", 32.0F,
                    between(0.7F, 1.0F));
            vanillaExplosion(level, x, y, z, true, random.nextLong());
            vanillaExplosion(level, x, y, z, false, random.nextLong());

            ParticleOptions large = particle("explosionlarge").orElse(null);
            ParticleOptions small = particle("explosionsmall").orElse(null);
            for (int iy = 0; iy < 5; iy++) {
                for (int ix = -3; ix <= 3; ix++) {
                    for (int iz = -3; iz <= 3; iz++) {
                        double ellipsoid = (ix * ix) / 9.0
                                + (iy * iy) / 25.0 + (iz * iz) / 9.0;
                        if (ellipsoid > 1.0) continue;
                        double originX = x + ix + between(-1.0, 1.0);
                        double originY = y + iy + between(-1.0, 1.0);
                        double originZ = z + iz + between(-1.0, 1.0);
                        ParticleOptions options = random.nextDouble() < 0.7 ? large : small;
                        addCountOne(level, options, originX, originY, originZ,
                                0.0, 0.5, 0.0, 0.25);
                    }
                }
            }
        }

        private void startNuclear(ClientLevel level) {
            double scale = nuclearScale();
            ParticleOptions explosionOne = particle("nukeexplosion_1").orElse(null);
            ParticleOptions explosionThree = particle("nukeexplosion_3").orElse(null);
            ParticleOptions smokeOne = particle("nukesmoke_1").orElse(null);

            // Mianbao 2.5.0's current Nuke_dynamite particle commands. Every
            // command below has count=0. Client packet
            // handling multiplies command delta by speed before constructing
            // the particle; keeping that multiplication is essential to the
            // original mushroom-cloud proportions.
            for (int index = 0; index < 50; index++) {
                addCountZero(level, explosionOne,
                        x + between(-1.0, 1.0) * 22.0 * scale,
                        y - 5.0 * scale + between(-0.5, 0.8) * 22.0 * scale,
                        z + between(-1.0, 1.0) * 22.0 * scale,
                        0.0, 16.0 * scale, 0.0, 0.1);
                addCountZero(level, explosionThree,
                        x + between(-1.0, 1.0) * 18.0 * scale,
                        y - 5.0 * scale + between(-0.5, 0.7) * 17.0 * scale,
                        z + between(-1.0, 1.0) * 18.0 * scale,
                        0.0, 16.0 * scale, 0.0, 0.1);
            }
            for (int index = 0; index < 20; index++) {
                addCountZero(level, explosionThree,
                        x + between(-4.0, 4.0) * scale,
                        y + between(-2.0, 2.0) * scale,
                        z + between(-4.0, 4.0) * scale,
                        0.0, (index + between(-2.0, 2.0)) * scale, 0.0, 0.1);
                addCountZero(level, explosionThree,
                        x + between(-3.0, 3.0) * scale, y,
                        z + between(-3.0, 3.0) * scale,
                        0.0, index * scale, 0.0, 0.1);
            }
            for (int index = 0; index < 50; index++) {
                double t2 = index * 0.5;
                double t3 = index * 0.6;
                addCountZero(level, explosionOne,
                        x + between(-1.0, 1.0) * t3 * scale,
                        y + (20.0 - t2) * scale,
                        z + between(-1.0, 1.0) * t3 * scale,
                        0.0, -1.0 * scale, 0.0, 0.1);
            }

            for (int index = 0; index < 60; index++) {
                double angle = index * 0.0872;
                // Mianbao creates a fresh RandomSource for each axis, so the
                // original ring is slightly irregular rather than circular.
                double fromX = x + Math.cos(angle) * nextIntInclusive(40, 50) * scale;
                double fromZ = z + Math.sin(angle) * nextIntInclusive(40, 50) * scale;
                addCountZero(level, smokeOne,
                        fromX + nextIntInclusive(-5, 5) * scale,
                        y + (50.0 + nextIntInclusive(-5, 5)) * scale,
                        fromZ + nextIntInclusive(-5, 5) * scale,
                        Math.cos(angle) * 10.0 * scale,
                        between(-0.25, 0.25) * scale,
                        Math.sin(angle) * 10.0 * scale, 0.8);
                addCountZero(level, smokeOne,
                        fromX + nextIntInclusive(-5, 5) * scale,
                        y + (5.0 + nextIntInclusive(-5, 5)) * scale,
                        fromZ + nextIntInclusive(-5, 5) * scale,
                        Math.cos(angle) * 15.0 * scale,
                        between(-0.25, 0.25) * scale,
                        Math.sin(angle) * 15.0 * scale, 0.8);
            }
        }

        private boolean tick(ClientLevel level) {
            age++;
            if (isNuclearProfile(profile)) {
                if (initialEffects && closePlayerEffects && age == 20) {
                    playMianbaoSound(level, "nuke_explode", 50.0F,
                            between(0.7F, 1.0F));
                }
                if (activeNuclearWaveRadius == 0
                        && nextNuclearWaveRadius <= NUCLEAR_WAVES
                        && age >= nextNuclearWaveRadius * NUCLEAR_WAVE_INTERVAL) {
                    activeNuclearWaveRadius = nextNuclearWaveRadius++;
                    nuclearWaveSource = 0;
                    nuclearWaveSliceTicks = 0;
                }
                if (activeNuclearWaveRadius != 0) {
                    emitNuclearWaveSlice(level);
                }
                return nextNuclearWaveRadius > NUCLEAR_WAVES
                        && activeNuclearWaveRadius == 0;
            }

            if ((profile == MianbaoDetonationVisualPayload.BOMB
                    || profile == MianbaoDetonationVisualPayload.GROUND)
                    && delayedSmoke && smokeEmitted < smokeCount
                    && age >= smokeStart
                    && (age - smokeStart) % smokeInterval == 0) {
                emitScorchedSmoke(level);
                smokeEmitted++;
            }
            if (delayedSmoke) return smokeEmitted >= smokeCount;
            return age > 0;
        }

        private void emitNuclearWaveSlice(ClientLevel level) {
            ParticleOptions smoke = particle("nukesmoke_3").orElse(null);
            ParticleOptions denseSmoke = particle("nukesmoke_2").orElse(null);
            int remainingSources = 61 - nuclearWaveSource;
            int remainingTicks = Math.max(
                    1, NUCLEAR_WAVE_INTERVAL - nuclearWaveSliceTicks);
            int budget = (remainingSources + remainingTicks - 1) / remainingTicks;
            double scale = nuclearScale();
            double ringRadius = activeNuclearWaveRadius * 5.0 * scale;
            while (budget-- > 0 && nuclearWaveSource < 61) {
                int source = nuclearWaveSource++;
                if (source == 0) {
                    vanillaExplosion(level, x,
                            y + activeNuclearWaveRadius, z,
                            true, random.nextLong());
                    continue;
                }

                // Advance through adjacent sources so one client tick only
                // rebuilds a small neighboring arc instead of touching six
                // distant sectors around the entire circumference at once.
                int index = source - 1;
                double angle = index * Math.PI * 2.0 / 60.0;
                double sourceX = x + Math.cos(angle) * ringRadius;
                double sourceY = y + nextIntInclusive(5, 10) * scale;
                double sourceZ = z + Math.sin(angle) * ringRadius;
                addCountZero(level, smoke, sourceX, sourceY, sourceZ,
                        0.0, 5.0 * scale, 0.0, 0.1);
                // Native command: delta(3,2,3), speed .5, count 3.
                for (int particleIndex = 0; particleIndex < 3; particleIndex++) {
                    addCountOne(level, denseSmoke,
                            sourceX, sourceY, sourceZ,
                            3.0 * scale, 2.0 * scale, 3.0 * scale, 0.5);
                }
                vanillaExplosion(level, sourceX, sourceY, sourceZ,
                        true, random.nextLong());
            }
            nuclearWaveSliceTicks++;
            if (nuclearWaveSource >= 61) {
                activeNuclearWaveRadius = 0;
                nuclearWaveSource = 0;
                nuclearWaveSliceTicks = 0;
            }
        }

        private void emitScorchedSmoke(ClientLevel level) {
            ParticleOptions smoke = particle("scorchedstonesmokeparticle").orElse(null);
            addCountZero(level, smoke,
                    x + nextIntInclusive(-5, 0),
                    y + nextIntInclusive(-5, 0),
                    z + nextIntInclusive(-5, 5),
                    -3.0, 0.5, -3.0, 0.25);
            addCountZero(level, smoke,
                    x + nextIntInclusive(0, 5),
                    y + nextIntInclusive(-5, 0),
                    z + nextIntInclusive(-5, 5),
                    -3.0, 0.5, -3.0, 0.25);
        }

        private void addCountZero(ClientLevel level, ParticleOptions options,
                                  double px, double py, double pz,
                                  double deltaX, double deltaY, double deltaZ,
                                  double speed) {
            if (options == null) return;
            addDetonationParticle(level, options, px, py, pz,
                    deltaX * speed, deltaY * speed, deltaZ * speed);
        }

        private void addCountOne(ClientLevel level, ParticleOptions options,
                                 double px, double py, double pz,
                                 double deltaX, double deltaY, double deltaZ,
                                 double speed) {
            if (options == null) return;
            double particleX = px + random.nextGaussian() * deltaX;
            double particleY = py + random.nextGaussian() * deltaY;
            double particleZ = pz + random.nextGaussian() * deltaZ;
            addDetonationParticle(level, options,
                    particleX, particleY, particleZ,
                    random.nextGaussian() * speed,
                    random.nextGaussian() * speed,
                    random.nextGaussian() * speed);
        }

        private void addDetonationParticle(
                ClientLevel level, ParticleOptions options,
                double px, double py, double pz,
                double velocityX, double velocityY, double velocityZ
        ) {
            if (isNuclearProfile(profile)
                    && isWithinNativeParticleRange(px, py, pz)) {
                // Match the stable native Mianbao path. The particle engine
                // may apply its configured limiter instead of retaining every
                // long-lived nuclear particle as an always-visible object.
                level.addParticle(options, px, py, pz,
                        velocityX, velocityY, velocityZ);
            } else {
                level.addAlwaysVisibleParticle(options, true, px, py, pz,
                        velocityX, velocityY, velocityZ);
            }
        }

        private void vanillaExplosion(ClientLevel level,
                                      double px, double py, double pz,
                                      boolean emitter, long seed) {
            // One native nuclear timeline contains 30 * 61 shockwave sources.
            // A remote camera can sit next to the impact while the player's
            // body is thousands of blocks away; creating a sound for every
            // reconstructed source then churns through OpenAL channels and
            // makes unrelated game audio cut in and out. The server-provided
            // close-player flag is intentionally independent of camera range.
            if (!isNuclearProfile(profile)
                    || closePlayerEffects) {
                playAtImpact(SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.BLOCKS, 4.0F,
                        (1.0F + (level.random.nextFloat()
                                - level.random.nextFloat()) * 0.2F) * 0.7F,
                        px, py, pz);
            }
            if (isNuclearProfile(profile)
                    && isWithinNativeParticleRange(px, py, pz)) {
                level.addParticle(
                        emitter ? ParticleTypes.EXPLOSION_EMITTER
                                : ParticleTypes.EXPLOSION,
                        px, py, pz, 1.0, 0.0, 0.0);
                return;
            }
            if (emitter) {
                EXPLOSION_EMITTERS.add(new AlwaysVisibleExplosionEmitter(
                        px, py, pz, seed));
            } else {
                level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION, true,
                        px, py, pz, 1.0, 0.0, 0.0);
            }
        }

        private boolean isWithinNativeParticleRange(
                double px, double py, double pz
        ) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameRenderer.getMainCamera() == null) return false;
            return minecraft.gameRenderer.getMainCamera().getPosition()
                    .distanceToSqr(px, py, pz) <= 512.0 * 512.0;
        }

        private double nuclearScale() {
            return profile == MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR
                    ? 2.0 : 1.0;
        }

        private void playMianbaoSound(ClientLevel level, String path,
                                      float volume, float pitch) {
            BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.fromNamespaceAndPath(
                    "mianbaos_modernwarfare", path)).ifPresent(sound ->
                    playAtImpact(sound, SoundSource.NEUTRAL, volume, pitch, x, y, z));
        }

        private int nextIntInclusive(int min, int max) {
            return min + random.nextInt(max - min + 1);
        }

        private double between(double min, double max) {
            return min + random.nextDouble() * (max - min);
        }

        private float between(float min, float max) {
            return min + random.nextFloat() * (max - min);
        }
    }

    private static boolean isNuclearProfile(byte profile) {
        return profile == MianbaoDetonationVisualPayload.NUCLEAR
                || profile == MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR;
    }

    /** Always-visible equivalent of vanilla HugeExplosionSeedParticle. */
    private static final class AlwaysVisibleExplosionEmitter {
        private static final int LIFETIME = 8;
        private final double x;
        private final double y;
        private final double z;
        private final RandomSource random;
        private int age;

        private AlwaysVisibleExplosionEmitter(double x, double y, double z, long seed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.random = RandomSource.create(seed);
        }

        private boolean tick(ClientLevel level) {
            if (age >= LIFETIME) return true;
            for (int index = 0; index < 6; index++) {
                level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION, true,
                        x + (random.nextDouble() - random.nextDouble()) * 4.0,
                        y + (random.nextDouble() - random.nextDouble()) * 4.0,
                        z + (random.nextDouble() - random.nextDouble()) * 4.0,
                        (double) age / LIFETIME, 0.0, 0.0);
            }
            age++;
            return age >= LIFETIME;
        }
    }

    private static void playAtImpact(SoundEvent sound, SoundSource source,
                                     float volume, float pitch,
                                     double x, double y, double z) {
        Minecraft minecraft = Minecraft.getInstance();
        // Keep the source anchored to the authoritative missile impact even
        // when it is outside the client's normally loaded chunks. Linear
        // attenuation preserves Mianbao/vanilla distance, volume and stereo
        // direction instead of turning the explosion into a global sound.
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                sound.getLocation(), source, volume, pitch,
                SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.LINEAR,
                x, y, z, false));
    }

    private static Vec3 velocity(MissileRemoteStatePayload payload) {
        return new Vec3(payload.velocityX(), payload.velocityY(), payload.velocityZ());
    }

    private static void applyRemoteRotation(Entity proxy, MissileRemoteStatePayload payload) {
        MianbaoBallisticMissileCompat.setRemoteRenderRotation(proxy, new Quaternionf(
                payload.rotationX(), payload.rotationY(),
                payload.rotationZ(), payload.rotationW()));
    }

    /** Starts the proxy at the native mesh's current interpolated entity origin. */
    private static Vec3 nativeRenderedPosition(
            ClientLevel level, UUID missileId, Vec3 fallback
    ) {
        float partialTick = Mth.clamp(Minecraft.getInstance().getTimer()
                .getGameTimeDeltaPartialTick(true), 0.0F, 1.0F);
        for (Entity candidate : level.entitiesForRendering()) {
            if (!candidate.getUUID().equals(missileId)
                    || candidate.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)) continue;
            return new Vec3(
                    Mth.lerp(partialTick, candidate.xo, candidate.getX()),
                    Mth.lerp(partialTick, candidate.yo, candidate.getY()),
                    Mth.lerp(partialTick, candidate.zo, candidate.getZ()));
        }
        return fallback;
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
        if (MianbaoCountermeasureCompat.isCountermeasure(missile)) {
            return MianbaoCountermeasureCompat.LIFETIME_TICKS;
        }
        if (MianbaoRocketPodCompat.isRocket(missile)) {
            return MianbaoRocketPodCompat.LIFETIME_TICKS;
        }
        if (com.hooya.stabilizedturret.content.controller
                .MianbaoBallisticMissileCompat.isMissile(missile)) {
            return com.hooya.stabilizedturret.config.MissileSettings.lifetimeTicks(missile);
        }
        if (com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat.isMissile(missile)) {
            return com.hooya.stabilizedturret.config.MissileSettings.lifetimeTicks(missile);
        }
        MianbaoAircraftCompat.WeaponType aircraft = MianbaoAircraftCompat.typeOf(missile);
        if (aircraft != null) return com.hooya.stabilizedturret.config.MissileSettings.supported(missile)
                ? com.hooya.stabilizedturret.config.MissileSettings.lifetimeTicks(missile)
                : MianbaoAircraftCompat.lifetimeTicks(aircraft);
        if (MianbaoAirDefenseCompat.isMissile(missile)) {
            return MianbaoAirDefenseCompat.getLifetimeTicks(missile);
        }
        return com.hooya.stabilizedturret.config.MissileSettings.supported(missile)
                ? com.hooya.stabilizedturret.config.MissileSettings.lifetimeTicks(missile) : 20 * 20;
    }

    private static Optional<ParticleOptions> particle(String path) {
        Object type = BuiltInRegistries.PARTICLE_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path));
        return type instanceof ParticleOptions options ? Optional.of(options) : Optional.empty();
    }

    private static void playRocketExplosionSounds(
            MissileExplosionPayload payload, RandomSource random
    ) {
        BuiltInRegistries.SOUND_EVENT.getOptional(
                ResourceLocation.fromNamespaceAndPath(
                        "mianbaos_modernwarfare", payload.airDefenseExplosion()
                                ? "missile_explode" : "new_rocket_explode"))
                .ifPresent(sound -> playAtImpact(
                        sound, SoundSource.NEUTRAL, 12.0F,
                        Mth.randomBetween(random, 0.8F, 1.0F),
                        payload.x(), payload.y(), payload.z()));
        String farSound = payload.strength() >= 4.5F
                ? "explode_far_sound3" : "explode_far_sound";
        BuiltInRegistries.SOUND_EVENT.getOptional(
                ResourceLocation.fromNamespaceAndPath(
                        "mianbaos_modernwarfare", farSound))
                .ifPresent(sound -> playAtImpact(
                        sound, SoundSource.NEUTRAL, 32.0F,
                        Mth.randomBetween(random, 0.7F, 1.0F),
                        payload.x(), payload.y(), payload.z()));
    }

    /** A clustered, globally budgeted visual avoids one-frame salvo stalls. */
    private static final class RocketExplosionVisual {
        private final Vec3 position;
        private final long createdAt;
        private final RandomSource random;
        private final AircraftExplosionParticles aircraftParticles;
        private final AirDefenseExplosionParticles airDefenseParticles;
        private float strength;
        private int targetParticles;
        private int emittedParticles;

        private RocketExplosionVisual(
                Vec3 position, float strength, byte particleProfile,
                long createdAt, long seed
        ) {
            this.position = position;
            this.createdAt = createdAt;
            this.random = RandomSource.create(seed);
            this.strength = strength;
            this.aircraftParticles = particleProfile == MissileExplosionPayload.AIRCRAFT
                    ? AircraftExplosionParticles.forStrength(strength) : null;
            this.airDefenseParticles = particleProfile == MissileExplosionPayload.AIR_DEFENSE
                    ? new AirDefenseExplosionParticles(AirDefenseExplosionParticles.ADOPTED_SCALE) : null;
            this.targetParticles = airDefenseParticles != null ? AirDefenseExplosionParticles.PARTICLE_COUNT
                    : aircraftParticles != null ? AircraftExplosionParticles.PARTICLE_COUNT : particleCount(strength);
        }

        private boolean preservesNativeParticles() {
            return aircraftParticles != null || airDefenseParticles != null;
        }

        private void merge(float mergedStrength) {
            strength = Math.max(strength, mergedStrength);
            targetParticles = Math.min(24,
                    Math.max(targetParticles, particleCount(strength)) + 3);
        }

        private int emit(ClientLevel level, int limit) {
            ParticleOptions explosion = airDefenseParticles != null
                    ? particle(AirDefenseExplosionParticles.PARTICLE).orElse(null)
                    : particle("explosionlarge")
                    .or(() -> particle("explosionsmall")).orElse(null);
            if (explosion == null) {
                emittedParticles = targetParticles;
                return 0;
            }
            int count = Math.min(limit, targetParticles - emittedParticles);
            double radius = Math.max(1.5, strength * 0.75);
            for (int index = 0; index < count; index++) {
                if (airDefenseParticles != null) {
                    var sample = airDefenseParticles.sample(emittedParticles + index, random);
                    Vec3 point = position.add(sample.offset()), velocity = sample.velocity();
                    Particle spawned = Minecraft.getInstance().particleEngine.createParticle(
                            explosion, point.x, point.y, point.z, velocity.x, velocity.y, velocity.z);
                    if (spawned != null) spawned.scale(airDefenseParticles.scale());
                    continue;
                }
                if (aircraftParticles != null) {
                    Vec3 point = position.add(aircraftParticles.offset(random));
                    Vec3 velocity = aircraftParticles.velocity(random);
                    // Direct creation retains visibility beyond vanilla's particle
                    // distance while exposing the instance for per-particle scaling.
                    Particle spawned = Minecraft.getInstance().particleEngine.createParticle(
                            explosion, point.x, point.y, point.z,
                            velocity.x, velocity.y, velocity.z);
                    if (spawned != null) spawned.scale(aircraftParticles.scale());
                    continue;
                }
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radial = Math.sqrt(random.nextDouble()) * radius;
                double height = random.nextDouble() * radius * 1.7;
                VirtualRocketParticles.explosion(level,
                        position.x + Math.cos(angle) * radial,
                        position.y + height,
                        position.z + Math.sin(angle) * radial,
                        (random.nextDouble() - 0.5) * 0.25,
                        random.nextDouble() * 0.25,
                        (random.nextDouble() - 0.5) * 0.25);
            }
            emittedParticles += count;
            if (!preservesNativeParticles()) emittedParticles = targetParticles;
            return count;
        }

        private boolean complete() {
            return emittedParticles >= targetParticles;
        }

        private static int particleCount(float strength) {
            return Mth.clamp(Math.round(strength * 4.0F), 8, 24);
        }
    }

    private static final class RemoteVisual {
        private final Entity entity;
        private final boolean detachedFromClientChunks;
        private long lastUpdateTick;
        private long lastExtrapolatedTick;
        private Vec3 authoritativePosition;
        private Vec3 authoritativeVelocity;
        private long authoritativeServerTick;

        private RemoteVisual(Entity entity, long lastUpdateTick,
                             boolean detachedFromClientChunks,
                             MissileRemoteStatePayload payload) {
            this.entity = entity;
            this.detachedFromClientChunks = detachedFromClientChunks;
            this.lastUpdateTick = lastUpdateTick;
            this.lastExtrapolatedTick = lastUpdateTick;
            this.authoritativePosition = new Vec3(payload.x(), payload.y(), payload.z());
            this.authoritativeVelocity = velocity(payload);
            this.authoritativeServerTick = payload.serverGameTime();
        }

        private void acceptDetached(MissileRemoteStatePayload payload, long now) {
            authoritativePosition = new Vec3(payload.x(), payload.y(), payload.z());
            authoritativeVelocity = velocity(payload);
            authoritativeServerTick = payload.serverGameTime();
            entity.setDeltaMovement(authoritativeVelocity);
            if (!MianbaoCountermeasureCompat.isCountermeasure(entity)) applyRemoteRotation(entity, payload);
            lastUpdateTick = now;
        }

        private void extrapolate(long now) {
            if (entity.isRemoved() || lastExtrapolatedTick >= now
                    || now - lastUpdateTick > REMOTE_TIMEOUT_TICKS) {
                return;
            }
            if (detachedFromClientChunks) {
                interpolateDetached(now);
                return;
            }
            Vec3 movement = entity.getDeltaMovement();
            if (movement.lengthSqr() < 1.0e-12) {
                lastExtrapolatedTick = now;
                return;
            }
            float oldYaw = entity.getYRot();
            float oldPitch = entity.getXRot();
            float newYaw = yaw(movement);
            float newPitch = pitch(movement);
            entity.setOldPosAndRot();
            entity.absMoveTo(
                    entity.getX() + movement.x,
                    entity.getY() + movement.y,
                    entity.getZ() + movement.z,
                    newYaw, newPitch);
            entity.yRotO = newYaw - Mth.wrapDegrees(newYaw - oldYaw);
            entity.xRotO = newPitch - Mth.wrapDegrees(newPitch - oldPitch);
            if (MianbaoRocketPodCompat.isRocket(entity)) {
                // Remote packets already contain the native trail's cached
                // post-tick velocity. Only its constant downward step remains.
                entity.setDeltaMovement(movement.add(
                        0.0, -MianbaoRocketPodCompat.flightDrop(entity), 0.0));
            }
            lastExtrapolatedTick = now;
        }

        private void interpolateDetached(long now) {
            long predictionTicks = Mth.clamp(now - authoritativeServerTick, 0L, 3L);
            Vec3 desired = authoritativePosition.add(
                    authoritativeVelocity.scale(predictionTicks));
            if (MianbaoCountermeasureCompat.isCountermeasure(entity)) {
                desired = authoritativePosition;
                Vec3 predictedVelocity = authoritativeVelocity;
                for (int index = 0; index < predictionTicks; index++) {
                    desired = desired.add(predictedVelocity);
                    predictedVelocity = predictedVelocity.scale((double) 0.99F).add(0, -0.02, 0);
                }
            }
            Vec3 current = entity.position();
            Vec3 error = desired.subtract(current);
            double snapDistance = Math.max(128.0,
                    authoritativeVelocity.length() * 8.0);
            Vec3 next = error.lengthSqr() > snapDistance * snapDistance
                    ? desired : current.add(error.scale(0.5));
            float oldYaw = entity.getYRot();
            float oldPitch = entity.getXRot();
            float newYaw = yaw(authoritativeVelocity);
            float newPitch = pitch(authoritativeVelocity);
            entity.setOldPosAndRot();
            entity.absMoveTo(next.x, next.y, next.z, newYaw, newPitch);
            entity.yRotO = newYaw - Mth.wrapDegrees(newYaw - oldYaw);
            entity.xRotO = newPitch - Mth.wrapDegrees(newPitch - oldPitch);
            entity.setDeltaMovement(authoritativeVelocity);
            lastExtrapolatedTick = now;
        }
    }

    private MissileLongRangeEffects() {
    }
}
