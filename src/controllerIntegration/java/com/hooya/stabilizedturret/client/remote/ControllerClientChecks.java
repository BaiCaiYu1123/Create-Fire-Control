package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.remote.*;
import com.hooya.stabilizedturret.registry.ModItems;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.network.WirelessControllerStatePayload;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Disposable in-game UI rendering fixture, excluded from release jars. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class ControllerClientChecks {
    private static int age, stage, wait;
    private static boolean launched;
    private static WirelessControllerScreen screen;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("create_fire_control.controllerClientChecks"))return;
        if(Boolean.getBoolean("create_fire_control.controllerKeyboardChecks"))return;
        Minecraft mc=Minecraft.getInstance();age++;
        try {
            if(age%120==0)System.out.println("CONTROLLER UI CHECK: "+(mc.screen==null?"game":mc.screen.getClass().getSimpleName())+", stage="+stage);
            if(!launched&&age>80&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen());return;
            }
            if(!launched&&mc.screen instanceof TitleScreen&&age>80) {
                launched=true;
                mc.createWorldOpenFlows().createFreshLevel("ControllerUiCheck-"+System.currentTimeMillis(),
                        new LevelSettings("Controller UI",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,
                                new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);
                return;
            }
            if(mc.player==null||mc.level==null)return;
            if(stage==0) {
                if(++wait<50)return;wait=0;
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.getToasts().clear();
                var stack=ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();ControllerSettings.ensureId(stack);
                CompoundTag data=ControllerSettings.data(stack);
                ControllerSettings.putBindings(data,List.of(
                        new ControllerSettings.Binding("key_87","Forward",new ItemStack(Items.IRON_INGOT),new ItemStack(Items.REDSTONE),true),
                        new ControllerSettings.Binding("mouse_0","Primary",new ItemStack(Items.GOLD_INGOT),ItemStack.EMPTY,true)),mc.level.registryAccess());
                data.putBoolean("WireAvailable",ControllerWireBridge.available());
                data.putUUID("EditToken",UUID.randomUUID());
                ControllerSettings.write(stack,data);mc.player.getInventory().setItem(0,stack);
                mc.player.getInventory().setItem(9,new ItemStack(Items.IRON_INGOT));
                mc.player.getInventory().setItem(10,new ItemStack(Items.REDSTONE));
                mc.player.getInventory().setItem(11,ModItems.WIRELESS_SIGNAL_PROCESSOR.get().getDefaultInstance());
                testProcessorPresentation(mc);
                testInputPriority(mc,data);testSneakRightClick(mc,data);testWheelRouting(mc,data);screen=new WirelessControllerScreen(data);mc.setScreen(screen);
                for(String axis:List.of("xpos","ypos")) {
                    Field position=MouseHandler.class.getDeclaredField(axis);position.setAccessible(true);position.setDouble(mc.mouseHandler,0);
                }
                testConfigurationRefresh(mc,data);testExitConfiguration(mc);stage=1;
            } else if(++wait>=15) {
                wait=0;
                if(stage==1) {capture(mc,"controller-main");testSignalStrength(mc);testFrequencySlots(mc);stage=2;}
                else if(stage==2) {capture(mc,"controller-editor");mc.options.guiScale().set(3);mc.resizeDisplay();stage=3;}
                else if(stage==3) {capture(mc,"controller-editor-small");
                    if(ControllerWireBridge.available()){testChannelShortcut(mc);stage=4;return;}
                    openFireControl(mc,-1);stage=5;
                } else if(stage==4) {capture(mc,"controller-channel");openFireControl(mc,-1);stage=5;}
                else if(stage==5) {capture(mc,"fire-control-inputs-no-terminal");mc.screen.mouseScrolled(mc.screen.width/2D,mc.screen.height/2D,0,-100);stage=12;}
                else if(stage==12) {capture(mc,"fire-control-inputs-other");openFrequency(mc);stage=6;}
                else if(stage==6) {capture(mc,"fire-control-frequency");openFireControl(mc,1);stage=7;}
                else if(stage==7) {capture(mc,"fire-control-inputs-advanced");openFireControl(mc,2);stage=8;}
                else if(stage==8) {capture(mc,"fire-control-inputs-wireless");openCombo(mc);stage=9;}
                else if(stage==9) {capture(mc,"controller-combo");mc.options.guiScale().set(3);mc.resizeDisplay();stage=10;}
                else if(stage==10) {capture(mc,"controller-combo-small");openEngine(mc);stage=13;}
                else if(stage==13) {capture(mc,"engine-wireless-inputs");finish(mc);stage=11;}
            }
        } catch(Throwable error) {
            StabilizedTurret.LOGGER.error("CONTROLLER CLIENT CHECK FAILED",error);
            try{Files.writeString(Path.of("controller-client-result.txt"),"FAIL: "+error);}catch(Exception ignored){}
            mc.stop();stage=5;
        }
    }
    private static void openFireControl(Minecraft mc,int kind)throws Exception {
        mc.options.guiScale().set(2);mc.resizeDisplay();
        CompoundTag state=new CompoundTag();state.putBoolean("NORMALAvailable",kind==0);
        state.putBoolean("ADVANCEDAvailable",kind==1);state.putBoolean("WIRELESSAvailable",kind==2);
        CompoundTag keys=new CompoundTag();keys.putString("14","mouse_0");keys.putString("0","key_86");state.put("WirelessRemoteKeys",keys);
        var frequencies=new net.minecraft.nbt.ListTag();
        for(int action=0;action<com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity.INPUT_ACTION_COUNT;action++) {CompoundTag entry=new CompoundTag();
            if(action==0){entry.put("First",new ItemStack(Items.IRON_INGOT).saveOptional(mc.level.registryAccess()));entry.put("Second",new ItemStack(Items.REDSTONE).saveOptional(mc.level.registryAccess()));}
            frequencies.add(entry);
        }state.put("Frequencies",frequencies);
        Class<?> payload=com.hooya.stabilizedturret.network.OpenControllerPayload.class;
        var components=payload.getRecordComponents();Class<?>[] types=new Class<?>[components.length];Object[] args=new Object[components.length];
        for(int i=0;i<components.length;i++) {types[i]=components[i].getType();String name=components[i].getName();
            args[i]=types[i]==double.class?0D:types[i]==boolean.class?false:types[i]==int.class?0:
                types[i]==BlockPos.class?mc.player.blockPosition():types[i]==java.util.UUID.class?null:
                types[i]==net.minecraft.network.chat.Component.class?net.minecraft.network.chat.Component.literal("-"):
                types[i]==java.util.List.class?(name.equals("devices")?List.of():java.util.Collections.nCopies(com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity.INPUT_ACTION_COUNT,"")):
                types[i]==CompoundTag.class?state:new com.hooya.stabilizedturret.content.bearing.YawLimits(180,180,0);
        }
        var initial=(com.hooya.stabilizedturret.network.OpenControllerPayload)payload.getDeclaredConstructor(types).newInstance(args);
        var fcc=new com.hooya.stabilizedturret.client.StabilizerControllerScreen(initial);mc.setScreen(fcc);
        Field page=fcc.getClass().getDeclaredField("page");page.setAccessible(true);
        @SuppressWarnings({"unchecked","rawtypes"}) Object inputs=Enum.valueOf((Class)page.getType(),"INPUTS");
        page.set(fcc,inputs);Method rebuild=fcc.getClass().getDeclaredMethod("rebuildPage");rebuild.setAccessible(true);rebuild.invoke(fcc);
        long buttons=fcc.children().stream().filter(child->child instanceof net.minecraft.client.gui.components.Button).count();
        int visibleActionCount=17;
        require(buttons==(kind==-1?2*visibleActionCount+3:3*visibleActionCount+3),"FCC shows frequency slots, two weapon pages and mappings for the present terminal");
        if(kind==1)testAdvancedWheelCapture(mc,fcc);
        if(kind==2) {
            var button=(net.minecraft.client.gui.components.Button)fcc.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button btn && btn.getMessage().getString().startsWith(net.minecraft.network.chat.Component.translatable("screen.create_fire_control.input.terminal_wireless").getString())).findFirst().orElseThrow();
            button.onPress();require(mc.screen==fcc,"FCC binding captures on the current page");
            fcc.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);
            String shiftPreview=com.hooya.stabilizedturret.client.remote.WirelessControllerClient.name("key_340").getString()+"+";
            require(fcc.children().stream().anyMatch(c->c instanceof net.minecraft.client.gui.components.Button btn&&btn.getMessage().getString().endsWith(shiftPreview)),"FCC displays held Shift with a trailing plus during capture");
            fcc.keyPressed(GLFW.GLFW_KEY_W,0,0);
            require(fcc.children().stream().anyMatch(c->c instanceof net.minecraft.client.gui.components.Button btn&&btn.getMessage().getString().contains(shiftPreview.substring(0,shiftPreview.length()-1))&&btn.getMessage().getString().endsWith(com.hooya.stabilizedturret.client.remote.WirelessControllerClient.name("key_87").getString())),"FCC previews both chord keys before release");
            fcc.keyReleased(GLFW.GLFW_KEY_W,0,0);fcc.keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);
            Field capturing=fcc.getClass().getDeclaredField("capturingRemoteKey");capturing.setAccessible(true);
            require(capturing.get(fcc)==null,"FCC two-key capture completes after release");
            button=(net.minecraft.client.gui.components.Button)fcc.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button btn && btn.getMessage().getString().startsWith(net.minecraft.network.chat.Component.translatable("screen.create_fire_control.input.terminal_wireless").getString())).findFirst().orElseThrow();
            button.onPress();fcc.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);require(capturing.get(fcc)==null,"FCC Esc clear completes capture");
            testWirelessWheelCapture(mc,fcc,state);
        }
    }
    private static net.minecraft.client.gui.components.Button terminalButton(com.hooya.stabilizedturret.client.StabilizerControllerScreen fcc,String terminal,int index) {
        String prefix=net.minecraft.network.chat.Component.translatable("screen.create_fire_control.input.terminal_"+terminal).getString();
        return (net.minecraft.client.gui.components.Button)fcc.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith(prefix)).toList().get(index);
    }
    private static void testWirelessWheelCapture(Minecraft mc,com.hooya.stabilizedturret.client.StabilizerControllerScreen fcc,CompoundTag state)throws Exception {
        Field capture=fcc.getClass().getDeclaredField("capturingRemoteKey");capture.setAccessible(true);
        Field chord=fcc.getClass().getDeclaredField("wirelessCapture");chord.setAccessible(true);
        for(boolean up:new boolean[]{true,false}) {
            terminalButton(fcc,"wireless",up?1:2).onPress();fcc.mouseScrolled(0,0,0,up?.05:-.05);
            require(capture.get(fcc)==null,"FCC captures either wheel direction immediately");
            require(((InputChordCapture)chord.get(fcc)).keys().equals(List.of(up?"scroll_up":"scroll_down")),"FCC records a stable direction instead of a mouse click");
            state.getCompound("WirelessRemoteKeys").putString(up?"16":"7",up?"scroll_up":"scroll_down");
            fcc.acceptInputState(new com.hooya.stabilizedturret.network.FireControlInputsStatePayload(mc.player.blockPosition(),state,true));
            require(terminalButton(fcc,"wireless",up?1:2).getMessage().getString().contains(WirelessControllerClient.name(up?"scroll_up":"scroll_down").getString()),"acknowledged wheel direction is displayed on its binding");
        }
        terminalButton(fcc,"wireless",1).onPress();fcc.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);fcc.mouseScrolled(0,0,0,1);
        require(capture.get(fcc)!=null,"wheel chord waits for held modifier release");fcc.keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);
        require(capture.get(fcc)==null&&((InputChordCapture)chord.get(fcc)).keys().equals(List.of("key_340","scroll_up")),"FCC supports modifier plus wheel capture");
        System.out.println("PASS: FCC up/down wheel capture, acknowledged labels and Shift+wheel chord; up action is directly above down");
    }
    private static void testAdvancedWheelCapture(Minecraft mc,com.hooya.stabilizedturret.client.StabilizerControllerScreen fcc)throws Exception {
        var controls=Class.forName("com.getitemfromblock.create_tweaked_controllers.controller.TweakedControlsUtil");
        Object profile=controls.getField("profile").get(null);Field layout=profile.getClass().getField("layout");Object[] original=(Object[])layout.get(profile),copy=original.clone();
        var wheel=Class.forName("com.getitemfromblock.create_tweaked_controllers.input.MouseWheelInput").getConstructor(float.class,float.class);
        copy[0]=wheel.newInstance(0F,1F);copy[1]=wheel.newInstance(0F,-1F);layout.set(profile,copy);
        try {
            var type=com.hooya.stabilizedturret.content.controller.RemoteControllerCompat.Type.ADVANCED;
            String[] signals=com.hooya.stabilizedturret.content.controller.DriveByWireCompat.keys(type);
            Field capture=fcc.getClass().getDeclaredField("capturingRemoteKey");capture.setAccessible(true);
            for(boolean up:new boolean[]{true,false}) {
                var matched=com.hooya.stabilizedturret.client.ControllerKeyMappingCompat.signalsForMouseScroll(type,up?.05:-.05);
                require(matched.contains(signals[up?0:1])&&!matched.contains(signals[up?1:0]),"real advanced wheel profile resolves both high-resolution directions");
                terminalButton(fcc,"advanced",up?1:2).onPress();fcc.mouseScrolled(0,0,0,up?.05:-.05);
                require(capture.get(fcc)==null,"advanced FCC wheel capture completes");
            }
        } finally {layout.set(profile,original);}
        System.out.println("PASS: real advanced-controller wheel profile, both directions and high-resolution FCC binding capture");
    }
    private static void testWheelRouting(Minecraft mc,CompoundTag original)throws Exception {
        Field focus=Minecraft.class.getDeclaredField("windowActive");focus.setAccessible(true);boolean wasActive=focus.getBoolean(mc);focus.setBoolean(mc,true);
        try {
            mc.setScreen(null);CompoundTag data=original.copy();data.putUUID("Session",UUID.randomUUID());
            data.put("Effective",ControllerSessions.strings(Set.of("scroll_up","scroll_down")));data.put("EffectiveOutputs",ControllerSessions.strings(Set.of("scroll_up","scroll_down")));
            ControllerSettings.putBindings(data,List.of(),mc.level.registryAccess());
            WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.ACTIVE,data));
            Method scroll=MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);scroll.setAccessible(true);
            int slot=mc.player.getInventory().selected;
            for(double delta:new double[]{3,-2,.05,-.05}) {
                scroll.invoke(mc.mouseHandler,mc.getWindow().getWindow(),0D,delta);
                require(WirelessControllerClient.active()&&mc.player.getInventory().selected==slot,"bound native wheel scroll remains owned by the remote");
            }
            require(!WirelessControllerClient.scroll(mc.getWindow().getWindow(),Double.NaN),"nonfinite scroll ignored");
        } finally {WirelessControllerClient.stop(false);focus.setBoolean(mc,wasActive);}
        System.out.println("PASS: transformed wheel handler consumes both directions and keeps hotbar/session stable");
    }
    private static void openFrequency(Minecraft mc)throws Exception {
        var parent=(com.hooya.stabilizedturret.client.StabilizerControllerScreen)mc.screen;
        var frequency=new FireControlFrequencyScreen(parent,mc.player.blockPosition(),0,new ItemStack(Items.IRON_INGOT),new ItemStack(Items.REDSTONE));mc.setScreen(frequency);
        int count=mc.player.getInventory().getItem(9).getCount();
        Field ix=frequency.getClass().getDeclaredField("inventoryX"),iy=frequency.getClass().getDeclaredField("inventoryY"),fx=frequency.getClass().getDeclaredField("frequencyX"),fy=frequency.getClass().getDeclaredField("frequencyY");
        for(Field field:List.of(ix,iy,fx,fy))field.setAccessible(true);
        frequency.mouseClicked(ix.getInt(frequency)+2,iy.getInt(frequency)+2,0);frequency.mouseClicked(fx.getInt(frequency)+2,fy.getInt(frequency)+2,0);
        require(mc.player.getInventory().getItem(9).getCount()==count,"FCC frequency editing does not consume inventory");
    }
    private static void openCombo(Minecraft mc)throws Exception {
        CompoundTag data=ControllerSettings.data(mc.player.getMainHandItem());
        data.putUUID("Id",UUID.randomUUID());data.putUUID("EditToken",UUID.randomUUID());
        ControllerSettings.putBindings(data,List.of(new ControllerSettings.Binding("key_87","Forward",new ItemStack(Items.IRON_INGOT),new ItemStack(Items.REDSTONE),true,"mouse_3")),mc.level.registryAccess());
        screen=new WirelessControllerScreen(data);mc.setScreen(screen);
        Method edit=WirelessControllerScreen.class.getDeclaredMethod("openEditor",String.class);edit.setAccessible(true);edit.invoke(screen,"key_87");
        Method begin=WirelessControllerScreen.class.getDeclaredMethod("beginRecord");begin.setAccessible(true);begin.invoke(screen);
        screen.keyPressed(GLFW.GLFW_KEY_LEFT_CONTROL,0,0);screen.keyPressed(GLFW.GLFW_KEY_W,0,0);
        screen.keyReleased(GLFW.GLFW_KEY_W,0,0);screen.keyReleased(GLFW.GLFW_KEY_LEFT_CONTROL,0,0);
        Field editing=WirelessControllerScreen.class.getDeclaredField("editing"),second=WirelessControllerScreen.class.getDeclaredField("secondInput");editing.setAccessible(true);second.setAccessible(true);
        require(editing.get(screen).equals("key_341")&&second.get(screen).equals("key_87"),"new controller records two physical keys after all releases");
        begin.invoke(screen);screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        require(editing.get(screen).equals("")&&second.get(screen).equals(""),"Esc clears the controller key assignment");
        begin.invoke(screen);screen.keyPressed(GLFW.GLFW_KEY_W,0,0);screen.keyReleased(GLFW.GLFW_KEY_W,0,0);
        require(editing.get(screen).equals("key_87")&&second.get(screen).equals(""),"single-key capture remains supported");
        begin.invoke(screen);screen.keyPressed(GLFW.GLFW_KEY_LEFT_CONTROL,0,0);screen.keyPressed(GLFW.GLFW_KEY_W,0,0);screen.keyReleased(GLFW.GLFW_KEY_W,0,0);screen.keyReleased(GLFW.GLFW_KEY_LEFT_CONTROL,0,0);
        System.out.println("PASS: direct one/two-key capture and Esc clear; mouse left/right always owned while active");
    }
    private static void openEngine(Minecraft mc)throws Exception {
        mc.options.guiScale().set(2);mc.resizeDisplay();
        var state=new com.hooya.stabilizedturret.network.OpenGasTurbinePayload(mc.player.blockPosition(),true,false,
            com.hooya.stabilizedturret.content.turbine.EngineControlSettings.defaults(),true);
        var screen=new com.hooya.stabilizedturret.client.GasTurbineScreen(state);mc.setScreen(screen);
        Field toggle=screen.getClass().getDeclaredField("toggleButton");toggle.setAccessible(true);
        ((net.minecraft.client.gui.components.Button)toggle.get(screen)).onPress();
        screen.keyPressed(GLFW.GLFW_KEY_LEFT_CONTROL,0,0);screen.keyPressed(GLFW.GLFW_KEY_W,0,0);
        screen.keyReleased(GLFW.GLFW_KEY_W,0,0);screen.keyReleased(GLFW.GLFW_KEY_LEFT_CONTROL,0,0);
        Field key=screen.getClass().getDeclaredField("toggleKey");key.setAccessible(true);
        require(key.get(screen).equals("key_341+key_87"),"new engine terminal records a two-key ignition chord");
        ((net.minecraft.client.gui.components.Button)toggle.get(screen)).onPress();screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        require(key.get(screen).equals(""),"Esc clears engine ignition input");
        Method begin=screen.getClass().getDeclaredMethod("beginCapture",Class.forName("com.hooya.stabilizedturret.client.GasTurbineScreen$BehaviorDraft"));begin.setAccessible(true);
        Field behaviors=screen.getClass().getDeclaredField("behaviors");behaviors.setAccessible(true);Object behavior=((List<?>)behaviors.get(screen)).getFirst();
        begin.invoke(screen,behavior);screen.keyPressed(GLFW.GLFW_KEY_A,0,0);screen.keyPressed(GLFW.GLFW_KEY_D,0,0);
        screen.keyReleased(GLFW.GLFW_KEY_D,0,0);screen.keyReleased(GLFW.GLFW_KEY_A,0,0);
        Field keys=behavior.getClass().getDeclaredField("keys");keys.setAccessible(true);
        require(keys.get(behavior).equals(List.of("key_65","key_68")),"new engine terminal records a two-key drive behavior");
        begin.invoke(screen,behavior);screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);require(((List<?>)keys.get(behavior)).isEmpty(),"Esc clears engine drive behavior");
        begin.invoke(screen,behavior);screen.keyPressed(GLFW.GLFW_KEY_W,0,0);screen.keyPressed(GLFW.GLFW_KEY_A,0,0);screen.keyReleased(GLFW.GLFW_KEY_A,0,0);screen.keyReleased(GLFW.GLFW_KEY_W,0,0);
        System.out.println("PASS: direct engine ignition/behavior capture and Esc clear");
    }
    private static void finish(Minecraft mc)throws Exception {
                    Files.writeString(Path.of("controller-client-result.txt"),"PASS");mc.stop();stage=11;}
    private static void testInputPriority(Minecraft mc,CompoundTag data)throws Exception {
        Field focus=Minecraft.class.getDeclaredField("windowActive");focus.setAccessible(true);boolean wasActive=focus.getBoolean(mc);focus.setBoolean(mc,true);
        try {
            mc.setScreen(null);data.putUUID("Session",UUID.randomUUID());data.put("Effective",ControllerSessions.strings(Set.of("key_87")));
            WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.ACTIVE,data));
            require(WirelessControllerClient.active(),"client accepts the session");
            long window=mc.getWindow().getWindow();
            mc.keyboardHandler.keyPress(window,87,0,GLFW.GLFW_PRESS,0);
            require(!mc.options.keyUp.isDown(),"owned W cannot move player");
            mc.keyboardHandler.keyPress(window,69,0,GLFW.GLFW_PRESS,0);
            require(mc.options.keyInventory.consumeClick(),"unconfigured E passes to vanilla");
            Method mouse=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);mouse.setAccessible(true);
            mouse.invoke(mc.mouseHandler,window,0,GLFW.GLFW_PRESS,0);
            require(!mc.options.keyAttack.consumeClick(),"default left button cannot attack even without a configured use");
            mouse.invoke(mc.mouseHandler,window,1,GLFW.GLFW_PRESS,0);
            require(!mc.options.keyUse.consumeClick()&&!mc.options.keyUse.isDown(),"default right button cannot use items even without a configured use");
            require(WirelessControllerClient.blocks(true,0)&&WirelessControllerClient.blocks(true,1),"both main mouse buttons always belong to active controller");
            mouse.invoke(mc.mouseHandler,window,0,GLFW.GLFW_RELEASE,0);mouse.invoke(mc.mouseHandler,window,1,GLFW.GLFW_RELEASE,0);
            mc.keyboardHandler.keyPress(window,258,0,GLFW.GLFW_PRESS,0);
            require(!WirelessControllerClient.active(),"Tab exits before vanilla key handling");
            mc.keyboardHandler.keyPress(window,258,0,GLFW.GLFW_RELEASE,0);
            data.putString("ExitChannel","key_69");data.put("Effective",ControllerSessions.strings(Set.of("key_87","key_258","key_69")));
            WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.ACTIVE,data));
            mc.keyboardHandler.keyPress(window,258,0,GLFW.GLFW_PRESS,0);
            require(WirelessControllerClient.active(),"Tab becomes a control input after choosing another exit");
            Field sent=WirelessControllerClient.class.getDeclaredField("sent");sent.setAccessible(true);
            require(((Set<?>)sent.get(null)).contains("key_258"),"the former exit reaches controller output");
            mc.keyboardHandler.keyPress(window,69,0,GLFW.GLFW_PRESS,0);
            require(!WirelessControllerClient.active()&&!mc.options.keyInventory.consumeClick(),"custom exit wins over conflicting vanilla inventory binding");
            require(WirelessControllerClient.blocks(false,69),"exit stays consumed until release");
            mc.keyboardHandler.keyPress(window,69,0,GLFW.GLFW_RELEASE,0);
            require(!WirelessControllerClient.blocks(false,69),"released exit returns to the game");
            mc.keyboardHandler.keyPress(window,258,0,GLFW.GLFW_RELEASE,0);
            data.putString("ExitChannel","mouse_0");data.put("Effective",ControllerSessions.strings(Set.of("key_87","mouse_0")));
            WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.ACTIVE,data));
            mouse.invoke(mc.mouseHandler,window,0,GLFW.GLFW_PRESS,0);
            require(!WirelessControllerClient.active()&&!mc.options.keyAttack.consumeClick(),"mouse exit wins over attack and controller output");
            mouse.invoke(mc.mouseHandler,window,0,GLFW.GLFW_RELEASE,0);data.remove("ExitChannel");
            System.out.println("PASS: keyboard/mouse ownership, configurable exit priority, freed Tab and exit release barrier");
        } finally {WirelessControllerClient.stop(false);focus.setBoolean(mc,wasActive);}
    }
    private static void testSneakRightClick(Minecraft mc,CompoundTag original)throws Exception {
        Field focus=Minecraft.class.getDeclaredField("windowActive");focus.setAccessible(true);boolean wasActive=focus.getBoolean(mc);focus.setBoolean(mc,true);
        var originalShift=mc.options.keyShift.getKey();
        Method mouse=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);mouse.setAccessible(true);
        Field sent=WirelessControllerClient.class.getDeclaredField("sent");sent.setAccessible(true);
        long window=mc.getWindow().getWindow();
        try {
            mc.setScreen(null);mc.options.keyShift.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_SHIFT));KeyMapping.resetMapping();
            for(boolean mounted:new boolean[]{false,true})for(boolean bound:new boolean[]{false,true}) {
                CompoundTag data=original.copy();data.remove("ExitChannel");data.remove("Lectern");data.putUUID("Session",UUID.randomUUID());
                ControllerSettings.putBindings(data,bound?List.of(new ControllerSettings.Binding("mouse_1","Sneak combo",ItemStack.EMPTY,ItemStack.EMPTY,true,"key_340",7)):List.of(),mc.level.registryAccess());
                data.put("Effective",ControllerSessions.strings(bound?Set.of("key_340","mouse_1"):Set.of()));
                data.put("EffectiveOutputs",ControllerSessions.strings(bound?Set.of("mouse_1"):Set.of()));
                if(mounted)data.put("Lectern",com.hooya.stabilizedturret.content.controller.BindingRef.capture(mc.level,mc.player.blockPosition()).save());
                WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.ACTIVE,data));
                mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_LEFT_SHIFT,0,GLFW.GLFW_PRESS,0);mc.player.setShiftKeyDown(true);
                mouse.invoke(mc.mouseHandler,window,GLFW.GLFW_MOUSE_BUTTON_RIGHT,GLFW.GLFW_PRESS,GLFW.GLFW_MOD_SHIFT);
                require(WirelessControllerClient.active()&&mc.screen==null,"Shift+right-click keeps handheld and lectern control active with or without a binding");
                require(!mc.options.keyUse.consumeClick()&&!mc.options.keyUse.isDown(),"Shift+right-click never reaches vanilla item use");
                @SuppressWarnings("unchecked") Set<String> pressed=(Set<String>)sent.get(null);
                require(pressed.contains("mouse_1")&&(!bound||pressed.contains("key_340")),"right-click and configured Shift reach controller input");
                require(ControllerChords.outputs(pressed,Set.of("mouse_1"),ControllerSettings.chords(data),ControllerInput.DEFAULT_EXIT).contains("mouse_1"),"Shift+right-click remains a usable controller chord");
                mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_LEFT_SHIFT,0,GLFW.GLFW_RELEASE,0);mc.player.setShiftKeyDown(false);
                if(bound) {
                    @SuppressWarnings("unchecked") Set<String> released=(Set<String>)sent.get(null);
                    require(!ControllerChords.outputs(released,Set.of("mouse_1"),ControllerSettings.chords(data),ControllerInput.DEFAULT_EXIT).contains("mouse_1"),"releasing Shift clears its chord without ending control");
                }
                mouse.invoke(mc.mouseHandler,window,GLFW.GLFW_MOUSE_BUTTON_RIGHT,GLFW.GLFW_RELEASE,0);
                require(WirelessControllerClient.active()&&mc.screen==null,"releasing Shift+right-click leaves control active");
                mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_TAB,0,GLFW.GLFW_PRESS,0);
                require(!WirelessControllerClient.active(),"configured exit still ends control");
                mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_TAB,0,GLFW.GLFW_RELEASE,0);
            }
            System.out.println("PASS: transformed Shift+right mouse input keeps handheld/lectern sessions active, consumes vanilla use, routes chords and releases normally");
        } finally {WirelessControllerClient.stop(false);mc.player.setShiftKeyDown(false);mc.options.keyShift.setKey(originalShift);KeyMapping.resetMapping();focus.setBoolean(mc,wasActive);}
    }
    private static void testChannelShortcut(Minecraft mc)throws Exception {
        mc.setScreen(null);
        BlockPos source=mc.player.blockPosition().above(2);
        mc.level.setBlock(source,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState(),3);
        mc.player.getInventory().setItem(0,BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("drivebywire","wire")).getDefaultInstance());
        Field selected=Class.forName("edn.stratodonut.drivebywire.client.ClientWireNetworkHandler").getDeclaredField("selectedSource");selected.setAccessible(true);selected.set(null,source);
        ControllerWireClient.select("key_87");
        var original=mc.options.keyInventory.getKey();
        try {
            mc.options.keyInventory.setKey(InputConstants.Type.KEYSYM.getOrCreate(72));KeyMapping.resetMapping();
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(),72,0,GLFW.GLFW_PRESS,0);
            require(mc.screen instanceof ControllerChannelScreen,"H opens the input picker");
            require(!mc.options.keyInventory.consumeClick(),"H consumes a conflicting vanilla binding");
            require(ControllerWireClient.current().equals("key_87"),"opening H does not select H");
            System.out.println("PASS: transformed H shortcut, conflict priority and unchanged channel until confirmation");
        } finally {mc.options.keyInventory.setKey(original);KeyMapping.resetMapping();}
    }
    private static void testConfigurationRefresh(Minecraft mc,CompoundTag data)throws Exception {
        Method edit=WirelessControllerScreen.class.getDeclaredMethod("openEditor",String.class);edit.setAccessible(true);
        edit.invoke(screen,"key_87");
        Field labelField=WirelessControllerScreen.class.getDeclaredField("label");labelField.setAccessible(true);
        ((net.minecraft.client.gui.components.EditBox)labelField.get(screen)).setValue("Unsaved purpose");
        String save=net.minecraft.network.chat.Component.translatable("remote.create_fire_control.save_input").getString();
        for(var child:List.copyOf(screen.children()))if(child instanceof net.minecraft.client.gui.components.Button button
                &&button.getMessage().getString().equals(save))button.onPress();
        WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.CONFIG,data));
        require(mc.screen==screen,"pairing response keeps the current editor");
        Method draft=WirelessControllerScreen.class.getDeclaredMethod("draft");draft.setAccessible(true);
        require(ControllerSettings.bindings((CompoundTag)draft.invoke(screen),mc.level.registryAccess()).stream()
                .anyMatch(binding->binding.channel().equals("key_87")&&binding.label().equals("Unsaved purpose")),
                "pairing response preserves unsaved frequency edits");
        CompoundTag reopened=data.copy();reopened.putUUID("EditToken",UUID.randomUUID());
        WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.CONFIG,reopened));
        require(mc.screen!=screen,"a new edit session loads authoritative settings");
        screen=(WirelessControllerScreen)mc.screen;
        System.out.println("PASS: pairing updates preserve unsaved edits and fresh sessions load saved settings");
    }
    private static void testProcessorPresentation(Minecraft mc) {
        var texture=ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"block/wireless_signal_processor");
        var model=mc.getBlockRenderer().getBlockModel(ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        require(model.getParticleIcon().contents().name().equals(texture),"processor loads its bundled texture without wire assets");
        require(model.getQuads(ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState(),null,
                net.minecraft.util.RandomSource.create()).size()==30,"processor preserves all six faces of the original five elements");
        var own=ModItems.WIRELESS_SIGNAL_PROCESSOR.get().getDefaultInstance();
        require(mc.getItemRenderer().getModel(own,mc.level,mc.player,0).getParticleIcon().contents().name().equals(texture),
                "processor inventory model uses the bundled texture");
        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),true,mc.level.registryAccess());
        require(com.hooya.stabilizedturret.registry.ModCreativeTabs.MAIN.get().getDisplayItems().stream().anyMatch(stack->stack.is(own.getItem())),
                "processor is available in the fire-control tab");
        require(BuiltInRegistries.CREATIVE_MODE_TAB.stream().filter(tab->BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).getNamespace().equals("drivebywire"))
                .noneMatch(tab->tab.getDisplayItems().stream().anyMatch(stack->stack.is(own.getItem()))),"processor is not inserted into the optional wire tab");
        System.out.println("PASS: bundled original hub model/texture, independent block/item rendering and native tab placement");
    }
    private static void testExitConfiguration(Minecraft mc)throws Exception {
        Field inputField=WirelessControllerScreen.class.getDeclaredField("inputs");inputField.setAccessible(true);
        ControllerInputPanel panel=(ControllerInputPanel)inputField.get(screen);
        int px=screen.left+10,py=screen.top+50;
        require(panel.hit(px+8,py+2.3*18+8)==null,"default Tab is disabled in the diagram");
        Field buttonField=WirelessControllerScreen.class.getDeclaredField("exitButton");buttonField.setAccessible(true);
        var exit=(net.minecraft.client.gui.components.Button)buttonField.get(screen);exit.onPress();
        screen.keyPressed(GLFW.GLFW_KEY_W,0,0);
        Method draft=WirelessControllerScreen.class.getDeclaredMethod("draft");draft.setAccessible(true);
        CompoundTag edited=(CompoundTag)draft.invoke(screen);
        require(ControllerSettings.exitChannel(edited).equals("key_87"),"captured exit is included in the save draft");
        require(panel.hit(px+2*18+8,py+2.3*18+8)==null,"new exit disables an existing W binding");
        require("key_258".equals(panel.hit(px+8,py+2.3*18+8)),"old Tab is editable again");
        edited.remove("ExitChannel");
        WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.CONFIG,edited));
        require(ControllerSettings.exitChannel((CompoundTag)draft.invoke(screen)).equals("key_87"),"server refresh preserves the unsaved exit draft");
        exit=(net.minecraft.client.gui.components.Button)buttonField.get(screen);exit.onPress();
        int mouseX=screen.left+10+Math.round(19.45F*18),mouseY=screen.top+50+Math.round(1.5F*18);
        screen.mouseClicked(mouseX,mouseY,0);
        require(ControllerSettings.exitChannel((CompoundTag)draft.invoke(screen)).equals("mouse_0"),"mouse diagram can select the exit");
        require(panel.hit(mouseX,mouseY)==null,"mouse exit is disabled in the diagram");
        exit.onPress();screen.keyPressed(GLFW.GLFW_KEY_TAB,0,0);
        require(ControllerSettings.exitChannel((CompoundTag)draft.invoke(screen)).equals(ControllerInput.DEFAULT_EXIT),"Tab can be restored");
        require(screen.children().stream().noneMatch(child->child instanceof net.minecraft.client.gui.components.Button button
                &&button.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("remote.create_fire_control.unpair").getString())),"unpair control is removed");
        System.out.println("PASS: exit capture, disabled diagram inputs, reusable Tab and draft refresh");
    }
    private static net.minecraft.client.gui.components.EditBox strengthBox() {
        String name=net.minecraft.network.chat.Component.translatable("remote.create_fire_control.signal_strength").getString();
        return (net.minecraft.client.gui.components.EditBox)screen.children().stream()
                .filter(child->child instanceof net.minecraft.client.gui.components.EditBox box&&box.getMessage().getString().equals(name)).findFirst().orElseThrow();
    }
    private static void saveInput() {
        String name=net.minecraft.network.chat.Component.translatable("remote.create_fire_control.save_input").getString();
        ((net.minecraft.client.gui.components.Button)screen.children().stream().filter(child->child instanceof net.minecraft.client.gui.components.Button button
                &&button.getMessage().getString().equals(name)).findFirst().orElseThrow()).onPress();
    }
    private static void testSignalStrength(Minecraft mc)throws Exception {
        Method edit=WirelessControllerScreen.class.getDeclaredMethod("openEditor",String.class);edit.setAccessible(true);
        Method draft=WirelessControllerScreen.class.getDeclaredMethod("draft");draft.setAccessible(true);
        edit.invoke(screen,"key_65");require(strengthBox().getValue().equals("16"),"new key strength defaults to 16");
        var box=strengthBox();box.setValue("7");box.setValue("17");require(box.getValue().equals("7"),"strength above 16 is rejected");
        box.setValue("-1");box.setValue("a");require(box.getValue().equals("7"),"negative and nonnumeric strength rejected");
        box.setValue("");saveInput();edit.invoke(screen,"key_65");require(strengthBox().getValue().equals("16"),"empty strength uses default 16");
        strengthBox().setValue("7");screen.resize(mc,screen.width,screen.height);require(strengthBox().getValue().equals("7"),"resize keeps strength draft");
        Field state=WirelessControllerScreen.class.getDeclaredField("state");state.setAccessible(true);
        require(screen.refreshConfig((CompoundTag)state.get(screen))&&strengthBox().getValue().equals("7"),"pairing refresh preserves unsaved strength");
        saveInput();
        require(ControllerSettings.bindings((CompoundTag)draft.invoke(screen),mc.level.registryAccess()).stream()
                .anyMatch(binding->binding.channel().equals("key_65")&&binding.signalStrength()==7&&binding.first().isEmpty()&&binding.second().isEmpty()),"strength-only key survives save without frequency or label");
        edit.invoke(screen,"key_65");require(strengthBox().getValue().equals("7"),"reopened strength-only key retains value");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);edit.invoke(screen,"key_87");
        require(strengthBox().getValue().equals("16"),"old key defaults to 16");strengthBox().setValue("7");
        Method begin=WirelessControllerScreen.class.getDeclaredMethod("beginRecord");begin.setAccessible(true);begin.invoke(screen);
        screen.keyPressed(GLFW.GLFW_KEY_W,0,0);screen.keyReleased(GLFW.GLFW_KEY_W,0,0);
        require(strengthBox().getValue().equals("7"),"recording a key preserves unsaved strength");
        saveInput();edit.invoke(screen,"key_87");require(strengthBox().getValue().equals("7"),"wireless key strength saves and reopens");
        System.out.println("PASS: strength input defaults, validation, blank fallback, refresh/resize/capture draft retention and frequency-free wire-only save");
    }
    private static void testFrequencySlots(Minecraft mc)throws Exception {
        int ix=intField("inventoryX"),iy=intField("inventoryY"),fx=intField("frequencyX"),fy=intField("frequencyY");
        int originalCount=mc.player.getInventory().getItem(9).getCount();
        screen.mouseClicked(ix+2,iy+2,0);screen.mouseClicked(fx+2,fy+2,0);
        Field first=WirelessControllerScreen.class.getDeclaredField("first");first.setAccessible(true);
        require(((ItemStack)first.get(screen)).is(Items.IRON_INGOT),"standard inventory position sets the ghost frequency");
        require(mc.player.getInventory().getItem(9).getCount()==originalCount,"frequency selection never consumes inventory items");
        screen.mouseClicked(fx+2,fy+2,1);require(((ItemStack)first.get(screen)).isEmpty(),"right-click clears the ghost slot");
        screen.mouseClicked(ix+2,iy+2,0);screen.mouseClicked(fx+2,fy+2,0);
        System.out.println("PASS: compact inventory click positions, ghost selection and clear without item consumption");
    }
    private static int intField(String name)throws Exception {
        Field field=WirelessControllerScreen.class.getDeclaredField(name);field.setAccessible(true);return field.getInt(screen);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void capture(Minecraft mc,String name)throws Exception {
        Path directory=Path.of("controller-ui");Files.createDirectories(directory);
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(directory.resolve(name+".png"));}
    }
}
