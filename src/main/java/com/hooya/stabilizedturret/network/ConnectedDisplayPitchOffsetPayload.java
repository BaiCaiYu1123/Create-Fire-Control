package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Updates one connected-display elevation bearing's local three-axis offset. */
public record ConnectedDisplayPitchOffsetPayload(
        BlockPos displayPos,
        UUID displaySubLevelId,
        BlockPos pitchPos,
        double x,
        double y,
        double z
) implements CustomPacketPayload {
    public static final Type<ConnectedDisplayPitchOffsetPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                    "connected_display_pitch_offset"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConnectedDisplayPitchOffsetPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeBlockPos(payload.displayPos);
                buffer.writeBoolean(payload.displaySubLevelId != null);
                if (payload.displaySubLevelId != null) buffer.writeUUID(payload.displaySubLevelId);
                buffer.writeBlockPos(payload.pitchPos);
                buffer.writeDouble(payload.x);
                buffer.writeDouble(payload.y);
                buffer.writeDouble(payload.z);
            }, buffer -> new ConnectedDisplayPitchOffsetPayload(
                    buffer.readBlockPos(),
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBlockPos(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));

    public static void handle(ConnectedDisplayPitchOffsetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || player.isSpectator() || !player.mayBuild()
                    || !finite(payload.x, payload.y, payload.z)) return;
            Level lookup = player.level();
            if (payload.displaySubLevelId != null) {
                var sub = SubLevelContainer.getContainer(lookup)
                        .getSubLevel(payload.displaySubLevelId);
                if (sub == null || sub.isRemoved()) return;
                lookup = sub.getLevel();
            }
            if (!lookup.hasChunkAt(payload.displayPos)
                    || !(lookup.getBlockEntity(payload.displayPos)
                    instanceof ConnectedDisplayBlockEntity display)) return;
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(
                    lookup, payload.displayPos.getCenter());
            if (!finite(world.x, world.y, world.z)
                    || player.distanceToSqr(world) > 8.0 * 8.0) return;
            display.setPitchOffset(payload.pitchPos,
                    new Vec3(payload.x, payload.y, payload.z));
        });
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
