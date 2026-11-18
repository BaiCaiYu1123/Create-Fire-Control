package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.RemoteWeaponStationControlStatePayload;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Right-click, ride-like manual control for Mianbao remote weapon stations. */
// Legacy implementation retained only for binary/source compatibility.
// Player control now belongs to Mianbao's native controller item.
public final class MianbaoRemoteWeaponStationControlHandler {
    private static final String CONTROL_SEAT_TAG =
            "CreateFireControlRemoteWeaponStationSeat";
    private static final Map<UUID, ControlSession> SESSIONS = new HashMap<>();
    private static final long INPUT_TIMEOUT_TICKS = 10L;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!MianbaoRemoteWeaponStationCompat.isStation(level, event.getPos())
                || event.getEntity().isShiftKeyDown()
                || AllItems.WRENCH.isIn(event.getItemStack())) {
            return;
        }
        consume(event);
        if (level.isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        BindingRef station = BindingRef.capture(level, event.getPos());
        if (SESSIONS.values().stream().anyMatch(
                session -> session.station().equals(station))) {
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.remote_weapon_station.occupied"), true);
            return;
        }
        stop(player, false);
        Vec3 seatPosition = MianbaoRemoteWeaponStationCompat
                .getControlSeatPosition(level, station);
        ServerLevel root = level.getServer() == null
                ? null : level.getServer().getLevel(level.dimension());
        if (seatPosition == null || root == null) return;

        RemoteStationSeat seat = new RemoteStationSeat(root);
        seat.setPos(seatPosition.x, seatPosition.y, seatPosition.z);
        seat.getPersistentData().putBoolean(CONTROL_SEAT_TAG, true);
        if (!root.addFreshEntity(seat) || !player.startRiding(seat, true)) {
            seat.discard();
            return;
        }
        SESSIONS.put(player.getUUID(), new ControlSession(station, seat));
        PacketDistributor.sendToPlayer(player,
                new RemoteWeaponStationControlStatePayload(true));
        player.displayClientMessage(Component.translatable(
                "message.create_fire_control.remote_weapon_station.controls"), true);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ControlSession session = SESSIONS.get(player.getUUID());
        if (session == null) return;
        RemoteStationSeat seat = session.seat();
        Level level = player.level();
        if (seat.isRemoved() || player.getVehicle() != seat
                || !session.station().isSameTarget(level)
                || !MianbaoRemoteWeaponStationCompat.isStation(
                level, session.station().pos())) {
            stop(player, true);
            return;
        }

        Vec3 seatPosition = MianbaoRemoteWeaponStationCompat
                .getControlSeatPosition(level, session.station());
        if (seatPosition == null) {
            stop(player, true);
            return;
        }
        seat.setPos(seatPosition.x, seatPosition.y, seatPosition.z);
        Vec3 view = player.getLookAngle();
        if (view.lengthSqr() > 1.0e-8) {
            MianbaoRemoteWeaponStationCompat.pointMuzzleAt(
                    level, session.station(), player.getEyePosition()
                            .add(view.normalize().scale(4096.0)));
        }
        boolean triggerFresh = session.triggerHeld
                && level.getGameTime() - session.lastInputTick <= INPUT_TIMEOUT_TICKS;
        MianbaoRemoteWeaponStationCompat.tickDirectFire(
                level, session.station(), triggerFresh);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ControlSession session = SESSIONS.remove(event.getEntity().getUUID());
        if (session != null) session.seat().discard();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ControlSession session : SESSIONS.values()) session.seat().discard();
        SESSIONS.clear();
    }

    public static boolean isDirectlyControlled(Level level, BindingRef station) {
        return level != null && station != null && SESSIONS.values().stream()
                .anyMatch(session -> !session.seat().isRemoved()
                        && session.station().equals(station));
    }

    public static void updateTrigger(ServerPlayer player, boolean firing) {
        ControlSession session = SESSIONS.get(player.getUUID());
        if (session == null || player.getVehicle() != session.seat()) return;
        session.triggerHeld = firing;
        session.lastInputTick = player.level().getGameTime();
    }

    private static void stop(ServerPlayer player, boolean notify) {
        ControlSession session = SESSIONS.remove(player.getUUID());
        if (session == null) return;
        if (player.getVehicle() == session.seat()) player.stopRiding();
        session.seat().discard();
        PacketDistributor.sendToPlayer(player,
                new RemoteWeaponStationControlStatePayload(false));
        if (notify) player.displayClientMessage(
                Component.translatable("message.create_fire_control.remote_weapon_station.exited"), true);
    }

    private static void consume(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static final class ControlSession {
        private final BindingRef station;
        private final RemoteStationSeat seat;
        private boolean triggerHeld;
        private long lastInputTick = Long.MIN_VALUE;

        private ControlSession(BindingRef station, RemoteStationSeat seat) {
            this.station = station;
            this.seat = seat;
        }

        private BindingRef station() {
            return station;
        }

        private RemoteStationSeat seat() {
            return seat;
        }
    }

    /** Uses Create's synced seat type while allowing a non-seat host block. */
    private static final class RemoteStationSeat extends SeatEntity {
        private RemoteStationSeat(Level level) {
            super(level);
        }

        @Override
        public void tick() {
            // Normal Create seats delete themselves unless a SeatBlock is
            // underneath. This virtual seat is explicitly session-owned.
        }
    }

    private MianbaoRemoteWeaponStationControlHandler() {
    }
}
