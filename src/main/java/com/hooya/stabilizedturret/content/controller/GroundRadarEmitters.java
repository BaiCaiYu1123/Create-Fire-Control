package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Loaded, bound, emitting ground radars. Never discovers targets by loading chunks. */
public final class GroundRadarEmitters {
    public static List<BindingRef> active(Level level) {
        Set<BindingRef> radars = new LinkedHashSet<>();
        for (var controller : ControllerTracker.loadedControllers()) {
            Level owner = controller.getLevel();
            if (controller.isRemoved() || !sameWorld(level, owner)
                    || controller.isAircraftRadarActive()) continue;
            BindingRef radar = controller.getBinding(BindingSlot.RADAR_BEARING);
            if (isEmitting(level, radar)) radars.add(radar);
        }
        radars.addAll(ConnectedDisplayBlockEntity.activeGroundRadarBindings(level));
        return List.copyOf(radars);
    }

    public static boolean sameWorld(Level a, @Nullable Level b) {
        return a != null && b != null && !b.isClientSide && a.getServer() == b.getServer()
                && a.dimension().equals(b.dimension());
    }

    public static boolean isEmitting(Level level, @Nullable BindingRef radar) {
        return radar != null && radar.resolve(level) instanceof MechanicalBearingBlockEntity bearing
                && bearing.isRunning() && bearing.getMovedContraption() != null
                && Math.abs(bearing.getAngularSpeed()) > 1.0e-3F;
    }

    public static @Nullable Vec3 position(Level level, @Nullable BindingRef radar) {
        return radar == null || !isEmitting(level, radar) ? null : radar.worldCenter(level);
    }

    public static @Nullable Vec3 boundPosition(Level level, @Nullable BindingRef radar) {
        return radar != null && active(level).contains(radar) ? position(level, radar) : null;
    }

    public static boolean own(BindingRef radar, StabilizerControllerBlockEntity receiver, Set<UUID> own) {
        return radar.equals(receiver.getBinding(BindingSlot.RADAR_BEARING))
                || radar.subLevelId() != null && own.contains(radar.subLevelId());
    }

    private GroundRadarEmitters() {}
}
