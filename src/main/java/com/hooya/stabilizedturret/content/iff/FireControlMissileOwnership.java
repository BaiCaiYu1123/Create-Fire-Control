package com.hooya.stabilizedturret.content.iff;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.RwrManagedMissiles;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.UUID;

/**
 * Persistent ownership marker shared by every missile guidance implementation.
 * Radar IFF must follow the fire-control device which adopted the missile, not
 * the missile entity type or its current distance from the launcher.
 */
public final class FireControlMissileOwnership {
    private static final String OWNER_REF_TAG = "CreateFireControlIffOwner";
    private static final String OWNER_KIND_TAG = "CreateFireControlIffOwnerKind";
    private static final String CONTROLLER_KIND = "controller";
    private static final String DISPLAY_KIND = "display";

    public static void markController(
            Entity missile, StabilizerControllerBlockEntity controller) {
        if (missile == null || controller == null || controller.getLevel() == null) return;
        missile.getPersistentData().put(OWNER_REF_TAG, BindingRef.capture(
                controller.getLevel(), controller.getBlockPos()).save());
        missile.getPersistentData().putString(OWNER_KIND_TAG, CONTROLLER_KIND);
        RwrManagedMissiles.track(missile, controller.getOwnControlledSubLevelIds());
    }

    public static void markDisplay(
            Entity missile, ConnectedDisplayBlockEntity display) {
        if (missile == null || display == null || display.getLevel() == null) return;
        missile.getPersistentData().put(OWNER_REF_TAG, BindingRef.capture(
                display.getLevel(), display.getBlockPos()).save());
        missile.getPersistentData().putString(OWNER_KIND_TAG, DISPLAY_KIND);
        RwrManagedMissiles.track(missile, display.getOwnStructureIdsForIff());
    }

    public static boolean isFriendly(
            Level radarLevel, Set<String> radarChannels, Entity missile) {
        if (radarLevel == null || radarChannels == null || radarChannels.isEmpty()
                || missile == null || !missile.getPersistentData().contains(OWNER_REF_TAG)) {
            return false;
        }
        Set<UUID> ownerStructure = ownerStructure(radarLevel, missile);
        return IffIdentification.sharesEnabledChannel(
                radarLevel, radarChannels, ownerStructure);
    }

    private static Set<UUID> ownerStructure(Level radarLevel, Entity missile) {
        BindingRef owner = BindingRef.load(
                missile.getPersistentData(), OWNER_REF_TAG);
        if (owner == null) return Set.of();
        String kind = missile.getPersistentData().getString(OWNER_KIND_TAG);
        if (CONTROLLER_KIND.equals(kind)) {
            for (StabilizerControllerBlockEntity controller
                    : ControllerTracker.loadedControllers()) {
                Level ownerLevel = controller == null ? null : controller.getLevel();
                if (controller == null || controller.isRemoved()
                        || !sameWorld(radarLevel, ownerLevel)
                        || !controller.getBlockPos().equals(owner.pos())
                        || !owner.isSameTarget(ownerLevel)) continue;
                return controller.getOwnControlledSubLevelIds();
            }
        } else if (DISPLAY_KIND.equals(kind)) {
            for (ConnectedDisplayBlockEntity display
                    : ConnectedDisplayBlockEntity.loadedDisplays()) {
                Level ownerLevel = display == null ? null : display.getLevel();
                if (display == null || display.isRemoved()
                        || !sameWorld(radarLevel, ownerLevel)
                        || !display.getBlockPos().equals(owner.pos())
                        || !owner.isSameTarget(ownerLevel)) continue;
                return display.getOwnStructureIdsForIff();
            }
        }

        // If the control block is temporarily unloaded, its exact physical
        // sublevel still identifies the launching structure without guessing
        // from missile position or proximity.
        return owner.subLevelId() == null
                ? Set.of() : Set.of(owner.subLevelId());
    }

    private static boolean sameWorld(Level first, Level second) {
        return first != null && second != null
                && first.getServer() == second.getServer()
                && first.dimension().equals(second.dimension());
    }

    private FireControlMissileOwnership() {
    }
}
