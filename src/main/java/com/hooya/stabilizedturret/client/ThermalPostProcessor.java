package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Runs after Iris/Sodium have completed the world frame but before any HUD.
 * This deliberately does not use GameRenderer's single post-effect slot,
 * which Iris replaces on the following frame.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ThermalPostProcessor {
    private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID,
            "shaders/post/thermal.json"
    );
    private static PostChain chain;
    private static int width = -1;
    private static int height = -1;
    private static long retryAfterMillis;
    private static int consecutiveFailures;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void renderAfterIris(RenderGuiEvent.Pre event) {
        if (!ClientAimState.isThermal()) {
            return;
        }
        if (System.currentTimeMillis() < retryAfterMillis) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            ensureChain(minecraft);
            if (chain == null) {
                return;
            }
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(event.getPartialTick().getGameTimeDeltaTicks());
            // PostChain unbinds its output. The GUI that follows expects the
            // game's main target to be active again.
            minecraft.getMainRenderTarget().bindWrite(false);
        } catch (Exception exception) {
            consecutiveFailures++;
            retryAfterMillis = System.currentTimeMillis()
                    + Math.min(30_000L, 2_000L * consecutiveFailures);
            if (consecutiveFailures == 1) {
                StabilizedTurret.LOGGER.error("Unable to render stabilized turret thermal optic", exception);
            } else if (consecutiveFailures == 2 || consecutiveFailures % 5 == 0) {
                StabilizedTurret.LOGGER.warn(
                        "Thermal optic post-process still unavailable; retry {} in {} ms",
                        consecutiveFailures,
                        Math.max(0L, retryAfterMillis - System.currentTimeMillis())
                );
            }
            shutdown();
        }
    }

    private static void ensureChain(Minecraft minecraft) throws Exception {
        if (chain == null) {
            chain = new PostChain(
                    minecraft.getTextureManager(),
                    minecraft.getResourceManager(),
                    minecraft.getMainRenderTarget(),
                    EFFECT
            );
            width = -1;
            height = -1;
            consecutiveFailures = 0;
            retryAfterMillis = 0L;
        }
        int nextWidth = minecraft.getWindow().getWidth();
        int nextHeight = minecraft.getWindow().getHeight();
        if (width != nextWidth || height != nextHeight) {
            chain.resize(nextWidth, nextHeight);
            width = nextWidth;
            height = nextHeight;
        }
    }

    public static void shutdown() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        width = -1;
        height = -1;
    }

    public static void beginSession() {
        retryAfterMillis = 0L;
        consecutiveFailures = 0;
    }

    public static void resourcesReloaded() {
        shutdown();
        beginSession();
    }

    @EventBusSubscriber(
            modid = StabilizedTurret.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD
    )
    public static final class ReloadRegistration {
        @SubscribeEvent
        public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> resourcesReloaded());
        }

        private ReloadRegistration() {
        }
    }

    private ThermalPostProcessor() {
    }
}
