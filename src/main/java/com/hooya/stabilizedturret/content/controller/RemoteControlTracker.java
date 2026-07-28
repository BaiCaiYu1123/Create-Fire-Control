package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RemoteControlTracker {
    private static final long TIMEOUT_TICKS = 12L;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void update(
            ServerPlayer player,
            boolean active,
            RemoteControllerCompat.Type type,
            BlockPos hubPos
    ) {
        if (!active) {
            Session previous = SESSIONS.remove(player.getUUID());
            closePreviousViews(player, previous);
            return;
        }
        if (!RemoteControllerCompat.isHoldingBoundController(player, type, hubPos)
                || !RemoteControllerCompat.isTerminal(player.level(), hubPos, type)) {
            Session previous = SESSIONS.remove(player.getUUID());
            closePreviousViews(player, previous);
            return;
        }
        Session next = new Session(
                player.level().dimension().location().toString(),
                hubPos.immutable(),
                type,
                player.level().getGameTime() + TIMEOUT_TICKS
        );
        Session previous = SESSIONS.put(player.getUUID(), next);
        if (previous != null && (!previous.dimension.equals(next.dimension)
                || !previous.hubPos.equals(next.hubPos) || previous.type != next.type)) {
            closePreviousViews(player, previous);
        }
    }

    public static StabilizerControllerBlockEntity findController(ServerPlayer player) {
        StabilizerControllerBlockEntity controller = findInputController(player);
        return controller != null && controller.isRemoteHeadAimEnabled() ? controller : null;
    }

    public static StabilizerControllerBlockEntity findInputController(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null
                || session.expiresAt < player.level().getGameTime()
                || !session.dimension.equals(player.level().dimension().location().toString())
                || !RemoteControllerCompat.isHoldingBoundController(player, session.type, session.hubPos)
                || !RemoteControllerCompat.isTerminal(player.level(), session.hubPos, session.type)) {
            Session removed = SESSIONS.remove(player.getUUID());
            closePreviousViews(player, removed);
            return null;
        }
        StabilizerControllerBlockEntity controller = ControllerTracker.findForRemoteTerminal(
                player.level(), session.hubPos, session.type
        );
        return controller;
    }

    public static java.util.List<ServerPlayer> findActivePlayers(
            StabilizerControllerBlockEntity controller
    ) {
        if (controller.getLevel() == null || controller.getLevel().getServer() == null) {
            return java.util.List.of();
        }
        return controller.getLevel().getServer().getPlayerList().getPlayers().stream()
                .filter(player -> findInputController(player) == controller)
                .toList();
    }

    public static ServerPlayer findActivePlayer(
            StabilizerControllerBlockEntity controller,
            RemoteControllerCompat.Type type
    ) {
        if (controller.getLevel() == null || controller.getLevel().getServer() == null) return null;
        for (ServerPlayer player : controller.getLevel().getServer().getPlayerList().getPlayers()) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null && session.type == type && findInputController(player) == controller) {
                return player;
            }
        }
        return null;
    }

    public static void clear(ServerPlayer player) {
        Session previous = SESSIONS.remove(player.getUUID());
        closePreviousViews(player, previous);
    }

    private static void closePreviousViews(ServerPlayer player, Session session) {
        if (session == null
                || !session.dimension.equals(player.level().dimension().location().toString())) {
            return;
        }
        StabilizerControllerBlockEntity controller = ControllerTracker.findForRemoteTerminal(
                player.level(), session.hubPos, session.type
        );
        if (controller != null) controller.releaseViews(player);
    }

    private record Session(
            String dimension,
            BlockPos hubPos,
            RemoteControllerCompat.Type type,
            long expiresAt
    ) {
    }

    private RemoteControlTracker() {
    }
}
