package com.hooya.stabilizedturret.content.controller;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-only CBC analysis. All CBC types are resolved by name so CBC and
 * every ammunition add-on remain genuinely optional dependencies.
 */
public final class CannonBallisticsCompat {
    private static final String BIG_PROJECTILE_BLOCK =
            "rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock";
    private static final String BIG_PROPELLANT_BLOCK =
            "rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock";
    private static final String INTEGRATED_PROPELLANT =
            "rbasamoyai.createbigcannons.munitions.big_cannon.propellant.IntegratedPropellantProjectile";
    private static final String AUTOCANNON_AMMO =
            "rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem";
    private static final String HEAVY_AUTOCANNON_AMMO =
            "com.dsvv.cbcat.cannon.heavy_autocannon.munitions.HeavyAutocannonAmmoItem";
    private static final String HEAVY_AUTOCANNON_PROJECTILE_ITEM =
            "com.dsvv.cbcat.cannon.heavy_autocannon.munitions.AbstractHeavyAutocannonProjectileItem";
    private static final String MEDIUM_CANNON_AMMO =
            "riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonAmmoItem";
    private static final String MEDIUM_CANNON_PROJECTILE_ITEM =
            "riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonRoundItem";
    private static final String ROCKET_POD_AMMO =
            "com.dsvv.cbcat.cannon.rocketpod.munitions.RocketItem";
    private static final String ROCKET_POD_PROJECTILE_ITEM =
            "com.dsvv.cbcat.cannon.rocketpod.munitions.AbstractRocketItem";
    private static final String MEDIUM_ROCKET_POD_AMMO =
            "com.dsvv.cbcat.cannon.medium_rocketpod.munitions.MediumRocketItem";
    private static final String MEDIUM_ROCKET_POD_PROJECTILE_ITEM =
            "com.dsvv.cbcat.cannon.medium_rocketpod.munitions.AbstractMediumRocketItem";
    private static final String ADVANCED_CONTEXT = "com.dsvv.cbcat.base.CustomPropellantContext";
    private static final String ADVANCED_BARREL = "com.dsvv.cbcat.base.IBigCannonBlockPhysics";
    private static final String DIMENSION_MUNITION_PROPERTIES =
            "rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler";
    private static final int MAX_CANNON_LENGTH = 256;
    private static final int MAX_FLIGHT_TICKS = 1200;
    private static final Map<UUID, CachedProfile> PROFILE_CACHE = new ConcurrentHashMap<>();

    public static @Nullable ShotProfile analyze(Level level, BindingRef ref) {
        CannonGeometry geometry = inspectGeometry(level, ref);
        if (geometry == null) return null;
        AbstractContraptionEntity cannon = geometry.cannon;
        long tick = cannon.level().getGameTime();
        CachedProfile cached = PROFILE_CACHE.get(cannon.getUUID());
        if (cached != null && cached.tick == tick) return cached.profile;
        Object contraption = geometry.contraption;
        Direction orientation = geometry.orientation;
        BlockPos start = geometry.start;
        Map<?, ?> blocks = geometry.blocks;
        Vec3 forward = geometry.forward;
        CannonFamily family = geometry.family;
        boolean itemCannon = family != CannonFamily.BIG_CANNON;
        List<BlockPos> line = geometry.line;
        String cannonType = geometry.cannonType;
        Vec3 muzzle = geometry.muzzle;
        Vec3 carrierVelocity = sampleMuzzleVelocity(cannon, muzzle);

        @SuppressWarnings("unchecked")
        Map<BlockPos, Object> presentBlockEntities =
                (Map<BlockPos, Object>) readField(contraption, "presentBlockEntities");
        if (presentBlockEntities == null) presentBlockEntities = Map.of();

        ShotProfile profile = itemCannon
                ? analyzeItemCannon(level, cannon, contraption, start, line,
                        presentBlockEntities, muzzle, forward, carrierVelocity, cannonType, family)
                : analyzeBigCannon(level, cannon, contraption, orientation, line,
                        presentBlockEntities, muzzle, forward, carrierVelocity, cannonType);
        if (profile == null && cached != null && tick - cached.tick <= 5) {
            ShotProfile previous = cached.profile;
            profile = new ShotProfile(
                    muzzle, forward, carrierVelocity,
                    previous.muzzleSpeed, previous.gravity, previous.drag,
                    previous.quadraticDrag, previous.projectile, previous.cannonType,
                    line.size(), previous.propellantCount, previous.maxFlightTicks,
                    previous.propellantCharge, previous.projectileMass, previous.recoil
            );
        }
        if (profile == null && itemCannon) {
            // Item-fed cannons briefly expose an empty input queue while CBC
            // transfers a round from a magazine into the firing cycle. The
            // display still has to keep its elevation solution during that
            // window instead of falling back to a straight line. Material
            // speed and barrel geometry remain authoritative; the fallback
            // uses CBC's standard autocannon AP gravity/drag until the exact
            // loaded projectile is visible again.
            profile = fallbackItemCannonProfile(
                    geometry, carrierVelocity);
        }
        if (profile != null) {
            PROFILE_CACHE.put(cannon.getUUID(), new CachedProfile(tick, profile));
            trimCaches(tick);
        }
        return profile;
    }

