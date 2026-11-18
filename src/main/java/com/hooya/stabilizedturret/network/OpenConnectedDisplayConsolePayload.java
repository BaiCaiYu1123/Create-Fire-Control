package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayConsoleBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Full state snapshot used both to open and refresh the remote console UI. */
public record OpenConnectedDisplayConsolePayload(
        BlockPos consolePos,
        UUID consoleSubLevelId,
        CompoundTag displayState,
        double radarX,
        double radarY,
        double radarZ,
        double displayX,
        double displayZ
) implements CustomPacketPayload {
    public static final Type<OpenConnectedDisplayConsolePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "open_connected_display_console"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenConnectedDisplayConsolePayload> STREAM_CODEC = StreamCodec.of(
            OpenConnectedDisplayConsolePayload::write,
            OpenConnectedDisplayConsolePayload::read);

    public static OpenConnectedDisplayConsolePayload from(
            ConnectedDisplayConsoleBlockEntity console,
            ConnectedDisplayBlockEntity display
    ) {
        BindingRef consoleRef = BindingRef.capture(console.getLevel(), console.getBlockPos());
        Vec3 radar = display.getRadarWorldOrigin();
        Vec3 displayWorld = Sable.HELPER.projectOutOfSubLevel(
                display.getLevel(), Vec3.atCenterOf(display.getBlockPos()));
        CompoundTag state = display.getUpdateTag(display.getLevel().registryAccess());
        return new OpenConnectedDisplayConsolePayload(
                console.getBlockPos(), consoleRef.subLevelId(), state,
                radar.x, radar.y, radar.z, displayWorld.x, displayWorld.z);
    }

    private static void write(RegistryFriendlyByteBuf buffer,
                              OpenConnectedDisplayConsolePayload payload) {
        buffer.writeBlockPos(payload.consolePos);
        buffer.writeBoolean(payload.consoleSubLevelId != null);
        if (payload.consoleSubLevelId != null) buffer.writeUUID(payload.consoleSubLevelId);
        buffer.writeNbt(payload.displayState);
        buffer.writeDouble(payload.radarX);
        buffer.writeDouble(payload.radarY);
        buffer.writeDouble(payload.radarZ);
        buffer.writeDouble(payload.displayX);
        buffer.writeDouble(payload.displayZ);
    }

    private static OpenConnectedDisplayConsolePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        CompoundTag state = buffer.readNbt();
        if (state == null) state = new CompoundTag();
        return new OpenConnectedDisplayConsolePayload(pos, subLevelId, state,
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
    }

    public static void handle(OpenConnectedDisplayConsolePayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.openConnectedDisplayConsole(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
