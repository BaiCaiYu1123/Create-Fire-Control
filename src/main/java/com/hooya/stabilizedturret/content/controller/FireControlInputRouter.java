package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.remote.KeyboardControllerInputs;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Shared backend for FCC and every engine; neither native source depends on Drive By Wire. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class FireControlInputRouter {
    private record Terminal(BlockPos pos, RemoteControllerCompat.Type type) { }
    private static final class Route {
        final RemoteInputRoute inputs = new RemoteInputRoute();
        long lastRead;
        boolean reported;
    }
    private static final Map<Level, Map<Terminal, Route>> ROUTES = new WeakHashMap<>();

    public static Map<String, Integer> signals(Level level, BlockPos pos, RemoteControllerCompat.Type type) {
        // The full-keyboard controller always uses its own transport, even when Drive By Wire is installed.
        if (type == RemoteControllerCompat.Type.WIRELESS) return KeyboardControllerInputs.signals(level, pos);
        if (level == null || level.isClientSide || pos == null || !level.hasChunkAt(pos)
                || !RemoteControllerCompat.isTerminal(level, pos, type)) return Map.of();
        Map<Terminal, Route> routes = ROUTES.computeIfAbsent(level, unused -> new HashMap<>());
        long now = level.getGameTime();
        routes.values().removeIf(route -> now - route.lastRead > 200);
        Route route = routes.computeIfAbsent(new Terminal(pos.immutable(), type), unused -> new Route());
        route.lastRead = now;
        NativeInputSnapshot input = RemoteInputTracker.snapshot(level, type, pos);
        DriveByWireCompat.SignalRead wire = DriveByWireCompat.readSignals(level, pos);
        Map<String, Integer> selected = route.inputs.select(wire.available(), wire.signals(), input);
        if (input.session() != null && route.inputs.usesNativeRoute() && !route.reported) {
            StabilizedTurret.LOGGER.warn("Controller input self-check failed at {} in {} ({}): {}; using fire-control native mappings",
                    pos, level.dimension().location(), type, route.inputs.failureReason());
            route.reported = true;
        }
        if (!route.inputs.usesNativeRoute()) route.reported = false;
        return selected;
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) ROUTES.remove(event.getLevel());
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { ROUTES.clear(); }
    private FireControlInputRouter() { }
}
