package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/** Optional Flight Control muzzle reference, without linking Flight Control classes. */
public final class ForwardIndicatorCompat {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            "create_flight_control", "forward_indicator");

    public static boolean isPresent(@Nullable Level level, @Nullable BindingRef ref) {
        return level != null && ref != null && level.hasChunkAt(ref.pos())
                && ref.isSameTarget(level) && isIndicator(level.getBlockState(ref.pos()));
    }

    public static boolean isIndicator(BlockState state) {
        return ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static @Nullable Vec3 worldDirection(@Nullable Level level, @Nullable BindingRef ref) {
        if (!isPresent(level, ref)) return null;
        BlockState state = level.getBlockState(ref.pos());
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING)) return null;
        // Flight Control's getModelForwardFacing uses the opposite model face.
        Direction forward = state.getValue(HorizontalDirectionalBlock.FACING).getOpposite();
        Vector3d world = new Vector3d(forward.getStepX(), forward.getStepY(), forward.getStepZ());
        if (ref.subLevelId() != null) {
            var subLevel = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
            if (subLevel == null || subLevel.isRemoved()) return null;
            subLevel.logicalPose().transformNormal(world);
        }
        if (!Double.isFinite(world.x) || !Double.isFinite(world.y) || !Double.isFinite(world.z)
                || world.lengthSquared() < 1.0e-8) return null;
        world.normalize();
        return new Vec3(world.x, world.y, world.z);
    }

    private ForwardIndicatorCompat() {}
}
