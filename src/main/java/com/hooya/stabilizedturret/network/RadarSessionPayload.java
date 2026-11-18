package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.controller.RwrThreatCollector;
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
        int rocketAmmo, int opticalGuidedAmmo, int micaEmAmmo, int aim120dAmmo,
        double aircraftHudScale, double aircraftHudX, double aircraftHudY,
        double rwrRange, double rwrScale,
        List<Threat> threats,
        List<Contact> contacts
) implements CustomPacketPayload {
    private static final int MAX_THREATS = 64;
    private static final int MAX_CONTACTS = 20;

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
        buffer.writeVarInt(payload.rocketAmmo);
        buffer.writeVarInt(payload.opticalGuidedAmmo);
        buffer.writeVarInt(payload.micaEmAmmo);
        buffer.writeVarInt(payload.aim120dAmmo);
        buffer.writeDouble(payload.aircraftHudScale); buffer.writeDouble(payload.aircraftHudX); buffer.writeDouble(payload.aircraftHudY);
        buffer.writeDouble(payload.rwrRange);
        buffer.writeDouble(payload.rwrScale);
        int threatCount = Math.min(MAX_THREATS, payload.threats.size());
        buffer.writeVarInt(threatCount);
        for (int i = 0; i < threatCount; i++) {
            Threat threat = payload.threats.get(i);
            buffer.writeUUID(threat.id);
            buffer.writeVarInt(threat.type.ordinal());
            buffer.writeDouble(threat.x); buffer.writeDouble(threat.y); buffer.writeDouble(threat.z);
            buffer.writeBoolean(threat.incoming);
        }
        int contactCount = Math.min(MAX_CONTACTS, payload.contacts.size());
        buffer.writeVarInt(contactCount);
        for (int i = 0; i < contactCount; i++) {
            Contact c = payload.contacts.get(i);
            buffer.writeUUID(c.id);
            buffer.writeDouble(c.x); buffer.writeDouble(c.y); buffer.writeDouble(c.z);
            buffer.writeDouble(c.velocityX); buffer.writeDouble(c.velocityY); buffer.writeDouble(c.velocityZ);
            buffer.writeDouble(c.minX); buffer.writeDouble(c.minY); buffer.writeDouble(c.minZ);
            buffer.writeDouble(c.maxX); buffer.writeDouble(c.maxY); buffer.writeDouble(c.maxZ);
            buffer.writeBoolean(c.missile);
            TargetLockTelemetry.write(buffer, c.telemetry);
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
        int rockets=buffer.readVarInt();
        int optical=buffer.readVarInt();
        int micaEm=buffer.readVarInt();
        int aim120d=buffer.readVarInt();
        double aircraftHudScale=buffer.readDouble(), aircraftHudX=buffer.readDouble(), aircraftHudY=buffer.readDouble();
        double rwrRange = buffer.readDouble(), rwrScale = buffer.readDouble();
        int threatCount = buffer.readVarInt();
        if (threatCount < 0 || threatCount > MAX_THREATS) {
            throw new IllegalArgumentException("Invalid RWR threat count: " + threatCount);
        }
        List<Threat> threats = new ArrayList<>(threatCount);
        for (int i = 0; i < threatCount; i++) {
            UUID id = buffer.readUUID();
            int ordinal = buffer.readVarInt();
            ThreatType[] values = ThreatType.values();
            ThreatType type = ordinal >= 0 && ordinal < values.length
                    ? values[ordinal] : ThreatType.RFL;
            threats.add(new Threat(id, type,
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readBoolean()));
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CONTACTS) {
            throw new IllegalArgumentException("Invalid radar contact count: " + count);
        }
        List<Contact> contacts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            contacts.add(new Contact(buffer.readUUID(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                     buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                     buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                     buffer.readBoolean(), TargetLockTelemetry.read(buffer)));
        }
        return new RadarSessionPayload(active, range, depression, elevation, hudScale, hudX, hudY, ox, oy, oz, sweep, locked, selectedTarget,
                aircraft, fx, fy, fz, ux, uy, uz, vx, vy, vz, horizontal, vertical, selected, far, medium, close,
                direct, top, laser, rockets, optical, micaEm, aim120d,
                aircraftHudScale, aircraftHudX, aircraftHudY,
                rwrRange, rwrScale, List.copyOf(threats), List.copyOf(contacts));
    }

    public static RadarSessionPayload from(StabilizerControllerBlockEntity controller) {
        if (controller == null || !controller.isRadarActive()) return inactive();
        net.minecraft.world.phys.Vec3 origin = controller.getRadarOrigin();
        List<Contact> contacts = controller.getRadarContacts().stream().map(c -> new Contact(
                c.subLevelId(), c.center().x, c.center().y, c.center().z,
                 c.velocity().x, c.velocity().y, c.velocity().z,
                 c.bounds().minX, c.bounds().minY, c.bounds().minZ,
                 c.bounds().maxX, c.bounds().maxY, c.bounds().maxZ,
                 c.missile(), controller.getTargetLockTelemetry(c.center(), c.velocity()))).toList();
        boolean aircraft = controller.isAircraftRadarMode();
        net.minecraft.world.phys.Vec3 forward = controller.getAircraftRadarForward();
        net.minecraft.world.phys.Vec3 up = controller.getAircraftRadarUp();
        net.minecraft.world.phys.Vec3 ownVelocity = controller.getAircraftVelocity();
        List<Threat> threats = RwrThreatCollector.collect(controller).stream()
                .map(threat -> new Threat(
                        threat.id(), ThreatType.valueOf(threat.type().name()),
                        threat.position().x, threat.position().y, threat.position().z,
                        threat.incoming()))
                .toList();
        return new RadarSessionPayload(true, aircraft ? controller.getAircraftRadarRange() : controller.getRadarRange(),
                controller.getRadarDepression(), controller.getRadarElevation(), controller.getRadarHudScale(),
                controller.getRadarHudX(), controller.getRadarHudY(), origin.x, origin.y, origin.z,
                controller.getRadarSweepDegrees(), com.hooya.stabilizedturret.content.controller.RadarLockMemory.displayLock(controller), controller.getAircraftSelectedTargetId(),
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
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.ROCKET_POD),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.OPTICAL_GUIDED),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.MICA_EM),
                controller.getAircraftAmmoCount(com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.AIM_120D),
                controller.getAircraftRadarHudScale(), controller.getAircraftRadarHudX(), controller.getAircraftRadarHudY(),
                controller.getRwrRange(), controller.getRwrScale(), threats, contacts);
    }

    public static RadarSessionPayload inactive() {
        return new RadarSessionPayload(false, 10000.0, 0.0, 75.0, 70.0, 1.0, 1.0, 0, 0, 0, 0, null, null,
                false, 0,0,-1, 0,1,0, 0,0,0, 90,16, 0,0,0,0,0,0,0,0,0,0,0,
                100,98,50, 2_000,100, List.of(), List.of());
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
                          double maxX, double maxY, double maxZ,
                          boolean missile, TargetLockTelemetry telemetry) {
        public Contact(UUID id, double x, double y, double z,
                       double velocityX, double velocityY, double velocityZ,
                       double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {
            this(id, x, y, z, velocityX, velocityY, velocityZ,
                    minX, minY, minZ, maxX, maxY, maxZ, false, TargetLockTelemetry.UNAVAILABLE);
        }
    }

    public enum ThreatType { AAA, RFL, MSL }

    public record Threat(UUID id, ThreatType type, double x, double y, double z,
                         boolean incoming) {
    }
}
