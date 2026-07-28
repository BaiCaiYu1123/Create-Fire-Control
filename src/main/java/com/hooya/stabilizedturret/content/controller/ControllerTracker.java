package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

public final class ControllerTracker {
    private static final Set<StabilizerControllerBlockEntity> LOADED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static void add(StabilizerControllerBlockEntity controller) {
        LOADED.add(controller);
    }

    public static void remove(StabilizerControllerBlockEntity controller) {
        LOADED.remove(controller);
    }

    public static @Nullable StabilizerControllerBlockEntity findForVehicle(Level level, @Nullable Entity vehicle) {
        if (vehicle == null) {
            return null;
        }
        String namespace = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getNamespace();
        if (!"create".equals(namespace) && !"interiors".equals(namespace)) {
            return null;
        }
        synchronized (LOADED) {
            return LOADED.stream()
                    .filter(controller -> controller.getLevel() == level)
                    .filter(controller -> !controller.isRemoved())
                    .filter(controller -> controller.isSeat(vehicle))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static @Nullable StabilizerControllerBlockEntity findForRemoteTerminal(
            Level level,
            BlockPos terminalPos,
            RemoteControllerCompat.Type type
    ) {
        synchronized (LOADED) {
            return LOADED.stream()
                    .filter(controller -> controller.getLevel() == level)
                    .filter(controller -> !controller.isRemoved())
                    .filter(controller -> RemoteTerminalDetector.ownsTerminal(
                            controller, level, terminalPos, type
                    ))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static @Nullable StabilizerControllerBlockEntity findForPlayerAim(ServerPlayer player) {
        StabilizerControllerBlockEntity remote = RemoteControlTracker.findController(player);
        if (remote != null) {
            return remote;
        }
        StabilizerControllerBlockEntity seated = findForVehicle(player.level(), player.getVehicle());
        return seated != null && !seated.isRemoteHeadAimEnabled() ? seated : null;
    }

    public static @Nullable StabilizerControllerBlockEntity findForMianbaoMissile(Entity missile) {
        if (!MianbaoMissileCompat.isGuidedMissile(missile)) {
            return null;
        }
        StabilizerControllerBlockEntity best = null;
        double bestDistanceSqr = 25.0;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved()
                        || controllerLevel == null
                        || controllerLevel.getServer() != missile.level().getServer()
                        || !controllerLevel.dimension().equals(missile.level().dimension())
                        || !controller.isMianbaoMissileGuidanceReady()) {
                    continue;
                }
                for (BindingRef launcher : controller.getMissileLaunchers()) {
                    double distanceSqr = MianbaoMissileCompat.getLauncherSpawnDistanceSqr(
                            controllerLevel, launcher, missile);
                    if (distanceSqr <= bestDistanceSqr) {
                        bestDistanceSqr = distanceSqr;
                        best = controller;
                    }
                }
            }
        }
        return best;
    }
    public static @Nullable StabilizerControllerBlockEntity findForMianbaoAaMissile(Entity missile) {
        if (!MianbaoAirDefenseCompat.isMissile(missile)) {
            return null;
        }
        StabilizerControllerBlockEntity best = null;
        double bestDistanceSqr = 25.0;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved() || controllerLevel == null
                        || controllerLevel.getServer() != missile.level().getServer()
                        || !controllerLevel.dimension().equals(missile.level().dimension())
                        || !controller.isRadarActive()
                        || controller.getRadarLockedSubLevel() == null) {
                    continue;
                }
                for (BindingRef launcher : controller.getAaMissileLaunchers()) {
                    double distance = MianbaoAirDefenseCompat.getLauncherSpawnDistanceSqr(
                            controllerLevel, launcher, missile);
                    if (distance <= bestDistanceSqr) {
                        bestDistanceSqr = distance;
                        best = controller;
                    }
                }
            }
        }
        return best;
    }

    public static @Nullable StabilizerControllerBlockEntity findForMianbaoAircraftMissile(Entity missile) {
        MianbaoAircraftCompat.WeaponType type = MianbaoAircraftCompat.typeOf(missile);
        if (type == null) return null;
        StabilizerControllerBlockEntity best = null;
        // The projectile can be spawned at the far edge of an assembled
        // cannon's contraption or after CBC has already advanced it one tick.
        // Keep the association generous and disambiguate using the nearest
        // bound muzzle below.
        double bestDistance = 256.0;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved() || controllerLevel == null
                        || controllerLevel.getServer() != missile.level().getServer()
                        || !controllerLevel.dimension().equals(missile.level().dimension())
                        || controller.getSelectedAircraftWeapon() != type
                        || !controller.isAircraftWeaponGuidanceReady(type)) continue;
                for (BindingRef rack : controller.getAircraftMissileRacks()) {
                    if (MianbaoAircraftCompat.weaponAt(controllerLevel, rack) != type) continue;
                    double distance = MianbaoAircraftCompat.spawnDistanceSqr(controllerLevel, rack, missile);
                    if (distance <= bestDistance) { bestDistance = distance; best = controller; }
                }
            }
        }
        return best;
    }

    public static @Nullable StabilizerControllerBlockEntity findForPodControlledCbcProjectile(
            Entity projectile
    ) {
        if (!isCbcProjectile(projectile)) return null;
        StabilizerControllerBlockEntity best = null;
        // This is only a candidate radius; the nearest bound muzzle still
        // wins. CBC add-ons and Sable may publish the spawned entity on the
        // parent level while the cannon mount is nested two physical levels
        // deep, and the first projected position can differ by more than the
        // old six-block cutoff on a large moving aircraft.
        double bestDistance = 1024.0;
        Vec3 projectileWorldPosition;
        try {
            projectileWorldPosition = dev.ryanhcode.sable.Sable.HELPER
                    .projectOutOfSubLevel(
                            projectile.level(), projectile.position());
        } catch (RuntimeException | LinkageError ignored) {
            projectileWorldPosition = projectile.position();
        }
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (!controller.isPodControlledCannonActive()
                        || controller.getPodGuidanceTarget() == null
                        || controller.getLevel() == null
                        || controller.getLevel().getServer() != projectile.level().getServer()
                        || !controller.getLevel().dimension().equals(projectile.level().dimension())) {
                    continue;
                }
                for (BindingRef mount : controller.getCannonMountBindings()) {
                    if (!mount.isSameTarget(controller.getLevel())
                            || !CannonMountCompat.hasAssembledCannon(
                            controller.getLevel(), mount)) {
                        continue;
                    }
                    Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(
                            controller.getLevel(), mount);
                    if (muzzle == null) continue;
                    double distance = muzzle.distanceToSqr(
                            projectileWorldPosition);
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = controller;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isCbcProjectile(Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if ("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile"
                    .equals(type.getName())) return true;
        }
        return false;
    }

    public static void invalidateBindingsAt(Level level, BlockPos pos) {
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (!controller.isRemoved()) {
                    controller.invalidateBindingsAt(level, pos);
                }
            }
        }
    }

    public static void releaseViews(ServerPlayer player) {
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (!controller.isRemoved()) controller.releaseViews(player);
            }
        }
    }

    public static boolean isBoundBearing(Level level, BlockPos pos) {
        return findBoundAssembledBearing(level, pos) != null;
    }

    /**
     * Snapshot of the two fire-control bearings belonging to loaded
     * controllers in this dimension. Physics post-processing uses the live
     * block entities so it can preserve ordinary bearings mounted farther
     * down the turret hierarchy without reflecting into BearingStabilizer's
     * legacy 01:02 state.
     */
    public static Set<SwivelBearingBlockEntity> getBoundAssembledBearings(Level level) {
        Set<SwivelBearingBlockEntity> result = new HashSet<>();
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (controller.isRemoved() || controller.getLevel() != level) {
                    continue;
                }
                for (BindingRef ref : controller.getAllFireControlBearingBindings()) {
                    if (ref != null
                            && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing
                            && bearing.isAssembled()
                            && bearing.getSubLevelID() != null) {
                        result.add(bearing);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Client-side safe variant of {@link #isBoundBearing} that never invokes
     * {@code Sable.HELPER.getContaining()}. The full resolver calls that API up
     * to four times per loaded controller, which blocks the render thread when
     * a sublevel is mid-physics-sync (e.g. immediately after CBC-AT projectile
     * impacts), causing the game to freeze when a player right-clicks any
     * bearing part while holding a Create linked controller or Create Tweaked
     * Controllers advanced remote.
     *
     * <p>This fast check only compares BlockPos values and queries
     * {@link Level#getBlockEntity} (a cheap chunk-cache lookup that never
     * touches the Sable sublevel API). It may return false negatives when the
     * bound bearing sits in a sublevel the client has not resolved yet, but in
     * that case the server-side authoritative handler still runs and protects
     * the bearing. The trade-off is acceptable: the goal is only to decide
     * whether to cancel the client-side interaction.
     */
    public static boolean isBoundBearingFast(Level level, BlockPos pos) {
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (controller.isRemoved()) {
                    continue;
                }
                for (BindingRef ref : controller.getAllFireControlBearingBindings()) {
                    if (ref == null) {
                        continue;
                    }
                    // Look up the bearing in the interacting level only.
                    // getBlockEntity is a pure chunk-cache read and does not
                    // resolve sublevels through Sable.HELPER.
                    if (!(level.getBlockEntity(ref.pos()) instanceof SwivelBearingBlockEntity bearing)) {
                        continue;
                    }
                    if (!bearing.isAssembled()) {
                        continue;
                    }
                    if (ref.pos().equals(pos) || pos.equals(bearing.getPlatePos())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static @Nullable SwivelBearingBlockEntity findBoundAssembledBearing(Level level, BlockPos pos) {
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (controller.isRemoved()) {
                    continue;
                }
                for (BindingRef ref : controller.getAllFireControlBearingBindings()) {
                    if (ref == null) {
                        continue;
                    }
                    Level controllerLevel = controller.getLevel();
                    SwivelBearingBlockEntity bearing = ref.resolve(level) instanceof SwivelBearingBlockEntity direct
                            ? direct
                            : controllerLevel != null && ref.resolve(controllerLevel) instanceof SwivelBearingBlockEntity fallback
                            ? fallback
                            : null;
                    if (bearing == null || !bearing.isAssembled()) {
                        continue;
                    }
                    // The visible bearing head is a separate link/plate block.
                    // Protect both the bound base and the assembled plate.
                    boolean baseMatches = ref.matches(level, pos)
                            || controllerLevel != null && ref.matches(controllerLevel, pos);
                    if (baseMatches || pos.equals(bearing.getPlatePos())) {
                        return bearing;
                    }
                }
            }
        }
        return null;
    }

    private ControllerTracker() {
    }
}
