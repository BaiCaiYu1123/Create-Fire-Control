package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetCannonMountModePayload(
        BlockPos controllerPos,
        BlockPos targetPos,
        boolean podControl
) implements CustomPacketPayload {
    public static final Type<SetCannonMountModePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "set_cannon_mount_mode")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCannonMountModePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.controllerPos);
                        buffer.writeBlockPos(payload.targetPos);
                        buffer.writeBoolean(payload.podControl);
                    },
                    buffer -> new SetCannonMountModePayload(
                            buffer.readBlockPos(),
                            buffer.readBlockPos(),
                            buffer.readBoolean()
                    )
            );

    public static void handle(SetCannonMountModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || Sable.HELPER.distanceSquaredWithSubLevels(
                    player.level(), player.position(),
                    Vec3.atCenterOf(payload.controllerPos)) > 64.0) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.controllerPos)
                    instanceof StabilizerControllerBlockEntity controller)) {
                return;
            }
            boolean changed = controller.setCannonMountPodControl(
                    payload.targetPos, payload.podControl);
            player.displayClientMessage(Component.translatable(changed
                    ? (payload.podControl
                    ? "message.stabilized_turret.cannon_mount_pod_control_enabled"
                    : "message.stabilized_turret.cannon_mount_default_enabled")
                    : "message.stabilized_turret.cannon_mount_requires_assembly"), true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
