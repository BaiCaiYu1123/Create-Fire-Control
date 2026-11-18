package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting;
import com.hooya.stabilizedturret.content.controller.AntiRadiationSearchFrame;
import com.hooya.stabilizedturret.content.controller.GroundRadarEmitters;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/** Available with the aircraft's own radar off, including installations with racks only. */
public record AntiRadiationStatePayload(boolean selected, boolean enabled, int ammo, @Nullable Vec3 target,
                                        @Nullable AntiRadiationSearchFrame searchFrame)
        implements CustomPacketPayload {
    public static final Type<AntiRadiationStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "anti_radiation_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AntiRadiationStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.selected);
                buffer.writeBoolean(payload.enabled);
                buffer.writeVarInt(payload.ammo);
                buffer.writeBoolean(payload.target != null);
                if (payload.target != null) {
                    buffer.writeDouble(payload.target.x); buffer.writeDouble(payload.target.y); buffer.writeDouble(payload.target.z);
                }
                buffer.writeBoolean(payload.searchFrame != null);
                if (payload.searchFrame != null) {
                    var frame = payload.searchFrame;
                    buffer.writeBoolean(frame.bodyId() != null);
                    if (frame.bodyId() != null) buffer.writeUUID(frame.bodyId());
                    writeVec(buffer, frame.localForward()); writeVec(buffer, frame.localUp());
                    writeVec(buffer, frame.forward()); writeVec(buffer, frame.up());
                }
            }, buffer -> {
                boolean selected = buffer.readBoolean(), enabled = buffer.readBoolean();
                int ammo = buffer.readVarInt();
                Vec3 target = buffer.readBoolean() ? readVec(buffer) : null;
                AntiRadiationSearchFrame frame = buffer.readBoolean()
                        ? new AntiRadiationSearchFrame(buffer.readBoolean() ? buffer.readUUID() : null,
                        readVec(buffer), readVec(buffer), readVec(buffer), readVec(buffer)) : null;
                return new AntiRadiationStatePayload(selected, enabled, ammo, target, frame);
            });

    private static void writeVec(RegistryFriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z);
    }
    private static Vec3 readVec(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public static AntiRadiationStatePayload from(@Nullable StabilizerControllerBlockEntity controller) {
        if (controller == null) return inactive();
        boolean selected = AntiRadiationTargeting.selected(controller);
        if (!selected) AntiRadiationTargeting.clear(controller);
        var radar = selected ? AntiRadiationTargeting.target(controller) : null;
        return new AntiRadiationStatePayload(selected, AntiRadiationTargeting.enabled(controller),
                controller.getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION),
                GroundRadarEmitters.position(controller.getLevel(), radar),
                selected ? AntiRadiationSearchFrame.from(controller) : null);
    }

    public static AntiRadiationStatePayload inactive() { return new AntiRadiationStatePayload(false, false, 0, null, null); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(AntiRadiationStatePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT)
            context.enqueueWork(() -> ClientPayloadHandlers.handleAntiRadiation(payload));
    }
}
