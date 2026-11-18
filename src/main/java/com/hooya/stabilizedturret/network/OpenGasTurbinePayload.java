package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.turbine.EngineControlSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenGasTurbinePayload(BlockPos pos, boolean advancedHubConnected,
        boolean engineEnabled, EngineControlSettings controls, boolean wirelessHub) implements CustomPacketPayload {
    public OpenGasTurbinePayload(BlockPos pos, boolean advancedHubConnected, boolean engineEnabled, EngineControlSettings controls) {this(pos, advancedHubConnected, engineEnabled, controls,false);}
    public static final Type<OpenGasTurbinePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_gas_turbine"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenGasTurbinePayload> STREAM_CODEC = StreamCodec.of(OpenGasTurbinePayload::write, OpenGasTurbinePayload::read);
    private static void write(RegistryFriendlyByteBuf buffer, OpenGasTurbinePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.advancedHubConnected);
        buffer.writeBoolean(payload.engineEnabled);
        EngineSettingsCodec.write(buffer, payload.controls);
        buffer.writeBoolean(payload.wirelessHub);
    }
    private static OpenGasTurbinePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        return new OpenGasTurbinePayload(pos, buffer.readBoolean(), buffer.readBoolean(), EngineSettingsCodec.read(buffer),buffer.readBoolean());
    }
    public static void handle(OpenGasTurbinePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openGasTurbine(payload); });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
