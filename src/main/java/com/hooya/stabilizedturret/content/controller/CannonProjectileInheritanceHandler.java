package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies deterministic pod fire-control ballistics to CBC projectiles.
 *
 * <p>The complete launch vector is replaced, rather than adjusted, so every
 * random spread component produced by CBC or a compatible ammunition add-on
 * is exactly zero. This handler deliberately contains no impact-learning or
 * previous-shot correction system.</p>
 */
public final class CannonProjectileInheritanceHandler {
    private static final String CBC_PROJECTILE =
            "rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile";
    private static final Set<UUID> VERIFY_NEXT_TICK =
            ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> APPLY_RETRIES =
            new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_FAILURE =
            new ConcurrentHashMap<>();
    private static final Map<UUID, LaunchGuard> LAUNCH_GUARDS =
            new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_SUCCESSFUL_PROFILES =
            ConcurrentHashMap.newKeySet();

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity projectile = event.getEntity();
        if (!isCbcProjectile(projectile)) return;
        // Sable fires contraption projectiles from its hidden plot first and
        // moves ("kicks") them into world space before their first tick.
        // Writing a world-space launch vector while the entity still has plot
        // coordinates makes Sable rotate that vector a second time. Depending
        // on the carrier attitude, the first visible packet can therefore
        // show a round leaving the muzzle sideways. Wait for the world-space
        // entity instead of guessing which side of the addEntity hook ran.
        if (isInsideSablePlot(projectile)) {
            APPLY_RETRIES.put(projectile.getUUID(), 4);
            return;
        }
        if (applyDeterministicLaunch(projectile)) {
            // Verify once after all add-on firing code has completed.
            VERIFY_NEXT_TICK.add(projectile.getUUID());
        } else {
            // The round has already been removed from an autocannon breech at
            // join time. Allow mount/controller caches to become available.
            APPLY_RETRIES.put(projectile.getUUID(), 4);
        }
    }

    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        Entity projectile = event.getEntity();
        if (projectile.level().isClientSide()) return;
        UUID id = projectile.getUUID();
        if (VERIFY_NEXT_TICK.remove(id)) {
            if (!applyDeterministicLaunch(projectile)) {
                APPLY_RETRIES.put(id, 3);
            }
            return;
        }

        Integer retries = APPLY_RETRIES.get(id);
        if (retries == null) {
            verifyDeterministicLaunch(projectile);
            return;
        }
        if (applyDeterministicLaunch(projectile)) {
            APPLY_RETRIES.remove(id);
            return;
        }
        if (retries <= 1) {
            APPLY_RETRIES.remove(id);
            String reason = LAST_FAILURE.remove(id);
            StabilizedTurret.LOGGER.warn(
                    "Unable to apply pod cannon zero-spread ballistics to CBC projectile {} "
                            + "class={} level={} pos=({}, {}, {}) reason={}",
                    id,
                    projectile.getClass().getName(),
                    projectile.level().getClass().getName(),
                    format(projectile.getX()),
                    format(projectile.getY()),
                    format(projectile.getZ()),
                    reason == null ? "unknown" : reason
            );
        } else {
            APPLY_RETRIES.put(id, retries - 1);
        }
    }

    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity projectile = event.getEntity();
        if (!isCbcProjectile(projectile)) return;
        Entity.RemovalReason reason = projectile.getRemovalReason();
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return;
        }
        UUID id = projectile.getUUID();
        VERIFY_NEXT_TICK.remove(id);
        APPLY_RETRIES.remove(id);
        LAST_FAILURE.remove(id);
        LAUNCH_GUARDS.remove(id);
    }

    private static boolean applyDeterministicLaunch(Entity projectile) {
        if (isInsideSablePlot(projectile)) {
            return fail(projectile, "awaiting_sable_world_space_projectile");
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForPodControlledCbcProjectile(projectile);
        if (controller == null || controller.getLevel() == null) {
            return fail(projectile,
                    "no_active_controller_or_projected_muzzle_within_32_blocks");
        }
        Vec3 rawTarget = controller.getPodGuidanceTarget();
        if (rawTarget == null) {
            return fail(projectile, "no_pod_guidance_target");
        }

        BindingRef selectedMount = null;
        CannonBallisticsCompat.ShotProfile selectedProfile = null;
        double nearestDistanceSqr = 1024.0;
        Vec3 projectileWorldPosition = projectile.position();
        for (BindingRef candidate : controller.getCannonMountBindings()) {
            // A controller may retain several valid empty mounts. They must
            // not provide cached geometry or win projectile association until
            // a cannon is currently assembled on that exact saved block.
            if (!candidate.isSameTarget(controller.getLevel())
                    || !CannonMountCompat.hasAssembledCannon(
                    controller.getLevel(), candidate)) {
                continue;
            }
            CannonBallisticsCompat.ShotProfile profile =
                    CannonBallisticsCompat.analyzeSpawnedProjectile(
                            controller.getLevel(), candidate, projectile);
            profile = PodControlledCannonBallistics.apply(profile);
            if (profile == null) continue;
            double distanceSqr =
                    profile.muzzle().distanceToSqr(projectileWorldPosition);
            if (distanceSqr <= nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                selectedMount = candidate;
                selectedProfile = profile;
            }
        }
        if (selectedMount == null || selectedProfile == null) {
            return fail(projectile, "no_compatible_bound_mount_profile");
        }

        Vec3 direction = controller.getCannonBallisticLaunchDirection(
                selectedMount, selectedProfile, rawTarget);
        if (direction == null || direction.lengthSqr() < 1.0e-10) {
            return fail(projectile, "ballistic_launch_solution_failed");
        }
        Vec3 worldVelocity = direction.scale(selectedProfile.muzzleSpeed())
                .add(selectedProfile.carrierVelocity());
        if (!isFinite(worldVelocity)) {
            return fail(projectile, "non_finite_launch_velocity");
        }

        // CBC can retain a collision-deferred velocity in this field. Clear it
        // so the base tick cannot restore an older spread-bearing launch.
        writeField(projectile, "nextVelocity", null);
        // At this point Sable has already kicked the projectile out of its
        // hidden plot. Entity position and delta movement are both ordinary
        // world-space values, so no inverse sublevel rotation or carrier
        // subtraction belongs here.
        projectile.setDeltaMovement(worldVelocity);
        projectile.hasImpulse = true;
        invokeCompatible(projectile, "setOrientation", worldVelocity.normalize());
        // The rotary-cannon and several CBC add-ons publish their final
        // launch motion through different hooks. Keep a very short guard over
        // the first two free-flight ticks and correct only a physically
        // impossible angular/speed discontinuity. Gravity and drag continue
        // normally, while a late spread-bearing overwrite can no longer make
        // an occasional round leave sideways.
        LAUNCH_GUARDS.put(projectile.getUUID(), new LaunchGuard(
                advanceVelocity(worldVelocity, selectedProfile),
                selectedProfile.gravity(),
                selectedProfile.drag(),
                selectedProfile.quadraticDrag(),
                2,
                false
        ));
        APPLY_RETRIES.remove(projectile.getUUID());
        LAST_FAILURE.remove(projectile.getUUID());

        String profileKey = selectedProfile.cannonType()
                + "|" + selectedProfile.projectile();
        if (LOGGED_SUCCESSFUL_PROFILES.add(profileKey)) {
            StabilizedTurret.LOGGER.info(
                    "Pod cannon deterministic profile active: cannon={} projectile={} "
                            + "muzzleSpeed={} carrier=({}, {}, {}) spread=0",
                    selectedProfile.cannonType(),
                    selectedProfile.projectile(),
                    format(selectedProfile.muzzleSpeed()),
                    format(selectedProfile.carrierVelocity().x),
                    format(selectedProfile.carrierVelocity().y),
                    format(selectedProfile.carrierVelocity().z)
            );
        }
        return true;
    }

    private static void verifyDeterministicLaunch(Entity projectile) {
        UUID id = projectile.getUUID();
        LaunchGuard guard = LAUNCH_GUARDS.get(id);
        if (guard == null) return;
        Vec3 actual = projectile.getDeltaMovement();
        // A collision/ricochet owns motion from this point onward. CBC stores
        // deferred collision velocity in nextVelocity, so never fight it.
        if (!isFinite(actual) || actual.lengthSqr() < 1.0e-8
                || readField(projectile, "nextVelocity") != null
                || readBooleanField(projectile, "inGround")) {
            LAUNCH_GUARDS.remove(id);
            return;
        }

        Vec3 expected = guard.expectedVelocity();
        double dot = MthClamp.dotNormalized(actual, expected);
        double actualSpeed = actual.length();
        double expectedSpeed = expected.length();
        double relativeSpeedError = expectedSpeed < 1.0e-8
                ? 0.0 : Math.abs(actualSpeed - expectedSpeed) / expectedSpeed;
        boolean overwritten = dot < Math.cos(Math.toRadians(0.35))
                || relativeSpeedError > 0.025;
        Vec3 accepted = actual;
        boolean logged = guard.logged();
        if (overwritten) {
            projectile.setDeltaMovement(expected);
            projectile.hasImpulse = true;
            invokeCompatible(projectile, "setOrientation", expected.normalize());
            writeField(projectile, "nextVelocity", null);
            accepted = expected;
            if (!logged) {
                StabilizedTurret.LOGGER.warn(
                        "Corrected late CBC projectile launch overwrite: id={} "
                                + "angle={}deg speedError={}%",
                        id,
                        format(Math.toDegrees(Math.acos(
                                Math.max(-1.0, Math.min(1.0, dot))))),
                        format(relativeSpeedError * 100.0)
                );
                logged = true;
            }
        }

        int remaining = guard.ticksRemaining() - 1;
        if (remaining <= 0) {
            LAUNCH_GUARDS.remove(id);
        } else {
            LAUNCH_GUARDS.put(id, new LaunchGuard(
                    advanceVelocity(
                            accepted,
                            guard.gravity(),
                            guard.drag(),
                            guard.quadraticDrag()
                    ),
                    guard.gravity(),
                    guard.drag(),
                    guard.quadraticDrag(),
                    remaining,
                    logged
            ));
        }
    }

    private static Vec3 advanceVelocity(
            Vec3 velocity,
            CannonBallisticsCompat.ShotProfile profile
    ) {
        return advanceVelocity(
                velocity, profile.gravity(), profile.drag(),
                profile.quadraticDrag());
    }

    private static Vec3 advanceVelocity(
            Vec3 velocity,
            double gravity,
            double drag,
            boolean quadraticDrag
    ) {
        double speed = velocity.length();
        double dragForce = Math.max(0.0, drag) * speed;
        if (quadraticDrag) dragForce *= speed;
        dragForce = Math.min(dragForce, speed);
        Vec3 acceleration = speed > 1.0e-10
                ? velocity.normalize().scale(-dragForce)
                : Vec3.ZERO;
        return velocity.add(acceleration).add(0.0, gravity, 0.0);
    }

    private static boolean isInsideSablePlot(Entity entity) {
        try {
            return dev.ryanhcode.sable.Sable.HELPER.getContaining(entity) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isCbcProjectile(Entity projectile) {
        for (Class<?> current = projectile.getClass();
             current != null;
             current = current.getSuperclass()) {
            if (CBC_PROJECTILE.equals(current.getName())) return true;
        }
        return false;
    }

    private static boolean fail(Entity projectile, String reason) {
        LAST_FAILURE.put(projectile.getUUID(), reason);
        return false;
    }

    private static boolean isFinite(Vec3 value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static void writeField(Object owner, String name, Object value) {
        Field field = findField(owner.getClass(), name);
        if (field == null) return;
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    private static Object readField(Object owner, String name) {
        Field field = findField(owner.getClass(), name);
        if (field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean readBooleanField(Object owner, String name) {
        Object value = readField(owner, name);
        return value instanceof Boolean bool && bool;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type;
             current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object invokeCompatible(
            Object owner, String name, Object argument
    ) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass();
             current != null;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != 1
                        || (argument != null
                        && !method.getParameterTypes()[0].isAssignableFrom(
                        argument.getClass()))) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(owner, argument);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private record LaunchGuard(
            Vec3 expectedVelocity,
            double gravity,
            double drag,
            boolean quadraticDrag,
            int ticksRemaining,
            boolean logged
    ) {
    }

    private static final class MthClamp {
        private static double dotNormalized(Vec3 first, Vec3 second) {
            double denominator = first.length() * second.length();
            if (denominator < 1.0e-12) return 1.0;
            return Math.max(-1.0, Math.min(1.0, first.dot(second) / denominator));
        }

        private MthClamp() {
        }
    }

    private CannonProjectileInheritanceHandler() {
    }
}
