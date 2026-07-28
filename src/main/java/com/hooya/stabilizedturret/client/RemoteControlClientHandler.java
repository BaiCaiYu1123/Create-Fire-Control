package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.network.RemoteControlStatePayload;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerClientHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.util.Optional;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class RemoteControlClientHandler {
    private static final String ADVANCED_HANDLER =
            "com.getitemfromblock.create_tweaked_controllers.controller.TweakedLinkedControllerClientHandler";
    private static boolean lastActive;
    private static boolean lastAdvanced;
    private static BlockPos lastHub = BlockPos.ZERO;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            lastActive = false;
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
