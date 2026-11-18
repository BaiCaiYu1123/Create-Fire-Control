package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FireControlConfigStatePayload(CompoundTag values, boolean editable) implements CustomPacketPayload {
    public static final Type<FireControlConfigStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "global_config_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FireControlConfigStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeNbt(payload.values); buffer.writeBoolean(payload.editable); },
            buffer -> new FireControlConfigStatePayload(java.util.Objects.requireNonNull(buffer.readNbt()), buffer.readBoolean()));
    public static void handle(FireControlConfigStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> FireControlSettings.receiveSnapshot(payload.values, payload.editable));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
