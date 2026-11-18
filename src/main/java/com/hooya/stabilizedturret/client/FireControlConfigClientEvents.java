package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class FireControlConfigClientEvents {
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { FireControlSettings.beginConnection(); }
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { FireControlSettings.clearRemoteView(); }
    private FireControlConfigClientEvents() {}
}
