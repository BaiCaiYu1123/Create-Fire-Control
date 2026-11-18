package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.diesel.*;
import com.hooya.stabilizedturret.network.*;
import com.hooya.stabilizedturret.registry.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual client model baking, renderer registration, settings screen and screenshots. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class DieselClientChecks {
    private static int phase, age;
    private static volatile boolean screenshotDone;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("create_fire_control.dieselClientChecks")) return;
        var mc=Minecraft.getInstance();
        try {
            if(phase==0 && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
                mc.options.guiScale().set(1);
                mc.resizeDisplay();
                checkModels(mc);
                mc.setScreen(new PreviewScreen());phase=1;age=0;
            } else if(phase==1 && ++age==25) {
                Screenshot.grab(mc.gameDirectory,"ztz99a_item.png",mc.getMainRenderTarget(),c->screenshotDone=true);
            } else if(phase==1 && screenshotDone) {
                screenshotDone=false;
                var initial=new OpenZtz99aDieselEnginePayload(BlockPos.ZERO,null,true,false, com.hooya.stabilizedturret.content.turbine.EngineControlSettings.defaults());
                ClientPayloadHandlers.openZtz99aDieselEngine(initial);
                require(mc.screen.getTitle().getString().contains("99A"),"independent GUI title");
                phase=2;age=0;
            } else if(phase==2 && ++age==25) {
                Screenshot.grab(mc.gameDirectory,"ztz99a_gui.png",mc.getMainRenderTarget(),c->screenshotDone=true);
            } else if(phase==2 && screenshotDone) finish(mc,null);
        } catch(Throwable error) { finish(mc,error); }
    }
    private static void checkModels(Minecraft mc) {
        StabilizedTurret.LOGGER.info("DIESEL QA mod file: {}",net.neoforged.fml.ModList.get()
                .getModFileById(StabilizedTurret.MOD_ID).getFile().getFilePath());
        require(mc.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID,"textures/block/ztz99a_diesel_engine.png")).isPresent(),
                "runtime texture resource lookup including persisted caches");
        require(Ztz99aDieselSoundConfig.SPEC.isLoaded(),"independent audio config loaded");
        var mix=Ztz99aDieselSoundConfig.parameters();
        require(mix.trimsDb().size()==26,"26 independent track trims");
        require(mix.idle().db(.5F)>-1 && mix.loaded().db(.5F)>4.9F,"native sampled load response on client");
        for (String sample:Ztz99aAudioCalibration.SAMPLES) {
            require(mc.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID,"sounds/engine/ztz99a/"+sample+".ogg")).isPresent(),
                    "runtime sound resource lookup "+sample);
            require(mc.getSoundManager().getSoundEvent(ModSounds.ZTZ99A.get(sample).get().getLocation())!=null,
                    "resolved diesel sound event "+sample);
        }
        var missing=mc.getModelManager().getMissingModel();
        for(var part:Ztz99aDieselEnginePart.values()) for(var facing:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}) {
            var state=ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState()
                    .setValue(Ztz99aDieselEngineBlock.PART,part).setValue(Ztz99aDieselEngineBlock.FACING,facing);
            var model=mc.getBlockRenderer().getBlockModel(state);
            require(model!=missing,"baked slice "+part+" "+facing);
            var quads=model.getQuads(state,null,RandomSource.create());
            require(!quads.isEmpty(),"visible baked quads");
            for(var quad:quads) require(quad.getSprite().contents().name().toString()
                    .equals("create_fire_control:block/ztz99a_diesel_engine"),"correct texture atlas sprite");
            if(part.isController()||part.isOutput()) require(mc.getBlockEntityRenderDispatcher().getRenderer(
                    new Ztz99aDieselEngineBlockEntity(BlockPos.ZERO,state))!=null,"registered shaft renderer");
        }
        var item=mc.getItemRenderer().getModel(new ItemStack(ModItems.ZTZ99A_DIESEL_ENGINE.get()),null,null,0);
        require(item!=missing && !item.getQuads(null,null,RandomSource.create()).isEmpty(),"inventory OBJ baking");
        for(var quad:item.getQuads(null,null,RandomSource.create())) require(quad.getSprite().contents().name()
                .toString().equals("create_fire_control:block/ztz99a_diesel_engine"),"inventory texture sprite");
    }
    private static void finish(Minecraft mc,Throwable error) {
        phase=3;
        if(error!=null) StabilizedTurret.LOGGER.error("DIESEL CLIENT CHECK FAILED",error);
        try { Files.writeString(Path.of("diesel-client-result.txt"),error==null?"PASS":"FAIL: "+error); }
        catch(Exception failure) { throw new RuntimeException(failure); }
        mc.stop();
    }
    private static void require(boolean value,String label) { if(!value) throw new AssertionError(label); }
    private static final class PreviewScreen extends Screen {
        private PreviewScreen() { super(Component.literal("99A model QA")); }
        @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial) {
            g.fill(0,0,width,height,0xff182127);
            g.drawCenteredString(font,"ZTZ99A Diesel Engine — actual Minecraft inventory renderer",width/2,20,0xffffff);
            g.pose().pushPose();
            g.pose().translate(width/2F-64,height/2F-64,0);
            g.pose().scale(8,8,8);
            g.renderFakeItem(new ItemStack(ModItems.ZTZ99A_DIESEL_ENGINE.get()),0,0);
            g.pose().popPose();g.flush();
        }
    }
}
