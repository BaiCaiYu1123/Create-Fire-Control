package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.RadarLockPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class RadarKeyHandler {
    public static final KeyMapping RADAR_LOCK = new KeyMapping(
            "key.stabilized_turret.radar_lock",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.stabilized_turret");

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (RADAR_LOCK.consumeClick()) {
            if (!ClientRadarState.isActive()) continue;
            UUID current = ClientRadarState.getLockedTarget();
            UUID target = current == null ? RadarHudRenderer.findNearestContactToReticle() : null;
            PacketDistributor.sendToServer(new RadarLockPayload(target));
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RadarKeyHandler::registerMappings);
    }

    private static void registerMappings(RegisterKeyMappingsEvent event) {
        event.register(RADAR_LOCK);
    }

    private RadarKeyHandler() {
    }
}
