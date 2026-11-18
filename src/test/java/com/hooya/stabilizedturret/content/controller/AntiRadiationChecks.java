package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

public final class AntiRadiationChecks {
    public static void main(String[] args) {
        double aspect = 2560.0 / 1440;
        var frame = new AntiRadiationTargeting.Frame(Vec3.ZERO, new Vec3(0, 0, -1),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), 1, aspect);
        require(frame.valid(), "Orthogonal real camera basis rejected");
        Vec3 reticle = new Vec3(.5, .5, 100), boxCenter = new Vec3(1211.0 / 2560, 620.0 / 1440, 100);
        require(frame.aimDistanceSquared(reticle) < frame.aimDistanceSquared(boxCenter),
                "Targets must be ranked by player reticle, not the acquisition box centre");
        var mouseFlight = frame.withAim(1100, 550);
        Vec3 flightCircle = new Vec3(1100.0 / 2560, 550.0 / 1440, 100);
        require(mouseFlight.valid() && mouseFlight.aimDistanceSquared(flightCircle) < mouseFlight.aimDistanceSquared(reticle),
                "Off-centre flight terminal circle must override the screen-centre reticle");
        require(!frame.withAim(Double.NaN, 550).valid() && !frame.withAim(1100, Double.POSITIVE_INFINITY).valid(),
                "Invalid network aiming reference accepted");
        for (int tick = 0; tick < 60; tick++) {
            require(AntiRadiationGeometry.searchFrameVisible(true, false, tick) == (tick / 10 % 2 == 0),
                    "Provisional 1 Hz search blink phase incorrect");
            require(AntiRadiationGeometry.searchFrameVisible(true, true, tick), "Locked search box blinked");
            require(!AntiRadiationGeometry.searchFrameVisible(false, false, tick), "Unarmed mode displayed its search box");
        }
        double middleX = .5, middleY = .5;
        for (double fovTan : new double[]{0.05, 0.5, 1, 2}) {
            var zoomed = new AntiRadiationTargeting.Frame(frame.origin(), frame.forward(), frame.right(), frame.up(), fovTan, aspect);
            Vec3 point = new Vec3((middleX - .5) * 2 * 100 * fovTan * aspect,
                    (.5 - middleY) * 2 * 100 * fovTan, -100);
            Vec3 projected = zoomed.project(point);
            require(projected != null && AntiRadiationGeometry.contains(projected.x, projected.y, aspect), "Visible target missed after zoom");
        }
        require(AntiRadiationGeometry.contains(1041.0 / 2560, 470.0 / 1440, aspect), "Top-left boundary changed");
        require(AntiRadiationGeometry.contains(1519.0 / 2560, 970.0 / 1440, aspect), "Bottom-right boundary changed");
        require(!AntiRadiationGeometry.contains(1040.0 / 2560, middleY, aspect), "Outside-left target acquired");
        require(!AntiRadiationGeometry.contains(middleX, 971.0 / 1440, aspect), "Outside-bottom target acquired");
        var aperture = AntiRadiationGeometry.Basis.of(frame.forward(), frame.up());
        var reference = new AntiRadiationTargeting.Frame(Vec3.ZERO, frame.forward(), frame.right(), frame.up(),
                Math.tan(Math.toRadians(35)), aspect);
        Vec3 corner = reference.projectDirection(aperture.ray(1041, 470));
        require(corner != null && Math.abs(corner.x * 2560 - 1041) < 1e-9 && Math.abs(corner.y * 1440 - 470) < 1e-9,
                "Centered frame no longer matches reference dimensions at FOV 70");
        for (double yaw : new double[]{-.4, .4}) {
            Vec3 cameraForward = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
            Vec3 cameraRight = cameraForward.cross(frame.up()).normalize();
            var freeLook = new AntiRadiationTargeting.Frame(Vec3.ZERO, cameraForward, cameraRight, frame.up(), 1, aspect);
            Vec3 center = freeLook.projectDirection(aperture.forward());
            require(center != null && Math.abs(center.x - .5) > .1, "Frame incorrectly followed player free look");
            require(aperture.contains(new Vec3(0, 0, -100)) && !aperture.contains(cameraForward.scale(100)),
                    "Search aperture rotated with camera instead of missile");
        }
        for (var basis : new AntiRadiationGeometry.Basis[]{aperture,
                AntiRadiationGeometry.Basis.of(new Vec3(1, 1, -1), new Vec3(-1, 2, 1)),
                AntiRadiationGeometry.Basis.of(new Vec3(0, 1, 0), new Vec3(0, 0, 1))}) {
            require(basis.contains(basis.forward().scale(100)) && !basis.contains(basis.forward().scale(-100)),
                    "Aircraft pitch/yaw/vertical mount accepted rear target");
            require(basis.contains(basis.ray(1041, 470).scale(100))
                    && !basis.contains(basis.ray(1040, 470).scale(100)), "Search cone disagrees with projected corner");
        }
        require(frame.project(new Vec3(0, 0, 100)) == null, "Target behind camera acquired");
        var rolled = new AntiRadiationTargeting.Frame(Vec3.ZERO, frame.forward(), frame.up(), frame.right().scale(-1), 1, aspect);
        require(rolled.valid(), "Rolled aircraft camera rejected");
        Vec3 front = rolled.project(new Vec3(0, 5, -100));
        require(front != null && front.x > .5 && Math.abs(front.y - .5) < 1e-12, "Roll not applied to screen projection");
        require(!new AntiRadiationTargeting.Frame(Vec3.ZERO, frame.forward(), frame.right(), frame.up(), Double.NaN, aspect).valid(), "Invalid FOV accepted");
        require(!new AntiRadiationTargeting.Frame(Vec3.ZERO, frame.forward(), frame.forward(), frame.up(), 1, aspect).valid(), "Non-camera basis accepted");
        for (double ratio : new double[]{4.0 / 3, 16.0 / 9, 21.0 / 9}) {
            double x = .5;
            require(AntiRadiationGeometry.contains(x, middleY, ratio), "Frame shape stretched with aspect ratio");
        }
        var profile = MianbaoMissileFlightProfile.of("antiradiationmissiletanshe");
        var type = MianbaoAircraftCompat.WeaponType.ANTI_RADIATION;
        require(MianbaoAircraftCompat.lifetimeTicks(type) == 400, "Anti-radiation lifetime must be 20 seconds");
        require(MianbaoAircraftCompat.accelerationTicks(type) == 100, "Acceleration must last 5 seconds");
        require(Math.abs(MianbaoAircraftCompat.maximumSpeedBlocksPerTick(type) - 51.045) < 1e-9, "Maximum speed must be Mach 3");
        var agm = MianbaoMissileFlightProfile.of("agm_1tanshe");
        require(profile.timer().equals(agm.timer()) && profile.motorBase() == agm.motorBase()
                && profile.motorStart() == agm.motorStart() && profile.motorEnd() == agm.motorEnd()
                && profile.large() == agm.large() && profile.laserSpark() == agm.laserSpark(),
                "ARM exhaust fix changed motor acceleration or native effect family");
        for (int tick : new int[]{1, 50, 51, 100, 200, 399, 400})
            require(profile.smoke(tick), "ARM flame/smoke stopped before expiry at tick " + tick);
        require(!profile.smoke(0) && !profile.smoke(401), "ARM exhaust emitted outside its flight lifetime");
        require(agm.smoke(50) && !agm.smoke(51), "Unrelated AGM exhaust lifetime changed");
        require(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION.ordinal() == 23, "Existing saved weapon ordinals shifted");
        require(FireControlWeaponInputs.action(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) == 44, "Existing ground-cycle binding overwritten");
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var packet = new com.hooya.stabilizedturret.network.AntiRadiationFramePayload(mouseFlight, false);
            com.hooya.stabilizedturret.network.AntiRadiationFramePayload.STREAM_CODEC.encode(buffer, packet);
            require(packet.equals(com.hooya.stabilizedturret.network.AntiRadiationFramePayload.STREAM_CODEC.decode(buffer))
                    && buffer.readableBytes() == 0, "Flight circle lost in camera frame packet");
            var mount = new AntiRadiationSearchFrame(java.util.UUID.randomUUID(), frame.forward(), frame.up(), frame.forward(), frame.up());
            var state = new com.hooya.stabilizedturret.network.AntiRadiationStatePayload(true, true, 2, new Vec3(3, 4, 5), mount);
            com.hooya.stabilizedturret.network.AntiRadiationStatePayload.STREAM_CODEC.encode(buffer, state);
            require(state.equals(com.hooya.stabilizedturret.network.AntiRadiationStatePayload.STREAM_CODEC.decode(buffer))
                    && buffer.readableBytes() == 0, "Search mode/locked point lost in state packet");
        } finally { buffer.release(); }
        System.out.println("PASS: centered 2K geometry, missile-axis cone/free look/pitch/roll, reticle/flight-circle ranking, blink/steady lock, pose packets, Mach 3/5s/20s profile and stable IDs");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
