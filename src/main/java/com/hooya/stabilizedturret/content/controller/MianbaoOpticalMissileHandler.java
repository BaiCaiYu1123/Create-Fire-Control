package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.hooya.stabilizedturret.network.TelevisionGuidanceStatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative television guidance for Mianbao's optical missile. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoOpticalMissileHandler {
    private static final double SPEED_BLOCKS_PER_TICK = 170.15 / 20.0;
    private static final int MANEUVER_DELAY_TICKS = 10;
    private static final int LIFETIME_TICKS = 20 * 20;
    private static final String ADOPTED_TAG = "CreateFireControlOpticalGuided";
    private static final String AIRCRAFT_ADOPTED_TAG = "CreateFireControlAircraftGuided";
    private static final TicketType<UUID> TICKET = TicketType.create(
            "create_fire_control_optical_missile", UUID::compareTo);
    private static final Map<Entity, State> ACTIVE = new IdentityHashMap<>();

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity missile = event.getEntity();
        if (!MianbaoAircraftCompat.isOpticalGuided(
                MianbaoAircraftCompat.typeOf(missile))) return;
        // A saved twenty-second projectile cannot safely retain a player camera
        // across a server restart. Re-adopt only fresh rack launches.
        if (missile.getPersistentData().getBoolean(ADOPTED_TAG)) return;

        ControllerTracker.MianbaoAircraftLaunch launch =
                ControllerTracker.findMianbaoAircraftLaunch(missile);
        if (launch == null || launch.controller().getLevel() == null) return;
        StabilizerControllerBlockEntity controller = launch.controller();
        if (hasActiveMissile(controller)) {
            missile.discard();
            return;
        }

        Vec3 direction = MianbaoAircraftCompat.launchDirection(
                controller.getLevel(), launch.rack());
        if (direction == null || direction.lengthSqr() < 1.0e-8) {
            direction = MianbaoMissileCompat.getWorldVelocity(missile);
        }
        if (direction.lengthSqr() < 1.0e-8) {
            direction = controller.getAircraftRadarForward();
        }
        direction = direction.normalize();
        Vec3 nativeVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
        Vec3 carrierVelocity = MianbaoAircraftCompat.carrierVelocityAtMuzzle(
                controller.getLevel(), launch.rack());
        Vec3 launchVelocity = nativeVelocity.add(carrierVelocity);
        if (isFiniteDirection(launchVelocity)) direction = launchVelocity.normalize();
        double initialSpeed = launchVelocity.length();
        missile.getPersistentData().putBoolean(ADOPTED_TAG, true);
        // Reuse the existing long-range pairing, collision and remote visual path.
        missile.getPersistentData().putBoolean(AIRCRAFT_ADOPTED_TAG, true);
        FireControlMissileOwnership.markController(missile, controller);
        missile.getPersistentData().putInt(
                "CreateFireControlSafetyTicks", MANEUVER_DELAY_TICKS);
        MissileFuseSafety.launched(missile);
        missile.getPersistentData().putBoolean("锁定模式", false);
        missile.getPersistentData().putBoolean("坐标模式", false);
        missile.getPersistentData().putBoolean("jamed", true);
        missile.setNoGravity(true);
        setFlight(missile, direction, initialSpeed);

        State state = new State(controller, direction, initialSpeed);
        state.tickets.update(missile, launchVelocity);
        synchronized (ACTIVE) {
            ACTIVE.put(missile, state);
        }
        MianbaoMissileRemoteSync.broadcast(missile);
        AirborneWeaponViewHandler.launched(controller, missile);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        synchronized (ACTIVE) {
            Iterator<Map.Entry<Entity, State>> iterator = ACTIVE.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, State> entry = iterator.next();
                Entity missile = entry.getKey();
                State state = entry.getValue();
                if (missile.isRemoved()) {
                    state.finish(missile);
                    iterator.remove();
                    continue;
                }
                state.age++;
                // Agm1trail's native counter detonates at 15 seconds. Keep its
                // motor/trail phase alive and let this handler own the requested
                // 20-second lifetime instead.
                if (missile.getPersistentData().getDouble("fadongji") > 100.0) {
                    missile.getPersistentData().putDouble("fadongji", 100.0);
                }
                if (state.age >= MissileSettings.lifetimeTicks(missile)) {
                    state.finish(missile);
                    if (MissileFuseSafety.protectedAircraft(missile)) missile.discard();
                    else MianbaoMissileDetonation.detonate(
                            missile,
                            MianbaoMissileDetonation.aircraftStrength(
                                    MianbaoAircraftCompat.WeaponType.OPTICAL_GUIDED));
                    iterator.remove();
                    continue;
                }

                ServerPlayer viewer = state.viewer(missile);
                if (state.viewerId != null && (viewer == null
                        || !state.controller.isSeatedMissileOperator(viewer))) {
                    if (viewer != null) state.leaveView(viewer);
                    else state.viewerId = null;
                    viewer = null;
                }
                if (state.age >= MANEUVER_DELAY_TICKS && viewer != null
                        && isFiniteDirection(state.requestedDirection)) {
                    state.direction = state.requestedDirection.normalize();
                }
                double progress = Mth.clamp(
                        (double) state.age / Math.max(1.0, LIFETIME_TICKS * MissileSettings.poweredScale(missile)), 0.0, 1.0);
                double speed = Mth.lerp(
                        progress, state.initialSpeed, MissileSettings.speedBlocksPerTick(missile));
                setFlight(missile, state.direction, speed);
                state.tickets.update(missile,
                        state.direction.scale(speed));
                MianbaoMissileRemoteSync.broadcast(missile);
            }
        }
    }

    public static boolean hasActiveMissile(StabilizerControllerBlockEntity controller) {
        if (controller == null) return false;
        synchronized (ACTIVE) {
            return ACTIVE.entrySet().stream().anyMatch(entry ->
                    !entry.getKey().isRemoved()
                            && entry.getValue().controller == controller);
        }
    }

    /** One key toggles both directions for this exact seated controller. */
    public static void toggleView(
            ServerPlayer player, StabilizerControllerBlockEntity controller
    ) {
        if (player == null || controller == null) return;
        synchronized (ACTIVE) {
            for (Map.Entry<Entity, State> entry : ACTIVE.entrySet()) {
                State state = entry.getValue();
                if (state.controller == controller
                        && player.getUUID().equals(state.viewerId)) {
                    state.leaveView(player);
                    return;
                }
            }
            if (!controller.isSeatedMissileOperator(player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.create_fire_control.optical_missile_seat_required"), true);
                return;
            }
            for (Map.Entry<Entity, State> entry : ACTIVE.entrySet()) {
                Entity missile = entry.getKey();
                State state = entry.getValue();
                if (state.controller != controller || missile.isRemoved()
                        || state.viewerId != null) continue;
                state.viewerId = player.getUUID();
                state.requestedDirection = state.direction;
                PacketDistributor.sendToPlayer(player,
                        new TelevisionGuidanceStatePayload(
                                true, missile.getUUID(),
                                Math.max(0, MissileSettings.lifetimeTicks(missile) - state.age)));
                return;
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.create_fire_control.no_optical_missile"), true);
        }
    }

    public static boolean isViewing(
            ServerPlayer player, StabilizerControllerBlockEntity controller
    ) {
        if (player == null || controller == null) return false;
        synchronized (ACTIVE) {
            return ACTIVE.values().stream().anyMatch(state ->
                    state.controller == controller
                            && player.getUUID().equals(state.viewerId));
        }
    }

    public static boolean isViewing(ServerPlayer player) {
        if (player == null) return false;
        synchronized (ACTIVE) {
            return ACTIVE.values().stream().anyMatch(state ->
                    player.getUUID().equals(state.viewerId));
        }
    }

    /** Accepts only the independent direction of the missile this seated player owns. */
    public static void acceptGuidanceDirection(ServerPlayer player, Vec3 direction) {
        if (player == null || !isFiniteDirection(direction)) return;
        Vec3 normalized = direction.normalize();
        synchronized (ACTIVE) {
            for (State state : ACTIVE.values()) {
                if (!player.getUUID().equals(state.viewerId)) continue;
                if (!state.controller.isSeatedMissileOperator(player)) return;
                state.requestedDirection = normalized;
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (ACTIVE) {
            for (Map.Entry<Entity, State> entry : ACTIVE.entrySet()) {
                entry.getValue().finish(entry.getKey());
            }
            ACTIVE.clear();
        }
    }

    private static void setFlight(Entity missile, Vec3 direction, double speed) {
        Vec3 velocity = direction.scale(Math.max(0.0, speed));
        MianbaoMissileCompat.setWorldVelocity(missile, velocity);
        float yaw = (float) (Mth.atan2(direction.x, direction.z) * Mth.RAD_TO_DEG);
        float pitch = (float) (Mth.atan2(
                direction.y, direction.horizontalDistance()) * Mth.RAD_TO_DEG);
        missile.setYRot(yaw);
        missile.setXRot(pitch);
        missile.yRotO = yaw;
        missile.xRotO = pitch;
        missile.hasImpulse = true;
    }

    private static boolean isFiniteDirection(Vec3 direction) {
        return direction != null && direction.lengthSqr() > 1.0e-8
                && Double.isFinite(direction.x)
                && Double.isFinite(direction.y)
                && Double.isFinite(direction.z);
    }

    private static final class State {
        private final StabilizerControllerBlockEntity controller;
        private final MissileChunkTickets tickets = new MissileChunkTickets(TICKET);
        private final double initialSpeed;
        private Vec3 direction;
        private Vec3 requestedDirection;
        private UUID viewerId;
        private int age;

        private State(
                StabilizerControllerBlockEntity controller,
                Vec3 direction,
                double initialSpeed
        ) {
            this.controller = controller;
            this.direction = direction;
            this.requestedDirection = direction;
            this.initialSpeed = Math.max(0.0, initialSpeed);
        }

        private ServerPlayer viewer(Entity missile) {
            if (viewerId == null || missile.getServer() == null) return null;
            ServerPlayer player = missile.getServer().getPlayerList().getPlayer(viewerId);
            return player != null && !player.isRemoved() ? player : null;
        }

        private void leaveView(ServerPlayer player) {
            viewerId = null;
            PacketDistributor.sendToPlayer(player,
                    new TelevisionGuidanceStatePayload(false, null, 0));
        }

        private void finish(Entity missile) {
            ServerPlayer player = viewer(missile);
            if (player != null) leaveView(player);
            tickets.release();
        }
    }

    private MianbaoOpticalMissileHandler() {
    }
}
