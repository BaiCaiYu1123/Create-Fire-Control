package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.TargetLockTelemetry;
import net.minecraft.world.phys.Vec3;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Motion and unit checks independent of a running renderer or world. */
public final class TargetLockHudChecks {
    public static void main(String[] args) throws IOException {
        Vec3 target = new Vec3(0, 600, 800);
        TargetLockTelemetry approaching = TargetLockTelemetry.measure(
                Vec3.ZERO, Vec3.ZERO, target, new Vec3(0, -3, -4), 100.0);
        close(1000.0, approaching.distance(), "slant distance");
        close(100.0, approaching.closingRate(), "approaching target in m/s");
        close(500.0, approaching.heightAboveGround(), "terrain-relative height");
        close(-100.0, TargetLockTelemetry.measure(Vec3.ZERO, Vec3.ZERO, target,
                new Vec3(0, 3, 4), 100.0).closingRate(), "receding target sign");
        close(0.0, TargetLockTelemetry.measure(Vec3.ZERO, new Vec3(1, 2, 3), target,
                new Vec3(1, 2, 3), 100.0).closingRate(), "matched observer and target velocity");
        close(100.0, TargetLockTelemetry.measure(Vec3.ZERO, new Vec3(0, 3, 4), target,
                Vec3.ZERO, 100.0).closingRate(), "moving observer toward stationary target");
        close(0.0, TargetLockTelemetry.measure(Vec3.ZERO, Vec3.ZERO, target,
                Vec3.ZERO, 650.0).heightAboveGround(), "nonnegative ground clearance");
        if (!Double.isNaN(TargetLockTelemetry.measure(Vec3.ZERO, Vec3.ZERO, target,
                Vec3.ZERO, Double.NaN).heightAboveGround())) {
            throw new AssertionError("Unknown terrain must not be replaced with sea level");
        }

        Vec3 relative = new Vec3(0, 0, 1000);
        var right = TargetLockFrame.motionIndicator(new Vec3(5, 0, 0), relative);
        var fastRight = TargetLockFrame.motionIndicator(new Vec3(10, 0, 0), relative);
        close(1.0, right.x(), "rightward flight");
        close(0.0, right.y(), "horizontal flight");
        close(right.extension() * 2.0, fastRight.extension(), "speed-proportional tick length");
        var climb = TargetLockFrame.motionIndicator(new Vec3(0, 5, 0), relative);
        close(-1.0, climb.y(), "climbing flight points upward");
        var reverse = TargetLockFrame.motionIndicator(new Vec3(-5, 0, 0), relative);
        close(-1.0, reverse.x(), "reversing flight direction");
        close(0.0, TargetLockFrame.motionIndicator(Vec3.ZERO, relative).extension(),
                "stationary target has no speed tick");
        var radial = TargetLockFrame.motionIndicator(new Vec3(0, 0, 5), relative);
        close(-1.0, radial.y(), "departing target points up on the heading dial");
        close(right.extension(), radial.extension(), "radial flight retains the speed tick");
        var incoming = TargetLockFrame.motionIndicator(new Vec3(0, 0, -5), relative);
        close(1.0, incoming.y(), "incoming target points down on the heading dial");
        var turnedView = TargetLockFrame.motionIndicator(new Vec3(5, 0, 0), new Vec3(1000, 0, 0));
        close(-1.0, turnedView.y(), "heading follows observer-to-target bearing");

        close(72.0, TargetLockFrame.REFERENCE_SIDE, "reference frame side at 2K");
        for (int guiScale = 1; guiScale <= 6; guiScale++) {
            double poseScale = (1440.0 / guiScale) / TargetLockFrame.REFERENCE_HEIGHT;
            close(72.0, TargetLockFrame.REFERENCE_SIDE * poseScale * guiScale,
                    "frame screen size at GUI scale " + guiScale);
        }
        textureChecks();
        System.out.println("Target lock HUD motion, units, ground clearance and fixed proportions passed.");
    }

    private static void textureChecks() throws IOException {
        BufferedImage optical = texture("optical");
        BufferedImage ground = texture("ground_radar");
        BufferedImage aircraft = texture("aircraft_radar");
        BufferedImage contact = texture("radar_contact");
        close(ground.getWidth(), contact.getWidth(), "cyan contact frame size");
        if ((contact.getRGB(38, 38) >>> 24) != 0) {
            throw new AssertionError("Cyan contact frame centre must stay transparent");
        }
        close(77, optical.getWidth(), "optical texture with AA padding");
        int outlineInk = 0;
        for (int y = 0; y < optical.getHeight(); y++) for (int x = 0; x < optical.getWidth(); x++) {
            boolean rendered = false;
            for (var region : TargetLockFrame.OPTICAL_OUTLINE) rendered |= region.contains(x, y);
            boolean interior = x >= 7 && x < 70 && y >= 7 && y < 70;
            if (rendered && interior && (optical.getRGB(x, y) >>> 24) > 0)
                throw new AssertionError("The baked reference ring must never be rendered as a stationary lead cue");
            if (rendered && !interior && (optical.getRGB(x, y) >>> 24) > 128) outlineInk++;
        }
        if (outlineInk < 1200) throw new AssertionError("Optical outline must retain the supplied continuous frame");
        close(76, ground.getWidth(), "radar texture with AA padding");
        close(76, aircraft.getWidth(), "aircraft texture with AA padding");
        if ((aircraft.getRGB(38, 38) >>> 24) != 0 || (ground.getRGB(38, 38) >>> 24) != 0) {
            throw new AssertionError("Extracted frames must not contain the reference target or sky");
        }
        if ((ground.getRGB(38, 3) >>> 24) != 0 || (aircraft.getRGB(38, 3) >>> 24) != 0) {
            throw new AssertionError("The reference radar frame's centre gap must stay open");
        }
        BufferedImage font = texture("numerals");
        close(384, font.getWidth(), "numeral atlas width");
        close(80, font.getHeight(), "numeral atlas height");
        for (int style = 0; style < 2; style++) for (int glyph = 0; glyph < 16; glyph++) {
            int visible = 0;
            for (int y = 0; y < 40; y++) for (int x = 0; x < 24; x++) {
                if ((font.getRGB(glyph * 24 + x, style * 40 + y) >>> 24) > 128) visible++;
            }
            if (visible < 8) throw new AssertionError("Missing HUD glyph " + glyph + " in style " + style);
        }
        for (String name : new String[]{"heading_green", "heading_black", "cap_green", "cap_black"}) texture(name);
    }

    private static BufferedImage texture(String name) throws IOException {
        String resource = "/assets/create_fire_control/textures/gui/target_lock/" + name + ".png";
        try (var stream = TargetLockHudChecks.class.getResourceAsStream(resource)) {
            if (stream == null) throw new AssertionError("Missing HUD resource " + resource);
            return ImageIO.read(stream);
        }
    }

    private static void close(double expected, double actual, String label) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1.0e-6) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private TargetLockHudChecks() {}
}