    private static @Nullable ShotProfile fallbackItemCannonProfile(
            CannonGeometry geometry,
            Vec3 carrierVelocity
    ) {
        if (geometry == null
                || geometry.family == CannonFamily.BIG_CANNON) {
            return null;
        }
        Object material = readField(geometry.contraption, "cannonMaterial");
        Object properties =
                material == null ? null : invokeNoArg(material, "properties");
        Number baseSpeed =
                properties == null ? null : invokeNumber(properties, "baseSpeed");
        if (baseSpeed == null || !(baseSpeed.doubleValue() > 1.0e-5)) {
            return null;
        }
        Number speedPerBarrel = invokeNumber(
                properties, "speedIncreasePerBarrel");
        Number maxIncreases = invokeNumber(
                properties, "maxSpeedIncreases");
        Number lifetime = invokeNumber(properties, "projectileLifetime");
        int barrelCount = Math.max(0, geometry.line.size() - 1);
        int effectiveBarrels = maxIncreases == null
                ? barrelCount
                : Math.min(barrelCount,
                Math.max(0, maxIncreases.intValue()));
        double speed = baseSpeed.doubleValue()
                + (speedPerBarrel == null
                ? 0.0
                : speedPerBarrel.doubleValue() * effectiveBarrels);
        if (geometry.family == CannonFamily.ROCKET_POD
                || geometry.family == CannonFamily.MEDIUM_ROCKET_POD) {
            speed = baseSpeed.doubleValue() * 0.5
                    + (speedPerBarrel == null
                    ? 0.0
                    : speedPerBarrel.doubleValue()
                    * 0.25 * effectiveBarrels);
        }
        if (!(speed > 1.0e-5)) return null;
        return new ShotProfile(
                geometry.muzzle,
                geometry.forward,
                carrierVelocity,
                speed,
                -0.025,
                0.01,
                false,
                "createbigcannons:fallback_autocannon_round",
                geometry.cannonType + "|fallback_loaded_round",
                barrelCount,
                1,
                lifetime == null
                        ? MAX_FLIGHT_TICKS
                        : Math.max(1, lifetime.intValue()),
                1.0,
                2.0,
                materialRecoil(properties)
        );
    }

    /**
     * Analyses the exact projectile that has just left a bound cannon.
     *
     * <p>Autocannons remove the round from their breech before
     * {@code EntityJoinLevelEvent}. Looking only at the breech at that point
     * can therefore return no profile (or the next queued ammunition type).
     * The spawned entity is authoritative for gravity, drag and projectile
     * identity; mount geometry still supplies the muzzle, cannon family and
     * Sable carrier velocity.</p>
     */
    public static @Nullable ShotProfile analyzeSpawnedProjectile(
            Level level,
            BindingRef ref,
            Entity projectile
    ) {
        if (projectile == null) return null;
        ResourceLocation projectileId =
                BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        ProjectilePhysics actual = projectilePhysics(
                projectile,
                projectileId == null ? projectile.getType().toString() : projectileId.toString()
        );
        ShotProfile loaded = analyze(level, ref);
        if (loaded != null) {
            if (actual == null) return loaded;
            return new ShotProfile(
                    loaded.muzzle, loaded.currentForward, loaded.carrierVelocity,
                    loaded.muzzleSpeed, actual.gravity, actual.drag,
                    actual.quadraticDrag, actual.name, loaded.cannonType,
                    loaded.barrelLength, loaded.propellantCount,
                    loaded.maxFlightTicks, loaded.propellantCharge,
                    actual.mass, actual.recoil
            );
        }

        CannonGeometry geometry = inspectGeometry(level, ref);
        if (geometry == null || actual == null) return null;
        Vec3 carrierVelocity = sampleMuzzleVelocity(
                geometry.cannon, geometry.muzzle);
        double observedSpeed = projectile.getDeltaMovement().length();
        if (!Double.isFinite(observedSpeed) || observedSpeed < 1.0e-5) {
            observedSpeed = 1.0;
        }
        return new ShotProfile(
                geometry.muzzle,
                geometry.forward,
                carrierVelocity,
                observedSpeed,
                actual.gravity,
                actual.drag,
                actual.quadraticDrag,
                actual.name,
                geometry.cannonType,
                geometry.line.size(),
                0,
                MAX_FLIGHT_TICKS,
                0.0,
                actual.mass,
                actual.recoil
        );
    }

    public static @Nullable Vec3 solveLaunchDirection(ShotProfile profile, Vec3 target) {
        Vec3 analytical = analyticalLaunchDirection(profile, target);
        if (analytical == null) return null;
        return refineLaunchDirection(profile, target, analytical);
    }

    /**
     * CBC advances position with v + a/2 and then applies the complete force
     * vector to velocity. Refine the inexpensive analytical estimate against
     * those exact discrete equations, including quadratic drag and inherited
     * carrier velocity. This is the feed-forward solution; real impact
     * calibration in the controller then removes any add-on-specific residual.
     */
    private static Vec3 refineLaunchDirection(
            ShotProfile profile,
            Vec3 target,
            Vec3 initialDirection
    ) {
        Vec3 direction = initialDirection.normalize();
        double distance = target.distanceTo(profile.muzzle);
        double launchWorldSpeed = Math.max(
                0.05,
                direction.scale(profile.muzzleSpeed)
                        .add(profile.carrierVelocity)
                        .length()
        );
        int flightTicks = Math.min(
                Math.min(profile.maxFlightTicks, MAX_FLIGHT_TICKS),
                Math.max(40, (int) Math.ceil(distance / launchWorldSpeed * 4.0) + 40)
        );
        TrajectorySample best = simulateTrajectory(
                profile, target, direction, flightTicks);
        Vec3 bestDirection = direction;

        for (int iteration = 0; iteration < 7; iteration++) {
            Vec3 forward = direction.normalize();
            Vec3 lateral = new Vec3(0.0, 1.0, 0.0).cross(forward);
            if (lateral.lengthSqr() < 1.0e-8) {
                lateral = new Vec3(1.0, 0.0, 0.0);
            } else {
                lateral = lateral.normalize();
            }
            Vec3 vertical = forward.cross(lateral).normalize();
            TrajectorySample base = simulateTrajectory(
                    profile, target, forward, flightTicks);
            if (base.errorSqr < best.errorSqr) {
                best = base;
                bestDirection = forward;
            }
            if (base.errorSqr < 1.0e-4) break;

            double epsilon = 1.0e-3;
            TrajectorySample lateralTrial = simulateTrajectory(
                    profile,
                    target,
                    forward.add(lateral.scale(epsilon)).normalize(),
                    flightTicks
            );
            TrajectorySample verticalTrial = simulateTrajectory(
                    profile,
                    target,
                    forward.add(vertical.scale(epsilon)).normalize(),
                    flightTicks
            );
            Vec3 miss = target.subtract(base.position);
            double errorLateral = miss.dot(lateral);
            double errorVertical = miss.dot(vertical);
            Vec3 lateralDerivative = lateralTrial.position
                    .subtract(base.position).scale(1.0 / epsilon);
            Vec3 verticalDerivative = verticalTrial.position
                    .subtract(base.position).scale(1.0 / epsilon);
            double j00 = lateralDerivative.dot(lateral);
            double j01 = verticalDerivative.dot(lateral);
            double j10 = lateralDerivative.dot(vertical);
            double j11 = verticalDerivative.dot(vertical);
            double determinant = j00 * j11 - j01 * j10;

            double lateralStep;
            double verticalStep;
            if (Double.isFinite(determinant) && Math.abs(determinant) > 1.0e-7) {
                lateralStep = (errorLateral * j11 - errorVertical * j01)
                        / determinant;
                verticalStep = (j00 * errorVertical - j10 * errorLateral)
                        / determinant;
            } else {
                double flightTime = Math.max(1.0, base.tick);
                lateralStep = errorLateral
                        / Math.max(profile.muzzleSpeed * flightTime, 1.0);
                verticalStep = errorVertical
                        / Math.max(profile.muzzleSpeed * flightTime, 1.0);
            }
            if (!Double.isFinite(lateralStep) || !Double.isFinite(verticalStep)) {
                break;
            }
            double stepLength = Math.hypot(lateralStep, verticalStep);
            if (stepLength > 0.20) {
                double scale = 0.20 / stepLength;
                lateralStep *= scale;
                verticalStep *= scale;
            }
            direction = forward
                    .add(lateral.scale(lateralStep))
                    .add(vertical.scale(verticalStep))
                    .normalize();
        }
        TrajectorySample finalSample = simulateTrajectory(
                profile, target, direction, flightTicks);
        return finalSample.errorSqr < best.errorSqr ? direction : bestDirection;
    }

