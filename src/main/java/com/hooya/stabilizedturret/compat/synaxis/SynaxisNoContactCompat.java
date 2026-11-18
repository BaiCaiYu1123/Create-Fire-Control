package com.hooya.stabilizedturret.compat.synaxis;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.sable.SablePairNoContactCompat;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Selects the body-pair no-contact backend.
 *
 * <p>When Sable Photomancy is installed, its Synaxis service is called
 * directly.  Without Photomancy, Fire Control's built-in Sable implementation
 * creates the same free constraint with pair contacts disabled.</p>
 */
public final class SynaxisNoContactCompat {
    private static final String PHOTOMANCY_MOD_ID = "sable_schematic_api";
    private static final String PHYSICS_CLASS =
            "com.verr1.synaxis.foundation.physics.SynaxisPhysics";
    private static final String BODY_ID_CLASS =
            "com.verr1.synaxis.foundation.physics.BodyId";
    private static final String ACCESS_CLASS =
            "com.verr1.synaxis.foundation.physics.nocontact.NoContactAccess";

    private static boolean reflectionResolved;
    private static boolean available;
    private static boolean failureLogged;
    private static Constructor<?> bodyIdConstructor;
    private static Method liveBodiesMethod;
    private static Method noContactsMethod;
    private static Method bodyDimensionMethod;
    private static Method bodyUuidMethod;
    private static Method existsMethod;
    private static Method putMethod;
    private static Method removeMethod;

    public static boolean isAvailable() {
        return usePhotomancyBackend() || SablePairNoContactCompat.isAvailable();
    }

    /** Returns every currently live non-ground physical body in this dimension. */
    public static Set<UUID> liveBodyIds(ServerLevel level) {
        if (!usePhotomancyBackend()) {
            return SablePairNoContactCompat.liveBodyIds(level);
        }
        try {
            Object value = liveBodiesMethod.invoke(null, level);
            if (!(value instanceof Iterable<?> bodies)) return Set.of();
            Set<UUID> result = new HashSet<>();
            for (Object body : bodies) {
                if (body == null || !level.dimension().equals(bodyDimensionMethod.invoke(body))) {
                    continue;
                }
                Object id = bodyUuidMethod.invoke(body);
                if (id instanceof UUID uuid && !isGround(uuid)) {
                    result.add(uuid);
                }
            }
            return Set.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return Set.of();
        }
    }

    public static boolean exists(ServerLevel level, UUID first, UUID second) {
        if (!valid(first, second)) return false;
        if (!usePhotomancyBackend()) {
            return SablePairNoContactCompat.exists(level, first, second);
        }
        try {
            Object access = noContactsMethod.invoke(null);
            return Boolean.TRUE.equals(existsMethod.invoke(
                    access,
                    level,
                    bodyId(level, first),
                    bodyId(level, second)
            ));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return false;
        }
    }

    /**
     * Creates a relation and returns true only when this call actually owned
     * the new relation.  ALREADY_EXISTS is deliberately false so a relation
     * made by the player is never claimed and later removed by fire control.
     */
    public static boolean put(ServerLevel level, UUID first, UUID second) {
        if (!valid(first, second)) return false;
        if (!usePhotomancyBackend()) {
            return SablePairNoContactCompat.put(level, first, second);
        }
        try {
            Object access = noContactsMethod.invoke(null);
            Object result = putMethod.invoke(
                    access,
                    level,
                    bodyId(level, first),
                    bodyId(level, second)
            );
            if (!(result instanceof Enum<?> update)) return false;
            return "CREATED".equals(update.name()) || "QUEUED".equals(update.name());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return false;
        }
    }

    /** Returns true after a relation is removed or was already absent. */
    public static boolean remove(ServerLevel level, UUID first, UUID second) {
        if (!valid(first, second)) return false;
        if (!usePhotomancyBackend()) {
            return SablePairNoContactCompat.remove(level, first, second);
        }
        try {
            Object access = noContactsMethod.invoke(null);
            Object result = removeMethod.invoke(
                    access,
                    level,
                    bodyId(level, first),
                    bodyId(level, second)
            );
            if (!(result instanceof Enum<?> update)) return false;
            return "REMOVED".equals(update.name()) || "NOT_FOUND".equals(update.name());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return false;
        }
    }

    public static void clearFallback(ServerLevel level) {
        SablePairNoContactCompat.clear(level);
    }

    public static String backendName() {
        return usePhotomancyBackend() ? "Photomancy/Synaxis" : "built-in Sable";
    }

    public static boolean isPhotomancyBackend() {
        return usePhotomancyBackend();
    }

    private static boolean usePhotomancyBackend() {
        return ModList.get().isLoaded(PHOTOMANCY_MOD_ID) && resolveReflection();
    }

    private static Object bodyId(ServerLevel level, UUID id)
            throws ReflectiveOperationException {
        return bodyIdConstructor.newInstance(level.dimension(), id);
    }

    private static boolean valid(UUID first, UUID second) {
        return first != null && second != null
                && !first.equals(second) && !isGround(first) && !isGround(second);
    }

    private static boolean isGround(UUID id) {
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }

    private static synchronized boolean resolveReflection() {
        if (reflectionResolved) return available;
        reflectionResolved = true;
        try {
            ClassLoader loader = SynaxisNoContactCompat.class.getClassLoader();
            Class<?> physicsClass = Class.forName(PHYSICS_CLASS, false, loader);
            Class<?> bodyIdClass = Class.forName(BODY_ID_CLASS, false, loader);
            Class<?> accessClass = Class.forName(ACCESS_CLASS, false, loader);

            bodyIdConstructor = bodyIdClass.getConstructor(ResourceKey.class, UUID.class);
            liveBodiesMethod = physicsClass.getMethod("liveBodies", Level.class);
            noContactsMethod = physicsClass.getMethod("noContacts");
            bodyDimensionMethod = bodyIdClass.getMethod("dimension");
            bodyUuidMethod = bodyIdClass.getMethod("id");
            existsMethod = accessClass.getMethod(
                    "exists", ServerLevel.class, bodyIdClass, bodyIdClass);
            putMethod = accessClass.getMethod(
                    "put", ServerLevel.class, bodyIdClass, bodyIdClass);
            removeMethod = accessClass.getMethod(
                    "remove", ServerLevel.class, bodyIdClass, bodyIdClass);
            available = true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            available = false;
        }
        return available;
    }

    private static void logFailure(Throwable exception) {
        if (failureLogged) return;
        failureLogged = true;
        StabilizedTurret.LOGGER.warn(
                "Unable to use Photomancy/Synaxis body-pair no-contact constraints; "
                        + "Fire Control will use its built-in Sable backend",
                exception
        );
    }

    private SynaxisNoContactCompat() {
    }
}
