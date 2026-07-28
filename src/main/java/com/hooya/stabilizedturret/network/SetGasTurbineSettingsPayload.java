package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record SetGasTurbineSettingsPayload(
        BlockPos pos,
        float forwardSpeed,
        float reverseSpeed,
        float turnSpeed,
        float acceleration,
        float soundVolume,
        List<String> keys
) implements CustomPacketPayload {
    public static final Type<SetGasTurbineSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_gas_turbine_settings")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetGasTurbineSettingsPayload> STREAM_CODEC =
            StreamCodec.of(SetGasTurbineSettingsPayload::write, SetGasTurbineSettingsPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, SetGasTurbineSettingsPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeFloat(payload.forwardSpeed);
        buffer.writeFloat(payload.reverseSpeed);
        buffer.writeFloat(payload.turnSpeed);
        buffer.writeFloat(payload.acceleration);
        buffer.writeFloat(payload.soundVolume);
        buffer.writeVarInt(payload.keys.size());
        for (String key : payload.keys) buffer.writeUtf(key, 64);
    }

    private static SetGasTurbineSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        float forward = buffer.readFloat();
        float reverse = buffer.readFloat();
        float turn = buffer.readFloat();
        float acceleration = buffer.readFloat();
        float soundVolume = buffer.readFloat();
        int count = buffer.readVarInt();
        if (count != GasTurbineBlockEntity.INPUT_COUNT) {
            throw new IllegalArgumentException("Invalid gas turbine key count: " + count);
        }
        List<String> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++) keys.add(buffer.readUtf(64));
        return new SetGasTurbineSettingsPayload(
                pos, forward, reverse, turn, acceleration,
                soundVolume, List.copyOf(keys));
    }

    public static void handle(SetGasTurbineSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (Sable.HELPER.distanceSquaredWithSubLevels(
                    player.level(), player.position(), Vec3.atCenterOf(payload.pos)) > 64.0) {
                return;
            }
            if (player.level().getBlockEntity(payload.pos) instanceof GasTurbineBlockEntity turbine) {
                turbine.applySettings(
                        payload.keys,
                        payload.forwardSpeed,
                        payload.reverseSpeed,
                        payload.turnSpeed,
                        payload.acceleration,
                        payload.soundVolume);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
