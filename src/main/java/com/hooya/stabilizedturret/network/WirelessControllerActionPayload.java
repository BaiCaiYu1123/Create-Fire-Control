package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.remote.ControllerSessions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;

/** Bounded NBT is decoded by vanilla; logical contents are sanitized against the held item. */
public record WirelessControllerActionPayload(int action, CompoundTag data) implements CustomPacketPayload {
    // Action 3 belonged to the removed pairing mode; keep later IDs stable.
    public static final int SAVE = 0, INPUT = 1, STOP = 2, UNPAIR = 4, CONFIG = 5, SCROLL = 6;
    public static final Type<WirelessControllerActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "wireless_controller_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WirelessControllerActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> { buf.writeVarInt(payload.action); buf.writeNbt(payload.data); },
            buf -> new WirelessControllerActionPayload(buf.readVarInt(), buf.readNbt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(WirelessControllerActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.data != null && context.player() instanceof ServerPlayer player)
                ControllerSessions.handle(player, payload);
        });
    }
}
