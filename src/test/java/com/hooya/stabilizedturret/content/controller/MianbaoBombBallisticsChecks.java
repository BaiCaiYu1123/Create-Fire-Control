package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.network.BombSightPayload;
import com.hooya.stabilizedturret.network.BombFlightPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public final class MianbaoBombBallisticsChecks {
    public static void main(String[] args) throws Exception {
        require(WeaponType.AIM_120D.ordinal() == 9 && WeaponType.BOMB.ordinal() == 10, "Existing weapon ordinals changed");
        int count = 0;
        for (WeaponType type : WeaponType.values()) if (MianbaoBombCompat.isBomb(type)) count++;
        require(count == 13, "Missing bomb family");
        for (String path : new String[]{"mixedmediumbombrack_", "mixedjdamrack_", "mixedglidebomb_"}) {
            for (int ammo = 1; ammo <= 3; ammo++) require(MianbaoBombCompat.ammoAt(id(path + ammo)) == ammo, "Multi-rack count lost");
        }
        require(MianbaoBombCompat.ammoAt(id("mixedbombrack")) == 0, "Empty triple rack contains bombs");
        require(MianbaoAircraftCompat.isSameRackVariant(id("mixedjdamrack_3"), id("mixedjdamrack_2")), "Three-to-two transition loses binding");
        require(MianbaoAircraftCompat.isSameRackVariant(id("bombrack"), id("rack")), "Empty rack loses binding");
        require(!MianbaoAircraftCompat.isSameRackVariant(id("bombrack"), id("bomb")), "Standalone bomb inherited rack binding");
        Vec3 ordinary = impact(WeaponType.BOMB), retarded = impact(WeaponType.RETARDED_BOMB), glide = impact(WeaponType.GLIDE_BOMB);
        require(retarded.x < ordinary.x && glide.x > ordinary.x, "High-drag/glide ranges do not differ correctly");
        var released = flight(WeaponType.BOMB);
        Vec3[] first = {null};
        MianbaoBombBallistics.trace(released, 0, (from, to) -> { if (first[0] == null) first[0] = to; return to; });
        near(first[0], released.origin().add(released.velocity()), 1.0e-12, "Gravity applied before native first move");
        require(MianbaoBombBallistics.trace(released, 0, (a, b) -> null) == null, "Invented terrain on unknown contact");
        groundSightGeometry();
        groundSightScreenSize();
        virtualFlightChecks();
        var aimed = new MianbaoBombBallistics.Flight(WeaponType.JDAM_BOMB, Vec3.ZERO, new Vec3(5, 0, 0), true, new Vec3(500, -100, 200), List.of());
        near(MianbaoBombBallistics.requested(aimed, new Vec3(1, 0, 0), aimed.velocity(), 19), aimed.velocity().scale(0.99F), 1.0e-12, "Guidance armed too early");
        require(MianbaoBombBallistics.requested(aimed, new Vec3(1, 0, 0), aimed.velocity(), 20).z > 0, "Coordinate steering missing");
        roundTrip(BombSightPayload.inactive());
        for (WeaponType type : WeaponType.values()) if (MianbaoBombCompat.isBomb(type)) {
            var f = new MianbaoBombBallistics.Flight(type, new Vec3(10, 300, -5), new Vec3(5, -.2, 2), true,
                    new Vec3(600, 60, 200), List.of(new Vec3(100, 50, 30)));
            roundTrip(new BombSightPayload(type.ordinal(), 2, 8, f, new Vec3(100, 64, 20)));
            roundTrip(new BombSightPayload(type.ordinal(), 0, 3, null, null));
        }
        if (args.length > 0) compareNative(Path.of(args[0]));
        System.out.println("PASS: 13 bomb families, multi-rack transitions, distinct ranges, release order, guidance and bomb sight protocol");
    }

    private static void virtualFlightChecks() {
        for (WeaponType type : WeaponType.values()) if (MianbaoBombCompat.isBomb(type)) {
            require(MianbaoBombCompat.entityWeapon(MianbaoBombCompat.entityType(type)) == type, "Native model/type mapping lost " + type);
            var flight = new MianbaoBombBallistics.Flight(type, new Vec3(10, 300, -5), new Vec3(5, -.2, 2), true,
                    new Vec3(600, 60, 200), List.of(new Vec3(100, 50, 30)));
            var math = new VirtualBombFlight(type, flight.origin(), flight.velocity(), 0);
            Vec3 position = flight.origin(), velocity = flight.velocity();
            for (int age = 1; age <= 100; age++) {
                boolean water = age >= 60;
                position = position.add(velocity);
                velocity = MianbaoBombBallistics.step(velocity, MianbaoBombBallistics.requested(flight, position, velocity, age, water),
                        MianbaoBombBallistics.guided(type), type == WeaponType.GLIDE_BOMB, MianbaoBombBallistics.drag(type), water);
                math.advance(true, flight.target(), flight.lasers(), water);
                near(math.position(), position, 1.0e-10, "Server/client mathematical position mismatch");
                near(math.velocity(), velocity, 1.0e-10, "Server/client mathematical velocity mismatch");
            }
            var state = new BombFlightPayload.State(java.util.UUID.randomUUID(), MianbaoBombCompat.entityType(type), position, velocity, 100,
                    true, flight.target(), flight.lasers(), true);
            var payload = new BombFlightPayload(ResourceLocation.parse("minecraft:overworld"), 1000, List.of(state), List.of(java.util.UUID.randomUUID()));
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                BombFlightPayload.STREAM_CODEC.encode(buffer, payload);
                require(payload.equals(BombFlightPayload.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(), "Mathematical flight packet changed");
            } finally { buffer.release(); }
        }
        System.out.println("PASS: all 13 mathematical bomb flights, water transitions and native-model packet round trips");
    }

    private static void groundSightGeometry() {
        Vec3 centre = new Vec3(100, 64, -50);
        var ring = com.hooya.stabilizedturret.client.BombSightGeometry.create(centre, point -> point);
        require(ring.size() == 72, "Ground ring must contain only circle segments and its short centre cross");
        for (var segment : ring) for (Vec3 point : List.of(segment.from(), segment.to())) {
            require(point.y == centre.y, "Bomb sight became a camera-facing vertical HUD ring");
            require(point.distanceTo(centre) <= 5.0 + 1.0e-9, "Extraneous white line outside the 3D ground ring");
        }
        var slope = com.hooya.stabilizedturret.client.BombSightGeometry.create(centre,
                point -> new Vec3(point.x, centre.y + (point.x - centre.x) * .2, point.z));
        for (var segment : slope) require(Math.abs(segment.from().y - centre.y - (segment.from().x - centre.x) * .2) < 1.0e-9,
                "3D ground ring did not follow terrain elevation");
        for (Vec3 invalid : new Vec3[]{null, new Vec3(Double.NaN, 64, -50), new Vec3(100, Double.POSITIVE_INFINITY, -50),
                new Vec3(30_000_000, 64, 30_000_000), centre.add(0, 100, 0)}) {
            require(com.hooya.stabilizedturret.client.BombSightGeometry.create(centre, point -> invalid).isEmpty(),
                    "Invalid or plot-local terrain sample entered the ground mesh: " + invalid);
        }
        var partial = com.hooya.stabilizedturret.client.BombSightGeometry.create(centre,
                point -> point.x > centre.x + 2 ? point.add(1_000_000, 0, 1_000_000) : point);
        require(!partial.isEmpty() && partial.size() < 72, "Rejected terrain samples did not leave a local gap");
        for (var segment : partial) require(segment.from().distanceTo(segment.to()) < 1,
                "Ground mesh joined across rejected plot coordinates");
        var cliff = com.hooya.stabilizedturret.client.BombSightGeometry.create(centre,
                point -> point.add(0, point.x > centre.x ? 4 : 0, 0));
        require(!cliff.isEmpty() && cliff.size() < 72, "Ground mesh did not break at a cliff");
        for (var segment : cliff) require(Math.abs(segment.from().y - segment.to().y) < 1.0e-9,
                "A cliff produced a long vertical marker line");
        require(com.hooya.stabilizedturret.client.BombSightGeometry.create(new Vec3(Double.NaN, 0, 0), point -> point).isEmpty(),
                "Non-finite impact produced marker vertices");
    }

    private static void groundSightScreenSize() {
        for (double aspect : new double[]{16.0 / 9, 21.0 / 9}) for (double fov : new double[]{30, 70, 110}) {
            var projection = new org.joml.Matrix4f().perspective((float) Math.toRadians(fov), (float) aspect, .05F, 20000F);
            for (double pitch : new double[]{10, 40, 80}) for (double yaw : new double[]{0, 35}) {
                var view = new org.joml.Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw));
                for (double distance : new double[]{5, 50, 500, 4000}) {
                    var point = new org.joml.Vector3f((float) (distance * .15), (float) (-distance * .2), (float) -distance);
                    new org.joml.Matrix4f(view).invert().transformPosition(point);
                    Vec3 centre = new Vec3(point.x, point.y, point.z);
                    double radius = com.hooya.stabilizedturret.client.BombSightScale.worldRadius(centre, view, projection, aspect);
                    require(radius > 0 && Double.isFinite(radius), "No finite screen-sized ground ring");
                    var mesh = com.hooya.stabilizedturret.client.BombSightGeometry.create(centre, radius, p -> p);
                    double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
                    double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                    for (var segment : mesh) for (Vec3 vertex : List.of(segment.from(), segment.to())) {
                        var clip = new org.joml.Vector4f((float) vertex.x, (float) vertex.y, (float) vertex.z, 1).mul(view).mul(projection);
                        minX = Math.min(minX, clip.x / clip.w); maxX = Math.max(maxX, clip.x / clip.w);
                        minY = Math.min(minY, clip.y / clip.w); maxY = Math.max(maxY, clip.y / clip.w);
                    }
                    double screenFraction = Math.max((maxX - minX) * aspect, maxY - minY) * .5;
                    require(Math.abs(screenFraction * 1440 - 195) < .1,
                            "Ground circle changed its 2K screen share: " + screenFraction + " distance=" + distance + " FOV=" + fov);
                    require(Math.abs(screenFraction * 720 - 97.5) < .1, "Screen share depends on GUI/window resolution");
                }
            }
        }
        require(com.hooya.stabilizedturret.client.BombSightScale.worldRadius(new Vec3(0, 0, 10), new org.joml.Matrix4f(),
                new org.joml.Matrix4f().perspective(1, 16F / 9, .05F, 1000), 16.0 / 9) == 0, "Behind-camera marker produced inverted geometry");
        System.out.println("PASS: 3D ground circle stays 195 px at 1440p / 97.5 px at 720p across 5-4000 blocks, FOVs, pitches and aspect ratios");
    }

    private static void compareNative(Path jar) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, MianbaoBombBallisticsChecks.class.getClassLoader())) {
            var step = loader.loadClass("net.mcreator.myfirstmod.entity.BombFlightPhysics").getMethod("step", Vec3.class, Vec3.class, boolean.class, boolean.class, double.class, boolean.class);
            Random random = new Random(822);
            for (int i = 0; i < 1000; i++) {
                Vec3 v = new Vec3(random.nextDouble() * 50 - 25, random.nextDouble() * 20 - 10, random.nextDouble() * 50 - 25);
                Vec3 desired = new Vec3(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5);
                boolean guided = random.nextBoolean(), glide = random.nextBoolean(), water = random.nextBoolean();
                double drag = new double[]{.02, .0025, .0006, .0008}[i % 4];
                near(MianbaoBombBallistics.step(v, desired, guided, glide, drag, water),
                        (Vec3) step.invoke(null, v, desired, guided, glide, drag, water), 1.0e-12, "Native bomb step mismatch");
            }
            System.out.println("PASS: 1,000 flight steps match native BombFlightPhysics (including water and guided/glide classes)");
        }
    }
    private static MianbaoBombBallistics.Flight flight(WeaponType type) {
        return new MianbaoBombBallistics.Flight(type, new Vec3(0, 300, 0), new Vec3(10, 0, 0), false, Vec3.ZERO, List.of());
    }
    private static Vec3 impact(WeaponType type) {
        Vec3 hit = MianbaoBombBallistics.trace(flight(type), -64, (from, to) -> to.y <= 0 ? from.lerp(to, from.y / (from.y - to.y)) : null);
        require(hit != null, "No ground contact for " + type);
        return hit;
    }
    private static void roundTrip(BombSightPayload value) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BombSightPayload.STREAM_CODEC.encode(buffer, value);
            require(value.equals(BombSightPayload.STREAM_CODEC.decode(buffer)), "Bomb sight packet changed");
            require(!buffer.isReadable(), "Unread bomb sight bytes");
        } finally { buffer.release(); }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void near(Vec3 a, Vec3 b, double tolerance, String message) { require(a.distanceTo(b) <= tolerance, message + ": " + a + " / " + b); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
