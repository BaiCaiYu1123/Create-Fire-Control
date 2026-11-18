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

public record OpenTigerIEnginePayload(BlockPos pos, UUID subLevelId, boolean advancedHubConnected,
        boolean engineEnabled, EngineControlSettings controls, boolean wirelessHub) implements CustomPacketPayload {
    public OpenTigerIEnginePayload(BlockPos pos, UUID subLevelId, boolean advancedHubConnected, boolean engineEnabled, EngineControlSettings controls) {this(pos, subLevelId, advancedHubConnected, engineEnabled, controls,false);}
    public static final Type<OpenTigerIEnginePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_tiger_i_engine"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTigerIEnginePayload> STREAM_CODEC = StreamCodec.of(OpenTigerIEnginePayload::write, OpenTigerIEnginePayload::read);
    private static void write(RegistryFriendlyByteBuf buffer, OpenTigerIEnginePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null); if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        buffer.writeBoolean(payload.advancedHubConnected);
        buffer.writeBoolean(payload.engineEnabled);
        EngineSettingsCodec.write(buffer, payload.controls);
        buffer.writeBoolean(payload.wirelessHub);
    }
    private static OpenTigerIEnginePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new OpenTigerIEnginePayload(pos, subLevelId, buffer.readBoolean(), buffer.readBoolean(), EngineSettingsCodec.read(buffer),buffer.readBoolean());
    }
    public static void handle(OpenTigerIEnginePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openTigerIEngine(payload); });
    }
    public static OpenTigerIEnginePayload from(OpenGasTurbinePayload p, UUID subLevelId) {
        return new OpenTigerIEnginePayload(p.pos(), subLevelId, p.advancedHubConnected(), p.engineEnabled(), p.controls(),p.wirelessHub());
    }
    public OpenGasTurbinePayload settings() {
        return new OpenGasTurbinePayload(pos, advancedHubConnected, engineEnabled, controls,wirelessHub);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}