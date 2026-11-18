package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.api.remote.RemoteControllerApi;
import com.hooya.stabilizedturret.api.remote.RemoteControllerApi.*;
import com.hooya.stabilizedturret.network.WirelessControllerActionPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.*;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.*;

/** Checks numeric values at real Create receivers and Drive By Wire sink faces. */
final class ControllerStrengthIntegration {
    static void run(ServerLevel level)throws Exception {
        Key primary=Key.keyboard(87),secondary=Key.mouse(3),wireOnly=Key.mouse(0);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"SignalStrength"));
        BlockPos hub=new BlockPos(4,240,6),sink=hub.east(),secondSink=hub.north();
        player.setPos(4.5,240,6.5);
        ItemStack remote=RemoteControllerApi.createController();player.setItemInHand(InteractionHand.MAIN_HAND,remote);
        require(new Binding(Chord.of(primary),"",ItemStack.EMPTY,ItemStack.EMPTY,true).signalStrength()==16,"old API constructor defaults to 16");
        CompoundTag legacy=ControllerSettings.data(remote);
        ControllerSettings.putBindings(legacy,List.of(new ControllerSettings.Binding(primary.channel(),"",ItemStack.EMPTY,ItemStack.EMPTY,true)),level.registryAccess());
        CompoundTag entry=legacy.getList("Bindings",Tag.TAG_COMPOUND).getCompound(0);entry.remove("SignalStrength");
        require(ControllerSettings.bindings(legacy,level.registryAccess()).getFirst().signalStrength()==16,"old item data defaults to 16");
        for(int malformed:new int[]{-99,999}) {
            entry.putInt("SignalStrength",malformed);ControllerSettings.write(remote,legacy);
            ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.CONFIG,legacy));
            var editors=ControllerSessions.class.getDeclaredField("EDITORS");editors.setAccessible(true);
            CompoundTag request=legacy.copy();request.putUUID("EditToken",(UUID)((Map<?,?>)editors.get(null)).get(player.getUUID()));
            ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.SAVE,request));
            int saved=ControllerSettings.data(remote).getList("Bindings",Tag.TAG_COMPOUND).getCompound(0).getInt("SignalStrength");
            require(saved==(malformed<0?0:16),"authenticated server SAVE normalizes out-of-range strength");
        }
        if(RemoteControllerApi.wiresAvailable()) {
            level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
            level.setBlockAndUpdate(sink,Blocks.IRON_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(secondSink,Blocks.IRON_BLOCK.defaultBlockState());
            RemoteControllerApi.bindHeldTerminal(player,InteractionHand.MAIN_HAND,hub);
            RemoteControllerApi.connectWire(level,hub,sink,Direction.WEST,primary);
            RemoteControllerApi.connectWire(level,hub,secondSink,Direction.SOUTH,wireOnly);
        }
        Probe probe=new Probe(hub,new ItemStack(Items.DIAMOND));
        Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level,probe);
        try {
            for(int strength:new int[]{0,1,7,16}) {
                Configuration config=new Configuration(Key.keyboard(258),Color.ORIGINAL,List.of(
                        new Binding(Chord.of(primary,secondary),"Numeric",new ItemStack(Items.DIAMOND),ItemStack.EMPTY,true,strength),
                        new Binding(Chord.of(wireOnly),"",ItemStack.EMPTY,ItemStack.EMPTY,true,9)));
                RemoteControllerApi.configureHeld(player,InteractionHand.MAIN_HAND,config);
                var restored=RemoteControllerApi.loadController(RemoteControllerApi.saveController(remote,level.registryAccess()),level.registryAccess());
                require(RemoteControllerApi.configuration(restored,level.registryAccess()).equals(config),"item/API persistence retains each strength");
                var handle=RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow();
                RemoteControllerApi.press(player,handle,primary);require(probe.value==0,"incomplete chord emits zero");
                RemoteControllerApi.setInputs(player,handle,Set.of(primary,secondary,wireOnly));
                require(probe.value==strength,"Create receiver gets exact configured strength "+strength);
                if(RemoteControllerApi.wiresAvailable()) {
                    var network=edn.stratodonut.drivebywire.wire.WireNetworkManager.get(level);
                    require(network.getSourceSignals(hub).getOrDefault(primary.channel(),0)==strength,"terminal source gets exact strength");
                    require(network.getSignalAt(sink,Direction.WEST)==strength,"wired sink gets exact strength");
                    require(network.getSignalAt(secondSink,Direction.SOUTH)==9,"wire-only key has independent strength");
                }
                RemoteControllerApi.release(player,handle,secondary);require(probe.value==0,"either chord key release clears wireless");
                if(RemoteControllerApi.wiresAvailable()) {
                    var network=edn.stratodonut.drivebywire.wire.WireNetworkManager.get(level);
                    require(network.getSignalAt(sink,Direction.WEST)==0&&network.getSignalAt(secondSink,Direction.SOUTH)==9,"release clears only its wired output");
                }
                RemoteControllerApi.stopControl(player,handle);
                if(RemoteControllerApi.wiresAvailable())require(edn.stratodonut.drivebywire.wire.WireNetworkManager.get(level).getSignalAt(secondSink,Direction.SOUTH)==0,"stop clears wired output");
            }
        } finally {Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level,probe);}
        System.out.println("PASS: numeric 0/1/7/16 Create receivers and wired sink values, independent wire-only strength, chord release, stop, legacy defaults and authenticated SAVE validation");
    }
    private static final class Probe implements IRedstoneLinkable {
        final BlockPos pos;final Couple<RedstoneLinkNetworkHandler.Frequency> frequency;int value;
        Probe(BlockPos pos,ItemStack item){this.pos=pos;frequency=Couple.create(RedstoneLinkNetworkHandler.Frequency.of(item),RedstoneLinkNetworkHandler.Frequency.EMPTY);}
        @Override public int getTransmittedStrength(){return 0;}
        @Override public void setReceivedStrength(int strength){value=strength;}
        @Override public boolean isListening(){return true;}
        @Override public boolean isAlive(){return true;}
        @Override public BlockPos getLocation(){return pos;}
        @Override public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey(){return frequency;}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
