package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.*;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ControllerClientRegistration {
    @SubscribeEvent public static void lecternRenderer(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(com.hooya.stabilizedturret.registry.ModBlockEntities.WIRELESS_LECTERN_CONTROLLER.get(),WirelessLecternControllerRenderer::new);
    }
    @SubscribeEvent public static void keyboardModels(ModelEvent.RegisterAdditional event) {
        KeyboardControllerModel.register(event);
    }
    @SubscribeEvent public static void keyboardReload(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        KeyboardControllerModel.reloadListener(event);
    }
    @SubscribeEvent public static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if(renderer==null)renderer=new KeyboardControllerRenderer();return renderer;
            }
            @Override public boolean applyForgeHandTransform(PoseStack pose, net.minecraft.client.player.LocalPlayer player,
                    net.minecraft.world.entity.HumanoidArm arm, ItemStack stack, float partialTick, float equip, float swing) {
                double smooth = WirelessControllerClient.modelCenterProgress(stack);
                if (smooth <= 0) return false;
                double side = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
                pose.translate(side * 0.56 * (1 - smooth), -0.52 - 0.11 * smooth,
                        -0.72 - 0.28 * smooth);
                return true;
            }
        },ModItems.WIRELESS_CONTROLLER.get());
    }
}
