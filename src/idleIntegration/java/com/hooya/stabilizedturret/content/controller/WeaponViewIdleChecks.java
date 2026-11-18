package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.network.WeaponViewPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Exercise discovery of a hold before any shot, with actual server payload routing. */
public final class WeaponViewIdleChecks {
    public static int verify(ServerLevel level) {
        var profile=new GameProfile(UUID.randomUUID(),"PreheldView");
        var player=new ServerPlayer(level.getServer(),level,profile,ClientInformation.createDefault());
        var capture=new Capture(level,player,profile);player.connection=capture;
        var otherProfile=new GameProfile(UUID.randomUUID(),"OtherView");
        var other=new ServerPlayer(level.getServer(),level,otherProfile,ClientInformation.createDefault());
        var otherCapture=new Capture(level,other,otherProfile);other.connection=otherCapture;
        var controller=new Controller(level);controller.viewer=player;
        var tick=new ServerTickEvent.Post(()->true,level.getServer());
        try {
            AirborneWeaponViewHandler.observeControllerInput(controller);
            AirborneWeaponViewHandler.tick(tick);
            require(capture.views.isEmpty(),"A pre-held view has no pose before a shot exists");
            var first=missile(level);var second=missile(level);
            AirborneWeaponViewHandler.launched(controller,first);
            AirborneWeaponViewHandler.tick(tick);
            require(capture.views.size()==1&&first.getUUID().equals(capture.views.getLast().id()),"Pre-held input acquires first launched shot");
            require(otherCapture.views.isEmpty(),"View payload remains private");
            AirborneWeaponViewHandler.launched(controller,second);
            AirborneWeaponViewHandler.tick(tick);
            require(first.getUUID().equals(capture.views.getLast().id()),"Holding retains selected shot when a later shot launches");
            controller.viewer=null;AirborneWeaponViewHandler.tick(tick);
            require(capture.views.getLast().id()==null,"Release sends the camera clear");
            controller.viewer=player;AirborneWeaponViewHandler.tick(tick);
            require(second.getUUID().equals(capture.views.getLast().id()),"Repress acquires latest live shot");
            return 6;
        } finally {
            controller.setRemoved();
            AirborneWeaponViewHandler.stop(new ServerStoppingEvent(level.getServer()));
        }
    }
    private static Entity missile(ServerLevel level){
        var entity=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("mianbaos_modernwarfare:farmissile_1tanshe")).orElseThrow().create(level);
        entity.setPos(10000,230,10000);return entity;
    }
    private static final class Controller extends StabilizerControllerBlockEntity {
        ServerPlayer viewer;
        Controller(ServerLevel level){super(new BlockPos(0,230,0),ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());setLevel(level);}
        @Override public ServerPlayer getWeaponViewOperator(){return viewer;}
    }
    private static final class Capture extends ServerGamePacketListenerImpl {
        final List<WeaponViewPayload> views=new ArrayList<>();
        Capture(ServerLevel level,ServerPlayer player,GameProfile profile){super(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(profile,false));}
        @Override public void send(Packet<?> packet){if(packet instanceof ClientboundCustomPayloadPacket custom&&custom.payload() instanceof WeaponViewPayload view)views.add(view);}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
