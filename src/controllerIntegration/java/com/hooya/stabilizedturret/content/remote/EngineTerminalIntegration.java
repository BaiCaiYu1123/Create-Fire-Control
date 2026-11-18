package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.registry.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

final class EngineTerminalIntegration {
    private static final String START="key_87+key_340",LEFT="key_65",RIGHT="key_68";
    static void run(ServerLevel level,ServerPlayer player,ItemStack controller)throws Exception {
        BlockPos hub=new BlockPos(30,240,4);
        level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        var states=List.of(ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.PART,GasTurbinePart.NEGATIVE),
                ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState(),ModBlocks.TIGER_I_ENGINE.get().defaultBlockState());
        var settings=new EngineControlSettings(150,START,List.of(new EngineDriveBehavior(List.of(LEFT,RIGHT),256,128,128,128,128,128)));
        var update=GasTurbineBlockEntity.class.getDeclaredMethod("updateTargetsFromRemote");update.setAccessible(true);
        for(int i=0;i<states.size();i++) {
            BlockPos pos=hub.offset(0,0,4+i*4);level.removeBlock(pos,false);level.setBlockAndUpdate(pos,states.get(i));
            var engine=(GasTurbineBlockEntity)level.getBlockEntity(pos);engine.onLoad();
            require(engine.bindAdvancedHub(BindingRef.capture(level,hub)),"each engine accepts the new terminal");
            require(engine.applySettings(settings),"engine accepts two-key ignition and drive chord");
            CompoundTag data=ControllerSettings.data(controller);ControllerSettings.putBindings(data,List.of(),level.registryAccess());ControllerSettings.write(controller,data);
            ControllerSessions.use(player,InteractionHand.MAIN_HAND,hub);ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
            Object session=session(player);
            require(ControllerActionRegistry.channels(level,hub).containsAll(Set.of("key_87","key_340",LEFT,RIGHT)),"engine mappings are live terminal uses without physical wire");
            input(player,session,Set.of("key_87"));update.invoke(engine);require(!engine.isEngineEnabled(),"ignition waits for both keys");
            input(player,session,Set.of("key_87","key_340"));update.invoke(engine);require(engine.isEngineEnabled(),"two-key ignition toggles engine");
            input(player,session,Set.of(LEFT));update.invoke(engine);require(engine.getTargetMagnitude()==0,"drive chord waits for both keys");
            input(player,session,Set.of(LEFT,RIGHT));update.invoke(engine);require(engine.getTargetLeftSpeed()==256&&engine.getTargetRightSpeed()==128,"both independent drive targets follow new terminal");
            input(player,session,Set.of(LEFT));update.invoke(engine);require(engine.getTargetMagnitude()==0,"release clears drive targets");
            if(net.neoforged.fml.ModList.get().isLoaded("sable_schematic_api"))BlueprintChecks.run(level,engine,hub,i);
            stop(player,session);
        }
        var tooltip=new ArrayList<net.minecraft.network.chat.Component>();
        require(((WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub)).addToTooltip(tooltip,false)&&tooltip.size()>=3,"new terminal exposes its connection hint panel");
        System.out.println("PASS: gas turbine, 99A and 8-cylinder engine, two-key ignition/drive and release; blueprint="+net.neoforged.fml.ModList.get().isLoaded("sable_schematic_api"));
    }
    // Keep optional blueprint types out of the base fixture's verifier on minimal servers.
    private static final class BlueprintChecks {
    private static void run(ServerLevel level,GasTurbineBlockEntity engine,BlockPos hub,int index)throws Exception {
        var save=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession(level,new org.joml.Vector3d(),new dev.ryanhcode.sable.companion.math.BoundingBox3d(20,230,0,60,250,24));
        var frame=new dev.rew1nd.sableschematicapi.api.blueprint.SubLevelSaveFrame(1,UUID.randomUUID(),null,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(20,230,0,60,250,24),BlockPos.ZERO,new dev.ryanhcode.sable.companion.math.Pose3d());save.addFrame(frame);
        var context=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext(save,frame,engine.getBlockPos(),engine.getBlockPos(),engine.getBlockState(),engine);
        var mapper=dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry.get(engine.getType());require(mapper!=null,"all engine types register a blueprint mapper");
        CompoundTag portable=mapper.save(context,engine.saveWithoutMetadata(level.registryAccess()));
        require(!portable.contains("AdvancedHub"),"blueprint removes original absolute terminal coordinate");
        BlockPos offset=new BlockPos(80+index*40,0,0),placedHub=hub.offset(offset),placedEngine=engine.getBlockPos().offset(offset);
        var place=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession(level,new org.joml.Vector3d(),new CompoundTag()) {
            @Override public BlockPos mapBlock(dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef ref){return ref.localPos().offset(offset);}
        };
        var placedContext=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext(place,null,1,frame.sourceUuid(),engine.getBlockPos(),placedEngine,engine.getBlockState());
        mapper.beforeLoadBlockEntity(placedContext,portable);
        var processor=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub);
        var processorMapper=dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry.get(processor.getType());
        CompoundTag processorTag=processor.saveWithoutMetadata(level.registryAccess());processorMapper.beforeLoadBlockEntity(placedContext,processorTag);
        require(!processorTag.getUUID("ProcessorIdentity").equals(processor.identity()),"blueprint terminal copies receive a fresh identity");
        level.setBlockAndUpdate(placedHub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());level.setBlockAndUpdate(placedEngine,engine.getBlockState());
        ((WirelessSignalProcessorBlockEntity)level.getBlockEntity(placedHub)).loadWithComponents(processorTag,level.registryAccess());
        var copy=(GasTurbineBlockEntity)level.getBlockEntity(placedEngine);copy.loadWithComponents(portable,level.registryAccess());
        require(copy.hasValidAdvancedHub()&&copy.getAdvancedHub().pos().equals(placedHub),"blueprint restores a connection to the placed terminal");
        require(copy.getTerminalType()==RemoteControllerCompat.Type.WIRELESS&&copy.getControlSettings().equals(engine.getControlSettings()),"blueprint preserves terminal type, ignition and behavior chords");
        require(copy.getAdvancedHub().save().getUUID(BindingRef.WIRELESS_PROCESSOR_ID_TAG).equals(processorTag.getUUID("ProcessorIdentity")),"engine blueprint binding follows the copied terminal identity");
        if(index==0) {
            BlockPos sink=hub.east();level.setBlockAndUpdate(sink,Blocks.IRON_BLOCK.defaultBlockState());
            edn.stratodonut.drivebywire.wire.WireNetworkManager.createConnection(level,hub,sink,Direction.WEST,"key_87");
            var wireEvent=dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEventRegistry.events().stream()
                    .filter(event->event.id().getPath().equals("drivebywire/wires")).findFirst().orElseThrow();
            save.recordSavedBlock(frame,hub,hub,level.getBlockState(hub),level.getBlockEntity(hub),0,new CompoundTag());
            save.recordSavedBlock(frame,sink,sink,level.getBlockState(sink),level.getBlockEntity(sink),0,new CompoundTag());
            CompoundTag extra=new CompoundTag();wireEvent.onSaveAfterBlocks(save,extra);
            level.setBlockAndUpdate(sink.offset(offset),Blocks.IRON_BLOCK.defaultBlockState());
            wireEvent.onPlaceAfterBlockEntities(place,extra);
            require(ControllerWireBridge.connected(level,placedHub).contains("key_87"),"Sable blueprint event restores physical wires from the new terminal");
            var computer=hub.north();level.setBlockAndUpdate(computer,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            var fcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer);
            fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB,BindingRef.capture(level,hub));fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS,14,START);
            var fccMapper=dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry.get(fcc.getType());
            var fccSave=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext(save,frame,computer,computer,fcc.getBlockState(),fcc);
            CompoundTag fccTag=fccMapper.save(fccSave,fcc.saveWithoutMetadata(level.registryAccess()));
            var fccPlace=new dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext(place,null,1,frame.sourceUuid(),computer,computer.offset(offset),fcc.getBlockState());
            fccMapper.beforeLoadBlockEntity(fccPlace,fccTag);
            require(fccTag.getCompound("Binding_WIRELESS_CONTROLLER_HUB").getLong("Pos")==placedHub.asLong()
                    && fccTag.getCompound("WirelessRemoteKeys").getString("14").equals(START),"blueprint restores FCC connection and two-key mapping");
            require(fccTag.getCompound("Binding_WIRELESS_CONTROLLER_HUB").getUUID(BindingRef.WIRELESS_PROCESSOR_ID_TAG).equals(processorTag.getUUID("ProcessorIdentity")),"FCC blueprint binding shares the copied terminal identity");
            level.setBlockAndUpdate(computer.offset(offset),fcc.getBlockState());
            var copiedFcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer.offset(offset));
            copiedFcc.loadWithComponents(fccTag,level.registryAccess());
            require(RemoteTerminalDetector.hasTerminal(copiedFcc,level,RemoteControllerCompat.Type.WIRELESS),"loaded FCC blueprint resolves the copied terminal without rebinding");
            System.out.println("PASS: Sable blueprint hooks restore new-terminal wires and FCC chord mappings");
        }
        System.out.println("PASS: blueprint mapper save/load remaps terminal and retains engine keys for "+engine.getType());
    }
    }
    private static Object session(ServerPlayer player)throws Exception {var f=ControllerSessions.class.getDeclaredField("SESSIONS");f.setAccessible(true);return ((Map<?,?>)f.get(null)).get(player.getUUID());}
    private static UUID nonce(Object session)throws Exception{var f=session.getClass().getDeclaredField("nonce");f.setAccessible(true);return (UUID)f.get(session);}
    private static void input(ServerPlayer p,Object s,Set<String> keys)throws Exception{CompoundTag tag=new CompoundTag();tag.putUUID("Session",nonce(s));tag.put("Pressed",ControllerSessions.strings(keys));ControllerSessions.handle(p,new com.hooya.stabilizedturret.network.WirelessControllerActionPayload(com.hooya.stabilizedturret.network.WirelessControllerActionPayload.INPUT,tag));}
    private static void stop(ServerPlayer p,Object s)throws Exception{CompoundTag tag=new CompoundTag();tag.putUUID("Session",nonce(s));ControllerSessions.handle(p,new com.hooya.stabilizedturret.network.WirelessControllerActionPayload(com.hooya.stabilizedturret.network.WirelessControllerActionPayload.STOP,tag));}
    private static void require(boolean v,String message){if(!v)throw new AssertionError(message);}
}
