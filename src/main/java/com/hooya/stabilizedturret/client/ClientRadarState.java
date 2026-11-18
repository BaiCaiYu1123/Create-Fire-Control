package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.hooya.stabilizedturret.network.TargetLockTelemetry;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

public final class ClientRadarState {
    private static boolean active;
    private static double range = 3000.0;
    private static double depression = 0.0;
    private static double elevation = 75.0;
    private static double hudScale = 70.0;
    private static double hudX = 1.0;
    private static double hudY = 3.0;
    private static Vec3 origin = Vec3.ZERO;
    private static float sweepDegrees;
    private static UUID lockedTarget;
    private static UUID selectedTarget;
    private static boolean aircraftMode;
    private static Vec3 forward = new Vec3(0,0,-1), up = new Vec3(0,1,0), ownVelocity = Vec3.ZERO;
    private static double aircraftHorizontalHalfAngle=90, aircraftVerticalAngle=16;
    private static int selectedAircraftWeapon, farAmmo, mediumAmmo, closeAmmo;
    private static int airToGroundDirectAmmo, airToGroundTopAttackAmmo, laserGuidedAmmo, rocketAmmo;
    private static int opticalGuidedAmmo, micaEmAmmo, aim120dAmmo;
    private static double aircraftHudScale=50, aircraftHudX=98, aircraftHudY=50;
    private static double rwrRange=2000, rwrScale=100;
    private static List<Threat> threats = List.of();
    private static List<Contact> contacts = List.of();

    public static void accept(RadarSessionPayload payload) {
        if (!payload.active() || active != payload.active() || aircraftMode != payload.aircraftMode()) ClientRadarMemory.clear();
        active = payload.active();
        range = payload.range();
        depression = payload.depression();
        elevation = payload.elevation();
        hudScale = payload.hudScale();
        hudX = payload.hudX();
        hudY = payload.hudY();
        origin = new Vec3(payload.originX(), payload.originY(), payload.originZ());
        sweepDegrees = payload.sweepDegrees();
        lockedTarget = payload.lockedTarget();
        selectedTarget = payload.selectedTarget();
        aircraftMode = payload.aircraftMode();
        forward = new Vec3(payload.forwardX(), payload.forwardY(), payload.forwardZ());
        up = new Vec3(payload.upX(), payload.upY(), payload.upZ());
        ownVelocity = new Vec3(payload.ownVelocityX(), payload.ownVelocityY(), payload.ownVelocityZ());
        aircraftHorizontalHalfAngle = payload.aircraftHorizontalHalfAngle();
        aircraftVerticalAngle = payload.aircraftVerticalAngle();
        selectedAircraftWeapon = payload.selectedAircraftWeapon();
        farAmmo = payload.farAmmo(); mediumAmmo = payload.mediumAmmo(); closeAmmo = payload.closeAmmo();
        airToGroundDirectAmmo = payload.airToGroundDirectAmmo();
        airToGroundTopAttackAmmo = payload.airToGroundTopAttackAmmo();
        laserGuidedAmmo = payload.laserGuidedAmmo();
        rocketAmmo = payload.rocketAmmo();
        opticalGuidedAmmo = payload.opticalGuidedAmmo();
        micaEmAmmo = payload.micaEmAmmo();
        aim120dAmmo = payload.aim120dAmmo();
        aircraftHudScale=payload.aircraftHudScale(); aircraftHudX=payload.aircraftHudX(); aircraftHudY=payload.aircraftHudY();
        rwrRange = payload.rwrRange();
        rwrScale = payload.rwrScale();
        threats = payload.threats().stream().map(threat -> new Threat(
                threat.id(), threat.type(),
                new Vec3(threat.x(), threat.y(), threat.z()), threat.incoming())).toList();
        contacts = payload.contacts().stream().map(c -> new Contact(c.id(),
                 new Vec3(c.x(), c.y(), c.z()),
                 new Vec3(c.velocityX(), c.velocityY(), c.velocityZ()),
                 new AABB(c.minX(), c.minY(), c.minZ(), c.maxX(), c.maxY(), c.maxZ()),
                 c.missile(), c.telemetry())).toList();
    }

    public static boolean isActive() { return active; }
    public static double getRange() { return range; }
    public static double getDepression() { return depression; }
    public static double getElevation() { return elevation; }
    public static double getHudScale() { return hudScale; }
    public static double getHudX() { return hudX; }
    public static double getHudY() { return hudY; }
    public static Vec3 getOrigin() { return origin; }
    public static float getSweepDegrees() { return sweepDegrees; }
    public static UUID getLockedTarget() { return visibleTarget(lockedTarget); }
    public static UUID getSelectedTarget() { return visibleTarget(selectedTarget); }
    public static boolean isAircraftMode() { return aircraftMode; }
    public static Vec3 getForward() { return forward; }
    public static Vec3 getUp() { return up; }
    public static Vec3 getOwnVelocity() { return ownVelocity; }
    public static double getAircraftHorizontalHalfAngle() { return aircraftHorizontalHalfAngle; }
    public static double getAircraftVerticalAngle() { return aircraftVerticalAngle; }
    public static int getSelectedAircraftWeapon() { return selectedAircraftWeapon; }
    public static int getFarAmmo() { return farAmmo; }
    public static int getMediumAmmo() { return mediumAmmo; }
    public static int getCloseAmmo() { return closeAmmo; }
    public static int getAirToGroundDirectAmmo() { return airToGroundDirectAmmo; }
    public static int getAirToGroundTopAttackAmmo() { return airToGroundTopAttackAmmo; }
    public static int getLaserGuidedAmmo() { return laserGuidedAmmo; }
    public static int getRocketAmmo() { return rocketAmmo; }
    public static int getOpticalGuidedAmmo() { return opticalGuidedAmmo; }
    public static int getMicaEmAmmo() { return micaEmAmmo; }
    public static int getAim120dAmmo() { return aim120dAmmo; }
    public static double getAircraftHudScale() { return aircraftHudScale; }
    public static double getAircraftHudX() { return aircraftHudX; }
    public static double getAircraftHudY() { return aircraftHudY; }
    public static double getRwrRange() { return rwrRange; }
    public static double getRwrScale() { return rwrScale; }
    public static List<Threat> getThreats() { return ClientRadarVisibility.rwr(origin, threats); }
    public static List<Contact> getContacts() { return ClientRadarMemory.controller(origin, ClientRadarVisibility.controller(origin, contacts)); }

    private static UUID visibleTarget(UUID id) {
        return id != null && getContacts().stream().anyMatch(contact -> id.equals(contact.id())) ? id : null;
    }

    public record Threat(UUID id, RadarSessionPayload.ThreatType type,
                         Vec3 position, boolean incoming) {
    }

    public record Contact(UUID id, Vec3 center, Vec3 velocity, AABB bounds,
                          boolean missile, TargetLockTelemetry telemetry) {
    }

    private ClientRadarState() {
    }
}
