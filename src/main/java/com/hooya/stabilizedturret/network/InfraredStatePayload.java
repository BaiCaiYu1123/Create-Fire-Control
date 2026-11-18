package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
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

public record InfraredStatePayload(boolean selected, int epoch, int ammo, boolean locked, long lostAt,
                                   @Nullable Vec3 target, Vec3 velocity, Vec3 aircraftOrigin, Vec3 aircraftForward) implements CustomPacketPayload {
    public InfraredStatePayload(boolean selected, int epoch, int ammo, boolean locked, long lostAt, @Nullable Vec3 target, Vec3 velocity) {
        this(selected, epoch, ammo, locked, lostAt, target, velocity, Vec3.ZERO, new Vec3(0, 0, -1));
    }
    public static final Type<InfraredStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "infrared_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InfraredStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.selected); buffer.writeVarInt(payload.epoch); buffer.writeVarInt(payload.ammo);
                buffer.writeBoolean(payload.locked); buffer.writeLong(payload.lostAt);
                buffer.writeBoolean(payload.target != null);
                if (payload.target != null) {
                    buffer.writeDouble(payload.target.x); buffer.writeDouble(payload.target.y); buffer.writeDouble(payload.target.z);
                }
                buffer.writeDouble(payload.velocity.x); buffer.writeDouble(payload.velocity.y); buffer.writeDouble(payload.velocity.z);
                buffer.writeDouble(payload.aircraftOrigin.x); buffer.writeDouble(payload.aircraftOrigin.y); buffer.writeDouble(payload.aircraftOrigin.z);
                buffer.writeDouble(payload.aircraftForward.x); buffer.writeDouble(payload.aircraftForward.y); buffer.writeDouble(payload.aircraftForward.z);
            }, buffer -> new InfraredStatePayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readBoolean(), buffer.readLong(), buffer.readBoolean()
                    ? new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()) : null,
                    new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                    new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                    new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())));

    public static InfraredStatePayload inactive() {
        return new InfraredStatePayload(false, 0, 0, false, Long.MIN_VALUE, null, Vec3.ZERO);
    }
    public static InfraredStatePayload from(@Nullable StabilizerControllerBlockEntity controller) {
        if (controller == null) return inactive();
        boolean selected = InfraredTargeting.selected(controller);
        if (!selected) InfraredTargeting.clear(controller);
        var track = selected ? InfraredTargeting.target(controller) : null;
        return new InfraredStatePayload(selected, controller.getInfraredSelectionEpoch(),
                controller.getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.CLOSE), track != null,
                selected ? InfraredTargeting.lostAt(controller) : Long.MIN_VALUE,
                selected ? InfraredTargeting.displayTarget(controller) : null, track == null ? Vec3.ZERO : track.velocity(),
                controller.getInfraredAircraftOrigin(), controller.getInfraredAircraftForward());
    }
    public static void handle(InfraredStatePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) context.enqueueWork(() -> ClientPayloadHandlers.handleInfrared(payload));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
