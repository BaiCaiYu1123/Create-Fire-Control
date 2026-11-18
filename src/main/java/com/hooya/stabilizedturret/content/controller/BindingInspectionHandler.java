package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.BindingInspectionPayload;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class BindingInspectionHandler {
    private static final long DISPLAY_TICKS = 20L * 20L;
    private static final Map<UUID, InspectionSession> ACTIVE = new ConcurrentHashMap<>();

    public static void toggle(
            ServerPlayer player,
            StabilizerControllerBlockEntity controller
    ) {
        Level level = controller.getLevel();
        if (level == null) return;
        BindingRef source = BindingRef.capture(level, controller.getBlockPos());
        long now = level.getGameTime();
        InspectionSession current = ACTIVE.get(player.getUUID());
        if (current != null && current.expiresAt() > now && current.source().equals(source)) {
            close(player);
            return;
        }

        ACTIVE.put(player.getUUID(), new InspectionSession(source, now + DISPLAY_TICKS));
        PacketDistributor.sendToPlayer(player, payload(controller));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        InspectionSession session = ACTIVE.get(player.getUUID());
        if (session == null) return;
        if (session.expiresAt() <= player.level().getGameTime()
                || !session.source().isInDimension(player.level())
                || !(session.source().resolve(player.level())
                instanceof StabilizerControllerBlockEntity)) {
            close(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE.remove(event.getEntity().getUUID());
    }

    private static BindingInspectionPayload payload(
            StabilizerControllerBlockEntity controller
    ) {
        LinkedHashSet<BindingSessionPayload.Candidate> bindings = new LinkedHashSet<>();
        for (StabilizerControllerBlockEntity.BoundDevice device : controller.getBoundDevices()) {
            BindingRef ref = device.ref();
            bindings.add(new BindingSessionPayload.Candidate(ref.pos(), ref.subLevelId(), true));
        }
        List<BindingSessionPayload.Candidate> scopeReferences =
                controller.getScopeReferenceBindings().stream()
                        .map(ref -> new BindingSessionPayload.Candidate(
                                ref.pos(), ref.subLevelId(), true))
                        .toList();
        Vec3 offset = controller.getScopeOffset();
        return new BindingInspectionPayload(
                true,
                List.copyOf(bindings),
                scopeReferences,
                offset.x,
                offset.y,
                offset.z
        );
    }

    private static void close(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player, BindingInspectionPayload.inactive());
    }

    private record InspectionSession(BindingRef source, long expiresAt) {
    }

    private BindingInspectionHandler() {
    }
}
