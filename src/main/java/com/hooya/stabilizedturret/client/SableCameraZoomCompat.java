package com.hooya.stabilizedturret.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/**
 * Accesses Sable's mixin-added camera zoom state without an illegal
 * cross-mixin {@code @Shadow}. The field only exists after Camera has been
 * transformed, so runtime reflection is the correct compatibility boundary.
 */
public final class SableCameraZoomCompat {
    private static final String ZOOM_FIELD = "sable$zoomAmount";
    private static final float SABLE_DISTANCE_OFFSET = 1.75F;
    private static final float MINIMUM_DISTANCE_FACTOR = 0.01F;
    private static final float MINIMUM_ZOOM =
            MINIMUM_DISTANCE_FACTOR - SABLE_DISTANCE_OFFSET;
    private static volatile Field zoomField;
    private static volatile boolean lookupFailed;

    public static boolean isEligible(Minecraft minecraft) {
        return isSableContraptionCamera(minecraft)
                && findSeatSubLevel(minecraft) != null;
    }

    public static boolean isSableContraptionCamera(Minecraft minecraft) {
        if (minecraft == null) return false;
        var cameraType = minecraft.options.getCameraType();
        return cameraType == SableCameraTypes.SUB_LEVEL_VIEW
                || cameraType == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED;
    }

    /**
     * Resolves the physical structure carrying the player's current seat.
     * This deliberately does not consult {@link ClientAimState}: the Sable
     * camera remains available whether fire control, a scope, or a remote
     * camera session is active or not.
     */
    public static SubLevel findSeatSubLevel(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) return null;

        Entity rider = minecraft.cameraEntity != null
                ? minecraft.cameraEntity
                : minecraft.player;
        if (rider == null || rider.getVehicle() == null) return null;

        SubLevel subLevel = Sable.HELPER.getVehicleSubLevel(rider);
        if (subLevel != null) return subLevel;

        // During the short client synchronization window the helper may not
        // have linked the rider yet, while the seat already lives in a plot.
        Entity vehicle = rider.getVehicle();
        return Sable.HELPER.getContaining(
                minecraft.level, vehicle.position());
    }

    public static boolean applyScroll(
            Minecraft minecraft, Camera camera, double scrollDelta
    ) {
        if (!isEligible(minecraft) || camera == null
                || !Double.isFinite(scrollDelta)) return false;
        Float current = read(camera);
        if (current == null) return false;
        double sensitivity;
        try {
            sensitivity = dev.ryanhcode.sable.SableClientConfig
                    .ZOOM_SENSITIVITY.get();
        } catch (RuntimeException | LinkageError ignored) {
            sensitivity = 1.0;
        }
        double requested = current - scrollDelta * sensitivity;
        float zoom;
        if (requested >= Float.MAX_VALUE) {
            zoom = Float.MAX_VALUE;
        } else {
            zoom = (float) Math.max(MINIMUM_ZOOM, requested);
        }
        return setZoomUnclamped(minecraft, camera, zoom);
    }

    /**
     * Bypasses Sable's {@code Mth.clamp(value, 0, 4)} setter while either
     * physical-structure camera is active.
     */
    public static boolean setZoomUnclamped(
            Minecraft minecraft, Camera camera, float requestedZoom
    ) {
        if (!isEligible(minecraft) || camera == null
                || !Float.isFinite(requestedZoom)) return false;
        write(camera, Math.max(MINIMUM_ZOOM, requestedZoom));
        return true;
    }

    /**
     * Returns either Sable contraption camera's structure-relative orbit
     * distance without the mod's collision clamp.
     */
    public static float desiredDistance(
            Minecraft minecraft,
            Camera camera,
            float requestedDistance,
            float partialTick
    ) {
        SubLevel seatSubLevel = findSeatSubLevel(minecraft);
        if (camera == null || seatSubLevel == null) return requestedDistance;

        BoundingBox3ic bounds = seatSubLevel.getPlot().getBoundingBox();
        Vec3 extents = new Vec3(
                bounds.maxX() - bounds.minX(),
                bounds.maxY() - bounds.minY(),
                bounds.maxZ() - bounds.minZ());
        double baseDistance = Math.max(
                requestedDistance, extents.scale(0.5).length());
        double factor = Math.max(
                MINIMUM_DISTANCE_FACTOR,
                SABLE_DISTANCE_OFFSET
                        + interpolatedZoom(camera, partialTick));
        double distance = baseDistance * factor;
        return distance >= Float.MAX_VALUE
                ? Float.MAX_VALUE
                : (float) distance;
    }

    public static float interpolatedZoom(Camera camera, float partialTick) {
        Float previous = readNamed(camera, "sable$lastInterpolatedZoom");
        Float current = readNamed(camera, "sable$interpolatedZoom");
        if (previous != null && current != null) {
            return Mth.lerp(partialTick, previous, current);
        }
        Float raw = read(camera);
        return raw == null ? 0.0F : raw;
    }

    private static Float readNamed(Camera camera, String name) {
        try {
            Field field = camera.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getFloat(camera);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Float read(Camera camera) {
        Field field = field(camera);
        if (field == null) return null;
        try {
            return field.getFloat(camera);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static void write(Camera camera, float value) {
        Field field = field(camera);
        if (field == null) return;
        try {
            field.setFloat(camera, value);
        } catch (IllegalAccessException | RuntimeException ignored) {
        }
    }

    private static Field field(Camera camera) {
        Field cached = zoomField;
        if (cached != null) return cached;
        if (lookupFailed) return null;
        synchronized (SableCameraZoomCompat.class) {
            if (zoomField != null) return zoomField;
            try {
                Field found = camera.getClass().getDeclaredField(ZOOM_FIELD);
                found.setAccessible(true);
                zoomField = found;
                return found;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                lookupFailed = true;
                return null;
            }
        }
    }

    private SableCameraZoomCompat() {
    }
}
