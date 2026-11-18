package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Exercises the existing hierarchy and reflective section reader without installing Voxy. */
public final class VoxyLodRaycasterChecks {
    public static void main(String[] args) throws Exception {
        for (int blockX : new int[]{24003, -24003}) {
            var engine = new Engine();
            for (int lod = 0; lod <= 4; lod++) solidVoxel(engine, lod, blockX, 70, 0);
            Vec3 direction = new Vec3(Math.signum(blockX), 0, 0);
            var hit = traceGeometry(engine, new Vec3(.5, 70.5, .5), direction, AimRayLimits.LOD_DISTANCE);
            Double distance = hit.distance();
            double face = blockX > 0 ? blockX : blockX + 1;
            require(distance != null && Math.abs(distance - Math.abs(face - .5)) < 1e-6,
                    "distant hierarchy refines to the first block face in both ray directions");
            require(hit.bounds().equals(new AABB(blockX, 70, 0, blockX + 1, 71, 1)),
                    "refined hit retains the exact solid block bounds after releasing sections");
            released(engine);
        }
        var coarse = new Engine();
        solidVoxel(coarse, 4, 24003, 70, 0);
        var coarseHit = traceGeometry(coarse, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0),
                AimRayLimits.LOD_DISTANCE);
        require(Math.abs(coarseHit.distance() - 23999.5) < 1e-6,
                "coarse-only cached terrain remains targetable at its rendered voxel face");
        require(coarseHit.bounds().equals(new AABB(24000, 64, 0, 24016, 80, 16)),
                "coarse-only marker reuses its actual 16-block voxel, not a fabricated fine face");
        released(coarse);

