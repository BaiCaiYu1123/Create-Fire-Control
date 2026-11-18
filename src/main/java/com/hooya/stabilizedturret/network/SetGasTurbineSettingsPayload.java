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

public record SetGasTurbineSettingsPayload(BlockPos pos, EngineControlSettings controls) implements CustomPacketPayload {
    public static final Type<SetGasTurbineSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_gas_turbine_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetGasTurbineSettingsPayload> STREAM_CODEC = StreamCodec.of(SetGasTurbineSettingsPayload::write, SetGasTurbineSettingsPayload::read);
    private static void write(RegistryFriendlyByteBuf buffer, SetGasTurbineSettingsPayload payload) {
        buffer.writeBlockPos(payload.pos);
        EngineSettingsCodec.write(buffer, payload.controls);
    }
    private static SetGasTurbineSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        return new SetGasTurbineSettingsPayload(pos, EngineSettingsCodec.read(buffer));
    }
    public static void handle(SetGasTurbineSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!C2SWriteAuthorization.canEditNearby(player, payload.pos)) return;
            if (player.level().getBlockEntity(payload.pos) instanceof GasTurbineBlockEntity engine
                    && !(engine instanceof com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlockEntity)) engine.applySettings(payload.controls);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
