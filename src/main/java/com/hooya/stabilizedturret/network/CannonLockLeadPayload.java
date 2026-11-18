package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Independent tick snapshots, so lock telemetry and HUD styling can evolve separately. */
public record CannonLockLeadPayload(@Nullable Track optical, @Nullable Track radar) implements CustomPacketPayload {
    public static final CannonLockLeadPayload INACTIVE = new CannonLockLeadPayload(null, null);
    public static final Type<CannonLockLeadPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "cannon_lock_lead"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CannonLockLeadPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> { writeTrack(buffer, value.optical); writeTrack(buffer, value.radar); },
            buffer -> new CannonLockLeadPayload(readTrack(buffer), readTrack(buffer)));

    public enum LeadStatus { NONE, READY, NO_AMMUNITION, UNREACHABLE }

    // Lead visibility and camera capture are separate policies: a bound cannon
    // can supply a cue while a selected missile type must not move the camera.
    public record Track(@Nullable UUID id, Vec3 target, Vec3 velocity, @Nullable Vec3 aimPoint,
                        @Nullable UUID acquisitionId, LeadStatus leadStatus, boolean cameraTracking) {
        public Track(@Nullable UUID id, Vec3 target, Vec3 velocity, @Nullable Vec3 aimPoint) {
            this(id, target, velocity, aimPoint, id);
        }

        public Track(@Nullable UUID id, Vec3 target, Vec3 velocity, @Nullable Vec3 aimPoint,
                     @Nullable UUID acquisitionId) {
            this(id, target, velocity, aimPoint, acquisitionId,
                    aimPoint == null ? LeadStatus.NONE : LeadStatus.READY, true);
        }

        public Track withAcquisition(UUID acquisition) {
            return new Track(id, target, velocity, aimPoint, acquisition, leadStatus, cameraTracking);
        }

        public Track withLeadStatus(LeadStatus status) {
            return new Track(id, target, velocity, aimPoint, acquisitionId, status, cameraTracking);
        }
    }

    private static void writeTrack(RegistryFriendlyByteBuf buffer, @Nullable Track value) {
        buffer.writeBoolean(value != null);
        if (value == null) return;
        buffer.writeBoolean(value.id != null);
        if (value.id != null) buffer.writeUUID(value.id);
        writeVector(buffer, value.target);
        writeVector(buffer, value.velocity);
        buffer.writeBoolean(value.aimPoint != null);
        if (value.aimPoint != null) writeVector(buffer, value.aimPoint);
        buffer.writeBoolean(value.acquisitionId != null);
        if (value.acquisitionId != null) buffer.writeUUID(value.acquisitionId);
        buffer.writeEnum(value.leadStatus);
        buffer.writeBoolean(value.cameraTracking);
    }

    private static @Nullable Track readTrack(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return null;
        UUID id = buffer.readBoolean() ? buffer.readUUID() : null;
        Vec3 target = readVector(buffer), velocity = readVector(buffer);
        Vec3 point = buffer.readBoolean() ? readVector(buffer) : null;
        UUID acquisition = buffer.readBoolean() ? buffer.readUUID() : null;
        return new Track(id, target, velocity, point, acquisition,
                buffer.readEnum(LeadStatus.class), buffer.readBoolean());
    }

    private static void writeVector(RegistryFriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z);
    }

    private static Vec3 readVector(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public static void handle(CannonLockLeadPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.handleCannonLockLead(payload);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
