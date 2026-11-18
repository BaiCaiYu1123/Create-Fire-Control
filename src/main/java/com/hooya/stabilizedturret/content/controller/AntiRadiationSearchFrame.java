package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/** Authoritative rack axes plus local axes for the client's interpolated Sable render pose. */
public record AntiRadiationSearchFrame(@Nullable UUID bodyId, Vec3 localForward, Vec3 localUp,
                                       Vec3 forward, Vec3 up) {
    public AntiRadiationGeometry.Basis basis() { return AntiRadiationGeometry.Basis.of(forward, up); }

    public static @Nullable AntiRadiationSearchFrame from(StabilizerControllerBlockEntity controller) {
        var level = controller.getLevel();
        var rack = controller.getAntiRadiationSearchRack();
        if (level == null || rack == null) return null;
        if (!rack.isSameTarget(level) || !level.hasChunkAt(rack.pos())) return null;
        var state = level.getBlockState(rack.pos());
        Direction facing = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING)
                : state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        Vec3 forward = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 up = facing.getAxis() == Direction.Axis.Y ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        if (rack.subLevelId() == null) return new AntiRadiationSearchFrame(null, forward, up, forward, up);
        var body = SubLevelContainer.getContainer(level).getSubLevel(rack.subLevelId());
        if (body == null || body.isRemoved()) return null;
        Vector3d worldForward = body.logicalPose().transformNormal(new Vector3d(forward.x, forward.y, forward.z));
        Vector3d worldUp = body.logicalPose().transformNormal(new Vector3d(up.x, up.y, up.z));
        return new AntiRadiationSearchFrame(rack.subLevelId(), forward, up,
                new Vec3(worldForward.x, worldForward.y, worldForward.z).normalize(),
                new Vec3(worldUp.x, worldUp.y, worldUp.z).normalize());
    }
}