    private static TrajectorySample simulateTrajectory(
            ShotProfile profile,
            Vec3 target,
            Vec3 launchDirection,
            int maxTicks
    ) {
        Vec3 position = profile.muzzle;
        Vec3 velocity = launchDirection.scale(profile.muzzleSpeed)
                .add(profile.carrierVelocity);
        Vec3 bestPosition = position;
        double bestErrorSqr = position.distanceToSqr(target);
        int bestTick = 0;
        double drag = Math.max(0.0, profile.drag);

        for (int tick = 1; tick <= maxTicks; tick++) {
            double speed = velocity.length();
            double dragForce = drag * speed;
            if (profile.quadraticDrag) dragForce *= speed;
            dragForce = Math.min(dragForce, speed);
            Vec3 acceleration = speed > 1.0e-10
                    ? velocity.normalize().scale(-dragForce)
                    : Vec3.ZERO;
            acceleration = acceleration.add(0.0, profile.gravity, 0.0);
            Vec3 previousPosition = position;
            position = position.add(velocity).add(acceleration.scale(0.5));
            velocity = velocity.add(acceleration);
            // CBC collision detection sweeps the projectile through the
            // complete movement segment for this tick. Sampling only the
            // integer-tick endpoint quantizes a 9-block/tick autocannon path
            // by several blocks and makes the solver deliberately miss a
            // target that lies between two samples. Solve against the closest
            // point on the same swept segment that CBC actually collides on.
            Vec3 movement = position.subtract(previousPosition);
            double movementLengthSqr = movement.lengthSqr();
            double segmentTime = movementLengthSqr < 1.0e-12
                    ? 1.0
                    : Mth.clamp(
                    target.subtract(previousPosition).dot(movement)
                            / movementLengthSqr,
                    0.0,
                    1.0
            );
            Vec3 sweptPosition = previousPosition.add(
                    movement.scale(segmentTime));
            double errorSqr = sweptPosition.distanceToSqr(target);
            if (errorSqr < bestErrorSqr) {
                bestErrorSqr = errorSqr;
                bestPosition = sweptPosition;
                bestTick = tick;
            } else if (tick > bestTick + 12
                    && position.subtract(target).dot(velocity) > 0.0) {
                break;
            }
            if (!isFinite(position) || !isFinite(velocity)) break;
        }
        return new TrajectorySample(bestPosition, bestErrorSqr, bestTick);
    }

    private static @Nullable Vec3 analyticalLaunchDirection(
            ShotProfile profile,
            Vec3 target
    ) {
        if (profile == null || target == null || !isFinite(target)
                || !(profile.muzzleSpeed > 1.0e-5)) return null;
        Vec3 displacement = target.subtract(profile.muzzle);
        if (displacement.lengthSqr() < 1.0e-8) return profile.currentForward;

        double drag = Math.max(0.0, profile.drag);
        // CBC applies quadratic drag as drag * |velocity|^2.  The closed-form
        // predictor below uses a scalar per-tick velocity multiplier, so use
        // the launch-speed linearization for quadratic projectiles.  This
        // keeps custom ammunition from being treated as ordinary linear-drag
        // shells while preserving the fast analytical solve used each tick.
        if (profile.quadraticDrag) {
            drag *= Math.max(profile.muzzleSpeed, 1.0);
        }
        drag = Math.min(0.999, drag);
        double gravity = profile.gravity;
        double bestError = Double.POSITIVE_INFINITY;
        Vec3 bestRequired = null;
        double previousTime = 0.05;
        RequiredVelocity previous = requiredVelocity(displacement, previousTime, gravity, drag);
        double previousFunction = previous.velocity.subtract(profile.carrierVelocity).length()
                - profile.muzzleSpeed;

        for (double time = 0.25; time <= Math.min(profile.maxFlightTicks, MAX_FLIGHT_TICKS);
             time += 0.25) {
            RequiredVelocity required = requiredVelocity(displacement, time, gravity, drag);
            Vec3 relative = required.velocity.subtract(profile.carrierVelocity);
            double function = relative.length() - profile.muzzleSpeed;
            double error = Math.abs(function);
            if (error < bestError) {
                bestError = error;
                bestRequired = relative;
            }
            if (previousFunction > 0.0 && function <= 0.0) {
                double low = previousTime;
                double high = time;
                for (int iteration = 0; iteration < 24; iteration++) {
                    double middle = (low + high) * 0.5;
                    Vec3 middleRelative = requiredVelocity(
                            displacement, middle, gravity, drag).velocity
                            .subtract(profile.carrierVelocity);
                    if (middleRelative.length() - profile.muzzleSpeed > 0.0) {
                        low = middle;
                    } else {
                        high = middle;
                    }
                }
                Vec3 exact = requiredVelocity(displacement, (low + high) * 0.5, gravity, drag)
                        .velocity.subtract(profile.carrierVelocity);
                return exact.lengthSqr() < 1.0e-10 ? null : exact.normalize();
            }
            previousTime = time;
            previousFunction = function;
            previous = required;
        }
        return bestRequired == null || bestRequired.lengthSqr() < 1.0e-10
                ? displacement.normalize()
                : bestRequired.normalize();
    }

