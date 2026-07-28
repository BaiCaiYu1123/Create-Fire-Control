package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

            try (Query query = new Query(api, engine)) {
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
                    return origin.add(normalized.scale(distance));
                }
                logDiagnostic(
                        result.hadKnownData() ? "known miss" : "no LOD data",
                        -1.0,
                        query
                );
            }
        } catch (QueryBudgetExceeded ignored) {
            // A malformed/sparse database must never stall the client thread.
            if (QUERY_DIAGNOSTICS.getAndIncrement() < 8) {
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

    private static void logDiagnostic(String result, double distance, Query query) {
        if (QUERY_DIAGNOSTICS.getAndIncrement() >= 8) return;
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
                if (levelOfDetail == 0) {
                    return new SearchResult(distance, true);
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
                    return new SearchResult(distance, true);
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
                access = new Access(
                        identifier.getMethod("ofEngineNullable", Level.class),
                        engine.getMethod(
                                "acquireIfExists",
                                int.class,
                                int.class,
                                int.class,
                                int.class
                        ),
                        section.getMethod("_unsafeGetRawDataArray"),
                        section.getMethod("release")
                );
                StabilizedTurret.LOGGER.info("Enabled optional Voxy LOD aim bridge");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                access = null;
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

    private record SearchResult(@Nullable Double hitDistance, boolean hadKnownData) {
        private static final SearchResult MISS_UNKNOWN = new SearchResult(null, false);
    }

    private record Access(
            Method engineForLevel,
            Method acquireSection,
            Method rawSectionData,
            Method releaseSection
    ) {
    }

    private static final class SectionView {
        private final Object section;
        private final long[] data;

        private SectionView(Object section, long[] data) {
            this.section = section;
            this.data = data;
        }
    }

    private static final class Query implements AutoCloseable {
        private static final SectionView EMPTY = new SectionView(null, null);

        private final Access access;
        private final Object engine;
        private final Map<Long, SectionView> sections = new HashMap<>();
        private int sectionAcquisitions;
        private int voxelTests;

        private Query(Access access, Object engine) {
            this.access = access;
            this.engine = engine;
        }

        private SampleState sample(
                int levelOfDetail,
                long voxelX,
                long voxelY,
                long voxelZ
        ) throws ReflectiveOperationException {
            if (++voxelTests > MAX_VOXEL_TESTS) throw QueryBudgetExceeded.INSTANCE;
            int sectionX = checkedSectionCoordinate(Math.floorDiv(voxelX, SECTION_EDGE));
            int sectionY = checkedSectionCoordinate(Math.floorDiv(voxelY, SECTION_EDGE));
            int sectionZ = checkedSectionCoordinate(Math.floorDiv(voxelZ, SECTION_EDGE));
            long key = sectionKey(levelOfDetail, sectionX, sectionY, sectionZ);
            SectionView view = sections.get(key);
            if (view == null) {
                if (++sectionAcquisitions > MAX_SECTION_ACQUISITIONS) {
                    throw QueryBudgetExceeded.INSTANCE;
                }
                Object section = access.acquireSection.invoke(
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
                    if (!(raw instanceof long[] data) || data.length < 32 * 32 * 32) {
                        access.releaseSection.invoke(section);
                        view = EMPTY;
                    } else {
                        view = new SectionView(section, data);
                    }
                }
                sections.put(key, view);
            }
            if (view == EMPTY) return SampleState.UNKNOWN;

            int localX = Math.floorMod(voxelX, SECTION_EDGE);
            int localY = Math.floorMod(voxelY, SECTION_EDGE);
            int localZ = Math.floorMod(voxelZ, SECTION_EDGE);
            int index = (localY << 10) | (localZ << 5) | localX;
            return (view.data[index] & BLOCK_ID_MASK) == 0L
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