        var refinedAir = new Engine();
        solidVoxel(refinedAir, 4, 24003, 70, 0);
        refinedAir.sections.put(new Key(3, Math.floorDiv(24003, 256), 0, 0), new Section(null, 0));
        require(trace(refinedAir, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0),
                AimRayLimits.LOD_DISTANCE) == null,
                "known finer air cancels a coarse solid instead of fabricating a terrain hit");
        released(refinedAir);
        renderedDetailKeepsVisibleCoarseTerrain(refinedAir);

        var uniform = new Engine();
        uniform.sections.put(new Key(4, 2, 0, 0), new Section(null, 1L << 27));
        require(Math.abs(trace(uniform, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0),
                AimRayLimits.LOD_DISTANCE) - 1023.5) < 1e-6,
                "uniform Voxy sections use get(index) when raw storage is null");
        released(uniform);

        require(trace(new Engine(), new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0),
                AimRayLimits.LOD_DISTANCE) == null, "missing cached data remains a miss");
        require(trace(coarse, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0), 20000) == null,
                "LOD hits stay inside the requested sight distance");
        var budget = new Engine();
        try {
            trace(budget, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0), 1_000_000);
            throw new AssertionError("Unbounded section traversal was accepted");
        } catch (InvocationTargetException expected) {
            require(expected.getCause().getClass().getSimpleName().equals("QueryBudgetExceeded")
                    && budget.acquisitions == 384, "sparse cache traversal stops at the section budget");
        }
        radarFluidsAndBatchBudget();
        System.out.println("PASS: distant Voxy hierarchy, negative coordinates, coarse/uniform sections, known air, no data and query budget");
    }

    private static Double trace(Engine engine, Vec3 origin, Vec3 direction, double max) throws Exception {
        return traceGeometry(engine, origin, direction, max).distance();
    }

    private record TraceGeometry(Double distance, AABB bounds) {}

    private static void renderedDetailKeepsVisibleCoarseTerrain(Engine engine) throws Exception {
        Vec3 origin = new Vec3(.5, 70.5, .5), forward = new Vec3(1, 0, 0);
        var distantView = new VoxyLodView(origin, forward, new Vec3(0, 0, -1), new Vec3(0, 1, 0),
                1.24, .7, .01, 128);
        var coarse = traceGeometry(engine, origin, forward, AimRayLimits.LOD_DISTANCE, distantView);
        require(coarse.distance() != null && Math.abs(coarse.distance() - 23999.5) < 1e-6
                        && coarse.bounds().getXsize() == 16,
                "visible distant coarse terrain is hit even when its finer cached sample is air");
        var zoomed = new VoxyLodView(origin, forward, new Vec3(0, 0, -1), new Vec3(0, 1, 0),
                1.24 / 16, .7 / 16, .01, 128);
        require(traceGeometry(engine, origin, forward, AimRayLimits.LOD_DISTANCE, zoomed).distance() == null,
                "optical zoom refines the same section instead of retaining a stale coarse silhouette");
        var nearView = new VoxyLodView(origin, forward, new Vec3(0, 0, -1), new Vec3(0, 1, 0),
                1.24, .7, .01, 48000);
        require(traceGeometry(engine, origin, forward, AimRayLimits.LOD_DISTANCE, nearView).distance() == null,
                "vanilla seam ring keeps known finer air and never invents a nearby terrain hit");
        require(traceGeometry(new Engine(), origin, forward, AimRayLimits.LOD_DISTANCE, distantView).distance() == null,
                "detail selection does not turn missing cache or sky into terrain");
        released(engine);
    }

    private static TraceGeometry traceGeometry(Engine engine, Vec3 origin, Vec3 direction, double max) throws Exception {
        return traceGeometry(engine, origin, direction, max, null);
    }

    private static TraceGeometry traceGeometry(Engine engine, Vec3 origin, Vec3 direction, double max,
                                               VoxyLodView view) throws Exception {
        return traceGeometry(engine, origin, direction, max, view, false);
    }

    private static TraceGeometry traceGeometry(Engine engine, Vec3 origin, Vec3 direction, double max,
                                               VoxyLodView view, boolean radar) throws Exception {
        Class<?> accessType = Class.forName(VoxyLodRaycaster.class.getName() + "$Access");
        Class<?> queryType = Class.forName(VoxyLodRaycaster.class.getName() + "$Query");
        Constructor<?> accessConstructor = accessType.getDeclaredConstructors()[0];
        accessConstructor.setAccessible(true);
        Object access = accessConstructor.newInstance(Engine.class.getMethod("forLevel", Level.class),
                Engine.class.getMethod("acquireIfExists", int.class, int.class, int.class, int.class),
                Section.class.getMethod("raw"), Section.class.getMethod("get", int.class),
                Section.class.getMethod("release"));
        Constructor<?> queryConstructor = queryType.getDeclaredConstructor(accessType, Object.class, VoxyLodView.class);
        queryConstructor.setAccessible(true);
        Object query = queryConstructor.newInstance(access, engine, view);
        if (radar) configureRadarQuery(query);
        Method trace = VoxyLodRaycaster.class.getDeclaredMethod("traceLevel", queryType, Vec3.class,
                Vec3.class, int.class, double.class, double.class);
        trace.setAccessible(true);
        try {
            Object result = trace.invoke(null, query, origin, direction, 4, 0.0, max);
            Method hitDistance = result.getClass().getDeclaredMethod("hitDistance");
            hitDistance.setAccessible(true);
            Method bounds = result.getClass().getDeclaredMethod("voxelBounds");
            bounds.setAccessible(true);
            return new TraceGeometry((Double) hitDistance.invoke(result), (AABB) bounds.invoke(result));
        } finally { ((AutoCloseable) query).close(); }
    }

    private static void solidVoxel(Engine engine, int lod, int x, int y, int z) {
        solidVoxel(engine, lod, x, y, z, 1);
    }
    private static void solidVoxel(Engine engine, int lod, int x, int y, int z, int id) {
        int cellSize = 1 << lod;
        int vx = Math.floorDiv(x, cellSize), vy = Math.floorDiv(y, cellSize), vz = Math.floorDiv(z, cellSize);
        Key key = new Key(lod, Math.floorDiv(vx, 32), Math.floorDiv(vy, 32), Math.floorDiv(vz, 32));
        Section section = engine.sections.computeIfAbsent(key, ignored -> new Section(new long[32768], 0));
        section.data[(Math.floorMod(vy, 32) << 10) | (Math.floorMod(vz, 32) << 5) | Math.floorMod(vx, 32)] = (long) id << 27;
    }

    private static void configureRadarQuery(Object query) throws Exception {
        set(query, "radar", true);
        set(query, "mapper", new RadarMapper());
        set(query, "blockStateForId", RadarMapper.class.getMethod("getBlockStateFromBlockId", int.class));
        set(query, "residentSection", Engine.class.getMethod("acquireIfCached", int.class, int.class, int.class, int.class));
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void radarFluidsAndBatchBudget() throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var engine = new Engine();
        for (int lod = 0; lod <= 4; lod++) {
            solidVoxel(engine, lod, 64, 70, 0, 1);
            solidVoxel(engine, lod, 96, 70, 0, 2);
            solidVoxel(engine, lod, 128, 70, 0, 3);
        }
        var hit = traceGeometry(engine, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0), 256, null, true);
        require(hit.distance() != null && Math.abs(hit.distance() - 127.5) < 1e-6,
                "radar skips Voxy water and lava and hits the solid terrain behind them");
        require(engine.acquisitions == 0 && engine.cachedAcquisitions > 0,
                "radar uses resident sections without the disk-backed acquireIfExists path");
        released(engine);
        var waterlogged = new Engine();
        for (int lod = 0; lod <= 4; lod++) solidVoxel(waterlogged, lod, 64, 70, 0, 4);
        require(traceGeometry(waterlogged, new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0), 256, null, true).distance() != null,
                "waterlogged Voxy solid remains a blocker");
        released(waterlogged);

        Class<?> accessType = Class.forName(VoxyLodRaycaster.class.getName() + "$Access");
        Class<?> queryType = Class.forName(VoxyLodRaycaster.class.getName() + "$Query");
        var accessCtor = accessType.getDeclaredConstructors()[0]; accessCtor.setAccessible(true);
        Object access = accessCtor.newInstance(Engine.class.getMethod("forLevel", Level.class),
                Engine.class.getMethod("acquireIfExists", int.class, int.class, int.class, int.class),
                Section.class.getMethod("raw"), Section.class.getMethod("get", int.class), Section.class.getMethod("release"));
        var queryCtor = queryType.getDeclaredConstructor(accessType, Object.class, VoxyLodView.class); queryCtor.setAccessible(true);
        var empty = new Engine(); Object query = queryCtor.newInstance(access, empty, null);
        configureRadarQuery(query); set(query, "maxVoxelTests", 80); set(query, "maxSectionAcquisitions", 8);
        var batchCtor = VoxyLodRaycaster.RadarBatch.class.getDeclaredConstructors()[0]; batchCtor.setAccessible(true);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            int done = 0;
            for (int i = 0; i < 20_000; i++) {
                Boolean clear = batch.clear(new Vec3(.5, 70.5, .5), new Vec3(256.5, 70.5, .5));
                if (clear == null) break;
                done++;
            }
            require(done > 0 && done < 20_000 && batch.voxelTests() <= 81,
                    "the whole crowd shares one voxel budget instead of resetting it for every target");
        }
        released(empty);
        checkRwrBatch(engine, queryCtor, access, batchCtor);
        checkClientPendingAndRefinement(queryCtor, access, batchCtor);
        System.out.println("PASS: Voxy radar fluids, waterlogged solids, resident-only reads and shared mass-target budget");
    }

    @SuppressWarnings("unchecked")
    private static void checkClientPendingAndRefinement(Constructor<?> queryCtor, Object access, Constructor<?> batchCtor) throws Exception {
        Class<?> visibility = com.hooya.stabilizedturret.client.ClientRadarVisibility.class;
        Class<?> cacheType = Class.forName(visibility.getName() + "$Cache");
        var cacheCtor = cacheType.getDeclaredConstructor(); cacheCtor.setAccessible(true);
        var filter = visibility.getDeclaredMethod("visibleInBatch", cacheType, Vec3.class, java.util.List.class,
                java.util.function.Function.class, java.util.function.Function.class, java.util.function.Predicate.class,
                long.class, int.class, VoxyLodRaycaster.RadarBatch.class); filter.setAccessible(true);
        var contact = new com.hooya.stabilizedturret.client.ClientRadarState.Threat(java.util.UUID.randomUUID(),
                com.hooya.stabilizedturret.network.RadarSessionPayload.ThreatType.RFL, new Vec3(4096.5, 70.5, .5), false);
        java.util.function.Function<com.hooya.stabilizedturret.client.ClientRadarState.Threat, java.util.UUID> id =
                com.hooya.stabilizedturret.client.ClientRadarState.Threat::id;
        java.util.function.Function<com.hooya.stabilizedturret.client.ClientRadarState.Threat, Vec3> center =
                com.hooya.stabilizedturret.client.ClientRadarState.Threat::position;
        java.util.function.Predicate<Object> requires = ignored -> true;
        var empty = new Engine(); Object query = queryCtor.newInstance(access, empty, null);
        configureRadarQuery(query); set(query, "maxVoxelTests", 0);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            var result = (java.util.List<?>) filter.invoke(null, cacheCtor.newInstance(), new Vec3(.5, 70.5, .5),
                    java.util.List.of(contact), id, center, requires, 10L, 4, batch);
            require(result.contains(contact), "an exhausted client budget preserves a server-confirmed far contact");
        }
        var coarse = new Engine(); solidVoxel(coarse, 4, 2048, 70, 0, 3);
        var refine = new VoxyLodView(new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0), new Vec3(0, 0, 1),
                new Vec3(0, 1, 0), .7, .7, 1e-12, 128);
        query = queryCtor.newInstance(access, coarse, refine); configureRadarQuery(query);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            require(batch.clear(new Vec3(.5, 70.5, .5), contact.position()) == null,
                    "a coarse parent is not a confirmed wall when required finer CPU sections are absent");
        }
        released(coarse);
        var leaf = new VoxyLodView(new Vec3(.5, 70.5, .5), new Vec3(1, 0, 0), new Vec3(0, 0, 1),
                new Vec3(0, 1, 0), .7, .7, 10, 128);
        query = queryCtor.newInstance(access, coarse, leaf); configureRadarQuery(query);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            require(Boolean.FALSE.equals(batch.clear(new Vec3(.5, 70.5, .5), contact.position())),
                    "a rendered coarse leaf still blocks the same far contact");
        }
        released(coarse);
        var solid = new Engine();
        for (int lod = 0; lod <= 4; lod++) solidVoxel(solid, lod, 2048, 70, 0, 3);
        Object cache = cacheCtor.newInstance();
        query = queryCtor.newInstance(access, solid, null); configureRadarQuery(query);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            var result = (java.util.List<?>) filter.invoke(null, cache, new Vec3(.5, 70.5, .5),
                    java.util.List.of(contact), id, center, requires, 10L, 4, batch);
            require(result.isEmpty(), "proved terrain still hides a server contact");
        }
        query = queryCtor.newInstance(access, solid, null); configureRadarQuery(query); set(query, "maxVoxelTests", 0);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            var result = (java.util.List<?>) filter.invoke(null, cache, new Vec3(10.25, 70.5, .5),
                    java.util.List.of(contact), id, center, requires, 11L, 4, batch);
            require(result.isEmpty(), "the cached blocking voxel prevents a blocked target flashing through during movement");
        }
        released(solid);
        System.out.println("PASS: pending client queries preserve confirmed tracks, missing refinements do not invent walls, real LOD blockers stay stable");
    }

    @SuppressWarnings("unchecked")
    private static void checkRwrBatch(Engine engine, Constructor<?> queryCtor, Object access, Constructor<?> batchCtor) throws Exception {
        Class<?> visibility = com.hooya.stabilizedturret.client.ClientRadarVisibility.class;
        Class<?> cacheType = Class.forName(visibility.getName() + "$Cache");
        var cacheCtor = cacheType.getDeclaredConstructor(); cacheCtor.setAccessible(true);
        var emitterField = visibility.getDeclaredField("RWR_EMITTER"); emitterField.setAccessible(true);
        var emitter = (java.util.function.Predicate<com.hooya.stabilizedturret.client.ClientRadarState.Threat>) emitterField.get(null);
        var filter = visibility.getDeclaredMethod("visibleInBatch", cacheType, Vec3.class, java.util.List.class,
                java.util.function.Function.class, java.util.function.Function.class, java.util.function.Predicate.class,
                long.class, int.class, VoxyLodRaycaster.RadarBatch.class);
        filter.setAccessible(true);
        var aaa = new com.hooya.stabilizedturret.client.ClientRadarState.Threat(java.util.UUID.randomUUID(),
                com.hooya.stabilizedturret.network.RadarSessionPayload.ThreatType.AAA, new Vec3(256.5, 70.5, .5), false);
        var rfl = new com.hooya.stabilizedturret.client.ClientRadarState.Threat(java.util.UUID.randomUUID(),
                com.hooya.stabilizedturret.network.RadarSessionPayload.ThreatType.RFL, new Vec3(112.5, 70.5, .5), false);
        var msl = new com.hooya.stabilizedturret.client.ClientRadarState.Threat(java.util.UUID.randomUUID(),
                com.hooya.stabilizedturret.network.RadarSessionPayload.ThreatType.MSL, new Vec3(256.5, 70.5, .5), true);
        java.util.function.Function<com.hooya.stabilizedturret.client.ClientRadarState.Threat, java.util.UUID> id =
                com.hooya.stabilizedturret.client.ClientRadarState.Threat::id;
        java.util.function.Function<com.hooya.stabilizedturret.client.ClientRadarState.Threat, Vec3> position =
                com.hooya.stabilizedturret.client.ClientRadarState.Threat::position;
        Object query = queryCtor.newInstance(access, engine, null); configureRadarQuery(query);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            var result = (java.util.List<?>) filter.invoke(null, cacheCtor.newInstance(), new Vec3(.5, 70.5, .5),
                    java.util.List.of(aaa, rfl, msl), id, position, emitter, 10L, 4, batch);
            require(!result.contains(aaa) && result.contains(rfl) && result.contains(msl),
                    "RWR hides terrain-blocked AAA, receives RFL through fluids, and preserves incoming MSL");
        }
        released(engine);
        query = queryCtor.newInstance(access, engine, null); configureRadarQuery(query); set(query, "maxVoxelTests", 0);
        try (var batch = (VoxyLodRaycaster.RadarBatch) batchCtor.newInstance(query)) {
            var result = (java.util.List<?>) filter.invoke(null, cacheCtor.newInstance(), new Vec3(.5, 70.5, .5),
                    java.util.List.of(msl), id, position, emitter, 10L, 4, batch);
            require(result.equals(java.util.List.of(msl)) && batch.voxelTests() == 0,
                    "MSL warnings survive an exhausted terrain budget without issuing a query");
        }
        System.out.println("PASS: RWR Voxy obstruction, fluid transparency, independent MSL and shared budget");
    }
    public static final class RadarMapper {
        public net.minecraft.world.level.block.state.BlockState getBlockStateFromBlockId(int id) {
            return switch (id) {
                case 1 -> net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
                case 2 -> net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState();
                case 3 -> net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                case 4 -> net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
                default -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            };
        }
    }

    private record Key(int lod, int x, int y, int z) {}
    public static final class Engine {
        private final Map<Key, Section> sections = new HashMap<>();
        private int acquisitions;
        private int cachedAcquisitions;
        public static Engine forLevel(Level ignored) { return null; }
        public Section acquireIfExists(int lod, int x, int y, int z) {
            acquisitions++;
            return acquire(lod, x, y, z);
        }
        public Section acquireIfCached(int lod, int x, int y, int z) {
            cachedAcquisitions++;
            return acquire(lod, x, y, z);
        }
        private Section acquire(int lod, int x, int y, int z) {
            Section section = sections.get(new Key(lod, x, y, z));
            if (section != null) section.acquired++;
            return section;
        }
    }
    public static final class Section {
        private final long[] data;
        private final long uniform;
        private int acquired, released;
        Section(long[] data, long uniform) { this.data = data; this.uniform = uniform; }
        public long[] raw() { return data; }
        public long get(int index) { return data == null ? uniform : data[index]; }
        public void release() { released++; }
    }
    private static void released(Engine engine) {
        for (Section section : engine.sections.values())
            require(section.acquired == section.released, "every acquired section is released after the ray");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private VoxyLodRaycasterChecks() {}
}
