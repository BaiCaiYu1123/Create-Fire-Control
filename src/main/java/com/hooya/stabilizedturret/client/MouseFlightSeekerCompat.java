package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.InfraredGeometry;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.joml.Vector3d;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional mouse-flight bridge. Captures the actual drawn target marker, including its stabilization and offsets. */
public final class MouseFlightSeekerCompat {
    private static RenderGuiEvent.Post sourceEvent;
    private static double targetX, targetY;
    private static boolean hasTarget;
    private static boolean rendering, hasAircraft;
    private static Vec3 aircraftOrigin, aircraftForward;
    private static Class<?> aircraftFrameType;
    private static Method originMethod, forwardMethod;
    private static boolean hasProjection, projectionVisible;
    private static Class<?> projectionType;
    private static Method xMethod, yMethod, visibleMethod;
    private static boolean hasInfraredProjection, infraredVisible, projectionApiChecked;
    private static double infraredX, infraredY;
    private static Method projectDirection, projectWorldPoint, stabilizePoint, activeController;
    private static Class<?> controllerType;
    private static Field markerDistance;
    private static Object previousInfraredPoint;

    public static void begin(RenderGuiEvent.Post event) {
        sourceEvent = event;
        hasTarget = false;
        hasAircraft = false;
        hasProjection = hasInfraredProjection = false;
        rendering = true;
    }
    public static void end(RenderGuiEvent.Post event) {
        if (sourceEvent == event) {
            if (rendering && hasAircraft && !hasTarget) {
                hasProjection = true;
                projectionVisible = false;
            }
            rendering = false;
        }
    }
    /** Read the native free-look direction into a separate IR projection; never draw or move the flight marker. */
    public static void captureInfraredAim(Vector3d direction) {
        hasInfraredProjection = rendering && hasAircraft;
        infraredVisible = false;
        if (!hasInfraredProjection || direction == null || !direction.isFinite()) {
            previousInfraredPoint = null;
            return;
        }
        try {
            if (!projectionApiChecked) {
                projectionApiChecked = true;
                Class<?> hud = Class.forName("ace.flight.neoforge.client.MouseFlightControllerClient");
                projectDirection = hud.getDeclaredMethod("projectDirection", Minecraft.class, Camera.class, Vector3d.class);
                projectWorldPoint = hud.getDeclaredMethod("projectWorldPoint", Minecraft.class, Camera.class, Vector3d.class);
                activeController = hud.getDeclaredMethod("activeControllerForCompat");
                projectDirection.setAccessible(true); projectWorldPoint.setAccessible(true); activeController.setAccessible(true);
                try {
                    Class<?> pointType = projectDirection.getReturnType();
                    stabilizePoint = hud.getDeclaredMethod("stabilizeScreenPoint", pointType, pointType);
                    stabilizePoint.setAccessible(true);
                } catch (NoSuchMethodException ignored) { /* Newer flight builds project without this deadzone. */ }
            }
            if (projectDirection == null || projectWorldPoint == null || activeController == null) return;
            Object controller = activeController.invoke(null);
            if (controller == null) return;
            if (controllerType != controller.getClass()) {
                controllerType = controller.getClass();
                markerDistance = controllerType.getField("noseCrosshairDistance");
            }
            var mc = Minecraft.getInstance();
            var camera = mc.gameRenderer.getMainCamera();
            double distance = markerDistance.getDouble(controller);
            Object point = distance > 0 ? projectWorldPoint.invoke(null, mc, camera,
                    new Vector3d(aircraftOrigin.x, aircraftOrigin.y, aircraftOrigin.z).fma(distance, direction))
                    : projectDirection.invoke(null, mc, camera, direction);
            if (stabilizePoint != null) point = stabilizePoint.invoke(null, point, previousInfraredPoint);
            if (point == null) { previousInfraredPoint = null; return; }
            if (projectionType != point.getClass()) {
                projectionType = point.getClass();
                xMethod = projectionType.getDeclaredMethod("x"); yMethod = projectionType.getDeclaredMethod("y");
                visibleMethod = projectionType.getDeclaredMethod("visible");
                xMethod.setAccessible(true); yMethod.setAccessible(true); visibleMethod.setAccessible(true);
            }
            infraredVisible = Boolean.TRUE.equals(visibleMethod.invoke(point));
            var graphics = sourceEvent.getGuiGraphics();
            infraredX = InfraredGeometry.WIDTH / 2
                    + (((Number) xMethod.invoke(point)).intValue() - graphics.guiWidth() / 2.0) * InfraredGeometry.HEIGHT / graphics.guiHeight();
            infraredY = ((Number) yMethod.invoke(point)).intValue() * InfraredGeometry.HEIGHT / graphics.guiHeight();
            previousInfraredPoint = infraredVisible ? point : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            previousInfraredPoint = null;
        }
    }
    public static void clearInfraredAim() { previousInfraredPoint = null; hasInfraredProjection = false; }
    public static void captureAircraftFrame(Object frame) {
        if (!rendering || frame == null) return;
        try {
            if (aircraftFrameType != frame.getClass()) {
                aircraftFrameType = frame.getClass();
                originMethod = aircraftFrameType.getDeclaredMethod("origin");
                forwardMethod = aircraftFrameType.getDeclaredMethod("forward");
                originMethod.setAccessible(true); forwardMethod.setAccessible(true);
            }
            Object position = originMethod.invoke(frame), direction = forwardMethod.invoke(frame);
            if (position instanceof Vector3dc p && direction instanceof Vector3dc d && p.isFinite() && d.isFinite() && d.lengthSquared() > 1e-8) {
                aircraftOrigin = new Vec3(p.x(), p.y(), p.z());
                aircraftForward = new Vec3(d.x(), d.y(), d.z()).normalize();
                hasAircraft = true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            hasAircraft = false;
        }
    }

    public static void capture(GuiGraphics graphics, int x, int y) {
        if (!rendering || sourceEvent == null || sourceEvent.getGuiGraphics() != graphics || graphics.guiHeight() <= 0) return;
        storeCenter(graphics, x, y);
        hasTarget = true; projectionVisible = true;
    }
    private static void storeCenter(GuiGraphics graphics, int x, int y) {
        targetX = InfraredGeometry.WIDTH / 2
                + (x - graphics.guiWidth() / 2.0) * InfraredGeometry.HEIGHT / graphics.guiHeight();
        targetY = y * InfraredGeometry.HEIGHT / graphics.guiHeight();
    }

    public static InfraredTargeting.Frame frame(RenderGuiEvent.Post event, InfraredTargeting.Frame camera) {
        // Event identity prevents the previous aircraft, camera, GUI scale or
        // inactive mouse-flight mode from leaving a stale marker behind.
        if (sourceEvent != event) return camera;
        var result = hasInfraredProjection ? camera.withSeeker(infraredX, infraredY)
                : hasTarget || hasProjection ? camera.withSeeker(targetX, targetY) : camera;
        if (hasInfraredProjection ? !infraredVisible : hasProjection && !projectionVisible) result = result.withoutSeeker();
        return hasAircraft ? result.withAircraft(aircraftOrigin, aircraftForward) : result;
    }
    public static boolean hasTarget(RenderGuiEvent.Post event) { return sourceEvent == event && hasTarget; }
    public static boolean hasFlightAim(RenderGuiEvent.Post event) { return sourceEvent == event && (hasTarget || hasProjection); }
    public static AntiRadiationTargeting.Frame frame(RenderGuiEvent.Post event, AntiRadiationTargeting.Frame camera) {
        return sourceEvent == event && hasTarget ? camera.withAim(targetX, targetY) : camera;
    }
    private MouseFlightSeekerCompat() {}
}
