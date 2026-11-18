package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.RocketSalvoPayload;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bound rocket pods produce mathematical flights, never moving chunk tickets. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoRocketPodProjectileHandler {
    private static final Map<UUID, State> ACTIVE = new HashMap<>();
    private static final Map<ServerLevel, RocketTerrainCache> TERRAIN = new IdentityHashMap<>();
    private static final Map<ServerLevel, List<UUID>> REMOVED = new IdentityHashMap<>();
    private static final int MAX_COLLISION_CATCHUP = 8;
    private static final int MAX_TERRAIN_WAIT_TICKS = 40;
    private static final int SYNC_INTERVAL = 20;

    public static boolean hasLaunchCapacity() { return ACTIVE.size() < RocketSalvoPayload.MAX_ROCKETS; }
    public static boolean hasAnyActiveRocket() { return !ACTIVE.isEmpty(); }
    public static int activeRocketCount() { return ACTIVE.size(); }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity rocket = event.getEntity();
        if (event.getLevel().isClientSide() || !MianbaoRocketPodCompat.isRocket(rocket)) return;
        boolean restoring = rocket.getPersistentData().getBoolean(MianbaoRocketPodCompat.ADOPTED_TAG);
        if (!restoring && !FireControlMissileCommandGuard.isLaunching()) return;
        ControllerTracker.MianbaoRocketLaunch launch = restoring ? null : ControllerTracker.findMianbaoRocketLaunch(rocket);
        if (!restoring && (launch == null || launch.controller().getLevel() == null)) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(rocket);
        if (root == null) return;
        // The native procedure still owns ammunition consumption and launch sound.
        // Cancelling registration prevents native ticks, trails, tracking and Sable
        // release hooks from doing any additional work for this mathematical shot.
        event.setCanceled(true);
        if (ACTIVE.containsKey(rocket.getUUID()) || !hasLaunchCapacity()) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(rocket);
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(rocket);
        if (!restoring && (!rocket.getPersistentData().getBoolean("MianbaoCarrierMotionApplied")
                || Sable.HELPER.getContaining(rocket) != null)) {
            velocity = velocity.add(MianbaoRocketPodCompat.carrierVelocityAtMuzzle(
                    launch.controller().getLevel(), launch.pod()));
        }
        if (!finite(position) || !finite(velocity) || velocity.lengthSqr() > 512.0 * 512.0) return;
        int age = restoring ? Math.max(0, rocket.getPersistentData().getInt("CreateFireControlRocketAge")) : 0;
        UUID carrier = launch == null ? null : launch.pod().subLevelId();
        double drag = launch == null ? MianbaoRocketFlight.AIR_DRAG
                : MianbaoRocketPodCompat.firstTickDrag(launch.controller().getLevel(), launch.pod());
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(rocket.getType());
        ACTIVE.put(rocket.getUUID(), new State(root, rocket.getUUID(), type, carrier,
                new VirtualRocketFlight(position, velocity, age, drag, MianbaoRocketPodCompat.flightDrop(rocket),
                        rocket.isNoGravity() ? 0.0 : MianbaoRocketFlight.ARROW_GRAVITY,
                        MianbaoRocketPodCompat.isVisualGuidedRocket(type)),
                MianbaoRocketPodCompat.detonationProfile(rocket), root.getGameTime(),
                rocket instanceof Projectile projectile && projectile.getOwner() != null
                        ? projectile.getOwner().getUUID() : null));
        if (launch != null) AirborneWeaponViewHandler.launched(launch.controller(), rocket.getUUID(), () -> {
            State state = ACTIVE.get(rocket.getUUID());
            return state == null ? null : new AirborneWeaponViewHandler.Pose(
                    state.type, state.flight.position(), state.flight.velocity());
        });
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty() && REMOVED.isEmpty()) return;
        Iterator<State> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next();
            long now = state.level.getGameTime();
            // A launch at this game time already represents the beginning of its
            // first flight tick; do not age it twice before the launch packet.
            if (now > state.lastAdvanced && state.flight.age() < MianbaoRocketFlight.LIFETIME_TICKS) {
                VirtualRocketFlight.Segment segment = state.flight.advance(state.guidanceTarget());
                Set<UUID> ignored = state.carrier != null && segment.age() <= 10
                        ? Set.of(state.carrier) : Set.of();
                SableMissileFuze.Contact structure = SableMissileFuze.firstBlockContact(
                        state.level, segment.from(), segment.to(), ignored);
                var entity = ProjectileUtil.getEntityHitResult(state.level, null, segment.from(), segment.to(),
                        new AABB(segment.from(), segment.to()).inflate(0.3), Entity::isPickable);
                Vec3 liveHit = nearest(segment.from(), structure == null ? null : structure.position(),
                        entity == null ? null : entity.getLocation());
                state.pending.add(new Sweep(segment, liveHit));
                state.lastAdvanced = now;
            }
            RocketTerrainCache terrain = TERRAIN.computeIfAbsent(state.level, RocketTerrainCache::new);
            Vec3 impact = null;
            for (int checked = 0; checked < MAX_COLLISION_CATCHUP && !state.pending.isEmpty(); checked++) {
                Sweep sweep = state.pending.peek();
                RocketTerrainCache.Contact contact = terrain.sweep(sweep.segment.from(), sweep.segment.to());
                if (!contact.ready()) break;
                state.pending.remove();
                impact = nearest(sweep.segment.from(), contact.hit(), sweep.liveHit);
                if (impact != null) break;
            }
            if (impact != null) {
                MianbaoDeferredDetonation.detonateVirtualRocket(state.level, state.id, impact, state.profile);
                terrain.invalidate(impact);
                remove(state, iterator);
            } else if (state.flight.age() >= MianbaoRocketFlight.LIFETIME_TICKS
                    && (state.pending.isEmpty() || now - state.bornAt >= MianbaoRocketFlight.LIFETIME_TICKS + MAX_TERRAIN_WAIT_TICKS)) {
                remove(state, iterator);
            }
        }
        for (ServerLevel level : event.getServer().getAllLevels()) sync(level);
    }

    private static void remove(State state, Iterator<State> iterator) {
        REMOVED.computeIfAbsent(state.level, ignored -> new ArrayList<>()).add(state.id);
        iterator.remove();
    }

    private static void sync(ServerLevel level) {
        if (ACTIVE.isEmpty() && !REMOVED.containsKey(level)) return;
        long now = level.getGameTime();
        boolean full = Math.floorMod(now, SYNC_INTERVAL) == 0;
        List<State> states = ACTIVE.values().stream().filter(state -> state.level == level
                && (full || state.unsent || state.guided && now % 5 == 0)).toList();
        List<UUID> removed = REMOVED.remove(level);
        if (removed == null) removed = List.of();
        if (states.isEmpty() && removed.isEmpty()) return;
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) != level) continue;
            Vec3 viewer = MianbaoMissileCompat.getWorldPosition(player);
            List<RocketSalvoPayload.State> visible = states.stream().filter(state ->
                    state.flight.position().distanceToSqr(viewer) <= 20000.0 * 20000.0)
                    .map(State::snapshot).toList();
            if (!visible.isEmpty() || !removed.isEmpty()) PacketDistributor.sendToPlayer(player,
                    new RocketSalvoPayload(level.dimension().location(), now, visible, removed));
        }
        states.forEach(state -> state.unsent = false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVE.clear(); TERRAIN.clear(); REMOVED.clear();
    }

    static Vec3 nearest(Vec3 from, Vec3 first, Vec3 second) {
        return first == null ? second : second == null || from.distanceToSqr(first) <= from.distanceToSqr(second)
                ? first : second;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private record Sweep(VirtualRocketFlight.Segment segment, Vec3 liveHit) {}

    private static final class State {
        final ServerLevel level;
        final UUID id, carrier;
        final UUID owner;
        final ResourceLocation type;
        final VirtualRocketFlight flight;
        final byte profile;
        final long bornAt;
        final boolean guided;
        final ArrayDeque<Sweep> pending = new ArrayDeque<>();
        long lastAdvanced;
        boolean unsent = true;

        State(ServerLevel level, UUID id, ResourceLocation type, UUID carrier,
              VirtualRocketFlight flight, byte profile, long now, UUID owner) {
            this.level = level; this.id = id; this.type = type; this.carrier = carrier;
            this.flight = flight; this.profile = profile; this.bornAt = now; this.lastAdvanced = now;
            this.owner = owner;
            this.guided = type.getPath().equals("heavyrocketlaserguidedtanshe")
                    || MianbaoRocketPodCompat.isVisualGuidedRocket(type);
        }

        Vec3 guidanceTarget() {
            if (!guided) return null;
            if (MianbaoRocketPodCompat.isVisualGuidedRocket(type)) {
                Entity shooter = owner == null ? null : level.getEntity(owner);
                if (shooter == null || !shooter.isAlive()) return null;
                Vec3 eye = MianbaoMissileCompat.getWorldPosition(shooter, shooter.getEyePosition());
                Vec3 look = MianbaoMissileCompat.getWorldPosition(shooter,
                        shooter.getEyePosition().add(shooter.getLookAngle())).subtract(eye).normalize();
                Vec3 nextPosition = flight.position().add(flight.velocity());
                return eye.add(look.scale(eye.distanceTo(nextPosition) + 5.0));
            }
            Entity nearest = null;
            double distance = Double.POSITIVE_INFINITY;
            for (Entity entity : level.getEntities((Entity) null,
                    new AABB(flight.position(), flight.position()).inflate(200), candidate ->
                            BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).getPath().equals("laserpoint_4"))) {
                double next = entity.position().distanceToSqr(flight.position());
                if (next < distance) { nearest = entity; distance = next; }
            }
            return nearest == null ? null : nearest.position();
        }

        RocketSalvoPayload.State snapshot() {
            return new RocketSalvoPayload.State(id, type, flight.position(), flight.velocity(),
                    flight.age(), flight.firstDrag(), flight.drop());
        }
    }

    private MianbaoRocketPodProjectileHandler() {}
}
