package com.hooya.stabilizedturret.client;

import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.lang.reflect.Field;

/**
 * Accesses Sable's mixin-added camera zoom state without an illegal
 * cross-mixin {@code @Shadow}. The field only exists after Camera has been
 * transformed, so runtime reflection is the correct compatibility boundary.
 */
public final class SableCameraZoomCompat {
    private static final String ZOOM_FIELD = "sable$zoomAmount";
    private static volatile Field zoomField;
    private static volatile boolean lookupFailed;

    public static boolean isEligible(Minecraft minecraft) {
        return minecraft != null
                && minecraft.options.getCameraType()
                == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED
                && ClientAimState.isActive()
                && !ClientAimState.isRemoteControl()
                && minecraft.player != null
                && minecraft.player.getVehicle() != null;
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
        write(camera, Mth.clamp(
                current - (float) (scrollDelta * sensitivity),
                -0.875F,
                4.0F
        ));
        return true;
    }

    public static void resetExtendedZoomWhenIneligible(
            Minecraft minecraft, Camera camera
    ) {
        if (isEligible(minecraft) || camera == null) return;
        Float current = read(camera);
        if (current != null && current < 0.0F) write(camera, 0.0F);
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
