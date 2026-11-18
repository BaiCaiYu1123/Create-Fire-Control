package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayConsoleBlockEntity;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record RequestConnectedDisplayConsoleStatePayload(
        BlockPos consolePos,
        UUID consoleSubLevelId
) implements CustomPacketPayload {
    public static final Type<RequestConnectedDisplayConsoleStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "request_connected_display_console_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            RequestConnectedDisplayConsoleStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.consolePos);
                buffer.writeBoolean(payload.consoleSubLevelId != null);
                if (payload.consoleSubLevelId != null) buffer.writeUUID(payload.consoleSubLevelId);
            },
            buffer -> new RequestConnectedDisplayConsoleStatePayload(
                    buffer.readBlockPos(), buffer.readBoolean() ? buffer.readUUID() : null));

    public static void handle(RequestConnectedDisplayConsoleStatePayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ConnectedDisplayConsoleBlockEntity console = resolveConsole(
                    player, payload.consolePos, payload.consoleSubLevelId);
            if (console == null || !console.refreshControl(player)) return;
            ConnectedDisplayBlockEntity display = console.resolveDisplay();
            if (display != null) {
                PacketDistributor.sendToPlayer(player,
                        OpenConnectedDisplayConsolePayload.from(console, display));
            }
        });
    }

    static @Nullable ConnectedDisplayConsoleBlockEntity resolveConsole(
            ServerPlayer player, BlockPos pos, @Nullable UUID subLevelId) {
        if (player.isSpectator()) return null;
        Level lookup = player.level();
        if (subLevelId != null) {
            var subLevel = SubLevelContainer.getContainer(lookup).getSubLevel(subLevelId);
            if (subLevel == null || subLevel.isRemoved()) return null;
            lookup = subLevel.getLevel();
        }
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(lookup, Vec3.atCenterOf(pos));
        if (!Double.isFinite(world.x) || !Double.isFinite(world.y)
                || !Double.isFinite(world.z)
                || player.distanceToSqr(world) > 12.0 * 12.0
                || !lookup.hasChunkAt(pos)) return null;
        return lookup.getBlockEntity(pos) instanceof ConnectedDisplayConsoleBlockEntity console
                ? console : null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
