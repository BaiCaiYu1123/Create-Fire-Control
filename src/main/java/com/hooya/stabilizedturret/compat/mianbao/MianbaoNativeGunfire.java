package com.hooya.stabilizedturret.compat.mianbao;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAutocannonProjectileLifetimeHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/** Supplies only trigger/pose to native gunfire; ammunition and ballistics remain native. */
public final class MianbaoNativeGunfire {
    private static final ThreadLocal<StationCommand> STATION_COMMAND = new ThreadLocal<>();
    private static final ThreadLocal<MountedShot> MOUNTED_SHOT = new ThreadLocal<>();
    private static final Map<Object, Long> STATION_TICKS = new WeakHashMap<>();
    private static Method stationTick;
    private static Method mountedFire;
    private static int mountedInterval;
    private static boolean lookupAttempted;
    private static boolean stationHookReady;
    private static boolean mountedHookReady;
    private static boolean warned;

    public static boolean tickStation(Object station, boolean trigger, boolean includeNativeTrigger) {
        lookup();
        if (stationTick == null || !stationHookReady) return false;
        StationCommand previous = STATION_COMMAND.get();
        StationCommand command = new StationCommand(station, trigger, includeNativeTrigger);
        STATION_COMMAND.set(command);
        try {
            stationTick.invoke(null, station);
            return command.ticked;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn(error);
            return false;
        } finally {
            restore(STATION_COMMAND, previous);
        }
    }

    /** Called at native tick HEAD. Bound stations are sampled by the computer, once per tick. */
    public static boolean beginStationTick(Object station) {
        if (!(station instanceof BlockEntity block) || block.getLevel() == null
                || block.getLevel().isClientSide) return true;
        StationCommand command = STATION_COMMAND.get();
        if (command == null || command.station != station) {
            if (ControllerTracker.isRemoteWeaponStationFireControlBound(
                    block.getLevel(), block.getBlockPos())) return false;
        }
        long now = block.getLevel().getGameTime();
        Long previous = STATION_TICKS.put(station, now);
        if (previous != null && previous == now) return false;
        if (command != null && command.station == station) command.ticked = true;
        return true;
    }

    public static boolean stationTrigger(boolean nativeTrigger) {
        StationCommand command = STATION_COMMAND.get();
        return command == null ? nativeTrigger
                : command.trigger || command.includeNativeTrigger && nativeTrigger;
    }

    /** The machine-gun key must never select or latch the separate anti-tank missile. */
    public static boolean stationMissileSelected(boolean nativeSelected) {
        StationCommand command = STATION_COMMAND.get();
        return command != null && command.trigger ? false : nativeSelected;
    }

    public static int mountedFireInterval() {
        lookup();
        return mountedInterval;
    }

    public static boolean fireMounted(ServerLevel root, BlockState gun, Vec3 muzzle, Vec3 direction) {
        lookup();
        if (mountedFire == null || !mountedHookReady) return false;
        MountedShot previous = MOUNTED_SHOT.get();
        MountedShot shot = new MountedShot(muzzle, direction);
        MOUNTED_SHOT.set(shot);
        try {
            BlockPos pos = BlockPos.containing(muzzle);
            mountedFire.invoke(null, root, (double) pos.getX(), (double) pos.getY(),
                    (double) pos.getZ(), gun);
            return shot.spawned;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn(error);
            return false;
        } finally {
            restore(MOUNTED_SHOT, previous);
        }
    }

    public static Vec3 mountedMuzzle(Vec3 nativeMuzzle) {
        MountedShot shot = MOUNTED_SHOT.get();
        return shot == null ? nativeMuzzle : shot.muzzle;
    }

    public static Vec3 mountedDirection(Vec3 nativeDirection) {
        MountedShot shot = MOUNTED_SHOT.get();
        return shot == null ? nativeDirection : shot.direction;
    }

    public static void beforeMountedSpawn(ServerLevel level, Entity shell) {
        if (MOUNTED_SHOT.get() != null) {
            MianbaoAutocannonProjectileLifetimeHandler.armDirectMachineGunRound(shell, level);
        }
    }

    public static void afterMountedSpawn(boolean accepted) {
        MountedShot shot = MOUNTED_SHOT.get();
        if (shot != null) shot.spawned = accepted;
    }

    // Mixin initialization marks readiness without loading native classes recursively.
    public static boolean stationHookReady() { stationHookReady = true; return true; }
    public static boolean mountedHookReady() { mountedHookReady = true; return true; }

    private static void lookup() {
        if (lookupAttempted) return;
        lookupAttempted = true;
        try {
            Class<?> controller = Class.forName("net.mcreator.myfirstmod.api.RemoteWeaponStationController");
            stationTick = Class.forName("net.mcreator.myfirstmod.remote.RemoteWeaponStationFireService")
                    .getMethod("tick", controller);
            mountedFire = Class.forName("net.mcreator.myfirstmod.procedures.HeavyMachineGunRedstoneFireProcedure")
                    .getMethod("execute", LevelAccessor.class, double.class, double.class,
                            double.class, BlockState.class);
            var interval = Class.forName("net.mcreator.myfirstmod.block.entity.HeavyMachineGunBlockEntity")
                    .getDeclaredField("FIRE_INTERVAL_TICKS");
            interval.setAccessible(true);
            mountedInterval = Math.max(1, interval.getInt(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn(error);
        }
    }

    private static void warn(Throwable error) {
        if (warned) return;
        warned = true;
        StabilizedTurret.LOGGER.warn("Mianbao native gunfire API is incompatible; gunfire disabled", error);
    }

    private static <T> void restore(ThreadLocal<T> local, T previous) {
        if (previous == null) local.remove(); else local.set(previous);
    }

    private static final class StationCommand {
        final Object station;
        final boolean trigger;
        final boolean includeNativeTrigger;
        boolean ticked;

        StationCommand(Object station, boolean trigger, boolean includeNativeTrigger) {
            this.station = station;
            this.trigger = trigger;
            this.includeNativeTrigger = includeNativeTrigger;
        }
    }

    private static final class MountedShot {
        final Vec3 muzzle;
        final Vec3 direction;
        boolean spawned;

        MountedShot(Vec3 muzzle, Vec3 direction) {
            this.muzzle = muzzle;
            this.direction = direction;
        }
    }

    private MianbaoNativeGunfire() {}
}
