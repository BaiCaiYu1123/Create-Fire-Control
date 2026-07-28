package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Independent radar and automatic-weapon computer hosted by a display wall. */
public final class ConnectedDisplayBlockEntity extends BlockEntity {
    private static final int SCAN_INTERVAL = 2;
    private static final double OWN_STRUCTURE_CLEARANCE_DISTANCE = 32.0;
    public static final double MIN_RANGE = 100.0;
    public static final double MAX_RANGE = 10_000.0;
    public static final double MIN_CANNON_FIRE_RANGE = 20.0;
    public static final double MAX_CANNON_FIRE_RANGE = 200.0;

    private final Map<BindingSlot, BindingRef> bindings = new HashMap<>();
    private final List<BindingRef> cannonMounts = new ArrayList<>();
    private final List<BindingRef> aaLaunchers = new ArrayList<>();
    private final Map<TargetKey, Vec3> previousCenters = new HashMap<>();
    private List<Contact> contacts = List.of();
    private boolean automaticAttack;
    private double radarRange = 2_000.0;
    private double cannonFireRange = MAX_CANNON_FIRE_RANGE;
    private double yawSpeed = 120.0;
    private double pitchSpeed = 90.0;
    private double depressionLimit = 20.0;
    private double elevationLimit = 45.0;
    private boolean yawReversed;
    private boolean pitchReversed;
    private @Nullable TargetKey selectedTarget;
    private @Nullable TargetKey manualFireTarget;
    private long manualFireUntil = Long.MIN_VALUE;
    private long lastScan = Long.MIN_VALUE;
    private Vec3 previousRadarOrigin;
    private Vec3 radarVelocity = Vec3.ZERO;
    private int cannonPulseTicks;
    private long lastMissileFire = Long.MIN_VALUE;
    private long lastCannonFireDiagnostic = Long.MIN_VALUE;
    private long automaticStructureFireStartedAt = Long.MIN_VALUE;
    private List<Set<UUID>> cachedTopology = List.of();
    private Set<UUID> cachedTopologyIds = Set.of();
    private long cachedTopologyAt = Long.MIN_VALUE;

