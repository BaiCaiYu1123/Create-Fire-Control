package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.RemoteTerminalDetector;
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

public record SetRemoteKeyBindingPayload(
        BlockPos pos, int action, boolean advanced, String key
) implements CustomPacketPayload {
    public static final Type<SetRemoteKeyBindingPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_remote_key_binding")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRemoteKeyBindingPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeVarInt(payload.action);
                buffer.writeBoolean(payload.advanced);
                buffer.writeUtf(payload.key, 64);
            },
            buffer -> new SetRemoteKeyBindingPayload(
                    buffer.readBlockPos(), buffer.readVarInt(), buffer.readBoolean(), buffer.readUtf(64))
    );

    public static void handle(SetRemoteKeyBindingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || payload.action < 0 || payload.action >= 11) {
                return;
            }
            if (Sable.HELPER.distanceSquaredWithSubLevels(
                    player.level(), player.position(), Vec3.atCenterOf(payload.pos)) > 64.0) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.pos) instanceof StabilizerControllerBlockEntity controller)) {
                return;
            }
            RemoteControllerCompat.Type type = payload.advanced
                    ? RemoteControllerCompat.Type.ADVANCED : RemoteControllerCompat.Type.NORMAL;
            if (!payload.key.isEmpty() && !RemoteTerminalDetector.hasTerminal(controller, player.level(), type)) {
                return;
            }
            controller.setRemoteKey(type, payload.action, payload.key);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
