package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.renderer.Ztz99aDieselEngineRenderer;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.client.renderer.GasTurbineRenderer;
import com.hooya.stabilizedturret.client.renderer.AzLoaderRenderer;
import com.hooya.stabilizedturret.client.renderer.MzLoaderRenderer;
import com.hooya.stabilizedturret.client.renderer.Df26BallisticMissileRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class ConnectedDisplayClientRegistration{
 @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e){
  e.registerBlockEntityRenderer(ModBlockEntities.CONNECTED_DISPLAY.get(),ConnectedDisplayRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.IFF_TRANSPONDER.get(),IffTransponderRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.GAS_TURBINE.get(),GasTurbineRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.ZTZ99A_DIESEL_ENGINE.get(),Ztz99aDieselEngineRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.TIGER_I_ENGINE.get(),com.hooya.stabilizedturret.client.renderer.TigerIEngineRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.MZ_LOADER.get(),MzLoaderRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.AZ_LOADER.get(),AzLoaderRenderer::new);
  e.registerBlockEntityRenderer(ModBlockEntities.TYPE_10_LOADER.get(),com.hooya.stabilizedturret.client.renderer.Type10LoaderRenderer::new);
 e.registerBlockEntityRenderer(
          ModBlockEntities.DF_26_BALLISTIC_MISSILE.get(),
          Df26BallisticMissileRenderer::new);
 }
 @SubscribeEvent public static void registerModels(ModelEvent.RegisterAdditional e){
  e.register(ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(
          StabilizedTurret.MOD_ID,"block/df_26")));
 }
 private ConnectedDisplayClientRegistration(){}
}
