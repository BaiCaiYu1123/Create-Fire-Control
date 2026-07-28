package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.Optional;

/** Optional bridge to Mianbao's public electro-optical pod API. */
public final class ElectroOpticalPodCompat {
    private static final ResourceLocation POD_ID = ResourceLocation.fromNamespaceAndPath(
            "mianbaos_modernwarfare", "electroopticalpod"
    );
    private static final String API_CLASS = "net.mcreator.myfirstmod.api.ElectroOpticalPodApi";

    public static boolean isPod(Level level, BlockPos pos) {
        return POD_ID.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    public static @Nullable Vec3 worldPosition(Level level, BindingRef ref) {
        BlockEntity pod = resolvePod(level, ref);
        if (pod == null || pod.getLevel() == null) return null;
        return Sable.HELPER.projectOutOfSubLevel(
                pod.getLevel(), Vec3.atCenterOf(pod.getBlockPos())
        );
    }

    /** Physical pod base direction, intentionally excluding its animated model angles. */
    public static @Nullable Vec3 baseWorldDirection(Level level, BindingRef ref) {
        BlockEntity pod = resolvePod(level, ref);
        if (pod == null) return null;
        Direction facing = baseFacing(pod);
        Vec3 localOrigin = Vec3.atCenterOf(pod.getBlockPos());
        Vec3 localForward = localOrigin.add(facing.getStepX(), 0.0, facing.getStepZ());
        Vec3 worldOrigin = Sable.HELPER.projectOutOfSubLevel(pod.getLevel(), localOrigin);
        Vec3 worldForward = Sable.HELPER.projectOutOfSubLevel(pod.getLevel(), localForward);
        Vec3 direction = worldForward.subtract(worldOrigin);
        return direction.lengthSqr() < 1.0e-8 ? null : direction.normalize();
    }

    /** Complete optical direction including the pod model's saved yaw and pitch. */
    public static @Nullable Vec3 modelWorldDirection(Level level, BindingRef ref) {
        BlockEntity pod = resolvePod(level, ref);
        if (pod == null || pod.getLevel() == null) return null;
        try {
            Object controller = findController(pod.getLevel(), pod.getBlockPos());
            if (controller == null) return baseWorldDirection(level, ref);
            Direction base = (Direction) controller.getClass().getMethod("getBaseFacing").invoke(controller);
            double yaw = Math.toRadians(((Number) controller.getClass()
                    .getMethod("getYawDegrees").invoke(controller)).doubleValue());
            double pitch = Math.toRadians(((Number) controller.getClass()
                    .getMethod("getPitchDegrees").invoke(controller)).doubleValue());
            Vec3 forward = new Vec3(base.getStepX(), 0.0, base.getStepZ()).normalize();
            Vec3 right = new Vec3(forward.z, 0.0, -forward.x);
            Vec3 horizontal = forward.scale(Math.cos(yaw)).add(right.scale(Math.sin(yaw)));
            Vec3 localDirection = horizontal.scale(Math.cos(pitch)).add(0.0, Math.sin(pitch), 0.0);
            Vec3 localOrigin = Vec3.atCenterOf(pod.getBlockPos());
            Vec3 worldOrigin = Sable.HELPER.projectOutOfSubLevel(pod.getLevel(), localOrigin);
            Vec3 worldForward = Sable.HELPER.projectOutOfSubLevel(
                    pod.getLevel(), localOrigin.add(localDirection));
            Vec3 direction = worldForward.subtract(worldOrigin);
            return direction.lengthSqr() < 1.0e-8 ? null : direction.normalize();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return baseWorldDirection(level, ref);
        }
    }

    /** Points both the yaw ring and pitch head at the requested world line. */
    public static void pointModelAt(Level level, BindingRef ref, Vec3 worldDirection) {
        if (worldDirection.lengthSqr() < 1.0e-8) return;
        BlockEntity pod = resolvePod(level, ref);
        if (pod == null || pod.getLevel() == null) return;
        try {
            Object controller = findController(pod.getLevel(), pod.getBlockPos());
            if (controller == null) return;
            Direction base = (Direction) controller.getClass().getMethod("getBaseFacing").invoke(controller);
            Vector3d local = new Vector3d(worldDirection.x, worldDirection.y, worldDirection.z);
            SubLevel containing = Sable.HELPER.getContaining(pod);
            if (containing != null && !containing.isRemoved()) {
                containing.logicalPose().transformNormalInverse(local);
            }
            if (local.lengthSquared() < 1.0e-8) return;
            local.normalize();

            float currentYaw = ((Number) controller.getClass()
                    .getMethod("getYawDegrees").invoke(controller)).floatValue();
            Vector3d forward = new Vector3d(base.getStepX(), 0.0, base.getStepZ()).normalize();
            double horizontalLength = Math.sqrt(local.x * local.x + local.z * local.z);
            Vector3d horizontal = horizontalLength < 1.0e-8
                    ? new Vector3d(forward)
                    : new Vector3d(local.x / horizontalLength, 0.0, local.z / horizontalLength);
            double crossY = forward.z * horizontal.x - forward.x * horizontal.z;
            // Mianbao's renderer applies the API angle directly around YP:
            // positive yaw turns the native north-facing model toward west.
            float principalYaw = horizontalLength < 1.0e-6
                    ? currentYaw
                    : Mth.wrapDegrees((float) Math.toDegrees(
                    Math.atan2(crossY, forward.dot(horizontal))));
            float principalPitch = (float) Math.toDegrees(
                    Math.atan2(local.y, horizontalLength)
            );
            // Mianbao's public controller wraps yaw to [-180, 180] and clamps
            // pitch to [-90, 90]. Feeding it the mathematically equivalent
            // inverted solution (yaw + 180, pitch = 180 - pitch) therefore
            // clamps only the pitch while retaining the inverted yaw. That is
            // the source of the pod model's occasional quarter/half-turn jump
            // while the optical camera itself remains correct. Always use the
            // API's one representable principal solution. The client camera
            // may still pass the nadir and invert; only the limited physical
            // model uses this canonical display pose.
            float yaw = Mth.wrapDegrees(principalYaw);
            float pitch = Mth.clamp(principalPitch, -90.0F, 90.0F);
            if (Math.abs(Mth.wrapDegrees(yaw - currentYaw)) < 0.05F
                    && Math.abs(pitch - ((Number) controller.getClass()
                    .getMethod("getPitchDegrees").invoke(controller)).floatValue()) < 0.05F) {
                return;
            }
            Method setter = controller.getClass().getMethod(
                    "setRotationDegrees", float.class, float.class
            );
            setter.invoke(controller, yaw, pitch);

            // Some pod builds expose the combined API but only synchronize
            // their yaw field from it. Verify the pitch actually changed and
            // use the dedicated public setter as a compatibility fallback.
            float appliedPitch = ((Number) controller.getClass()
                    .getMethod("getPitchDegrees").invoke(controller)).floatValue();
            if (Math.abs(pitch - appliedPitch) >= 0.05F) {
                controller.getClass().getMethod("setPitchDegrees", float.class)
                        .invoke(controller, pitch);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The dependency is optional; an incompatible build disables only pod animation.
        }
    }

    private static Direction baseFacing(BlockEntity pod) {
        try {
            Object controller = findController(pod.getLevel(), pod.getBlockPos());
            if (controller != null) {
                Object result = controller.getClass().getMethod("getBaseFacing").invoke(controller);
                if (result instanceof Direction direction && direction.getAxis().isHorizontal()) {
                    return direction;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        if (pod.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING)) {
            return pod.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        }
        return Direction.NORTH;
    }

    private static @Nullable BlockEntity resolvePod(Level level, BindingRef ref) {
        BlockEntity blockEntity = ref.resolve(level);
        return blockEntity != null
                && POD_ID.equals(BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()))
                ? blockEntity
                : null;
    }

    private static @Nullable Object findController(Level level, BlockPos pos)
            throws ReflectiveOperationException {
        Class<?> api = Class.forName(API_CLASS);
        Method find = api.getMethod(
                "findController", net.minecraft.world.level.BlockGetter.class, BlockPos.class
        );
        Object result = find.invoke(null, level, pos);
        return result instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    private ElectroOpticalPodCompat() {
    }
}
