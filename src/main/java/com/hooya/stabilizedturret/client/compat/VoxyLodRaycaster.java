package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.CannonProjectileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketFlight;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Optional CPU-side ray query for Voxy's persisted LOD world.
 *
 * <p>Voxy terrain is rendering data rather than a Minecraft chunk, so
 * {@link Level#clip} cannot see it. This bridge deliberately uses reflection:
 * Create: Fire Control remains loadable when Voxy is absent, while the local
 * 0.3.2 rebase can expose its public WorldEngine/WorldSection data without a
 * hard mod dependency.</p>
 */
public final class VoxyLodRaycaster {
    private static final String WORLD_IDENTIFIER =
            "me.cortex.voxy.commonImpl.WorldIdentifier";
    private static final String WORLD_ENGINE =
            "me.cortex.voxy.common.world.WorldEngine";
    private static final String WORLD_SECTION =
            "me.cortex.voxy.common.world.WorldSection";

    /** Voxy 0.3.2 stores block-resolution data at 0 and 16-block voxels at 4. */
    private static final int MAX_LOD = 4;
    private static final int SECTION_EDGE = 32;
    private static final int MAX_SECTION_ACQUISITIONS = 384;
    private static final int MAX_VOXEL_TESTS = 32_768;
    private static final long BLOCK_ID_MASK = ((1L << 20) - 1L) << 27;
    private static final double DDA_EPSILON = 1.0e-7;

    private static volatile Access access;
    private static volatile boolean accessAttempted;
    private static volatile boolean runtimeFailureLogged;
    private static volatile boolean missingEngineLogged;
    private static final AtomicInteger QUERY_DIAGNOSTICS = new AtomicInteger();
    private static final ThreadLocal<String> EXPLICIT_DIAGNOSTIC = new ThreadLocal<>();
    private static boolean configAttempted;
    private static Field configField, subdivisionField;

    /** A whole radar batch shares section references and a single bounded budget. Resident data only: no disk I/O. */
    public static @Nullable RadarBatch radarBatch(Level level, @Nullable VoxyLodView view,
                                                  int voxelBudget, int sectionBudget) {
        Access api = getAccess();
        if (api == null || level == null) return null;
        try {
            Object engine = api.engineForLevel.invoke(null, level);
            if (engine == null) return null;
            Query query = new Query(api, engine, view);
            query.maxVoxelTests = voxelBudget;
            query.maxSectionAcquisitions = sectionBudget;
            query.radarLevel = level;
            query.radar = true;
            query.residentSection = engine.getClass().getMethod("acquireIfCached",
                    int.class, int.class, int.class, int.class);
            query.mapper = engine.getClass().getMethod("getMapper").invoke(engine);
            query.blockStateForId = query.mapper.getClass().getMethod("getBlockStateFromBlockId", int.class);
            return new RadarBatch(query);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                StabilizedTurret.LOGGER.warn("Voxy radar visibility bridge unavailable", failure);
            }
            return null;
        }
    }

    public static final class RadarBatch implements AutoCloseable {
        private final Query query;
        private @Nullable RayHit obstruction;
        private RadarBatch(Query query) { this.query = query; }
        /** null means the budget ran out; unchecked targets must wait. */
        public @Nullable Boolean clear(Vec3 origin, Vec3 target) {
            obstruction = null;
            query.radarMissingRefinement = false;
            Vec3 delta = target.subtract(origin);
            double length = delta.length();
            if (!finite(origin) || !finite(target) || length < 1e-6) return false;
            try {
                Vec3 direction = delta.scale(1 / length);
                SearchResult result = traceLevel(query, origin, direction, MAX_LOD, 0, Math.max(0, length - .2));
                if (result.hitDistance() != null) {
                    obstruction = new RayHit(origin.add(direction.scale(result.hitDistance())), result.voxelBounds());
                    return false;
                }
                return query.radarMissingRefinement ? null : true;
            } catch (QueryBudgetExceeded ignored) {
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                return null;
            }
        }
        public int voxelTests() { return query.voxelTests; }
        public @Nullable RayHit obstruction() { return obstruction; }
        public int sectionAcquisitions() { return query.sectionAcquisitions; }
        @Override public void close() { query.close(); }
    }

    /** A ranging press gets its own log even after routine query diagnostics are exhausted. */
    public static <T> T withDiagnostics(String label, Supplier<T> query) {
        String previous = EXPLICIT_DIAGNOSTIC.get();
        EXPLICIT_DIAGNOSTIC.set(label);
        try { return query.get(); }
        finally {
            if (previous == null) EXPLICIT_DIAGNOSTIC.remove();
            else EXPLICIT_DIAGNOSTIC.set(previous);
        }
    }

    /** Optional live renderer setting; unavailable versions retain the finest-data query. */
    public static double subdivisionSize() {
        try {
            if (!configAttempted) {
                configAttempted = true;
                Class<?> config = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
                configField = config.getField("CONFIG");
                subdivisionField = config.getField("subDivisionSize");
            }
            if (configField != null && subdivisionField != null) {
                Object value = subdivisionField.get(configField.get(null));
                if (value instanceof Number number && number.doubleValue() > 0)
                    return number.doubleValue();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        return Double.NaN;
    }

    /** Immutable hit geometry remains usable after releasing Voxy's section references. */
    public record RayHit(Vec3 point, AABB voxelBounds) {
        /** World-space entrance face, used to transport the sight as its camera moves. */
        public @Nullable Vec3 surfaceNormal(Vec3 direction) {
            if (!finite(direction) || direction.lengthSqr() < 1e-12) return null;
            Vec3 unit = direction.normalize();
            double nearest = Double.POSITIVE_INFINITY, strongest = 0;
            Vec3 normal = null;
            for (int axis = 0; axis < 3; axis++) {
                double step = axis == 0 ? unit.x : axis == 1 ? unit.y : unit.z;
                if (Math.abs(step) < 1e-12) continue;
                double coordinate = axis == 0 ? point.x : axis == 1 ? point.y : point.z;
                double face = axis == 0 ? (step > 0 ? voxelBounds.minX : voxelBounds.maxX)
                        : axis == 1 ? (step > 0 ? voxelBounds.minY : voxelBounds.maxY)
                        : (step > 0 ? voxelBounds.minZ : voxelBounds.maxZ);
                double distance = Math.abs(coordinate - face);
                if (distance > nearest + 1e-6
                        || (Math.abs(distance - nearest) <= 1e-6 && Math.abs(step) <= strongest)) continue;
                nearest = distance;
                strongest = Math.abs(step);
                double sign = step > 0 ? -1 : 1;
                normal = axis == 0 ? new Vec3(sign, 0, 0)
                        : axis == 1 ? new Vec3(0, sign, 0) : new Vec3(0, 0, sign);
            }
            // Starting inside a voxel has no acquired entrance plane.
            return nearest <= 1e-5 ? normal : null;
        }

        /** Re-intersect the cached solid voxel with this frame's rendered muzzle ray. */
        public @Nullable Vec3 ray(Vec3 origin, Vec3 direction, double maxDistance) {
            if (!finite(origin) || !finite(direction) || direction.lengthSqr() < 1e-12
                    || !Double.isFinite(maxDistance) || maxDistance <= 0) return null;
            Vec3 unit = direction.normalize();
            double entry = 0, exit = maxDistance;
            for (int axis = 0; axis < 3; axis++) {
                double start = axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z;
                double step = axis == 0 ? unit.x : axis == 1 ? unit.y : unit.z;
                double min = axis == 0 ? voxelBounds.minX : axis == 1 ? voxelBounds.minY : voxelBounds.minZ;
                double max = axis == 0 ? voxelBounds.maxX : axis == 1 ? voxelBounds.maxY : voxelBounds.maxZ;
                if (Math.abs(step) < 1e-12) {
                    if (start < min || start > max) return null;
                    continue;
                }
                double a = (min - start) / step, b = (max - start) / step;
                entry = Math.max(entry, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (entry > exit) return null;
            }
            return origin.add(unit.scale(entry));
        }
    }

    /**
     * Returns the first visible Voxy terrain point on the ray, or {@code null}
     * when Voxy is absent, has no cached data, or the query budget is reached.
     */
    public static @Nullable Vec3 clip(
            Level level,
            Vec3 origin,
            Vec3 direction,
            double maxDistance
    ) {
        RayHit hit = clipHit(level, origin, direction, maxDistance);
        return hit == null ? null : hit.point();
    }

    /** Full LOD query with the voxel bounds needed for frame-rate marker updates. */
    public static @Nullable RayHit clipHit(
            Level level, Vec3 origin, Vec3 direction, double maxDistance
    ) {
        return clipHit(level, origin, direction, maxDistance, null);
    }

    public static @Nullable RayHit clipHit(
            Level level, Vec3 origin, Vec3 direction, double maxDistance, @Nullable VoxyLodView view
    ) {
        if (level == null || maxDistance <= 0.0 || direction.lengthSqr() < 1.0e-8) {
            return null;
        }
        Access api = getAccess();
        if (api == null) return null;

        Vec3 normalized = direction.normalize();
        try {
            Object engine = api.engineForLevel.invoke(null, level);
            if (engine == null) {
                if (!missingEngineLogged) {
                    missingEngineLogged = true;
                    StabilizedTurret.LOGGER.warn(
                            "Voxy LOD aim bridge is available, but the current level has no WorldEngine"
                    );
                }
                return null;
            }

            try (Query query = new Query(api, engine, view)) {
                // Voxy maintains the top node whenever lower data exists.
                // Starting anywhere finer when the top node is absent would
                // synchronously probe hundreds of empty storage sections.
                SearchResult result = traceLevel(
                        query,
                        origin,
                        normalized,
                        MAX_LOD,
                        0.0,
                        maxDistance
                );
                if (result.hitDistance() != null) {
                    double distance = Math.max(
                            0.0,
                            Math.min(maxDistance, result.hitDistance())
                    );
                    logDiagnostic("hit", distance, query);
                    return new RayHit(origin.add(normalized.scale(distance)), result.voxelBounds());
                }
                logDiagnostic(
                        result.hadKnownData() ? "known miss" : "no LOD data",
                        -1.0,
                        query
                );
            }
        } catch (QueryBudgetExceeded ignored) {
            // A malformed/sparse database must never stall the client thread.
            if (EXPLICIT_DIAGNOSTIC.get() != null || QUERY_DIAGNOSTICS.getAndIncrement() < 8) {
                StabilizedTurret.LOGGER.warn(
                        "Voxy LOD aim query reached its safety budget before finding terrain"
                );
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                StabilizedTurret.LOGGER.warn(
                        "Disabling this Voxy LOD aim query after an incompatible runtime call",
                        failure
                );
            }
        }
        return null;
    }

    /**
     * Traces the native Mianbao rocket trajectory through Voxy's persisted terrain.
     * The complete path shares one bounded section query.
     * Returning {@code null} is the normal optional-compat result when Voxy is
     * absent or when no cached terrain is hit before {@code maxTicks}.
     */
    public static @Nullable Vec3 clipBallistic(
            Level level,
            Vec3 origin,
            Vec3 initialVelocity,
            int maxTicks,
            int safetyTicks,
            double firstDrag,
            double flightDrop
    ) {
        return clipBallistic(level, origin, initialVelocity, maxTicks, safetyTicks,
                firstDrag, flightDrop, MianbaoRocketFlight.ARROW_GRAVITY);
    }

    public static @Nullable Vec3 clipBallistic(
            Level level, Vec3 origin, Vec3 initialVelocity, int maxTicks, int safetyTicks,
            double firstDrag, double flightDrop, double firstGravity
    ) {
        if (level == null || maxTicks <= 0 || origin == null || initialVelocity == null
                || !finite(origin) || !finite(initialVelocity)
                || !Double.isFinite(firstDrag) || !Double.isFinite(flightDrop) || !Double.isFinite(firstGravity)) {
            return null;
        }
        Access api = getAccess();
        if (api == null) return null;

        try {
            Object engine = api.engineForLevel.invoke(null, level);
            if (engine == null) return null;
            try (Query query = new Query(api, engine)) {
                boolean[] hadKnownData = {false};
                Vec3 hit = MianbaoRocketFlight.trace(origin, initialVelocity,
                        maxTicks, safetyTicks, firstDrag, flightDrop, firstGravity,
                        level.getMinBuildHeight() - 64.0, (position, next) -> {
                    Vec3 segment = next.subtract(position);
                    double length = segment.length();
                    if (length > DDA_EPSILON) {
                        SearchResult result;
                        try {
                            result = traceLevel(query, position, segment.scale(1.0 / length),
                                    MAX_LOD, 0.0, length);
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException("Unable to trace Voxy rocket terrain", failure);
                        }
                        hadKnownData[0] |= result.hadKnownData();
                        if (result.hitDistance() != null) {
                            double distance = Math.max(0.0, Math.min(length, result.hitDistance()));
                            logDiagnostic("ballistic hit", distance, query);
                            return position.add(segment.scale(distance / length));
                        }
                    }
                    return null;
                });
                if (hit != null) return hit;
                logDiagnostic(
                        hadKnownData[0] ? "ballistic known miss" : "ballistic no LOD data",
                        -1.0,
                        query
                );
            }
        } catch (QueryBudgetExceeded ignored) {
            if (QUERY_DIAGNOSTICS.getAndIncrement() < 8) {
                StabilizedTurret.LOGGER.warn(
                        "Voxy LOD ballistic query reached its safety budget before finding terrain"
                );
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                StabilizedTurret.LOGGER.warn(
                        "Disabling this Voxy LOD ballistic query after an incompatible runtime call",
                        failure
                );
            }
        }
        return null;
    }

    /** CBC motion with v + a/2, querying loaded terrain and LOD on each swept segment. */
    public static @Nullable Vec3 clipCannonBallistic(
            Level level, Vec3 origin, Vec3 initialVelocity, int maxTicks,
            double gravity, double drag, boolean quadratic,
            BiFunction<Vec3, Vec3, Vec3> loadedContact
    ) {
        if (level == null || !finite(origin) || !finite(initialVelocity)
                || !Double.isFinite(gravity) || !Double.isFinite(drag)) return null;
        Access api = getAccess();
        try {
            Object engine = api == null ? null : api.engineForLevel.invoke(null, level);
            try (Query query = engine == null ? null : new Query(api, engine)) {
                Vec3 position = origin, velocity = initialVelocity;
                for (int tick = 0; tick < Math.min(1200, maxTicks); tick++) {
                    CannonProjectileFlight.Step step = CannonProjectileFlight.step(position, velocity, gravity, drag, quadratic);
                    if (!finite(step.position()) || !finite(step.velocity())) break;
                    Vec3 hit = loadedContact.apply(position, step.position());
                    Vec3 segment = step.position().subtract(position);
                    double length = segment.length();
                    if (query != null && length > DDA_EPSILON) {
                        SearchResult result = traceLevel(query, position, segment.scale(1.0 / length),
                                MAX_LOD, 0.0, length);
                        if (result.hitDistance() != null) {
                            double distance = Math.clamp(result.hitDistance(), 0.0, length);
                            if (hit == null || distance * distance < hit.distanceToSqr(position)) {
                                hit = position.add(segment.scale(distance / length));
                            }
                        }
                    }
                    if (hit != null) return hit;
                    position = step.position();
                    velocity = step.velocity();
                    if (position.y < level.getMinBuildHeight() - 64.0
                            || position.distanceToSqr(origin) > 4096.0 * 4096.0) break;
                }
                return null;
            }
        } catch (QueryBudgetExceeded ignored) {
            // Continue the loaded-world trajectory if optional LOD data exhausted its budget.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                StabilizedTurret.LOGGER.warn("Voxy cannon trajectory query failed", failure);
            }
        }
        return CannonProjectileFlight.trace(origin, initialVelocity, gravity, drag, quadratic,
                maxTicks, level.getMinBuildHeight() - 64.0, loadedContact);
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    /** Bombs share the native unpowered simulation, with one bounded LOD query for the entire flight. */
    public static @Nullable Vec3 clipBombBallistic(Level level,
            com.hooya.stabilizedturret.content.controller.MianbaoBombBallistics.Flight flight) {
        if (level == null || flight == null) return null;
        Access api = getAccess();
        if (api == null) return null;
        try {
            Object engine = api.engineForLevel.invoke(null, level);
            if (engine == null) return null;
            try (Query query = new Query(api, engine)) {
                return com.hooya.stabilizedturret.content.controller.MianbaoBombBallistics.trace(
                        flight, level.getMinBuildHeight() - 64.0, (from, to) -> {
                            Vec3 segment = to.subtract(from);
                            double length = segment.length();
                            if (length < DDA_EPSILON) return null;
                            try {
                                SearchResult result = traceLevel(query, from, segment.scale(1.0 / length), MAX_LOD, 0, length);
                                return result.hitDistance() == null ? null : from.add(segment.scale(Math.clamp(result.hitDistance(), 0.0, length) / length));
                            } catch (ReflectiveOperationException failure) {
                                throw new IllegalStateException("Unable to trace Voxy bomb terrain", failure);
                            }
                        });
            }
        } catch (QueryBudgetExceeded ignored) {
            // Unknown terrain leaves the marker hidden; no chunk loading or guessed ground.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                StabilizedTurret.LOGGER.warn("Voxy bomb trajectory query failed", failure);
            }
        }
        return null;
    }

    private static void logDiagnostic(String result, double distance, Query query) {
        String explicit = EXPLICIT_DIAGNOSTIC.get();
        if (explicit == null && QUERY_DIAGNOSTICS.getAndIncrement() >= 8) return;
        if (explicit != null) {
            StabilizedTurret.LOGGER.info("Voxy {} query: result={} range={} viewDetail={} sections={} voxels={}",
                    explicit, result, distance, query.view != null, query.sectionAcquisitions, query.voxelTests);
            return;
        }
        if (distance >= 0.0) {
            StabilizedTurret.LOGGER.info(
                    "Voxy LOD aim {} at {} blocks ({} sections, {} voxels)",
                    result,
                    String.format(java.util.Locale.ROOT, "%.1f", distance),
                    query.sectionAcquisitions,
                    query.voxelTests
            );
        } else {
            StabilizedTurret.LOGGER.info(
                    "Voxy LOD aim {} ({} sections, {} voxels)",
                    result,
                    query.sectionAcquisitions,
                    query.voxelTests
            );
        }
    }

    private static SearchResult traceLevel(
            Query query,
            Vec3 origin,
            Vec3 direction,
            int levelOfDetail,
            double minimumDistance,
            double maximumDistance
    ) throws ReflectiveOperationException {
        if (maximumDistance < minimumDistance) {
            return SearchResult.MISS_UNKNOWN;
        }

        double cellSize = 1 << levelOfDetail;
        double sampleDistance = Math.min(
                maximumDistance,
                minimumDistance + DDA_EPSILON
        );
        Vec3 sample = origin.add(direction.scale(sampleDistance));
        long cellX = floorToLong(sample.x / cellSize);
        long cellY = floorToLong(sample.y / cellSize);
        long cellZ = floorToLong(sample.z / cellSize);

        int stepX = direction.x > 0.0 ? 1 : direction.x < 0.0 ? -1 : 0;
        int stepY = direction.y > 0.0 ? 1 : direction.y < 0.0 ? -1 : 0;
        int stepZ = direction.z > 0.0 ? 1 : direction.z < 0.0 ? -1 : 0;

        double nextX = boundaryDistance(origin.x, direction.x, cellX, stepX, cellSize);
        double nextY = boundaryDistance(origin.y, direction.y, cellY, stepY, cellSize);
        double nextZ = boundaryDistance(origin.z, direction.z, cellZ, stepZ, cellSize);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(direction.x);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(direction.y);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(direction.z);

        double distance = minimumDistance;
        boolean hadKnownData = false;
        while (distance <= maximumDistance + DDA_EPSILON) {
            SampleState state = query.sample(levelOfDetail, cellX, cellY, cellZ);
            hadKnownData |= state != SampleState.UNKNOWN;

            double exitDistance = Math.min(
                    maximumDistance,
                    Math.min(nextX, Math.min(nextY, nextZ))
            );
            if (state == SampleState.SOLID) {
                if (levelOfDetail == 0 || !query.shouldRefine(levelOfDetail, cellX, cellY, cellZ)) {
                    return solidHit(distance, cellSize, cellX, cellY, cellZ);
                }
                SearchResult child = traceLevel(
                        query,
                        origin,
                        direction,
                        levelOfDetail - 1,
                        Math.max(minimumDistance, distance),
                        Math.min(maximumDistance, exitDistance)
                );
                if (child.hitDistance() != null) return child;
                if (!child.hadKnownData()) {
                    // Only this coarse voxel exists in the database. Match the
                    // geometry Voxy actually renders instead of discarding it.
                    if (query.radar && query.view != null) {
                        // The renderer wants children here. Their CPU data may have been
                        // released after mesh upload; the parent is not proof of a wall.
                        query.radarMissingRefinement = true;
                    } else return solidHit(distance, cellSize, cellX, cellY, cellZ);
                }
            }

            if (exitDistance >= maximumDistance - DDA_EPSILON) break;
            double boundary = Math.min(nextX, Math.min(nextY, nextZ));
            if (nextX <= boundary + DDA_EPSILON) {
                cellX += stepX;
                nextX += deltaX;
            }
            if (nextY <= boundary + DDA_EPSILON) {
                cellY += stepY;
                nextY += deltaY;
            }
            if (nextZ <= boundary + DDA_EPSILON) {
                cellZ += stepZ;
                nextZ += deltaZ;
            }
            distance = boundary;
        }
        return new SearchResult(null, hadKnownData);
    }

    private static SearchResult solidHit(double distance, double size, long x, long y, long z) {
        return new SearchResult(distance, true, new AABB(
                x * size, y * size, z * size, (x + 1) * size, (y + 1) * size, (z + 1) * size));
    }

    private static double boundaryDistance(
            double origin,
            double direction,
            long cell,
            int step,
            double cellSize
    ) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = (step > 0 ? cell + 1.0 : cell) * cellSize;
        double distance = (boundary - origin) / direction;
        return Math.max(0.0, distance);
    }

    private static long floorToLong(double value) {
        long truncated = (long) value;
        return value < truncated ? truncated - 1L : truncated;
    }

    private static @Nullable Access getAccess() {
        if (accessAttempted) return access;
        synchronized (VoxyLodRaycaster.class) {
            if (accessAttempted) return access;
            try {
                ClassLoader loader = VoxyLodRaycaster.class.getClassLoader();
                Class<?> identifier = Class.forName(WORLD_IDENTIFIER, false, loader);
                Class<?> engine = Class.forName(WORLD_ENGINE, false, loader);
                Class<?> section = Class.forName(WORLD_SECTION, false, loader);
                Method rawSectionData;
                try {
                    rawSectionData = section.getMethod("_unsafeGetRawDataArray");
                } catch (NoSuchMethodException ignored) {
                    // The 0.3.2 NeoForge rebase exposes the same backing data
                    // through _rawOrNull() and represents uniform sections with
                    // a null array.
                    rawSectionData = section.getMethod("_rawOrNull");
                }
                access = new Access(
                        identifier.getMethod("ofEngineNullable", Level.class),
                        engine.getMethod(
                                "acquireIfExists",
                                int.class,
                                int.class,
                                int.class,
                                int.class
                        ),
                        rawSectionData,
                        section.getMethod("get", int.class),
                        section.getMethod("release")
                );
                StabilizedTurret.LOGGER.info("Enabled optional Voxy LOD aim bridge");
            } catch (ClassNotFoundException ignored) {
                // Optional dependency is not installed.
                access = null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                access = null;
                StabilizedTurret.LOGGER.warn(
                        "Detected Voxy, but its LOD query API is incompatible",
                        failure
                );
            }
            accessAttempted = true;
            return access;
        }
    }

    private enum SampleState {
        UNKNOWN,
        AIR,
        SOLID
    }

    private record SearchResult(@Nullable Double hitDistance, boolean hadKnownData, @Nullable AABB voxelBounds) {
        private SearchResult(@Nullable Double hitDistance, boolean hadKnownData) {
            this(hitDistance, hadKnownData, null);
        }
        private static final SearchResult MISS_UNKNOWN = new SearchResult(null, false);
    }

    private record Access(
            Method engineForLevel,
            Method acquireSection,
            Method rawSectionData,
            Method sectionValue,
            Method releaseSection
    ) {
    }

    private static final class SectionView {
        private final Object section;
        private final long[] data;
        private final long uniformValue;

        private SectionView(Object section, long[] data) {
            this.section = section;
            this.data = data;
            this.uniformValue = 0L;
        }

        private SectionView(Object section, long uniformValue) {
            this.section = section;
            this.data = null;
            this.uniformValue = uniformValue;
        }
    }

    private static final class Query implements AutoCloseable {
        private static final SectionView EMPTY = new SectionView(null, null);

        private final Access access;
        private final Object engine;
        private final @Nullable VoxyLodView view;
        private final Map<Long, Boolean> refinements = new HashMap<>();
        private final Map<Long, SectionView> sections = new HashMap<>();
        private int sectionAcquisitions;
        private int voxelTests;
        private int maxSectionAcquisitions = MAX_SECTION_ACQUISITIONS;
        private int maxVoxelTests = MAX_VOXEL_TESTS;
        private @Nullable Level radarLevel;
        private boolean radar;
        private boolean radarMissingRefinement;
        private Method residentSection, blockStateForId;
        private Object mapper;
        private final Map<Integer, Boolean> radarBlockers = new HashMap<>();
        private final Map<Long, Boolean> loadedRadarChunks = new HashMap<>();

        private Query(Access access, Object engine) {
            this(access, engine, null);
        }

        private Query(Access access, Object engine, @Nullable VoxyLodView view) {
            this.access = access;
            this.engine = engine;
            this.view = view;
        }

        private boolean shouldRefine(int lod, long x, long y, long z) {
            if (view == null) return true;
            int sx = checkedSectionCoordinate(Math.floorDiv(x, SECTION_EDGE));
            int sy = checkedSectionCoordinate(Math.floorDiv(y, SECTION_EDGE));
            int sz = checkedSectionCoordinate(Math.floorDiv(z, SECTION_EDGE));
            return refinements.computeIfAbsent(sectionKey(lod, sx, sy, sz), ignored -> {
                double size = SECTION_EDGE * (1 << lod);
                return view.shouldRefine(new AABB(sx * size, sy * size, sz * size,
                        (sx + 1) * size, (sy + 1) * size, (sz + 1) * size));
            });
        }

        private SampleState sample(
                int levelOfDetail,
                long voxelX,
                long voxelY,
                long voxelZ
        ) throws ReflectiveOperationException {
            if (++voxelTests > maxVoxelTests) throw QueryBudgetExceeded.INSTANCE;
            // The authoritative server checks real chunks, including moved structures.
            // Old LOD copies of those chunks must not mask a live opening or the radar itself.
            if (radarLevel != null) {
                int x = (int) Math.floorDiv(voxelX << levelOfDetail, 16);
                int z = (int) Math.floorDiv(voxelZ << levelOfDetail, 16);
                if (loadedRadarChunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x, z),
                        ignored -> radarLevel.hasChunk(x, z))) return SampleState.AIR;
            }
            int sectionX = checkedSectionCoordinate(Math.floorDiv(voxelX, SECTION_EDGE));
            int sectionY = checkedSectionCoordinate(Math.floorDiv(voxelY, SECTION_EDGE));
            int sectionZ = checkedSectionCoordinate(Math.floorDiv(voxelZ, SECTION_EDGE));
            long key = sectionKey(levelOfDetail, sectionX, sectionY, sectionZ);
            SectionView view = sections.get(key);
            if (view == null) {
                if (++sectionAcquisitions > maxSectionAcquisitions) {
                    throw QueryBudgetExceeded.INSTANCE;
                }
                Object section = (residentSection == null ? access.acquireSection : residentSection).invoke(
                        engine,
                        levelOfDetail,
                        sectionX,
                        sectionY,
                        sectionZ
                );
                if (section == null) {
                    view = EMPTY;
                } else {
                    Object raw = access.rawSectionData.invoke(section);
                    if (raw instanceof long[] data && data.length >= 32 * 32 * 32) {
                        view = new SectionView(section, data);
                    } else if (raw == null) {
                        // Voxy 0.3.2 stores a uniform section without allocating
                        // its 32^3 array. WorldSection#get(index) returns the
                        // uniform value directly and remains read-only here.
                        Object value = access.sectionValue.invoke(section, 0);
                        if (value instanceof Number number) {
                            view = new SectionView(section, number.longValue());
                        } else {
                            access.releaseSection.invoke(section);
                            view = EMPTY;
                        }
                    } else {
                        access.releaseSection.invoke(section);
                        view = EMPTY;
                    }
                }
                sections.put(key, view);
            }
            if (view == EMPTY) return SampleState.UNKNOWN;

            int localX = Math.floorMod(voxelX, SECTION_EDGE);
            int localY = Math.floorMod(voxelY, SECTION_EDGE);
            int localZ = Math.floorMod(voxelZ, SECTION_EDGE);
            int index = (localY << 10) | (localZ << 5) | localX;
            long voxel = view.data == null ? view.uniformValue : view.data[index];
            if (radar && (voxel & BLOCK_ID_MASK) != 0L) {
                int id = (int) ((voxel & BLOCK_ID_MASK) >>> 27);
                Boolean blocker = radarBlockers.get(id);
                if (blocker == null) {
                    Object state = blockStateForId.invoke(mapper, id);
                    blocker = state instanceof net.minecraft.world.level.block.state.BlockState block
                            && !block.isAir() && !(block.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
                    radarBlockers.put(id, blocker);
                }
                return blocker ? SampleState.SOLID : SampleState.AIR;
            }
            return (voxel & BLOCK_ID_MASK) == 0L
                    ? SampleState.AIR
                    : SampleState.SOLID;
        }

        @Override
        public void close() {
            for (SectionView view : sections.values()) {
                if (view != EMPTY && view.section != null) {
                    try {
                        access.releaseSection.invoke(view.section);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Continue releasing every other acquired section.
                    }
                }
            }
            sections.clear();
        }

        private static int checkedSectionCoordinate(long coordinate) {
            if (coordinate < Integer.MIN_VALUE || coordinate > Integer.MAX_VALUE) {
                throw QueryBudgetExceeded.INSTANCE;
            }
            return (int) coordinate;
        }

        private static long sectionKey(int levelOfDetail, int x, int y, int z) {
            return ((long) levelOfDetail << 60)
                    | ((long) (y & 0xFF) << 52)
                    | ((long) (z & 0xFFFFFF) << 28)
                    | ((long) (x & 0xFFFFFF) << 4);
        }
    }

    private static final class QueryBudgetExceeded extends RuntimeException {
        private static final QueryBudgetExceeded INSTANCE = new QueryBudgetExceeded();

        private QueryBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    private VoxyLodRaycaster() {
    }
}
