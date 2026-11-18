package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FireControlInputsStatePayload(BlockPos pos,CompoundTag state,boolean accepted) implements CustomPacketPayload {
    public static final Type<FireControlInputsStatePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"fire_control_inputs_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf,FireControlInputsStatePayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeBlockPos(p.pos);b.writeNbt(p.state);b.writeBoolean(p.accepted);},
        b->new FireControlInputsStatePayload(b.readBlockPos(),java.util.Objects.requireNonNull(b.readNbt()),b.readBoolean()));
    public static void handle(FireControlInputsStatePayload p,IPayloadContext context) {
        context.enqueueWork(()->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)
            com.hooya.stabilizedturret.client.ClientPayloadHandlers.handleFireControlInputs(p);});
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
