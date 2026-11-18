package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.turbine.EngineControlSettings;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

public record SetZtz99aDieselEngineSettingsPayload(BlockPos pos, UUID subLevelId, EngineControlSettings controls) implements CustomPacketPayload {
    public static final Type<SetZtz99aDieselEngineSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_ztz99a_diesel_engine_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetZtz99aDieselEngineSettingsPayload> STREAM_CODEC = StreamCodec.of(SetZtz99aDieselEngineSettingsPayload::write, SetZtz99aDieselEngineSettingsPayload::read);
    private static void write(RegistryFriendlyByteBuf buffer, SetZtz99aDieselEngineSettingsPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null); if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        EngineSettingsCodec.write(buffer, payload.controls);
    }
    private static SetZtz99aDieselEngineSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new SetZtz99aDieselEngineSettingsPayload(pos, subLevelId, EngineSettingsCodec.read(buffer));
    }
    public static void handle(SetZtz99aDieselEngineSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var engine = com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlockEntity.findLoadedForSettings(player, payload.pos, payload.subLevelId);
            if (engine != null && C2SWriteAuthorization.canEditNearby(player, engine)) engine.applySettings(payload.controls);
        });
    }
    public static SetZtz99aDieselEngineSettingsPayload from(SetGasTurbineSettingsPayload p, UUID subLevelId) {
        return new SetZtz99aDieselEngineSettingsPayload(p.pos(), subLevelId, p.controls());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}