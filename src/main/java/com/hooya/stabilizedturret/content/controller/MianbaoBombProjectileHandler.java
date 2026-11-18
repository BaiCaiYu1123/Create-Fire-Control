package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.network.BombFlightPayload;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.lang.reflect.Method;
import java.util.*;

/** Fire-control bombs have mathematical flight and occasional native effects, with no tracked flying entities. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoBombProjectileHandler {
    private static final Map<UUID, State> ACTIVE = new LinkedHashMap<>();
    private static final Map<ServerLevel, RocketTerrainCache> TERRAIN = new IdentityHashMap<>();
    private static final Map<ServerLevel, List<UUID>> REMOVED = new IdentityHashMap<>();
    private static final Map<String, Method> EFFECTS = new HashMap<>();
    private static final ThreadLocal<State> EFFECT_SOURCE = new ThreadLocal<>();

    public static int activeBombCount() { return ACTIVE.size(); }
    /** A cruise cluster's native scatter feeds the existing small-bomb integrator. */
    static boolean adoptMathematicalCruiseChild(ServerLevel root, Entity bomb) {
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(bomb.getType());
        WeaponType type = MianbaoBombCompat.entityWeapon(entityType);
        if (type == null || !hasLaunchCapacity(type)) return false;
        bomb.getPersistentData().putBoolean("CreateFireControlCruiseClusterWarhead", true);
        ACTIVE.put(bomb.getUUID(), new State(root, bomb, entityType, type, null, bomb.position(), bomb.getDeltaMovement()));
        return true;
    }
    static BombFlightPayload.State snapshot(UUID id) { State state = ACTIVE.get(id); return state == null ? null : state.snapshot(); }
    public static boolean hasLaunchCapacity(WeaponType type) {
        int needed = 1 + (type == WeaponType.CLUSTER_BOMB ? 20 : type == WeaponType.WHITE_PHOSPHORUS_BOMB ? 12 : 0);
        return ACTIVE.values().stream().mapToInt(s -> 1 + s.reserved).sum() + needed <= BombFlightPayload.MAX_BOMBS;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()) return;
        Entity bomb = event.getEntity();
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(bomb.getType());
        WeaponType type = MianbaoBombCompat.entityWeapon(entityType);
        if (type == null) return;
        var launch = MianbaoBombCompat.currentLaunch();
        State parent = EFFECT_SOURCE.get();
        if (launch == null && parent == null) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(bomb);
        if (root == null) return;
        event.setCanceled(true);
        if (ACTIVE.containsKey(bomb.getUUID()) || ACTIVE.size() >= BombFlightPayload.MAX_BOMBS) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(bomb);
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(bomb);
        UUID carrier = parent == null ? launch.binding().subLevelId() : parent.carrier;
        if (parent == null) {
            if (!bomb.getPersistentData().getBoolean("MianbaoCarrierMotionApplied") || Sable.HELPER.getContaining(bomb) != null) {
                Vec3 local = MianbaoBombCompat.localReleasePoint(launch.pos(), launch.level().getBlockState(launch.pos()));
                velocity = velocity.add(MianbaoAircraftCompat.carrierVelocityAtPoint(launch.level(), launch.binding(), local));
            }
            // Native procedures copy these settings by querying nearby entities after addFreshEntity.
            // This shot never enters that query, so capture the source block before the native call replaces it.
            bomb.getPersistentData().merge(launch.settings().copy());
        }
        if (!MianbaoBombBallistics.finite(position) || !MianbaoBombBallistics.finite(velocity) || velocity.lengthSqr() > 512.0 * 512.0) return;
        bomb.setPos(position); bomb.setDeltaMovement(velocity);
        ACTIVE.put(bomb.getUUID(), new State(root, bomb, entityType, type, carrier, position, velocity));
        if (parent == null) AirborneWeaponViewHandler.launched(
                AirborneWeaponViewHandler.launchingController(), bomb.getUUID(), () -> {
                    var state = snapshot(bomb.getUUID());
                    return state == null ? null : new AirborneWeaponViewHandler.Pose(
                            state.entityType(), state.position(), state.velocity());
                });
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty() && REMOVED.isEmpty()) return;
        Map<ServerLevel, List<Vec3>> lasers = new IdentityHashMap<>();
        for (State s : List.copyOf(ACTIVE.values())) {
            if (s.source.isRemoved()) { remove(s); continue; }
            long now = s.level.getGameTime();
            RocketTerrainCache terrain = TERRAIN.computeIfAbsent(s.level, RocketTerrainCache::new);
            if (now > s.lastAdvanced && !expired(s)) {
                Vec3 next = s.flight.position().add(s.flight.velocity());
                s.lasers = selectLaser(s, next, lasers);
                boolean water = terrain.inWater(next);
                if (water != s.water) s.unsent = true;
                s.water = water;
                VirtualBombFlight.Segment segment = s.flight.advance(s.coordinates(), s.target(), s.lasers, water);
                var structure = SableMissileFuze.firstBlockContact(s.level, segment.from(), segment.to(),
                        s.carrier != null && segment.age() <= 10 ? Set.of(s.carrier) : Set.of());
                var entity = ProjectileUtil.getEntityHitResult(s.level, null, segment.from(), segment.to(),
                        new AABB(segment.from(), segment.to()).inflate(0.3), candidate -> candidate.isPickable()
                                && candidate.isAlive() && (!(s.source instanceof Projectile p) || candidate != p.getOwner()));
                Vec3 body = structure == null ? null : structure.position();
                Vec3 living = entity == null ? null : entity.getLocation();
                Vec3 hit = MianbaoRocketPodProjectileHandler.nearest(segment.from(), body, living);
                s.pending.add(new Sweep(segment, hit, hit != null && hit == living ? entity.getEntity() : null));
                s.lastAdvanced = now;
            }
            boolean stopped = false;
            for (int i = 0; i < 8 && !s.pending.isEmpty(); i++) {
                Sweep sweep = s.pending.peek();
                var contact = terrain.sweep(sweep.segment.from(), sweep.segment.to());
                if (!contact.ready()) break;
                s.pending.remove();
                Vec3 hit = MianbaoRocketPodProjectileHandler.nearest(sweep.segment.from(), contact.hit(), sweep.liveHit);
                try {
                    if (hit != null) {
                        nativeImpact(s, hit, hit == sweep.liveHit ? sweep.entity : null, sweep.segment);
                        terrain.invalidate(hit); stopped = true; break;
                    }
                    if (!nativeFlightEffects(s, sweep.segment, terrain)) { s.pending.addFirst(sweep); break; }
                    if (s.source.isRemoved()) { stopped = true; break; }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    StabilizedTurret.LOGGER.error("Unable to apply native bomb effect for {}", s.entityType, failure);
                    stopped = true; break;
                }
            }
            if (stopped || expired(s) && (s.pending.isEmpty() || now - s.bornAt > MianbaoBombBallistics.MAX_TICKS + 40)) remove(s);
        }
        for (ServerLevel level : event.getServer().getAllLevels()) sync(level);
    }

    private static List<Vec3> selectLaser(State s, Vec3 position, Map<ServerLevel, List<Vec3>> cache) {
        if (s.type != WeaponType.LASER_BOMB && (s.type != WeaponType.JDAM_BOMB || s.coordinates())) return List.of();
        List<Vec3> candidates = cache.computeIfAbsent(s.level, level -> {
            List<Vec3> result = new ArrayList<>();
            for (Entity e : level.getAllEntities()) if (e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).equals(
                    ResourceLocation.fromNamespaceAndPath(MianbaoAircraftCompat.MOD_ID, "laserpoint_4"))) result.add(e.position());
            return result;
        });
        Vec3 closest = null; double distance = Double.POSITIVE_INFINITY;
        for (Vec3 target : candidates) {
            Vec3 offset = target.subtract(position);
            if (Math.abs(offset.x) > 300 || Math.abs(offset.y) > 300 || Math.abs(offset.z) > 300) continue;
            double next = offset.lengthSqr(); if (next < distance) { distance = next; closest = target; }
        }
        return closest == null ? List.of() : List.of(closest);
    }

    private static boolean nativeFlightEffects(State s, VirtualBombFlight.Segment segment, RocketTerrainCache terrain) throws ReflectiveOperationException {
        String path = s.entityType.getPath();
        String procedure = null;
        if (path.equals("clusterbombtanshe") && segment.age() == 30) { s.reserved = 0; procedure = "Cluster_bomb_change"; }
        if (path.equals("white_phosphorusbombtanshe") && (segment.age() == 30 || segment.age() == 31)) {
            if (segment.age() == 30) s.reserved -= 6;
            procedure = "White_phosphorus_bomb_tanshe_trail";
        }
        if (s.type == WeaponType.EARTH_PENETRATOR_BOMB && segment.age() > 20
                && s.source.getPersistentData().getDouble("钻地") < s.source.getPersistentData().getDouble("深度")) {
            Vec3 ahead = segment.to().add(segment.incoming().scale(segment.water() ? 0.6F : 0.99F));
            var drillingContact = terrain.sweep(segment.to(), ahead);
            if (!drillingContact.ready()) return false;
            if (drillingContact.hit() != null && !loadedAround(s.level, segment.to(), ahead, 4)) {
                // A confirmed drilling contact needs native block mutation, just as an impact does.
                // Install only this effect's chunks; never keep flight-path chunks loaded with tickets.
                forEachChunk(segment.to(), ahead, 4, (x, z) -> s.level.getChunk(x, z));
            }
            // Native drilling scans the next step after moving. Its guidance write is ignored: the shared integrator owns velocity.
            if (drillingContact.hit() != null) procedure = "Earthpenetratorbombtrail";
        }
        if (procedure == null) return true;
        positionSource(s, segment.to(), segment);
        s.source.getPersistentData().putDouble("time", segment.age() - 1);
        s.source.getPersistentData().putDouble("gotime", segment.age() - 1);
        State previous = EFFECT_SOURCE.get(); EFFECT_SOURCE.set(s);
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            Method method = EFFECTS.get(procedure);
            if (method == null) {
                method = Class.forName("net.mcreator.myfirstmod.procedures." + procedure + "Procedure")
                        .getMethod("execute", LevelAccessor.class, double.class, double.class, double.class, Entity.class);
                EFFECTS.put(procedure, method);
            }
            Vec3 p = segment.to(); method.invoke(null, s.level, p.x, p.y, p.z, s.source);
            if (s.type == WeaponType.EARTH_PENETRATOR_BOMB) terrain.invalidate(p);
        } finally { if (previous == null) EFFECT_SOURCE.remove(); else EFFECT_SOURCE.set(previous); }
        return true;
    }

    private static void nativeImpact(State s, Vec3 point, Entity target, VirtualBombFlight.Segment segment) throws ReflectiveOperationException {
        positionSource(s, point, segment);
        s.source.setDeltaMovement(segment.incoming());
        s.reserved = 0;
        State previous = EFFECT_SOURCE.get(); EFFECT_SOURCE.set(s);
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            if (target != null) s.source.getClass().getMethod("onHitEntity", EntityHitResult.class).invoke(s.source, new EntityHitResult(target, point));
            else s.source.getClass().getMethod("onHitBlock", BlockHitResult.class).invoke(s.source, new BlockHitResult(point,
                    Direction.getNearest(-segment.incoming().x, -segment.incoming().y, -segment.incoming().z),
                    BlockPos.containing(point.add(segment.incoming().normalize().scale(1.0e-5))), false));
        } finally { if (previous == null) EFFECT_SOURCE.remove(); else EFFECT_SOURCE.set(previous); }
    }

    private static void positionSource(State s, Vec3 point, VirtualBombFlight.Segment segment) {
        s.source.setPos(point); s.source.setDeltaMovement(segment.incoming().scale(segment.water() ? 0.6F : 0.99F));
        s.source.tickCount = segment.age();
    }
    private static boolean loadedAround(ServerLevel level, Vec3 a, Vec3 b, int radius) {
        boolean[] loaded = {true};
        forEachChunk(a, b, radius, (x, z) -> { if (level.getChunkSource().getChunkNow(x, z) == null) loaded[0] = false; });
        return loaded[0];
    }
    private static void forEachChunk(Vec3 a, Vec3 b, int radius, java.util.function.BiConsumer<Integer, Integer> action) {
        for (int x = net.minecraft.util.Mth.floor(Math.min(a.x, b.x) - radius) >> 4; x <= (net.minecraft.util.Mth.floor(Math.max(a.x, b.x) + radius) >> 4); x++)
            for (int z = net.minecraft.util.Mth.floor(Math.min(a.z, b.z) - radius) >> 4; z <= (net.minecraft.util.Mth.floor(Math.max(a.z, b.z) + radius) >> 4); z++) action.accept(x, z);
    }
    private static boolean expired(State s) {
        return s.flight.age() >= MianbaoBombBallistics.MAX_TICKS || !MianbaoBombBallistics.finite(s.flight.position())
                || s.flight.position().distanceToSqr(s.origin) > 4096.0 * 4096.0
                || s.flight.position().y < s.level.getMinBuildHeight() - 64 || !s.level.getWorldBorder().isWithinBounds(s.flight.position());
    }
    private static void remove(State s) {
        ACTIVE.remove(s.source.getUUID()); s.source.discard();
        REMOVED.computeIfAbsent(s.level, ignored -> new ArrayList<>()).add(s.source.getUUID());
    }
    private static void sync(ServerLevel level) {
        if (ACTIVE.isEmpty() && !REMOVED.containsKey(level)) return;
        long now = level.getGameTime();
        List<State> states = ACTIVE.values().stream().filter(s -> s.level == level &&
                (s.unsent || now % 20 == 0 || MianbaoBombBallistics.guided(s.type) && now % 5 == 0)).toList();
        List<UUID> removed = REMOVED.remove(level); if (removed == null) removed = List.of();
        if (states.isEmpty() && removed.isEmpty()) return;
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) != level) continue;
            Vec3 viewer = MianbaoMissileCompat.getWorldPosition(player);
            List<BombFlightPayload.State> visible = states.stream().filter(s -> s.flight.position().distanceToSqr(viewer) < 20000.0 * 20000.0).map(State::snapshot).toList();
            for (int start = 0; start < Math.max(1, removed.size()); start += BombFlightPayload.MAX_BOMBS) {
                List<UUID> batch = removed.subList(start, Math.min(removed.size(), start + BombFlightPayload.MAX_BOMBS));
                if (start == 0 && !visible.isEmpty() || !batch.isEmpty()) PacketDistributor.sendToPlayer(player,
                        new BombFlightPayload(level.dimension().location(), now, start == 0 ? visible : List.of(), batch));
            }
        }
        states.forEach(s -> s.unsent = false);
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { ACTIVE.clear(); TERRAIN.clear(); REMOVED.clear(); EFFECT_SOURCE.remove(); }
    private record Sweep(VirtualBombFlight.Segment segment, Vec3 liveHit, Entity entity) {}
    private static final class State {
        final ServerLevel level;
        final Entity source; // Unregistered effect context only; never ticked, saved or tracked by Minecraft.
        final ResourceLocation entityType;
        final WeaponType type;
        final UUID carrier;
        final Vec3 origin;
        final VirtualBombFlight flight;
        final long bornAt;
        final ArrayDeque<Sweep> pending = new ArrayDeque<>();
        List<Vec3> lasers = List.of();
        long lastAdvanced;
        int reserved;
        boolean unsent = true, water;
        State(ServerLevel level, Entity source, ResourceLocation entityType, WeaponType type, UUID carrier, Vec3 position, Vec3 velocity) {
            this.level = level; this.source = source; this.entityType = entityType; this.type = type; this.carrier = carrier;
            origin = position; flight = new VirtualBombFlight(type, position, velocity, 0); bornAt = lastAdvanced = level.getGameTime();
            reserved = entityType.getPath().equals("clusterbombtanshe") ? 20 : entityType.getPath().equals("white_phosphorusbombtanshe") ? 12 : 0;
        }
        boolean coordinates() { return source.getPersistentData().getBoolean("坐标模式"); }
        Vec3 target() { var data = source.getPersistentData(); return new Vec3(data.getDouble("target_x"), data.getDouble("target_y"), data.getDouble("target_z")); }
        BombFlightPayload.State snapshot() { return new BombFlightPayload.State(source.getUUID(), entityType, flight.position(), flight.velocity(), flight.age(), coordinates(), target(), lasers, water); }
    }
    private MianbaoBombProjectileHandler() {}
}
