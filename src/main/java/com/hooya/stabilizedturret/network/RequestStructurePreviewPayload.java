package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

public record RequestStructurePreviewPayload(BlockPos pos, UUID body, boolean display) implements CustomPacketPayload {
    public static final Type<RequestStructurePreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "request_structure_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestStructurePreviewPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.pos); b.writeUUID(p.body); b.writeBoolean(p.display); },
            b -> new RequestStructurePreviewPayload(b.readBlockPos(), b.readUUID(), b.readBoolean()));
    public static void handle(RequestStructurePreviewPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var host = StructurePreviewAccess.host(player, p.pos, p.body, p.display);
            if (host != null) PacketDistributor.sendToPlayer(player, StructurePreviewStatePayload.from(host, p.body, p.display));
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
