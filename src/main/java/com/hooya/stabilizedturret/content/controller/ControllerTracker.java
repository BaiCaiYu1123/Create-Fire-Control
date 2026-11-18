package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** Snapshot used by server-side systems that need all loaded controllers. */
    public static List<StabilizerControllerBlockEntity> loadedControllers() {
        synchronized (LOADED) {
            return List.copyOf(LOADED);
        }
    }

    public static boolean hasLoadedControllers() { return !LOADED.isEmpty(); }

    /** Matches the bound mount's live assembled entity, including nested Sable plots. */
    public static @Nullable StabilizerControllerBlockEntity findForCannonRecoil(Entity cannon) {
        if (cannon == null || cannon.isRemoved() || cannon.level().isClientSide
                || cannon.level().getServer() == null) return null;
        StabilizerControllerBlockEntity match = null;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level level = controller.getLevel();
                if (controller.isRemoved() || level == null || level.isClientSide
                        || level.getServer() != cannon.level().getServer()
                        || !level.dimension().equals(cannon.level().dimension())
                        || !controller.isStabilizerReactionEnabled()) continue;
                for (BindingRef mount : controller.getCannonMountBindings()) {
                    if (CannonMountCompat.getMountedCannonEntity(level, mount) != cannon) continue;
                    // Conflicting owners must not select a random multiplier.
                    if (match != null && match != controller) return null;
                    match = controller;
                    break;
                }
            }
        }
        return match;
    }

    /** Resolves a persisted controller reference without loading any chunk. */
    public static @Nullable StabilizerControllerBlockEntity findLoaded(
            Level level, BlockPos pos
    ) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        synchronized (LOADED) {
            return LOADED.stream()
                    .filter(controller -> controller.getLevel() == level)
                    .filter(controller -> !controller.isRemoved())
                    .filter(controller -> controller.getBlockPos().equals(pos))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Resolves the exact controller that opened a settings screen. */
    public static @Nullable StabilizerControllerBlockEntity findLoadedForSettings(
            ServerPlayer player,
            BlockPos pos,
            @Nullable java.util.UUID subLevelId
    ) {
        if (player == null || pos == null) return null;
        StabilizerControllerBlockEntity match = null;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved() || controllerLevel == null
                        || controllerLevel.isClientSide
                        || controllerLevel.getServer() != player.getServer()
                        || !controllerLevel.dimension().equals(player.level().dimension())
                        || !controller.getBlockPos().equals(pos)) {
                    continue;
                }
                SubLevel containing = Sable.HELPER.getContaining(controller);
                java.util.UUID currentSubLevelId = containing == null
                        ? null : containing.getUniqueId();
                if (!java.util.Objects.equals(subLevelId, currentSubLevelId)) continue;
                if (match != null && match != controller) return null;
                match = controller;
            }
        }
        return match;
    }

    /**
     * Resolves a controller saved on a projectile across root/Sable level
     * instances without loading a chunk. New saves include the exact Sable
     * owner; old saves fall back to an unambiguous same-position match.
     */
    public static @Nullable StabilizerControllerBlockEntity findLoadedForMissile(
            Entity missile,
            BlockPos pos,
            @Nullable java.util.UUID subLevelId,
            boolean exactSubLevel
    ) {
        if (missile == null || pos == null || missile.level().getServer() == null) return null;
        StabilizerControllerBlockEntity match = null;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved() || controllerLevel == null
                        || controllerLevel.getServer() != missile.level().getServer()
                        || !controllerLevel.dimension().equals(missile.level().dimension())
                        || !controller.getBlockPos().equals(pos)) {
                    continue;
                }
                SubLevel containing = Sable.HELPER.getContaining(controller);
                java.util.UUID currentSubLevel = containing == null
                        ? null : containing.getUniqueId();
                if (exactSubLevel && !java.util.Objects.equals(
                        subLevelId, currentSubLevel)) continue;
                if (match != null && match != controller) return null;
                match = controller;
            }
        }
        return match;
    }

    public static @Nullable java.util.UUID controllerSubLevelId(
            StabilizerControllerBlockEntity controller
    ) {
        SubLevel containing = controller == null ? null : Sable.HELPER.getContaining(controller);
        return containing == null ? null : containing.getUniqueId();
    }

    public static List<RwrThreat> collectRwrRadarThreats(
            StabilizerControllerBlockEntity receiver,
            Set<java.util.UUID> receiverStructure,
            Vec3 receiverOrigin,
            double range
    ) {
        if (receiver == null || receiver.getLevel() == null
                || receiverStructure == null || receiverStructure.isEmpty()) {
            return List.of();
        }
        double rangeSqr = range * range;
        List<RwrThreat> result = new ArrayList<>();
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity source : Set.copyOf(LOADED)) {
                Level sourceLevel = source == null ? null : source.getLevel();
                if (source == null || source == receiver || source.isRemoved()
                        || sourceLevel == null || sourceLevel.isClientSide
                        || sourceLevel.getServer() != receiver.getLevel().getServer()
                        || !sourceLevel.dimension().equals(receiver.getLevel().dimension())
                        || !source.isRadarActive()
                        || intersects(source.getOwnControlledSubLevelIds(), receiverStructure)
                        || source.getRadarContacts().stream().noneMatch(
                        contact -> contactTargets(contact, receiverStructure))) {
                    continue;
                }
                Vec3 position = source.getRadarOrigin();
                if (position.distanceToSqr(receiverOrigin) > rangeSqr) continue;
                result.add(new RwrThreat(
                        RwrThreat.sourceId(
                                "controller",
                                sourceLevel,
                                source.getBlockPos(),
                                controllerSubLevelId(source)),
                        source.isAircraftRadarMode()
                                ? RwrThreat.Type.RFL : RwrThreat.Type.AAA,
                        position,
                        false));
            }
        }
        return List.copyOf(result);
    }

    private static boolean contactTargets(
            StabilizerControllerBlockEntity.RadarContact contact,
            Set<java.util.UUID> target
    ) {
        return target.contains(contact.subLevelId())
                || intersects(contact.memberIds(), target);
    }

    private static boolean intersects(Set<java.util.UUID> a, Set<java.util.UUID> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Set<java.util.UUID> smaller = a.size() <= b.size() ? a : b;
        Set<java.util.UUID> larger = smaller == a ? b : a;
        for (java.util.UUID id : smaller) if (larger.contains(id)) return true;
        return false;
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

    /** The optics and their lock cues must follow the same controller as the visible aim session. */
    public static @Nullable StabilizerControllerBlockEntity playerViewController(ServerPlayer player,
            @Nullable StabilizerControllerBlockEntity seated, @Nullable StabilizerControllerBlockEntity input) {
        if (input != null && (input.isPodViewEnabled(player) || input.isScopeEnabled(player)
                || input.isRemoteHeadAimEnabled())) return input;
        return seated;
    }

    public static boolean shouldBlockCompetingHeavyCannonAim(
            Level level, BlockPos pos
    ) {
        if (level == null || level.isClientSide || pos == null) return false;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved() || controllerLevel == null
                        || controllerLevel.isClientSide
                        || controllerLevel.getServer() != level.getServer()
                        || !controllerLevel.dimension().equals(level.dimension())) {
                    continue;
                }
                if (controller.blocksCompetingHeavyCannonAim(level, pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A bound mount gives the HMG trigger exclusively to its fire-control computer. */
    public static boolean isMountedMachineGunFireControlBound(
            PitchOrientedContraptionEntity cannon
    ) {
        if (cannon == null || cannon.level().isClientSide) return false;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                if (!controller.isRemoved()
                        && controller.controlsCannonContraption(cannon)) return true;
            }
        }
        return false;
    }

    /** Lets the computer sample native station gunfire without a second block-tick writer. */
    public static boolean isRemoteWeaponStationFireControlBound(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || pos == null) return false;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level owner = controller.getLevel();
                if (controller.isRemoved() || owner == null || owner.isClientSide
                        || owner.getServer() != level.getServer()
                        || !owner.dimension().equals(level.dimension())) continue;
                for (BindingRef ref : controller.getRemoteWeaponStations()) {
                    if (ref.pos().equals(pos) && ref.isSameTarget(level)) return true;
                }
            }
        }
        return false;
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
                        // A locked radar track can be a structure or an entity
                        // (including a detached mathematical missile).
                        || controller.getRadarContact(controller.getRadarLockedSubLevelId()) == null) {
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
        MianbaoAircraftLaunch launch = findMianbaoAircraftLaunch(missile);
        return launch == null ? null : launch.controller();
    }

    public static @Nullable MianbaoAircraftLaunch findMianbaoAircraftLaunch(Entity missile) {
        MianbaoAircraftCompat.WeaponType type = MianbaoAircraftCompat.typeOf(missile);
        if (type == null) return null;
        var launch = MianbaoAircraftRackCompat.currentLaunch(missile);
        if (launch != null) {
            var controller = launch.controller();
            Level owner = controller.getLevel();
            return owner != null && !controller.isRemoved()
                    && owner.getServer() == missile.level().getServer()
                    && owner.dimension().equals(missile.level().dimension())
                    && controller.getSelectedAircraftWeapon() == type
                    && controller.isAircraftWeaponGuidanceReady(type)
                    && controller.getAircraftMissileRacks().contains(launch.rack())
                    && launch.rack().isSameTarget(owner)
                    ? new MianbaoAircraftLaunch(controller, launch.rack()) : null;
        }
        StabilizerControllerBlockEntity best = null;
        BindingRef bestRack = null;
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
                    if (MianbaoAircraftRackCompat.weaponAt(controllerLevel, rack) != type) continue;
                    double distance = MianbaoAircraftCompat.spawnDistanceSqr(controllerLevel, rack, missile);
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = controller;
                        bestRack = rack;
                    }
                }
            }
        }
        return best == null || bestRack == null
                ? null : new MianbaoAircraftLaunch(best, bestRack);
    }

    public record MianbaoAircraftLaunch(
            StabilizerControllerBlockEntity controller,
            BindingRef rack
    ) {
    }

    public static @Nullable StabilizerControllerBlockEntity findForMianbaoRocket(Entity rocket) {
        MianbaoRocketLaunch launch = findMianbaoRocketLaunch(rocket);
        return launch == null ? null : launch.controller();
    }

    public static @Nullable MianbaoRocketLaunch findMianbaoRocketLaunch(Entity rocket) {
        if (!MianbaoRocketPodCompat.isRocket(rocket)) return null;
        StabilizerControllerBlockEntity best = null;
        BindingRef bestPod = null;
        double bestDistance = 256.0;
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved() || controllerLevel == null
                        || controllerLevel.getServer() != rocket.level().getServer()
                        || !controllerLevel.dimension().equals(rocket.level().dimension())
                        || controller.getSelectedAircraftWeapon()
                        != MianbaoAircraftCompat.WeaponType.ROCKET_POD) {
                    continue;
                }
                for (BindingRef pod : controller.getAircraftRocketPods()) {
                    double distance = MianbaoRocketPodCompat.spawnDistanceSqr(
                            controllerLevel, pod, rocket);
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = controller;
                        bestPod = pod;
                    }
                }
            }
        }
        return best == null || bestPod == null
                ? null : new MianbaoRocketLaunch(best, bestPod);
    }

    public record MianbaoRocketLaunch(
            StabilizerControllerBlockEntity controller,
            BindingRef pod
    ) {
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

    /**
     * Cheap launch-time gate for the pod-ballistics hook. Ordinary CBC
     * projectiles are common (especially rotary autocannons) and must not be
     * retried through every loaded controller when no pod cannon is nearby.
     * The exact muzzle/profile association remains in
     * {@link #findForPodControlledCbcProjectile(Entity)}.
     */
    public static boolean hasNearbyPodControlledCannon(Entity projectile) {
        if (!isCbcProjectile(projectile)) return false;
        Vec3 projectileWorldPosition;
        try {
            projectileWorldPosition = dev.ryanhcode.sable.Sable.HELPER
                    .projectOutOfSubLevel(projectile.level(), projectile.position());
        } catch (RuntimeException | LinkageError ignored) {
            projectileWorldPosition = projectile.position();
        }
        synchronized (LOADED) {
            for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                Level controllerLevel = controller.getLevel();
                if (controller.isRemoved()
                        || !controller.isPodControlledCannonActive()
                        || controller.getPodGuidanceTarget() == null
                        || controllerLevel == null
                        || controllerLevel.getServer() != projectile.level().getServer()
                        || !controllerLevel.dimension().equals(projectile.level().dimension())) {
                    continue;
                }
                for (BindingRef mount : controller.getCannonMountBindings()) {
                    Vec3 mountCenter = mount.worldCenter(controllerLevel);
                    if (mountCenter != null
                            && mountCenter.distanceToSqr(projectileWorldPosition) <= 1024.0) {
                        return true;
                    }
                }
            }
        }
        return false;
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
