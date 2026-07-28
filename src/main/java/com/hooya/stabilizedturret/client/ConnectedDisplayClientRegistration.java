package com.hooya.stabilizedturret.client;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.client.renderer.GasTurbineRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class ConnectedDisplayClientRegistration{
 @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e){
  e.registerBlockEntityRenderer(ModBlockEntities.CONNECTED_DISPLAY.get(),ConnectedDisplayRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.GAS_TURBINE.get(),GasTurbineRenderer::new);
 }
 private ConnectedDisplayClientRegistration(){}
}
