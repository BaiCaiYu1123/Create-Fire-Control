package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Additional visibility against the actual resident Voxy geometry, shared by HUDs and display walls. */
public final class ClientRadarVisibility {
    private static final Object CONTROLLER = new Object();
    private static final Object RWR = new Object();
    private static final Predicate<ClientRadarState.Threat> RWR_EMITTER =
            threat -> threat.type() != com.hooya.stabilizedturret.network.RadarSessionPayload.ThreatType.MSL;
    private static final Map<Object, Cache> CACHES = new WeakHashMap<>();
    private static java.lang.ref.WeakReference<ClientLevel> world = new java.lang.ref.WeakReference<>(null);
    private static long budgetTick = Long.MIN_VALUE;
    private static int voxels, sections;

    public static List<ClientRadarState.Contact> controller(Vec3 origin, List<ClientRadarState.Contact> contacts) {
        return filter(CONTROLLER, origin, contacts, ClientRadarState.Contact::id, ClientRadarState.Contact::center);
    }

    public static List<ClientRadarState.Threat> rwr(Vec3 origin, List<ClientRadarState.Threat> threats) {
        return filter(RWR, origin, threats, ClientRadarState.Threat::id, ClientRadarState.Threat::position, RWR_EMITTER);
    }

    public static List<ConnectedDisplayBlockEntity.Contact> display(ConnectedDisplayBlockEntity display,
                                                                  List<ConnectedDisplayBlockEntity.Contact> contacts) {
        return filter(display, display.getRadarWorldOrigin(), contacts,
                contact -> contact.key().id(), ConnectedDisplayBlockEntity.Contact::center);
    }

    private static <T> List<T> filter(Object owner, Vec3 origin, List<T> contacts,
                                     Function<T, UUID> id, Function<T, Vec3> center) {
        return filter(owner, origin, contacts, id, center, contact -> true);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> filter(Object owner, Vec3 origin, List<T> contacts,
                                     Function<T, UUID> id, Function<T, Vec3> center,
                                     Predicate<T> requiresVisibility) {
        if (!com.hooya.stabilizedturret.config.FireControlSettings.radarRaycastEnabled()) {
            CACHES.clear();
            budgetTick = Long.MIN_VALUE;
            return contacts;
        }
        var level = Minecraft.getInstance().level;
        if (level == null || contacts.isEmpty()) return contacts;
        if (world.get() != level) { world = new java.lang.ref.WeakReference<>(level); CACHES.clear(); budgetTick = Long.MIN_VALUE; }
        long now = level.getGameTime();
        if (budgetTick != now) { budgetTick = now; voxels = 16_384; sections = 384; }
        Cache cache = CACHES.computeIfAbsent(owner, ignored -> new Cache());
        if (cache.tick == now && cache.input == contacts && origin.equals(cache.origin))
            return (List<T>) cache.output;
        Set<UUID> present = new HashSet<>();
        double estimatedSteps = 0;
        for (T contact : contacts) {
            if (!requiresVisibility.test(contact)) continue;
            present.add(id.apply(contact));
            estimatedSteps += origin.distanceTo(center.apply(contact)) / 16;
        }
        int ttl = Math.min(20, Math.max(4, (int) Math.ceil(estimatedSteps / 8192)));
        cache.samples.keySet().retainAll(present);
        if (present.isEmpty()) return contacts;
        List<T> result;
        try (var batch = VoxyLodRaycaster.radarBatch(level, ClientAimState.lodView(Minecraft.getInstance()),
                Math.max(0, voxels), Math.max(0, sections))) {
            if (batch == null) {
                cache.tick = now; cache.input = contacts; cache.origin = origin; cache.output = contacts;
                return contacts;
            }
            result = visibleInBatch(cache, origin, contacts, id, center, requiresVisibility, now, ttl, batch);
            voxels -= batch.voxelTests();
            sections -= batch.sectionAcquisitions();
        }
        cache.tick = now; cache.input = contacts; cache.origin = origin; cache.output = result;
        return result;
    }

    /** RWR missile warnings bypass radio visibility and consume no terrain-query budget. */
    private static <T> List<T> visibleInBatch(Cache cache, Vec3 origin, List<T> contacts,
                                             Function<T, UUID> id, Function<T, Vec3> center,
                                             Predicate<T> requiresVisibility, long now, int ttl,
                                             VoxyLodRaycaster.RadarBatch batch) {
        int count = contacts.size();
        cache.cursor = Math.floorMod(cache.cursor, count);
        for (int i = 0; i < count; i++) {
            T contact = contacts.get(cache.cursor);
            cache.cursor = (cache.cursor + 1) % count;
            if (!requiresVisibility.test(contact)) continue;
            UUID key = id.apply(contact);
            Vec3 point = center.apply(contact);
            Sample old = cache.samples.get(key);
            if (old != null && old.matches(origin, point, now, ttl)) continue;
            Boolean clear = batch.clear(origin, point);
            if (clear == null) break;
            cache.samples.put(key, new Sample(origin, point, now, clear, batch.obstruction()));
        }
        List<T> result = new ArrayList<>();
        for (T contact : contacts) {
            if (!requiresVisibility.test(contact)) { result.add(contact); continue; }
            Sample sample = cache.samples.get(id.apply(contact));
            Vec3 point = center.apply(contact);
            // Every incoming contact has already passed authoritative server visibility.
            // Only a proved LOD obstruction can remove it; missing CPU data or a spent
            // client budget must not erase contacts and toggle the lock every other frame.
            boolean blocked = sample != null && !sample.visible
                    && (sample.matches(origin, point, now, ttl) || sample.stillBlocks(origin, point, now));
            if (!blocked) result.add(contact);
        }
        return List.copyOf(result);
    }

    private record Sample(Vec3 origin, Vec3 center, long tick, boolean visible,
                          VoxyLodRaycaster.RayHit obstruction) {
        boolean matches(Vec3 from, Vec3 target, long now, int ttl) {
            return now >= tick && now - tick < ttl && origin.distanceToSqr(from) < .0625
                    && center.distanceToSqr(target) < .0625;
        }
        boolean stillBlocks(Vec3 from, Vec3 target, long now) {
            Vec3 delta = target.subtract(from);
            return obstruction != null && now >= tick && now - tick <= 12
                    && obstruction.ray(from, delta, delta.length() - .2) != null;
        }
    }
    private static final class Cache {
        private final Map<UUID, Sample> samples = new HashMap<>();
        private int cursor;
        private long tick = Long.MIN_VALUE;
        private Object input;
        private Vec3 origin;
        private List<?> output;
    }
    private ClientRadarVisibility() {}
}
