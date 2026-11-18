package com.hooya.stabilizedturret.content.missile.impact;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Bounded, paced chunk tickets for the terminal impact area only. */
public final class ImpactAreaManager implements AutoCloseable {
    public static final int MAX_RADIUS_CHUNKS = 8;
    public static final int TICKETS_PER_LEVEL_TICK = 2;
    private static final int STALE_AREA_TICKS = 20 * 60 * 6;
    private static final TicketType<UUID> TICKET = TicketType.create(
            "create_fire_control_impact", UUID::compareTo);
    private static final Map<ServerLevel, Budget> BUDGETS = new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<UUID, Area> areas = new HashMap<>();

    public ImpactAreaManager(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
    }

    /** Plans tickets without reading or synchronously loading the destination. */
    public void prepare(UUID id, Vec3 target, int chunkRadius) {
        if (id == null || !finite(target)) return;
        int radius = Math.max(1, Math.min(MAX_RADIUS_CHUNKS, chunkRadius));
        ChunkPos center = chunkAt(target);
        Area current = areas.get(id);
        if (current != null && current.center.equals(center) && current.radius == radius) {
            current.target = target;
            current.lastTouched = level.getGameTime();
            return;
        }
        release(id);
        areas.put(id, new Area(target, center, radius, plan(center, radius),
                level.getGameTime()));
    }

    /** Rebuilds the ticket plan only after a moving target crosses a chunk edge. */
    public void retarget(UUID id, Vec3 target) {
        if (id == null || !finite(target)) return;
        Area area = areas.get(id);
        if (area == null) return;
        if (!area.center.equals(chunkAt(target))) {
            prepare(id, target, area.radius);
        } else {
            area.target = target;
            area.lastTouched = level.getGameTime();
        }
    }

    public void tick() {
        Budget budget = BUDGETS.computeIfAbsent(level, ignored -> new Budget());
        long now = level.getGameTime();
        if (budget.tick != now) {
            budget.tick = now;
            budget.remaining = TICKETS_PER_LEVEL_TICK;
        }
        for (Iterator<Map.Entry<UUID, Area>> iterator = areas.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Area> entry = iterator.next();
            Area area = entry.getValue();
            if (now - area.lastTouched > STALE_AREA_TICKS) {
                removeTickets(entry.getKey(), area);
                iterator.remove();
                continue;
            }
            while (budget.remaining > 0 && area.nextTicket < area.plan.size()) {
                ChunkPos position = area.plan.get(area.nextTicket++);
                if (area.ticketed.add(position)) {
                    level.getChunkSource().addRegionTicket(TICKET, position, 0, entry.getKey(), true);
                    budget.remaining--;
                }
            }
        }
    }

    public boolean ready(UUID id) {
        Area area = areas.get(id);
        if (area == null || area.nextTicket < area.plan.size()) return false;
        for (ChunkPos position : area.plan) {
            if (level.getChunkSource().getChunkNow(position.x, position.z) == null) return false;
        }
        return true;
    }

    /** True when the position lies in the planned horizontal chunk square. */
    public boolean contains(UUID id, Vec3 position) {
        Area area = areas.get(id);
        if (area == null || !finite(position)) return false;
        ChunkPos chunk = chunkAt(position);
        return Math.abs(chunk.x - area.center.x) <= area.radius
                && Math.abs(chunk.z - area.center.z) <= area.radius;
    }

    /** Clips an incoming high-speed segment to the already ticketed square. */
    public Vec3 segmentStart(UUID id, Vec3 from, Vec3 to) {
        Area area = areas.get(id);
        if (area == null || !finite(from) || !finite(to) || contains(id, from)) return from;
        double minX = (double) ((area.center.x - area.radius) << 4);
        double maxX = (double) ((area.center.x + area.radius + 1) << 4) - 1.0e-5;
        double minZ = (double) ((area.center.z - area.radius) << 4);
        double maxZ = (double) ((area.center.z + area.radius + 1) << 4) - 1.0e-5;
        Vec3 delta = to.subtract(from);
        double enter = 0.0;
        double exit = 1.0;
        double[] clipped = clipAxis(from.x, delta.x, minX, maxX, enter, exit);
        if (clipped == null) return to;
        enter = clipped[0]; exit = clipped[1];
        clipped = clipAxis(from.z, delta.z, minZ, maxZ, enter, exit);
        if (clipped == null) return to;
        return from.add(delta.scale(Math.max(0.0, Math.min(1.0, clipped[0]))));
    }

    public void release(UUID id) {
        Area area = areas.remove(id);
        if (area != null) removeTickets(id, area);
    }

    @Override
    public void close() {
        for (UUID id : new ArrayList<>(areas.keySet())) release(id);
    }

    private void removeTickets(UUID id, Area area) {
        for (ChunkPos position : area.ticketed) {
            level.getChunkSource().removeRegionTicket(TICKET, position, 0, id, true);
        }
    }

    private static ChunkPos chunkAt(Vec3 position) {
        return new ChunkPos(((int) Math.floor(position.x)) >> 4,
                ((int) Math.floor(position.z)) >> 4);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static double[] clipAxis(double origin, double delta, double minimum,
                                     double maximum, double enter, double exit) {
        if (Math.abs(delta) < 1.0e-12) {
            return origin < minimum || origin > maximum ? null : new double[]{enter, exit};
        }
        double first = (minimum - origin) / delta;
        double second = (maximum - origin) / delta;
        if (first > second) { double swap = first; first = second; second = swap; }
        enter = Math.max(enter, first);
        exit = Math.min(exit, second);
        return enter <= exit ? new double[]{enter, exit} : null;
    }

    private static List<ChunkPos> plan(ChunkPos center, int radius) {
        List<ChunkPos> result = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = center.x - ring; x <= center.x + ring; x++) {
                for (int z = center.z - ring; z <= center.z + ring; z++) {
                    if (Math.max(Math.abs(x - center.x), Math.abs(z - center.z)) == ring) {
                        result.add(new ChunkPos(x, z));
                    }
                }
            }
        }
        return result;
    }

    private static final class Budget {
        private long tick = Long.MIN_VALUE;
        private int remaining;
    }

    private static final class Area {
        private Vec3 target;
        private final ChunkPos center;
        private final int radius;
        private final List<ChunkPos> plan;
        private final Set<ChunkPos> ticketed = new HashSet<>();
        private long lastTouched;
        private int nextTicket;

        private Area(Vec3 target, ChunkPos center, int radius, List<ChunkPos> plan, long now) {
            this.target = target;
            this.center = center;
            this.radius = radius;
            this.plan = plan;
            this.lastTouched = now;
        }
    }
}
