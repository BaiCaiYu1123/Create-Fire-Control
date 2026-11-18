package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload.Heading;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.UUID;

public final class ScopeOrientationResolver {
    private ScopeOrientationResolver() {}

    public static ScopeOrientationPayload measure(Level level, BindingRef yaw, Vec3 barrel,
                                                   UUID barrelBody, YawLimits limits) {
        if (!ScopeOrientationMath.valid(barrel) || yaw == null
                || !(yaw.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled() || bearing.getPlatePos() == null)
            return ScopeOrientationPayload.inactive();
        var container = SubLevelContainer.getContainer(level);
        SubLevel turret = container.getSubLevel(bearing.getSubLevelID());
        if (turret == null || turret.isRemoved()) return ScopeOrientationPayload.inactive();
        var plate = level.getBlockState(bearing.getPlatePos());
        if (!plate.hasProperty(SwivelBearingPlateBlock.FACING)) return ScopeOrientationPayload.inactive();
        Direction bearingFacing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        // Only a direction bearing can define a horizontal hull/turret relationship.
        if (bearingFacing.getAxis() != Direction.Axis.Y) return ScopeOrientationPayload.inactive();
        Direction plateFacing = plate.getValue(SwivelBearingPlateBlock.FACING);
        Vec3 localHull = ScopeOrientationMath.hullForward(barrel, turret.logicalPose().orientation(),
                new Quaterniond(bearingFacing.getRotation()), new Quaterniond(plateFacing.getRotation()),
                BearingStabilizer.getAssemblyZeroDegrees(bearing));
        if (limits.enabled()) {
            var center = limits.localCenter();
            localHull = new Vec3(center.x, center.y, center.z);
        }
        if (!ScopeOrientationMath.valid(localHull)) return ScopeOrientationPayload.inactive();
        SubLevel hull = Sable.HELPER.getContaining(bearing);
        Heading hullHeading = localHeading(hull, localHull);
        SubLevel barrelLevel = barrelBody == null ? null : container.getSubLevel(barrelBody);
        Heading turretHeading = worldHeading(barrelLevel, barrel.normalize());
        return new ScopeOrientationPayload(hullHeading, turretHeading);
    }

    private static Heading localHeading(SubLevel body, Vec3 local) {
        if (body == null) return new Heading(null, local, local);
        return new Heading(body.getUniqueId(), local,
                ScopeOrientationMath.rotate(body.logicalPose().orientation(), local));
    }

    private static Heading worldHeading(SubLevel body, Vec3 world) {
        if (body == null || body.isRemoved()) return new Heading(null, world, world);
        return new Heading(body.getUniqueId(), ScopeOrientationMath.rotate(
                new Quaterniond(body.logicalPose().orientation()).conjugate(), world), world);
    }
}
