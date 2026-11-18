package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.compat.QModelingThermalRenderBridge;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Applies one native-resolution monochrome pass to the already completed world
 * image. Geometry, alpha blending, cutout discard and depth remain entirely in
 * the original renderer; this class never patches or re-renders world shaders.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ThermalPostProcessor {
    private static final ResourceLocation NATIVE_EFFECT = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID,
            "shaders/post/thermal.json"
    );
    private static PostChain chain;
    private static int width = -1;
    private static int height = -1;
    private static long retryAfterMillis;
    private static int consecutiveFailures;

    /** Runs after Iris/Sodium/Voxy/Sable finish the world and before the HUD. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void renderAfterIris(RenderGuiEvent.Pre event) {
        if (!ClientAimState.isThermal()
                || System.currentTimeMillis() < retryAfterMillis) {
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
            minecraft.getMainRenderTarget().bindWrite(false);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            consecutiveFailures = 0;
            retryAfterMillis = 0L;
        } catch (Exception exception) {
            recordFailure(exception);
        }
    }

    private static void ensureChain(Minecraft minecraft) throws Exception {
        if (chain == null) {
            chain = new PostChain(
                    minecraft.getTextureManager(),
                    minecraft.getResourceManager(),
                    minecraft.getMainRenderTarget(),
                    NATIVE_EFFECT
            );
            width = -1;
            height = -1;
        }
        int nextWidth = minecraft.getMainRenderTarget().viewWidth;
        int nextHeight = minecraft.getMainRenderTarget().viewHeight;
        if (width != nextWidth || height != nextHeight) {
            chain.resize(nextWidth, nextHeight);
            width = nextWidth;
            height = nextHeight;
        }
    }

    private static void recordFailure(Exception exception) {
        consecutiveFailures++;
        retryAfterMillis = System.currentTimeMillis()
                + Math.min(30_000L, 2_000L * consecutiveFailures);
        if (consecutiveFailures == 1) {
            StabilizedTurret.LOGGER.error("Unable to render fire-control thermal optic", exception);
        } else if (consecutiveFailures == 2 || consecutiveFailures % 5 == 0) {
            StabilizedTurret.LOGGER.warn(
                    "Thermal optic still unavailable; retry {} in {} ms",
                    consecutiveFailures,
                    Math.max(0L, retryAfterMillis - System.currentTimeMillis())
            );
        }
        shutdown();
    }

    public static void shutdown() {
        QModelingThermalRenderBridge.shutdown();
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
            event.registerReloadListener(
                    (ResourceManagerReloadListener) resourceManager -> resourcesReloaded()
            );
        }

        private ReloadRegistration() {
        }
    }

    private ThermalPostProcessor() {
    }
}
