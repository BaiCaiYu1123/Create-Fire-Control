package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Ordinary AA rounds use the computer's measured beam, not a radar intercept. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class OrdinaryAirDefenseGuidance {
    public static final String GUIDED_TAG = "CreateFireControlOrdinaryAaGuided";
    static final String AGE_TAG = "CreateFireControlOrdinaryAaAge";
    static final String FUSE_TAG = "CreateFireControlOrdinaryAaFuse";
    private static final String OWNER_TAG = "CreateFireControlOrdinaryAaOwner";
    private static final String INITIAL_SPEED_TAG = "CreateFireControlOrdinaryAaInitialSpeed";
    public static final double MAX_SPEED = 340.3 / 20.0;
    public static final double LAUNCH_SPEED = 2.0;
    public static final int ACCELERATION_TICKS = 10 * 20;
    public static final int LIFETIME_TICKS = 20 * 20;
    private static final double MANEUVERABILITY = 4.0;
    static final double MAX_TURN_RADIANS = BeamRidingGuidance.MAX_TURN_RADIANS * MANEUVERABILITY;
    private static final TicketType<UUID> TICKET = TicketType.create("create_fire_control_ordinary_aa", UUID::compareTo);
    private static final Map<Entity, Flight> ACTIVE = new IdentityHashMap<>();

    public static boolean isOrdinaryType(EntityType<?> type) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id.getNamespace().equals(MianbaoMissileCompat.MOD_ID)
                && id.getPath().equals("portableantiairmissiletanshe");
    }

    public static boolean isManaged(Entity missile) {
        return missile != null && isOrdinaryType(missile.getType())
                && missile.getPersistentData().getBoolean(GUIDED_TAG);
    }

    static AcceleratingMissileIntercept.SpeedProfile speedProfile(double initialSpeed) {
        return new AcceleratingMissileIntercept.SpeedProfile(
                Double.isFinite(initialSpeed) ? Math.max(0.1, Math.min(MAX_SPEED, initialSpeed)) : LAUNCH_SPEED,
                MAX_SPEED, ACCELERATION_TICKS);
    }

    static Vec3 guide(Vec3 position, Vec3 velocity, Vec3 origin, Vec3 beam, double speed) {
        return BeamRidingGuidance.guide(position, velocity, origin, beam, speed, MANEUVERABILITY);
    }

    public static void adopt(Entity missile, StabilizerControllerBlockEntity controller) {
        var data = missile.getPersistentData();
        data.putBoolean(GUIDED_TAG, true);
        data.putBoolean("CreateFireControlGuided", true);
        data.put(OWNER_TAG, BindingRef.capture(controller.getLevel(), controller.getBlockPos()).save());
        data.putDouble(INITIAL_SPEED_TAG, MianbaoMissileCompat.getWorldVelocity(missile).length());
        data.putDouble(FUSE_TAG, controller.getAaProximityFuseRange());
        data.putInt(AGE_TAG, 0);
        FireControlMissileOwnership.markController(missile, controller);
        missile.setNoGravity(true);
        ACTIVE.put(missile, new Flight(missile, controller));
    }

    static void cancelLaunch(Entity missile) {
        Flight flight = ACTIVE.remove(missile);
        if (flight != null) flight.tickets.release();
        missile.discard();
    }

    /** Restore the exact computer reference and age after a save/load. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity missile = event.getEntity();
        if (event.getLevel().isClientSide() || !isManaged(missile) || ACTIVE.containsKey(missile)) return;
        ACTIVE.put(missile, new Flight(missile, null));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Entity missile = entry.getKey();
            Flight flight = entry.getValue();
            if (!missile.isRemoved() && missile.isAlive()) {
                MianbaoMissileCompat.advanceIfNativeTickMissed(missile, false);
            }
            if (missile.isRemoved() || !missile.isAlive()) {
                flight.tickets.release();
                MianbaoMissileRemoteSync.remove(missile);
                iterator.remove();
                continue;
            }
            int age = missile.getPersistentData().getInt(AGE_TAG) + 1;
            missile.getPersistentData().putInt(AGE_TAG, age);
            if (age >= MissileSettings.lifetimeTicks(missile)) {
                missile.discard(); // Timeout is deliberately silent, without a warhead detonation.
                flight.tickets.release();
                MianbaoMissileRemoteSync.remove(missile);
                iterator.remove();
                continue;
            }
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (age >= MianbaoAirDefenseCompat.guidanceDelayTicks()) {
                var root = MianbaoMissileCompat.getRootServerLevel(missile);
                var contact = SableMissileFuze.firstBlockContact(root, flight.previousPosition, position);
                // Match the existing AA fuze: zero disables proximity only,
                // and the swept segment catches targets crossed in one tick.
                if (contact != null || flight.proximityFuseRange > 0.0
                        && SableMissileFuze.anyCenterOfMassWithinRange(root,
                        flight.previousPosition, position, flight.proximityFuseRange)) {
                    MianbaoMissileDetonation.detonate(missile, contact == null ? position : contact.position());
                    flight.tickets.release();
                    MianbaoMissileRemoteSync.remove(missile);
                    iterator.remove();
                    continue;
                }
            }
            flight.previousPosition = position;
            var computer = flight.controller;
            if (computer == null || computer.isRemoved()) {
                computer = flight.owner == null ? null : ControllerTracker.findLoadedForMissile(
                        missile, flight.owner.pos(), flight.owner.subLevelId(), true);
                if (computer != null && !flight.owner.isSameTarget(computer.getLevel())) computer = null;
                flight.controller = computer;
            }
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
            double speed = flight.profile.speedAt(age);
            Vec3 direction = velocity.lengthSqr() > 1.0e-8 ? velocity.normalize() : flight.lastDirection;
            Vec3 commanded = direction.scale(speed);
            if (age >= 3 && computer != null && !computer.isRemoved()) {
                Vec3 origin = computer.getMeasuredAimOrigin(), beam = computer.getMeasuredAimDirection();
                if (origin != null && beam != null && beam.lengthSqr() > 1.0e-8) {
                    commanded = guide(MianbaoMissileCompat.getWorldPosition(missile),
                            velocity, origin, beam, speed);
                }
            }
            flight.lastDirection = commanded.normalize();
            missile.setNoGravity(true);
            MianbaoMissileCompat.setWorldVelocity(missile, commanded);
            flight.tickets.update(missile, commanded, 96.0);
            MianbaoMissileRemoteSync.broadcast(missile);
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        for (Flight flight : ACTIVE.values()) flight.tickets.release();
        ACTIVE.clear();
    }

    private static final class Flight {
        final BindingRef owner;
        final AcceleratingMissileIntercept.SpeedProfile profile;
        final double proximityFuseRange;
        final MissileChunkTickets tickets = new MissileChunkTickets(TICKET, true);
        StabilizerControllerBlockEntity controller;
        Vec3 lastDirection;
        Vec3 previousPosition;

        Flight(Entity missile, StabilizerControllerBlockEntity controller) {
            this.owner = BindingRef.load(missile.getPersistentData(), OWNER_TAG);
            this.controller = controller;
            double maximum = MissileSettings.speedBlocksPerTick(missile);
            double initial = missile.getPersistentData().getDouble(INITIAL_SPEED_TAG);
            this.profile = new AcceleratingMissileIntercept.SpeedProfile(
                    Double.isFinite(initial) ? Math.max(.1, Math.min(maximum, initial)) : LAUNCH_SPEED,
                    maximum, MissileSettings.accelerationTicks(missile, ACCELERATION_TICKS));
            this.proximityFuseRange = Math.max(0.0, Math.min(10.0,
                    missile.getPersistentData().getDouble(FUSE_TAG)));
            this.previousPosition = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
            lastDirection = velocity.lengthSqr() > 1.0e-8 ? velocity.normalize() : new Vec3(0, 0, -1);
            tickets.update(missile, velocity, 96.0);
        }
    }

    private OrdinaryAirDefenseGuidance() {}
}