    public static @Nullable Vec3 getMuzzlePosition(Level level, BindingRef ref) {
        CannonGeometry geometry = inspectGeometry(level, ref);
        return geometry == null ? null : geometry.muzzle;
    }

    /**
     * Returns the authoritative world-space barrel axis produced by CBC's
     * current contraption transform, without consulting ammunition caches.
     */
    public static @Nullable Vec3 getCurrentForward(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) return null;
        Object mounted = invokeNoArg(mount, "getContraption");
        if (!(mounted instanceof AbstractContraptionEntity cannon)
                || cannon.isRemoved()) return null;
        Object contraption = cannon.getContraption();
        Direction orientation = contraption == null
                ? null
                : invokeNoArg(contraption, "initialOrientation")
                instanceof Direction value ? value : null;
        // Controller calibration has just written the current entity angles;
        // partialTick=1 observes those values immediately. Ordinary shot
        // geometry intentionally keeps partialTick=0 because CBC itself fires
        // from that previous-tick pose.
        return orientation == null
                ? null : worldForward(cannon, orientation, 1.0F);
    }

    public static Vec3 getInheritedVelocity(Level level, BindingRef ref) {
        ShotProfile profile = analyze(level, ref);
        return profile == null ? Vec3.ZERO : profile.carrierVelocity;
    }

    private static @Nullable ShotProfile analyzeBigCannon(
            Level level,
            AbstractContraptionEntity cannon,
            Object contraption,
            Direction orientation,
            List<BlockPos> line,
            Map<BlockPos, Object> present,
            Vec3 muzzle,
            Vec3 forward,
            Vec3 carrierVelocity,
            String cannonType
    ) {
        Class<?> projectileBlockType = optionalClass(BIG_PROJECTILE_BLOCK);
        Class<?> propellantType = optionalClass(BIG_PROPELLANT_BLOCK);
        if (projectileBlockType == null || propellantType == null) return null;

        List<StructureBlockInfo> loaded = new ArrayList<>();
        List<StructureBlockInfo> projectileBlocks = new ArrayList<>();
        List<PropellantEntry> propellants = new ArrayList<>();
        for (BlockPos position : line) {
            Object blockEntity = present.get(position);
            Object behavior = blockEntity == null ? null : invokeNoArg(blockEntity, "cannonBehavior");
            Object rawInfo = behavior == null ? null : invokeNoArg(behavior, "block");
            if (!(rawInfo instanceof StructureBlockInfo info) || info.state().isAir()) continue;
            loaded.add(info);
            Block block = info.state().getBlock();
            if (projectileBlockType.isInstance(block)) projectileBlocks.add(info);
            if (propellantType.isInstance(block)) {
                Number power = invokeNumber(block, "getChargePower", info);
                if (power != null && power.doubleValue() > 0.0) {
                    propellants.add(new PropellantEntry(block, info, power.doubleValue()));
                }
            }
        }

        ProjectilePhysics projectile = createBigProjectile(level, projectileBlocks);
        if (projectile == null) return null;
        double baseCharge = propellants.stream().mapToDouble(PropellantEntry::power).sum()
                + projectile.addedCharge;
        if (projectile.integratedCharge > 0.0) baseCharge += projectile.integratedCharge;

        double speed = baseCharge;
        AdvancedResult advanced = analyzeAdvanced(
                line, contraption, propellants, orientation,
                projectile.addedCharge + projectile.integratedCharge);
        if (advanced != null && advanced.velocity > 1.0e-5) {
            speed = advanced.velocity;
        }
        if (!(speed > 1.0e-5)) return null;
        return new ShotProfile(
                muzzle, forward, carrierVelocity, speed,
                projectile.gravity, projectile.drag, projectile.quadraticDrag,
                projectile.name, cannonType, line.size(),
                propellants.size(), MAX_FLIGHT_TICKS, baseCharge, projectile.mass,
                propellants.stream().mapToDouble(entry -> entry.power * 1.0).sum()
                        + projectile.recoil
        );
    }

    private static @Nullable ShotProfile analyzeItemCannon(
            Level level,
            AbstractContraptionEntity cannon,
            Object contraption,
            BlockPos start,
            List<BlockPos> line,
            Map<BlockPos, Object> present,
            Vec3 muzzle,
            Vec3 forward,
            Vec3 carrierVelocity,
            String cannonType,
            CannonFamily family
    ) {
        Class<?>[] ammunitionTypes = ammunitionTypes(family);
        if (ammunitionTypes.length == 0) return null;
        ItemStack ammunition = findCannonAmmunition(
                contraption, present.get(start), ammunitionTypes);
        if (ammunition.isEmpty()) return null;
        Object ammoItem = ammunition.getItem();
        String projectileFactory = family == CannonFamily.MEDIUM_CANNON
                ? "getMediumcannonProjectile"
                : "getAutocannonProjectile";
        Object projectile = invokeCompatible(
                ammoItem, projectileFactory, ammunition, level);
        String projectileName = projectile instanceof Entity entity
                ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()
                : BuiltInRegistries.ITEM.getKey(ammunition.getItem()).toString();
        ProjectilePhysics physics = projectilePhysics(projectile, projectileName);
        if (physics == null) return null;

        Object material = readField(contraption, "cannonMaterial");
        Object properties = material == null ? null : invokeNoArg(material, "properties");
        Number baseSpeed = properties == null ? null : invokeNumber(properties, "baseSpeed");
        Number speedPerBarrel = properties == null ? null
                : invokeNumber(properties, "speedIncreasePerBarrel");
        Number maxIncreases = properties == null ? null : invokeNumber(properties, "maxSpeedIncreases");
        Number lifetime = properties == null ? null : invokeNumber(properties, "projectileLifetime");
        if (baseSpeed == null) return null;
        int barrelCount = Math.max(0, line.size() - 1);
        int effectiveBarrels = maxIncreases == null
                ? barrelCount : Math.min(barrelCount, maxIncreases.intValue());
        double speed = baseSpeed.doubleValue()
                + (speedPerBarrel == null ? 0.0 : speedPerBarrel.doubleValue() * effectiveBarrels);
        double referenceSpeed = baseSpeed.doubleValue()
                + (speedPerBarrel == null || maxIncreases == null
                ? 0.0
                : speedPerBarrel.doubleValue() * Math.max(0, maxIncreases.intValue()));
        Number velocityMultiplier = invokeNumber(
                projectile,
                "stabilizedTurret$getVelocityMultiplier"
        );
        if (velocityMultiplier != null && velocityMultiplier.doubleValue() > 0.0) {
            speed *= velocityMultiplier.doubleValue();
            referenceSpeed *= velocityMultiplier.doubleValue();
        }
        if (family == CannonFamily.ROCKET_POD
                || family == CannonFamily.MEDIUM_ROCKET_POD) {
            // CBC AT launches both rocket-pod families at half base speed and
            // one quarter of the normal per-barrel increase.
            speed = baseSpeed.doubleValue() * 0.5
                    + (speedPerBarrel == null
                    ? 0.0
                    : speedPerBarrel.doubleValue() * 0.25 * effectiveBarrels);
            referenceSpeed = baseSpeed.doubleValue() * 0.5
                    + (speedPerBarrel == null || maxIncreases == null
                    ? 0.0
                    : speedPerBarrel.doubleValue() * 0.25
                    * Math.max(0, maxIncreases.intValue()));
        }
        boolean strongRound = family == CannonFamily.HEAVY_AUTOCANNON
                && Boolean.TRUE.equals(invokeCompatible(ammoItem, "isStrong", ammunition));
        if (strongRound) {
            speed *= 1.5;
            referenceSpeed *= 1.5;
        }
        if (!(speed > 1.0e-5)) return null;
        double referenceRecoil = materialRecoil(properties);
        String describedType = cannonType
                + "|family=" + family.id
                + "|fixed_speed=" + referenceSpeed
                + "|fixed_recoil=" + referenceRecoil
                + "|strong=" + strongRound;
        return new ShotProfile(
                muzzle, forward, carrierVelocity, speed,
                physics.gravity, physics.drag, physics.quadraticDrag,
                physics.name, describedType, barrelCount,
                1, lifetime == null ? MAX_FLIGHT_TICKS : Math.max(1, lifetime.intValue()),
                strongRound ? 1.5 : 1.0,
                physics.mass, referenceRecoil + physics.recoil
        );
    }

    private static @Nullable AdvancedResult analyzeAdvanced(
            List<BlockPos> line,
            Object contraption,
            List<PropellantEntry> propellants,
            Direction orientation,
            double projectileCharge
    ) {
        Class<?> contextType = optionalClass(ADVANCED_CONTEXT);
        Class<?> barrelType = optionalClass(ADVANCED_BARREL);
        if (contextType == null || barrelType == null || advancedPhysicsDisabled()) return null;
        try {
            Object context = contextType.getConstructor().newInstance();
            for (PropellantEntry propellant : propellants) {
                invokeCompatible(context, "addPropellant",
                        propellant.block, propellant.info, orientation);
            }
            Object rawBlocks = invokeNoArg(contraption, "getBlocks");
            if (rawBlocks instanceof Map<?, ?> blocks) {
                for (BlockPos position : line) {
                    Object raw = blocks.get(position);
                    if (!(raw instanceof StructureBlockInfo info)) continue;
                    Block block = info.state().getBlock();
                    if (barrelType.isInstance(block)) {
                        invokeCompatible(context, "addBarrel", block);
                    }
                }
            }
            Field charges = findField(contextType, "chargesUsed");
            if (charges != null && projectileCharge != 0.0) {
                charges.setAccessible(true);
                charges.setFloat(context, charges.getFloat(context) + (float) projectileCharge);
            }
            Number velocity = invokeNumber(context, "getVelocity");
            return velocity == null ? null : new AdvancedResult(velocity.doubleValue());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean advancedPhysicsDisabled() {
        try {
            Class<?> configs = Class.forName("com.dsvv.cbcat.config.CBCATConfigs");
            Object server = configs.getField("SERVER").get(null);
            Object bigCannons = server == null ? null : readField(server, "bigCannons");
            Object option = bigCannons == null ? null : readField(bigCannons, "disablePhysicRework");
            Object value = option == null ? null : invokeNoArg(option, "get");
            return value instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static @Nullable ProjectilePhysics createBigProjectile(
            Level level, List<StructureBlockInfo> projectileBlocks
    ) {
        Class<?> projectileBlockType = optionalClass(BIG_PROJECTILE_BLOCK);
        if (projectileBlockType == null || projectileBlocks.isEmpty()) return null;
        for (StructureBlockInfo info : projectileBlocks) {
            Block block = info.state().getBlock();
            if (!projectileBlockType.isInstance(block)) continue;
            Object projectile = invokeCompatible(block, "getProjectile", level, projectileBlocks);
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            ProjectilePhysics physics = projectilePhysics(projectile, name);
            if (physics == null) continue;
            Number addedCharge = invokeNumber(projectile, "addedChargePower");
            double integratedCharge = 0.0;
            Class<?> integratedType = optionalClass(INTEGRATED_PROPELLANT);
            if (integratedType != null && integratedType.isInstance(projectile)) {
                Number value = invokeNumber(projectile, "getChargePower");
                if (value != null) integratedCharge = value.doubleValue();
            }
            return new ProjectilePhysics(
                    physics.gravity, physics.drag, physics.quadraticDrag, name,
                    addedCharge == null ? 0.0 : addedCharge.doubleValue(),
                    integratedCharge, physics.mass, physics.recoil
            );
        }
        return null;
    }

    private static @Nullable ProjectilePhysics projectilePhysics(Object projectile, String name) {
        if (!(projectile instanceof Entity)) return null;
        Number gravity = invokeNumberDeclared(projectile, "getDefaultGravity");
        Object properties = invokeDeclaredNoArg(projectile, "getBallisticProperties");
        Number configuredDrag = properties == null ? null : invokeNumber(properties, "drag");
        Number configuredMass = properties == null ? null
                : invokeNumber(properties, "durabilityMass");
        Number configuredRecoil = properties == null ? null
                : invokeNumber(properties, "addedRecoil");
        double dragMultiplier = 1.0;
        Class<?> dimensionPropertiesType = optionalClass(DIMENSION_MUNITION_PROPERTIES);
        if (dimensionPropertiesType != null && projectile instanceof Entity entity) {
            Object dimensionProperties = invokeStaticCompatible(
                    dimensionPropertiesType, "getProperties", entity.level());
            Number configuredMultiplier = dimensionProperties == null
                    ? null : invokeNumber(dimensionProperties, "dragMultiplier");
            if (configuredMultiplier != null) dragMultiplier = configuredMultiplier.doubleValue();
        }
        Object quadratic = properties == null ? null : invokeNoArg(properties, "isQuadraticDrag");
        if (!(quadratic instanceof Boolean)) {
            quadratic = properties == null ? null : invokeNoArg(properties, "quadraticDrag");
        }
        if (gravity == null || configuredDrag == null) return null;
        return new ProjectilePhysics(
                gravity.doubleValue(), Math.max(0.0, configuredDrag.doubleValue() * dragMultiplier),
                quadratic instanceof Boolean bool && bool, name, 0.0, 0.0,
                configuredMass == null ? 0.0 : Math.max(0.0, configuredMass.doubleValue()),
                configuredRecoil == null ? 0.0 : Math.max(0.0, configuredRecoil.doubleValue())
        );
    }

    private static ItemStack findCannonAmmunition(
            Object contraption, @Nullable Object breech, Class<?>[] ammunitionTypes
    ) {
        Object queue = breech == null ? null : invokeNoArg(breech, "getInputBuffer");
        if (queue instanceof ItemStack stack && isAmmunition(stack, ammunitionTypes)) {
            return stack;
        }
        if (queue instanceof Collection<?> collection) {
            for (Object value : collection) {
                if (value instanceof ItemStack stack && isAmmunition(stack, ammunitionTypes)) {
                    return stack;
                }
            }
        }
        Object storage = invokeNoArg(contraption, "getItemStorage");
        Number slots = storage == null ? null : invokeNumber(storage, "getSlots");
        if (slots != null) {
            for (int slot = 0; slot < slots.intValue(); slot++) {
                Object raw = invokeCompatible(storage, "getStackInSlot", slot);
                if (raw instanceof ItemStack stack && isAmmunition(stack, ammunitionTypes)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isAmmunition(ItemStack stack, Class<?>[] ammunitionTypes) {
        if (stack == null || stack.isEmpty()) return false;
        Object item = stack.getItem();
        for (Class<?> type : ammunitionTypes) {
            if (type != null && type.isInstance(item)) return true;
        }
        return false;
    }

    private static Class<?>[] ammunitionTypes(CannonFamily family) {
        String[] names = switch (family) {
            case AUTOCANNON, TWIN_AUTOCANNON, ROTARY_CANNON ->
                    new String[]{AUTOCANNON_AMMO};
            case HEAVY_AUTOCANNON -> new String[]{
                    HEAVY_AUTOCANNON_AMMO,
                    HEAVY_AUTOCANNON_PROJECTILE_ITEM
            };
            case MEDIUM_CANNON -> new String[]{
                    MEDIUM_CANNON_AMMO,
                    MEDIUM_CANNON_PROJECTILE_ITEM
            };
            case ROCKET_POD -> new String[]{
                    ROCKET_POD_AMMO,
                    ROCKET_POD_PROJECTILE_ITEM
            };
            case MEDIUM_ROCKET_POD -> new String[]{
                    MEDIUM_ROCKET_POD_AMMO,
                    MEDIUM_ROCKET_POD_PROJECTILE_ITEM
            };
            default -> new String[0];
        };
        List<Class<?>> types = new ArrayList<>(names.length);
        for (String name : names) {
            Class<?> type = optionalClass(name);
            if (type != null) types.add(type);
        }
        return types.toArray(Class<?>[]::new);
    }

    private static CannonFamily cannonFamily(String className) {
        String value = className == null ? "" : className.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("mountedheavyautocannoncontraption")) {
            return CannonFamily.HEAVY_AUTOCANNON;
        }
        if (value.contains("mountedtwinautocannoncontraption")) {
            return CannonFamily.TWIN_AUTOCANNON;
        }
        if (value.contains("mountedrotarycannoncontraption")) {
            return CannonFamily.ROTARY_CANNON;
        }
        if (value.contains("mountedmediumcannoncontraption")) {
            return CannonFamily.MEDIUM_CANNON;
        }
        if (value.contains("mountedmediumrocketrailcontraption")) {
            return CannonFamily.MEDIUM_ROCKET_POD;
        }
        if (value.contains("mountedrocketpodcontraption")) {
            return CannonFamily.ROCKET_POD;
        }
        if (value.contains("mountedautocannoncontraption")) {
            return CannonFamily.AUTOCANNON;
        }
        return CannonFamily.BIG_CANNON;
    }

    private static String describeCannon(
            Object contraption,
            Map<?, ?> blocks,
            List<BlockPos> line
    ) {
        String materialName = "";
        Object material = readField(contraption, "cannonMaterial");
        Object rawMaterialName = material == null ? null : invokeNoArg(material, "name");
        if (rawMaterialName != null) materialName = rawMaterialName.toString();

        String representativeBlock = "";
        for (BlockPos position : line) {
            Object raw = blocks.get(position);
            if (!(raw instanceof StructureBlockInfo info)) continue;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(info.state().getBlock());
            String path = id.getPath();
            if (representativeBlock.isEmpty()
                    || path.contains("breech")
                    || path.contains("nethersteel")
                    || path.contains("cast_iron")) {
                representativeBlock = id.toString();
            }
            if (path.contains("nethersteel")) break;
        }
        return contraption.getClass().getSimpleName()
                + "|material=" + materialName
                + "|block=" + representativeBlock;
    }

    private static double materialRecoil(@Nullable Object properties) {
        Number value = properties == null ? null : invokeNumber(properties, "baseRecoil");
        return value == null ? 0.0 : Math.max(0.0, value.doubleValue());
    }

    private static List<BlockPos> collectCannonLine(
            Map<?, ?> blocks, BlockPos start, Direction direction
    ) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos cursor = start;
        for (int count = 0; count < MAX_CANNON_LENGTH; count++) {
            if (!blocks.containsKey(cursor)) break;
            result.add(cursor);
            cursor = cursor.relative(direction);
        }
        return result;
    }

    private static @Nullable Vec3 bigCannonMuzzle(
            AbstractContraptionEntity cannon,
            BlockPos start,
            Direction orientation,
            List<BlockPos> line,
            Vec3 forward
    ) {
        BlockPos firstAir = line.isEmpty()
                ? start.relative(orientation)
                : line.get(line.size() - 1).relative(orientation);
        Vec3 beyondMuzzle = worldPoint(cannon, Vec3.atCenterOf(firstAir.relative(orientation)));
        return beyondMuzzle == null ? null : beyondMuzzle.subtract(forward.scale(2.0));
    }

    private static @Nullable Vec3 autocannonMuzzle(
            AbstractContraptionEntity cannon,
            BlockPos start,
            Direction orientation,
            List<BlockPos> line,
            Vec3 forward
    ) {
        BlockPos firstAir = line.isEmpty()
                ? start.relative(orientation)
                : line.get(line.size() - 1).relative(orientation);
        Vec3 beyondMuzzle = worldPoint(cannon, Vec3.atCenterOf(firstAir.relative(orientation)));
        return beyondMuzzle == null ? null : beyondMuzzle.subtract(forward.scale(1.5));
    }

    private static @Nullable Vec3 worldForward(
            AbstractContraptionEntity cannon, Direction orientation
    ) {
        return worldForward(cannon, orientation, 0.0F);
    }

    private static @Nullable Vec3 worldForward(
            AbstractContraptionEntity cannon,
            Direction orientation,
            float partialTick
    ) {
        Vec3 origin = worldPoint(
                cannon, Vec3.atCenterOf(BlockPos.ZERO), partialTick);
        Vec3 ahead = worldPoint(
                cannon,
                Vec3.atCenterOf(BlockPos.ZERO.relative(orientation)),
                partialTick
        );
        if (origin == null || ahead == null) return null;
        Vec3 result = ahead.subtract(origin);
        return result.lengthSqr() < 1.0e-8 ? null : result.normalize();
    }

    private static @Nullable Vec3 worldPoint(AbstractContraptionEntity cannon, Vec3 local) {
        return worldPoint(cannon, local, 0.0F);
    }

    private static @Nullable Vec3 worldPoint(
            AbstractContraptionEntity cannon,
            Vec3 local,
            float partialTick
    ) {
        Vec3 point = cannon.toGlobalVector(local, partialTick);
        return Sable.HELPER.projectOutOfSubLevel(cannon.level(), point);
    }

    /**
     * Resolves cannon geometry without inspecting its ammunition. This stays
     * valid during the firing window after CBC has extracted a round from the
     * breech and is also sufficient to associate a spawned projectile with
     * the nearest bound mount.
     */
    private static @Nullable CannonGeometry inspectGeometry(
            Level level, BindingRef ref
    ) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) return null;
        Object mounted = invokeNoArg(mount, "getContraption");
        if (!(mounted instanceof AbstractContraptionEntity cannon)
                || cannon.isRemoved()) return null;
        Object contraption = cannon.getContraption();
        if (contraption == null) return null;
        Direction orientation =
                invokeNoArg(contraption, "initialOrientation") instanceof Direction value
                        ? value : null;
        BlockPos start =
                invokeNoArg(contraption, "getStartPos") instanceof BlockPos value
                        ? value : null;
        Object rawBlocks = invokeNoArg(contraption, "getBlocks");
        if (orientation == null || start == null
                || !(rawBlocks instanceof Map<?, ?> blocks)) return null;
        Vec3 forward = worldForward(cannon, orientation);
        if (forward == null) return null;
        CannonFamily family = cannonFamily(contraption.getClass().getName());
        List<BlockPos> line = collectCannonLine(blocks, start, orientation);
        String cannonType = describeCannon(contraption, blocks, line);
        Vec3 muzzle = family == CannonFamily.BIG_CANNON
                ? bigCannonMuzzle(cannon, start, orientation, line, forward)
                : autocannonMuzzle(cannon, start, orientation, line, forward);
        if (muzzle == null || !isFinite(muzzle)) return null;
        return new CannonGeometry(
                cannon, contraption, orientation, start, blocks, forward,
                family, line, cannonType, muzzle
        );
    }

    private static Vec3 sampleMuzzleVelocity(AbstractContraptionEntity cannon, Vec3 muzzle) {
        SubLevel containing = Sable.HELPER.getContaining(cannon);
        if (containing == null || containing.isRemoved()) return Vec3.ZERO;
        try {
            // Measure how the *same local muzzle point* moved between Sable's
            // previous and current carrier poses. Merely subtracting the two
            // pose origins loses the velocity produced when an aircraft
            // pitches, yaws or rolls. Conversely, querying the CBC cannon
            // entity's point velocity also includes the gun mount's own
            // traverse/elevation, which must not be treated as carrier motion.
            Vector3d localMuzzle = new Vector3d(muzzle.x, muzzle.y, muzzle.z);
            containing.logicalPose().transformPositionInverse(localMuzzle);
            // Sable exposes rigid-body velocity in blocks/second and includes
            // both linear motion and omega x radius for this exact local
            // point. It is the authoritative carrier velocity on the server.
            Vector3d physicalVelocity = Sable.HELPER.getVelocity(
                    cannon.level(), containing, localMuzzle, new Vector3d()
            ).div(20.0);
            if (physicalVelocity.isFinite()
                    && physicalVelocity.lengthSquared() > 1.0e-10) {
                return new Vec3(
                        physicalVelocity.x,
                        physicalVelocity.y,
                        physicalVelocity.z
                );
            }

            // Pose history is a blocks/tick fallback while a physics handle is
            // being rebuilt (for example immediately after assembly).
            Vector3d previousWorldMuzzle = new Vector3d(localMuzzle);
            containing.lastPose().transformPosition(previousWorldMuzzle);
            Vector3d carrierDelta = new Vector3d(muzzle.x, muzzle.y, muzzle.z)
                    .sub(previousWorldMuzzle);
            if (carrierDelta.isFinite()) {
                return new Vec3(carrierDelta.x, carrierDelta.y, carrierDelta.z);
            }
            return physicalVelocity.isFinite()
                    ? new Vec3(physicalVelocity.x, physicalVelocity.y, physicalVelocity.z)
                    : Vec3.ZERO;
        } catch (RuntimeException | LinkageError ignored) {
            return Vec3.ZERO;
        }
    }

    private static void trimCaches(long tick) {
        if (PROFILE_CACHE.size() > 1024) {
            PROFILE_CACHE.entrySet().removeIf(entry -> entry.getValue().tick < tick - 2);
        }
    }

    private static RequiredVelocity requiredVelocity(
            Vec3 displacement, double time, double gravity, double drag
    ) {
        int fullTicks = Math.max(0, (int) Math.floor(time));
        double partialTick = Math.max(0.0, time - fullTicks);
        if (drag < 1.0e-8) {
            double gravityDisplacement = gravity * (
                    0.5 * fullTicks * fullTicks
                            + partialTick * (fullTicks + 0.5)
            );
            return new RequiredVelocity(new Vec3(
                    displacement.x / time,
                    (displacement.y - gravityDisplacement) / time,
                    displacement.z / time
            ));
        }
        double effectiveDrag = Math.min(drag, 1.0);
        double q = 1.0 - effectiveDrag;
        double qn = Math.pow(q, fullTicks);
        double velocitySum = (1.0 - qn) / effectiveDrag;
        double halfStepFactor = 1.0 - effectiveDrag * 0.5;
        double launchCoefficient = halfStepFactor * (
                velocitySum + partialTick * qn
        );
        double gravityDisplacement = halfStepFactor * gravity / effectiveDrag * (
                fullTicks - velocitySum + partialTick * (1.0 - qn)
        ) + 0.5 * gravity * (fullTicks + partialTick);
        if (launchCoefficient < 1.0e-8) {
            return new RequiredVelocity(displacement.scale(1.0 / Math.max(time, 1.0e-8)));
        }
        return new RequiredVelocity(new Vec3(
                displacement.x / launchCoefficient,
                (displacement.y - gravityDisplacement) / launchCoefficient,
                displacement.z / launchCoefficient
        ));
    }

    private static @Nullable Class<?> optionalClass(String name) {
        try {
            return Class.forName(name, false, CannonBallisticsCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Object invokeNoArg(Object owner, String name) {
        return invokeCompatible(owner, name);
    }

    private static @Nullable Object invokeDeclaredNoArg(Object owner, String name) {
        Method method = findMethod(owner.getClass(), name, 0);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Number invokeNumber(Object owner, String name, Object... arguments) {
        Object result = invokeCompatible(owner, name, arguments);
        return result instanceof Number number ? number : null;
    }

    private static @Nullable Number invokeNumberDeclared(Object owner, String name) {
        Object result = invokeDeclaredNoArg(owner, name);
        return result instanceof Number number ? number : null;
    }

    private static @Nullable Object invokeCompatible(Object owner, String name, Object... arguments) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != arguments.length
                        || !accepts(method.getParameterTypes(), arguments)) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(owner, arguments);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                }
            }
        }
        return null;
    }

    private static @Nullable Object invokeStaticCompatible(
            Class<?> owner, String name, Object... arguments
    ) {
        if (owner == null) return null;
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != arguments.length
                        || !accepts(method.getParameterTypes(), arguments)) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(null, arguments);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                }
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        for (int index = 0; index < parameters.length; index++) {
            Object argument = arguments[index];
            if (argument == null) continue;
            Class<?> parameter = wrap(parameters[index]);
            if (!parameter.isInstance(argument)) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static @Nullable Method findMethod(Class<?> type, String name, int argumentCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == argumentCount) {
                    return method;
                }
            }
        }
        return null;
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static @Nullable Object readField(Object owner, String name) {
        Field field = findField(owner.getClass(), name);
        if (field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Number readNumberField(Object owner, String name) {
        Object value = readField(owner, name);
        return value instanceof Number number ? number : null;
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    public record ShotProfile(
            Vec3 muzzle,
            Vec3 currentForward,
            Vec3 carrierVelocity,
            double muzzleSpeed,
            double gravity,
            double drag,
            boolean quadraticDrag,
            String projectile,
            String cannonType,
            int barrelLength,
            int propellantCount,
            int maxFlightTicks,
            double propellantCharge,
            double projectileMass,
            double recoil
    ) {
    }

    private record ProjectilePhysics(
            double gravity,
            double drag,
            boolean quadraticDrag,
            String name,
            double addedCharge,
            double integratedCharge,
            double mass,
            double recoil
    ) {
    }

    private record PropellantEntry(Block block, StructureBlockInfo info, double power) {
    }

    private record AdvancedResult(double velocity) {
    }

    private record CachedProfile(long tick, ShotProfile profile) {
    }

    private record CannonGeometry(
            AbstractContraptionEntity cannon,
            Object contraption,
            Direction orientation,
            BlockPos start,
            Map<?, ?> blocks,
            Vec3 forward,
            CannonFamily family,
            List<BlockPos> line,
            String cannonType,
            Vec3 muzzle
    ) {
    }

    private record RequiredVelocity(Vec3 velocity) {
    }

    private record TrajectorySample(
            Vec3 position,
            double errorSqr,
            int tick
    ) {
    }

    private enum CannonFamily {
        BIG_CANNON("big_cannon"),
        AUTOCANNON("autocannon"),
        TWIN_AUTOCANNON("twin_autocannon"),
        HEAVY_AUTOCANNON("heavy_autocannon"),
        ROTARY_CANNON("rotary_cannon"),
        MEDIUM_CANNON("medium_cannon"),
        ROCKET_POD("rocket_pod"),
        MEDIUM_ROCKET_POD("medium_rocket_pod");

        private final String id;

        CannonFamily(String id) {
            this.id = id;
        }
    }

    private CannonBallisticsCompat() {
    }
}
