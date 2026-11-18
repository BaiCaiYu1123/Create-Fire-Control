import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

/** One-shot read-only snapshots on the game's own threads; does not transform classes or edit worlds. */
public final class RadarLiveDiagnostics {
    static Instrumentation instrumentation;
    static final Map<String, Class<?>> classes = new HashMap<>();
    static final String ROOT = "com.hooya.stabilizedturret.";
    static String output;
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        instrumentation = inst; output = args;
        for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName();
            if (name.startsWith(ROOT) || name.equals("net.minecraft.client.Minecraft")
                    || name.equals("net.neoforged.neoforge.server.ServerLifecycleHooks")
                    || name.equals("dev.ryanhcode.sable.api.sublevel.SubLevelContainer")) classes.put(name, type);
        }
        Object mc = call(type("net.minecraft.client.Minecraft"), "getInstance");
        Object server = call(type("net.neoforged.neoforge.server.ServerLifecycleHooks"), "getCurrentServer");
        if (server == null) ((Executor) mc).execute(() -> client(mc));
        else ((Executor) server).execute(() -> {
            StringBuilder text = new StringBuilder();
            try {
                for (Object controller : (Iterable<?>) call(type(ROOT + "content.controller.ControllerTracker"), "loadedControllers")) {
                    if (!Boolean.TRUE.equals(call(controller, "isRadarActive"))) continue;
                    text.append("CONTROLLER ").append(call(controller, "getBlockPos")).append(" origin=").append(call(controller, "getRadarOrigin"))
                            .append(" aircraft=").append(call(controller, "isAircraftRadarMode"))
                            .append(" range=").append(call(controller, "getAircraftRadarRange"))
                            .append(" forward=").append(call(controller, "getAircraftRadarForward"))
                            .append(" minSpeed=").append(call(controller, "getAircraftRadarMinimumTargetSpeed"))
                            .append(" own=").append(call(controller, "getOwnControlledSubLevelIds"))
                            .append(" lock=").append(call(controller, "getRadarLockedSubLevelId")).append('\n');
                    text.append("CONTACTS ").append(call(controller, "getRadarContacts")).append('\n');
                    text.append("GUNNER ").append(call(controller, "findSeatedGunner")).append('\n');
                    Object visibility = field(controller, "radarVisibility");
                    text.append("SAMPLES ").append(field(visibility, "samples")).append('\n');
                    text.append("PENDING ").append(((Map<?, ?>) field(visibility, "pending")).size()).append('\n');
                    Object level = call(controller, "getLevel");
                    Object container = call(type("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"), "getContainer", level);
                    Object origin = call(controller, "getRadarOrigin");
                    int seen = 0;
                    for (Object body : (Iterable<?>) call(container, "getAllSubLevels")) {
                        if (++seen > 200) break;
                        Object bounds = call(type(ROOT + "content.controller.StabilizerControllerBlockEntity"), "subLevelWorldBounds", body);
                        Object center = call(bounds, "getCenter");
                        double distance = ((Number) call(origin, "distanceTo", center)).doubleValue();
                        if (distance > 500 && distance < 20_000)
                            text.append("BODY ").append(call(body, "getUniqueId")).append(" distance=").append(distance).append(" bounds=").append(bounds).append('\n');
                    }
                }
                for (Object terrain : ((Map<?, ?>) field(type(ROOT + "content.controller.RadarVisibility"), "WORLDS")).values()) {
                    Map<?, ?> saved = (Map<?, ?>) field(terrain, "saved"); int ready = 0, pending = 0, invalid = 0;
                    text.append("WORLD tick=").append(field(terrain, "tick")).append(" remaining=").append(field(terrain, "remaining"))
                            .append(" reads=").append(field(terrain, "reads")).append(" scanners=").append(field(terrain, "scanners")).append('\n');
                    for (var entry : saved.entrySet()) {
                        var future = (java.util.concurrent.CompletableFuture<?>) field(entry.getValue(), "future");
                        if (!future.isDone()) pending++;
                        else if (future.getNow(null) == null) {
                            if (invalid++ < 12) {
                                long key = (Long) entry.getKey();
                                text.append("INVALID_CHUNK ").append((int) key).append(',').append((int) (key >>> 32))
                                        .append(" requested=").append(field(entry.getValue(), "requested")).append('\n');
                            }
                        } else ready++;
                    }
                    text.append("TERRAIN saved=").append(saved.size()).append(" ready=").append(ready).append(" invalid=").append(invalid).append(" pending=").append(pending).append('\n');
                }
            } catch (Throwable error) { text.append(error).append('\n'); }
            write(".server.txt", text);
            ((Executor) mc).execute(() -> client(mc));
        });
    }
    static void client(Object mc) {
        StringBuilder text = new StringBuilder();
        try {
            Class<?> state = type(ROOT + "client.ClientRadarState");
            Object player = field(mc, "player");
            text.append("clientClassLoader=").append(state.getClassLoader()).append(" active=").append(call(state, "isActive"))
                    .append(" player=").append(player).append(" vehicle=").append(player == null ? null : call(player, "getVehicle")).append('\n');
            Object origin = field(state, "origin"), level = field(mc, "level");
            text.append("origin=").append(origin).append(" range=").append(field(state, "range"))
                    .append(" rawLock=").append(field(state, "lockedTarget")).append('\n');
            List<?> raw = (List<?>) field(state, "contacts");
            text.append("RAW ").append(raw).append('\n');
            text.append("FILTERED ").append(call(state, "getContacts")).append('\n');
            Class<?> clientVisibility = type(ROOT + "client.ClientRadarVisibility");
            text.append("clientBudget voxels=").append(field(clientVisibility, "voxels"))
                    .append(" sections=").append(field(clientVisibility, "sections"))
                    .append(" caches=").append(field(clientVisibility, "CACHES")).append('\n');
            if (level != null && !raw.isEmpty()) {
                Object view = call(type(ROOT + "client.ClientAimState"), "lodView", mc);
                for (int i = 0; i < Math.min(3, raw.size()); i++) {
                    Object contact = raw.get(i), target = call(contact, "center");
                    Object batch = call(type(ROOT + "client.compat.VoxyLodRaycaster"), "radarBatch", level, view, 4096, 64);
                    if (batch == null) { text.append("NO VOXY ENGINE\n"); break; }
                    try {
                        Object delta = call(target, "subtract", origin);
                        double distance = ((Number) call(delta, "length")).doubleValue();
                        Object direction = call(delta, "scale", 1 / distance);
                        Object query = field(batch, "query");
                        Object hit = call(type(ROOT + "client.compat.VoxyLodRaycaster"), "traceLevel", query, origin, direction, 4, 0.0, distance - .2);
                        text.append("VOXY ").append(call(contact, "id")).append(' ').append(hit)
                                .append(" blockIds=").append(field(query, "radarBlockers")).append('\n');
                        Object mapper = field(query, "mapper");
                        for (Object id : ((Map<?, ?>) field(query, "radarBlockers")).keySet())
                            text.append("MATERIAL ").append(id).append(' ').append(call(mapper, "getBlockStateFromBlockId", id)).append('\n');
                    } catch (Throwable error) { text.append("VOXY ").append(error).append('\n'); }
                    finally { ((AutoCloseable) batch).close(); }
                }
            }
        } catch (Throwable error) { text.append(error).append('\n'); }
        write(".client.txt", text);
        classes.clear();
    }
    static Class<?> type(String name) {
        Class<?> found = classes.get(name);
        if (found != null) return found;
        try {
            Class<?> state = classes.get(ROOT + "client.ClientRadarState");
            found = Class.forName(name, false, state.getClassLoader());
            classes.put(name, found); return found;
        } catch (Exception error) { throw new IllegalStateException(name, error); }
    }
    static void open(Class<?> type) {
        Module module = type.getModule();
        if (module.isNamed() && !module.isOpen(type.getPackageName(), RadarLiveDiagnostics.class.getModule()))
            instrumentation.redefineModule(module, Set.of(), Map.of(), Map.of(type.getPackageName(), Set.of(RadarLiveDiagnostics.class.getModule())), Set.of(), Map.of());
    }
    static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { open(current); Field field = current.getDeclaredField(name); field.setAccessible(true); return field.get(owner instanceof Class<?> ? null : owner); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    static Object call(Object owner, String name, Object... args) throws Exception {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                boolean matches = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> p = method.getParameterTypes()[i];
                    if (args[i] != null && !(p.isPrimitive() ? args[i] instanceof Number || args[i] instanceof Boolean : p.isInstance(args[i]))) matches = false;
                }
                if (!matches) continue;
                open(current); method.setAccessible(true);
                return method.invoke(owner instanceof Class<?> ? null : owner, args);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    static void write(String suffix, StringBuilder text) {
        try { Files.writeString(Path.of(output + suffix), text); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
}
