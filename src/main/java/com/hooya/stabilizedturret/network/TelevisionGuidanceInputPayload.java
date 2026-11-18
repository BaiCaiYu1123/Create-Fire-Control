package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoOpticalMissileHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Independent mouse-look direction used only by the active television-guided missile. */
public record TelevisionGuidanceInputPayload(double x, double y, double z)
        implements CustomPacketPayload {
    public static final Type<TelevisionGuidanceInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "television_guidance_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TelevisionGuidanceInputPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeDouble(payload.x);
                buffer.writeDouble(payload.y);
                buffer.writeDouble(payload.z);
            },
            buffer -> new TelevisionGuidanceInputPayload(
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
    );

    public static void handle(
            TelevisionGuidanceInputPayload payload, IPayloadContext context
    ) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MianbaoOpticalMissileHandler.acceptGuidanceDirection(
                    player, new Vec3(payload.x, payload.y, payload.z));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
