package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Optional, registry-only integration for Mianbao's aircraft rocket pods. */
public final class MianbaoRocketPodCompat {
    public static final String ADOPTED_TAG = "CreateFireControlRocket";
    public static final int LIFETIME_TICKS = MianbaoRocketFlight.LIFETIME_TICKS;
    public static final int SAFETY_TICKS = 10;
    public static final double MUZZLE_SPEED = 10.0;
    public static final double HEAVY_MUZZLE_SPEED = 9.0;
    public static final double AIR_DRAG = MianbaoRocketFlight.AIR_DRAG;
    public static final double WATER_DRAG = MianbaoRocketFlight.WATER_DRAG;
    public static final double GRAVITY_PER_TICK = MianbaoRocketFlight.ARROW_GRAVITY;

    private static final Map<ResourceLocation, PodFamily> PODS = podBlocks();
    private static final Map<ResourceLocation, RocketWarhead> ROCKETS = rocketEntities();
    private static final Map<PodFamily, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();
    private static final Map<RocketWarhead, Optional<Method>> FLIGHT_METHODS = new ConcurrentHashMap<>();

    private enum PodFamily {
        STANDARD("rocketpod", 14, "RocketpodfireProcedure"),
        MEDIUM("mediumrocketpod", 36, "MediumrocketpodgoProcedure"),
        SECOND("rocketpodsecond", 9, "Rocketpodfire2Procedure"),
        SECOND_RIGHT("rocketpodsecondright", 9, "Rocketpodfire3Procedure"),
        SECOND_LEFT("rocketpodsecondleft", 9, "Rocketpodfire3Procedure"),
        TYPE_1("medium_rocket_pod_type_1", 14, "MediumrocketpodgoProcedure"),
        TYPE_2("medium_rocket_pod_type_2", 36, "MediumrocketpodgoProcedure"),
        TYPE_3("medium_rocket_pod_type_3", 9, "MediumrocketpodgoProcedure"),
        HEAVY("heavy_rocketpod", 15, "HeavyrocketpodgoProcedure"),
        HEAVY_SECOND("heavy_rocketpod_2", 15, "HeavyrocketpodgoProcedure");

        final String basePath;
        final int capacity;
        final String fireProcedure;

        PodFamily(String basePath, int capacity, String fireProcedure) {
            this.basePath = basePath;
            this.capacity = capacity;
            this.fireProcedure = fireProcedure;
        }

        boolean usesBlockEntityAmmo() {
            return this == MEDIUM || isHeavy() || isModern();
        }

        boolean isModern() {
            return this == TYPE_1 || this == TYPE_2 || this == TYPE_3;
        }

        boolean isHeavy() {
            return this == HEAVY || this == HEAVY_SECOND;
        }
    }

    private enum RocketWarhead {
        STANDARD,
        VISUAL_GUIDED,
        HEAVY_EXPLOSIVE,
        HEAVY_FIRE,
        HEAVY_GUIDED
    }

