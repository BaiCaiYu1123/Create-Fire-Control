package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.AcceleratingMissileIntercept;
import com.hooya.stabilizedturret.content.controller.ActiveRadarSeeker;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileDetonation;
import com.hooya.stabilizedturret.content.controller.MissileChunkTickets;
import com.hooya.stabilizedturret.content.controller.RwrThreat;
import com.hooya.stabilizedturret.content.controller.SableMissileFuze;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Homing ownership for air-defense missiles launched by a display computer. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AutoDefenseMissileGuidance {
    private static final int SAFETY_TICKS = MianbaoAirDefenseCompat.guidanceDelayTicks();
    private static final TicketType<UUID> MISSILE_TICKET = TicketType.create(
            "create_fire_control_display_interceptor", UUID::compareTo);
    private static Pending pending;
    private static final Map<Entity, Guided> guided = new IdentityHashMap<>();
    private static final Map<Entity, PendingRecovery> recovering = new IdentityHashMap<>();
    private static final Map<ConnectedDisplayBlockEntity, Map<UUID, ThreatEngagement>> engagements =
            new IdentityHashMap<>();
    private static final String DISPLAY_REF_TAG = "CreateFireControlDisplayRef";
    private static final String CONTACT_TAG = "CreateFireControlDisplayContact";
    private static final String CONTACT_SAMPLE_TICK_TAG = "CreateFireControlDisplayContactSampleTick";
    private static final String AGE_TAG = "CreateFireControlDisplayAge";
    private static final String INITIAL_X_TAG = "CreateFireControlDisplayInitialX";
    private static final String INITIAL_Y_TAG = "CreateFireControlDisplayInitialY";
    private static final String INITIAL_Z_TAG = "CreateFireControlDisplayInitialZ";

    public static boolean canLaunch(ConnectedDisplayBlockEntity display,
                                    ConnectedDisplayBlockEntity.Contact contact) {
        if (display == null || contact == null || !contact.missile()
                || display.isFriendlyFireControlMissile(contact)
                || !isThreatAlive(display, contact)) return false;
        ThreatEngagement engagement = engagement(display, contact.key().id());
        return engagement.launchesInWave == 0;
    }

    public static void noteLaunch(ConnectedDisplayBlockEntity display,
                                  ConnectedDisplayBlockEntity.Contact contact) {
        if (display == null || contact == null || !contact.missile()) return;
        engagement(display, contact.key().id()).launchesInWave++;
    }

    public static boolean hasLaunched(ConnectedDisplayBlockEntity display,
                                      ConnectedDisplayBlockEntity.Contact contact) {
        if (display == null || contact == null || !contact.missile()) return false;
        Map<UUID, ThreatEngagement> byThreat = engagements.get(display);
        ThreatEngagement engagement = byThreat == null
                ? null : byThreat.get(contact.key().id());
        return engagement != null && engagement.launchesInWave > 0
                && isThreatAlive(display, contact);
    }

    public static boolean isThreatAlive(ConnectedDisplayBlockEntity display,
                                        ConnectedDisplayBlockEntity.Contact contact) {
        if (contact == null || !contact.missile()) return false;
        Entity threat = findEntity(display, contact.key().id());
        return threat != null && threat.isAlive() && !threat.isRemoved();
    }

    public static void releaseDisplay(ConnectedDisplayBlockEntity display) {
        if (display == null) return;
        if (pending != null && pending.display == display) pending = null;
        boolean active = guided.values().stream().anyMatch(state -> state.display == display);
        if (!active) engagements.remove(display);
    }

    public static void cancelPending(ConnectedDisplayBlockEntity display) {
        if (pending != null && pending.display == display) pending = null;
    }

    public static List<RwrThreat> collectRwrMissileThreats(
            Level receiverLevel,
            Set<UUID> receiverStructure,
            Vec3 receiverOrigin,
            double range
    ) {
        if (receiverLevel == null || receiverStructure == null
                || receiverStructure.isEmpty()) return List.of();
        double rangeSqr = range * range;
        List<RwrThreat> result = new ArrayList<>();
        for (Map.Entry<Entity, Guided> entry : guided.entrySet()) {
            Entity missile = entry.getKey();
            ConnectedDisplayBlockEntity.Contact contact = entry.getValue().contact;
            if (missile == null || missile.isRemoved() || !missile.isAlive()
                    || contact == null || contact.missile()
                    || missile.level().getServer() != receiverLevel.getServer()
                    || !missile.level().dimension().equals(receiverLevel.dimension())
                    || (!receiverStructure.contains(contact.key().id())
                    && !intersects(contact.members(), receiverStructure))) continue;
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (position.distanceToSqr(receiverOrigin) > rangeSqr) continue;
            result.add(new RwrThreat(
                    missile.getUUID(), RwrThreat.Type.MSL, position, true));
        }
        return List.copyOf(result);
    }

    private static boolean intersects(Set<UUID> a, Set<UUID> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Set<UUID> smaller = a.size() <= b.size() ? a : b;
        Set<UUID> larger = smaller == a ? b : a;
        for (UUID id : smaller) if (larger.contains(id)) return true;
        return false;
    }

    public static void armNext(ConnectedDisplayBlockEntity display, BindingRef launcher,
                               ConnectedDisplayBlockEntity.Contact contact) {
        if (display == null || display.isFriendlyFireControlMissile(contact)) return;
        pending = new Pending(display, launcher, contact,
                display.getLevel() == null ? Long.MIN_VALUE : display.getLevel().getGameTime());
    }

    public static void cancelNext(ConnectedDisplayBlockEntity display) {
        if (pending != null && pending.display == display) pending = null;
    }

    /**
     * Lets the ordinary AA guidance subscriber leave the next display-launched missile alone.
     * Entity join subscriber order is not guaranteed, so the persistent ownership tag may not
     * have been written yet when the other subscriber runs.
     */
    public static boolean isPendingLaunch(Entity missile) {
        return matchesPendingLaunch(missile);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !MianbaoAirDefenseCompat.isMissile(event.getEntity())) return;
        Entity missile = event.getEntity();
        if (com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance.isManaged(missile)) return;
        var data = missile.getPersistentData();
        if (data.getBoolean("CreateFireControlDisplayGuided")) {
            if (!data.contains(DISPLAY_REF_TAG) || !data.contains(CONTACT_TAG)) {
                discardWithoutTerrain(missile);
                return;
            }
            PendingRecovery recovery = new PendingRecovery(
                    BindingRef.loadTag(data.getCompound(DISPLAY_REF_TAG)),
                    ConnectedDisplayBlockEntity.Contact.load(
                            data.getCompound(CONTACT_TAG)),
                    new Vec3(data.getDouble(INITIAL_X_TAG),
                            data.getDouble(INITIAL_Y_TAG),
                            data.getDouble(INITIAL_Z_TAG)),
                    Math.max(0, data.getInt(AGE_TAG)));
            if (!tryRestore(missile, recovery)) {
                recovery.chunkTickets.update(missile, null);
                recovering.put(missile, recovery);
            }
            return;
        }
        if (!matchesPendingLaunch(missile)) return;
        ThreatEngagement engagement = pending.contact.missile()
                ? engagement(pending.display, pending.contact.key().id()) : null;
        if (engagement != null) engagement.activeInterceptors++;
        Guided state = new Guided(missile, pending.display, pending.contact,
                MianbaoMissileCompat.getWorldVelocity(missile), engagement,
                MianbaoMissileCompat.getWorldPosition(missile));
        guided.put(missile, state);
        state.chunkTickets.update(missile, null);
        data.putBoolean("CreateFireControlAaGuided", true);
        data.putBoolean("CreateFireControlDisplayGuided", true);
        data.put(DISPLAY_REF_TAG, BindingRef.capture(
                pending.display.getLevel(), pending.display.getBlockPos()).save());
        FireControlMissileOwnership.markDisplay(missile, pending.display);
        data.put(CONTACT_TAG, pending.contact.save());
        data.putLong(CONTACT_SAMPLE_TICK_TAG, state.targetSampleTick);
        data.putDouble(INITIAL_X_TAG, state.initialDirection.x * state.initialSpeed);
        data.putDouble(INITIAL_Y_TAG, state.initialDirection.y * state.initialSpeed);
        data.putDouble(INITIAL_Z_TAG, state.initialDirection.z * state.initialSpeed);
        pending = null;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        tickRecovering();
        cleanupEngagements();
        Iterator<Map.Entry<Entity, Guided>> iterator = guided.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, Guided> entry = iterator.next();
            Entity missile = entry.getKey();
            Guided state = entry.getValue();
            if (missile.isRemoved() || !missile.isAlive()) {
                finishInterceptor(state);
                iterator.remove();
                continue;
            }
            MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
            if (missile.isRemoved()) continue;
            state.chunkTickets.update(missile, null);
            if (++state.age >= MianbaoAirDefenseCompat.getLifetimeTicks(missile)) {
                if (state.contact.missile()) discardWithoutTerrain(missile);
                else selfDestruct(missile);
                finishInterceptor(state);
                iterator.remove();
                continue;
            }
            missile.getPersistentData().putInt(AGE_TAG, state.age);
            ResolvedTarget resolved = resolveTarget(missile, state);
            if (resolved == null) {
                if (state.contact.missile()) discardWithoutTerrain(missile);
                else selfDestruct(missile);
                finishInterceptor(state);
                iterator.remove();
                continue;
            }
            Vec3 target = resolved.position();
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (state.contact.missile()) {
                Entity threat = findEntity(null, missile, state.contact.key().id());
                if (threat == null || threat.isRemoved() || !threat.isAlive()) {
                    missile.discard();
                    finishInterceptor(state);
                    iterator.remove();
                    continue;
                }
            }
            double proximityRange = state.proximityRange;
            // Ordinary radar contacts are living entities or Sable physical
            // structures. Their transmitted AABB can overlap a launcher even
            // when no target block is nearby (especially for a long, rotated
            // structure), so using that box as the proximity fuze made an AA
            // missile detonate shortly after launch. Use the resolved entity
            // position / physical centre of mass and sweep the travelled
            // segment so a fast missile cannot step over the fuze radius.
            if (!state.contact.missile() && state.age >= SAFETY_TICKS
                    && distanceToSegmentSqr(target, state.previousPosition, position)
                    <= proximityRange * proximityRange) {
                selfDestruct(missile);
                finishInterceptor(state);
                iterator.remove();
                continue;
            }
            state.previousPosition = position;
            double speed = state.speedAtAge();
            missile.setNoGravity(true);
            if (state.age <= SAFETY_TICKS) {
                MianbaoMissileCompat.setWorldVelocity(missile, state.initialDirection.scale(speed));
                continue;
            }
            state.flightDirection = AcceleratingMissileIntercept.guide(
                    position, state.flightDirection, target, resolved.velocity(),
                    state.speedProfile, state.age,
                    MianbaoAirDefenseCompat.getLifetimeTicks(missile) - state.age,
                    MianbaoAirDefenseCompat.maxTurnRadians(speed));
            MianbaoMissileCompat.setWorldVelocity(missile,
                    state.flightDirection.scale(speed));
        }
    }

    /** All normal-priority device handlers have moved their mathematical shots now.
     * Evaluate before LOWEST mathematical sync so both removals reach clients this tick. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMissileFuzes(ServerTickEvent.Post event) {
        Iterator<Map.Entry<Entity, Guided>> iterator = guided.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Entity interceptor = entry.getKey();
            Guided state = entry.getValue();
            if (!state.contact.missile()) continue;
            if (interceptor.isRemoved() || !interceptor.isAlive()) {
                finishInterceptor(state);
                iterator.remove();
                continue;
            }
            Entity threat = findEntity(null, interceptor, state.contact.key().id());
            if (threat == null || threat.isRemoved() || !threat.isAlive()) continue;
            long now = event.getServer().overworld().getGameTime();
            Vec3 position = MianbaoMissileCompat.getWorldPosition(interceptor);
            Vec3 threatPosition = MianbaoMissileCompat.getWorldPosition(threat);
            // Never join samples across a missing tick or a new/recovered launch.
            // Both samples are taken in this same post-movement phase.
            var contact = state.fuzeSampleTick == now - 1
                    ? MovingMissileFuze.firstContact(state.fuzePreviousPosition, position,
                            state.fuzePreviousThreatPosition, threatPosition, state.proximityRange)
                    : MovingMissileFuze.firstContact(position, position,
                            threatPosition, threatPosition, state.proximityRange);
            state.fuzePreviousPosition = position;
            state.fuzePreviousThreatPosition = threatPosition;
            state.fuzeSampleTick = now;
            if (state.age >= SAFETY_TICKS && contact != null) {
                intercept(interceptor, threat, contact.target(), state);
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Guided state = guided.get(event.getProjectile());
        if (state == null || !state.contact.missile()
                || !(event.getRayTraceResult() instanceof EntityHitResult entityHit)
                || !entityHit.getEntity().getUUID().equals(state.contact.key().id())) return;
        event.setCanceled(true);
        Entity interceptor = event.getProjectile();
        Entity threat = entityHit.getEntity();
        intercept(interceptor, threat, MianbaoMissileCompat.getWorldPosition(threat), state);
        guided.remove(interceptor);
    }

    private static ResolvedTarget resolveTarget(Entity missile, Guided state) {
        state.seeker.update(missile);
        var track = state.seeker.memory();
        return track == null ? null : new ResolvedTarget(AcceleratingMissileIntercept.targetPositionAt(
                track.center(), track.velocity(), track.tick(), missile.level().getGameTime()), track.velocity());
    }

    private record ResolvedTarget(Vec3 position, Vec3 velocity) {}

    private static void selfDestruct(Entity missile) {
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        // Keep each native AA missile's requested tripled blast strength. The
        // former hard-coded 3.0 reduced every display-guided proximity burst
        // to the fallback projectile strength.
        MianbaoMissileDetonation.detonateWithoutDrops(missile, position,
                MianbaoMissileDetonation.explosionStrength(missile));
    }

    private static void discardWithoutTerrain(Entity missile) {
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root != null) {
            MianbaoMissileEffects.broadcastDetonationVisual(root,
                    MianbaoMissileCompat.getWorldPosition(missile),
                    MianbaoDetonationVisualPayload.LITTLE_BOMB,
                    root.getRandom().nextLong());
        }
        missile.discard();
    }

    private static void intercept(Entity interceptor, Entity threat, Vec3 position, Guided state) {
        MianbaoMissileEffects.broadcastExplosion(interceptor, position,
                MianbaoMissileDetonation.explosionStrength(interceptor), false);
        threat.getPersistentData().putBoolean("CreateFireControlIntercepted", true);
        threat.discard();
        interceptor.discard();
        if (state.engagement != null) state.engagement.destroyed = true;
        finishInterceptor(state);
    }

    private static boolean tryRestore(Entity missile, PendingRecovery recovery) {
        ConnectedDisplayBlockEntity display =
                ConnectedDisplayBlockEntity.findLoaded(recovery.displayRef, missile);
        ThreatEngagement engagement = display != null && recovery.contact.missile()
                ? engagement(display, recovery.contact.key().id()) : null;
        if (engagement != null) {
            engagement.launchesInWave = Math.max(1, engagement.launchesInWave);
            engagement.activeInterceptors++;
        }
        Guided state = new Guided(missile, display, recovery.contact,
                recovery.initialVelocity, engagement,
                MianbaoMissileCompat.getWorldPosition(missile));
        state.age = recovery.age;
        Vec3 currentVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
        if (currentVelocity.lengthSqr() > 1.0e-8) state.flightDirection = currentVelocity.normalize();
        if (missile.getPersistentData().contains(CONTACT_SAMPLE_TICK_TAG)) {
            state.targetSampleTick = missile.getPersistentData().getLong(CONTACT_SAMPLE_TICK_TAG);
        }
        state.chunkTickets.update(missile, null);
        guided.put(missile, state);
        if (display != null) FireControlMissileOwnership.markDisplay(missile, display);
        recovery.chunkTickets.release();
        return true;
    }

    private static void tickRecovering() {
        Iterator<Map.Entry<Entity, PendingRecovery>> iterator =
                recovering.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, PendingRecovery> entry = iterator.next();
            Entity missile = entry.getKey();
            PendingRecovery recovery = entry.getValue();
            if (missile == null || missile.isRemoved() || !missile.isAlive()) {
                recovery.chunkTickets.release();
                iterator.remove();
                continue;
            }
            MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
            recovery.chunkTickets.update(missile, null);
            missile.getPersistentData().putInt(AGE_TAG, ++recovery.age);
            if (recovery.age >= MianbaoAirDefenseCompat.getLifetimeTicks(missile)) {
                if (recovery.contact.missile()) discardWithoutTerrain(missile);
                else selfDestruct(missile);
                recovery.chunkTickets.release();
                iterator.remove();
                continue;
            }
            if (tryRestore(missile, recovery)) iterator.remove();
        }
    }

    private static void cleanupEngagements() {
        Iterator<Map.Entry<ConnectedDisplayBlockEntity, Map<UUID, ThreatEngagement>>>
                displays = engagements.entrySet().iterator();
        while (displays.hasNext()) {
            Map.Entry<ConnectedDisplayBlockEntity, Map<UUID, ThreatEngagement>> entry =
                    displays.next();
            ConnectedDisplayBlockEntity display = entry.getKey();
            entry.getValue().entrySet().removeIf(threat -> {
                Entity entity = findEntity(display, threat.getKey());
                return threat.getValue().activeInterceptors <= 0
                        && (entity == null || !entity.isAlive());
            });
            boolean active = guided.values().stream()
                    .anyMatch(state -> state.display == display && !state.finished);
            if (entry.getValue().isEmpty()
                    || (display == null || display.isRemoved()) && !active) {
                displays.remove();
            }
        }
    }

    private static ThreatEngagement engagement(ConnectedDisplayBlockEntity display, UUID threatId) {
        return engagements.computeIfAbsent(display, ignored -> new HashMap<>())
                .computeIfAbsent(threatId, ignored -> new ThreatEngagement());
    }

    private static boolean matchesPendingLaunch(Entity missile) {
        if (missile == null || pending == null || pending.display.isRemoved()
                || pending.display.getLevel() == null) return false;
        long now = pending.display.getLevel().getGameTime();
        return now - pending.armedAt <= 2L
                && now >= pending.armedAt
                && MianbaoAirDefenseCompat.getLauncherSpawnDistanceSqr(
                pending.display.getLevel(), pending.launcher, missile) <= 144.0;
    }

    private static void finishInterceptor(Guided state) {
        state.chunkTickets.release();
        if (state.finished) return;
        state.finished = true;
        if (state.engagement != null) {
            state.engagement.activeInterceptors = Math.max(0,
                    state.engagement.activeInterceptors - 1);
        }
        cleanupReleasedDisplay(state.display);
    }

    private static void cleanupReleasedDisplay(ConnectedDisplayBlockEntity display) {
        if (display == null || !display.isRemoved()) return;
        boolean active = guided.values().stream()
                .anyMatch(state -> state.display == display && !state.finished);
        if (!active) engagements.remove(display);
    }

    private static Entity findEntity(ConnectedDisplayBlockEntity display, UUID id) {
        if (display == null || display.getLevel() == null) return null;
        Level rootLevel = SubLevelContainer.getContainer(display.getLevel()).getLevel();
        if (!(rootLevel instanceof ServerLevel root)) return null;
        return findEntity(root, id);
    }

    private static Entity findEntity(ConnectedDisplayBlockEntity ignored, Entity context, UUID id) {
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(context);
        return root == null ? null : findEntity(root, id);
    }

    private static Entity findEntity(ServerLevel root, UUID id) {
        Entity mathematical = com.hooya.stabilizedturret.content.controller.MathematicalMissileManager.find(root, id);
        if (mathematical != null) return mathematical;
        Entity entity = root.getEntity(id);
        if (entity != null) return entity;
        for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
            if (sub != null && !sub.isRemoved() && sub.getLevel() instanceof ServerLevel server) {
                entity = server.getEntity(id);
                if (entity != null) return entity;
            }
        }
        return null;
    }

    private static double distanceToSegmentSqr(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0e-12) return point.distanceToSqr(from);
        double fraction = Math.max(0.0, Math.min(1.0,
                point.subtract(from).dot(segment) / lengthSqr));
        return point.distanceToSqr(from.add(segment.scale(fraction)));
    }

    private record Pending(ConnectedDisplayBlockEntity display, BindingRef launcher,
                           ConnectedDisplayBlockEntity.Contact contact, long armedAt) {}
    private static final class PendingRecovery {
        final BindingRef displayRef;
        final ConnectedDisplayBlockEntity.Contact contact;
        final Vec3 initialVelocity;
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        int age;

        PendingRecovery(BindingRef displayRef,
                        ConnectedDisplayBlockEntity.Contact contact,
                        Vec3 initialVelocity, int age) {
            this.displayRef = displayRef;
            this.contact = contact;
            this.initialVelocity = initialVelocity;
            this.age = age;
        }
    }
    private static final class Guided {
        final ActiveRadarSeeker seeker;
        final double proximityRange;
        final ConnectedDisplayBlockEntity display;
        ConnectedDisplayBlockEntity.Contact contact;
        final Vec3 initialDirection;
        final double initialSpeed;
        final AcceleratingMissileIntercept.SpeedProfile speedProfile;
        final ThreatEngagement engagement;
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        Vec3 previousPosition;
        Vec3 fuzePreviousPosition;
        Vec3 fuzePreviousThreatPosition;
        long fuzeSampleTick = Long.MIN_VALUE;
        Vec3 flightDirection;
        long targetSampleTick;
        boolean finished;
        int age;
        Guided(Entity missile, ConnectedDisplayBlockEntity display, ConnectedDisplayBlockEntity.Contact contact,
               Vec3 initialVelocity, ThreatEngagement engagement, Vec3 initialPosition) {
            this.display=display; this.contact=contact;
            this.engagement=engagement;
            this.speedProfile = MianbaoAirDefenseCompat.speedProfile(missile, initialVelocity.length());
            this.initialSpeed = speedProfile.initialSpeed();
            this.initialDirection=initialVelocity.lengthSqr()<1.0e-8
                    ? new Vec3(0.0,0.0,1.0):initialVelocity.normalize();
            this.flightDirection = initialDirection;
            this.targetSampleTick = display == null ? missile.getPersistentData().getLong(CONTACT_SAMPLE_TICK_TAG)
                    : display.getContactSampleTick(contact.key());
            this.seeker = new ActiveRadarSeeker(contact.key().id(), ActiveRadarSeeker.AimPoint.PHYSICAL_CENTER,
                    missile.getPersistentData());
            this.seeker.seed(contact.center(), contact.velocity(), contact.bounds(), contact.members(), targetSampleTick);
            this.seeker.update(missile);
            String fuseTag = "CreateFireControlDisplayFuse";
            this.proximityRange = missile.getPersistentData().contains(fuseTag) ? missile.getPersistentData().getDouble(fuseTag)
                    : display == null ? ConnectedDisplayBlockEntity.DEFAULT_MISSILE_PROXIMITY_RANGE : display.getMissileProximityRange();
            missile.getPersistentData().putDouble(fuseTag, proximityRange);
            this.previousPosition=initialPosition;
        }
        double speedAtAge(){return speedProfile.speedAt(age);}
    }
    private static final class ThreatEngagement {
        int launchesInWave;
        int activeInterceptors;
        boolean destroyed;
    }
    @SubscribeEvent public static void onStop(ServerStoppingEvent event){
        guided.values().forEach(state -> state.chunkTickets.release());
        recovering.values().forEach(state -> state.chunkTickets.release());
        guided.clear();recovering.clear();engagements.clear();pending=null;
    }
    private AutoDefenseMissileGuidance() {}
}
