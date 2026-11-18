package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.*;
import com.hooya.stabilizedturret.network.*;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Runs against transformed Create, real optional Drive By Wire and loaded registries. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID)
public final class ControllerIntegration {
    private static final String KEY="key_87", MOUSE="mouse_3";
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if(!event.getServer().isDedicatedServer())return;
        if (Boolean.getBoolean("create_fire_control.engineInputChecks")) {
            EngineInputIntegration.start(event.getServer());
            return;
        }
        if (Boolean.getBoolean("create_fire_control.controllerTransferChecks")) {
            ControllerTransferIntegration.start(event.getServer());
            return;
        }
        if (Boolean.getBoolean("create_fire_control.keyboardNativeChecks")) {
            KeyboardNativeIntegration.start(event.getServer());
            return;
        }
        if (Boolean.getBoolean("create_fire_control.remoteMappingProbe")) {
            SableRemoteMappingIntegration.start(event.getServer());
            return;
        }
        Throwable failed=null;
        try { run(event.getServer().overworld()); }
        catch(Throwable error){failed=error;StabilizedTurret.LOGGER.error("CONTROLLER INTEGRATION FAILED",error);}
        finally {
            try {Files.writeString(Path.of("controller-integration-result.txt"),failed==null?"PASS":"FAIL: "+failed);}
            catch(Exception error){throw new RuntimeException(error);}
            event.getServer().halt(false);
        }
    }
    private static void run(ServerLevel level) throws Exception {
        level.setChunkForced(0,0,true);
        if (Boolean.getBoolean("create_fire_control.nativeInputChecks")) { NativeInputFallbackIntegration.run(level);return; }
        if (Boolean.getBoolean("create_fire_control.controllerApiOnly")) { ControllerApiIntegration.run(level);return; }
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"ControllerCheck"));
        player.setPos(4,240,4);
        ItemStack controller=ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND,controller);ControllerSettings.ensureId(controller);
        CompoundTag data=ControllerSettings.data(controller);
        ControllerSettings.putBindings(data,List.of(new ControllerSettings.Binding(KEY,"Forward",new ItemStack(Items.IRON_INGOT),ItemStack.EMPTY,true)),level.registryAccess());
        ControllerSettings.write(controller,data);
        player.setShiftKeyDown(true);
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
        require(session(player)==null&&editors().containsKey(player.getUUID()),"idle sneak-use still opens controller configuration");
        player.setShiftKeyDown(false);
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
        Object session=session(player); require(session!=null,"server session starts without a wire dependency");
        require(field(session,"effective").equals(Set.of(KEY)),"only configured input is active");
        input(player,session,Set.of(KEY,MOUSE,"bad"));
        var frequency=Couple.create(Frequency.of(new ItemStack(Items.IRON_INGOT)),Frequency.EMPTY);
        require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"real Create wireless transmitter powers its frequency");
        sneakUseWhileControlling(level,player,controller,session,frequency);
        input(player,session,Set.of());require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"wireless release is immediate");
        if(ControllerWireBridge.available()) wire(level,player,controller);
        else require(ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get()!=null,"core processor class loads without optional interfaces");
        session=session(player);
        if(session!=null)stop(player,session);
        require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"stop clears wireless output");
        customExit(level,player,controller);
        colors(player,controller);
        ControllerStrengthIntegration.run(level);
        ControllerWheelIntegration.run(level);
        chords(level,player,controller);
        fireControl(level,player,controller);EngineTerminalIntegration.run(level,player,controller);
        LecternControllerIntegration.run(level);
        ControllerApiIntegration.run(level);
        System.out.println("PASS: controller server, Create wireless, live input filtering, cleanup; wire="+ControllerWireBridge.available());
    }
    private static Map<?,?> editors()throws Exception {
        Field field=ControllerSessions.class.getDeclaredField("EDITORS");field.setAccessible(true);return (Map<?,?>)field.get(null);
    }
    private static void sneakUseWhileControlling(ServerLevel level,ServerPlayer player,ItemStack controller,Object active,
                                               Couple<Frequency> frequency)throws Exception {
        CompoundTag before=ControllerSettings.data(controller);
        BlockPos pos=new BlockPos(6,240,4);
        level.setBlockAndUpdate(pos,Blocks.LECTERN.defaultBlockState());
        player.setShiftKeyDown(true);
        try {
            ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
            require(session(player)==active&&!editors().containsKey(player.getUUID()),"active sneak-use keeps its session and does not open configuration");
            ControllerSessions.use(player,InteractionHand.MAIN_HAND,pos);
            require(level.getBlockState(pos).is(Blocks.LECTERN)&&player.getMainHandItem()==controller,"active sneak-use cannot place or consume controller on a lectern");
            var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),Direction.UP,pos,false);
            var event=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,hit);
            ControllerSessions.rightClickBlock(event);
            require(event.isCanceled()&&session(player)==active,"active right-click block event is consumed without interrupting control");
            player.setItemInHand(InteractionHand.OFF_HAND,ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance());
            ControllerSessions.use(player,InteractionHand.OFF_HAND,null);
            require(session(player)==active,"offhand sneak-use cannot replace an active controller");
            for(boolean offhand:new boolean[]{false,true}) {
                var held=player.getItemInHand(offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);ControllerSettings.ensureId(held);
                CompoundTag request=ControllerSettings.data(held);request.putBoolean("Offhand",offhand);
                ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.CONFIG,request));
                require(session(player)==active&&!editors().containsKey(player.getUUID()),"configuration packet cannot interrupt active control in either hand");
            }
            require(ControllerSettings.data(controller).equals(before)&&Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"sneak-use preserves configuration and held output");
        } finally {player.setShiftKeyDown(false);player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);level.removeBlock(pos,false);}
        System.out.println("PASS: active Shift+right-use, lectern placement, block event and both-hand configuration packets preserve session and held output; idle configuration remains available");
    }
    private static void chords(ServerLevel level,ServerPlayer player,ItemStack controller)throws Exception {
        CompoundTag data=ControllerSettings.data(controller);data.remove("Hub");data.remove("Processor");
        ControllerSettings.putBindings(data,List.of(new ControllerSettings.Binding(KEY,"Combo",new ItemStack(Items.IRON_INGOT),ItemStack.EMPTY,true,MOUSE)),level.registryAccess());
        ControllerSettings.write(controller,data);
        require(ControllerSettings.bindings(data,level.registryAccess()).getFirst().secondInput().equals(MOUSE),"two-input binding round trips through item data");
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);Object session=session(player);
        var frequency=Couple.create(Frequency.of(new ItemStack(Items.IRON_INGOT)),Frequency.EMPTY);
        require(field(session,"effective").equals(Set.of(KEY,MOUSE)),"combo claims exactly its two inputs");
        input(player,session,Set.of(KEY));require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"primary alone has no redstone output");
        input(player,session,Set.of(KEY,MOUSE));require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"complete combo powers real redstone");
        input(player,session,Set.of(MOUSE));require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"releasing primary clears combo output");
        input(player,session,Set.of(KEY,MOUSE));require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"reverse press order works");
        input(player,session,Set.of(KEY));require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"releasing secondary clears combo output");
        stop(player,session);System.out.println("PASS: persisted two-input chord, both orders and either release using real Create wireless");
    }
    private static void fireControl(ServerLevel level,ServerPlayer player,ItemStack controller)throws Exception {
        var type=com.hooya.stabilizedturret.content.controller.RemoteControllerCompat.Type.WIRELESS;
        BlockPos hub=new BlockPos(14,240,4),computer=hub.west();
        level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(computer,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var fcc=(com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity)level.getBlockEntity(computer);
        com.hooya.stabilizedturret.content.controller.ControllerTracker.add(fcc);
        fcc.bind(com.hooya.stabilizedturret.content.controller.BindingSlot.WIRELESS_CONTROLLER_HUB,
                com.hooya.stabilizedturret.content.controller.BindingRef.capture(level,hub));
        require(fcc.setRemoteKey(type,14,KEY),"new terminal accepts direct physical key mapping");
        require(!fcc.setRemoteKey(type,14,"bad"),"invalid physical mapping rejected");
        require(ControllerActionRegistry.channels(level,hub).equals(Set.of(KEY)),"fire-control mapping is a live action use without a wire");
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,hub);
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);Object session=session(player);
        require(com.hooya.stabilizedturret.content.controller.RemoteControlTracker.findInputController(player)==fcc,"new controller qualifies as fire-control operator");
        input(player,session,Set.of(KEY));invoke(fcc,"tickRemoteInputs");
        require(!((boolean[])field(fcc,"remoteInputPressed"))[14],"fire-control action waits for the complete combo");
        input(player,session,Set.of(KEY,MOUSE));invoke(fcc,"tickRemoteInputs");
        require(((boolean[])field(fcc,"remoteInputPressed"))[14],"combo reaches the fire-control machine-gun hold input");
        require(KeyboardControllerInputs.signals(level,hub).get(KEY)==16,"direct fire-control mapping uses the native input path");
        input(player,session,Set.of(KEY));invoke(fcc,"tickRemoteInputs");
        require(!((boolean[])field(fcc,"remoteInputPressed"))[14],"release clears fire-control input");
        fcc.setRemoteKey(type,14,"");invoke(session,"refresh");
        require(field(session,"actions").equals(Set.of()),"removing a fire-control mapping removes the action use");
        stop(player,session);require(com.hooya.stabilizedturret.content.controller.RemoteControlTracker.findInputController(player)==null,"stop clears new fire-control operator");
        System.out.println("PASS: direct processor mapping, operator qualification, chord-driven FCC input and cleanup");
    }
    private static void customExit(ServerLevel level,ServerPlayer player,ItemStack controller)throws Exception {
        CompoundTag data=ControllerSettings.data(controller);
        ControllerSettings.putBindings(data,List.of(
                new ControllerSettings.Binding(KEY,"Reserved",new ItemStack(Items.IRON_INGOT),ItemStack.EMPTY,true),
                new ControllerSettings.Binding(ControllerInput.DEFAULT_EXIT,"Former exit",new ItemStack(Items.GOLD_INGOT),ItemStack.EMPTY,true)),level.registryAccess());
        ControllerSettings.write(controller,data);saveConfig(player,controller,KEY);
        require(ControllerSettings.exitChannel(ControllerSettings.data(controller)).equals(KEY),"exit persists through validated SAVE");
        BlockPos hub=new BlockPos(10,240,4);
        Set<String> actionPressed=new HashSet<>();
        if(ControllerWireBridge.available()) {
            level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
            level.setBlockAndUpdate(hub.east(),Blocks.IRON_BLOCK.defaultBlockState());
            ControllerSessions.use(player,InteractionHand.MAIN_HAND,hub);
            for(String channel:List.of(KEY,ControllerInput.DEFAULT_EXIT))
                edn.stratodonut.drivebywire.wire.WireNetworkManager.createConnection(level,hub,hub.east(),Direction.WEST,channel);
            ControllerActionRegistry.register(new ControllerActionRegistry.Provider() {
                public Set<String> channels(ServerLevel world,BlockPos pos) {return world==level&&pos.equals(hub)?Set.of(KEY,ControllerInput.DEFAULT_EXIT):Set.of();}
                public void input(ServerLevel world,BlockPos pos,String channel,boolean pressed) {if(pressed)actionPressed.add(channel);else actionPressed.remove(channel);}
            });
        }
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);Object session=session(player);
        require(field(session,"effective").equals(Set.of(ControllerInput.DEFAULT_EXIT)),"reserved exit is removed from wireless, wire and future actions");
        input(player,session,Set.of(KEY,ControllerInput.DEFAULT_EXIT));
        require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(Couple.create(Frequency.of(new ItemStack(Items.IRON_INGOT)),Frequency.EMPTY)),"reserved exit cannot power its saved wireless binding");
        var tabFrequency=Couple.create(Frequency.of(new ItemStack(Items.GOLD_INGOT)),Frequency.EMPTY);
        require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(tabFrequency),"Tab now powers a wireless frequency");
        if(ControllerWireBridge.available()) {
            var signals=edn.stratodonut.drivebywire.wire.WireNetworkManager.get(level).getSourceSignals(hub);
            require(signals.getOrDefault(KEY,0)==0&&signals.getOrDefault(ControllerInput.DEFAULT_EXIT,0)==16,"wire rejects reserved exit but transmits Tab");
            require(actionPressed.equals(Set.of(ControllerInput.DEFAULT_EXIT)),"future actions reject reserved exit");
        }
        stop(player,session);require(actionPressed.isEmpty()&&!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(tabFrequency),"custom exit session cleanup");
        saveConfig(player,controller,"bad");
        require(ControllerSettings.exitChannel(ControllerSettings.data(controller)).equals(ControllerInput.DEFAULT_EXIT),"invalid saved exit falls back to Tab");
        System.out.println("PASS: exit persistence, server reservation across all outputs, reusable Tab and invalid-exit fallback");
    }
    private static void saveConfig(ServerPlayer player,ItemStack controller,String exit)throws Exception {
        saveConfig(player,controller,exit,ControllerSettings.color(ControllerSettings.data(controller)));
    }
    private static void saveConfig(ServerPlayer player,ItemStack controller,String exit,String color)throws Exception {
        CompoundTag incoming=ControllerSettings.data(controller);
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.CONFIG,incoming));
        Field editors=ControllerSessions.class.getDeclaredField("EDITORS");editors.setAccessible(true);
        incoming.putUUID("EditToken",(UUID)((Map<?,?>)editors.get(null)).get(player.getUUID()));incoming.putString("ExitChannel",exit);
        incoming.putString("Color",color);
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.SAVE,incoming));
    }
    private static void colors(ServerPlayer player,ItemStack controller)throws Exception {
        CompoundTag forged=ControllerSettings.data(controller);forged.putString("Color","blue");
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.SAVE,forged));
        require(ControllerSettings.color(ControllerSettings.data(controller)).equals("default"),"palette changes require the editor token");
        saveConfig(player,controller,ControllerInput.DEFAULT_EXIT,"blue");
        require(ControllerSettings.color(ControllerSettings.data(controller)).equals("blue"),"chosen palette persists through authenticated SAVE");
        saveConfig(player,controller,ControllerInput.DEFAULT_EXIT,"../unexpected");
        require(ControllerSettings.color(ControllerSettings.data(controller)).equals("default"),"invalid palette falls back to the original texture");
        System.out.println("PASS: authenticated palette save and invalid-palette fallback");
    }
    private static void wire(ServerLevel level,ServerPlayer player,ItemStack controller) throws Exception {
        stop(player,session(player));
        BlockPos hub=new BlockPos(8,240,4),sink=hub.east();
        level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(sink,Blocks.IRON_BLOCK.defaultBlockState());
        var processor=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub);
        require(ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get() instanceof edn.stratodonut.drivebywire.wire.MultiChannelWireSource,"optional multi-channel mixin applies");
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,hub);
        CompoundTag data=ControllerSettings.data(controller);
        require(data.hasUUID("Processor")&&data.getUUID("Processor").equals(processor.identity()),"right click pairs directly without entering a binding mode");
        require(session(player)==null,"pairing click never activates remote outputs");
        require(ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState().getShape(level,hub).max(Direction.Axis.Y)==.5,
                "processor collision matches the wire hub's half-block model");
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
        Object session=session(player);require(field(session,"effective").equals(Set.of(KEY)),"empty processor does not claim all inputs");
        edn.stratodonut.drivebywire.wire.WireNetworkManager.createConnection(level,hub,sink,Direction.WEST,MOUSE);
        invoke(session,"refresh");require(field(session,"effective").equals(Set.of(KEY,MOUSE)),"a wired mouse button enables independently");
        input(player,session,Set.of(KEY,MOUSE));
        require(edn.stratodonut.drivebywire.wire.WireNetworkManager.get(level).getSourceSignals(hub).get(MOUSE)==16,"real wire output powers mouse channel");
        edn.stratodonut.drivebywire.wire.WireNetworkManager.removeConnection(level,hub,sink,Direction.WEST,MOUSE);
        invoke(session,"refresh");require(field(session,"effective").equals(Set.of(KEY)),"unwiring removes mouse ownership");
        require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(Couple.create(Frequency.of(new ItemStack(Items.IRON_INGOT)),Frequency.EMPTY)),"unwiring mouse preserves independent wireless key");
        stop(player,session);
        level.removeBlock(hub,false);level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
        require(field(session(player),"hub")==null,"replacement processor cannot inherit pairing");
    }
    private static Object session(ServerPlayer player)throws Exception {
        Field field=ControllerSessions.class.getDeclaredField("SESSIONS");field.setAccessible(true);
        return ((Map<?,?>)field.get(null)).get(player.getUUID());
    }
    private static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void invoke(Object object,String name)throws Exception {Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(object);}
    private static void input(ServerPlayer player,Object session,Set<String> pressed)throws Exception {
        CompoundTag payload=new CompoundTag();payload.putUUID("Session",(UUID)field(session,"nonce"));payload.put("Pressed",ControllerSessions.strings(pressed));
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT,payload));
    }
    private static void stop(ServerPlayer player,Object session)throws Exception {
        CompoundTag payload=new CompoundTag();payload.putUUID("Session",(UUID)field(session,"nonce"));
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.STOP,payload));
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
