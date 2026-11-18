package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Optional CBC/CBC Neo Warfare bridge with no hard dependency. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CannonMountCompat {
    private static final long FIRE_REQUEST_GRACE_TICKS = 2L;
    private static final Map<BlockEntity, Boolean> VIRTUAL_FIRE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BlockEntity, Long> ACTIVE_FIRE_REQUESTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BlockEntity, Integer> ACTIVE_FIRE_POWERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean isCannonMount(BlockEntity blockEntity) {
        return findNoArgMethod(blockEntity.getClass(), "getContraption") != null
                && findNoArgMethod(blockEntity.getClass(), "getContraptionDirection") != null;
    }

    public static @Nullable Vec3 getAssembledCannonDirection(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) {
            return null;
        }

        // CBC's assembled contraption orientation is authoritative. A mount's
        // facing only describes the base and can disagree with the assembled
        // barrel axis on rotated, inverted and add-on cannon mounts.
        Vec3 transformed = CannonBallisticsCompat.getCurrentForward(level, ref);
        if (transformed != null && transformed.lengthSqr() > 1.0e-8) {
            return transformed.normalize();
        }

        Entity cannon = getMountedCannon(mount);
        if (cannon != null && !cannon.isRemoved()) {
            // CBC stores cannon pitch/yaw through its view-rotation methods;
            // getLookAngle() can still contain the base Entity rotation and
            // produces a small vertical offset on several mount variants.
            Vec3 direction = cannon.getViewVector(1.0F);
            if (direction.lengthSqr() > 1.0e-8) {
                // Sable's Entity.calculateViewVector mixin already converts a
                // contraption inside a physical sub-level to world space.
                return direction.normalize();
            }
        }

        // Never substitute the base/mount facing. Without an assembled cannon
        // whose actual transformed axis can be measured, there is no valid
        // muzzle direction to bind or aim.
        return null;
    }

    public static @Nullable Vec3 getMountedCannonPosition(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) {
            return null;
        }
        Entity cannon = getMountedCannon(mount);
        if (cannon != null && !cannon.isRemoved()) {
            return Sable.HELPER.projectOutOfSubLevel(cannon.level(), cannon.getBoundingBox().getCenter());
        }
        Level mountLevel = mount.getLevel();
        return mountLevel == null
                ? null
                : Sable.HELPER.projectOutOfSubLevel(mountLevel, Vec3.atCenterOf(ref.pos()));
    }

    public static boolean hasAssembledCannon(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) return false;
        Entity cannon = getMountedCannon(mount);
        return cannon != null && !cannon.isRemoved();
    }

    /** Exact assembled CBC entity used by mounted auxiliary weapons. */
    public static @Nullable Entity getMountedCannonEntity(Level level, BindingRef ref) {
        BlockEntity mount = ref == null ? null : ref.resolve(level);
        if (mount == null) return null;
        Entity cannon = getMountedCannon(mount);
        return cannon == null || cannon.isRemoved() ? null : cannon;
    }

    /** CBC rotates about contraption-local block zero, never its barrel bounding-box centre. */
    public static @Nullable VirtualScopeGeometry.Axis getMountedCannonPitchAxis(Level level, BindingRef ref) {
        Entity entity = getMountedCannonEntity(level, ref);
        if (!(entity instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity cannon)
                || cannon.getContraption() == null) return null;
        Object initial = invokeDeclaredNoArg(cannon, "getInitialOrientation");
        if (!(initial instanceof Direction)) initial = invokeDeclaredNoArg(cannon.getContraption(), "initialOrientation");
        if (!(initial instanceof Direction forward) || forward.getAxis().isVertical()) return null;
        Vec3 center = Vec3.atCenterOf(net.minecraft.core.BlockPos.ZERO);
        Vec3 lateral = Vec3.atLowerCornerOf(forward.getClockWise().getNormal());
        Vec3 pivot = Sable.HELPER.projectOutOfSubLevel(cannon.level(), cannon.toGlobalVector(center, 1.0F));
        Vec3 side = Sable.HELPER.projectOutOfSubLevel(cannon.level(), cannon.toGlobalVector(center.add(lateral), 1.0F));
        return new VirtualScopeGeometry.Axis(pivot, side.subtract(pivot));
    }

    /**
     * Supplies a virtual redstone signal to the CBC mount's fire input without
     * changing its assembly input.
     */
    public static boolean setAutomaticFire(Level level, BindingRef ref, boolean powered) {
        return setAutomaticFire(level, ref, powered, powered ? 15 : 0);
    }

    /** Supplies a virtual redstone signal with a configurable firing strength. */
    public static boolean setAutomaticFire(
            Level level, BindingRef ref, boolean powered, int firePower) {
        BlockEntity mount = ref == null ? null : ref.resolve(level);
        if (mount == null || getMountedCannon(mount) == null) return false;
        if (powered) {
            Level mountLevel = mount.getLevel();
            if (mountLevel == null || mountLevel.getServer() == null) return false;
            // A Sable-mounted cannon can live in a sub-level whose game time
            // pauses or lags behind another vehicle sub-level in multiplayer.
            // Server tick count is the shared clock for every mounted cannon.
            ACTIVE_FIRE_REQUESTS.put(mount,
                    (long) mountLevel.getServer().getTickCount());
            ACTIVE_FIRE_POWERS.put(mount, Math.max(1, Math.min(15, firePower)));
            return true;
        }

        // An idle display must not write a zero into a mount which is being
        // driven by ordinary redstone. Only release mounts for which this
        // bridge previously asserted the virtual fire signal.
        if (ACTIVE_FIRE_REQUESTS.remove(mount) == null
                && !Boolean.TRUE.equals(VIRTUAL_FIRE_STATES.get(mount))) {
            ACTIVE_FIRE_POWERS.remove(mount);
            return true;
        }
        ACTIVE_FIRE_POWERS.remove(mount);
        return applyVirtualFireSignal(mount, false);
    }

    /**
     * CBC refreshes physical redstone from neighbouring blocks during its own
     * block-entity tick. Apply the virtual fire input at server-tick end so it
     * cannot be overwritten later in the same tick.
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        synchronized (ACTIVE_FIRE_REQUESTS) {
            var iterator = ACTIVE_FIRE_REQUESTS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BlockEntity, Long> entry = iterator.next();
                BlockEntity mount = entry.getKey();
                Level level = mount == null ? null : mount.getLevel();
                boolean live = mount != null
                        && !mount.isRemoved()
                        && level != null
                        && level.getServer() == event.getServer()
                        && getMountedCannon(mount) != null
                        && isFireRequestFresh(entry.getValue(), now);
                if (!live) {
                    if (mount != null
                            && Boolean.TRUE.equals(VIRTUAL_FIRE_STATES.get(mount))) {
                        applyVirtualFireSignal(mount, false);
                    }
                    ACTIVE_FIRE_POWERS.remove(mount);
                    iterator.remove();
                    continue;
                }
                if (!applyVirtualFireSignal(mount, true,
                        ACTIVE_FIRE_POWERS.getOrDefault(mount, 15))) {
                    ACTIVE_FIRE_POWERS.remove(mount);
                    iterator.remove();
                }
            }
        }
    }

    static boolean isFireRequestFresh(long requestedAt, long serverTick) {
        long age = serverTick - requestedAt;
        return age >= 0L && age <= FIRE_REQUEST_GRACE_TICKS;
    }

    private static boolean applyVirtualFireSignal(BlockEntity mount, boolean powered) {
        return applyVirtualFireSignal(mount, powered, powered ? 15 : 0);
    }

    private static boolean applyVirtualFireSignal(
            BlockEntity mount, boolean powered, int firePower) {
        Boolean stateValue = readBooleanProperty(
                mount.getBlockState(), "fire_powered");
        Boolean virtualPrevious = VIRTUAL_FIRE_STATES.get(mount);
        boolean previous = virtualPrevious != null
                ? virtualPrevious
                : stateValue != null && stateValue;
        if (applyMountedContraptionFire(mount, powered, firePower)) {
            VIRTUAL_FIRE_STATES.put(mount, powered);
            return true;
        }
        try {
            Method method = mount.getClass().getMethod(
                    "onRedstoneUpdate",
                    boolean.class, boolean.class, boolean.class, boolean.class, int.class);
            // CBC's first two booleans are the new/old ASSEMBLY_POWERED
            // states. The mounted cannon check above proves assembly is live,
            // so both must remain true even if the external assembly pulse
            // has already gone low. Passing false here disassembles both the
            // CBC mount and CBC Neo Warfare's compact_mount.
            method.invoke(
                    mount,
                    true,
                    true,
                    powered,
                    previous,
                    powered ? firePower : 0
            );
            VIRTUAL_FIRE_STATES.put(mount, powered);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null
                    ? invocation.getCause()
                    : exception;
            StabilizedTurret.LOGGER.warn(
                    "Unable to drive virtual cannon fire input: mount={} pos={} powered={} previous={}",
                    mount.getClass().getName(),
                    mount.getBlockPos(),
                    powered,
                    previous,
                    cause
            );
            return false;
        }
    }

    /**
     * Drives CBC's assembled contraption directly at tick end. Some optional
     * mounts keep a valid mounted entity while their private {@code running}
     * flag or neighbour-redstone refresh prevents the public mount wrapper
     * from forwarding a virtual signal. The contraption method is the common
     * CBC endpoint used by both the standard mount and Neo Warfare compact
     * mount.
     */
    private static boolean applyMountedContraptionFire(
            BlockEntity mount, boolean powered, int firePower) {
        Entity cannon = getMountedCannon(mount);
        if (cannon == null || cannon.isRemoved()
                || !(mount.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }
        Object contraption = invokeNoArg(cannon, "getContraption");
        if (contraption == null) return false;
        for (Method method : contraption.getClass().getMethods()) {
            if (!"onRedstoneUpdate".equals(method.getName())
                    || method.getParameterCount() != 5) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (!parameters[0].isInstance(serverLevel)
                    || !parameters[1].isInstance(cannon)
                    || parameters[2] != boolean.class
                    || parameters[3] != int.class
                    || !parameters[4].isInstance(mount)) {
                continue;
            }
            try {
                // A sustained automatic-fire request repeatedly supplies an
                // active edge. Autocannons use the power value as fire rate;
                // single-shot cannons use the edge and still enforce their
                // own loading/cooldown rules.
                method.invoke(
                        contraption,
                        serverLevel,
                        cannon,
                        powered,
                        powered ? firePower : 0,
                        mount
                );
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                Throwable cause = exception instanceof InvocationTargetException invocation
                        && invocation.getCause() != null
                        ? invocation.getCause()
                        : exception;
                StabilizedTurret.LOGGER.warn(
                        "Unable to drive assembled cannon redstone endpoint: contraption={} mount={} pos={}",
                        contraption.getClass().getName(),
                        mount.getClass().getName(),
                        mount.getBlockPos(),
                        cause
                );
                return false;
            }
        }
        return false;
    }

    private static @Nullable Boolean readBooleanProperty(
            BlockState state,
            String serializedName
    ) {
        for (Property<?> property : state.getProperties()) {
            if (!serializedName.equals(property.getName())) continue;
            Comparable<?> value = state.getValue(property);
            return value instanceof Boolean bool ? bool : null;
        }
        return null;
    }

    /**
     * Points an assembled CBC mount at a finite world-space target. The target
     * is transformed into the mount's Sable frame before CBC yaw/pitch values
     * are calculated, so aircraft translation and rotation do not alter the
     * designated impact point.
     */
    public static boolean pointAssembledCannonAt(Level level, BindingRef ref, Vec3 worldTarget) {
        return pointAssembledCannonAt(level, ref, worldTarget, 90.0, 90.0);
    }

    public static boolean pointAssembledCannonAt(
            Level level,
            BindingRef ref,
            Vec3 worldTarget,
            double configuredDepression,
            double configuredElevation
    ) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null || !isFinite(worldTarget)) return false;
        Entity cannon = getMountedCannon(mount);
        if (cannon == null || cannon.isRemoved()) return false;

        CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.analyze(level, ref);
        Vec3 solvedDirection = profile == null
                ? null
                : CannonBallisticsCompat.solveLaunchDirection(profile, worldTarget);
        Vec3 origin = profile == null
                ? Sable.HELPER.projectOutOfSubLevel(
                        cannon.level(), cannon.getBoundingBox().getCenter())
                : profile.muzzle();
        // solveLaunchDirection returns the required muzzle direction relative
        // to the carrier. That is exactly the direction the barrel must face.
        // The old implementation added carrier velocity a second time here,
        // even though the solver had already subtracted it from the required
        // world launch velocity. This doubled aircraft-motion compensation.
        Vec3 worldDirection = solvedDirection == null
                ? worldTarget.subtract(origin)
                : solvedDirection;
        return pointAssembledCannonAlong(
                level, ref, worldDirection,
                configuredDepression, configuredElevation);
    }

    /**
     * Applies an already solved world-space muzzle direction. Fire-control
     * calibration is angular; passing the direction directly prevents the
     * mount from solving a second, slightly different translated target.
     */
    public static boolean pointAssembledCannonAlong(
            Level level,
            BindingRef ref,
            Vec3 worldDirection,
            double configuredDepression,
            double configuredElevation
    ) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null || !isFinite(worldDirection)) return false;
        Entity cannon = getMountedCannon(mount);
        if (cannon == null || cannon.isRemoved()) return false;
        Vector3d localDirection = new Vector3d(
                worldDirection.x,
                worldDirection.y,
                worldDirection.z
        );
        SubLevel containing = Sable.HELPER.getContaining(mount);
        if (containing != null && !containing.isRemoved()) {
            containing.logicalPose().transformNormalInverse(localDirection);
        }
        if (!Double.isFinite(localDirection.x)
                || !Double.isFinite(localDirection.y)
                || !Double.isFinite(localDirection.z)
                || localDirection.lengthSquared() < 1.0e-8) {
            return false;
        }
        localDirection.normalize();

        double horizontal = Math.hypot(localDirection.x, localDirection.z);
        float desiredYaw = Mth.wrapDegrees((float) Math.toDegrees(
                Math.atan2(-localDirection.x, localDirection.z)));
        float physicalElevation = (float) Math.toDegrees(
                Math.atan2(localDirection.y, horizontal));
        Float maxDepression = invokeFloatNoArg(mount, "getMaxDepress");
        Float maxElevation = invokeFloatNoArg(mount, "getMaxElevate");
        double depression = Mth.clamp(configuredDepression, 0.0, 90.0);
        double elevation = Mth.clamp(configuredElevation, 0.0, 90.0);
        if (maxDepression != null) depression = Math.min(depression, Math.abs(maxDepression));
        if (maxElevation != null) elevation = Math.min(elevation, Math.abs(maxElevation));
        physicalElevation = Mth.clamp(
                physicalElevation, (float) -depression, (float) elevation);

        Float currentControlYaw = readFloatField(mount, "cannonYaw");
        Float currentControlPitch = readFloatField(mount, "cannonPitch");
        Vec3 currentWorldForward =
                CannonBallisticsCompat.getCurrentForward(level, ref);
        if (currentControlYaw != null
                && currentControlPitch != null
                && currentWorldForward != null
                && currentWorldForward.lengthSqr() > 1.0e-8) {
            // CBC mount variants store controller yaw/pitch relative to their
            // assembly direction, and the sign differs with the original
            // cannon axis. Writing an absolute local yaw only happens to align
            // at a subset of headings, which is why rotary-cannon rounds left
            // the barrel straight at some angles and sideways at others.
            //
            // Solve against CBC's authoritative transformed barrel axis. Try
            // the four possible yaw/pitch sign conventions synchronously and
            // retain the one whose actual world forward best matches the
            // requested direction. This also covers optional CBC add-ons that
            // inherit the mount but alter assembly orientation.
            Vector3d currentLocal = new Vector3d(
                    currentWorldForward.x,
                    currentWorldForward.y,
                    currentWorldForward.z
            );
            if (containing != null && !containing.isRemoved()) {
                containing.logicalPose().transformNormalInverse(currentLocal);
            }
            currentLocal.normalize();
            float currentPhysicalYaw = Mth.wrapDegrees(
                    (float) Math.toDegrees(Math.atan2(
                            -currentLocal.x, currentLocal.z)));
            float currentPhysicalPitch = (float) Math.toDegrees(Math.atan2(
                    currentLocal.y,
                    Math.hypot(currentLocal.x, currentLocal.z)
            ));
            float yawError = Mth.wrapDegrees(
                    desiredYaw - currentPhysicalYaw);
            float pitchError = physicalElevation - currentPhysicalPitch;
            float bestYaw = currentControlYaw;
            float bestPitch = currentControlPitch;
            double bestScore = normalizedDot(
                    currentWorldForward, worldDirection);

            for (float yawSign : new float[]{1.0F, -1.0F}) {
                for (float pitchSign : new float[]{1.0F, -1.0F}) {
                    float candidateYaw = currentControlYaw
                            + yawError * yawSign;
                    float candidatePitch = Mth.clamp(
                            currentControlPitch + pitchError * pitchSign,
                            (float) -depression,
                            (float) elevation
                    );
                    if (!invokeFloatArg(mount, "setYaw", candidateYaw)
                            || !invokeFloatArg(
                            mount, "setPitch", candidatePitch)) {
                        continue;
                    }
                    invokeDeclaredNoArg(mount, "applyRotation");
                    Vec3 measured = CannonBallisticsCompat.getCurrentForward(
                            level, ref);
                    double score = normalizedDot(measured, worldDirection);
                    if (score > bestScore) {
                        bestScore = score;
                        bestYaw = candidateYaw;
                        bestPitch = candidatePitch;
                    }
                }
            }
            if (!invokeFloatArg(mount, "setYaw", bestYaw)
                    || !invokeFloatArg(mount, "setPitch", bestPitch)) {
                return false;
            }
        } else {
            // Reflection fallback for a mount implementation without CBC's
            // standard controller fields. CBC yaw is relative to the cannon's
            // assembly heading, rather than an absolute world-frame yaw.
            Object rawBase = invokeNoArg(mount, "getContraptionDirection");
            float baseYaw = rawBase instanceof Direction direction
                    ? direction.toYRot() : 0.0F;
            float yaw = Mth.wrapDegrees(desiredYaw - baseYaw);
            if (!invokeFloatArg(mount, "setYaw", yaw)
                    || !invokeFloatArg(
                    mount, "setPitch", physicalElevation)) {
                return false;
            }
        }
        // Also apply during the controller tick so block-entity tick ordering
        // cannot add one visible frame of lag.
        invokeDeclaredNoArg(mount, "applyRotation");
        mount.setChanged();
        invokeNoArg(mount, "sendData");
        return true;
    }

    public static int getMountedCannonEntityId(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) {
            return -1;
        }
        Entity cannon = getMountedCannon(mount);
        return cannon == null || cannon.isRemoved() ? -1 : cannon.getId();
    }

    public static @Nullable UUID getMountedCannonEntityUuid(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) {
            return null;
        }
        Entity cannon = getMountedCannon(mount);
        return cannon == null || cannon.isRemoved() ? null : cannon.getUUID();
    }

    /**
     * Returns the physical Sable layer that actually owns the assembled cannon
     * entity. This is deliberately not the layer containing the bound mount:
     * nested pitch assemblies commonly put the barrel one physical level below
     * the mount, which was why hiding the mount level left the barrel visible.
     */
    public static @Nullable UUID getMountedCannonSubLevelId(Level level, BindingRef ref) {
        BlockEntity mount = ref.resolve(level);
        if (mount == null) {
            return null;
        }
        Entity cannon = getMountedCannon(mount);
        if (cannon == null || cannon.isRemoved()) {
            return null;
        }
        SubLevel containing = Sable.HELPER.getContaining(cannon);
        return containing == null ? null : containing.getUniqueId();
    }

    private static @Nullable Entity getMountedCannon(BlockEntity mount) {
        Object contraption = invokeNoArg(mount, "getContraption");
        return contraption instanceof Entity entity ? entity : null;
    }

    private static @Nullable Object invokeNoArg(Object owner, String name) {
        Method method = findNoArgMethod(owner.getClass(), name);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(owner);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Float invokeFloatNoArg(Object owner, String name) {
        Method method = findDeclaredNoArgMethod(owner.getClass(), name);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            Object result = method.invoke(owner);
            return result instanceof Number number ? number.floatValue() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean invokeFloatArg(Object owner, String name, float value) {
        try {
            Method method = owner.getClass().getMethod(name, float.class);
            method.invoke(owner, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static @Nullable Float readFloatField(Object owner, String name) {
        Field field = findField(owner.getClass(), name);
        if (field == null) return null;
        try {
            field.setAccessible(true);
            Object value = field.get(owner);
            return value instanceof Number number
                    ? number.floatValue() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        for (Class<?> current = type;
             current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException | LinkageError ignored) {
            }
        }
        return null;
    }

    private static @Nullable Object invokeDeclaredNoArg(Object owner, String name) {
        Method method = findDeclaredNoArgMethod(owner.getClass(), name);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Method findNoArgMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Method findDeclaredNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException | LinkageError ignored) {
            }
        }
        return null;
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private static double normalizedDot(
            @Nullable Vec3 first,
            @Nullable Vec3 second
    ) {
        if (first == null || second == null
                || first.lengthSqr() < 1.0e-10
                || second.lengthSqr() < 1.0e-10) {
            return -1.0;
        }
        return Mth.clamp(
                first.normalize().dot(second.normalize()), -1.0, 1.0);
    }

    private CannonMountCompat() {
    }
}
