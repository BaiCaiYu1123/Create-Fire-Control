package com.hooya.stabilizedturret.network;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.ArmorMeshMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
public record ArmorMeshResultPayload(int menu,String message) implements CustomPacketPayload {
    public static final Type<ArmorMeshResultPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"armor_mesh_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArmorMeshResultPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeVarInt(p.menu);b.writeUtf(p.message,128);},b->new ArmorMeshResultPayload(b.readVarInt(),b.readUtf(128)));
    public static void handle(ArmorMeshResultPayload payload,IPayloadContext context){context.enqueueWork(()->{if(context.player().containerMenu instanceof ArmorMeshMenu menu&&menu.containerId==payload.menu)menu.result(payload.message);});}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
