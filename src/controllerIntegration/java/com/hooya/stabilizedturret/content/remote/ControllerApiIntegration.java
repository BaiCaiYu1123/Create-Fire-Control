package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.api.remote.RemoteControllerApi;
import com.hooya.stabilizedturret.api.remote.RemoteControllerApi.*;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.registry.*;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.*;

/** Exercises the public facade exactly as another server mod would; excluded from the release. */
final class ControllerApiIntegration {
    private static final Key W=Key.keyboard(87),SIDE=Key.mouse(3),LEFT=Key.mouse(0);
    static void run(ServerLevel level)throws Exception {
        BlockPos hub=new BlockPos(2,240,14),computer=hub.west(),mount=hub.east(2),sink=hub.north();
        level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(computer,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        ControllerTracker.add((StabilizerControllerBlockEntity)level.getBlockEntity(computer));
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"ControllerApi"));player.setPos(4.5,240,13.5);
        ItemStack stack=RemoteControllerApi.createController();player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        ItemStack originalFrequency=new ItemStack(Items.GOLD_NUGGET,32);
        Binding binding=new Binding(Chord.of(W,SIDE),"API combo",originalFrequency,ItemStack.EMPTY,true);
        originalFrequency.shrink(32);binding.first().shrink(1);
        require(binding.first().is(Items.GOLD_NUGGET),"frequency records copy constructor arguments and accessors");
        Configuration config=new Configuration(Key.keyboard(258),Color.BLUE_RED,List.of(binding));
        require(RemoteControllerApi.configureHeld(player,InteractionHand.MAIN_HAND,config),"configure through public API");
        require(RemoteControllerApi.configuration(stack,level.registryAccess()).equals(config),"all typed configuration values round trip by value");
        require(RemoteControllerApi.bindHeldTerminal(player,InteractionHand.MAIN_HAND,hub),"item pairs with terminal through API");
        var terminal=RemoteControllerApi.boundTerminal(level,stack).orElseThrow();
        var restored=RemoteControllerApi.loadController(RemoteControllerApi.saveController(stack,level.registryAccess()),level.registryAccess());
        require(RemoteControllerApi.identity(restored).equals(RemoteControllerApi.identity(stack)),"API serialization preserves identity");
        require(RemoteControllerApi.boundTerminal(level,restored).orElseThrow().equals(terminal),"API serialization preserves pairing");
        require(RemoteControllerApi.configuration(restored,level.registryAccess()).bindings().getFirst().input().keys().equals(List.of(W,SIDE)),"API serialization preserves both chord inputs");
        require(RemoteControllerApi.bindFireControl(level,computer,hub),"fire control binds to the terminal through API");
        require(RemoteControllerApi.fireControlTerminal(level,computer).orElseThrow().equals(terminal),"fire-control pairing can be read through API");
        for (FireControlAction action:FireControlAction.values()) require(RemoteControllerApi.mapFireControl(level,computer,action,Chord.of(W,SIDE)),"all sixteen FCC actions are mapped through typed API");
        require(RemoteControllerApi.fireControlMappings(level,computer).size()==FireControlAction.values().length,"all typed FCC mappings round trip");
        for (FireControlAction action:FireControlAction.values()) require(RemoteControllerApi.clearFireControlMapping(level,computer,action),"FCC action can be cleared through API");
        if (RemoteControllerApi.wiresAvailable()) require(RemoteControllerApi.mapFireControl(level,computer,FireControlAction.MACHINE_GUN,Chord.of(W,SIDE)),"map real FCC machine-gun input");
        if (RemoteControllerApi.wiresAvailable()) {
            level.setBlockAndUpdate(sink,Blocks.LEVER.defaultBlockState());
            require(RemoteControllerApi.connectWire(level,hub,sink,Direction.UP,LEFT),"connect a terminal wire through API");
            require(RemoteControllerApi.wiredInputs(level,hub).contains(LEFT),"connected physical wire is visible through API");
        }
        Set<Key> uses=new HashSet<>(Set.of(W));Set<Key> down=new HashSet<>();List<Boolean> transitions=new ArrayList<>();
        Registration registration=RemoteControllerApi.registerActions(id("actions"),new ActionProvider() {
            @Override public Set<Key> inputs(ServerLevel world,BlockPos pos) { return world==level&&pos.equals(hub)?Set.copyOf(uses):Set.of(); }
            @Override public void input(ServerLevel world,BlockPos pos,Key key,boolean pressed) {
                if (pressed) down.add(key);else down.remove(key);transitions.add(pressed);
            }
        });
        var handle=RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow();
        require(RemoteControllerApi.controlState(player).orElseThrow().apiControlled(),"API starts a logical-only session");
        require(RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow().equals(handle),"repeated start reuses the current API token");
        var frequency=Couple.create(Frequency.of(new ItemStack(Items.GOLD_NUGGET)),Frequency.EMPTY);
        RemoteControllerApi.press(player,handle,W);
        require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency)&&down.isEmpty(),"API primary alone does not trigger a two-key chord");
        require(RemoteControllerApi.press(player,handle,SIDE),"press secondary through API");
        require(Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency)&&down.contains(W),"API chord powers real Create wireless and custom action");
        require(RemoteControllerApi.controlState(player).orElseThrow().activeOutputs().contains(W),"functional output snapshot is public");
        var other=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"ApiBusy"));other.setPos(player.getX(),player.getY(),player.getZ());
        var second=RemoteControllerApi.createController();other.setItemInHand(InteractionHand.MAIN_HAND,second);RemoteControllerApi.bindHeldTerminal(other,InteractionHand.MAIN_HAND,hub);
        require(RemoteControllerApi.startControl(other,InteractionHand.MAIN_HAND).isEmpty()&&down.contains(W),"busy terminal refusal preserves the original provider output");
        require(!RemoteControllerApi.setInputs(other,handle,Set.of(W,SIDE)),"another player cannot use an API handle");
        if (RemoteControllerApi.wiresAvailable()) {
            wireOutput(level,hub,computer,player,handle);
            require(RemoteControllerApi.disconnectWire(level,hub,sink,Direction.UP,LEFT),"disconnect wire through API");
        }
        uses.clear();RemoteControllerApi.keepAlive(player,handle);
        require(down.isEmpty()&&transitions.equals(List.of(true,false)),"removing a provider use releases it even while wireless keeps the same output active");
        uses.add(W);RemoteControllerApi.keepAlive(player,handle);require(down.contains(W),"restored live action is routed again");
        registration.close();registration.close();require(down.isEmpty(),"unregister releases delivered inputs and close is idempotent");
        RemoteControllerApi.release(player,handle,SIDE);require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"API release stops wireless immediately");
        require(RemoteControllerApi.stopControl(player,handle),"API stops its own logical session");
        var newer=RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow();
        require(!RemoteControllerApi.press(player,handle,W),"a stale API token cannot operate a newer session");
        RemoteControllerApi.setInputs(player,newer,Set.of(W,SIDE));
        RemoteControllerApi.configureHeld(player,InteractionHand.MAIN_HAND,config);
        require(RemoteControllerApi.controlState(player).isEmpty()&&!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"configuration changes stop the old session and clear all outputs");
        callbackStop(level,hub,player,frequency);
        var timeout=RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow();
        RemoteControllerApi.setInputs(player,timeout,Set.of(W,SIDE));
        long before=level.getGameTime();level.getServer().getWorldData().overworldData().setGameTime(before+31);
        ControllerSessions.tick(new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(()->true,level.getServer()));
        require(RemoteControllerApi.controlState(player).isEmpty()&&!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"missing API heartbeat releases the real output after 30 ticks");
        level.getServer().getWorldData().overworldData().setGameTime(before);
        require(RemoteControllerApi.unbindFireControl(level,computer)&&RemoteControllerApi.fireControlTerminal(level,computer).isEmpty(),"FCC terminal detaches through API");
        engines(level,hub);
        level.setBlockAndUpdate(mount,Blocks.LECTERN.defaultBlockState());player.setShiftKeyDown(true);
        require(RemoteControllerApi.mount(player,InteractionHand.MAIN_HAND,mount),"API mounting is independent of native sneak input");player.setShiftKeyDown(false);
        var mounted=(WirelessLecternControllerBlockEntity)level.getBlockEntity(mount);
        require(RemoteControllerApi.configureLectern(level,mount,config)&&RemoteControllerApi.bindLecternTerminal(level,mount,hub),"mounted configuration and pairing have public operations");
        var mountedHandle=RemoteControllerApi.startLecternControl(player,mount).orElseThrow();
        RemoteControllerApi.setInputs(player,mountedHandle,Set.of(W,SIDE));
        require(!mounted.inUse()&&mounted.visualPressed().isEmpty(),"API lectern inputs do not enable animation or depressed keycaps");
        var recovered=RemoteControllerApi.retrieve(player,mount).orElseThrow();
        require(level.getBlockState(mount).is(Blocks.LECTERN)&&RemoteControllerApi.controlState(player).isEmpty(),"API retrieval restores lectern and stops the session");
        require(RemoteControllerApi.configuration(recovered,level.registryAccess()).color()==Color.BLUE_RED&&RemoteControllerApi.boundTerminal(level,recovered).isPresent(),"retrieved item retains all settings and pairing");
        player.setItemInHand(InteractionHand.MAIN_HAND,recovered);require(RemoteControllerApi.mount(player,InteractionHand.MAIN_HAND,mount),"remount configured item");
        player.setItemInHand(InteractionHand.MAIN_HAND,RemoteControllerApi.createController());
        require(RemoteControllerApi.swapLectern(player,InteractionHand.MAIN_HAND,mount),"swap through the functional API");
        require(RemoteControllerApi.configuration(player.getMainHandItem(),level.registryAccess()).color()==Color.BLUE_RED,"swap gives back the original configured item");
        RemoteControllerApi.retrieve(player,mount);
        require(RemoteControllerApi.unbindHeldTerminal(player,InteractionHand.MAIN_HAND)&&RemoteControllerApi.boundTerminal(level,player.getMainHandItem()).isEmpty(),"unpair through public API");
        RemoteControllerApi.bindHeldTerminal(player,InteractionHand.MAIN_HAND,hub);
        level.removeBlock(hub,false);level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        require(RemoteControllerApi.boundTerminal(level,player.getMainHandItem()).isEmpty(),"replacement terminal cannot inherit saved identity");
        java.util.concurrent.CompletableFuture.runAsync(()->{
            try { RemoteControllerApi.terminal(level,hub);throw new AssertionError("off-thread API call was accepted"); }
            catch (IllegalStateException expected) { }
        }).join();
        requireBad(()->Chord.of(W,W));requireBad(()->Chord.of(W,SIDE,LEFT));requireBad(()->Key.mouse(99));
        System.out.println("PASS: public controller API configuration, pairing, all FCC mappings, real wireless/wires, provider removal, logical sessions, ownership, stale tokens, engine/lectern operations and thread guards; wire="+RemoteControllerApi.wiresAvailable());
    }
    private static void wireOutput(ServerLevel level,BlockPos hub,BlockPos computer,ServerPlayer player,ControlHandle handle)throws Exception {
        RemoteControllerApi.press(player,handle,LEFT);
        require(edn.stratodonut.drivebywire.wire.WireNetworkManager.get(level).getSourceSignals(hub).get(LEFT.channel())==16,"API drives original terminal wire signals");
        var fcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer);
        var tick=StabilizerControllerBlockEntity.class.getDeclaredMethod("tickRemoteInputs");tick.setAccessible(true);tick.invoke(fcc);
        var values=StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed");values.setAccessible(true);
        require(((boolean[])values.get(fcc))[14],"API chord reaches the real FCC machine-gun mapping");
        RemoteControllerApi.release(player,handle,LEFT);
    }
    private static void callbackStop(ServerLevel level,BlockPos hub,ServerPlayer player,Couple<Frequency> frequency) {
        var handle=new ControlHandle[1];Set<Key> delivered=new HashSet<>();int[] pressed={0},released={0};
        try(var registration=RemoteControllerApi.registerActions(id("callback_stop"),new ActionProvider() {
            @Override public Set<Key> inputs(ServerLevel world,BlockPos pos) { return world==level&&pos.equals(hub)?Set.of(W,LEFT):Set.of(); }
            @Override public void input(ServerLevel world,BlockPos pos,Key key,boolean down) {
                if (down) {delivered.add(key);pressed[0]++;RemoteControllerApi.stopControl(player,handle[0]);}
                else {delivered.remove(key);released[0]++;}
            }
        })) {
            handle[0]=RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow();
            RemoteControllerApi.setInputs(player,handle[0],Set.of(W,SIDE,LEFT));
            require(pressed[0]==1&&released[0]==1&&delivered.isEmpty()&&RemoteControllerApi.controlState(player).isEmpty(),"stopping inside a provider callback cannot leave later inputs latched");
            require(!Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency),"reentrant callback stop releases real wireless output");
        }
    }
    private static void engines(ServerLevel level,BlockPos hub) {
        var states=List.of(ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.PART,GasTurbinePart.NEGATIVE),
                ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState(),ModBlocks.TIGER_I_ENGINE.get().defaultBlockState());
        for (int i=0;i<states.size();i++) {
            BlockPos engine=new BlockPos(9+i,240,14);level.setBlockAndUpdate(engine,states.get(i));
            require(RemoteControllerApi.bindEngine(level,engine,hub)&&RemoteControllerApi.engineTerminal(level,engine).isPresent(),"every engine accepts an API terminal binding");
            require(RemoteControllerApi.unbindEngine(level,engine)&&RemoteControllerApi.engineTerminal(level,engine).isEmpty(),"engine terminal can be detached through API");
        }
    }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("api_integration",name); }
    private static void requireBad(Runnable action) { try {action.run();throw new AssertionError("invalid API input accepted");}catch(IllegalArgumentException expected){} }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
