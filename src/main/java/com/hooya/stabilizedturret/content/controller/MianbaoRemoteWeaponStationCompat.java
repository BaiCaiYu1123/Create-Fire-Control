package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.mianbao.MianbaoNativeGunfire;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Optional bridge to Mianbao Arsenal's public remote-weapon-station API. */
public final class MianbaoRemoteWeaponStationCompat {
    /** War Thunder-like vehicle machine-gun traverse rates. */
    public static final float MAX_YAW_DEGREES_PER_SECOND = 60.0F;
    public static final float MAX_PITCH_DEGREES_PER_SECOND = 45.0F;
    public static final float MIN_PITCH_DEGREES = -20.0F;
    public static final float MAX_PITCH_DEGREES = 65.0F;
    public static final String REDUCED_AUTOCANNON_TAG =
            "CreateFireControlReducedRemoteWeaponStationShell";

    private static final String MOD_NAMESPACE = "mianbaos_modernwarfare";
    private static final String API_CLASS = "net.mcreator.myfirstmod.api.RemoteWeaponStationApi";
    private static final String SESSION_MANAGER_CLASS =
            "net.mcreator.myfirstmod.remote.RemoteWeaponStationSessionManager";
    private static final String HEAVY_CANNON_MISSILE_GUIDANCE_CLASS =
            "net.mcreator.myfirstmod.remote.HeavyCannonAntiTankGuidanceService";
    private static final String HEAVY_CANNON_MISSILE_TAG =
            "MianbaoHeavyStationGuidedMissile";
    private static final ResourceLocation HEAVY_CANNON_STATION =
            id("heavycannonweaponstation");
    private static final ResourceLocation AUTOCANNON_EXPLOSION_PARTICLE = id("explosionsmall_2");
    private static final ResourceLocation AUTOCANNON_EXPLOSION_SOUND = id("new_rocket_explode");
    private static final ResourceLocation AUTOCANNON_EXPLOSION_FAR_SOUND = id("explode_far_sound");
    static final int MAX_CONTINUOUS_FIRE_TICKS = 15 * 20;
    static final int OVERHEAT_COOLDOWN_TICKS = 5 * 20;
    private static final float EXPLOSION_POWER = 1.0F; // Native autocannon: 1.5.
    private static final float EXPLOSION_DAMAGE = 12.0F; // Native autocannon: 20.
    private static final double EXPLOSION_DAMAGE_RADIUS = 2.5; // Native autocannon: 3.5.
    private static final Set<ResourceLocation> STATION_IDS = Set.of(
            id("weapon_station_type_1_green"),
            id("weapon_station_type_1_sand"),
            id("weapon_station_type_2_green"),
            id("weapon_station_type_2_sand"),
            ResourceLocation.fromNamespaceAndPath(MOD_NAMESPACE, "weaponcontrolsystem_1"),
            ResourceLocation.fromNamespaceAndPath(MOD_NAMESPACE, "weaponcontrolsystem_2"),
            // Mianbao 2.5 adds one combined heavy-cannon station and the
            // low/high tripod variants of its heavy machine gun.  All three
            // share the same RemoteWeaponStationController contract as the
            // original weapon-control-system blocks.
            HEAVY_CANNON_STATION,
            ResourceLocation.fromNamespaceAndPath(MOD_NAMESPACE, "heavymachinegunstandlow"),
            ResourceLocation.fromNamespaceAndPath(MOD_NAMESPACE, "heavymachinegunstandhigh")
            // The standalone `heavymachinegun` is intentionally absent: it is
            // a gun block without a RemoteWeaponStationController block entity.
    );

