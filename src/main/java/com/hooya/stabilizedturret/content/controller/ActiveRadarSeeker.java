package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Independent, assigned-target seeker. World queries never load chunks or switch targets. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ActiveRadarSeeker {
    public static final String TAG = "CreateFireControlActiveRadarSeeker";
    public static final double RANGE = 12_000.0;
    private static final double MIN_FORWARD_COSINE = Math.cos(Math.toRadians(85.0));
    private static final Map<ServerLevel, WorldSamples> WORLD_SAMPLES = new IdentityHashMap<>();

    public enum AimPoint { BOUNDS, PHYSICAL_CENTER }
    public record Snapshot(Vec3 center, Vec3 velocity, AABB bounds, Set<UUID> members, long tick) {}
    record Observation(Vec3 center, @Nullable Vec3 velocity, AABB bounds, Set<UUID> members) {}
    private record Key(UUID target, Set<UUID> members, AimPoint aimPoint) {}
    private static final class WorldSamples {
        long tick = Long.MIN_VALUE;
        final Map<Key, Optional<Observation>> targets = new HashMap<>();
    }

    private final UUID target;
    private final AimPoint aimPoint;
    private Set<UUID> members = Set.of();
    private Set<UUID> persistedMembers;
    private @Nullable Snapshot memory;
    private long attemptedAt = Long.MIN_VALUE;
    private boolean locked;

    public ActiveRadarSeeker(UUID target, AimPoint aimPoint, CompoundTag data) {
        this.target = target;
        this.aimPoint = aimPoint;
        CompoundTag saved = data.getCompound(TAG);
        if (!saved.hasUUID("Target") || !saved.getUUID("Target").equals(target) || !saved.getBoolean("HasMemory")) return;
        Set<UUID> ids = new HashSet<>();
        for (Tag value : saved.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) value;
            if (item.hasUUID("Id")) ids.add(item.getUUID("Id"));
        }
        seed(vector(saved, "P"), vector(saved, "V"), new AABB(
                saved.getDouble("MinX"), saved.getDouble("MinY"), saved.getDouble("MinZ"),
                saved.getDouble("MaxX"), saved.getDouble("MaxY"), saved.getDouble("MaxZ")), ids, saved.getLong("Tick"));
        attemptedAt = saved.contains("AttemptedAt") ? saved.getLong("AttemptedAt") : saved.getLong("Tick");
        locked = saved.getBoolean("Locked");
    }

    /** The launcher supplies identity and an initial cue only, never ongoing illumination. */
    public void seed(Vec3 center, Vec3 velocity, AABB bounds, Set<UUID> ids, long tick) {
        if (memory != null || !finite(center) || !finite(velocity) || !finite(bounds)) return;
        members = Set.copyOf(ids);
        memory = new Snapshot(center, velocity, bounds, members, tick);
    }

    public @Nullable Snapshot memory() { return memory; }
    public boolean locked() { return locked; }

    public void update(Entity missile) {
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        long now = root.getGameTime();
        sample(now, MianbaoMissileCompat.getWorldPosition(missile),
                MianbaoMissileCompat.getWorldVelocity(missile), () -> worldObservation(root));
        save(missile.getPersistentData());
    }

    boolean sample(long now, Vec3 position, Vec3 forward, Supplier<@Nullable Observation> observations) {
        if (attemptedAt == now) return locked;
        attemptedAt = now;
        locked = false;
        Observation observation = observations.get();
        if (observation == null || !finite(observation.center) || !finite(observation.bounds)
                || !detectable(position, forward, observation.center)) return false;
        // Measure world-space displacement ourselves, including Sable/carrier
        // motion and entities whose local delta movement omits that motion.
        Vec3 velocity = memory != null && now > memory.tick
                ? observation.center.subtract(memory.center).scale(1.0 / (now - memory.tick))
                : observation.velocity != null ? observation.velocity : memory == null ? Vec3.ZERO : memory.velocity;
        if (!finite(velocity)) return false;
        members = Set.copyOf(observation.members);
        memory = new Snapshot(observation.center, velocity, observation.bounds, members, now);
        locked = true;
        return true;
    }

    static boolean detectable(Vec3 position, Vec3 forward, Vec3 target) {
        if (!finite(position) || !finite(forward) || !finite(target)) return false;
        Vec3 relative = target.subtract(position);
        if (relative.lengthSqr() > RANGE * RANGE) return false;
        if (relative.lengthSqr() < 1.0e-12) return true;
        return forward.lengthSqr() > 1.0e-12
                && forward.normalize().dot(relative.normalize()) >= MIN_FORWARD_COSINE;
    }

    public void save(CompoundTag data) {
        CompoundTag saved = data.getCompound(TAG);
        boolean newIdentity = !saved.hasUUID("Target") || !saved.getUUID("Target").equals(target);
        if (newIdentity) { saved = new CompoundTag(); saved.putUUID("Target", target); }
        saved.putBoolean("Locked", locked);
        saved.putLong("AttemptedAt", attemptedAt);
        if (memory != null) {
            saved.putBoolean("HasMemory", true);
            putVector(saved, "P", memory.center); putVector(saved, "V", memory.velocity);
            saved.putLong("Tick", memory.tick);
            saved.putDouble("MinX", memory.bounds.minX); saved.putDouble("MinY", memory.bounds.minY); saved.putDouble("MinZ", memory.bounds.minZ);
            saved.putDouble("MaxX", memory.bounds.maxX); saved.putDouble("MaxY", memory.bounds.maxY); saved.putDouble("MaxZ", memory.bounds.maxZ);
            // Reuse the member list while the assigned structure remains the same.
            if (newIdentity || !members.equals(persistedMembers)) {
                ListTag list = new ListTag();
                for (UUID id : members) { CompoundTag item = new CompoundTag(); item.putUUID("Id", id); list.add(item); }
                saved.put("Members", list); persistedMembers = members;
            }
        }
        data.put(TAG, saved);
    }

    private @Nullable Observation worldObservation(ServerLevel root) {
        WorldSamples samples = WORLD_SAMPLES.computeIfAbsent(root, ignored -> new WorldSamples());
        long now = root.getGameTime();
        if (samples.tick != now) { samples.targets.clear(); samples.tick = now; }
        return samples.targets.computeIfAbsent(new Key(target, members, aimPoint),
                ignored -> Optional.ofNullable(resolve(root))).orElse(null);
    }

    private @Nullable Observation resolve(ServerLevel root) {
        var container = SubLevelContainer.getContainer(root);
        Set<UUID> ids = members.isEmpty() ? Set.of(target) : members;
        AABB bounds = null;
        Vec3 weighted = Vec3.ZERO, unweighted = Vec3.ZERO;
        double totalMass = 0;
        int centers = 0;
        for (UUID id : ids) {
            SubLevel part = container.getSubLevel(id);
            if (part == null || part.isRemoved()) continue;
            var local = part.getPlot().getBoundingBox();
            if (local.minX() > local.maxX() || local.minY() > local.maxY() || local.minZ() > local.maxZ()) continue;
            AABB box = StabilizerControllerBlockEntity.subLevelWorldBounds(part);
            if (!finite(box)) continue;
            bounds = bounds == null ? box : bounds.minmax(box);
            Vec3 center = SableMissileFuze.physicalCenterOfMass(part);
            if (center != null && finite(center)) {
                unweighted = unweighted.add(center); centers++;
                double mass = part instanceof ServerSubLevel server && server.getMassTracker() != null
                        ? server.getMassTracker().getMass() : 0;
                if (Double.isFinite(mass) && mass > 1e-8) { weighted = weighted.add(center.scale(mass)); totalMass += mass; }
            }
        }
        if (bounds != null) {
            Vec3 center = aimPoint == AimPoint.BOUNDS ? bounds.getCenter() : totalMass > 1e-8
                    ? weighted.scale(1.0 / totalMass) : centers > 0 ? unweighted.scale(1.0 / centers) : bounds.getCenter();
            return new Observation(center, null, bounds, ids);
        }
        Entity entity = MathematicalMissileManager.find(root, target);
        if (entity == null) entity = root.getEntity(target);
        if (entity == null) for (SubLevel part : container.getAllSubLevels()) {
            if (part != null && !part.isRemoved() && part.getLevel() instanceof ServerLevel source) {
                entity = source.getEntity(target);
                if (entity != null) break;
            }
        }
        if (entity == null || entity.isRemoved() || !entity.isAlive()) return null;
        Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
        // Entity origins and bounding boxes may be expressed in a Sable plot.
        AABB local = entity.getBoundingBox(), box = null;
        for (int i = 0; i < 8; i++) {
            Vec3 point = Sable.HELPER.projectOutOfSubLevel(entity.level(), new Vec3(
                    (i & 1) == 0 ? local.minX : local.maxX,
                    (i & 2) == 0 ? local.minY : local.maxY,
                    (i & 4) == 0 ? local.minZ : local.maxZ));
            AABB corner = new AABB(point, point);
            box = box == null ? corner : box.minmax(corner);
        }
        return new Observation(center, MianbaoMissileCompat.getWorldVelocity(entity), box, Set.of());
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private static boolean finite(AABB box) {
        return box != null && Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }
    private static Vec3 vector(CompoundTag tag, String prefix) {
        return new Vec3(tag.getDouble(prefix + "X"), tag.getDouble(prefix + "Y"), tag.getDouble(prefix + "Z"));
    }
    private static void putVector(CompoundTag tag, String prefix, Vec3 vector) {
        tag.putDouble(prefix + "X", vector.x); tag.putDouble(prefix + "Y", vector.y); tag.putDouble(prefix + "Z", vector.z);
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { WORLD_SAMPLES.clear(); }
}