    public ConnectedDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONNECTED_DISPLAY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state,
                            ConnectedDisplayBlockEntity display) {
        if (!level.isClientSide) display.serverTick();
    }

    private void serverTick() {
        removeInvalidBindings();
        maintainBoundBearings();
        if (cannonPulseTicks > 0 && --cannonPulseTicks == 0) {
            setCannonFire(false);
        }
        if (!isRadarActive()) {
            updateContacts(List.of());
            setCannonFire(false);
            manualFireTarget = null;
            manualFireUntil = Long.MIN_VALUE;
            returnBearingsToNeutral();
            return;
        }
        long now = level.getGameTime();
        if (lastScan == Long.MIN_VALUE || now - lastScan >= SCAN_INTERVAL) {
            scan(now);
            lastScan = now;
        }
        Contact target = selectedContact();
        if (automaticAttack) {
            boolean advance = target == null
                    || (!target.key().living()
                    && automaticStructureFireStartedAt != Long.MIN_VALUE
                    && now - automaticStructureFireStartedAt >= 100L);
            if (advance) {
                Contact next = nextAutomaticContact(selectedTarget);
                TargetKey nextKey = next == null ? null : next.key();
                if (!java.util.Objects.equals(selectedTarget, nextKey)) {
                    selectedTarget = nextKey;
                    sync();
                }
                target = next;
                automaticStructureFireStartedAt = Long.MIN_VALUE;
            } else {
                target = selectedContact();
            }
            if (target == null && selectedTarget != null) {
                selectedTarget = null;
                sync();
            }
        }
        boolean manualFiring = target != null
                && manualFireTarget != null
                && manualFireTarget.equals(target.key())
                && (target.key().living() || now < manualFireUntil);
        if (manualFireTarget != null && !manualFiring) {
            manualFireTarget = null;
            manualFireUntil = Long.MIN_VALUE;
        }
        if (target == null) {
            if (selectedTarget != null && !automaticAttack) {
                selectedTarget = null;
                sync();
            }
            setCannonFire(false);
            returnBearingsToNeutral();
            return;
        }
        engage(target, now, automaticAttack || manualFiring);
    }

    public boolean bind(BindingSlot slot, BindingRef ref) {
        if (slot == BindingSlot.CANNON_MOUNT) {
            if (!cannonMounts.contains(ref)) cannonMounts.add(ref);
        } else if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
            if (!aaLaunchers.contains(ref)) aaLaunchers.add(ref);
        } else if (slot == BindingSlot.RADAR_BEARING
                || slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
            bindings.put(slot, ref);
        } else if (slot == BindingSlot.BEARING) {
            if (bindings.get(BindingSlot.YAW) == null) bindings.put(BindingSlot.YAW, ref);
            else bindings.put(BindingSlot.PITCH, ref);
        } else {
            return false;
        }
        sync();
        return true;
    }

    public void setSettings(boolean automaticAttack, double radarRange, double yawSpeed,
                            double pitchSpeed, double depressionLimit, double elevationLimit,
                            boolean yawReversed, boolean pitchReversed,
                            double cannonFireRange) {
        if (this.automaticAttack != automaticAttack) {
            automaticStructureFireStartedAt = Long.MIN_VALUE;
        }
        this.automaticAttack = automaticAttack;
        this.radarRange = Mth.clamp(radarRange, MIN_RANGE, MAX_RANGE);
        this.cannonFireRange = Mth.clamp(
                cannonFireRange,
                MIN_CANNON_FIRE_RANGE,
                MAX_CANNON_FIRE_RANGE);
        this.yawSpeed = Mth.clamp(yawSpeed, 0.0, 720.0);
        this.pitchSpeed = Mth.clamp(pitchSpeed, 0.0, 720.0);
        this.depressionLimit = Mth.clamp(depressionLimit, 0.0, 180.0);
        this.elevationLimit = Mth.clamp(elevationLimit, 0.0, 180.0);
        this.yawReversed = yawReversed;
        this.pitchReversed = pitchReversed;
        if (!automaticAttack) setCannonFire(false);
        sync();
    }

    public void setRadarRange(double radarRange) {
        double next = Mth.clamp(radarRange, MIN_RANGE, MAX_RANGE);
        if (Math.abs(this.radarRange - next) < 0.01) return;
        this.radarRange = next;
        sync();
    }

    public void toggleAutomaticAttack() {
        automaticAttack = !automaticAttack;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        automaticStructureFireStartedAt = Long.MIN_VALUE;
        if (!automaticAttack) setCannonFire(false);
        sync();
    }

    public void clearSelectedTarget() {
        selectedTarget = null;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        automaticStructureFireStartedAt = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
    }

    public void requestManualFire() {
        Contact selected = selectedContact();
        if (selected == null) return;
        automaticAttack = false;
        manualFireTarget = selected.key();
        manualFireUntil = selected.key().living()
                ? Long.MAX_VALUE
                : level.getGameTime() + 100L;
        sync();
    }

    public boolean requestMissileFire() {
        if (level == null || aaLaunchers.isEmpty()) return false;
        Contact selected = selectedContact();
        if (selected == null) return false;
        long now = level.getGameTime();
        if (lastMissileFire != Long.MIN_VALUE
                && now - lastMissileFire < 2L) return false;
        for (BindingRef launcher : aaLaunchers) {
            if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }
            AutoDefenseMissileGuidance.armNext(this, launcher, selected);
            if (!MianbaoAirDefenseCompat.fireOne(level, launcher)) {
                AutoDefenseMissileGuidance.cancelNext(this);
                continue;
            }
            lastMissileFire = now;
            return true;
        }
        return false;
    }

    public boolean handleScreenInteraction(
            ConnectedDisplayLayout.ScreenHit hit
    ) {
        double x = hit.canvasX();
        double y = hit.canvasY();
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.SLIDER_X1,
                ConnectedDisplayLayout.SLIDER_Y1,
                ConnectedDisplayLayout.SLIDER_X2,
                ConnectedDisplayLayout.SLIDER_Y2
        )) {
            setRadarRange(ConnectedDisplayLayout.sliderRange(x));
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.AUTO_X1,
                ConnectedDisplayLayout.AUTO_Y1,
                ConnectedDisplayLayout.AUTO_X2,
                ConnectedDisplayLayout.AUTO_Y2
        )) {
            toggleAutomaticAttack();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.FIRE_X1,
                ConnectedDisplayLayout.FIRE_Y1,
                ConnectedDisplayLayout.FIRE_X2,
                ConnectedDisplayLayout.FIRE_Y2
        )) {
            requestManualFire();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.CLEAR_X1,
                ConnectedDisplayLayout.CLEAR_Y1,
                ConnectedDisplayLayout.CLEAR_X2,
                ConnectedDisplayLayout.CLEAR_Y2
        )) {
            clearSelectedTarget();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.MISSILE_X1,
                ConnectedDisplayLayout.MISSILE_Y1,
                ConnectedDisplayLayout.MISSILE_X2,
                ConnectedDisplayLayout.MISSILE_Y2
        )) {
            requestMissileFire();
            return true;
        }
        if (x < 0.0 || x > ConnectedDisplayLayout.RADAR_W
                || y < ConnectedDisplayLayout.HEADER_H
                || y > ConnectedDisplayLayout.CANVAS_H) {
            return false;
        }
        ConnectedDisplayLayout.RadarGeometry geometry =
                ConnectedDisplayLayout.radarGeometry(
                        hit.wallWidth(), hit.wallHeight());
        Vec3 origin = radarOrigin();
        Contact nearest = null;
        double nearestDistance = 30.0 * 30.0;
        for (Contact contact : contacts) {
            ConnectedDisplayLayout.RadarPoint point =
                    ConnectedDisplayLayout.radarPoint(
                            contact.center(), origin, radarRange, geometry);
            double distance = (point.x() - x) * (point.x() - x)
                    + (point.y() - y) * (point.y() - y);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = contact;
            }
        }
        if (nearest == null) return false;
        selectedTarget = nearest.key();
        automaticAttack = false;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
        return true;
    }

    public boolean isAutomaticAttack() { return automaticAttack; }
    public double getRadarRange() { return radarRange; }
    public double getCannonFireRange() { return cannonFireRange; }
    public double getYawSpeed() { return yawSpeed; }
    public double getPitchSpeed() { return pitchSpeed; }
    public double getDepressionLimit() { return depressionLimit; }
    public double getElevationLimit() { return elevationLimit; }
    public boolean isYawReversed() { return yawReversed; }
    public boolean isPitchReversed() { return pitchReversed; }
    public boolean hasBoundMissileLauncher() { return !aaLaunchers.isEmpty(); }
    public @Nullable TargetKey getSelectedTarget() { return selectedTarget; }
    public boolean isSelected(Contact contact) {
        return selectedTarget != null && selectedTarget.equals(contact.key());
    }
    public Vec3 getRadarWorldOrigin() { return radarOrigin(); }
    public List<Contact> getContacts() { return contacts; }
    public List<Device> getDevices() {
        List<Device> result = new ArrayList<>();
        bindings.forEach((slot, ref) -> result.add(new Device(slot, ref)));
        cannonMounts.forEach(ref -> result.add(new Device(BindingSlot.CANNON_MOUNT, ref)));
        aaLaunchers.forEach(ref -> result.add(new Device(BindingSlot.AA_MISSILE_LAUNCHER, ref)));
        return List.copyOf(result);
    }

    public boolean isBound(BindingRef ref) {
        return bindings.containsValue(ref)
                || cannonMounts.contains(ref)
                || aaLaunchers.contains(ref);
    }

    private boolean isRadarActive() {
        if (level == null) return false;
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        if (ref == null || !(ref.resolve(level) instanceof MechanicalBearingBlockEntity bearing)) return false;
        return bearing.isRunning() && bearing.getMovedContraption() != null
                && Math.abs(bearing.getAngularSpeed()) > 1.0e-3F;
    }

    private Vec3 radarOrigin() {
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        BlockEntity radar = ref == null ? null : ref.resolve(level);
        if (radar != null && radar.getLevel() != null) {
            return Sable.HELPER.projectOutOfSubLevel(radar.getLevel(), Vec3.atCenterOf(radar.getBlockPos()));
        }
        return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(worldPosition));
    }

    private void scan(long now) {
        Vec3 origin = radarOrigin();
        if (previousRadarOrigin != null) radarVelocity = origin.subtract(previousRadarOrigin).scale(1.0 / SCAN_INTERVAL);
        previousRadarOrigin = origin;
        List<Set<UUID>> topology = subLevelTopology(now);
        Set<UUID> own = ownStructureIds(topology);
        List<Contact> found = new ArrayList<>();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        for (Set<UUID> cluster : topology) {
            if (cluster.stream().anyMatch(own::contains)) continue;
            AABB bounds = null;
            Set<UUID> members = new HashSet<>();
            for (UUID memberId : cluster) {
                SubLevel member = container.getSubLevel(memberId);
                if (member == null || member.isRemoved() || own.contains(member.getUniqueId())) continue;
                AABB part = com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity
                        .subLevelWorldBounds(member);
                bounds = bounds == null ? part : union(bounds, part);
                members.add(member.getUniqueId());
            }
            if (bounds == null) continue;
            UUID key = members.stream().min(UUID::compareTo).orElse(null);
            if (key == null) continue;
            addContact(found, new TargetKey(key, false), members,
                    bounds.getCenter(), bounds, origin, now);
        }
        Set<UUID> livingVisited = new HashSet<>();
        if (level instanceof ServerLevel server) {
            AABB query = new AABB(origin, origin).inflate(radarRange);
            for (LivingEntity living : server.getEntitiesOfClass(LivingEntity.class, query,
                    entity -> entity.isAlive() && !entity.isSpectator())) {
                SubLevel containing = Sable.HELPER.getContaining(living);
                if (containing != null && own.contains(containing.getUniqueId())) continue;
                if (!livingVisited.add(living.getUUID())) continue;
                addContact(found, new TargetKey(living.getUUID(), true), Set.of(),
                        living.getBoundingBox().getCenter(), living.getBoundingBox(), origin, now);
            }
        }
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved() || own.contains(sub.getUniqueId())) continue;
            Vector3d localOrigin = new Vector3d(origin.x, origin.y, origin.z);
            sub.logicalPose().transformPositionInverse(localOrigin);
            AABB localQuery = new AABB(localOrigin.x, localOrigin.y, localOrigin.z,
                    localOrigin.x, localOrigin.y, localOrigin.z).inflate(radarRange);
            for (LivingEntity living : sub.getLevel().getEntitiesOfClass(LivingEntity.class, localQuery,
                    entity -> entity.isAlive() && !entity.isSpectator())) {
                if (!livingVisited.add(living.getUUID())) continue;
                AABB worldBounds = projectBounds(sub.getLevel(), living.getBoundingBox());
                addContact(found, new TargetKey(living.getUUID(), true), Set.of(),
                        worldBounds.getCenter(), worldBounds, origin, now);
            }
        }
        found.sort(Comparator.comparingDouble(Contact::closingRate).reversed()
                .thenComparingDouble(Contact::distance));
        updateContacts(found.size() > 64 ? List.copyOf(found.subList(0, 64)) : List.copyOf(found));
        previousCenters.keySet().retainAll(found.stream().map(Contact::key).collect(java.util.stream.Collectors.toSet()));
    }

    private void addContact(List<Contact> found, TargetKey key, Set<UUID> members,
                            Vec3 center, AABB bounds, Vec3 origin, long now) {
        double distance = center.distanceTo(origin);
        if (distance > radarRange || distance < 1.0) return;
        Vec3 previous = previousCenters.put(key, center);
        Vec3 velocity = previous == null ? Vec3.ZERO : center.subtract(previous).scale(1.0 / SCAN_INTERVAL);
        Vec3 los = center.subtract(origin).normalize();
        double closing = -(velocity.subtract(radarVelocity)).dot(los) * 20.0;
        found.add(new Contact(key, members, center, velocity, bounds, distance, closing));
    }

    private void engage(Contact contact, long now, boolean fire) {
        AimSolution solution = solveAim(contact);
        Vec3 aimTarget = solution.target();
        Vec3 measured = measuredWeaponDirection();
        Vec3 origin = measuredWeaponOrigin();
        if (measured == null || origin == null) return;
        Vec3 requested = solution.launchDirection() == null
                ? aimTarget.subtract(origin)
                : solution.launchDirection();
        if (requested.lengthSqr() < 1.0e-8) return;
        requested = requested.normalize();
        commandBearing(BindingSlot.YAW, true, measured, requested);
        commandBearing(BindingSlot.PITCH, false, measured, requested);

        if (!cannonMounts.isEmpty()) {
            for (BindingRef mount : cannonMounts) {
                if (!CannonMountCompat.hasAssembledCannon(level, mount)) {
                    CannonMountCompat.setAutomaticFire(level, mount, false);
                    continue;
                }
                CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.analyze(level, mount);
                AimSolution mountSolution = solveAim(contact, profile);
                Vec3 predicted = mountSolution.target();
                Vec3 requiredDirection =
                        mountSolution.launchDirection() == null
                                ? null
                                : mountSolution.launchDirection().normalize();
                // An automatic-defense display treats a cannon mount as a
                // fixed ballistic sensor and trigger only. It must never
                // write the mount's own yaw/pitch: the bound Aeronautics
                // physical bearings are the sole aiming actuators.
                Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, mount);
                Vec3 direction = CannonMountCompat.getAssembledCannonDirection(level, mount);
                if (muzzle != null && requiredDirection == null
                        && predicted.distanceToSqr(muzzle) > 1.0e-8) {
                    requiredDirection = predicted.subtract(muzzle).normalize();
                }
                Vec3 normalizedDirection = direction == null
                        || direction.lengthSqr() < 1.0e-8
                        ? null : direction.normalize();
                double targetDistance = muzzle == null
                        ? Double.POSITIVE_INFINITY
                        : muzzle.distanceTo(contact.center());
                double alignmentDot = normalizedDirection == null
                        || requiredDirection == null
                        ? -1.0
                        : Mth.clamp(
                        normalizedDirection.dot(requiredDirection),
                        -1.0,
                        1.0
                );
                double angleError = Math.toDegrees(Math.acos(alignmentDot));
                boolean safe = fire && muzzle != null
                        && normalizedDirection != null
                        && requiredDirection != null
                        && targetDistance <= cannonFireRange
                        // Compare the barrel against the gravity/drag
                        // compensated launch vector. Comparing it with the
                        // straight line to the target rejects every correctly
                        // elevated gun and made both manual and automatic fire
                        // appear non-functional.
                        && alignmentDot
                        > Math.cos(Math.toRadians(3.0));
                // Do not use the own-structure clip as a firing gate. Sable
                // nested structures can report the assembled barrel/turret
                // plot along this ray even when the measured CBC muzzle is
                // already clear, which permanently blocked every shot.
                // CBC remains authoritative for the projectile's real block
                // and entity collision after it leaves the muzzle.
                boolean queued = CannonMountCompat.setAutomaticFire(
                        level, mount, safe);
                if (safe
                        && automaticAttack
                        && !contact.key().living()
                        && automaticStructureFireStartedAt == Long.MIN_VALUE) {
                    automaticStructureFireStartedAt = now;
                }
                if (fire && (lastCannonFireDiagnostic == Long.MIN_VALUE
                        || now - lastCannonFireDiagnostic >= 20L)) {
                    StabilizedTurret.LOGGER.info(
                            "Display cannon fire request: display={} mount={} assembled=true distance={} limit={} angleError={} safe={} queued={}",
                            worldPosition,
                            mount.pos(),
                            String.format(java.util.Locale.ROOT, "%.2f", targetDistance),
                            String.format(java.util.Locale.ROOT, "%.2f", cannonFireRange),
                            String.format(java.util.Locale.ROOT, "%.2f", angleError),
                            safe,
                            queued
                    );
                    lastCannonFireDiagnostic = now;
                }
            }
            cannonPulseTicks = fire ? 2 : 0;
        }
        boolean launcherSafe = clearOfOwnStructure(
                origin.add(measured.normalize().scale(0.35)), aimTarget);
        if (fire && launcherSafe && !aaLaunchers.isEmpty()
                && (lastMissileFire == Long.MIN_VALUE
                || now - lastMissileFire >= 40L)) {
            for (BindingRef launcher : aaLaunchers) {
                Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, launcher);
                if (muzzle == null || !clearOfOwnStructure(
                        muzzle.add(measured.normalize().scale(0.35)),
                        muzzle.add(measured.normalize().scale(12.0)))) continue;
                if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) > 0) {
                    AutoDefenseMissileGuidance.armNext(this, launcher, contact);
                    if (!MianbaoAirDefenseCompat.fireOne(level, launcher)) {
                        AutoDefenseMissileGuidance.cancelNext(this);
                        continue;
                    }
                    lastMissileFire = now;
                    if (automaticAttack
                            && !contact.key().living()
                            && automaticStructureFireStartedAt == Long.MIN_VALUE) {
                        automaticStructureFireStartedAt = now;
                    }
                    break;
                }
            }
        }
    }

    private @Nullable Contact selectedContact() {
        if (selectedTarget == null) return null;
        for (Contact contact : contacts) {
            if (selectedTarget.equals(contact.key())) return contact;
        }
        return null;
    }

    private @Nullable Contact nextAutomaticContact(
            @Nullable TargetKey previous
    ) {
        if (contacts.isEmpty()) return null;
        if (previous == null) return contacts.get(0);
        for (int index = 0; index < contacts.size(); index++) {
            if (previous.equals(contacts.get(index).key())) {
                return contacts.get((index + 1) % contacts.size());
            }
        }
        return contacts.get(0);
    }

    private AimSolution solveAim(Contact contact) {
        CannonBallisticsCompat.ShotProfile profile = cannonMounts.stream()
                .filter(ref -> CannonMountCompat.hasAssembledCannon(level, ref))
                .map(ref -> CannonBallisticsCompat.analyze(level, ref))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return solveAim(contact, profile);
    }

    /**
     * Iterates a moving-target intercept against CBC's exact discrete
     * trajectory solver. The profile already contains the firing structure's
     * inherited Sable velocity, while the contact velocity supplies target
     * motion, so stationary, carrier-moving and target-moving cases share one
     * solution.
     */
    private AimSolution solveAim(
            Contact contact,
            @Nullable CannonBallisticsCompat.ShotProfile profile
    ) {
        Vec3 target = contact.center();
        if (profile == null) {
            double ticks = Math.min(200.0,
                    contact.distance() / 8.0);
            Vec3 predicted = target.add(contact.velocity().scale(ticks));
            Vec3 origin = measuredWeaponOrigin();
            return new AimSolution(
                    predicted,
                    origin == null ? null : predicted.subtract(origin).normalize()
            );
        }
        Vec3 predicted = target;
        Vec3 direction = null;
        double flightTicks = Math.min(
                profile.maxFlightTicks(),
                contact.distance() / Math.max(0.1, profile.muzzleSpeed())
        );
        for (int iteration = 0; iteration < 5; iteration++) {
            predicted = target.add(contact.velocity().scale(flightTicks));
            direction = CannonBallisticsCompat.solveLaunchDirection(
                    profile, predicted);
            if (direction == null) break;
            Vec3 worldLaunchVelocity = direction.scale(profile.muzzleSpeed())
                    .add(profile.carrierVelocity());
            double closingSpeed = Math.max(
                    0.1,
                    worldLaunchVelocity.subtract(contact.velocity())
                            .dot(predicted.subtract(profile.muzzle()).normalize())
            );
            double next = Mth.clamp(
                    predicted.distanceTo(profile.muzzle()) / closingSpeed,
                    0.0,
                    profile.maxFlightTicks()
            );
            if (Math.abs(next - flightTicks) < 0.05) {
                flightTicks = next;
                break;
            }
            flightTicks = (flightTicks + next) * 0.5;
        }
        if (direction == null) {
            direction = predicted.subtract(profile.muzzle()).normalize();
        }
        return new AimSolution(predicted, direction);
    }

    private void commandBearing(BindingSlot slot, boolean yaw, Vec3 measured, Vec3 requested) {
        BindingRef ref = bindings.get(slot);
        if (ref != null && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()) {
            BearingStabilizer.commandConstrainedAim(bearing, measured, requested, yaw,
                    yaw ? yawSpeed : pitchSpeed,
                    yaw ? 180.0 : depressionLimit,
                    yaw ? 180.0 : elevationLimit,
                    yaw ? yawReversed : pitchReversed);
        }
    }

    private void maintainBoundBearings() {
        maintainBearing(BindingSlot.YAW, true);
        maintainBearing(BindingSlot.PITCH, false);
    }

    private void returnBearingsToNeutral() {
        returnBearingToNeutral(BindingSlot.YAW, true);
        returnBearingToNeutral(BindingSlot.PITCH, false);
    }

    private void returnBearingToNeutral(BindingSlot slot, boolean yaw) {
        BindingRef ref = bindings.get(slot);
        if (ref == null
                || !(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled()) return;
        BearingStabilizer.commandConstrainedNeutral(
                bearing,
                yaw,
                yaw ? yawSpeed : pitchSpeed,
                yaw ? 180.0 : depressionLimit,
                yaw ? 180.0 : elevationLimit
        );
    }

    private void maintainBearing(BindingSlot slot, boolean yaw) {
        BindingRef ref = bindings.get(slot);
        if (ref == null || !(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled()) return;
        BearingStabilizer.maintainConstrainedBinding(bearing, yaw,
                yaw ? 180.0 : depressionLimit,
                yaw ? 180.0 : elevationLimit);
    }

    private @Nullable Vec3 measuredWeaponDirection() {
        for (BindingRef mount : cannonMounts) {
            Vec3 direction = CannonMountCompat.getAssembledCannonDirection(level, mount);
            if (direction != null) return direction;
        }
        BindingRef launcher = aaLaunchers.isEmpty() ? null : aaLaunchers.get(0);
        if (launcher == null) return null;
        Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, launcher);
        Vec3 base = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(launcher.pos()));
        return muzzle == null ? null : muzzle.subtract(base).normalize();
    }

    private @Nullable Vec3 measuredWeaponOrigin() {
        for (BindingRef mount : cannonMounts) {
            Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, mount);
            if (muzzle != null) return muzzle;
        }
        return aaLaunchers.isEmpty() ? null
                : MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, aaLaunchers.get(0));
    }

    private void setCannonFire(boolean powered) {
        for (BindingRef mount : cannonMounts) CannonMountCompat.setAutomaticFire(level, mount, powered);
    }

    private boolean clearOfOwnStructure(Vec3 start, Vec3 end) {
        if (level == null || start == null || end == null) return false;
        Vec3 limitedEnd = limitSegment(
                start, end, OWN_STRUCTURE_CLEARANCE_DISTANCE);
        for (UUID id : ownStructureIds(subLevelTopology(level == null ? 0L : level.getGameTime()))) {
            SubLevel sub = SubLevelContainer.getContainer(level).getSubLevel(id);
            if (sub == null || sub.isRemoved()) continue;
            Vector3d localStart = new Vector3d(start.x, start.y, start.z);
            Vector3d localEnd = new Vector3d(
                    limitedEnd.x, limitedEnd.y, limitedEnd.z);
            sub.logicalPose().transformPositionInverse(localStart);
            sub.logicalPose().transformPositionInverse(localEnd);
            Vec3 localStartVec = new Vec3(
                    localStart.x, localStart.y, localStart.z);
            Vec3 localEndVec = new Vec3(
                    localEnd.x, localEnd.y, localEnd.z);
            // Level.clip obtains block states along the complete segment.
            // On C2ME, touching an unloaded chunk can synchronously wait for
            // that chunk on the server thread and reduce TPS to zero. This
            // safety check only needs the vehicle immediately in front of the
            // muzzle, so never request or wait for missing chunks.
            if (!isSegmentLoaded(sub.getLevel(), localStartVec, localEndVec)) {
                return false;
            }
            ClipContext context = new ClipContext(
                    localStartVec, localEndVec,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    CollisionContext.empty());
            BlockHitResult hit = sub.getLevel().clip(context);
            if (hit.getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    private static Vec3 limitSegment(
            Vec3 start, Vec3 end, double maxDistance) {
        Vec3 delta = end.subtract(start);
        double lengthSqr = delta.lengthSqr();
        double maximum = Math.max(0.0, maxDistance);
        if (!Double.isFinite(lengthSqr)
                || !Double.isFinite(maximum)
                || lengthSqr <= 1.0e-12) {
            return start;
        }
        if (lengthSqr <= maximum * maximum) return end;
        return start.add(delta.scale(maximum / Math.sqrt(lengthSqr)));
    }

    private static boolean isSegmentLoaded(
            Level level, Vec3 start, Vec3 end) {
        if (level == null
                || !Double.isFinite(start.x)
                || !Double.isFinite(start.y)
                || !Double.isFinite(start.z)
                || !Double.isFinite(end.x)
                || !Double.isFinite(end.y)
                || !Double.isFinite(end.z)) {
            return false;
        }
        Vec3 delta = end.subtract(start);
        int samples = Math.max(1, Mth.ceil(delta.length() / 4.0));
        for (int index = 0; index <= samples; index++) {
            double fraction = (double) index / samples;
            double x = Mth.lerp(fraction, start.x, end.x);
            double z = Mth.lerp(fraction, start.z, end.z);
            int chunkX = Mth.floor(x) >> 4;
            int chunkZ = Mth.floor(z) >> 4;
            if (!level.hasChunk(chunkX, chunkZ)) return false;
        }
        return true;
    }

    private Set<UUID> ownStructureIds(List<Set<UUID>> topology) {
        Set<UUID> seeds = new HashSet<>();
        SubLevel containing = Sable.HELPER.getContaining(this);
        if (containing != null) seeds.add(containing.getUniqueId());
        bindings.values().forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        cannonMounts.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        aaLaunchers.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        Set<UUID> result = new HashSet<>(seeds);
        for (Set<UUID> cluster : topology) {
            if (cluster.stream().anyMatch(seeds::contains)) result.addAll(cluster);
        }
        return result;
    }

    private List<Set<UUID>> subLevelTopology(long now) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        List<SubLevel> loaded = new ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub != null && !sub.isRemoved()) loaded.add(sub);
        }
        Set<UUID> ids = loaded.stream().map(SubLevel::getUniqueId).collect(java.util.stream.Collectors.toSet());
        if (cachedTopologyAt != Long.MIN_VALUE && now >= cachedTopologyAt
                && now - cachedTopologyAt < 100L && cachedTopologyIds.equals(ids)) return cachedTopology;
        Map<UUID, UUID> parent = new HashMap<>();
        ids.forEach(id -> parent.put(id, id));
        for (SubLevel sub : loaded) {
            for (SubLevel connected : SubLevelHelper.getConnectedChain(sub)) {
                if (connected != null && ids.contains(connected.getUniqueId()))
                    union(parent, sub.getUniqueId(), connected.getUniqueId());
            }
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor
                    : sub.getPlot().getBlockEntityActors()) {
                if (actor instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()
                        && bearing.getSubLevelID() != null && ids.contains(bearing.getSubLevelID())) {
                    union(parent, sub.getUniqueId(), bearing.getSubLevelID());
                }
            }
        }
        Map<UUID, Set<UUID>> groups = new java.util.LinkedHashMap<>();
        for (UUID id : ids) groups.computeIfAbsent(find(parent, id), ignored -> new HashSet<>()).add(id);
        cachedTopology = groups.values().stream().map(Set::copyOf).toList();
        cachedTopologyIds = Set.copyOf(ids);
        cachedTopologyAt = now;
        return cachedTopology;
    }

    private static UUID find(Map<UUID, UUID> parent, UUID id) {
        UUID root = parent.get(id);
        if (root == null || root.equals(id)) return id;
        root = find(parent, root);
        parent.put(id, root);
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        UUID rootA = find(parent, a), rootB = find(parent, b);
        if (!rootA.equals(rootB)) parent.put(rootB, rootA);
    }

    private static AABB projectBounds(Level source, AABB local) {
        AABB result = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(source, new Vec3(
                    (corner & 1) == 0 ? local.minX : local.maxX,
                    (corner & 2) == 0 ? local.minY : local.maxY,
                    (corner & 4) == 0 ? local.minZ : local.maxZ));
            AABB point = new AABB(world, world);
            result = result == null ? point : union(result, point);
        }
        return result == null ? local : result;
    }

    private void removeInvalidBindings() {
        if (level == null) return;
        bindings.entrySet().removeIf(entry -> !entry.getValue().isSameTarget(level));
        cannonMounts.removeIf(ref -> !ref.isSameTarget(level));
        aaLaunchers.removeIf(ref -> !ref.isSameTarget(level));
    }

    private void updateContacts(List<Contact> next) {
        if (contacts.equals(next)) return;
        contacts = next;
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private static AABB union(AABB a, AABB b) {
        return new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("AutomaticAttack", automaticAttack);
        tag.putDouble("RadarRange", radarRange);
        tag.putDouble("CannonFireRange", cannonFireRange);
        tag.putDouble("YawSpeed", yawSpeed);
        tag.putDouble("PitchSpeed", pitchSpeed);
        tag.putDouble("DepressionLimit", depressionLimit);
        tag.putDouble("ElevationLimit", elevationLimit);
        tag.putBoolean("YawReversed", yawReversed);
        tag.putBoolean("PitchReversed", pitchReversed);
        if (selectedTarget != null) {
            tag.putUUID("SelectedTarget", selectedTarget.id());
            tag.putBoolean("SelectedTargetLiving", selectedTarget.living());
        }
        bindings.forEach((slot, ref) -> tag.put("Binding_" + slot.name(), ref.save()));
        ListTag mounts = new ListTag(); cannonMounts.forEach(ref -> mounts.add(ref.save())); tag.put("CannonMounts", mounts);
        ListTag launchers = new ListTag(); aaLaunchers.forEach(ref -> launchers.add(ref.save())); tag.put("AaLaunchers", launchers);
        ListTag contactTags = new ListTag(); contacts.forEach(contact -> contactTags.add(contact.save())); tag.put("Contacts", contactTags);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        automaticAttack = tag.getBoolean("AutomaticAttack");
        radarRange = Mth.clamp(tag.contains("RadarRange") ? tag.getDouble("RadarRange") : 2000.0, MIN_RANGE, MAX_RANGE);
        cannonFireRange = Mth.clamp(
                tag.contains("CannonFireRange")
                        ? tag.getDouble("CannonFireRange")
                        : MAX_CANNON_FIRE_RANGE,
                MIN_CANNON_FIRE_RANGE,
                MAX_CANNON_FIRE_RANGE);
        yawSpeed = Mth.clamp(tag.contains("YawSpeed") ? tag.getDouble("YawSpeed") : 120.0, 0.0, 720.0);
        pitchSpeed = Mth.clamp(tag.contains("PitchSpeed") ? tag.getDouble("PitchSpeed") : 90.0, 0.0, 720.0);
        depressionLimit = Mth.clamp(tag.contains("DepressionLimit") ? tag.getDouble("DepressionLimit") : 20.0, 0.0, 180.0);
        elevationLimit = Mth.clamp(tag.contains("ElevationLimit") ? tag.getDouble("ElevationLimit") : 45.0, 0.0, 180.0);
        yawReversed = tag.getBoolean("YawReversed");
        pitchReversed = tag.getBoolean("PitchReversed");
        selectedTarget = tag.hasUUID("SelectedTarget")
                ? new TargetKey(
                tag.getUUID("SelectedTarget"),
                tag.getBoolean("SelectedTargetLiving"))
                : null;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        bindings.clear();
        for (BindingSlot slot : List.of(BindingSlot.RADAR_BEARING, BindingSlot.YAW, BindingSlot.PITCH)) {
            BindingRef ref = BindingRef.load(tag, "Binding_" + slot.name()); if (ref != null) bindings.put(slot, ref);
        }
        cannonMounts.clear(); for (Tag value : tag.getList("CannonMounts", Tag.TAG_COMPOUND)) cannonMounts.add(BindingRef.loadTag((CompoundTag)value));
        aaLaunchers.clear(); for (Tag value : tag.getList("AaLaunchers", Tag.TAG_COMPOUND)) aaLaunchers.add(BindingRef.loadTag((CompoundTag)value));
        contacts = tag.getList("Contacts", Tag.TAG_COMPOUND).stream().map(value -> Contact.load((CompoundTag)value)).toList();
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        if (pkt.getTag() != null) loadAdditional(pkt.getTag(), registries);
    }

    public record Device(BindingSlot slot, BindingRef ref) {}
    public record TargetKey(UUID id, boolean living) {}
    private record AimSolution(Vec3 target, @Nullable Vec3 launchDirection) {}
    public record Contact(TargetKey key, Set<UUID> members, Vec3 center, Vec3 velocity,
                          AABB bounds, double distance, double closingRate) {
        CompoundTag save() { CompoundTag t=new CompoundTag(); t.putUUID("Id",key.id); t.putBoolean("Living",key.living);
            t.putDouble("X",center.x);t.putDouble("Y",center.y);t.putDouble("Z",center.z);
            t.putDouble("VX",velocity.x);t.putDouble("VY",velocity.y);t.putDouble("VZ",velocity.z);
            t.putDouble("MinX",bounds.minX);t.putDouble("MinY",bounds.minY);t.putDouble("MinZ",bounds.minZ);
            t.putDouble("MaxX",bounds.maxX);t.putDouble("MaxY",bounds.maxY);t.putDouble("MaxZ",bounds.maxZ);
            t.putDouble("Distance",distance);t.putDouble("Closing",closingRate); return t; }
        static Contact load(CompoundTag t) { return new Contact(new TargetKey(t.getUUID("Id"),t.getBoolean("Living")),Set.of(),
                new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z")),new Vec3(t.getDouble("VX"),t.getDouble("VY"),t.getDouble("VZ")),
                new AABB(t.getDouble("MinX"),t.getDouble("MinY"),t.getDouble("MinZ"),t.getDouble("MaxX"),t.getDouble("MaxY"),t.getDouble("MaxZ")),
                t.getDouble("Distance"),t.getDouble("Closing")); }
    }
}
