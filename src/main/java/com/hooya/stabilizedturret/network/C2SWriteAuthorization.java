package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;

/** Shared server-side checks for packets that edit a nearby block entity. */
final class C2SWriteAuthorization {
    private static final double INTERACTION_DISTANCE_SQUARED = 64.0;

    static boolean canEditNearby(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator() || !player.mayBuild()) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)
                || !level.mayInteract(player, pos)) {
            return false;
        }
        return Sable.HELPER.distanceSquaredWithSubLevels(
                level, player.position(), Vec3.atCenterOf(pos))
                <= INTERACTION_DISTANCE_SQUARED;
    }

    /**
     * Authorizes a settings write against the actual loaded controller. The
     * controller position is local to its Sable sublevel, so using that local
     * BlockPos directly against ServerLevel silently rejected moving-vehicle
     * settings and could select an unrelated root-world block entity.
     */
    static boolean canEditNearby(
            ServerPlayer player, StabilizerControllerBlockEntity controller
    ) {
        return canEditNearby(player, (BlockEntity) controller);
    }

    static boolean canEditNearby(ServerPlayer player, BlockEntity blockEntity) {
        if (player.isSpectator() || !player.mayBuild() || blockEntity == null
                || blockEntity.isRemoved() || blockEntity.getLevel() == null
                || blockEntity.getLevel().getServer() != player.getServer()
                || !blockEntity.getLevel().dimension().equals(player.level().dimension())) {
            return false;
        }
        Vec3 localCenter = Vec3.atCenterOf(blockEntity.getBlockPos());
        SubLevel containing = Sable.HELPER.getContaining(blockEntity);
        Vec3 worldCenter;
        if (containing == null) {
            worldCenter = localCenter;
        } else if (containing.isRemoved()) {
            return false;
        } else {
            Vector3d projected = containing.logicalPose().transformPosition(
                    new Vector3d(localCenter.x, localCenter.y, localCenter.z));
            worldCenter = new Vec3(projected.x, projected.y, projected.z);
        }
        if (!Double.isFinite(worldCenter.x) || !Double.isFinite(worldCenter.y)
                || !Double.isFinite(worldCenter.z)
                || player.position().distanceToSqr(worldCenter)
                > INTERACTION_DISTANCE_SQUARED) {
            return false;
        }
        BlockPos worldPos = BlockPos.containing(worldCenter);
        ServerLevel level = player.serverLevel();
        return level.isInWorldBounds(worldPos) && level.isLoaded(worldPos)
                && level.mayInteract(player, worldPos);
    }

    private C2SWriteAuthorization() {
    }
}
