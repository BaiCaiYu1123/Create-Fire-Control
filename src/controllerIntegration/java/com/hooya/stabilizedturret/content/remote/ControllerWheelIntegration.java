package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.api.remote.RemoteControllerApi;
import com.hooya.stabilizedturret.api.remote.RemoteControllerApi.*;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.network.*;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.lang.reflect.Method;
import java.util.*;

/** Exercises native wheel packets against real FCC direction logic and terminal outputs. */
final class ControllerWheelIntegration {
    static void run(ServerLevel level)throws Exception {
        long originalTime=level.getGameTime();BlockPos hub=new BlockPos(7,240,14),pos=hub.west();
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"ControllerWheel"));player.setPos(7,240,14);
        level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(pos,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        AmmoFixture fcc=new AmmoFixture(pos,level.getBlockState(pos));level.setBlockEntity(fcc);ControllerTracker.add(fcc);
        var remote=RemoteControllerApi.createController();player.setItemInHand(InteractionHand.MAIN_HAND,remote);
        require(Key.scroll(true).channel().equals("scroll_up")&&Key.parse("scroll_down").equals(Key.scroll(false)),"wheel API keys round trip");
        var up=new Binding(Chord.of(Key.scroll(true)),"Up",new ItemStack(Items.DIAMOND),ItemStack.EMPTY,true,7);
        var down=new Binding(Chord.of(Key.scroll(false)),"Down",new ItemStack(Items.EMERALD),ItemStack.EMPTY,true,4);
        RemoteControllerApi.configureHeld(player,InteractionHand.MAIN_HAND,new Configuration(Key.keyboard(258),Color.ORIGINAL,List.of(up,down)));
        RemoteControllerApi.bindHeldTerminal(player,InteractionHand.MAIN_HAND,hub);RemoteControllerApi.bindFireControl(level,pos,hub);
        require(RemoteControllerApi.mapFireControl(level,pos,FireControlAction.CYCLE_AIRCRAFT_WEAPON,down.input()),"old action 7 remains forward/down");
        require(RemoteControllerApi.mapFireControl(level,pos,FireControlAction.CYCLE_AIRCRAFT_WEAPON_UP,up.input()),"new action 16 is reverse/up");
        fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS,14,"key_87");
        fcc.setFrequency(16,true,new ItemStack(Items.DIAMOND));
        var saved=fcc.saveWithoutMetadata(level.registryAccess());
        var restored=new StabilizerControllerBlockEntity(pos,fcc.getBlockState());restored.loadWithComponents(saved,level.registryAccess());
        require(restored.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS).get(7).equals("scroll_down")
                &&restored.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS).get(16).equals("scroll_up"),"old and new mappings survive world data");
        var legacy=saved.copy();legacy.remove("AircraftWeaponCycleUpWireless");
        legacy.getCompound("WirelessRemoteKeys").remove("16");restored.loadWithComponents(legacy,level.registryAccess());
        require(restored.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS).get(7).equals("scroll_down")
                &&restored.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS).get(16).isEmpty(),"legacy action indexes remain unchanged");
        // Remove the frequency from this fixture so only direct terminal mapping drives it.
        fcc.setFrequency(16,true,ItemStack.EMPTY);
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
        UUID token=ControllerSessions.apiState(player).orElseThrow().token();
        try {
            CompoundTag held=new CompoundTag();held.putUUID("Session",token);held.put("Pressed",ControllerSessions.strings(Set.of("key_87")));
            ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT,held));
            List<MianbaoAircraftCompat.WeaponType> next=scroll(level,player,token,fcc,false,3,hub,4);
            var types=MianbaoAircraftCompat.WeaponType.values();
            require(next.equals(List.of(types[2],types[types.length-1],types[0])),"three down notches skip empty types and wrap forward");
            next=scroll(level,player,token,fcc,true,2,hub,7);
            require(next.equals(List.of(types[types.length-1],types[2])),"two up notches wrap backward independently");
            CompoundTag stop=new CompoundTag();stop.putUUID("Session",token);ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.STOP,stop));
            require(ControllerSessions.apiState(player).isEmpty(),"stop ends wheel session");
            require(KeyboardControllerInputs.signals(level,hub).isEmpty(),"stop clears terminal wheel signals");
            var handle=RemoteControllerApi.startControl(player,InteractionHand.MAIN_HAND).orElseThrow();
            require(RemoteControllerApi.scroll(player,handle,true,3),"public API accepts queued wheel notches");
            RemoteControllerApi.stopControl(player,handle);
            for(int i=0;i<8;i++){level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime()+1);ControllerSessions.tick(new ServerTickEvent.Post(()->true,level.getServer()));}
            require(KeyboardControllerInputs.signals(level,hub).isEmpty(),"stopped queue cannot reactivate outputs");
            if (ControllerWireBridge.available()) advanced(level,player,fcc,hub.east(3));
        } finally {RemoteControllerApi.controlState(player).ifPresent(s->ControllerSessions.apiStop(player,s.handle().token()));level.getServer().getWorldData().overworldData().setGameTime(originalTime);ControllerTracker.remove(fcc);}
        System.out.println("PASS: native wheel packets, exact notch counts, independent up/down weapon cycling, wrap/empty skipping, strength, legacy mapping data and API queue cleanup");
    }
    private static void advanced(ServerLevel level,ServerPlayer player,AmmoFixture fcc,BlockPos hub)throws Exception {
        var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_tweaked_controllers","tweaked_linked_controller"));
        if(item==Items.AIR)return;
        var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("drivebywire","tweaked_controller_hub"));
        level.setBlockAndUpdate(hub,block.defaultBlockState());
        ItemStack remote=new ItemStack(item);CompoundTag data=new CompoundTag();data.putLong("DriveByWireHub",hub.asLong());
        remote.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));player.setItemInHand(InteractionHand.MAIN_HAND,remote);
        fcc.bind(BindingSlot.TWEAKED_CONTROLLER_HUB,BindingRef.capture(level,hub));
        fcc.setRemoteKey(RemoteControllerCompat.Type.ADVANCED,7,"buttonA");fcc.setRemoteKey(RemoteControllerCompat.Type.ADVANCED,16,"buttonB");
        Method tick=StabilizerControllerBlockEntity.class.getDeclaredMethod("tickRemoteInputs");tick.setAccessible(true);
        var types=MianbaoAircraftCompat.WeaponType.values();
        for(boolean up:new boolean[]{false,true}) {
            String key=up?"buttonB":"buttonA";int steps=up?2:3;
            RemoteControlTracker.update(player,true,RemoteControllerCompat.Type.ADVANCED,hub);
            // Even a native wheel slot left high must not merge or latch queued notches.
            edn.stratodonut.drivebywire.compat.ControllerSignalStore.setSignal(level,hub,key,15);
            RemoteInputTracker.update(player,RemoteControllerCompat.Type.ADVANCED,hub,Set.of(),null,List.of(new ControllerPulseQueue.Pulse(Set.of(key),steps)));
            List<MianbaoAircraftCompat.WeaponType> actual=new ArrayList<>();var previous=fcc.getSelectedAircraftWeapon();
            for(int i=0;i<steps*2+2;i++) {
                tick.invoke(fcc);var current=fcc.getSelectedAircraftWeapon();if(current!=previous)actual.add(current);previous=current;
                level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime()+1);
            }
            require(actual.equals(up?List.of(types[0],types[types.length-1]):List.of(types[types.length-1],types[0],types[2])),"advanced wheel keeps every notch and direction even with native wire signal held high");
            edn.stratodonut.drivebywire.compat.ControllerSignalStore.clear(level,hub);
        }
        RemoteControlTracker.clear(player);require(!RemoteInputTracker.isPressed(fcc,RemoteControllerCompat.Type.ADVANCED,"buttonA"),"advanced exit clears pending wheel state");
        System.out.println("PASS: real advanced controller/terminal, ordered repeated wheel packets and native signal precedence");
    }
    private static List<MianbaoAircraftCompat.WeaponType> scroll(ServerLevel level,ServerPlayer player,UUID token,AmmoFixture fcc,boolean up,int steps,BlockPos hub,int strength)throws Exception {
        CompoundTag packet=new CompoundTag();packet.putUUID("Session",token);packet.putString("Wheel",up?"scroll_up":"scroll_down");packet.putInt("Steps",steps);
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.SCROLL,packet));
        CompoundTag released=new CompoundTag();released.putUUID("Session",token);released.put("Pressed",ControllerSessions.strings(Set.of()));
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT,released));
        Method tick=StabilizerControllerBlockEntity.class.getDeclaredMethod("tickRemoteInputs");tick.setAccessible(true);
        List<MianbaoAircraftCompat.WeaponType> result=new ArrayList<>();var before=fcc.getSelectedAircraftWeapon();
        for(int i=0;i<steps*2+2;i++) {
            ControllerSessions.tick(new ServerTickEvent.Post(()->true,level.getServer()));tick.invoke(fcc);
            var after=fcc.getSelectedAircraftWeapon();if(after!=before)result.add(after);before=after;
            int value=KeyboardControllerInputs.signals(level,hub).getOrDefault(up?"scroll_up":"scroll_down",0);
            require(value==0||value==16,"native wheel uses a logical key state independent of output strength");
            require(KeyboardControllerInputs.signals(level,hub).getOrDefault("key_87",0)==0,"queued wheel does not replay an unrelated released key");
            var frequency=net.createmod.catnip.data.Couple.create(com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency.of(new ItemStack(up?Items.DIAMOND:Items.EMERALD)),com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency.EMPTY);
            require(com.simibubi.create.Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(frequency)==(value>0),"wireless and wired wheel pulses agree");
            level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime()+1);
        }
        return result;
    }
    private static final class AmmoFixture extends StabilizerControllerBlockEntity {
        AmmoFixture(BlockPos pos,BlockState state){super(pos,state);}
        @Override public int getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType type){var values=MianbaoAircraftCompat.WeaponType.values();return type==values[0]||type==values[2]||type==values[values.length-1]?10:0;}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
