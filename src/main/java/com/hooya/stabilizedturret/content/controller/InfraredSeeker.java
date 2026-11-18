package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Passive assigned-structure seeker; no radar cue, radar memory or IFF query. */
public final class InfraredSeeker {
    public static final String TAG = "CreateFireControlInfraredSeeker";
    public record Track(UUID id, Vec3 center, Vec3 velocity, AABB bounds, Set<UUID> members, long tick) {
        Track withVelocityFrom(@Nullable Track previous) {
            if (previous != null && tick == previous.tick)
                return new Track(id, center, previous.velocity, bounds, members, tick);
            return previous == null || tick < previous.tick ? this : new Track(id, center,
                    center.subtract(previous.center).scale(1.0 / (tick - previous.tick)), bounds, members, tick);
        }
    }
    private final UUID target;
    private final Set<UUID> members;
    private final double tangent;
    private Track memory;
    private long attemptedAt = Long.MIN_VALUE;
    private boolean locked;

    public InfraredSeeker(CompoundTag data) {
        CompoundTag saved = data.getCompound(TAG);
        target = saved.hasUUID("Target") ? saved.getUUID("Target") : null;
        Set<UUID> ids = new HashSet<>();
        for (Tag tag : saved.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag;
            if (entry.hasUUID("Id")) ids.add(entry.getUUID("Id"));
        }
        members = Set.copyOf(ids);
        double angle = saved.getDouble("Tangent");
        tangent = saved.getBoolean("Hemisphere") ? Double.POSITIVE_INFINITY
                : Double.isFinite(angle) && angle > 0 ? angle : InfraredGeometry.GIMBAL_TANGENT;
        if (target != null && saved.getBoolean("HasMemory")) memory = new Track(target,
                vector(saved, "P"), vector(saved, "V"), new AABB(vector(saved, "Min"), vector(saved, "Max")),
                members, saved.getLong("Tick"));
    }
    public static void assign(CompoundTag data, Track track, double tangent) {
        CompoundTag saved = new CompoundTag();
        saved.putUUID("Target", track.id());
        if (tangent == Double.POSITIVE_INFINITY) saved.putBoolean("Hemisphere", true);
        else saved.putDouble("Tangent", tangent);
        ListTag list = new ListTag();
        for (UUID id : track.members()) { CompoundTag item = new CompoundTag(); item.putUUID("Id", id); list.add(item); }
        saved.put("Members", list);
        writeMemory(saved, track);
        data.put(TAG, saved);
        // Saved shots upgraded from the old close-range radar model cannot
        // keep an active radar tracker alongside the infrared seeker.
        data.remove(ActiveRadarSeeker.TAG);
    }
    public @Nullable Track update(Entity missile) {
        long now = missile.level().getGameTime();
        if (attemptedAt == now) return locked ? memory : null;
        attemptedAt = now;
        Track observation = target == null ? null : observe(missile.level(), target, members, now);
        locked = observation != null && detectable(MianbaoMissileCompat.getWorldPosition(missile),
                MianbaoMissileCompat.getWorldVelocity(missile), observation.center(), tangent);
        if (locked) {
            memory = observation.withVelocityFrom(memory);
            CompoundTag saved = missile.getPersistentData().getCompound(TAG);
            writeMemory(saved, memory); missile.getPersistentData().put(TAG, saved);
        }
        return locked ? memory : null;
    }
    static boolean detectable(Vec3 position, Vec3 forward, Vec3 center, double tangent) {
        Vec3 delta = center.subtract(position);
        if (forward.lengthSqr() < 1e-12) return false;
        double depth = delta.dot(forward.normalize());
        if (!Double.isFinite(delta.lengthSqr()) || delta.lengthSqr() > InfraredGeometry.RANGE * InfraredGeometry.RANGE + 1e-6) return false;
        return tangent == Double.POSITIVE_INFINITY ? depth >= -1e-8
                : depth > 0 && delta.lengthSqr() - depth * depth <= depth * depth * tangent * tangent + 1e-8;
    }
    public static @Nullable Track observe(Level level, UUID target, Set<UUID> members, long tick) {
        AABB bounds = null;
        Set<UUID> existing = new HashSet<>();
        var container = SubLevelContainer.getContainer(level);
        for (UUID id : members.isEmpty() ? Set.of(target) : members) {
            var part = container.getSubLevel(id);
            if (part == null || part.isRemoved()) continue;
            var local = part.getPlot().getBoundingBox();
            if (local.minX() > local.maxX() || local.minY() > local.maxY() || local.minZ() > local.maxZ()) continue;
            AABB box = StabilizerControllerBlockEntity.subLevelWorldBounds(part);
            if (!Double.isFinite(box.getCenter().lengthSqr())) continue;
            bounds = bounds == null ? box : bounds.minmax(box);
            existing.add(id);
        }
        return bounds == null ? null : new Track(target, bounds.getCenter(), Vec3.ZERO, bounds, Set.copyOf(existing), tick);
    }
    private static void writeMemory(CompoundTag saved, Track track) {
        saved.putBoolean("HasMemory", true); saved.putLong("Tick", track.tick());
        put(saved, "P", track.center()); put(saved, "V", track.velocity());
        put(saved, "Min", new Vec3(track.bounds().minX, track.bounds().minY, track.bounds().minZ));
        put(saved, "Max", new Vec3(track.bounds().maxX, track.bounds().maxY, track.bounds().maxZ));
    }
    private static Vec3 vector(CompoundTag tag, String key) {
        return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z"));
    }
    private static void put(CompoundTag tag, String key, Vec3 v) {
        tag.putDouble(key + "X", v.x); tag.putDouble(key + "Y", v.y); tag.putDouble(key + "Z", v.z);
    }
}
