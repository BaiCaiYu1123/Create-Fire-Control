package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Camera metadata only. The legacy toggle bit is retained for wire compatibility and ignored. */
public record AntiRadiationFramePayload(AntiRadiationTargeting.Frame frame, boolean toggle)
        implements CustomPacketPayload {
    public static final Type<AntiRadiationFramePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "anti_radiation_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AntiRadiationFramePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                write(buffer, payload.frame.origin()); write(buffer, payload.frame.forward());
                write(buffer, payload.frame.right()); write(buffer, payload.frame.up());
                buffer.writeDouble(payload.frame.halfTanY()); buffer.writeDouble(payload.frame.aspect());
                buffer.writeDouble(payload.frame.aimX()); buffer.writeDouble(payload.frame.aimY());
                buffer.writeBoolean(payload.toggle);
            }, buffer -> new AntiRadiationFramePayload(new AntiRadiationTargeting.Frame(
                    read(buffer), read(buffer), read(buffer), read(buffer), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble()), buffer.readBoolean()));
    private static void write(RegistryFriendlyByteBuf buffer, Vec3 v) {
        buffer.writeDouble(v.x); buffer.writeDouble(v.y); buffer.writeDouble(v.z);
    }
    private static Vec3 read(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
    public static void handle(AntiRadiationFramePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var controller = ControllerTracker.findForVehicle(player.level(), player.getVehicle());
            if (controller == null) return;
            AntiRadiationTargeting.updateFrame(controller, player, payload.frame);
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