    private static Map<ResourceLocation, PodFamily> podBlocks() {
        Map<ResourceLocation, PodFamily> result = new HashMap<>();
        for (PodFamily family : PodFamily.values()) {
            result.put(id(family.basePath), family);
            if (!family.usesBlockEntityAmmo()) {
                for (int ammo = 1; ammo <= family.capacity; ammo++) {
                    result.put(id(family.basePath + "_" + ammo), family);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceLocation, RocketWarhead> rocketEntities() {
        return Map.of(
                id("rocketstanshewu"), RocketWarhead.STANDARD,
                id("rocket_he_tanshe"), RocketWarhead.STANDARD,
                id("rocket_visual_guided_tanshe"), RocketWarhead.VISUAL_GUIDED,
                id("heavyrocketexplosiontanshe"), RocketWarhead.HEAVY_EXPLOSIVE,
                id("heavyrocketfiretanshe"), RocketWarhead.HEAVY_FIRE,
                id("heavyrocketlaserguidedtanshe"), RocketWarhead.HEAVY_GUIDED
        );
    }

    public static boolean isPod(BlockState state) {
        return state != null && isPodId(blockId(state));
    }

    public static boolean isPodId(ResourceLocation blockId) {
        return blockId != null && PODS.containsKey(blockId);
    }

    public static boolean isVisualGuidedRocket(ResourceLocation entityId) {
        return entityId != null && ROCKETS.get(entityId) == RocketWarhead.VISUAL_GUIDED;
    }

    public static boolean isSamePodVariant(ResourceLocation first, ResourceLocation second) {
        PodFamily firstFamily = PODS.get(first);
        return firstFamily != null && firstFamily == PODS.get(second);
    }

    public static boolean isRocket(Entity entity) {
        return entity != null && ROCKETS.containsKey(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static float nativeExplosionStrength(Entity entity) {
        if (entity == null) return 0.0F;
        RocketWarhead warhead = ROCKETS.get(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        return warhead == RocketWarhead.HEAVY_EXPLOSIVE
                || warhead == RocketWarhead.HEAVY_GUIDED ? 5.0F
                : warhead == null ? 0.0F : 3.0F;
    }

    public static int ammoCount(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        if (context == null) return 0;
        PodFamily family = context.family();
        if (!family.usesBlockEntityAmmo()) {
            return ammoFromBlockId(context.blockId(), family);
        }
        BlockEntity blockEntity = context.level().getBlockEntity(context.pos());
        if (blockEntity == null || blockEntity.isRemoved()) return 0;
        return MianbaoLauncherAmmo.rocketPod(blockEntity, family.isHeavy(), family.isModern());
    }

    public static boolean fireOne(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        int before = ammoCount(level, ref);
        if (context == null || before <= 0) return false;
        Optional<Method> method = FIRE_METHODS.computeIfAbsent(
                context.family(), MianbaoRocketPodCompat::findFireMethod);
        if (method.isEmpty()) return false;
        try (FireControlMissileCommandGuard.Scope ignored =
                     FireControlMissileCommandGuard.enter()) {
            Method fire = method.get();
            Object[] arguments = fire.getParameterCount() == 5
                    ? new Object[]{context.level(), (double) context.pos().getX(),
                    (double) context.pos().getY(), (double) context.pos().getZ(), context.state()}
                    : new Object[]{context.level(), (double) context.pos().getX(),
                    (double) context.pos().getY(), (double) context.pos().getZ()};
            fire.invoke(null, arguments);
            return ammoCount(level, ref) < before;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to fire bound Mianbao rocket pod {} at {}",
                    context.blockId(), context.pos(), exception);
            return false;
        }
    }

    public static @Nullable Vec3 muzzle(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        if (context == null) return null;
        return Sable.HELPER.projectOutOfSubLevel(
                context.level(), localMuzzle(context));
    }

    /**
     * Returns the Sable carrier's world velocity at this pod's muzzle in
     * blocks/tick. The point query includes both rigid-body translation and
     * the omega-cross-radius contribution from pitch, yaw and roll.
     */
    public static Vec3 carrierVelocityAtMuzzle(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        if (context == null || ref.subLevelId() == null) return Vec3.ZERO;
        SubLevel carrier = SubLevelContainer.getContainer(level)
                .getSubLevel(ref.subLevelId());
        if (carrier == null || carrier.isRemoved()) return Vec3.ZERO;

        Vec3 localMuzzle = localMuzzle(context);
        Vector3d localPoint = new Vector3d(
                localMuzzle.x, localMuzzle.y, localMuzzle.z);
        try {
            // Sable reports rigid-body point velocity in blocks/second.
            Vector3d physicalVelocity = Sable.HELPER.getVelocity(
                    context.level(), carrier, localPoint, new Vector3d()
            ).div(20.0);
            if (physicalVelocity.isFinite()) {
                return new Vec3(
                        physicalVelocity.x,
                        physicalVelocity.y,
                        physicalVelocity.z
                );
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to pose history while the physics handle rebuilds.
        }

        try {
            // Both poses transform the same local muzzle point, preserving
            // angular carrier motion in this blocks/tick fallback as well.
            Vector3d currentWorld = carrier.logicalPose().transformPosition(
                    new Vector3d(localPoint));
            Vector3d previousWorld = carrier.lastPose().transformPosition(
                    new Vector3d(localPoint));
            Vector3d velocity = currentWorld.sub(previousWorld);
            return velocity.isFinite()
                    ? new Vec3(velocity.x, velocity.y, velocity.z)
                    : Vec3.ZERO;
        } catch (RuntimeException | LinkageError ignored) {
            return Vec3.ZERO;
        }
    }

    public static @Nullable Vec3 launchDirection(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        if (context == null) return null;
        Direction facing = facing(context.state());
        Vec3 localOrigin = Vec3.atCenterOf(context.pos());
        Vec3 localForward = localOrigin.add(
                facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vec3 worldOrigin = Sable.HELPER.projectOutOfSubLevel(context.level(), localOrigin);
        Vec3 worldForward = Sable.HELPER.projectOutOfSubLevel(context.level(), localForward);
        Vec3 direction = worldForward.subtract(worldOrigin);
        return direction.lengthSqr() < 1.0e-8 ? null : direction.normalize();
    }

    public static double muzzleSpeed(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        if (context == null) return MUZZLE_SPEED;
        BlockEntity blockEntity = context.level().getBlockEntity(context.pos());
        if (blockEntity == null) return MUZZLE_SPEED;
        if (!context.family().isHeavy()) {
            return context.family().isModern() && MianbaoLauncherAmmo.hasVisualGuidedRound(blockEntity)
                    ? 6.0 : MUZZLE_SPEED;
        }
        return MianbaoLauncherAmmo.heavyMuzzleSpeed(blockEntity);
    }

    public static boolean hasVisualGuidedRound(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        return context != null && context.family().isModern()
                && MianbaoLauncherAmmo.hasVisualGuidedRound(context.level().getBlockEntity(context.pos()));
    }

    public static double firstGravity(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        return context != null && context.family().isModern() ? 0.0 : MianbaoRocketFlight.ARROW_GRAVITY;
    }

    public static double flightDrop(Level level, BindingRef ref) {
        if (hasVisualGuidedRound(level, ref)) return 0.0;
        PodContext context = resolve(level, ref);
        return context != null && context.family().isHeavy()
                ? MianbaoRocketFlight.HEAVY_DROP : MianbaoRocketFlight.STANDARD_DROP;
    }

    public static double flightDrop(Entity rocket) {
        if (isVisualGuidedRocket(BuiltInRegistries.ENTITY_TYPE.getKey(rocket.getType()))) return 0.0;
        return ROCKETS.get(BuiltInRegistries.ENTITY_TYPE.getKey(rocket.getType()))
                == RocketWarhead.STANDARD
                ? MianbaoRocketFlight.STANDARD_DROP : MianbaoRocketFlight.HEAVY_DROP;
    }

    public static double firstTickDrag(Level level, BindingRef ref) {
        PodContext context = resolve(level, ref);
        return context != null && context.level().getFluidState(
                net.minecraft.core.BlockPos.containing(localMuzzle(context))).is(FluidTags.WATER)
                ? WATER_DRAG : AIR_DRAG;
    }

    /** Runs the original cached-velocity flight when the entire entity tick was missed. */
    public static void runNativeFlight(Entity rocket) {
        RocketWarhead warhead = ROCKETS.get(BuiltInRegistries.ENTITY_TYPE.getKey(rocket.getType()));
        if (warhead == null || rocket.level().isClientSide()) return;
        Optional<Method> method = FLIGHT_METHODS.computeIfAbsent(warhead, type -> findProcedure(
                switch (type) {
                    case STANDARD -> "Rocket_trailProcedure";
                    case VISUAL_GUIDED -> "RocketVisualGuidedTansheTrailProcedure";
                    case HEAVY_EXPLOSIVE, HEAVY_FIRE -> "HeavyRocketTtrailProcedure";
                    case HEAVY_GUIDED -> "FarrocketlaserguidedtrailProcedure";
                }, type == RocketWarhead.VISUAL_GUIDED ? 6 : 5));
        if (method.isEmpty()) return;
        try {
            if (warhead == RocketWarhead.VISUAL_GUIDED) {
                Entity owner = rocket instanceof net.minecraft.world.entity.projectile.Projectile projectile
                        ? projectile.getOwner() : null;
                method.get().invoke(null, rocket.level(), rocket.getX(), rocket.getY(), rocket.getZ(), owner, rocket);
            } else {
                method.get().invoke(null, rocket.level(), rocket.getX(), rocket.getY(), rocket.getZ(), rocket);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to advance native Mianbao rocket flight", exception);
        }
    }

    public static double spawnDistanceSqr(Level level, BindingRef ref, Entity rocket) {
        Vec3 muzzle = muzzle(level, ref);
        if (muzzle == null || rocket == null) return Double.POSITIVE_INFINITY;
        return Math.min(muzzle.distanceToSqr(MianbaoMissileCompat.getWorldPosition(rocket)),
                muzzle.distanceToSqr(rocket.position()));
    }

    /** Predicts the native unguided trajectory without synchronously loading terrain. */
    public static @Nullable Vec3 predictImpact(
            ServerLevel root, Vec3 origin, Vec3 initialVelocity, double firstDrag, double drop,
            Set<java.util.UUID> ignoredSubLevels
    ) {
        return predictImpact(root, origin, initialVelocity, firstDrag, drop,
                MianbaoRocketFlight.ARROW_GRAVITY, ignoredSubLevels);
    }

    public static @Nullable Vec3 predictImpact(
            ServerLevel root,
            Vec3 origin,
            Vec3 initialVelocity,
            double firstDrag,
            double drop,
            double firstGravity,
            Set<java.util.UUID> ignoredSubLevels
    ) {
        if (root == null || !finite(origin) || !finite(initialVelocity)) return null;
        Set<java.util.UUID> ignored = ignoredSubLevels == null
                ? Set.of() : ignoredSubLevels;
        return MianbaoRocketFlight.trace(origin, initialVelocity, LIFETIME_TICKS,
                0, firstDrag, drop, firstGravity, root.getMinBuildHeight() - 64.0, (position, next) -> {
            Vec3 closest = null;
            double closestDistanceSqr = Double.POSITIVE_INFINITY;

            if (isSegmentLoaded(root, position, next)) {
                BlockHitResult terrain = root.clip(new ClipContext(
                        position, next, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (terrain.getType() != HitResult.Type.MISS) {
                    closest = terrain.getLocation();
                    closestDistanceSqr = position.distanceToSqr(closest);
                }
            }

            SableMissileFuze.Contact structure = SableMissileFuze.firstBlockContact(
                    root, position, next, ignored);
            if (structure != null) {
                double distanceSqr = position.distanceToSqr(structure.position());
                if (distanceSqr < closestDistanceSqr) closest = structure.position();
            }
            return closest;
        });
    }

    /** Applies the same post-move drag and gravity as AbstractArrow in air. */
    public static void applyPostMovePhysics(Entity rocket) {
        if (!isRocket(rocket)) return;
        double drag = rocket.isInWater() ? WATER_DRAG : AIR_DRAG;
        Vec3 velocity = rocket.getDeltaMovement().scale(drag);
        if (!rocket.isNoGravity()) velocity = velocity.add(0.0, -GRAVITY_PER_TICK, 0.0);
        rocket.setDeltaMovement(velocity);
        rocket.hasImpulse = true;
    }

    /** Queues the matching TPS-safe warhead at a Sable contact. */
    public static boolean detonateAt(Entity rocket, SableMissileFuze.Contact contact) {
        if (rocket == null || contact == null || rocket.level().isClientSide()) return false;
        return deferDetonation(rocket, contact.position());
    }

    /** Used by both the Sable contact path and the native block-hit mixin. */
    public static boolean deferDetonation(Entity rocket, Vec3 worldPosition) {
        if (rocket == null || worldPosition == null || rocket.level().isClientSide()) {
            return false;
        }
        if (!isRocket(rocket)) return false;
        return MianbaoDeferredDetonation.interceptRocket(
                rocket, worldPosition, detonationProfile(rocket));
    }

    static byte detonationProfile(Entity rocket) {
        return switch (ROCKETS.get(BuiltInRegistries.ENTITY_TYPE.getKey(rocket.getType()))) {
            case STANDARD, VISUAL_GUIDED -> MianbaoDeferredDetonation.PROFILE_ROCKET;
            case HEAVY_EXPLOSIVE, HEAVY_GUIDED ->
                    MianbaoDeferredDetonation.PROFILE_HEAVY_ROCKET;
            case HEAVY_FIRE ->
                    MianbaoDeferredDetonation.PROFILE_INCENDIARY_ROCKET;
        };
    }

    private static int ammoFromBlockId(ResourceLocation blockId, PodFamily family) {
        if (blockId == null || blockId.equals(id(family.basePath))) return 0;
        String prefix = family.basePath + "_";
        if (!blockId.getPath().startsWith(prefix)) return 0;
        try {
            return Math.max(0, Math.min(family.capacity,
                    Integer.parseInt(blockId.getPath().substring(prefix.length()))));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isSegmentLoaded(ServerLevel level, Vec3 from, Vec3 to) {
        int samples = Math.max(1, Mth.ceil(Math.max(
                Math.abs(to.x - from.x), Math.abs(to.z - from.z)) / 8.0));
        for (int sample = 0; sample <= samples; sample++) {
            double fraction = (double) sample / samples;
            int blockX = Mth.floor(Mth.lerp(fraction, from.x, to.x));
            int blockZ = Mth.floor(Mth.lerp(fraction, from.z, to.z));
            if (level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static Optional<Method> findFireMethod(PodFamily family) {
        Optional<Method> current = findProcedure(family.fireProcedure, 5);
        return current.isPresent() ? current : findProcedure(family.fireProcedure, 4);
    }

    private static Optional<Method> findProcedure(String simpleName, int parameters) {
        try {
            Class<?> owner = Class.forName("net.mcreator.myfirstmod.procedures." + simpleName);
            for (Method method : owner.getMethods()) {
                if (method.getName().equals("execute")
                        && method.getParameterCount() == parameters) {
                    return Optional.of(method);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return Optional.empty();
    }

    private static @Nullable PodContext resolve(Level level, BindingRef ref) {
        if (level == null || ref == null || !ref.isSameTarget(level)) return null;
        Level targetLevel = level;
        if (ref.subLevelId() != null) {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
            if (subLevel != null && !subLevel.isRemoved()) targetLevel = subLevel.getLevel();
        }
        BlockState state = targetLevel.getBlockState(ref.pos());
        ResourceLocation blockId = blockId(state);
        PodFamily family = PODS.get(blockId);
        if (family == null && targetLevel != level) {
            targetLevel = level;
            state = targetLevel.getBlockState(ref.pos());
            blockId = blockId(state);
            family = PODS.get(blockId);
        }
        return family == null ? null
                : new PodContext(targetLevel, ref.pos(), state, blockId, family);
    }

    private static Direction facing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            return Direction.fromAxisAndDirection(state.getValue(BlockStateProperties.AXIS),
                    Direction.AxisDirection.POSITIVE);
        }
        return Direction.NORTH;
    }

    private static Vec3 localMuzzle(PodContext context) {
        Direction facing = facing(context.state());
        return Vec3.atCenterOf(context.pos()).add(
                facing.getStepX() * 2.6,
                facing.getStepY() * 2.6,
                facing.getStepZ() * 2.6);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MianbaoAircraftCompat.MOD_ID, path);
    }

    private record PodContext(Level level, net.minecraft.core.BlockPos pos,
                              BlockState state, ResourceLocation blockId,
                              PodFamily family) {
    }

    private MianbaoRocketPodCompat() {
    }
}
