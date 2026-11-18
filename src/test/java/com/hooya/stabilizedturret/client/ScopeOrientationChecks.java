package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.ScopeOrientationMath;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload.Heading;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class ScopeOrientationChecks {
    public static void main(String[] args) throws Exception {
        physicalHeadings();
        protocol();
        layout();
        preview();
        System.out.println("PASS: scope hull/turret headings, moving/inverted carriers, wraparound, protocol and HUD bounds");
    }

    private static void physicalHeadings() {
        for (Direction facing : new Direction[]{Direction.UP, Direction.DOWN}) {
            Quaterniond bearing = new Quaterniond(facing.getRotation());
            Quaterniond plate = new Quaterniond(facing.getOpposite().getRotation());
            for (double carrierYaw : new double[]{-179, -90, 0, 89, 179}) {
                Quaterniond carrier = new Quaterniond().rotationYXZ(Math.toRadians(carrierYaw), .23, -.17);
                for (double zero : new double[]{0, 27}) {
                    Quaterniond neutral = new Quaterniond(bearing).rotateY(Math.toRadians(zero))
                            .mul(new Quaterniond(plate).conjugate());
                    for (double turn : new double[]{-179, -90, -30, 0, 40, 90, 179}) {
                        Quaterniond actual = new Quaterniond(carrier).mul(bearing)
                                .rotateY(Math.toRadians(zero + turn)).mul(new Quaterniond(plate).conjugate());
                        for (double frontYaw : new double[]{-90, 0, 90, 180}) {
                            Vec3 front = direction(frontYaw, 0);
                            for (double pitch : new double[]{-30, 0, 45, 85}) {
                                Vec3 localBarrel = ScopeOrientationMath.rotate(new Quaterniond(neutral).conjugate(),
                                        direction(frontYaw, pitch));
                                Vec3 worldBarrel = ScopeOrientationMath.rotate(actual, localBarrel);
                                Vec3 measured = ScopeOrientationMath.hullForward(worldBarrel, actual, bearing, plate, zero);
                                require(measured != null && measured.distanceTo(front) < 1.0e-8,
                                        "hull front moved when the carrier, turret or barrel rotated");
                                Vec3 worldHull = ScopeOrientationMath.rotate(carrier, measured);
                                require(worldHull.distanceTo(ScopeOrientationMath.rotate(carrier, front)) < 1.0e-8,
                                        "hull must follow its own carrier pose");
                            }
                        }
                    }
                }
            }
        }
        close(2, ScopeOrientationMath.wrap(-179 - 179), "crossing the negative world-yaw seam");
        close(-2, ScopeOrientationMath.wrap(179 - -179), "crossing the positive world-yaw seam");
        close(90, ScopeOrientationMath.yaw(new Vec3(-1, 0, 0)), "rightward heading");
        close(-90, ScopeOrientationMath.yaw(new Vec3(1, 0, 0)), "leftward heading");
        require(ScopeOrientationMath.hullForward(new Vec3(0, 1, 0), new Quaterniond(),
                new Quaterniond(), new Quaterniond(), 0) == null, "vertical barrels have no invented heading");
    }

    private static Vec3 direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static void protocol() {
        Heading hull = new Heading(new UUID(1, 2), new Vec3(0, 0, 1), direction(179, 10));
        Heading turret = new Heading(new UUID(3, 4), new Vec3(1, 0, 0), direction(-179, 20));
        for (var payload : new ScopeOrientationPayload[]{new ScopeOrientationPayload(hull, turret),
                new ScopeOrientationPayload(new Heading(null, direction(90, 0), direction(90, 0)), turret),
                ScopeOrientationPayload.inactive()}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                ScopeOrientationPayload.STREAM_CODEC.encode(buffer, payload);
                require(payload.equals(ScopeOrientationPayload.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(),
                        "heading packet changed in transit");
            } finally { buffer.release(); }
        }
        for (Vec3 invalid : new Vec3[]{Vec3.ZERO, new Vec3(Double.NaN, 0, 1), new Vec3(0, 0, Double.POSITIVE_INFINITY)}) {
            try {
                new Heading(null, invalid, new Vec3(0, 0, 1));
                throw new AssertionError("invalid heading accepted");
            } catch (IllegalArgumentException expected) { /* rejected */ }
        }
    }

    private static void layout() {
        for (int[] size : new int[][]{{2560, 1440}, {1920, 1080}, {3440, 1440}, {1280, 1024},
                {800, 600}, {640, 360}, {320, 240}, {854, 480}}) {
            var layout = ScopeOrientationGeometry.layout(size[0], size[1]);
            double radius = (ScopeOrientationGeometry.RADIUS + 3) * layout.scale();
            require(layout.x() - radius > size[0] / 2.0 && layout.x() + radius < size[0], "horizontal HUD bounds");
            require(layout.y() + radius < size[1] - 20 * layout.scale(), "bottom HUD bounds");
            for (int angle = -180; angle <= 180; angle += 15) {
                ScopeOrientationGeometry.Lines inside = (x0, y0, x1, y1) -> {
                    require(Math.hypot(x0, y0) <= ScopeOrientationGeometry.RADIUS + 1e-8
                            && Math.hypot(x1, y1) <= ScopeOrientationGeometry.RADIUS + 1e-8,
                            "rotated tank escaped the indicator region");
                };
                ScopeOrientationGeometry.hull(angle, inside);
                ScopeOrientationGeometry.turret(angle, inside);
            }
        }
        double[] gunTip = new double[2];
        ScopeOrientationGeometry.turret(90, (x0, y0, x1, y1) -> {
            if (x1 > gunTip[0]) { gunTip[0] = x1; gunTip[1] = y1; }
        });
        close(46, gunTip[0], "positive yaw draws the barrel to the right");
        for (int guiScale = 1; guiScale <= 6; guiScale++) {
            var scaled = ScopeOrientationGeometry.layout(2560 / guiScale, 1440 / guiScale);
            close(1.8, scaled.scale() * guiScale, "GUI scale preserves physical indicator size");
        }
    }

    private static void preview() throws Exception {
        BufferedImage image = new BufferedImage(1120, 350, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        String[] titles = {"Aligned", "Turret right +60 deg", "Hull right +45 deg", "Turret rear +180 deg"};
        double[][] poses = {{0, 0}, {0, 60}, {45, 0}, {0, 180}};
        for (int panel = 0; panel < poses.length; panel++) {
            int left = panel * 280;
            g.setColor(panel % 2 == 0 ? new Color(26, 35, 33) : new Color(165, 154, 117));
            g.fillRect(left, 0, 280, 350);
            final double cx = left + 140, cy = 170, scale = 1.8;
            for (boolean shadow : new boolean[]{true, false}) {
                g.setStroke(new BasicStroke(shadow ? 4.5f : 1.8f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                g.setColor(shadow ? new Color(0, 0, 0, 184) : new Color(127, 138, 145));
                ScopeOrientationGeometry.Lines lines = (x0, y0, x1, y1) ->
                        g.draw(new Line2D.Double(cx + x0 * scale, cy + y0 * scale, cx + x1 * scale, cy + y1 * scale));
                ScopeOrientationGeometry.dial(lines);
                g.setColor(shadow ? new Color(0, 0, 0, 184) : Color.WHITE);
                ScopeOrientationGeometry.hull(poses[panel][0], lines);
                g.setColor(shadow ? new Color(0, 0, 0, 184) : new Color(255, 207, 100));
                ScopeOrientationGeometry.turret(poses[panel][1], lines);
            }
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            g.drawString(titles[panel], left + 14, 32);
            g.setColor(new Color(255, 207, 100));
            g.drawString(String.format("Turret %+.0f deg", ScopeOrientationMath.wrap(poses[panel][1] - poses[panel][0])),
                    left + 72, 290);
        }
        g.dispose();
        Path output = Path.of("build/reports/scope-orientation-preview.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void close(double expected, double actual, String message) {
        require(Math.abs(expected - actual) < 1.0e-7, message + ": " + actual);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
