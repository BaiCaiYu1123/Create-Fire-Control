package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.remote.WirelessControllerClient;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WirelessControllerStatePayload(int mode, CompoundTag data) implements CustomPacketPayload {
    public static final int CONFIG = 0, ACTIVE = 1, UPDATE = 2, STOPPED = 3;
    public static final Type<WirelessControllerStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "wireless_controller_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WirelessControllerStatePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> { buf.writeVarInt(payload.mode); buf.writeNbt(payload.data); },
            buf -> new WirelessControllerStatePayload(buf.readVarInt(), buf.readNbt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(WirelessControllerStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.data != null && FMLEnvironment.dist == Dist.CLIENT) WirelessControllerClient.receive(payload);
        });
    }
}
