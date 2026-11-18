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
import java.util.UUID;

public record OpenZtz99aDieselEnginePayload(BlockPos pos, UUID subLevelId, boolean advancedHubConnected,
        boolean engineEnabled, EngineControlSettings controls, boolean wirelessHub) implements CustomPacketPayload {
    public OpenZtz99aDieselEnginePayload(BlockPos pos, UUID subLevelId, boolean advancedHubConnected, boolean engineEnabled, EngineControlSettings controls) {this(pos, subLevelId, advancedHubConnected, engineEnabled, controls,false);}
    public static final Type<OpenZtz99aDieselEnginePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_ztz99a_diesel_engine"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenZtz99aDieselEnginePayload> STREAM_CODEC = StreamCodec.of(OpenZtz99aDieselEnginePayload::write, OpenZtz99aDieselEnginePayload::read);
    private static void write(RegistryFriendlyByteBuf buffer, OpenZtz99aDieselEnginePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null); if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        buffer.writeBoolean(payload.advancedHubConnected);
        buffer.writeBoolean(payload.engineEnabled);
        EngineSettingsCodec.write(buffer, payload.controls);
        buffer.writeBoolean(payload.wirelessHub);
    }
    private static OpenZtz99aDieselEnginePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new OpenZtz99aDieselEnginePayload(pos, subLevelId, buffer.readBoolean(), buffer.readBoolean(), EngineSettingsCodec.read(buffer),buffer.readBoolean());
    }
    public static void handle(OpenZtz99aDieselEnginePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openZtz99aDieselEngine(payload); });
    }
    public static OpenZtz99aDieselEnginePayload from(OpenGasTurbinePayload p, UUID subLevelId) {
        return new OpenZtz99aDieselEnginePayload(p.pos(), subLevelId, p.advancedHubConnected(), p.engineEnabled(), p.controls(),p.wirelessHub());
    }
    public OpenGasTurbinePayload settings() {
        return new OpenGasTurbinePayload(pos, advancedHubConnected, engineEnabled, controls,wirelessHub);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}