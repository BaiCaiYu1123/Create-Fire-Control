package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.network.*;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import io.netty.buffer.Unpooled;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Real loaded blocks, real Create redstone network, actual optional mods and normal engine ticks. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class EngineInputIntegration {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static int age;
    private static final List<GasTurbineBlockEntity> engines = new ArrayList<>();
    private static final List<Sender> senders = new ArrayList<>();
    private static EngineRedstoneChannel ignition, drive;
    private static final BlockPos HUB = new BlockPos(12,240,12);
    static void start(MinecraftServer value) { server=value;level=server.overworld();age=0;engines.clear();senders.clear(); }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(server==null || event.getServer()!=server)return;
        try { run(++age); } catch(Throwable error) { finish(error); }
    }
    private static void run(int tick) throws Exception {
        // Let the forced spawn chunk and SmartBlockEntity initialization finish before input assertions.
        if(tick>1&&tick<20)return;
        if(tick>=20)tick-=15;
        if(tick==1) {
            level.setChunkForced(0,0,true);
            player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"EngineInputs"));player.setPos(10,240,10);
            ignition=new EngineRedstoneChannel(new ItemStack(Items.IRON_INGOT),new ItemStack(Items.REDSTONE));
            drive=new EngineRedstoneChannel(new ItemStack(Items.GOLD_INGOT),new ItemStack(Items.REDSTONE));
            var states=List.of(ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.PART,GasTurbinePart.NEGATIVE),
                    ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState(),ModBlocks.TIGER_I_ENGINE.get().defaultBlockState());
            for(int i=0;i<states.size();i++) {
                var pos=new BlockPos(3+i*4,240,3);level.setBlockAndUpdate(pos,states.get(i));
                engines.add((GasTurbineBlockEntity)level.getBlockEntity(pos));
            }
        }
        if(tick==5) {
            var settings=settings("", List.of(), ignition, drive);
            for(var engine:engines)require(engine.applySettings(settings),"unbound engine accepts direct redstone configuration");
            var nbt=new CompoundTag();EngineSettingsCodec.save(nbt,settings,level.registryAccess());
            require(EngineSettingsCodec.load(nbt,level.registryAccess()).equals(settings),"frequency pairs survive NBT including behavior order");
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),level.registryAccess());
            try { EngineSettingsCodec.write(buffer,settings);require(EngineSettingsCodec.read(buffer).equals(settings)&&buffer.readableBytes()==0,"frequency pairs survive network round trip"); }
            finally { buffer.release(); }
            var start=new Sender(ignition);var motion=new Sender(drive);senders.add(start);senders.add(motion);
            for(var sender:senders)Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level,sender);
        }
        if(tick==7)power(0,15);
        if(tick==9) { for(var engine:engines)require(engine.isEngineEnabled(),"redstone rising edge ignites each engine");power(1,15); }
        if(tick==11) {
            verifyDrive(true);for(var engine:engines)require(engine.isEngineEnabled(),"held ignition toggles only once");power(0,0);power(1,0);
        }
        if(tick==13) {
            verifyDrive(false);
            power(1,15);
            // Empty/empty is deliberately silent even if an unconfigured transmitter is powered.
            var empty=new Sender(EngineRedstoneChannel.EMPTY);senders.add(empty);Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level,empty);power(2,15);
            for(var engine:engines) {
                var saved=engine.saveWithoutMetadata(level.registryAccess());engine.loadWithComponents(saved,level.registryAccess());
                require(engine.getControlSettings().toggleChannel().equals(ignition),"all engine types reload their frequencies");
            }
        }
        if(tick==15) {
            verifyDrive(true);
        }
        if(tick==17) {
            verifyDrive(true);
            for(var engine:engines)require(engine.applySettings(settings("",List.of(),ignition,
                    new EngineRedstoneChannel(new ItemStack(Items.DIAMOND),new ItemStack(Items.REDSTONE)))),"live channel edit");
        }
        if(tick==19) { verifyDrive(false);power(1,0);power(0,15); }
        if(tick==21) {
            for(var engine:engines)require(!engine.isEngineEnabled(),"a second redstone rising edge stops the engine");power(0,0);
            if(!ControllerWireBridge.available()) { finish(null);return; }
            var id=ResourceLocation.fromNamespaceAndPath("drivebywire","tweaked_controller_hub");
            var block=BuiltInRegistries.BLOCK.getOptional(id).orElseThrow();level.setBlockAndUpdate(HUB,block.defaultBlockState());
            for(var engine:engines) {
                require(engine.applySettings(settings("key_87",List.of("key_65"),EngineRedstoneChannel.EMPTY,EngineRedstoneChannel.EMPTY)),"old native configuration installed");
                require(engine.bindAdvancedHub(BindingRef.capture(level,HUB)),"real advanced terminal binds");
                require(engine.getTerminalType()==RemoteControllerCompat.Type.ADVANCED && engine.getControlSettings().engineToggleKey().isEmpty()
                        && engine.getControlSettings().behaviors().getFirst().keys().isEmpty(),"switching terminal clears incompatible native mappings");
                require(!engine.applySettings(settings("key_87",List.of("key_65"),EngineRedstoneChannel.EMPTY,EngineRedstoneChannel.EMPTY)),"advanced terminal rejects arbitrary physical keyboard channels");
                require(engine.applySettings(settings("buttonA",List.of("buttonB"),EngineRedstoneChannel.EMPTY,EngineRedstoneChannel.EMPTY)),"advanced terminal accepts supported slots after switching");
            }
            Item item=BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("create_tweaked_controllers","tweaked_linked_controller")).orElseThrow();
            var remote=new ItemStack(item);CustomData.update(DataComponents.CUSTOM_DATA,remote,tag->tag.putLong("DriveByWireHub",HUB.asLong()));
            player.setItemInHand(InteractionHand.MAIN_HAND,remote);
        }
        if(tick>=22&&tick<=33) {
            Set<String> keys=tick<=25?Set.of("buttonA","buttonB"):tick>=30?Set.of("buttonB"):Set.of();
            RemoteControlTracker.update(player,true,RemoteControllerCompat.Type.ADVANCED,HUB);
            RemoteInputTracker.update(player,RemoteControllerCompat.Type.ADVANCED,HUB,keys);
        }
        if(tick==24) { verifyDrive(true);for(var engine:engines)require(engine.isEngineEnabled(),"real advanced slot input ignites engine without an FCC"); }
        if(tick==28)verifyDrive(false);
        if(tick==32)verifyDrive(true);
        if(tick==46) { verifyDrive(false); finish(null); }
    }
    private static EngineControlSettings settings(String toggle,List<String> keys,EngineRedstoneChannel start,EngineRedstoneChannel motion) {
        return new EngineControlSettings(150,toggle,List.of(new EngineDriveBehavior(keys,256,128,128,128,128,128,
                EngineTransitionRule.DEFAULT,EngineTransitionRule.DEFAULT,motion)),false,128,128,256,start);
    }
    private static void power(int index,int strength) { var sender=senders.get(index);sender.strength=strength;Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level,sender); }
    private static void verifyDrive(boolean active) {
        for(var engine:engines)require(engine.getTargetLeftSpeed()==(active?256:0)&&engine.getTargetRightSpeed()==(active?128:0),
                "real server tick drive="+active+" at "+engine.getBlockPos()+" actual="+engine.getTargetLeftSpeed()+"/"+engine.getTargetRightSpeed());
    }
    private static final class Sender implements IRedstoneLinkable {
        private final EngineRedstoneChannel channel;private int strength;
        Sender(EngineRedstoneChannel value) { channel=value; }
        @Override public int getTransmittedStrength(){return strength;}
        @Override public void setReceivedStrength(int value){}
        @Override public boolean isListening(){return false;}
        @Override public boolean isAlive(){return server!=null;}
        @Override public Couple<Frequency> getNetworkKey(){return Couple.create(Frequency.of(channel.first()),Frequency.of(channel.second()));}
        @Override public BlockPos getLocation(){return new BlockPos(8,240,8);}
    }
    private static void finish(Throwable error) {
        if(error!=null)StabilizedTurret.LOGGER.error("ENGINE INPUT CHECK FAILED at tick "+age,error);
        String result=error==null?"PASS: real Create redstone network, all three engines, edge/hold/release, live edits, NBT/network persistence; advanced="+ControllerWireBridge.available():"FAIL at tick "+age+": "+error;
        try{Files.writeString(Path.of("engine-input-result.txt"),result);}catch(Exception failure){throw new RuntimeException(failure);}
        System.out.println(result);var done=server;server=null;done.halt(false);
    }
    private static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
}
