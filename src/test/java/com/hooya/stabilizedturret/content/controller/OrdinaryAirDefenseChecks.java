package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

public final class OrdinaryAirDefenseChecks {
    public static void main(String[] args) {
        var profile = OrdinaryAirDefenseGuidance.speedProfile(2);
        require(profile.speedAt(0) == 2, "Launch speed changed");
        require(profile.speedAt(199) < 17.015 && profile.speedAt(200) == 17.015, "Not Mach 1 at ten seconds");
        double previous = profile.speedAt(0);
        for (int tick = 1; tick <= 400; tick++) {
            double speed = profile.speedAt(tick);
            require(speed >= previous && speed <= 17.015, "Motor is non-monotonic or exceeds Mach 1");
            previous = speed;
        }
        require(OrdinaryAirDefenseGuidance.speedProfile(50).speedAt(0) == 17.015, "Carrier launch exceeds cap");
        require(Double.isFinite(OrdinaryAirDefenseGuidance.speedProfile(Double.NaN).speedAt(200)), "Bad launch velocity leaked");
        require(OrdinaryAirDefenseGuidance.LIFETIME_TICKS == 400, "Lifetime is not twenty seconds");

        // Compare the extracted implementation against the pre-existing ATGM law.
        for (Vec3 position : new Vec3[]{new Vec3(200, 12, 40), new Vec3(-50, -10, 8), new Vec3(500, 0, 0)}) {
            for (Vec3 beam : new Vec3[]{new Vec3(1, 0, 0), new Vec3(.7, .2, .3).normalize()}) {
                for (double speed : new double[]{2, 9, 17.015}) {
                    Vec3 velocity = new Vec3(.9, .1, -.2).normalize().scale(speed);
                    Vec3 origin = new Vec3(3, 2, 1);
                    double along = Math.max(0, position.subtract(origin).dot(beam));
                    Vec3 error = origin.add(beam.scale(along)).subtract(position);
                    double length = error.length(), lateral = Math.min(speed * .22, length * .08);
                    Vec3 desired = beam.scale(speed);
                    if (length > 1e-6) desired = desired.add(error.scale(lateral / length));
                    Vec3 reference = AcceleratingMissileIntercept.rotateTowards(
                            velocity.normalize(), desired.normalize(), Math.toRadians(4)).scale(speed);
                    Vec3 actual = BeamRidingGuidance.guide(position, velocity, origin, beam, speed);
                    require(actual.distanceToSqr(reference) < 1e-24, "ATGM beam law changed");
                    require(Math.abs(actual.length() - speed) < 1e-10, "Beam steering changes motor speed");
                }
            }
        }
        for (double speed : new double[]{2, 9, 17.015}) {
            Vec3 forward = new Vec3(1, 0, 0), right = new Vec3(0, 0, 1);
            Vec3 oldTurn = BeamRidingGuidance.guide(Vec3.ZERO, forward.scale(speed), Vec3.ZERO, right, speed);
            Vec3 newTurn = OrdinaryAirDefenseGuidance.guide(Vec3.ZERO, forward.scale(speed), Vec3.ZERO, right, speed);
            require(Math.abs(angle(forward, oldTurn) - Math.toRadians(4)) < 1e-10, "ATGM turn limit changed");
            require(Math.abs(angle(forward, newTurn) - Math.toRadians(16)) < 1e-10, "Ordinary AA turn limit is not fourfold");
            require(Math.abs(newTurn.length() - speed) < 1e-10, "Extra maneuverability changed missile speed");

            Vec3 slightOffset = new Vec3(100, 0, .1);
            Vec3 oldCorrection = BeamRidingGuidance.guide(slightOffset, forward.scale(speed), Vec3.ZERO, forward, speed);
            Vec3 newCorrection = OrdinaryAirDefenseGuidance.guide(slightOffset, forward.scale(speed), Vec3.ZERO, forward, speed);
            double oldRatio = -oldCorrection.z / oldCorrection.x;
            double newRatio = -newCorrection.z / newCorrection.x;
            require(Math.abs(newRatio / oldRatio - 4) < 1e-9, "Small beam-error response is not fourfold");

            // A wide offset must permit stronger correction instead of hitting
            // the old lateral cap even after the turn limit was raised.
            Vec3 offset = new Vec3(100, 0, 1000), velocity = forward.scale(speed);
            for (int tick = 0; tick < 20; tick++) {
                velocity = OrdinaryAirDefenseGuidance.guide(offset, velocity, Vec3.ZERO, forward, speed);
            }
            require(Math.abs(-velocity.z / velocity.x - .88) < 1e-9, "Ordinary AA still uses the old lateral cap");
            require(Math.abs(velocity.length() - speed) < 1e-10, "Beam reacquisition changed motor speed");
        }
        require(turnTicks(false) == 23 && turnTicks(true) == 6, "Ninety-degree beam capture did not become about four times faster");
        System.out.println("Ordinary AA motor, lifetime, fourfold maneuverability and unchanged ATGM guidance checks passed");
    }

    private static int turnTicks(boolean ordinary) {
        Vec3 velocity = new Vec3(1, 0, 0), beam = new Vec3(0, 0, 1);
        for (int tick = 1; tick <= 100; tick++) {
            velocity = ordinary ? OrdinaryAirDefenseGuidance.guide(Vec3.ZERO, velocity, Vec3.ZERO, beam, 1)
                    : BeamRidingGuidance.guide(Vec3.ZERO, velocity, Vec3.ZERO, beam, 1);
            if (angle(velocity, beam) < 1e-7) return tick;
        }
        throw new AssertionError("Beam capture failed");
    }

    private static double angle(Vec3 from, Vec3 to) {
        return Math.acos(Math.max(-1, Math.min(1, from.normalize().dot(to.normalize()))));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
