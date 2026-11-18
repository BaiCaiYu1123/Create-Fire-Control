package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.PolyMenu;
import com.caca.copypoly.client.EditorPreferences;
import com.hooya.stabilizedturret.client.ClientVisualConfig;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.ArmorGroupMenu;
import com.hooya.stabilizedturret.registry.PolyArmorMenus;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class ArmorEditorEvents {
    @SubscribeEvent public static void capture(RenderLevelStageEvent event){
        if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_LEVEL&&Minecraft.getInstance().screen instanceof ArmorMeshScreen screen)
            screen.view.capture(event.getModelViewMatrix(),event.getProjectionMatrix(),event.getCamera().getPosition(),event.getPartialTick().getGameTimeDeltaPartialTick(true),screen.width,screen.height);
    }
    @SubscribeEvent public static void movement(MovementInputUpdateEvent event){if(Minecraft.getInstance().screen instanceof ArmorMeshScreen screen)screen.view.movement(event.getInput());}
    @SubscribeEvent public static void hand(RenderHandEvent event){if(Minecraft.getInstance().screen instanceof ArmorMeshScreen screen&&screen.view.active())event.setCanceled(true);}
    @SubscribeEvent public static void player(RenderPlayerEvent.Pre event){var mc=Minecraft.getInstance();if(event.getEntity()==mc.player&&mc.screen instanceof ArmorMeshScreen screen&&screen.view.active()&&mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(mc.player.getEyePosition())<1)event.setCanceled(true);}
    @EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void screens(RegisterMenuScreensEvent event){event.register(PolyArmorMenus.EDITOR.get(),ArmorMeshScreen::new);
            event.<PolyMenu,ArmorGroupScreen>register(PolyArmorMenus.GROUP_EDITOR.get(),
                (menu, inventory, title) -> new ArmorGroupScreen((ArmorGroupMenu)menu,inventory,title));
            EditorPreferences.bind(ClientVisualConfig::armorEditorXray,ClientVisualConfig::armorEditorXray);
            EditorPreferences.bindGroupTextures(ClientVisualConfig::armorGroupTextures,ClientVisualConfig::armorGroupTextures);
        }
        @SubscribeEvent public static void shaders(RegisterShadersEvent event) throws java.io.IOException {com.caca.copypoly.client.ArmorEditorView.registerShader(event);}
    }
    private ArmorEditorEvents(){}
}