    private static volatile boolean apiLookupAttempted;
    private static volatile @Nullable Method findControllerMethod;
    private static volatile boolean manualLookupAttempted;
    private static volatile @Nullable Method manualControlMethod;
    private static volatile boolean sessionManualLookupAttempted;
    private static volatile @Nullable Method sessionManualControlMethod;
    private static volatile boolean sessionMapLookupAttempted;
    private static volatile @Nullable Field sessionMapField;
    private static volatile @Nullable Field sessionBindingField;
    private static volatile @Nullable Method bindingDimensionMethod;
    private static volatile @Nullable Method bindingPosMethod;
    private static volatile @Nullable Method bindingSubLevelMethod;
    private static volatile boolean heavyMissileLookupAttempted;
    private static volatile @Nullable Method heavyMissileLaunchMethod;
    private static volatile @Nullable Field heavyMissileGuidedMapField;
    private static volatile boolean manualControlWarningLogged;
    private static final Map<Level, Map<BindingRef, Long>> LAST_AIM_TICKS =
            new WeakHashMap<>();
    private static final ThreadLocal<PitchEnvelope> ACTIVE_FIRE_CONTROL_PITCH =
            new ThreadLocal<>();

    /**
     * Device identity is registry-based so binding does not depend on optional
     * API class-loading order or on the concrete server Level implementation.
     */
    public static boolean isStation(Level level, BlockPos pos) {
        return level != null && pos != null && STATION_IDS.contains(
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    public static boolean isHeavyCannonStation(Level level, BlockPos pos) {
        return level != null && pos != null && HEAVY_CANNON_STATION.equals(
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    /** Native Mianbao handheld control has priority over fire-control head aim. */
    public static boolean isManuallyControlled(Level level, BindingRef ref) {
        if (level == null || ref == null) return false;

        // Manual ownership must not depend on BindingRef's remembered block-entity
        // Java identity. Sable can rebuild that instance while both bindings still
        // refer to the same logical station. Ask through every useful Level view;
        // the native session is recorded against whichever view handled the click.
        for (Level candidate : manualControlLevels(level, ref)) {
            if (invokeManualControl(manualControlMethod(candidate.getClass()),
                    candidate, ref.pos())) {
                return true;
            }
            if (invokeManualControl(sessionManualControlMethod(candidate.getClass()),
                    candidate, ref.pos())) {
                return true;
            }
        }

        // Mianbao's public predicate currently derives the Sable plot from the
        // supplied Level. If the controller and player entered through different
        // Level views, that derivation can return false even though a live native
        // session owns this exact dimension/plot/position. Inspecting the native
        // session identities is a read-only compatibility fallback. Sessions are
        // removed on Shift exit, so fire control resumes automatically next tick.
        return isPresentInNativeSessions(level, ref);
    }

    public static @Nullable Vec3 getMuzzlePosition(Level level, BindingRef ref) {
        Object station = resolve(level, ref);
        Vec3 localPosition = invokeVec3(station, "getMuzzlePosition");
        return localPosition == null ? null : toWorldPosition(level, ref, localPosition);
    }

    public static @Nullable Vec3 getMuzzleDirection(Level level, BindingRef ref) {
        Object station = resolve(level, ref);
        Vec3 localDirection = invokeVec3(station, "getMuzzleDirection");
        Vec3 direction = localDirection == null
                ? null : toWorldDirection(level, ref, localDirection);
        return direction == null || direction.lengthSqr() < 1.0e-8
                ? null : direction.normalize();
    }

    /** Exact native missile-rail position, projected into the root world. */
    public static @Nullable Vec3 getAntiTankMissileLaunchPosition(
            Level level, BindingRef ref
    ) {
        Object station = resolveHeavyCannon(level, ref);
        Vec3 localPosition = invokeVec3(station, "getAntiTankMissileLaunchPosition");
        return localPosition == null ? null : toWorldPosition(level, ref, localPosition);
    }

    public static boolean isAntiTankMissileLoaded(Level level, BindingRef ref) {
        Object station = resolveHeavyCannon(level, ref);
        return Boolean.TRUE.equals(invokeBoolean(station, "isAntiTankMissileLoaded"));
    }

    /** Identifies the missile before EntityJoinLevelEvent is dispatched. */
    public static boolean isHeavyCannonAntiTankMissile(@Nullable Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(HEAVY_CANNON_MISSILE_TAG);
    }

    /**
     * Uses Mianbao's native launch implementation, then removes the new missile
     * from Mianbao's station guidance map so the fire-control computer becomes
     * its sole guidance owner. The returned entity has already been spawned.
     */
    public static @Nullable Entity fireAntiTankMissile(Level level, BindingRef ref) {
        Object station = resolveHeavyCannon(level, ref);
        if (station == null || !Boolean.TRUE.equals(
                invokeBoolean(station, "isAntiTankMissileLoaded"))) return null;

        Method launchMethod = heavyMissileLaunchMethod(station.getClass());
        Map<Entity, ?> nativeGuided = heavyMissileGuidedMap();
        if (launchMethod == null || nativeGuided == null) return null;

        boolean selectedByFireControl = false;
        if (!Boolean.TRUE.equals(invokeBoolean(station, "isAntiTankMissileSelected"))) {
            if (!Boolean.TRUE.equals(invokeBoolean(station, "cycleWeapon",
                    new Class<?>[]{int.class}, 1))) return null;
            selectedByFireControl = true;
        }

        Set<Entity> before = Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (nativeGuided) {
            before.addAll(nativeGuided.keySet());
        }

        boolean fired = false;
        try (FireControlMissileCommandGuard.Scope ignored =
                     FireControlMissileCommandGuard.enter()) {
            Object result = launchMethod.invoke(null, station);
            fired = result instanceof Boolean accepted && accepted;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The optional dependency changed underneath us; fail this pulse safely.
        }
        if (!fired) {
            if (selectedByFireControl) {
                invokeBoolean(station, "cycleWeapon", new Class<?>[]{int.class}, 1);
            }
            return null;
        }

        Entity launched = null;
        synchronized (nativeGuided) {
            for (Entity candidate : Set.copyOf(nativeGuided.keySet())) {
                if (!before.contains(candidate) && isHeavyCannonAntiTankMissile(candidate)) {
                    launched = candidate;
                    nativeGuided.remove(candidate);
                    break;
                }
            }
        }
        return launched;
    }

    /** Uses the exact optic anchor exposed to Mianbao's own station remote. */
    public static @Nullable Vec3 getCameraPosition(Level level, BindingRef ref) {
        Object station = resolve(level, ref);
        Vec3 localPosition = invokeVec3(station, "getCameraPosition");
        return localPosition == null ? null : toWorldPosition(level, ref, localPosition);
    }

    /**
     * Slews a bound station toward one authoritative world-space aim point.
     * The station API applies angles instantly, so rate limiting lives here.
     */
    public static boolean pointMuzzleAt(Level level, BindingRef ref, Vec3 worldTarget) {
        return pointMuzzleAt(level, ref, worldTarget,
                MAX_YAW_DEGREES_PER_SECOND, MAX_PITCH_DEGREES_PER_SECOND,
                -MIN_PITCH_DEGREES, MAX_PITCH_DEGREES);
    }

    /**
     * Fire-control-owned slew. The controller's configured rates and elevation
     * envelope replace Mianbao's native station limits for this write only.
     */
    public static boolean pointMuzzleAt(
            Level level,
            BindingRef ref,
            Vec3 worldTarget,
            double yawDegreesPerSecond,
            double pitchDegreesPerSecond,
            double depressionDegrees,
            double elevationDegrees
    ) {
        if (!finite(worldTarget)) return false;
        Object station = resolve(level, ref);
        if (station == null) return false;
        try {
            Vec3 stationTarget = toStationPosition(level, ref, worldTarget);
            Vec3 muzzle = invokeVec3(station, "getMuzzlePosition");
            Direction baseFacing = invokeDirection(station, "getBaseFacing");
            Float currentYaw = invokeFloat(station, "getYawDegrees");
            Float currentPitch = invokeFloat(station, "getPitchDegrees");
            if (!finite(stationTarget) || muzzle == null || baseFacing == null
                    || currentYaw == null || currentPitch == null) return false;

            Vec3 requested = stationTarget.subtract(muzzle);
            if (!finite(requested) || requested.lengthSqr() < 1.0e-12) return false;
            requested = requested.normalize();
            float desiredPitch = (float) Math.toDegrees(Math.asin(
                    Mth.clamp(requested.y, -1.0, 1.0)));
            desiredPitch = clampPitch(desiredPitch,
                    depressionDegrees, elevationDegrees);
            float worldYaw = (float) Math.toDegrees(Math.atan2(requested.x, -requested.z));
            float desiredYaw = Mth.wrapDegrees(worldYaw - baseYawDegrees(baseFacing));

            long elapsedTicks = reserveAimTick(level, ref);
            if (elapsedTicks == 0L) return true;
            float nextYaw = stepWrappedDegrees(currentYaw, desiredYaw,
                    (float) (Math.max(0.0, yawDegreesPerSecond)
                            * elapsedTicks / 20.0));
            float nextPitch = stepDegrees(currentPitch, desiredPitch,
                    (float) (Math.max(0.0, pitchDegreesPerSecond)
                            * elapsedTicks / 20.0));
            nextPitch = clampPitch(nextPitch, depressionDegrees, elevationDegrees);
            Object result;
            ACTIVE_FIRE_CONTROL_PITCH.set(new PitchEnvelope(
                    (float) -Math.max(0.0, depressionDegrees),
                    (float) Math.max(0.0, elevationDegrees)));
            try {
                result = station.getClass()
                        .getMethod("setRotationDegrees", float.class, float.class)
                        .invoke(station, nextYaw, nextPitch);
            } finally {
                ACTIVE_FIRE_CONTROL_PITCH.remove();
            }
            return !(result instanceof Boolean accepted) || accepted;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The dependency is optional; an incompatible API disables only this integration.
            return false;
        }
    }

    /** Uses native redstone gunfire, including native ammunition, damage and cadence. */
    public static boolean tickPoweredFire(Level level, BindingRef ref) {
        return tickFire(level, ref, false, true);
    }

    /** Direct-control trigger is ORed with the station's normal redstone input. */
    public static boolean tickDirectFire(
            Level level, BindingRef ref, boolean directTrigger
    ) {
        return tickFire(level, ref, directTrigger, true);
    }

    /**
     * Feed the machine-gun key into native gunfire, not a parallel compatibility projectile.
     * Native handheld control has priority; native redstone remains available.
     */
    public static boolean tickFireControl(
            Level level, BindingRef ref, boolean fireControlTrigger
    ) {
        return tickFire(level, ref, fireControlTrigger
                && !isManuallyControlled(level, ref), true);
    }

    private static boolean tickFire(
            Level level,
            BindingRef ref,
            boolean directTrigger,
            boolean includeNativeRedstone
    ) {
        if (level == null || level.isClientSide || ref == null) return false;
        Object station = resolve(level, ref);
        return station != null && MianbaoNativeGunfire.tickStation(
                station, directTrigger, includeNativeRedstone);
    }

    /** Legacy saved rounds only. New firing exclusively uses native Mianbao ammunition. */
    public static boolean detonateReducedAutocannon(
            LevelAccessor world, double x, double y, double z, Entity source
    ) {
        if (source == null || source.level().isClientSide()
                || !source.getPersistentData().getBoolean(REDUCED_AUTOCANNON_TAG)) {
            return false;
        }
        if (!(world instanceof ServerLevel level)) return false;
        Vec3 impact = new Vec3(x, y, z);
        level.explode(null, impact.x, impact.y, impact.z,
                EXPLOSION_POWER, Level.ExplosionInteraction.BLOCK);
        play(level, AUTOCANNON_EXPLOSION_SOUND, impact, 0.5F, 0.8F, 1.0F);
        play(level, AUTOCANNON_EXPLOSION_FAR_SOUND, impact, 0.5F, 0.7F, 1.0F);
        sendMianbaoParticle(level, AUTOCANNON_EXPLOSION_PARTICLE, impact,
                5, 0.75, 0.75, 0.75, 0.12);

        DamageSource damage = new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageTypes.EXPLOSION));
        for (Entity nearby : level.getEntitiesOfClass(Entity.class,
                new AABB(impact, impact).inflate(EXPLOSION_DAMAGE_RADIUS),
                candidate -> candidate != source)) {
            nearby.hurt(damage, EXPLOSION_DAMAGE);
        }
        source.discard();
        return true;
    }

    static float stepWrappedDegrees(float current, float target, float maximumStep) {
        return Mth.wrapDegrees(current + Mth.clamp(
                Mth.wrapDegrees(target - current), -maximumStep, maximumStep));
    }

    static float stepDegrees(float current, float target, float maximumStep) {
        return current + Mth.clamp(target - current, -maximumStep, maximumStep);
    }

    public static float clampPitch(float pitchDegrees) {
        return Mth.clamp(pitchDegrees, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES);
    }

    static float clampPitch(
            float pitchDegrees, double depressionDegrees, double elevationDegrees
    ) {
        return Mth.clamp(pitchDegrees,
                (float) -Math.max(0.0, depressionDegrees),
                (float) Math.max(0.0, elevationDegrees));
    }

    /** Called by the optional mixin at Mianbao's internal hard-coded clamp. */
    public static float clampPitchForActiveFireControl(
            float pitchDegrees, float nativeMinimum, float nativeMaximum
    ) {
        PitchEnvelope envelope = ACTIVE_FIRE_CONTROL_PITCH.get();
        return envelope == null
                ? Mth.clamp(pitchDegrees, nativeMinimum, nativeMaximum)
                : Mth.clamp(pitchDegrees, envelope.minimum(), envelope.maximum());
    }

    public static boolean isFireControlWriteActive() {
        return ACTIVE_FIRE_CONTROL_PITCH.get() != null;
    }

    /** World-space location used by the invisible direct-control seat. */
    public static @Nullable Vec3 getControlSeatPosition(Level level, BindingRef ref) {
        Object station = resolve(level, ref);
        Direction facing = invokeDirection(station, "getBaseFacing");
        if (facing == null) return null;
        Vec3 local = Vec3.atCenterOf(ref.pos())
                .subtract(facing.getStepX() * 0.9, 0.35,
                        facing.getStepZ() * 0.9);
        return toWorldPosition(level, ref, local);
    }

    private static @Nullable Object resolve(Level level, BindingRef ref) {
        if (level == null || level.isClientSide || ref == null
                || !ref.isSameTarget(level) || !isStation(level, ref.pos())) return null;
        return findController(level, ref.pos());
    }

    private static @Nullable Object resolveHeavyCannon(Level level, BindingRef ref) {
        if (level == null || ref == null || !isHeavyCannonStation(level, ref.pos())) {
            return null;
        }
        return resolve(level, ref);
    }

    private static @Nullable Method heavyMissileLaunchMethod(Class<?> stationClass) {
        ensureHeavyMissileReflection(stationClass);
        return heavyMissileLaunchMethod;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<Entity, ?> heavyMissileGuidedMap() {
        Field field = heavyMissileGuidedMapField;
        if (field == null) return null;
        try {
            Object value = field.get(null);
            return value instanceof Map<?, ?> map ? (Map<Entity, ?>) map : null;
        } catch (IllegalAccessException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static synchronized void ensureHeavyMissileReflection(Class<?> stationClass) {
        if (heavyMissileLookupAttempted) return;
        heavyMissileLookupAttempted = true;
        try {
            Class<?> guidance = Class.forName(HEAVY_CANNON_MISSILE_GUIDANCE_CLASS);
            heavyMissileLaunchMethod = guidance.getMethod("launch", stationClass);
            Field guided = guidance.getDeclaredField("GUIDED");
            guided.setAccessible(true);
            heavyMissileGuidedMapField = guided;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            heavyMissileLaunchMethod = null;
            heavyMissileGuidedMapField = null;
        }
    }

    private static @Nullable Vec3 invokeVec3(@Nullable Object station, String methodName) {
        if (station == null) return null;
        try {
            Object result = station.getClass().getMethod(methodName).invoke(station);
            return result instanceof Vec3 value && finite(value) ? value : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Float invokeFloat(@Nullable Object station, String methodName) {
        if (station == null) return null;
        try {
            Object result = station.getClass().getMethod(methodName).invoke(station);
            return result instanceof Number value && Float.isFinite(value.floatValue())
                    ? value.floatValue() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Boolean invokeBoolean(
            @Nullable Object station, String methodName
    ) {
        return invokeBoolean(station, methodName, new Class<?>[0]);
    }

    private static @Nullable Boolean invokeBoolean(
            @Nullable Object station,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        if (station == null) return null;
        try {
            Object result = station.getClass().getMethod(methodName, parameterTypes)
                    .invoke(station, arguments);
            return result instanceof Boolean value ? value : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Direction invokeDirection(
            @Nullable Object station, String methodName
    ) {
        if (station == null) return null;
        try {
            Object result = station.getClass().getMethod(methodName).invoke(station);
            return result instanceof Direction direction ? direction : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static long reserveAimTick(Level level, BindingRef ref) {
        synchronized (LAST_AIM_TICKS) {
            Map<BindingRef, Long> ticks = LAST_AIM_TICKS.computeIfAbsent(
                    level, ignored -> new HashMap<>());
            long now = level.getGameTime();
            Long previous = ticks.put(ref, now);
            if (previous != null && previous == now) return 0L;
            if (previous == null || previous > now) return 1L;
            return Mth.clamp(now - previous, 1L, 5L);
        }
    }

    /** Legacy thermal regression model; native gunfire owns the live thermal state. */
    static boolean updateThermalState(ThermalState state, long now, boolean powered) {
        if (!powered) {
            state.lastUpdateTick = now;
            state.lastPoweredTick = Long.MIN_VALUE;
            state.continuousTicks = 0;
            state.allowedThisTick = false;
            return false;
        }
        if (state.lastUpdateTick == now) return state.allowedThisTick;
        state.lastUpdateTick = now;

        if (now < state.overheatedUntil) {
            state.lastPoweredTick = now;
            state.allowedThisTick = false;
            return false;
        }
        if (state.overheatedUntil != Long.MIN_VALUE) {
            state.overheatedUntil = Long.MIN_VALUE;
            state.lastPoweredTick = Long.MIN_VALUE;
            state.continuousTicks = 0;
        }
        if (state.lastPoweredTick != now - 1L) state.continuousTicks = 0;
        state.lastPoweredTick = now;
        state.continuousTicks++;
        state.allowedThisTick = true;
        if (state.continuousTicks >= MAX_CONTINUOUS_FIRE_TICKS) {
            // The threshold tick completes the full 15-second burst. Blocking
            // starts on the following tick and lasts exactly five seconds.
            state.overheatedUntil = now + OVERHEAT_COOLDOWN_TICKS + 1L;
            state.continuousTicks = 0;
        }
        return true;
    }

    static final class ThermalState {
        private long lastUpdateTick = Long.MIN_VALUE;
        private long lastPoweredTick = Long.MIN_VALUE;
        private long overheatedUntil = Long.MIN_VALUE;
        private int continuousTicks;
        private boolean allowedThisTick;
    }

    private record PitchEnvelope(float minimum, float maximum) {
    }

    private static float baseYawDegrees(Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }

    private static void play(
            ServerLevel level, ResourceLocation soundId, Vec3 position,
            float volume, float minimumPitch, float maximumPitch
    ) {
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(soundId);
        if (sound == null) return;
        level.playSound(null, position.x, position.y, position.z, sound,
                SoundSource.NEUTRAL, volume,
                Mth.nextFloat(level.random, minimumPitch, maximumPitch));
    }

    private static void sendMianbaoParticle(
            ServerLevel level, ResourceLocation particleId, Vec3 position,
            int count, double spreadX, double spreadY, double spreadZ, double speed
    ) {
        if (BuiltInRegistries.PARTICLE_TYPE.get(particleId)
                instanceof SimpleParticleType particle) {
            level.sendParticles(particle, position.x, position.y, position.z,
                    count, spreadX, spreadY, spreadZ, speed);
        }
    }

    /** Mianbao reports coordinates in the block entity's logical Level. */
    private static Vec3 toWorldPosition(Level level, BindingRef ref, Vec3 localPosition) {
        SubLevel containing = containing(level, ref);
        if (containing == null) return localPosition;
        Vector3d world = containing.logicalPose().transformPosition(
                new Vector3d(localPosition.x, localPosition.y, localPosition.z));
        return new Vec3(world.x, world.y, world.z);
    }

    private static Vec3 toStationPosition(Level level, BindingRef ref, Vec3 worldPosition) {
        SubLevel containing = containing(level, ref);
        if (containing == null) return worldPosition;
        Vector3d local = containing.logicalPose().transformPositionInverse(
                new Vector3d(worldPosition.x, worldPosition.y, worldPosition.z));
        return new Vec3(local.x, local.y, local.z);
    }

    private static Vec3 toWorldDirection(Level level, BindingRef ref, Vec3 localDirection) {
        SubLevel containing = containing(level, ref);
        if (containing == null) return localDirection;
        Vector3d world = containing.logicalPose().transformNormal(
                new Vector3d(localDirection.x, localDirection.y, localDirection.z));
        return new Vec3(world.x, world.y, world.z);
    }

    private static @Nullable SubLevel containing(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        try {
            SubLevel containing = Sable.HELPER.getContaining(level, ref.pos());
            return containing == null || containing.isRemoved() ? null : containing;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Object findController(BlockGetter level, BlockPos pos) {
        Method find = findControllerMethod(level.getClass());
        if (find == null) return null;
        try {
            Object result = find.invoke(null, level, pos);
            return result instanceof Optional<?> optional ? optional.orElse(null) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Method findControllerMethod(Class<?> levelClass) {
        if (apiLookupAttempted) return findControllerMethod;
        synchronized (MianbaoRemoteWeaponStationCompat.class) {
            if (apiLookupAttempted) return findControllerMethod;
            try {
                Class<?> api = Class.forName(API_CLASS);
                for (Method method : api.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("findController")
                            && Modifier.isStatic(method.getModifiers())
                            && parameters.length == 2
                            && parameters[0].isAssignableFrom(levelClass)
                            && parameters[1] == BlockPos.class) {
                        findControllerMethod = method;
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                findControllerMethod = null;
            }
            apiLookupAttempted = true;
            return findControllerMethod;
        }
    }

    private static @Nullable Method manualControlMethod(Class<?> levelClass) {
        if (manualLookupAttempted) return manualControlMethod;
        synchronized (MianbaoRemoteWeaponStationCompat.class) {
            if (manualLookupAttempted) return manualControlMethod;
            try {
                Class<?> api = Class.forName(API_CLASS);
                for (Method method : api.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("isManuallyControlled")
                            && Modifier.isStatic(method.getModifiers())
                            && parameters.length == 2
                            && parameters[0].isAssignableFrom(levelClass)
                            && parameters[1] == BlockPos.class) {
                        manualControlMethod = method;
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                manualControlMethod = null;
            }
            manualLookupAttempted = true;
            return manualControlMethod;
        }
    }

    private static Set<Level> manualControlLevels(Level level, BindingRef ref) {
        Set<Level> candidates = new LinkedHashSet<>();
        candidates.add(level);
        try {
            SubLevel containing = containing(level, ref);
            if (containing != null && containing.getLevel() != null) {
                candidates.add(containing.getLevel());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The root server Level below remains a valid second probe.
        }
        if (level.getServer() != null) {
            ServerLevel root = level.getServer().getLevel(level.dimension());
            if (root != null) candidates.add(root);
        }
        return candidates;
    }

    private static boolean invokeManualControl(
            @Nullable Method method, Level level, BlockPos pos
    ) {
        if (method == null) return false;
        try {
            Object result = method.invoke(null, level, pos);
            return result instanceof Boolean controlled && controlled;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warnManualControlFailure("调用人工接管检测失败", failure);
            return false;
        }
    }

    private static @Nullable Method sessionManualControlMethod(Class<?> levelClass) {
        if (sessionManualLookupAttempted) return sessionManualControlMethod;
        synchronized (MianbaoRemoteWeaponStationCompat.class) {
            if (sessionManualLookupAttempted) return sessionManualControlMethod;
            try {
                Class<?> manager = Class.forName(SESSION_MANAGER_CLASS);
                for (Method method : manager.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("isManuallyControlled")
                            && Modifier.isStatic(method.getModifiers())
                            && parameters.length == 2
                            && parameters[0].isAssignableFrom(levelClass)
                            && parameters[1] == BlockPos.class) {
                        sessionManualControlMethod = method;
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                warnManualControlFailure("找不到人工接管会话接口", failure);
                sessionManualControlMethod = null;
            }
            sessionManualLookupAttempted = true;
            return sessionManualControlMethod;
        }
    }

    private static boolean isPresentInNativeSessions(Level level, BindingRef ref) {
        Field sessionsField = nativeSessionMapField();
        if (sessionsField == null) return false;
        try {
            Object sessionsValue = sessionsField.get(null);
            if (!(sessionsValue instanceof Map<?, ?> sessions) || sessions.isEmpty()) {
                return false;
            }
            for (Object session : sessions.values()) {
                Object binding = nativeSessionBinding(session);
                if (binding != null && nativeBindingMatches(level, ref, binding)) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warnManualControlFailure("读取人工接管会话失败", failure);
        }
        return false;
    }

    private static @Nullable Field nativeSessionMapField() {
        if (sessionMapLookupAttempted) return sessionMapField;
        synchronized (MianbaoRemoteWeaponStationCompat.class) {
            if (sessionMapLookupAttempted) return sessionMapField;
            try {
                Class<?> manager = Class.forName(SESSION_MANAGER_CLASS);
                Field field = manager.getDeclaredField("SESSIONS");
                if (!Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    throw new IllegalAccessException("SESSIONS is not accessible");
                }
                sessionMapField = field;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                warnManualControlFailure("找不到人工接管会话表", failure);
                sessionMapField = null;
            }
            sessionMapLookupAttempted = true;
            return sessionMapField;
        }
    }

    private static @Nullable Object nativeSessionBinding(@Nullable Object session)
            throws ReflectiveOperationException {
        if (session == null) return null;
        Field field = sessionBindingField;
        if (field == null || field.getDeclaringClass() != session.getClass()) {
            synchronized (MianbaoRemoteWeaponStationCompat.class) {
                field = sessionBindingField;
                if (field == null || field.getDeclaringClass() != session.getClass()) {
                    field = session.getClass().getDeclaredField("binding");
                    if (!field.trySetAccessible()) {
                        throw new IllegalAccessException("session binding is not accessible");
                    }
                    sessionBindingField = field;
                }
            }
        }
        return field.get(session);
    }

    private static boolean nativeBindingMatches(
            Level level, BindingRef ref, Object binding
    ) throws ReflectiveOperationException {
        Class<?> bindingClass = binding.getClass();
        Method dimension = bindingDimensionMethod;
        Method pos = bindingPosMethod;
        Method subLevel = bindingSubLevelMethod;
        if (dimension == null || pos == null || subLevel == null
                || dimension.getDeclaringClass() != bindingClass) {
            synchronized (MianbaoRemoteWeaponStationCompat.class) {
                dimension = bindingClass.getMethod("dimension");
                pos = bindingClass.getMethod("pos");
                subLevel = bindingClass.getMethod("subLevelId");
                bindingDimensionMethod = dimension;
                bindingPosMethod = pos;
                bindingSubLevelMethod = subLevel;
            }
        }
        Object actualDimension = dimension.invoke(binding);
        Object actualPos = pos.invoke(binding);
        Object actualSubLevel = subLevel.invoke(binding);
        return sameManualBinding(
                level.dimension().location(), ref.pos(), ref.subLevelId(),
                actualDimension instanceof ResourceLocation value ? value : null,
                actualPos instanceof BlockPos value ? value : null,
                actualSubLevel instanceof UUID value ? value : null);
    }

    static boolean sameManualBinding(
            @Nullable ResourceLocation expectedDimension,
            @Nullable BlockPos expectedPos,
            @Nullable UUID expectedSubLevel,
            @Nullable ResourceLocation actualDimension,
            @Nullable BlockPos actualPos,
            @Nullable UUID actualSubLevel
    ) {
        return Objects.equals(expectedDimension, actualDimension)
                && Objects.equals(expectedPos, actualPos)
                && Objects.equals(expectedSubLevel, actualSubLevel);
    }

    private static void warnManualControlFailure(String message, Throwable failure) {
        if (manualControlWarningLogged) return;
        synchronized (MianbaoRemoteWeaponStationCompat.class) {
            if (manualControlWarningLogged) return;
            manualControlWarningLogged = true;
            Throwable cause = failure instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : failure;
            StabilizedTurret.LOGGER.warn(
                    "{}；火控将继续尝试兼容回退。人工控制检测异常：{}",
                    message, cause.toString());
        }
    }

    private static boolean finite(@Nullable Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_NAMESPACE, path);
    }

    private MianbaoRemoteWeaponStationCompat() {
    }
}
