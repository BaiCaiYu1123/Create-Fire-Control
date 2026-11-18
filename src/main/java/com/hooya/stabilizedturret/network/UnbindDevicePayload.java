package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-authoritative device removal. The expected target position prevents a
 * stale GUI row from removing a different launcher after list compaction.
 */
public record UnbindDevicePayload(
        BlockPos controllerPos,
        String slotName,
        int index,
        BlockPos targetPos,
        boolean confirmed
) implements CustomPacketPayload {
    public static final Type<UnbindDevicePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "unbind_device")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, UnbindDevicePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.controllerPos);
                        buffer.writeUtf(payload.slotName);
                        buffer.writeVarInt(payload.index);
                        buffer.writeBlockPos(payload.targetPos);
                        buffer.writeBoolean(payload.confirmed);
                    },
                    buffer -> new UnbindDevicePayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(),
                            buffer.readVarInt(),
                            buffer.readBlockPos(),
                            buffer.readBoolean()
                    )
            );

    public static void handle(UnbindDevicePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!C2SWriteAuthorization.canEditNearby(player, payload.controllerPos)) {
                return;
            }
            BindingSlot slot;
            try {
                slot = BindingSlot.valueOf(payload.slotName);
            } catch (IllegalArgumentException exception) {
                return;
            }
            if ((slot == BindingSlot.YAW || slot == BindingSlot.PITCH)
                    && !payload.confirmed) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.controllerPos)
                    instanceof StabilizerControllerBlockEntity controller)) {
                return;
            }
            boolean removed = controller.unbindDevice(slot, payload.index, payload.targetPos);
            player.displayClientMessage(Component.translatable(removed
                    ? "message.create_fire_control.device_unbound"
                    : "message.create_fire_control.device_unbind_failed"), true);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new FireControlInputsStatePayload(payload.controllerPos, controller.inputUiState(), true));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
