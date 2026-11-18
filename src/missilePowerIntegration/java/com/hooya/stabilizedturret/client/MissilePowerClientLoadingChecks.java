package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the real client-only targets so invalid effect/audio injections fail before delivery. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MissilePowerClientLoadingChecks {
    private static volatile boolean completed;
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if (!Boolean.getBoolean("create_fire_control.missilePowerClientChecks")) return;
        event.enqueueWork(() -> {
            try {
                Class.forName("net.mcreator.myfirstmod.entity.BallisticMissileLaunchEffects").getDeclaredMethods();
                Class.forName("net.mcreator.myfirstmod.client.sound.BallisticMissileBoosterSound").getDeclaredMethods();
                Files.writeString(Path.of("missile-power-client-result.txt"),
                        "PASS: native ballistic exhaust and booster-loop client mixins loaded");
            } catch (Throwable failure) {
                StabilizedTurret.LOGGER.error("MISSILE POWER CLIENT FAILED", failure);
                try { Files.writeString(Path.of("missile-power-client-result.txt"), "FAIL: " + failure); }
                catch (Exception io) { throw new RuntimeException(io); }
            } finally { completed = true; }
        });
    }

    @EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
    public static final class FinishOnTick {
        private static int readyTicks;
        @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            // Texture reload workers must finish before TextureManager is closed.
            if (completed && Minecraft.getInstance().getOverlay() == null && ++readyTicks >= 5)
                Minecraft.getInstance().stop();
        }
    }
}
