package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AssignBearingRolePayload(
        BlockPos controllerPos,
        String sourceRoleName,
        int index,
        BlockPos targetPos,
        String roleName
) implements CustomPacketPayload {
    public static final Type<AssignBearingRolePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "assign_bearing_role")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AssignBearingRolePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.controllerPos);
                        buffer.writeUtf(payload.sourceRoleName, 32);
                        buffer.writeVarInt(payload.index);
                        buffer.writeBlockPos(payload.targetPos);
                        buffer.writeUtf(payload.roleName, 32);
                    },
                    buffer -> new AssignBearingRolePayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(32),
                            buffer.readVarInt(),
                            buffer.readBlockPos(),
                            buffer.readUtf(32)
                    )
            );

    public static void handle(AssignBearingRolePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !C2SWriteAuthorization.canEditNearby(player, payload.controllerPos)) {
                return;
            }
            BindingSlot sourceRole;
            BindingSlot role;
            try {
                sourceRole = BindingSlot.valueOf(payload.sourceRoleName);
                role = BindingSlot.valueOf(payload.roleName);
            } catch (IllegalArgumentException exception) {
                return;
            }
            if (player.level().getBlockEntity(payload.controllerPos)
                    instanceof StabilizerControllerBlockEntity controller) {
                controller.assignBearingRole(
                        sourceRole, payload.index, payload.targetPos, role
                );
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
