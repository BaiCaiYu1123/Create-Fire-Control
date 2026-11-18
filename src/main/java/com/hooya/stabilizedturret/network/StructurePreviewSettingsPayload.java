package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.Objects;
import java.util.UUID;

/** Exact host + role + plot UUID + local position; never invokes a global settings operation. */
public record StructurePreviewSettingsPayload(BlockPos hostPos, UUID hostBody, boolean display,
                                             String role, BlockPos pos, UUID body, PreviewBearingSettings settings)
        implements CustomPacketPayload {
    public static final Type<StructurePreviewSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "structure_preview_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StructurePreviewSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeBlockPos(p.hostPos); b.writeUUID(p.hostBody); b.writeBoolean(p.display);
                b.writeUtf(p.role, 8); b.writeBlockPos(p.pos); b.writeBoolean(p.body != null);
                if (p.body != null) b.writeUUID(p.body);
                b.writeNbt(p.settings.save());
            }, b -> new StructurePreviewSettingsPayload(b.readBlockPos(), b.readUUID(), b.readBoolean(),
                    b.readUtf(8), b.readBlockPos(), b.readBoolean() ? b.readUUID() : null,
                    PreviewBearingSettings.load(Objects.requireNonNull(b.readNbt()))));
    public static void handle(StructurePreviewSettingsPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !p.settings.finite()
                    || !(p.role.equals("YAW") || p.role.equals("PITCH"))) return;
            var host = StructurePreviewAccess.host(player, p.hostPos, p.hostBody, p.display);
            if (host == null) return;
            var d = StructurePreviewAccess.bound(host, p.role, p.pos, p.body);
            if (d == null) return;
            var s = p.settings.clamped();
            if (host instanceof StabilizerControllerBlockEntity controller)
                controller.setPreviewBearingSettings(d.slot(), d.ref(), s);
            else ((ConnectedDisplayBlockEntity)host).setPreviewBearingSettings(d.slot(), d.ref(), s);
            PacketDistributor.sendToPlayer(player, StructurePreviewStatePayload.from(host, p.hostBody, p.display));
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
