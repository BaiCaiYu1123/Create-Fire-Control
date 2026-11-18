package com.hooya.stabilizedturret.content.iff;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Resolves enabled, case-sensitive IFF channels owned by loaded structures. */
public final class IffIdentification {
    private static final double CONTACT_EPSILON = 1.0e-4;
    private static final double STANDING_EPSILON = 0.05;
    public static Set<String> channelsForStructure(Level level, Set<UUID> structure) {
        if (level == null || structure == null || structure.isEmpty()) return Set.of();
        Set<UUID> completeStructure = IffStructureTopology.expand(level, structure);
        Set<String> channels = new HashSet<>();
        for (StabilizerControllerBlockEntity controller
                : ControllerTracker.loadedControllers()) {
            Level ownerLevel = controller == null ? null : controller.getLevel();
            if (!sameWorld(level, ownerLevel) || controller.isRemoved()
                    || !intersects(completeStructure,
                    controller.getOwnControlledSubLevelIds())) {
                continue;
            }
            collect(ownerLevel, controller.getIffTransponderBindings(), channels);
        }
        for (ConnectedDisplayBlockEntity display
                : ConnectedDisplayBlockEntity.loadedDisplays()) {
            Level ownerLevel = display == null ? null : display.getLevel();
            if (!sameWorld(level, ownerLevel) || display.isRemoved()
                    || !intersects(completeStructure,
                    display.getOwnStructureIdsForIff())) {
                continue;
            }
            collect(ownerLevel, display.getIffTransponderBindings(), channels);
        }
        return channels.isEmpty() ? Set.of() : Set.copyOf(channels);
    }

    public static boolean sharesEnabledChannel(
            Level level, Set<String> sourceChannels, Set<UUID> targetStructure) {
        if (sourceChannels == null || sourceChannels.isEmpty()
                || targetStructure == null || targetStructure.isEmpty()) {
            return false;
        }
        Set<String> targetChannels = channelsForStructure(level, targetStructure);
        for (String channel : sourceChannels) {
            if (targetChannels.contains(channel)) return true;
        }
        return false;
    }

    /**
     * Players touching, overlapping, standing on, or physically living inside
     * a same-channel structure are friendly contacts for this radar.
     */
    public static boolean isFriendlyPlayerContact(
            Level level,
            Set<String> sourceChannels,
            Player player,
            @Nullable UUID containingSubLevel,
            AABB worldBounds
    ) {
        if (level == null || sourceChannels == null || sourceChannels.isEmpty()
                || player == null || worldBounds == null) return false;
        if (containingSubLevel != null && sharesEnabledChannel(
                level, sourceChannels, Set.of(containingSubLevel))) {
            return true;
        }
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()) continue;
            var physical = subLevel.boundingBox();
            AABB structureBounds = new AABB(
                    physical.minX(), physical.minY(), physical.minZ(),
                    physical.maxX(), physical.maxY(), physical.maxZ());
            boolean touching = touchesOrOverlaps(worldBounds, structureBounds,
                    CONTACT_EPSILON);
            boolean standing = player.onGround()
                    && horizontalTouchesOrOverlaps(worldBounds, structureBounds,
                    CONTACT_EPSILON)
                    && worldBounds.minY >= structureBounds.maxY - CONTACT_EPSILON
                    && worldBounds.minY <= structureBounds.maxY + STANDING_EPSILON;
            if ((touching || standing) && sharesEnabledChannel(
                    level, sourceChannels, Set.of(subLevel.getUniqueId()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesOrOverlaps(
            AABB first, AABB second, double epsilon) {
        return horizontalTouchesOrOverlaps(first, second, epsilon)
                && first.maxY + epsilon >= second.minY
                && second.maxY + epsilon >= first.minY;
    }

    private static boolean horizontalTouchesOrOverlaps(
            AABB first, AABB second, double epsilon) {
        return first.maxX + epsilon >= second.minX
                && second.maxX + epsilon >= first.minX
                && first.maxZ + epsilon >= second.minZ
                && second.maxZ + epsilon >= first.minZ;
    }

    private static void collect(
            Level level, Iterable<BindingRef> bindings, Set<String> channels) {
        for (BindingRef binding : bindings) {
            if (binding.resolve(level) instanceof IffTransponderBlockEntity transponder
                    && transponder.isEnabled()) {
                channels.add(transponder.getChannel());
            }
        }
    }

    private static boolean sameWorld(Level first, Level second) {
        return first != null && second != null
                && first.getServer() == second.getServer()
                && first.dimension().equals(second.dimension());
    }

    private static boolean intersects(Set<UUID> first, Set<UUID> second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) {
            return false;
        }
        Set<UUID> smaller = first.size() <= second.size() ? first : second;
        Set<UUID> larger = smaller == first ? second : first;
        for (UUID id : smaller) if (larger.contains(id)) return true;
        return false;
    }

    private IffIdentification() {
    }
}
