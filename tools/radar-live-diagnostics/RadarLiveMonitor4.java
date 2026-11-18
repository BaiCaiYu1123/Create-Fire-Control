import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded read-only observations. No class transforms, forced scans, configuration or world edits. */
public final class RadarLiveMonitor4 {
    static Instrumentation instrumentation;
    static final Map<String, Class<?>> classes = new HashMap<>();
    static final String ROOT = "com.hooya.stabilizedturret.";
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        instrumentation = inst;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith(ROOT) || Set.of("net.minecraft.client.Minecraft",
                    "net.neoforged.neoforge.server.ServerLifecycleHooks",
                    "dev.ryanhcode.sable.api.sublevel.SubLevelContainer").contains(c.getName())) classes.put(c.getName(), c);
        }
        Object mc = call(type("net.minecraft.client.Minecraft"), "getInstance");
        Object server = call(type("net.neoforged.neoforge.server.ServerLifecycleHooks"), "getCurrentServer");
        Path path = Path.of(args);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Radar read-only monitor"); t.setDaemon(true); return t;
        });
        AtomicBoolean outstanding = new AtomicBoolean(); AtomicInteger counter = new AtomicInteger();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        timer.scheduleAtFixedRate(() -> {
            if (System.nanoTime() >= deadline) { timer.shutdown(); return; }
            if (!outstanding.compareAndSet(false, true)) return;
            int number = counter.incrementAndGet();
            Executor executor = (Executor) (server == null ? mc : server);
            executor.execute(() -> {
                StringBuilder s = new StringBuilder("S ").append(number).append(" time=").append(System.currentTimeMillis());
                try {
                    if (server != null) for (Object controller : (Iterable<?>) call(type(ROOT + "content.controller.ControllerTracker"), "loadedControllers")) {
                        if (!Boolean.TRUE.equals(call(controller, "isRadarActive"))) continue;
                        Object level = call(controller, "getLevel"), origin = call(controller, "getRadarOrigin");
                        s.append(" tick=").append(call(level, "getGameTime")).append(" scan=").append(field(controller, "lastRadarScanTick"))
                                .append(" origin=").append(origin).append(" forward=").append(call(controller, "getAircraftRadarForward"))
                                .append(" angles=").append(field(controller, "aircraftRadarHorizontalHalfAngle")).append('/')
                                .append(field(controller, "aircraftRadarVerticalAngle")).append(" lock=").append(field(controller, "radarLockedSubLevelId"))
                                .append(" selected=").append(field(controller, "aircraftSelectedTargetId"))
                                .append(" contacts=").append(field(controller, "radarContacts"));
                        Object visibility = field(controller, "radarVisibility");
                        s.append("\nSAMPLES ").append(field(visibility, "samples"));
                        s.append("\nPENDING");
                        for (var entry : ((Map<?, ?>) field(visibility, "pending")).entrySet())
                            s.append(' ').append(entry.getKey()).append(':').append(field(entry.getValue(), "fraction"))
                                    .append(':').append(field(entry.getValue(), "geometry"));
                        if (number % 5 == 1) {
                            Object container = call(type("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"), "getContainer", level);
                            Map<Object, Object> bodies = new HashMap<>();
                            for (Object body : (Iterable<?>) call(container, "getAllSubLevels")) bodies.put(call(body, "getUniqueId"), body);
                            Set<?> own = (Set<?>) call(controller, "getOwnControlledSubLevelIds");
                            for (Object group : (List<?>) field(controller, "cachedRadarClusters")) {
                                Set<?> members = (Set<?>) group; if (members.stream().anyMatch(own::contains)) continue;
                                Object bounds = null;
                                for (Object id : members) {
                                    Object body = bodies.get(id); if (body == null) continue;
                                    Object part = call(type(ROOT + "content.controller.StabilizerControllerBlockEntity"), "subLevelWorldBounds", body);
                                    bounds = bounds == null ? part : call(type(ROOT + "content.controller.StabilizerControllerBlockEntity"), "unionBounds", bounds, part);
                                }
                                if (bounds != null) {
                                    Object center = call(bounds, "getCenter");
                                    s.append("\nCANDIDATE ").append(members).append(" center=").append(center)
                                            .append(" cone=").append(call(controller, "pointInAircraftRadarCone", origin, center));
                                }
                            }
                        }
                    }
                    for (Object terrain : ((Map<?, ?>) field(type(ROOT + "content.controller.RadarVisibility"), "WORLDS")).values()) {
                        int pending = 0, invalid = 0;
                        for (Object entry : ((Map<?, ?>) field(terrain, "saved")).values()) {
                            var future = (CompletableFuture<?>) field(entry, "future");
                            if (!future.isDone()) pending++; else if (future.getNow(null) == null) invalid++;
                        }
                        s.append("\nTERRAIN tick=").append(field(terrain, "tick")).append(" remaining=").append(field(terrain, "remaining"))
                                .append(" local=").append(field(terrain, "localRemaining")).append(" reads=").append(field(terrain, "reads"))
                                .append(" saved=").append(((Map<?, ?>) field(terrain, "saved")).size())
                                .append(" columns=").append(((Map<?, ?>) field(terrain, "columns")).size())
                                .append(" pending=").append(pending).append(" invalid=").append(invalid);
                    }
                } catch (Throwable error) { s.append(" ERROR ").append(error); }
                append(path, s);
                ((Executor) mc).execute(() -> {
                    StringBuilder c = new StringBuilder("C ").append(number);
                    try {
                        Class<?> state = type(ROOT + "client.ClientRadarState");
                        Object level = field(mc, "level"), raw = field(state, "contacts");
                        c.append(" tick=").append(level == null ? null : call(level, "getGameTime"))
                                .append(" active=").append(field(state, "active")).append(" origin=").append(field(state, "origin"))
                                .append(" lock=").append(field(state, "lockedTarget")).append(" raw=").append(raw);
                        Class<?> cv = type(ROOT + "client.ClientRadarVisibility");
                        Object cache = ((Map<?, ?>) field(cv, "CACHES")).get(field(cv, "CONTROLLER"));
                        c.append(" budget=").append(field(cv, "voxels")).append('/').append(field(cv, "sections"));
                        if (cache != null) c.append(" cacheTick=").append(field(cache, "tick"))
                                .append(" sameInput=").append(field(cache, "input") == raw)
                                .append(" output=").append(field(cache, "output")).append(" samples=").append(field(cache, "samples"));
                    } catch (Throwable error) { c.append(" ERROR ").append(error); }
                    append(path, c); outstanding.set(false);
                });
            });
        }, 0, 150, TimeUnit.MILLISECONDS);
    }
    static void append(Path path, StringBuilder value) {
        try { Files.writeString(path, value.append('\n'), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception ignored) {}
    }
    static Class<?> type(String name) throws Exception {
        Class<?> value = classes.get(name);
        if (value == null) {
            value = Class.forName(name, false, classes.get(ROOT + "client.ClientRadarState").getClassLoader());
            classes.put(name, value);
        }
        return value;
    }
    static void open(Class<?> c) {
        Module m = c.getModule();
        if (m.isNamed() && !m.isOpen(c.getPackageName(), RadarLiveMonitor4.class.getModule()))
            instrumentation.redefineModule(m, Set.of(), Map.of(), Map.of(c.getPackageName(), Set.of(RadarLiveMonitor4.class.getModule())), Set.of(), Map.of());
    }
    static Object field(Object object, String name) throws Exception {
        for (Class<?> c = object instanceof Class<?> k ? k : object.getClass(); c != null; c = c.getSuperclass()) {
            try { open(c); Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object instanceof Class<?> ? null : object); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    static Object call(Object object, String name, Object... args) throws Exception {
        for (Class<?> c = object instanceof Class<?> k ? k : object.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                boolean matches = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> p = m.getParameterTypes()[i];
                    if (args[i] != null && !(p.isPrimitive() ? args[i] instanceof Number || args[i] instanceof Boolean : p.isInstance(args[i]))) matches = false;
                }
                if (matches) { open(c); m.setAccessible(true); return m.invoke(object instanceof Class<?> ? null : object, args); }
            }
        }
        throw new NoSuchMethodException(name);
    }
}
