package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;

import java.util.UUID;

/** A world sight ray whose origin rides a physical body, intersecting the selected block face. */
public record MovingAimRay(UUID bodyId, Vec3 localOrigin, Vec3 worldDirection,
                           Vec3 surfacePoint, Vec3 surfaceNormal, Vec3 worldOffset,
                           @Nullable PhysicalSurface physicalSurface) {
    public record PhysicalSurface(UUID bodyId, Vec3 localPoint, Vec3 localNormal) {
        public PhysicalSurface {
            if (bodyId == null || !finite(localPoint) || !finite(localNormal) || localNormal.lengthSqr() < 1e-12)
                throw new IllegalArgumentException("Invalid physical sight surface");
            localNormal = localNormal.normalize();
        }
    }

    public MovingAimRay(UUID bodyId, Vec3 localOrigin, Vec3 worldDirection,
                        Vec3 surfacePoint, Vec3 surfaceNormal, Vec3 worldOffset) {
        this(bodyId, localOrigin, worldDirection, surfacePoint, surfaceNormal, worldOffset, null);
    }
    public MovingAimRay(UUID bodyId,Vec3 localOrigin,Vec3 worldDirection,Vec3 surfacePoint,Vec3 surfaceNormal) {
        this(bodyId,localOrigin,worldDirection,surfacePoint,surfaceNormal,Vec3.ZERO);
    }
    public MovingAimRay {
        if (bodyId == null || !finite(localOrigin) || !finite(worldDirection)
                || !finite(surfacePoint) || !finite(surfaceNormal) || !finite(worldOffset)
                || worldDirection.lengthSqr() < 1e-12 || surfaceNormal.lengthSqr() < 1e-12)
            throw new IllegalArgumentException("Invalid moving sight ray");
        worldDirection = worldDirection.normalize();
        surfaceNormal = surfaceNormal.normalize();
    }

    public @Nullable Vec3 currentTarget(Level level) {
        var body = SubLevelContainer.getContainer(level).getSubLevel(bodyId);
        if (body == null || body.isRemoved()) return null;
        var surface = surfacePose(level);
        if (physicalSurface != null && surface == null) return null;
        return intersect(body.logicalPose().transformPosition(vector(localOrigin)).add(vector(worldOffset)), surface);
    }

    /** A new depth/face is operator acquisition even when the view direction is unchanged. */
    public boolean sameSurface(MovingAimRay other) {
        if (other == null || !bodyId.equals(other.bodyId)) return false;
        if (physicalSurface != null || other.physicalSurface != null) {
            return physicalSurface != null && other.physicalSurface != null
                    && physicalSurface.bodyId.equals(other.physicalSurface.bodyId)
                    && physicalSurface.localNormal.distanceToSqr(other.physicalSurface.localNormal) < 1e-12
                    && Math.abs(other.physicalSurface.localPoint.subtract(physicalSurface.localPoint)
                    .dot(physicalSurface.localNormal)) < 1e-6;
        }
        return surfaceNormal.distanceToSqr(other.surfaceNormal)<1e-12
                && Math.abs(other.surfacePoint.subtract(surfacePoint).dot(surfaceNormal))<1e-6;
    }

    public @Nullable Vec3 predictedTarget(ArticulatedServoPredictor.BodyState body,
                                          Vector3d modelOrigin, Vector3d localCOM) {
        return body == null ? null : intersect(body.position().add(modelOrigin)
                .add(body.orientation().transform(vector(localOrigin).sub(localCOM))).add(vector(worldOffset)));
    }

    public @Nullable Vec3 predictedTarget(ArticulatedServoPredictor.BodyState body,
                                          Vector3d modelOrigin, Vector3d localCOM, Level level) {
        var surface = surfacePose(level);
        if (physicalSurface != null && surface == null) return null;
        return body == null ? null : intersect(body.position().add(modelOrigin)
                .add(body.orientation().transform(vector(localOrigin).sub(localCOM))).add(vector(worldOffset)), surface);
    }

    private @Nullable Pose3dc surfacePose(Level level) {
        if (physicalSurface == null) return null;
        var surfaceBody = SubLevelContainer.getContainer(level).getSubLevel(physicalSurface.bodyId);
        return surfaceBody == null || surfaceBody.isRemoved() ? null : surfaceBody.logicalPose();
    }

    private @Nullable Vec3 intersect(Vector3d origin) {
        return intersect(origin, null);
    }

    public @Nullable Vec3 intersect(Vector3d origin, @Nullable Pose3dc surfacePose) {
        if (!origin.isFinite()) return null;
        Vec3 point = surfacePoint, normal = surfaceNormal;
        if (physicalSurface != null && surfacePose != null) {
            point = surfacePose.transformPosition(physicalSurface.localPoint);
            Vector3d transformed = surfacePose.transformNormal(vector(physicalSurface.localNormal));
            normal = new Vec3(transformed.x, transformed.y, transformed.z).normalize();
        }
        double denominator = worldDirection.dot(normal);
        if (Math.abs(denominator) < 1e-8) return null;
        Vec3 start = new Vec3(origin.x,origin.y,origin.z);
        double distance = point.subtract(start).dot(normal)/denominator;
        if (!(distance > 0 && distance <= AimRayLimits.MAX_TARGET_DISTANCE)) return null;
        return start.add(worldDirection.scale(distance));
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    private static Vector3d vector(Vec3 v) { return new Vector3d(v.x,v.y,v.z); }
}
