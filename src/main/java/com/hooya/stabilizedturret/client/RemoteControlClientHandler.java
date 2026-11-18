package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.network.RemoteControlStatePayload;
import com.hooya.stabilizedturret.network.RemoteInputStatePayload;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerClientHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import com.hooya.stabilizedturret.content.remote.ControllerPulseQueue.Pulse;
import com.hooya.stabilizedturret.content.remote.ControllerInput;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class RemoteControlClientHandler {
    private static final String ADVANCED_HANDLER =
            "com.getitemfromblock.create_tweaked_controllers.controller.TweakedLinkedControllerClientHandler";
    private static boolean lastActive;
    private static boolean lastAdvanced;
    private static BlockPos lastHub = BlockPos.ZERO;
    private static Set<String> lastPressed = Set.of();
    private static final List<Pulse> pendingWheelSignals = new ArrayList<>();

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.screen != null) {
            return;
        }
        RemoteState state = detectState(minecraft);
        if (!state.active || !state.advanced) return;
        Set<String> matched = ControllerKeyMappingCompat.signalsForMouseScroll(
                RemoteControllerCompat.Type.ADVANCED, event.getScrollDeltaY());
        if (matched.isEmpty()) return;
        if(pendingWheelSignals.size()<64)pendingWheelSignals.add(new Pulse(matched,ControllerInput.scrollSteps(event.getScrollDeltaY())));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            lastActive = false;
            lastPressed = Set.of();
            pendingWheelSignals.clear();
            return;
        }

        RemoteState state = detectState(minecraft);
        boolean changed = state.active != lastActive
                || state.advanced != lastAdvanced
                || !state.hubPos.equals(lastHub);
        if (changed || (state.active && minecraft.player.tickCount % 5 == 0)) {
            PacketDistributor.sendToServer(new RemoteControlStatePayload(
                    state.active, state.advanced, state.hubPos
            ));
            lastActive = state.active;
            lastAdvanced = state.advanced;
            lastHub = state.hubPos;
        }
        Set<String> pressed = state.active ? ControllerKeyMappingCompat.pressedSignals(
                state.advanced ? RemoteControllerCompat.Type.ADVANCED : RemoteControllerCompat.Type.NORMAL) : Set.of();
        Set<String> transmitted = pressed;
        boolean releaseWheelPulse = state.active
                && lastPressed.stream().anyMatch(signal -> !pressed.contains(signal));
        if (state.active && (changed || !pendingWheelSignals.isEmpty()||!transmitted.equals(lastPressed)
                || releaseWheelPulse || minecraft.player.tickCount % 5 == 0)) {
            PacketDistributor.sendToServer(new RemoteInputStatePayload(
                    state.advanced, state.hubPos, transmitted,
                    transmitted.stream().anyMatch(key -> !lastPressed.contains(key))
                            ? ClientAimState.captureLaserRangeSample() : null,List.copyOf(pendingWheelSignals),
                    ControllerKeyMappingCompat.observedSignals(state.advanced
                            ? RemoteControllerCompat.Type.ADVANCED : RemoteControllerCompat.Type.NORMAL)));
        }
        lastPressed = transmitted;
        pendingWheelSignals.clear();
    }

    private static RemoteState detectState(Minecraft minecraft) {
        if (LinkedControllerClientHandler.MODE == LinkedControllerClientHandler.Mode.ACTIVE) {
            Optional<BlockPos> hub = RemoteControllerCompat.findHeldBoundHub(
                    minecraft.player, RemoteControllerCompat.Type.NORMAL
            );
            if (hub.isPresent()) {
                return new RemoteState(true, false, hub.get());
            }
        }
        if (isAdvancedControllerActive()) {
            Optional<BlockPos> hub = RemoteControllerCompat.findHeldBoundHub(
                    minecraft.player, RemoteControllerCompat.Type.ADVANCED
            );
            if (hub.isPresent()) {
                return new RemoteState(true, true, hub.get());
            }
        }
        // Create's linked-controller client mode can drop out when another
        // entity becomes the camera. Television guidance deliberately uses a
        // missile proxy as that entity, but the same held controller must keep
        // delivering thermal and toggle-view inputs until the player exits.
        // The server still validates the held bound controller and terminal.
        if (ClientTelevisionGuidanceState.isActive() && lastActive) {
            RemoteControllerCompat.Type type = lastAdvanced
                    ? RemoteControllerCompat.Type.ADVANCED
                    : RemoteControllerCompat.Type.NORMAL;
            Optional<BlockPos> hub = RemoteControllerCompat.findHeldBoundHub(
                    minecraft.player, type);
            if (hub.isPresent() && hub.get().equals(lastHub)) {
                return new RemoteState(true, lastAdvanced, lastHub);
            }
        }
        return new RemoteState(false, false, BlockPos.ZERO);
    }

    private static boolean isAdvancedControllerActive() {
        try {
            Class<?> handler = Class.forName(ADVANCED_HANDLER);
            Field modeField = handler.getField("MODE");
            Object mode = modeField.get(null);
            return mode instanceof Enum<?> value && "ACTIVE".equals(value.name());
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private record RemoteState(boolean active, boolean advanced, BlockPos hubPos) {
    }

    private RemoteControlClientHandler() {
    }
}
