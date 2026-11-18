package com.hooya.stabilizedturret.compat.sable;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Fire Control's built-in body-pair no-contact backend.
 *
 * <p>This deliberately mirrors Sable Photomancy/Synaxis: it creates a fully
 * free Sable constraint between two physical bodies and disables contacts on
 * that constraint only.  The bodies therefore still collide with the world
 * and with every body outside the selected pair.</p>
 */
public final class SablePairNoContactCompat {
    private static final String MODERN_CONFIGURATION =
            "dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration";
    private static final String LEGACY_CONFIGURATION =
            "dev.ryanhcode.sable.api.physics.constraint.free.FreeConstraintConfiguration";
    private static final Map<ServerLevel, Map<BodyPair, Object>> HANDLES =
            new WeakHashMap<>();

    private static boolean reflectionResolved;
    private static boolean available;
    private static boolean failureLogged;
    private static Constructor<?> configurationConstructor;
    private static Method addConstraintMethod;
    private static Method contactsMethod;
    private static Method removeMethod;
    private static Method validMethod;

    public static boolean isAvailable() {
        return resolveReflection();
    }

    public static Set<UUID> liveBodyIds(ServerLevel level) {
        Set<UUID> result = new HashSet<>();
        for (SubLevel candidate : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (candidate instanceof ServerSubLevel body && !body.isRemoved()) {
                UUID id = body.getUniqueId();
                if (id != null && !isGround(id)) result.add(id);
            }
        }
        return Set.copyOf(result);
    }

    public static boolean exists(ServerLevel level, UUID first, UUID second) {
        if (!validPair(first, second)) return false;
        BodyPair pair = BodyPair.of(first, second);
        synchronized (HANDLES) {
            Map<BodyPair, Object> handles = HANDLES.get(level);
            Object handle = handles == null ? null : handles.get(pair);
            if (handle == null) return false;
            if (isHandleValid(handle)) return true;
            handles.remove(pair);
            if (handles.isEmpty()) HANDLES.remove(level);
            return false;
        }
    }

    public static boolean put(ServerLevel level, UUID first, UUID second) {
        if (!validPair(first, second) || !resolveReflection()) return false;
        BodyPair pair = BodyPair.of(first, second);
        synchronized (HANDLES) {
            Map<BodyPair, Object> handles = HANDLES.computeIfAbsent(
                    level, ignored -> new HashMap<>());
            Object current = handles.get(pair);
            if (current != null && isHandleValid(current)) return false;
            if (current != null) handles.remove(pair);

            SubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevel firstCandidate = container.getSubLevel(pair.first);
            SubLevel secondCandidate = container.getSubLevel(pair.second);
            if (!(firstCandidate instanceof ServerSubLevel firstBody)
                    || !(secondCandidate instanceof ServerSubLevel secondBody)
                    || firstBody.isRemoved() || secondBody.isRemoved()
                    || firstBody.getMassTracker() == null
                    || secondBody.getMassTracker() == null
                    || firstBody.getMassTracker().getCenterOfMass() == null
                    || secondBody.getMassTracker().getCenterOfMass() == null) {
                if (handles.isEmpty()) HANDLES.remove(level);
                return false;
            }

            try {
                PhysicsPipeline pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
                Object configuration = configurationConstructor.newInstance(
                        new Vector3d(firstBody.getMassTracker().getCenterOfMass()),
                        new Vector3d(secondBody.getMassTracker().getCenterOfMass()),
                        new Quaterniond()
                );
                Object handle = addConstraintMethod.invoke(
                        pipeline, firstBody, secondBody, configuration);
                if (handle == null) {
                    if (handles.isEmpty()) HANDLES.remove(level);
                    return false;
                }
                contactsMethod.invoke(handle, false);
                if (!isHandleValid(handle)) {
                    safeRemove(handle);
                    if (handles.isEmpty()) HANDLES.remove(level);
                    return false;
                }
                handles.put(pair, handle);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (handles.isEmpty()) HANDLES.remove(level);
                logFailure(unwrap(exception));
                return false;
            }
        }
    }

    /** Returns true after the relation is absent, matching Synaxis' API. */
    public static boolean remove(ServerLevel level, UUID first, UUID second) {
        if (!validPair(first, second)) return false;
        synchronized (HANDLES) {
            Map<BodyPair, Object> handles = HANDLES.get(level);
            if (handles == null) return true;
            Object handle = handles.remove(BodyPair.of(first, second));
            if (handles.isEmpty()) HANDLES.remove(level);
            if (handle != null) safeRemove(handle);
            return true;
        }
    }

    public static void clear(ServerLevel level) {
        synchronized (HANDLES) {
            Map<BodyPair, Object> handles = HANDLES.remove(level);
            if (handles == null) return;
            for (Object handle : handles.values()) safeRemove(handle);
        }
    }

    private static boolean isHandleValid(Object handle) {
        if (handle == null || !resolveReflection()) return false;
        try {
            return Boolean.TRUE.equals(validMethod.invoke(handle));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(unwrap(exception));
            return false;
        }
    }

    private static void safeRemove(Object handle) {
        if (handle == null || !resolveReflection()) return;
        try {
            if (Boolean.TRUE.equals(validMethod.invoke(handle))) {
                removeMethod.invoke(handle);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(unwrap(exception));
        }
    }

    private static synchronized boolean resolveReflection() {
        if (reflectionResolved) return available;
        reflectionResolved = true;
        try {
            ClassLoader loader = SablePairNoContactCompat.class.getClassLoader();
            Class<?> configurationClass;
            try {
                configurationClass = Class.forName(MODERN_CONFIGURATION, false, loader);
            } catch (ClassNotFoundException ignored) {
                configurationClass = Class.forName(LEGACY_CONFIGURATION, false, loader);
            }
            configurationConstructor = configurationClass.getConstructor(
                    org.joml.Vector3dc.class,
                    org.joml.Vector3dc.class,
                    org.joml.Quaterniondc.class
            );

            for (Method method : PhysicsPipeline.class.getMethods()) {
                if ("addConstraint".equals(method.getName())
                        && method.getParameterCount() == 3) {
                    addConstraintMethod = method;
                    break;
                }
            }
            if (addConstraintMethod == null) throw new NoSuchMethodException("addConstraint");

            Class<?> handleClass = Class.forName(
                    "dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle",
                    false,
                    loader
            );
            contactsMethod = handleClass.getMethod("setContactsEnabled", boolean.class);
            removeMethod = handleClass.getMethod("remove");
            validMethod = handleClass.getMethod("isValid");
            available = true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            available = false;
            logFailure(exception);
        }
        return available;
    }

    private static Throwable unwrap(Throwable exception) {
        if (exception instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return exception;
    }

    private static void logFailure(Throwable exception) {
        if (failureLogged) return;
        failureLogged = true;
        StabilizedTurret.LOGGER.warn(
                "Unable to use Fire Control's built-in Sable body-pair no-contact backend",
                exception
        );
    }

    private static boolean validPair(UUID first, UUID second) {
        return first != null && second != null
                && !first.equals(second) && !isGround(first) && !isGround(second);
    }

    private static boolean isGround(UUID id) {
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }

    private record BodyPair(UUID first, UUID second) {
        private static BodyPair of(UUID first, UUID second) {
            return first.compareTo(second) <= 0
                    ? new BodyPair(first, second)
                    : new BodyPair(second, first);
        }
    }

    private SablePairNoContactCompat() {
    }
}
