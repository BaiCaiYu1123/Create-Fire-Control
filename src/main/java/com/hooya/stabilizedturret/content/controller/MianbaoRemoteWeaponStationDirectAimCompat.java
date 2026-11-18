package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Uses Mianbao's uploaded mouse angles when either native control mode targets
 * a station on a Sable structure. Server-side player rotation can be replaced
 * by vehicle synchronization there, while the uploaded angles remain
 * authoritative for both direct right-click and controller-item sessions.
 */
public final class MianbaoRemoteWeaponStationDirectAimCompat {
    private static final String SESSION_MANAGER_CLASS =
            "net.mcreator.myfirstmod.remote.RemoteWeaponStationSessionManager";
    private static final long INPUT_TIMEOUT_TICKS = 10L;
    private static final float MAX_YAW_STEP = 60.0F / 20.0F;
    private static final float MAX_PITCH_STEP = 45.0F / 20.0F;
    private static final float MIN_PITCH = -20.0F;
    private static final float MAX_PITCH = 65.0F;
    private static final float DIRECT_MIN_YAW = -60.0F;
    private static final float DIRECT_MAX_YAW = 60.0F;
    private static final Map<ServerPlayer, AimSample> DIRECT_AIM = new WeakHashMap<>();
    private static final Map<ServerPlayer, CommandState> COMMAND_STATE =
            new WeakHashMap<>();

    private static volatile boolean reflectionLookupAttempted;
    private static volatile @Nullable Field sessionsField;
    private static volatile @Nullable Field sessionOpticalField;
    private static volatile @Nullable Field sessionBindingField;
    private static volatile @Nullable Method bindingPosMethod;
    private static volatile boolean warningLogged;
    private static volatile boolean activationLogged;
    private static volatile boolean finalWriterLogged;

    /** Called from Mianbao's existing input handler; no extra packets are sent. */
    public static void recordInput(
            ServerPlayer player, float yaw, float pitch, boolean exit
    ) {
        if (player == null || exit || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            synchronized (DIRECT_AIM) {
                if (player != null) DIRECT_AIM.remove(player);
            }
            return;
        }

        NativeSession session = nativeSession(player);
        if (session == null) {
            synchronized (DIRECT_AIM) {
                DIRECT_AIM.remove(player);
            }
            return;
        }
        SubLevel containing;
        try {
            containing = Sable.HELPER.getContaining(player.level(), session.pos());
        } catch (RuntimeException | LinkageError failure) {
            warnOnce("定位物理结构上的遥控武器站失败", failure);
            return;
        }
        if (containing == null || containing.isRemoved()) {
            synchronized (DIRECT_AIM) {
                DIRECT_AIM.remove(player);
            }
            return;
        }

        Vec3 direction = directionFromUploadedAngles(
                yaw, pitch, session.baseFacing());
        if (!finite(direction) || direction.lengthSqr() < 1.0e-8) return;
        synchronized (DIRECT_AIM) {
            DIRECT_AIM.put(player, new AimSample(
                    direction.normalize(), player.level().getGameTime()));
        }
        if (!activationLogged) {
            activationLogged = true;
            StabilizedTurret.LOGGER.info(
                    "Enabled uploaded-view aiming for Mianbao weapon-station control on Sable");
        }
    }

    /** Authoritative fire-control packet, independent from ridden entity rotation. */
    public static void recordIndependentView(
            ServerPlayer player, float worldYaw, float worldPitch
    ) {
        if (player == null || !Float.isFinite(worldYaw)
                || !Float.isFinite(worldPitch)) return;
        NativeSession session = nativeSession(player);
        if (session == null) {
            synchronized (DIRECT_AIM) {
                DIRECT_AIM.remove(player);
            }
            return;
        }
        SubLevel containing;
        try {
            containing = Sable.HELPER.getContaining(player.level(), session.pos());
        } catch (RuntimeException | LinkageError failure) {
            warnOnce("定位物理结构上的遥控武器站失败", failure);
            return;
        }
        if (containing == null || containing.isRemoved()) return;

        Vec3 direction = Vec3.directionFromRotation(
                Mth.clamp(worldPitch, -90.0F, 90.0F),
                Mth.wrapDegrees(worldYaw));
        if (!finite(direction) || direction.lengthSqr() < 1.0e-8) return;
        synchronized (DIRECT_AIM) {
            DIRECT_AIM.put(player, new AimSample(
                    direction.normalize(), player.level().getGameTime()));
        }
        if (!activationLogged) {
            activationLogged = true;
            StabilizedTurret.LOGGER.info(
                    "Enabled fire-control-owned aiming for Mianbao weapon stations on Sable");
        }
    }

