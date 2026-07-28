package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayLayout;
import com.hooya.stabilizedturret.network.AutoDefenseSettingsPayload;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Repeats only the range-slider action while RMB is held. Buttons and radar
 * contacts still use the normal block interaction once, so toggles cannot be
 * triggered repeatedly by a held mouse button.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ConnectedDisplayInteractionHandler {
    private static double lastSentRange = Double.NaN;
    private static long lastSentAt = Long.MIN_VALUE;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || minecraft.screen != null
                || minecraft.player.isShiftKeyDown()
                || AllItems.WRENCH.isIn(minecraft.player.getMainHandItem())
                || AllItems.WRENCH.isIn(minecraft.player.getOffhandItem())
                || GLFW.glfwGetMouseButton(
                minecraft.getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS
                || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            lastSentRange = Double.NaN;
            return;
        }
        Level hitLevel = minecraft.level;
        try {
            SubLevel containing = Sable.HELPER.getContaining(
                    minecraft.level, hit.getLocation());
            if (containing != null && !containing.isRemoved()) {
                hitLevel = containing.getLevel();
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        if (!(hitLevel.getBlockState(hit.getBlockPos()).getBlock()
                instanceof ConnectedDisplayBlock)) {
            return;
        }
        ConnectedDisplayLayout.ScreenHit screen =
                ConnectedDisplayLayout.locate(
                        hitLevel, hit.getBlockPos(), hit.getLocation());
        if (screen == null || !ConnectedDisplayLayout.inside(
                screen.canvasX(), screen.canvasY(),
                ConnectedDisplayLayout.SLIDER_X1,
                ConnectedDisplayLayout.SLIDER_Y1,
                ConnectedDisplayLayout.SLIDER_X2,
                ConnectedDisplayLayout.SLIDER_Y2
        )) {
            return;
        }
        double range = ConnectedDisplayLayout.sliderRange(screen.canvasX());
        long now = minecraft.level.getGameTime();
        if (Double.isFinite(lastSentRange)
                && Math.abs(range - lastSentRange) < 9.9
                && now - lastSentAt < 4L) {
            return;
        }
        ConnectedDisplayBlockEntity display = screen.controller();
        BindingRef reference = BindingRef.capture(
                hitLevel, display.getBlockPos());
        PacketDistributor.sendToServer(new AutoDefenseSettingsPayload(
                display.getBlockPos(),
                reference.subLevelId(),
                display.isAutomaticAttack(),
                range,
                display.getYawSpeed(),
                display.getPitchSpeed(),
                display.getDepressionLimit(),
                display.getElevationLimit(),
                display.isYawReversed(),
                display.isPitchReversed(),
                display.getCannonFireRange()
        ));
        lastSentRange = range;
        lastSentAt = now;
    }

    private ConnectedDisplayInteractionHandler() {}
}
