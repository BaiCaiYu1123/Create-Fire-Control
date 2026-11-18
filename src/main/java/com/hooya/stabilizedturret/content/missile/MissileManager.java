package com.hooya.stabilizedturret.content.missile;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.RwrMissileApproach;
import com.hooya.stabilizedturret.content.controller.RwrThreat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.missile.impact.BallisticImpact;
import com.hooya.stabilizedturret.content.missile.impact.ImpactAreaManager;
import com.hooya.stabilizedturret.network.VirtualMissileStatePayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative, entity-free missile simulation and viewer synchronization. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MissileManager implements AutoCloseable {
    public static final int MAX_LIFETIME_TICKS = 20 * 60 * 5;
    private static final Map<ServerLevel, MissileManager> MANAGERS = new HashMap<>();

    private final ServerLevel level;
    private final ImpactAreaManager impacts;
    private final FlareManager flares;
    private final Map<UUID, VirtualMissile> missiles = new LinkedHashMap<>();
    private final Map<UUID, Vec3> pendingImpactReleases = new HashMap<>();
    private final Map<UUID, Set<UUID>> visibleMissiles = new HashMap<>();
    private final Map<UUID, Set<UUID>> sentFlares = new HashMap<>();

    private MissileManager(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
        this.impacts = new ImpactAreaManager(level);
        this.flares = new FlareManager(level);
    }

    public static boolean virtualize(
            ServerLevel root,
            ConnectedDisplayBlockEntity display,
            UUID id,
            Vec3 position,
            Vec3 velocity,
            Vec3 direction,
            @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
            @Nullable Vec3 fixedTarget,
            Vec3 initialTarget
    ) {
        if (root == null || display == null || id == null || !finite(position)
                || !finite(velocity) || !finite(direction) || !finite(initialTarget)) return false;
        MissileManager manager = MANAGERS.computeIfAbsent(root, MissileManager::new);
        if (manager.missiles.containsKey(id)) return true;
        manager.missiles.put(id, new VirtualMissile(id, display, targetKey, fixedTarget,
                position, velocity, direction, initialTarget));
        manager.syncAll(true);
        StabilizedTurret.LOGGER.info("Virtualized ballistic missile {} at {}", id, position);
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            MissileManager manager = MANAGERS.get(level);
            if (manager != null) manager.tick();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        syncJoiningPlayer(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        syncJoiningPlayer(event.getEntity());
    }

    private static void syncJoiningPlayer(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(serverPlayer);
        MissileManager manager = root == null ? null : MANAGERS.get(root);
        if (manager != null) manager.syncPlayer(serverPlayer, true);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (MissileManager manager : MANAGERS.values()) manager.close();
        MANAGERS.clear();
    }

    private void tick() {
        impacts.tick();
        flares.tick();
        for (Iterator<Map.Entry<UUID, Vec3>> iterator =
             pendingImpactReleases.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, Vec3> entry = iterator.next();
            if (!BallisticImpact.isTerrainWorkActive(level, entry.getValue())) {
                impacts.release(entry.getKey());
                iterator.remove();
            }
        }
        boolean removedAny = false;
        for (Iterator<Map.Entry<UUID, VirtualMissile>> iterator = missiles.entrySet().iterator();
             iterator.hasNext();) {
            Map.Entry<UUID, VirtualMissile> entry = iterator.next();
            VirtualMissile missile = entry.getValue();
            boolean remove = missile.tick(level, impacts, flares);
            missile.display().updateBallisticTelemetry(
                    new ConnectedDisplayBlockEntity.BallisticTelemetry(
                            missile.id(), missile.position(), missile.velocity(),
                            missile.target(), missile.age()));
            if (remove) {
                if (missile.phase() == VirtualMissile.Phase.IMPACT
                        && BallisticImpact.isTerrainWorkActive(level, missile.position())) {
                    pendingImpactReleases.put(entry.getKey(), missile.position());
                } else {
                    impacts.release(entry.getKey());
                }
                missile.display().removeBallisticTelemetry(entry.getKey());
                iterator.remove();
                removedAny = true;
            }
        }
        if (removedAny) syncAll(true);
        else if (level.getGameTime() % MissilePhysicsConfig.SYNC_INTERVAL.get() == 0L) syncAll(false);
    }

    private void syncAll(boolean force) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) == level) syncPlayer(player, force);
        }
    }

    private void syncPlayer(ServerPlayer player, boolean force) {
        Vec3 viewer = MianbaoMissileCompat.getWorldPosition(player);
        double range = MissilePhysicsConfig.MAX_RENDER_DISTANCE.get();
        double rangeSqr = range * range;
        Set<UUID> nowVisible = new HashSet<>();
        ListTag missileTags = new ListTag();
        for (VirtualMissile missile : missiles.values()) {
            if (missile.position().distanceToSqr(viewer) > rangeSqr) continue;
            nowVisible.add(missile.id());
            missileTags.add(writeMissile(missile));
        }

        Set<UUID> previous = visibleMissiles.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>());
        ListTag removed = new ListTag();
        for (UUID id : previous) {
            if (!nowVisible.contains(id)) {
                CompoundTag tag = new CompoundTag();
                tag.putUUID("Id", id);
                removed.add(tag);
            }
        }
        previous.clear();
        previous.addAll(nowVisible);

        Set<UUID> flareHistory = sentFlares.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>());
        Set<UUID> activeFlares = new HashSet<>();
        for (VirtualFlare flare : flares.all()) activeFlares.add(flare.id());
        flareHistory.retainAll(activeFlares);
        ListTag flareTags = new ListTag();
        double flareRange = MissilePhysicsConfig.FLARE_RENDER_DISTANCE.get();
        double flareRangeSqr = flareRange * flareRange;
        for (VirtualFlare flare : flares.all()) {
            Vec3 current = flare.positionAt(level.getGameTime());
            if (current.distanceToSqr(viewer) <= flareRangeSqr && flareHistory.add(flare.id())) {
                flareTags.add(writeFlare(flare));
            }
        }
        if (!force && missileTags.isEmpty() && removed.isEmpty() && flareTags.isEmpty()) return;

        CompoundTag data = new CompoundTag();
        data.put("Missiles", missileTags);
        data.put("Removed", removed);
        data.put("Flares", flareTags);
        PacketDistributor.sendToPlayer(player, new VirtualMissileStatePayload(
                level.dimension().location(), data));
    }

    private CompoundTag writeMissile(VirtualMissile missile) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", missile.id());
        putVector(tag, "P", missile.position());
        putVector(tag, "V", missile.velocity());
        putVector(tag, "D", missile.direction());
        tag.putString("Phase", missile.phase().name());
        tag.putLong("Time", level.getGameTime());
        tag.putInt("Age", missile.age());
        return tag;
    }

    private static CompoundTag writeFlare(VirtualFlare flare) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", flare.id());
        putVector(tag, "P", flare.position());
        putVector(tag, "V", flare.velocity());
        tag.putLong("Time", flare.birthTime());
        tag.putInt("Lifetime", flare.lifetimeTicks());
        tag.putFloat("Heat", flare.heat());
        tag.putLong("Seed", flare.seed());
        tag.putDouble("Drag", flare.drag());
        tag.putDouble("Gravity", flare.gravity());
        return tag;
    }

    private static void putVector(CompoundTag tag, String prefix, Vec3 value) {
        tag.putDouble(prefix + "X", value.x);
        tag.putDouble(prefix + "Y", value.y);
        tag.putDouble(prefix + "Z", value.z);
    }

    public VirtualMissile get(UUID id) { return missiles.get(id); }

    public static List<RwrThreat> collectRwrMissileThreats(
            StabilizerControllerBlockEntity receiver, Set<UUID> structure, AABB bounds) {
        MissileManager manager = MANAGERS.get(receiver.getLevel());
        if (manager == null) return List.of();
        List<RwrThreat> found = new ArrayList<>();
        double rangeSqr = receiver.getRwrRange() * receiver.getRwrRange();
        for (VirtualMissile missile : manager.missiles.values()) {
            if (missile.phase() == VirtualMissile.Phase.DEAD
                    || missile.phase() == VirtualMissile.Phase.IMPACT
                    || missile.display().getOwnStructureIdsForIff().stream().anyMatch(structure::contains)
                    || missile.position().distanceToSqr(receiver.getRadarOrigin()) > rangeSqr) continue;
            if (missile.targetsAny(structure)
                    || RwrMissileApproach.isIncoming(missile.position(), missile.velocity(), bounds,
                    receiver.getAircraftVelocity())) {
                found.add(new RwrThreat(missile.id(), RwrThreat.Type.MSL, missile.position(), true));
            }
        }
        return found;
    }
    public Collection<VirtualMissile> missiles() {
        return Collections.unmodifiableCollection(missiles.values());
    }

    public void cancel(UUID id) {
        VirtualMissile missile = missiles.remove(id);
        if (missile != null) {
            impacts.release(id);
            missile.display().removeBallisticTelemetry(id);
            syncAll(true);
        }
    }

    static boolean detonate(ServerLevel level, UUID id, Vec3 position) {
        BallisticImpact.detonate(level, id, position);
        return true;
    }

    @Override
    public void close() {
        for (VirtualMissile missile : missiles.values()) {
            missile.display().removeBallisticTelemetry(missile.id());
        }
        missiles.clear();
        visibleMissiles.clear();
        sentFlares.clear();
        pendingImpactReleases.clear();
        impacts.close();
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