    /** Replaces only the unreliable server rider look vector. */
    public static Vec3 directViewOrPlayerView(ServerPlayer player) {
        if (player == null) return Vec3.ZERO;
        AimSample sample;
        synchronized (DIRECT_AIM) {
            sample = DIRECT_AIM.get(player);
            if (sample != null && (player.level().getGameTime() < sample.tick()
                    || player.level().getGameTime() - sample.tick() > INPUT_TIMEOUT_TICKS)) {
                DIRECT_AIM.remove(player);
                sample = null;
            }
        }
        return sample == null ? player.getLookAngle() : sample.direction();
    }

    /**
     * Runs after Mianbao's native player tick and is the final attitude writer
     * for a station on a Sable structure. This removes ordering ambiguity with
     * the native rider-based writer while preserving its slew rate and limits.
     */
    public static void applyIndependentAim(ServerPlayer player) {
        if (player == null) return;
        AimSample sample = freshSample(player);
        if (sample == null) return;
        NativeSession session = nativeSession(player);
        if (session == null) {
            synchronized (COMMAND_STATE) {
                COMMAND_STATE.remove(player);
            }
            return;
        }

        SubLevel containing;
        try {
            containing = Sable.HELPER.getContaining(player.level(), session.pos());
        } catch (RuntimeException | LinkageError failure) {
            warnOnce("定位物理结构上的遥控武器站失败", failure);
            return;
        }
        if (containing == null || containing.isRemoved()) return;
        BlockEntity station = player.level().getBlockEntity(session.pos());
        if (station == null || station.isRemoved()) return;

        try {
            Vector3d localVector = containing.logicalPose().transformNormalInverse(
                    new Vector3d(sample.direction().x,
                            sample.direction().y,
                            sample.direction().z));
            Vec3 localDirection = new Vec3(
                    localVector.x, localVector.y, localVector.z);
            if (!finite(localDirection)
                    || localDirection.lengthSqr() < 1.0e-8) return;
            localDirection = localDirection.normalize();

            float desiredPitch = Mth.clamp((float) Math.toDegrees(Math.asin(
                    Mth.clamp(localDirection.y, -1.0, 1.0))),
                    MIN_PITCH, MAX_PITCH);
            float localWorldYaw = (float) Math.toDegrees(Math.atan2(
                    localDirection.x, -localDirection.z));
            float desiredYaw = Mth.wrapDegrees(
                    localWorldYaw - baseYawDegrees(session.baseFacing()));
            if (!session.optical()) {
                desiredYaw = Mth.clamp(desiredYaw,
                        DIRECT_MIN_YAW, DIRECT_MAX_YAW);
            }

            Method getYaw = station.getClass().getMethod("getYawDegrees");
            Method getPitch = station.getClass().getMethod("getPitchDegrees");
            Method setRotation = station.getClass().getMethod(
                    "setRotationDegrees", float.class, float.class);
            Object yawValue = getYaw.invoke(station);
            Object pitchValue = getPitch.invoke(station);
            if (!(yawValue instanceof Number yawNumber)
                    || !(pitchValue instanceof Number pitchNumber)) return;
            float currentYaw = yawNumber.floatValue();
            float currentPitch = pitchNumber.floatValue();
            if (!Float.isFinite(currentYaw) || !Float.isFinite(currentPitch)) return;

            // Mianbao has already written its rider-derived angle earlier in
            // this tick. Step from our own previous final command instead of
            // that overwritten value, otherwise two equal 3-degree writers
            // can cancel each other forever.
            CommandState command;
            synchronized (COMMAND_STATE) {
                command = COMMAND_STATE.get(player);
                UUID structureId = containing.getUniqueId();
                if (command == null
                        || !command.pos.equals(session.pos())
                        || !Objects.equals(command.structureId, structureId)) {
                    command = new CommandState(
                            session.pos().immutable(), structureId,
                            currentYaw, currentPitch);
                    COMMAND_STATE.put(player, command);
                }
            }
            float nextYaw = Mth.wrapDegrees(command.yaw + Mth.clamp(
                    Mth.wrapDegrees(desiredYaw - command.yaw),
                    -MAX_YAW_STEP, MAX_YAW_STEP));
            float nextPitch = command.pitch + Mth.clamp(
                    desiredPitch - command.pitch,
                    -MAX_PITCH_STEP, MAX_PITCH_STEP);
            nextPitch = Mth.clamp(nextPitch, MIN_PITCH, MAX_PITCH);
            setRotation.invoke(station, nextYaw, nextPitch);
            synchronized (COMMAND_STATE) {
                command.yaw = nextYaw;
                command.pitch = nextPitch;
            }
            if (!finalWriterLogged) {
                finalWriterLogged = true;
                StabilizedTurret.LOGGER.info(
                        "Fire control is now the final Sable weapon-station attitude writer");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warnOnce("火控最终写入遥控武器站角度失败", failure);
        }
    }

    private static @Nullable AimSample freshSample(ServerPlayer player) {
        synchronized (DIRECT_AIM) {
            AimSample sample = DIRECT_AIM.get(player);
            if (sample == null) return null;
            long now = player.level().getGameTime();
            if (now < sample.tick() || now - sample.tick() > INPUT_TIMEOUT_TICKS) {
                DIRECT_AIM.remove(player);
                return null;
            }
            return sample;
        }
    }

    static Vec3 directionFromUploadedAngles(
            float relativeYaw, float elevation, Direction baseFacing
    ) {
        float worldYaw = Mth.wrapDegrees(relativeYaw + baseYawDegrees(baseFacing));
        double yawRadians = Math.toRadians(worldYaw);
        double pitchRadians = Math.toRadians(elevation);
        double horizontal = Math.cos(pitchRadians);
        return new Vec3(
                Math.sin(yawRadians) * horizontal,
                Math.sin(pitchRadians),
                -Math.cos(yawRadians) * horizontal);
    }

    private static @Nullable NativeSession nativeSession(ServerPlayer player) {
        if (!ensureReflection()) return null;
        try {
            Object sessionsValue = sessionsField.get(null);
            if (!(sessionsValue instanceof Map<?, ?> sessions)) return null;
            Object session = sessions.get(player.getUUID());
            if (session == null) return null;
            boolean optical = sessionOpticalField.getBoolean(session);
            Object binding = sessionBindingField.get(session);
            Object posValue = bindingPosMethod.invoke(binding);
            if (!(posValue instanceof BlockPos pos)) return null;
            BlockEntity station = player.level().getBlockEntity(pos);
            if (station == null) return null;
            Object facingValue = station.getClass().getMethod("getBaseFacing").invoke(station);
            return facingValue instanceof Direction facing
                    ? new NativeSession(pos, facing, optical) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warnOnce("读取面包原生直接操控会话失败", failure);
            return null;
        }
    }

    private static boolean ensureReflection() {
        if (reflectionLookupAttempted) {
            return sessionsField != null && sessionOpticalField != null
                    && sessionBindingField != null && bindingPosMethod != null;
        }
        synchronized (MianbaoRemoteWeaponStationDirectAimCompat.class) {
            if (reflectionLookupAttempted) return ensureReflection();
            try {
                Class<?> manager = Class.forName(SESSION_MANAGER_CLASS);
                Field sessions = manager.getDeclaredField("SESSIONS");
                if (!Modifier.isStatic(sessions.getModifiers())
                        || !sessions.trySetAccessible()) {
                    throw new IllegalAccessException("SESSIONS is not accessible");
                }
                Object value = sessions.get(null);
                if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
                    // A live input packet always has a session, so an empty map
                    // here indicates an incompatible implementation.
                    throw new IllegalStateException("native session map is empty during input");
                }
                Object session = map.values().iterator().next();
                Field optical = session.getClass().getDeclaredField("optical");
                Field binding = session.getClass().getDeclaredField("binding");
                if (!optical.trySetAccessible() || !binding.trySetAccessible()) {
                    throw new IllegalAccessException("native session fields are not accessible");
                }
                Object bindingValue = binding.get(session);
                Method pos = bindingValue.getClass().getMethod("pos");
                sessionsField = sessions;
                sessionOpticalField = optical;
                sessionBindingField = binding;
                bindingPosMethod = pos;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                warnOnce("初始化面包直接操控兼容失败", failure);
                sessionsField = null;
                sessionOpticalField = null;
                sessionBindingField = null;
                bindingPosMethod = null;
            }
            reflectionLookupAttempted = true;
            return sessionsField != null && sessionOpticalField != null
                    && sessionBindingField != null && bindingPosMethod != null;
        }
    }

    private static float baseYawDegrees(Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }

    private static boolean finite(@Nullable Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static void warnOnce(String message, Throwable failure) {
        if (warningLogged) return;
        synchronized (MianbaoRemoteWeaponStationDirectAimCompat.class) {
            if (warningLogged) return;
            warningLogged = true;
            StabilizedTurret.LOGGER.warn("{}：{}", message, failure.toString());
        }
    }

    private record NativeSession(BlockPos pos, Direction baseFacing, boolean optical) {
    }

    private record AimSample(Vec3 direction, long tick) {
    }

    private static final class CommandState {
        private final BlockPos pos;
        private final UUID structureId;
        private float yaw;
        private float pitch;

        private CommandState(
                BlockPos pos, UUID structureId, float yaw, float pitch
        ) {
            this.pos = pos;
            this.structureId = structureId;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private MianbaoRemoteWeaponStationDirectAimCompat() {
    }
}
