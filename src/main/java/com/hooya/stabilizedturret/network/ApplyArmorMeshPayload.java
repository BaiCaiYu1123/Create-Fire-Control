package com.hooya.stabilizedturret.network;

import com.caca.copypoly.*;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ApplyArmorMeshPayload(int menu,BlockPos pos,int revision,EditableArmorMesh mesh) implements CustomPacketPayload {
    public static final Type<ApplyArmorMeshPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"apply_armor_mesh"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ApplyArmorMeshPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeBlockPos(p.pos);b.writeVarInt(p.revision);p.mesh.encode(b);},
            b->new ApplyArmorMeshPayload(b.readVarInt(),b.readBlockPos(),b.readVarInt(),EditableArmorMesh.decode(b)));
    public static String apply(ServerPlayer player,ApplyArmorMeshPayload payload){
        if(!(player.containerMenu instanceof ArmorMeshMenu menu)||menu.containerId!=payload.menu||!menu.pos.equals(payload.pos)||!menu.stillValid(player)
                ||!player.level().mayInteract(player,payload.pos))return "permission";
        if(!(player.level().getBlockEntity(payload.pos) instanceof PolyBlockEntity be)||!(be instanceof PolyArmorAccess armor)||!armor.fireControl$armorDamage().damaged())return "changed";
        if(payload.revision!=menu.revision||payload.revision!=be.revision())return "changed";
        if(payload.mesh.equals(menu.snapshot()))return "";
        if(!be.tryBeginEdit())return "rate";
        try{armor.fireControl$armorDamage().install(payload.mesh);armor.fireControl$armorChanged();return "";}
        catch(IllegalArgumentException invalid){return invalid.getMessage();}
    }
    public static void handle(ApplyArmorMeshPayload payload,IPayloadContext context){context.enqueueWork(()->{
        if(context.player() instanceof ServerPlayer player)PacketDistributor.sendToPlayer(player,new ArmorMeshResultPayload(payload.menu,apply(player,payload)));
    });}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
