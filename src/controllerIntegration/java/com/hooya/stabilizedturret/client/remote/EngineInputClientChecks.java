package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.*;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.network.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Actual optional-controller profile, input capture, channel picker and rendered Minecraft UI. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class EngineInputClientChecks {
    private static int age,stage,wait;
    private static boolean launched,finished;
    private static volatile boolean captured;
    private static GasTurbineScreen screen;
    private static EngineRedstoneChannel channel;
    private static SetGasTurbineSettingsPayload saved;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("create_fire_control.engineInputClientChecks") || finished)return;
        var mc=Minecraft.getInstance();age++;
        try {
            if(age>1600)throw new AssertionError("UI fixture timed out at "+stage);
            if(!launched&&age>60&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen"))mc.setScreen(new TitleScreen());
            if(!launched&&age>60&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                launched=true;GLFW.glfwHideWindow(mc.getWindow().getWindow());
                mc.createWorldOpenFlows().createFreshLevel("EngineInputs-"+System.currentTimeMillis(),
                        new LevelSettings("Engine inputs",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);return;
            }
            if(mc.player==null||mc.level==null)return;
            if(++wait<15)return;wait=0;
            if(stage==0) {
                mc.options.guiScale().set(2);mc.resizeDisplay();
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.getToasts().clear();
                mc.player.getInventory().setItem(9,new ItemStack(Items.IRON_INGOT));mc.player.getInventory().setItem(10,new ItemStack(Items.REDSTONE));
                channel=new EngineRedstoneChannel(new ItemStack(Items.IRON_INGOT),new ItemStack(Items.REDSTONE));
                advancedCapture(mc);screen=screen(true);mc.setScreen(screen);stage=1;
            } else if(stage==1) {
                capture(mc,"engine-advanced-redstone.png");stage=2;
            } else if(stage==2&&captured) {
                // Channels stay editable on an engine with no terminal.
                screen=screen(false);mc.setScreen(screen);call(screen,"openChannel",new Class<?>[]{draftClass()},new Object[]{null});
                require(mc.screen instanceof FireControlFrequencyScreen,"unbound engine opens same inventory-based channel picker");
                stage=3;
            } else if(stage==3) { capture(mc,"engine-redstone-channel-picker.png");stage=4;
            } else if(stage==4&&captured) {
                var picker=mc.screen;
                int ix=(int)field(picker,"inventoryX"),iy=(int)field(picker,"inventoryY");
                int fx=(int)field(picker,"frequencyX"),fy=(int)field(picker,"frequencyY");
                picker.mouseClicked(fx+2,fy+2,1);picker.mouseClicked(fx+28,fy+2,1);
                picker.mouseClicked(ix+2,iy+2,0);picker.mouseClicked(fx+2,fy+2,0);
                picker.mouseClicked(ix+20,iy+2,0);picker.mouseClicked(fx+28,fy+2,0);
                button(picker,"remote.create_fire_control.save").onPress();
                require(mc.screen==screen&&field(screen,"toggleChannel").equals(channel),"channel picker stores both inventory items in parent draft");
                call(screen,"saveAndClose",new Class<?>[0],new Object[0]);
                require(saved!=null&&saved.controls().toggleChannel().equals(channel),"engine save includes ignition channel without a terminal");
                screen=screen(false);mc.setScreen(screen);mc.options.guiScale().set(3);mc.resizeDisplay();stage=5;
            } else if(stage==5) { capture(mc,"engine-redstone-small.png");stage=6;
            } else if(stage==6&&captured)finish(mc,null);
        } catch(Throwable error){finish(mc,error);}
    }
    private static GasTurbineScreen screen(boolean connected) {
        var behavior=new EngineDriveBehavior(connected?List.of("buttonB"):List.of(),256,128,128,128,128,128,
                EngineTransitionRule.DEFAULT,EngineTransitionRule.DEFAULT,channel);
        var controls=new EngineControlSettings(150,connected?"buttonA":"",List.of(behavior),false,128,128,256,channel);
        return new GasTurbineScreen(new OpenGasTurbinePayload(BlockPos.ZERO,connected,false,controls,false),
                Component.literal("Engine input checks"),value->saved=value);
    }
    private static void advancedCapture(Minecraft mc)throws Exception {
        var util=Class.forName("com.getitemfromblock.create_tweaked_controllers.controller.TweakedControlsUtil");
        Object profile=util.getField("profile").get(null);require(profile!=null,"actual Tweaked Controllers 1.2.7 profile loaded");
        var layoutField=profile.getClass().getField("layout");Object previous=layoutField.get(profile);
        Object layout=Array.newInstance(Class.forName("com.getitemfromblock.create_tweaked_controllers.input.GenericInput"),25);
        var keyboard=Class.forName("com.getitemfromblock.create_tweaked_controllers.input.KeyboardInput").getConstructor(int.class);
        Array.set(layout,0,keyboard.newInstance(87));Array.set(layout,1,keyboard.newInstance(65));Array.set(layout,2,keyboard.newInstance(-1));
        try {
            layoutField.set(profile,layout);
            require("buttonA".equals(ControllerKeyMappingCompat.signalForKeyboardKey(RemoteControllerCompat.Type.ADVANCED,87,0)),"advanced key resolves to logical controller slot");
            require(ControllerKeyMappingCompat.signalForKeyboardKey(RemoteControllerCompat.Type.ADVANCED,313,0)==null,"unconfigured physical key is rejected");
            require(!ControllerKeyMappingCompat.observedSignals(RemoteControllerCompat.Type.ADVANCED).contains("buttonX"),"unbound advanced slot is not treated as observed input");
            screen=screen(true);mc.setScreen(screen);
            call(screen,"beginCapture",new Class<?>[]{draftClass()},new Object[]{null});
            screen.keyPressed(313,0,0);screen.keyReleased(313,0,0);
            require(field(screen,"toggleKey").equals("buttonA"),"unsupported key cannot replace engine mapping");
            screen.keyPressed(65,0,0);screen.keyReleased(65,0,0);
            require(field(screen,"toggleKey").equals("buttonB"),"supported advanced key captures its controller channel");
            call(screen,"saveAndClose",new Class<?>[0],new Object[0]);
            require(saved.controls().engineToggleKey().equals("buttonB"),"captured logical slot is the value sent to server");
        } finally { layoutField.set(profile,previous); }
    }
    private static Class<?> draftClass()throws Exception{return Class.forName(GasTurbineScreen.class.getName()+"$BehaviorDraft");}
    private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void call(Object target,String name,Class<?>[] types,Object[] args)throws Exception{var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
    private static Button button(Screen target,String key){return target.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(b->b.getMessage().getString().equals(Component.translatable(key).getString())).findFirst().orElseThrow();}
    private static void capture(Minecraft mc,String name){captured=false;Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->captured=true);}
    private static void finish(Minecraft mc,Throwable error){
        finished=true;if(error!=null)StabilizedTurret.LOGGER.error("ENGINE INPUT UI CHECK FAILED",error);
        String result=error==null?"PASS: actual advanced profile filtering/capture, redstone picker inventory slots, unbound-engine configuration, save and two GUI scales":"FAIL: "+error;
        try{Files.writeString(Path.of("engine-input-client-result.txt"),result);}catch(Exception failure){throw new RuntimeException(failure);}
        System.out.println(result);mc.stop();
    }
    private static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
}
