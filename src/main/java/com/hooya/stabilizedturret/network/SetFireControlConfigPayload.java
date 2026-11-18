package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetFireControlConfigPayload(CompoundTag values) implements CustomPacketPayload {
    public static final Type<SetFireControlConfigPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_global_config"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetFireControlConfigPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeNbt(payload.values),
            buffer -> new SetFireControlConfigPayload(java.util.Objects.requireNonNull(buffer.readNbt())));
    public static void handle(SetFireControlConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sender) FireControlSettings.handleChanges(sender, payload.values);
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
