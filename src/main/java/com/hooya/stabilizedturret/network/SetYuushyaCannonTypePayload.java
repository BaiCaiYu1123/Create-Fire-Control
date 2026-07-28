package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetYuushyaCannonTypePayload(BlockPos pos, boolean mediumCannonBarrel)
        implements CustomPacketPayload {
    public static final Type<SetYuushyaCannonTypePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "set_yuushya_cannon_type")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetYuushyaCannonTypePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.pos);
                        buffer.writeBoolean(payload.mediumCannonBarrel);
                    },
                    buffer -> new SetYuushyaCannonTypePayload(buffer.readBlockPos(), buffer.readBoolean())
            );

    public static void handle(SetYuushyaCannonTypePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !ModList.get().isLoaded("cbcmodernwarfare")
                    || !ModList.get().isLoaded("yuushya_modelling")
                    || player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64.0
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            YuushyaCannonCompat.setBarrel(level, payload.pos, payload.mediumCannonBarrel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
