package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Hard 20-second lifetime for fire-control Mianbao autocannon shells.
 *
 * <p>Vanilla entities stop aging after entering a non-ticking chunk. A short
 * forward corridor of shared region tickets keeps only these tagged shells
 * ticking until their absolute expiry, then releases every ticket at once.</p>
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoAutocannonProjectileLifetimeHandler {
    public static final int LIFETIME_TICKS = 20 * 20;
    private static final String EXPIRY_TICK_TAG =
            "CreateFireControlAutocannonExpiryTick";
    private static final String MOUNTED_MACHINE_GUN_TAG =
            "CreateFireControlMountedHeavyMachineGunShell";
    private static final int FORWARD_TICKET_TICKS = 3;
    private static final int MAX_TICKETED_PATH_CHUNKS = 8;
    private static final int TICKET_RELEASE_DELAY_TICKS = 2;
    private static final TicketType<Long> TICKET = TicketType.create(
            "create_fire_control_autocannon_shell", Long::compareTo);

    private static final Map<Entity, State> ACTIVE = new IdentityHashMap<>();
    /** One actual ticket per chunk even when several rounds share its path. */
    private static final Map<ServerLevel, Map<Long, Integer>> SHARED_TICKETS =
            new IdentityHashMap<>();

    /** Writes the absolute expiry before addFreshEntity dispatches the join event. */
    public static void arm(Entity shell, ServerLevel level) {
        if (shell == null || level == null) return;
        shell.getPersistentData().putLong(
                EXPIRY_TICK_TAG, expiryTick(level.getGameTime()));
    }

    /** Marks a direct-damage mounted-HMG round for the same bounded lifetime. */
    public static void armDirectMachineGunRound(Entity shell, ServerLevel level) {
        if (shell == null || level == null) return;
        shell.getPersistentData().putBoolean(MOUNTED_MACHINE_GUN_TAG, true);
        arm(shell, level);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !isManaged(event.getEntity())
                || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity shell = event.getEntity();
        long now = level.getGameTime();
        long expiry = shell.getPersistentData().contains(EXPIRY_TICK_TAG)
                ? shell.getPersistentData().getLong(EXPIRY_TICK_TAG)
                : expiryTick(now);
        shell.getPersistentData().putLong(EXPIRY_TICK_TAG, expiry);
        if (isExpired(now, expiry)) {
            shell.discard();
            return;
        }
        synchronized (ACTIVE) {
            State state = new State(level, expiry);
            State replaced = ACTIVE.put(shell, state);
            if (replaced != null) replaced.release();
            state.updateTickets(shell);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        synchronized (ACTIVE) {
            State state = ACTIVE.remove(event.getEntity());
            if (state != null) state.release();
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        synchronized (ACTIVE) {
            Iterator<Map.Entry<Entity, State>> iterator = ACTIVE.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, State> entry = iterator.next();
                Entity shell = entry.getKey();
                State state = entry.getValue();
                if (shell == null || shell.isRemoved() || !shell.isAlive()) {
                    state.release();
                    iterator.remove();
                    continue;
                }
                if (isExpired(state.level.getGameTime(), state.expiryTick)) {
                    state.release();
                    iterator.remove();
                    shell.discard();
                    continue;
                }
                state.updateTickets(shell);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (ACTIVE) {
            for (State state : ACTIVE.values()) state.release();
            ACTIVE.clear();
            releaseAllSharedTickets();
        }
    }

    private static boolean isManaged(Entity entity) {
        return entity != null && (entity.getPersistentData().getBoolean(
                MianbaoRemoteWeaponStationCompat.REDUCED_AUTOCANNON_TAG)
                || entity.getPersistentData().getBoolean(
                MianbaoCannonTurretPodCompat.PROJECTILE_TAG)
                || entity.getPersistentData().getBoolean(MOUNTED_MACHINE_GUN_TAG));
    }

    static long expiryTick(long launchTick) {
        return launchTick + LIFETIME_TICKS;
    }

    static boolean isExpired(long now, long expiryTick) {
        return now >= expiryTick;
    }

    private static boolean acquireSharedTicket(ServerLevel level, long chunkKey) {
        Map<Long, Integer> tickets = SHARED_TICKETS.computeIfAbsent(
                level, ignored -> new HashMap<>());
        Integer references = tickets.get(chunkKey);
        if (references != null) {
            tickets.put(chunkKey, references + 1);
            return true;
        }
        level.getChunkSource().addRegionTicket(
                TICKET, new ChunkPos(chunkKey), 0, chunkKey, true);
        tickets.put(chunkKey, 1);
        return true;
    }

    private static void releaseSharedTicket(ServerLevel level, long chunkKey) {
        Map<Long, Integer> tickets = SHARED_TICKETS.get(level);
        if (tickets == null) return;
        Integer references = tickets.get(chunkKey);
        if (references == null) return;
        if (references > 1) {
            tickets.put(chunkKey, references - 1);
            return;
        }
        level.getChunkSource().removeRegionTicket(
                TICKET, new ChunkPos(chunkKey), 0, chunkKey, true);
        tickets.remove(chunkKey);
        if (tickets.isEmpty()) SHARED_TICKETS.remove(level);
    }

    private static void releaseAllSharedTickets() {
        for (Map.Entry<ServerLevel, Map<Long, Integer>> levelEntry
                : SHARED_TICKETS.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            for (long chunkKey : levelEntry.getValue().keySet()) {
                level.getChunkSource().removeRegionTicket(
                        TICKET, new ChunkPos(chunkKey), 0, chunkKey, true);
            }
        }
        SHARED_TICKETS.clear();
    }

    private static final class State {
        final ServerLevel level;
        final long expiryTick;
        final Map<Long, Long> ticketExpiry = new HashMap<>();

        State(ServerLevel level, long expiryTick) {
            this.level = level;
            this.expiryTick = expiryTick;
        }

        void updateTickets(Entity shell) {
            Vec3 position = shell.position();
            Vec3 velocity = shell.getDeltaMovement();
            if (!finite(position) || !finite(velocity)) return;
            Vec3 lookAhead = velocity.scale(FORWARD_TICKET_TICKS);
            int samples = Math.min(MAX_TICKETED_PATH_CHUNKS - 1, Math.max(1,
                    Mth.ceil(Math.max(Math.abs(lookAhead.x),
                            Math.abs(lookAhead.z)) / 8.0)));
            Set<Long> wanted = new HashSet<>();
            for (int sample = 0; sample <= samples; sample++) {
                double fraction = (double) sample / samples;
                int blockX = Mth.floor(position.x + lookAhead.x * fraction);
                int blockZ = Mth.floor(position.z + lookAhead.z * fraction);
                wanted.add(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
            }

            long now = level.getGameTime();
            long releaseAt = now + TICKET_RELEASE_DELAY_TICKS;
            for (long chunkKey : wanted) {
                if (!ticketExpiry.containsKey(chunkKey)) {
                    acquireSharedTicket(level, chunkKey);
                }
                ticketExpiry.put(chunkKey, releaseAt);
            }
            for (Iterator<Map.Entry<Long, Long>> iterator =
                 ticketExpiry.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<Long, Long> entry = iterator.next();
                if (entry.getValue() > now) continue;
                releaseSharedTicket(level, entry.getKey());
                iterator.remove();
            }
        }

        void release() {
            for (long chunkKey : ticketExpiry.keySet()) {
                releaseSharedTicket(level, chunkKey);
            }
            ticketExpiry.clear();
        }
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private MianbaoAutocannonProjectileLifetimeHandler() {
    }
}
