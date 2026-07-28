package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record OpenGasTurbinePayload(
        BlockPos pos,
        boolean advancedHubConnected,
        boolean engineEnabled,
        float forwardSpeed,
        float reverseSpeed,
        float turnSpeed,
        float acceleration,
        float soundVolume,
        List<String> keys
) implements CustomPacketPayload {
    public static final Type<OpenGasTurbinePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_gas_turbine")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenGasTurbinePayload> STREAM_CODEC =
            StreamCodec.of(OpenGasTurbinePayload::write, OpenGasTurbinePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, OpenGasTurbinePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.advancedHubConnected);
        buffer.writeBoolean(payload.engineEnabled);
        buffer.writeFloat(payload.forwardSpeed);
        buffer.writeFloat(payload.reverseSpeed);
        buffer.writeFloat(payload.turnSpeed);
        buffer.writeFloat(payload.acceleration);
        buffer.writeFloat(payload.soundVolume);
        buffer.writeVarInt(payload.keys.size());
        for (String key : payload.keys) buffer.writeUtf(key, 64);
    }

    private static OpenGasTurbinePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        boolean connected = buffer.readBoolean();
        boolean engineEnabled = buffer.readBoolean();
        float forward = buffer.readFloat();
        float reverse = buffer.readFloat();
        float turn = buffer.readFloat();
        float acceleration = buffer.readFloat();
        float soundVolume = buffer.readFloat();
        int count = buffer.readVarInt();
        if (count < 0 || count > GasTurbineBlockEntity.INPUT_COUNT) {
            throw new IllegalArgumentException("Invalid gas turbine key count: " + count);
        }
        List<String> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++) keys.add(buffer.readUtf(64));
        while (keys.size() < GasTurbineBlockEntity.INPUT_COUNT) keys.add("");
        return new OpenGasTurbinePayload(
                pos, connected, engineEnabled, forward, reverse, turn,
                acceleration, soundVolume, List.copyOf(keys));
    }

    public static void handle(OpenGasTurbinePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.openGasTurbine(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
