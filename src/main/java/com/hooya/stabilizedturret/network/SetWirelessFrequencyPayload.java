package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Sets one half of an embedded Create wireless-redstone channel. */
public record SetWirelessFrequencyPayload(
        BlockPos pos,
        int channel,
        boolean first
) implements CustomPacketPayload {
    public static final Type<SetWirelessFrequencyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_wireless_frequency")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetWirelessFrequencyPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeVarInt(payload.channel);
                buffer.writeBoolean(payload.first);
            },
            buffer -> new SetWirelessFrequencyPayload(
                buffer.readBlockPos(),
                buffer.readVarInt(),
                    buffer.readBoolean()
            )
    );

    public static void handle(SetWirelessFrequencyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (Sable.HELPER.distanceSquaredWithSubLevels(
                    player.level(), player.position(), Vec3.atCenterOf(payload.pos)) > 64.0) {
                return;
            }
            if (player.level().getBlockEntity(payload.pos) instanceof StabilizerControllerBlockEntity controller) {
                if (payload.channel >= 0 && payload.channel < 11) {
                    controller.setFrequency(payload.channel, payload.first, player.getMainHandItem());
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
