package com.hooya.stabilizedturret.validation;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisNoContactCompat;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.network.ServerAimSessionHandler;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Isolated real-server checks. Never included in the production source set/JAR. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class IdleIntegration {
    private static ServerLevel level;
    private static ServerSubLevel a, b, late;
    private static int age, checks;
    private static boolean done;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        try {
            level = event.getServer().overworld();
            packetChecks();
            checks += WeaponViewIdleChecks.verify(level);
            require(map(BearingStabilizer.class,"BOUND").isEmpty(),"weapon-view lifecycle leaves no bearing bindings");
            typeChecks();
            // No bindings and no saved ownership: no Synaxis graph snapshot should be requested.
            var snapshots = map(SynaxisWeldCompat.class, "SNAPSHOTS");
            snapshots.clear();
            for (int i=0;i<1000;i++) BearingStabilizer.onServerTickPost(post());
            require(snapshots.isEmpty(), "1000 empty ticks requested no weld graph");
            level.setChunkForced(0, 0, true);
            a = body(new BlockPos(1, 180, 1));
            b = body(new BlockPos(5, 180, 1));
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        if (done || level == null) return;
        try {
            age++;
            if (age == 8) {
                require(!a.isRemoved()&&!b.isRemoved(),"test bodies remain loaded before collision checks");
                require(SynaxisNoContactCompat.put(level,a.getUniqueId(),b.getUniqueId()), "native pair created");
                ownedTag(a, b.getUniqueId());
                var user = new CompoundTag(); user.putBoolean("AstDisableStructureCollision",true); b.setUserDataTag(user);
                BearingStabilizer.refreshNoContactsNow(level);
                StabilizedTurret.LOGGER.info("Idle cleanup diagnostic: aRemoved={} bRemoved={} bound={} owned={} tag={}",
                        a.isRemoved(),b.isRemoved(),map(BearingStabilizer.class,"BOUND"),
                        map(BearingStabilizer.class,"OWNED_NO_CONTACTS"),a.getUserDataTag());
                require(!SynaxisNoContactCompat.exists(level,a.getUniqueId(),b.getUniqueId()), "orphan saved pair removed");
                require(!a.getUserDataTag().contains("CreateFireControlOwnedNoContactPeers"), "owned peers cleared from saved body");
                require(!a.getUserDataTag().getBoolean("AstDisableStructureCollision"), "legacy owned collision flag restored");
                require(b.getUserDataTag().getBoolean("AstDisableStructureCollision"), "player's own collision flag preserved");
                late = body(new BlockPos(9,180,1));
                var tag = new CompoundTag();
                tag.putBoolean("AstDisableStructureCollision",true);tag.putBoolean("StabilizedTurretOwnsNoCollision",true);
                late.setUserDataTag(tag);
            }
            if (age == 12) {
                require(!late.getUserDataTag().getBoolean("StabilizedTurretOwnsNoCollision"), "late loaded body migrated by observer");
                var discovery = map(BearingStabilizer.class, "COLLISION_DISCOVERY").get(level);
                var bodiesField=discovery.getClass().getDeclaredField("bodies");bodiesField.setAccessible(true);
                var indexed=(Map<?,?>)bodiesField.get(discovery);
                require(indexed.containsKey(late.getUniqueId()), "new body discovered");
                var id=late.getUniqueId();
                SubLevelContainer.getContainer(level).removeSubLevel(late,
                        dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
                require(!indexed.containsKey(id), "removed body released from index");
                for (int i=0;i<1000;i++) BearingStabilizer.onServerTickPost(post());
                require(map(SynaxisWeldCompat.class,"SNAPSHOTS").isEmpty(), "unrelated bodies do not activate graph maintenance");
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    @SuppressWarnings("unchecked") private static void packetChecks() throws Exception {
        var playersField=PlayerList.class.getDeclaredField("players");playersField.setAccessible(true);
        var players=(List<ServerPlayer>)playersField.get(level.getServer().getPlayerList());
        var added=new ArrayList<ServerPlayer>();
        try {
            for(String name:List.of("IdleOne","IdleTwo")) {
                var profile=new GameProfile(UUID.randomUUID(),name);
                var player=new ServerPlayer(level.getServer(),level,profile,ClientInformation.createDefault());
                var capture=new Capture(player,profile);player.connection=capture;
                players.add(player);added.add(player);
                var tick=new PlayerTickEvent.Post(player);
                ServerAimSessionHandler.onPlayerTick(tick);
                require(capture.custom==4,"initial inactive HUD sends four states");
                capture.custom=0;
                for(int i=0;i<1000;i++) ServerAimSessionHandler.onPlayerTick(tick);
                require(capture.custom==0,"1000 inactive player ticks send no redundant states");
            }
            for(Class<?> manager:List.of(MathematicalMissileManager.class,MianbaoBombProjectileHandler.class,
                    MianbaoRocketPodProjectileHandler.class,MianbaoCountermeasureManager.class)) {
                map(manager,"REMOVED").put(level,new ArrayList<>(List.of(UUID.randomUUID())));
                added.forEach(p->((Capture)p.connection).custom=0);
                runManager(manager);
                for(var player:added) require(((Capture)player.connection).custom==1,manager.getSimpleName()+" flushes final deletion to each player");
                require(!map(manager,"REMOVED").containsKey(level),"deletion queue drained");
                added.forEach(p->((Capture)p.connection).custom=0);
                runManager(manager);
                for(var player:added) require(((Capture)player.connection).custom==0,"empty manager remains silent");
            }
        } finally { players.removeAll(added); }
    }

    private static void runManager(Class<?> type) {
        if(type==MathematicalMissileManager.class)MathematicalMissileManager.sync(post());
        else if(type==MianbaoBombProjectileHandler.class)MianbaoBombProjectileHandler.tick(post());
        else if(type==MianbaoRocketPodProjectileHandler.class)MianbaoRocketPodProjectileHandler.onServerTickPost(post());
        else MianbaoCountermeasureManager.onServerTick(post());
    }

    private static void typeChecks() {
        var pig=EntityType.PIG.create(level);
        for(int i=0;i<1000;i++) {
            require(!MianbaoAirDefenseCompat.isAnyFireControlMissile(pig),"ordinary type is not a missile");
            require(!MianbaoCountermeasureCompat.isCountermeasure(pig),"ordinary type is not a flare");
        }
        for(String id:List.of("antitankmissile_1tanshe","antiairmissiletanshe","farmissile_1tanshe")) {
            var type=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("mianbaos_modernwarfare:"+id)).orElseThrow();
            var entity=type.create(level);
            require(MianbaoAirDefenseCompat.isAnyFireControlMissile(entity),"native type recognized: "+id);
        }
        var flare=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("mianbaos_modernwarfare:countermeasure")).orElseThrow().create(level);
        require(MianbaoCountermeasureCompat.isCountermeasure(flare),"native flare recognized after negative cache entries");
    }

    @SuppressWarnings("unchecked") private static Map<Object,Object> map(Class<?> owner,String name) throws Exception {
        var field=owner.getDeclaredField(name);field.setAccessible(true);return (Map<Object,Object>)field.get(null);
    }
    private static ServerSubLevel body(BlockPos pos) throws Exception {
        level.setBlockAndUpdate(pos,Blocks.IRON_BLOCK.defaultBlockState());
        var result=SimAssemblyHelper.assembleFromSingleBlock(level,pos.below(),pos,false,false);
        require(result!=null,"physical body assembled");
        var body=(ServerSubLevel)result.subLevel();
        SubLevelContainer.getContainer(level).addForceLoadTicket(body,
                dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                net.minecraft.util.Unit.INSTANCE);
        return body;
    }
    private static void ownedTag(ServerSubLevel body,UUID peer) {
        var tag=new CompoundTag();var peers=new ListTag();var p=new CompoundTag();p.putUUID("Peer",peer);peers.add(p);
        tag.put("CreateFireControlOwnedNoContactPeers",peers);tag.putBoolean("AstDisableStructureCollision",true);
        tag.putBoolean("StabilizedTurretOwnsNoCollision",true);body.setUserDataTag(tag);
    }
    private static ServerTickEvent.Post post(){return new ServerTickEvent.Post(()->true,level.getServer());}
    private static final class Capture extends ServerGamePacketListenerImpl {
        int custom;
        Capture(ServerPlayer player,GameProfile profile){super(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(profile,false));}
        @Override public void send(Packet<?> packet){if(packet instanceof ClientboundCustomPayloadPacket)custom++;}
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void finish(Throwable error){
        done=true;if(error!=null)error.printStackTrace();
        try{Files.writeString(Path.of("idle-integration-result.txt"),error==null?"PASS: "+checks+" checks\n":"FAIL: "+error+"\n");}
        catch(Exception failure){throw new RuntimeException(failure);}
        if(level!=null)level.getServer().halt(false);
    }
}
