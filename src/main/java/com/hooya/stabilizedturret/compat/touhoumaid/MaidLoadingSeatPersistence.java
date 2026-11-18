package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.hooya.stabilizedturret.content.loader.MaidCannonLoading;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.List;

/** Keeps a maid out of Sable's remote plot entity regions while the world is saved. */
public final class MaidLoadingSeatPersistence {
    public static void onServerStopping(ServerStoppingEvent event) {
        for (var level : event.getServer().getAllLevels()) {
            List<EntityMaid> maids = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid) maids.add(maid);
            }
            for (EntityMaid maid : maids) prepareForRootWorldSave(maid);
        }
    }

    private static void prepareForRootWorldSave(EntityMaid maid) {
        if (maid.getTask() == null
                || !TaskCannonLoading.UID.equals(maid.getTask().getUid())) return;
        MaidCannonLoading.SeatTarget seat = MaidCannonLoading.currentSeat(maid).orElse(null);
        if (seat == null || !seat.isSableSeat()) return;

        seat.remember(maid);
        Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(
                maid.level(), maid.position());
        Entity vehicle = maid.getVehicle();
        maid.stopRiding();
        if (vehicle instanceof SeatEntity && !vehicle.isVehicle()) vehicle.discard();
        if (maid instanceof EntityMovementExtension movement) {
            movement.sable$setTrackingSubLevel(null);
        }
        if (finite(worldPosition)) maid.setPos(worldPosition);
        maid.setDeltaMovement(Vec3.ZERO);
        EntitySubLevelUtil.setOldPosNoMovement(maid);
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x)
                && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private MaidLoadingSeatPersistence() {
    }
}
