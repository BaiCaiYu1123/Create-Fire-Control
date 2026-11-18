package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ArmorMeshSnapshotPayload(int menu,BlockPos pos,int revision,EditableArmorMesh mesh) implements CustomPacketPayload {
    public static final Type<ArmorMeshSnapshotPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"armor_mesh_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArmorMeshSnapshotPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeBlockPos(p.pos);b.writeVarInt(p.revision);p.mesh.encode(b);},
            b->new ArmorMeshSnapshotPayload(b.readVarInt(),b.readBlockPos(),b.readVarInt(),EditableArmorMesh.decode(b)));
    public static void handle(ArmorMeshSnapshotPayload payload,IPayloadContext context){context.enqueueWork(()->{
        if(context.player().containerMenu instanceof ArmorMeshMenu menu&&menu.containerId==payload.menu&&menu.pos.equals(payload.pos)&&menu.revision==payload.revision)menu.snapshot(payload.mesh);
    });}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
