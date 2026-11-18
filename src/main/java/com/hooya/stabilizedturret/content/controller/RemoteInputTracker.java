package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import com.hooya.stabilizedturret.network.RemoteInputStatePayload.RangeSample;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import com.hooya.stabilizedturret.content.remote.ControllerPulseQueue;

/** Independently transported physical input, validated against the existing terminal session. */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.hooya.stabilizedturret.StabilizedTurret.MOD_ID)
public final class RemoteInputTracker {
    private static final long TIMEOUT_TICKS = 12L;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void update(ServerPlayer player, RemoteControllerCompat.Type type, BlockPos hubPos, Set<String> pressed) {
        update(player, type, hubPos, pressed, null);
    }

    public static void update(ServerPlayer player, RemoteControllerCompat.Type type, BlockPos hubPos,
                              Set<String> pressed, @Nullable RangeSample sample) {
        update(player,type,hubPos,pressed,sample,List.of());
    }
    public static void update(ServerPlayer player,RemoteControllerCompat.Type type,BlockPos hubPos,Set<String> pressed,
                              @Nullable RangeSample sample,List<ControllerPulseQueue.Pulse> pulses) {
        update(player, type, hubPos, pressed, sample, pulses, Set.copyOf(DriveByWireCompat.keyList(type)));
    }

    public static void update(ServerPlayer player, RemoteControllerCompat.Type type, BlockPos hubPos,
                              Set<String> pressed, @Nullable RangeSample sample,
                              List<ControllerPulseQueue.Pulse> pulses, Set<String> observed) {
        if (!RemoteControlTracker.matchesActiveHubSession(player, type, hubPos)) {
            SESSIONS.remove(player.getUUID());
            return;
        }
        StabilizerControllerBlockEntity controller = RemoteControlTracker.findInputController(player);
        Set<String> acceptedObserved = observed.stream().filter(key -> DriveByWireCompat.accepts(type, key))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> accepted = pressed.stream().filter(acceptedObserved::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Session previous = SESSIONS.get(player.getUUID());
        boolean same=previous!=null&&previous.type==type&&previous.hubPos.equals(hubPos)&&previous.dimension.equals(player.level().dimension().location().toString())
                &&previous.expiresAt>=player.level().getGameTime()&&previous.controller==controller;
        ControllerPulseQueue wheel=same?previous.wheel:new ControllerPulseQueue();
        Set<String> wheelKeys=new HashSet<>(same?previous.wheelKeys:Set.of());
        if(type==RemoteControllerCompat.Type.ADVANCED)for(var pulse:pulses) {
            Set<String> keys=pulse.inputs().stream().filter(acceptedObserved::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
            wheelKeys.addAll(keys);wheel.add(keys,pulse.steps());
        }
        if (controller != null && ControllerTracker.findForPlayerAim(player) == controller) {
            String rangeKey = controller.getRemoteKeys(type).get(StabilizerControllerBlockEntity.INPUT_ACTION_LASER_RANGE);
            boolean wasPressed = previous != null && previous.controller == controller && previous.type == type
                    && previous.expiresAt >= player.level().getGameTime() && previous.pressed.contains(rangeKey);
            if (!rangeKey.isEmpty() && accepted.contains(rangeKey) && !wasPressed)
                controller.requestLaserRange(player, sample);
        }
        SESSIONS.put(player.getUUID(), new Session(
                player, same ? previous.token : UUID.randomUUID(), acceptedObserved,
                controller,
                player.level().dimension().location().toString(),
                hubPos.immutable(),
                type,
                accepted,
                player.level().getGameTime() + TIMEOUT_TICKS,wheel,Set.copyOf(wheelKeys)));
    }

    public static boolean isPressed(StabilizerControllerBlockEntity controller, RemoteControllerCompat.Type type, String key) {
        if (controller.getLevel() == null) return false;
        long now = controller.getLevel().getGameTime();
        prune();
        return SESSIONS.values().stream().anyMatch(session -> session.controller == controller
                && session.type == type && (session.pressed.contains(key)||session.wheel.active(now).contains(key)));
    }
    public static boolean usesPulses(StabilizerControllerBlockEntity controller,RemoteControllerCompat.Type type,String key) {
        return SESSIONS.values().stream().anyMatch(session->session.controller==controller&&session.type==type
                &&controller.getLevel()!=null&&session.expiresAt>=controller.getLevel().getGameTime()&&session.wheelKeys.contains(key));
    }
    public static void clear(ServerPlayer player) {SESSIONS.remove(player.getUUID());}

    public static NativeInputSnapshot snapshot(Level level, RemoteControllerCompat.Type type, BlockPos hubPos) {
        prune();
        Map<String, Integer> signals = new HashMap<>();
        Set<String> observed = new HashSet<>(), pulses = new HashSet<>();
        UUID token = null;
        for (Session session : SESSIONS.values()) {
            if (session.player.level() != level || session.type != type || !session.hubPos.equals(hubPos)) continue;
            if (token == null) token = session.token;
            observed.addAll(session.observed);
            pulses.addAll(session.wheelKeys);
            session.pressed.forEach(key -> signals.put(key, 15));
            session.wheel.active(level.getGameTime()).forEach(key -> signals.put(key, 15));
        }
        return token == null ? NativeInputSnapshot.EMPTY : new NativeInputSnapshot(token, signals, observed, pulses);
    }

    private static void prune() {
        SESSIONS.values().removeIf(session -> session.expiresAt < session.player.level().getGameTime()
                || !session.player.isAlive() || session.player.hasDisconnected() || session.player.isSpectator()
                || !RemoteControlTracker.matchesActiveHubSession(session.player, session.type, session.hubPos));
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) { SESSIONS.clear(); }

    /** Returns direct physical input for a terminal-bound non-FCC consumer. */
    public static boolean isPressed(
            Level level,
            RemoteControllerCompat.Type type,
            BlockPos hubPos,
            String key
    ) {
        if (level == null || key == null || key.isEmpty()) return false;
        long now = level.getGameTime();
        prune();
        String dimension = level.dimension().location().toString();
        return SESSIONS.values().stream().anyMatch(session ->
                session.dimension.equals(dimension)
                        && session.hubPos.equals(hubPos)
                        && session.type == type
                        && (session.pressed.contains(key)||session.wheel.active(now).contains(key)));
    }

    private record Session(
            ServerPlayer player, UUID token, Set<String> observed,
            StabilizerControllerBlockEntity controller,
            String dimension,
            BlockPos hubPos,
            RemoteControllerCompat.Type type,
            Set<String> pressed,
            long expiresAt,ControllerPulseQueue wheel,Set<String> wheelKeys
    ) { }
    private RemoteInputTracker() { }
}
