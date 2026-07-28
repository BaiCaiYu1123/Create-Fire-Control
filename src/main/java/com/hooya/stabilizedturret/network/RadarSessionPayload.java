package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record RadarSessionPayload(
        boolean active,
        double range,
        double depression,
        double elevation,
        double hudScale,
        double hudX,
        double hudY,
        double originX,
        double originY,
        double originZ,
        float sweepDegrees,
        UUID lockedTarget,
        UUID selectedTarget,
        boolean aircraftMode,
        double forwardX, double forwardY, double forwardZ,
        double upX, double upY, double upZ,
        double ownVelocityX, double ownVelocityY, double ownVelocityZ,
        double aircraftHorizontalHalfAngle,
        double aircraftVerticalAngle,
        int selectedAircraftWeapon,
        int farAmmo, int mediumAmmo, int closeAmmo,
        int airToGroundDirectAmmo, int airToGroundTopAttackAmmo, int laserGuidedAmmo,
        double aircraftHudScale, double aircraftHudX, double aircraftHudY,
        List<Contact> contacts
) implements CustomPacketPayload {
    public static final Type<RadarSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "radar_session"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarSessionPayload> STREAM_CODEC =
            StreamCodec.of(RadarSessionPayload::write, RadarSessionPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, RadarSessionPayload payload) {
        buffer.writeBoolean(payload.active);
        buffer.writeDouble(payload.range);
        buffer.writeDouble(payload.depression);
        buffer.writeDouble(payload.elevation);
        buffer.writeDouble(payload.hudScale);
        buffer.writeDouble(payload.hudX);
        buffer.writeDouble(payload.hudY);
        buffer.writeDouble(payload.originX);
        buffer.writeDouble(payload.originY);
        buffer.writeDouble(payload.originZ);
        buffer.writeFloat(payload.sweepDegrees);
        buffer.writeBoolean(payload.lockedTarget != null);
        if (payload.lockedTarget != null) buffer.writeUUID(payload.lockedTarget);
        buffer.writeBoolean(payload.selectedTarget != null);
        if (payload.selectedTarget != null) buffer.writeUUID(payload.selectedTarget);
        buffer.writeBoolean(payload.aircraftMode);
        buffer.writeDouble(payload.forwardX); buffer.writeDouble(payload.forwardY); buffer.writeDouble(payload.forwardZ);
        buffer.writeDouble(payload.upX); buffer.writeDouble(payload.upY); buffer.writeDouble(payload.upZ);
        buffer.writeDouble(payload.ownVelocityX); buffer.writeDouble(payload.ownVelocityY); buffer.writeDouble(payload.ownVelocityZ);
        buffer.writeDouble(payload.aircraftHorizontalHalfAngle);
        buffer.writeDouble(payload.aircraftVerticalAngle);
        buffer.writeVarInt(payload.selectedAircraftWeapon);
        buffer.writeVarInt(payload.farAmmo); buffer.writeVarInt(payload.mediumAmmo); buffer.writeVarInt(payload.closeAmmo);
        buffer.writeVarInt(payload.airToGroundDirectAmmo);
        buffer.writeVarInt(payload.airToGroundTopAttackAmmo);
        buffer.writeVarInt(payload.laserGuidedAmmo);
        buffer.writeDouble(payload.aircraftHudScale); buffer.writeDouble(payload.aircraftHudX); buffer.writeDouble(payload.aircraftHudY);
        buffer.writeVarInt(Math.min(20, payload.contacts.size()));
        for (int i = 0; i < Math.min(20, payload.contacts.size()); i++) {
            Contact c = payload.contacts.get(i);
            buffer.writeUUID(c.id);
            buffer.writeDouble(c.x); buffer.writeDouble(c.y); buffer.writeDouble(c.z);
            buffer.writeDouble(c.velocityX); buffer.writeDouble(c.velocityY); buffer.writeDouble(c.velocityZ);
            buffer.writeDouble(c.minX); buffer.writeDouble(c.minY); buffer.writeDouble(c.minZ);
            buffer.writeDouble(c.maxX); buffer.writeDouble(c.maxY); buffer.writeDouble(c.maxZ);
        }
    }

    private static RadarSessionPayload read(RegistryFriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        double range = buffer.readDouble();
        double depression = buffer.readDouble();
        double elevation = buffer.readDouble();
        double hudScale = buffer.readDouble();
        double hudX = buffer.readDouble();
        double hudY = buffer.readDouble();
        double ox = buffer.readDouble(), oy = buffer.readDouble(), oz = buffer.readDouble();
        float sweep = buffer.readFloat();
        UUID locked = buffer.readBoolean() ? buffer.readUUID() : null;
        UUID selectedTarget = buffer.readBoolean() ? buffer.readUUID() : null;
        boolean aircraft = buffer.readBoolean();
        double fx=buffer.readDouble(), fy=buffer.readDouble(), fz=buffer.readDouble();
        double ux=buffer.readDouble(), uy=buffer.readDouble(), uz=buffer.readDouble();
        double vx=buffer.readDouble(), vy=buffer.readDouble(), vz=buffer.readDouble();
        double horizontal=buffer.readDouble(), vertical=buffer.readDouble();
        int selected=buffer.readVarInt(), far=buffer.readVarInt(), medium=buffer.readVarInt(), close=buffer.readVarInt();
        int direct=buffer.readVarInt(), top=buffer.readVarInt(), laser=buffer.readVarInt();
        double aircraftHudScale=buffer.readDouble(), aircraftHudX=buffer.readDouble(), aircraftHudY=buffer.readDouble();
        int count = Math.min(20, buffer.readVarInt());
        List<Contact> contacts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            contacts.add(new Contact(buffer.readUUID(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        }
        return new RadarSessionPayload(active, range, depression, elevation, hudScale, hudX, hudY, ox, oy, oz, sweep, locked, selectedTarget,
                aircraft, fx, fy, fz, ux, uy, uz, vx, vy, vz, horizontal, vertical, selected, far, medium, close,
                direct, top, laser,
                aircraftHudScale, aircraftHudX, aircraftHudY, List.copyOf(contacts));
    }

    public static RadarSessionPayload from(StabilizerControllerBlockEntity controller) {
        if (controller == null || !controller.isRadarActive()) return inactive();
        net.minecraft.world.phys.Vec3 origin = controller.getRadarOrigin();
        List<Contact> contacts = controller.getRadarContacts().stream().map(c -> new Contact(
                c.subLevelId(), c.center().x, c.center().y, c.center().z,
                c.velocity().x, c.velocity().y, c.velocity().z,
                c.bounds().minX, c.bounds().minY, c.bounds().minZ,
                c.bounds().maxX, c.bounds().maxY, c.bounds().maxZ)).toList();
        boolean aircraft = controller.isAircraftRadarMode();
        net.minecraft.world.phys.Vec3 forward = controller.getAircraftRadarForward();
        net.minecraft.world.phys.Vec3 up = controller.getAircraftRadarUp();
        net.minecraft.world.phys.Vec3 ownVelocity = controller.getAircraftVelocity();
        return new RadarSessionPayload(true, aircraft ? controller.getAircraftRadarRange() : controller.getRadarRange(),
                controller.getRadarDepression(), controller.getRadarElevation(), controller.getRadarHudScale(),
                controller.getRadarHudX(), controller.getRadarHudY(), origin.x, origin.y, origin.z,
                controller.getRadarSweepDegrees(), controller.getRadarLockedSubLevelId(), controller.getAircraftSelectedTargetId(),
                aircraft, forward.x, forward.y, forward.z, up.x, up.y, up.z,
                ownVelocity.x, ownVelocity.y, ownVelocity.z,
                controller.getAircraftRadarHorizontalHalfAngle(), controller.getAircraftRadarVerticalAngle(),
                controller.getSelectedAircraftWeapon().ordinal(),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.FAR),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.MEDIUM),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.CLOSE),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.LASER_GUIDED),
                controller.getAircraftRadarHudScale(), controller.getAircraftRadarHudX(), controller.getAircraftRadarHudY(), contacts);
    }

    public static RadarSessionPayload inactive() {
        return new RadarSessionPayload(false, 10000.0, 0.0, 75.0, 70.0, 1.0, 1.0, 0, 0, 0, 0, null, null,
                false, 0,0,-1, 0,1,0, 0,0,0, 90,16, 0,0,0,0,0,0,0, 100,98,50, List.of());
    }

    public static void handle(RadarSessionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.handleRadarSession(payload);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Contact(UUID id, double x, double y, double z,
                          double velocityX, double velocityY, double velocityZ,
                          double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {
    }
}
