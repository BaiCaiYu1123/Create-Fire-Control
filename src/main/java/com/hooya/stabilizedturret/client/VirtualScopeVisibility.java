package com.hooya.stabilizedturret.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Per-viewer visibility; immutable snapshots also serve Flywheel's frame workers. */
public final class VirtualScopeVisibility {
    private record Hidden(Set<UUID> bodies, Set<UUID> entities) {}
    private static final Hidden EMPTY = new Hidden(Set.of(), Set.of());
    private static volatile Hidden hidden = EMPTY;

    public static void update(boolean scoped, Collection<UUID> bodies, Collection<UUID> entities) {
        hidden = scoped ? new Hidden(Set.copyOf(bodies), Set.copyOf(entities)) : EMPTY;
    }

    public static void clear() {
        hidden = EMPTY;
    }

    public static boolean isActive() {
        Hidden snapshot = hidden;
        return viewingScope() && (!snapshot.bodies.isEmpty() || !snapshot.entities.isEmpty());
    }

    public static boolean hides(SubLevel body) {
        return body != null && hidden.bodies.contains(body.getUniqueId()) && viewingScope();
    }

    private static boolean viewingScope() {
        return ClientAimState.isScoped() && !ClientWeaponViewState.isActive()
                && !ClientTelevisionGuidanceState.isActive();
    }

    public static boolean hides(Entity entity) {
        if (!viewingScope()) return false;
        Hidden snapshot = hidden;
        return snapshot.entities.contains(entity.getUUID())
                || (!snapshot.bodies.isEmpty() && hides(Sable.HELPER.getContaining(entity)));
    }

    public static boolean hides(BlockEntity blockEntity) {
        return !hidden.bodies.isEmpty() && hides(Sable.HELPER.getContaining(blockEntity));
    }

    /** Sable creates each block-entity Flywheel embedding at its plot centre. */
    public static boolean hidesEmbedding(Vec3i renderOrigin) {
        var level = Minecraft.getInstance().level;
        return level != null && !hidden.bodies.isEmpty()
                && hides(Sable.HELPER.getContaining(level, new BlockPos(renderOrigin)));
    }

    public static Iterable<ClientSubLevel> visibleBodies(Iterable<ClientSubLevel> bodies) {
        if (hidden.bodies.isEmpty() || !viewingScope()) return bodies;
        ArrayList<ClientSubLevel> visible = new ArrayList<>();
        for (ClientSubLevel body : bodies) if (!hides(body)) visible.add(body);
        return visible;
    }

    private VirtualScopeVisibility() {}
}
