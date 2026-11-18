package com.hooya.stabilizedturret.content.controller;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.UUID;

public final class InfraredMissileChecks {
    public static void main(String[] args) throws Exception {
        for (double aspect : new double[]{4.0 / 3, 16.0 / 9, 21.0 / 9}) {
            double x = InfraredGeometry.screenX(InfraredGeometry.CENTER_X, aspect);
            double y = InfraredGeometry.CENTER_Y / InfraredGeometry.HEIGHT;
            require(InfraredGeometry.contains(x, y, aspect), "circle centre rejected");
            require(InfraredGeometry.contains(InfraredGeometry.screenX(InfraredGeometry.CENTER_X + InfraredGeometry.RADIUS - .001, aspect), y, aspect), "inside rim rejected");
            require(!InfraredGeometry.contains(InfraredGeometry.screenX(InfraredGeometry.CENTER_X + InfraredGeometry.RADIUS + .001, aspect), y, aspect), "outside rim acquired");
            near(InfraredGeometry.referenceX(InfraredGeometry.screenX(InfraredGeometry.SEEKER_X, aspect), aspect), InfraredGeometry.SEEKER_X, "aspect round trip");
            for (double fov : new double[]{.05, .5, 1, 2}) {
                var frame = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(0, 0, -1),
                        new Vec3(1, 0, 0), new Vec3(0, 1, 0), fov, aspect);
                require(frame.valid(), "camera basis invalid");
                var projected = frame.project(frame.direction(InfraredGeometry.SEEKER_X, InfraredGeometry.SEEKER_Y).scale(1000));
                near(InfraredGeometry.referenceX(projected.x, aspect), InfraredGeometry.SEEKER_X, "acquisition does not match drawn circle x");
                near(projected.y * InfraredGeometry.HEIGHT, InfraredGeometry.SEEKER_Y, "acquisition does not match drawn circle y");
                require(frame.project(new Vec3(0, 0, 10)) == null, "rear target projected");
                for (double[] point : new double[][]{{1280, 500}, {1664, 360}, {900, 700}}) {
                    var moved = frame.withSeeker(point[0], point[1]);
                    require(moved.valid(), "mouse-flight marker frame invalid");
                    Vec3 aimed = moved.project(moved.direction(moved.seekerX(), moved.seekerY()).scale(1000));
                    near(InfraredGeometry.referenceX(aimed.x, aspect), point[0], "mouse-flight acquisition x differs from HUD");
                    near(aimed.y * InfraredGeometry.HEIGHT, point[1], "mouse-flight acquisition y differs from HUD");
                    var rolled = new InfraredTargeting.Frame(frame.origin(), frame.forward(), frame.up(), frame.right().scale(-1),
                            fov, aspect, point[0], point[1]);
                    require(rolled.valid(), "rolled flight camera rejected");
                    Vec3 rotated = rolled.project(rolled.direction(point[0], point[1]).scale(1000));
                    near(InfraredGeometry.referenceX(rotated.x, aspect), point[0], "rolled mouse-flight marker x mismatch");
                    near(rotated.y * InfraredGeometry.HEIGHT, point[1], "rolled mouse-flight marker y mismatch");
                }
                require(!frame.withSeeker(Double.NaN, 500).valid() && !frame.withSeeker(1000, Double.POSITIVE_INFINITY).valid(),
                        "invalid network acquisition centre accepted");
            }
        }
        var networkFrame = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(1, 0, 0),
                new Vec3(0, 1, 0), .7, 16.0 / 9, 1664, 360).withAircraft(new Vec3(50, 30, -10), new Vec3(1, 0, 0));
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var payload = new com.hooya.stabilizedturret.network.InfraredFramePayload(networkFrame.withoutSeeker(), 42);
            com.hooya.stabilizedturret.network.InfraredFramePayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = com.hooya.stabilizedturret.network.InfraredFramePayload.STREAM_CODEC.decode(buffer);
            require(decoded.equals(payload) && buffer.readableBytes() == 0, "network frame lost the mouse-flight acquisition point or epoch");
        } finally { buffer.release(); }
        var aligned = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(1, 0, 0),
                new Vec3(0, 1, 0), Math.tan(Math.toRadians(35)), 16.0 / 9);
        for (int angle = 0; angle < 360; angle += 15) {
            Vec3 boundary = aligned.rangeBoundary(Math.toRadians(angle));
            near(boundary.dot(aligned.aircraftForward()), 0, "hemisphere equator is not at 90 degrees");
            near(boundary.length(), 2000, "hemisphere equator lost the 2km radius");
            require(aligned.project(boundary) == null, "physical equator unexpectedly became a perspective HUD circle");
        }
        var guide = aligned.rangeHudCircle();
        near(guide.x, 1280, "aligned guide centre x"); near(guide.y, 720, "aligned guide centre y");
        near(guide.z, InfraredGeometry.RADIUS, "reference guide radius");
        for (double degrees : new double[]{-90, -60, 0, 60, 90, 120, 180}) {
            double angle = Math.toRadians(degrees);
            Vec3 cameraForward = new Vec3(Math.sin(angle), 0, -Math.cos(angle));
            var camera = new InfraredTargeting.Frame(Vec3.ZERO, cameraForward, cameraForward.cross(new Vec3(0, 1, 0)),
                    new Vec3(0, 1, 0), aligned.halfTanY(), aligned.aspect()).withAircraft(Vec3.ZERO, aligned.aircraftForward());
            Vec3 circle = camera.rangeHudCircle();
            if (degrees == 180) { require(circle == null, "rear-only frustum kept the forward guide"); continue; }
            near(circle.x, 1280 - degrees / 90 * circle.z, "guide failed to follow aircraft-relative view angle");
            near(circle.y, 720, "horizontal view moved guide vertically");
            require(Double.isFinite(circle.z) && circle.z >= InfraredGeometry.RADIUS && circle.z <= 4 * InfraredGeometry.RADIUS,
                    "view-dependent radius is unbounded or shrank below the reference");
            if (degrees != 0) require(circle.z > guide.z, "turning the view left the guide diameter fixed");
            var pitched = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(0, Math.sin(angle), -Math.cos(angle)),
                    new Vec3(1, 0, 0), new Vec3(0, Math.cos(angle), Math.sin(angle)), aligned.halfTanY(), aligned.aspect())
                    .withAircraft(Vec3.ZERO, aligned.aircraftForward()).rangeHudCircle();
            if (Math.abs(degrees) <= 90) {
                require(pitched != null, "visible pitched guide missing");
                near(pitched.z, circle.z, "yaw and pitch give different diameter at the same view angle");
            }
            var shifted = camera.withAircraft(new Vec3(300, 20, 40), camera.aircraftForward()).rangeHudCircle();
            near(shifted.distanceTo(circle), 0, "2km range or camera parallax changed angular guide");
        }
        var zoomGuide = new InfraredTargeting.Frame(Vec3.ZERO, aligned.forward(), aligned.right(), aligned.up(),
                aligned.halfTanY() * 2, aligned.aspect()).rangeHudCircle();
        near(zoomGuide.z, guide.z * .5, "FOV scale diverged from reference calibration");
        double lastRadius = guide.z;
        for (int degrees = 1; degrees <= 90; degrees++) {
            double angle = Math.toRadians(degrees);
            Vec3 cameraForward = new Vec3(Math.sin(angle), 0, -Math.cos(angle));
            var frame = new InfraredTargeting.Frame(Vec3.ZERO, cameraForward, cameraForward.cross(aligned.up()), aligned.up(),
                    aligned.halfTanY(), aligned.aspect()).withAircraft(Vec3.ZERO, aligned.aircraftForward());
            Vec3 circle = frame.rangeHudCircle();
            require(circle != null && circle.z > lastRadius && circle.z - lastRadius < 12,
                    "view diameter jumped or failed to grow continuously at " + degrees);
            var rolled = new InfraredTargeting.Frame(Vec3.ZERO, cameraForward, aligned.up(), frame.right().scale(-1),
                    frame.halfTanY(), frame.aspect()).withAircraft(Vec3.ZERO, aligned.aircraftForward()).rangeHudCircle();
            near(rolled.z, circle.z, "camera roll changed diameter without changing view angle");
            near(Math.hypot(rolled.x-1280, rolled.y-720), Math.hypot(circle.x-1280, circle.y-720), "roll distorted circular guide offset");
            lastRadius = circle.z;
        }
        var noseEast = aligned.withAircraft(Vec3.ZERO, new Vec3(1, 0, 0));
        Vec3 inNose = new Vec3(1000, 0, 0), alongView = new Vec3(-500, 0, -1000);
        require(noseEast.inRange(inNose) && !noseEast.inRange(alongView), "maximum acquisition cone follows the camera instead of the nose");
        var lookEast = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0), .2, 16.0 / 9)
                .withAircraft(noseEast.aircraftOrigin(), noseEast.aircraftForward());
        require(lookEast.inRange(inNose) && !lookEast.inRange(alongView), "free-look or FOV changed aircraft-relative eligibility");
        require(noseEast.project(inNose) == null && noseEast.inRange(inNose), "looking away must not lose a target still in the nose cone");
        require(!noseEast.withAircraft(Vec3.ZERO, new Vec3(-1, 0, 0)).inRange(inNose), "turning the actual nose away did not lose range");
        require(!noseEast.withAircraft(new Vec3(2000, 0, 0), new Vec3(1, 0, 0)).valid(), "unbounded aircraft origin accepted");
        for (double angle : new double[]{0, .4, -.7, 1.2, -1.5, Math.PI}) {
            Vec3 forward = new Vec3(Math.sin(angle), 0, -Math.cos(angle));
            var view = new InfraredTargeting.Frame(new Vec3(3, 7, 12), forward, forward.cross(new Vec3(0, 1, 0)),
                    new Vec3(0, 1, 0), .7, 16.0 / 9).withAircraft(Vec3.ZERO, new Vec3(0, 0, -1));
            for (double[] point : new double[][]{{1280, 720}, {-12000, -8000}, {9000, 19000}, {2500, 720}, {1280, -2000}}) {
                Vec3 limited = view.limitSeeker(point[0], point[1]);
                if (angle == 0)
                    require(limited != null, "visible cone failed to constrain a seeker centre");
                if (limited == null) continue;
                Vec3 hudCircle = view.rangeHudCircle();
                require(hudCircle != null && Math.hypot(limited.x - hudCircle.x, limited.y - hudCircle.y) <= hudCircle.z + 1e-6,
                        "seeker centre escaped the visible circular guide");
                Vec3 ray = view.direction(limited.x, limited.y);
                Vec3 offset = view.origin().subtract(view.aircraftOrigin());
                double along = offset.dot(ray);
                double distance = -along + Math.sqrt(along * along + 2000 * 2000 - offset.lengthSqr());
                Vec3 onSphere = offset.add(ray.scale(distance));
                require(distance > 0 && onSphere.dot(view.aircraftForward()) >= -1e-6,
                        "search/tracking centre escaped the actual projected gimbal rim");
                Vec3 twice = view.limitSeeker(limited.x, limited.y);
                require(twice != null && twice.distanceTo(limited) < 1e-5, "centre clamp drifted when applied twice");
            }
        }
        var side = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1),
                new Vec3(0, 1, 0), .7, 16.0 / 9).withAircraft(Vec3.ZERO, new Vec3(0, 0, -1));
        var edge = side.limitSeeker(6000, 720);
        near(edge.y, 720, "horizontal seeker limit changed vertical aim");
        near(edge.x, 1280, "side-view seeker did not stop at the 90-degree equator");
        require(aligned.limitSeeker(Double.NaN, 700) == null, "nonfinite seeker centre accepted");
        require(InfraredSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(2000, 0, 0), 1), "2km boundary rejected");
        require(!InfraredSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(2000.001, 0, 0), 1), "beyond 2km tracked");
        require(InfraredSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1000, 0, 999), 1), "inside gimbal rejected");
        require(!InfraredSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1000, 0, 1001), 1), "outside gimbal tracked");
        require(!InfraredSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(-10, 0, 0), 1), "rear target tracked");
        for (double angle : new double[]{0, 41, 65, 89.99, 90, 90.01, 120, 180}) for (boolean vertical : new boolean[]{false, true}) for (double sign : new double[]{-1, 1}) {
            Vec3 direction = new Vec3(Math.cos(Math.toRadians(angle)), vertical ? sign * Math.sin(Math.toRadians(angle)) : 0,
                    vertical ? 0 : sign * Math.sin(Math.toRadians(angle)));
            for (double range : new double[]{1999, 2000, 2000.01, 2001})
                require(InfraredSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), direction.scale(range), InfraredGeometry.GIMBAL_TANGENT)
                                == (angle <= 90 && range <= 2000), "hemisphere or strict 2km boundary failed: " + angle + "/" + range);
        }
        int flashes = 0; boolean previous = false;
        for (int tick = 100; tick < 140; tick++) {
            require(InfraredGeometry.blinking(tick, 100), "loss sequence ended early");
            boolean visible = InfraredGeometry.visible(tick, 100);
            if (visible && !previous) flashes++;
            previous = visible;
        }
        require(flashes == 4 && !InfraredGeometry.blinking(140, 100) && InfraredGeometry.visible(140, 100), "loss must flash exactly four times then return to green");
        require(!InfraredGeometry.blinking(100, Long.MIN_VALUE), "fresh selection has an old loss animation");
        int pulses = 0; previous = false;
        for (int tick = 0; tick < 100; tick++) {
            boolean on = InfraredGeometry.pulseVisible(tick);
            if (on && !previous) pulses++;
            previous = on;
        }
        require(pulses == 10, "search and tracking circles must continuously pulse at 2 Hz");
        require(MianbaoAircraftCompat.isInfrared(MianbaoAircraftCompat.WeaponType.CLOSE)
                && !MianbaoAircraftCompat.isRadarGuided(MianbaoAircraftCompat.WeaponType.CLOSE), "close missiles remain radar-guided");
        require(MianbaoAircraftCompat.isRadarGuided(MianbaoAircraftCompat.WeaponType.FAR)
                && MianbaoAircraftCompat.isRadarGuided(MianbaoAircraftCompat.WeaponType.AIM_120D), "other AAM guidance changed");
        UUID id = UUID.randomUUID(); Vec3 center = new Vec3(500, 100, 20);
        var initial = new InfraredSeeker.Track(id, center, new Vec3(2, 0, 0), new AABB(center, center).inflate(3), Set.of(id), 20);
        var measured = new InfraredSeeker.Track(id, center.add(6, 0, 0), Vec3.ZERO, initial.bounds(), Set.of(id), 23).withVelocityFrom(initial);
        near(measured.velocity().x, 2, "physical target velocity measurement");
        near(new InfraredSeeker.Track(id, measured.center(), Vec3.ZERO, measured.bounds(), Set.of(id), 23)
                .withVelocityFrom(measured).velocity().x, 2, "launch-tick resampling discarded inherited target velocity");
        CompoundTag data = new CompoundTag(); data.put(ActiveRadarSeeker.TAG, new CompoundTag());
        InfraredSeeker.assign(data, measured, .9);
        require(!data.contains(ActiveRadarSeeker.TAG), "infrared assignment retained a radar seeker");
        InfraredSeeker restored = new InfraredSeeker(data);
        var memory = InfraredSeeker.class.getDeclaredField("memory"); memory.setAccessible(true);
        var track = (InfraredSeeker.Track) memory.get(restored);
        require(track.id().equals(id) && track.members().equals(Set.of(id)) && track.tick() == 23, "infrared identity/structure persistence");
        near(track.center().x, measured.center().x, "restored independent seeker measurement");
        InfraredSeeker.assign(data, measured, InfraredGeometry.GIMBAL_TANGENT);
        require(data.getCompound(InfraredSeeker.TAG).getBoolean("Hemisphere")
                        && !data.getCompound(InfraredSeeker.TAG).contains("Tangent"), "hemisphere NBT must not serialize an infinite tangent");
        System.out.println("PASS: infrared +/-90 hemisphere, strict 2km, projected centre constraints, visible/hidden network frames, four loss flashes, passive routing, physical velocity and finite hemisphere NBT");
    }
    private static void near(double actual, double expected, String message) { require(Math.abs(actual - expected) < 1e-7, message + ": " + actual); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
