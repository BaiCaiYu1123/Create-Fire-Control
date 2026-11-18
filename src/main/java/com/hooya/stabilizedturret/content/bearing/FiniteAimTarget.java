package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.LaserRangeCompensation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.UUID;

/** A finite world target tied to the actual muzzle body, never to a virtual point on its carrier. */
public record FiniteAimTarget(UUID bodyId, Vec3 localMuzzle, Vec3 localForward, Vec3 worldTarget,
                              @Nullable CannonBallisticsCompat.ShotProfile profile, @Nullable Source source,
                              @Nullable MovingAimRay movingRay, boolean compensateCarrierVelocity,
                              double elevationCorrection, boolean fixedElevation,
                              @Nullable Vec3 parallelSight) {
    public FiniteAimTarget(UUID bodyId, Vec3 localMuzzle, Vec3 localForward, Vec3 worldTarget,
                          @Nullable CannonBallisticsCompat.ShotProfile profile, @Nullable Source source,
                          @Nullable MovingAimRay movingRay, boolean compensateCarrierVelocity,
                          double elevationCorrection, boolean fixedElevation) {
        this(bodyId, localMuzzle, localForward, worldTarget, profile, source, movingRay,
                compensateCarrierVelocity, elevationCorrection, fixedElevation, null);
    }
    public FiniteAimTarget(UUID bodyId, Vec3 localMuzzle, Vec3 localForward, Vec3 worldTarget,
                          @Nullable CannonBallisticsCompat.ShotProfile profile, @Nullable Source source,
                          @Nullable MovingAimRay movingRay, boolean compensateCarrierVelocity) {
        this(bodyId, localMuzzle, localForward, worldTarget, profile, source, movingRay,
                compensateCarrierVelocity, 0, false);
    }
    public FiniteAimTarget(UUID bodyId, Vec3 localMuzzle, Vec3 localForward, Vec3 worldTarget,
                           @Nullable CannonBallisticsCompat.ShotProfile profile, @Nullable Source source,
                           @Nullable MovingAimRay movingRay) {
        this(bodyId,localMuzzle,localForward,worldTarget,profile,source,movingRay,true);
    }

    public FiniteAimTarget(UUID bodyId, Vec3 localMuzzle, Vec3 localForward, Vec3 worldTarget,
                           @Nullable CannonBallisticsCompat.ShotProfile profile, @Nullable Source source) {
        this(bodyId,localMuzzle,localForward,worldTarget,profile,source,null);
    }

    public FiniteAimTarget withMovingRay(@Nullable MovingAimRay ray) {
        return new FiniteAimTarget(bodyId,localMuzzle,localForward,worldTarget,profile,source,ray,
                compensateCarrierVelocity, elevationCorrection, fixedElevation, parallelSight);
    }

    /** Changes only the bearing's aiming inverse, never projectile physics or impact prediction. */
    public FiniteAimTarget withCarrierVelocityCompensation(boolean enabled) {
        return new FiniteAimTarget(bodyId,localMuzzle,localForward,worldTarget,profile,source,movingRay,enabled,
                elevationCorrection, fixedElevation, parallelSight);
    }

    public FiniteAimTarget withElevationCorrection(double radians) {
        return new FiniteAimTarget(bodyId, localMuzzle, localForward, worldTarget, profile, source, movingRay,
                compensateCarrierVelocity, radians, true, parallelSight);
    }

    /** Keep the virtual sight and bore parallel even after the muzzle moves during elevation. */
    public FiniteAimTarget withParallelSight(@Nullable Vec3 direction) {
        return new FiniteAimTarget(bodyId, localMuzzle, localForward, worldTarget, profile, source, movingRay,
                compensateCarrierVelocity, elevationCorrection, fixedElevation, direction);
    }
    /** Two devices on the same physical structure must not inherit each other's acquisition. */
    public record Source(BlockPos bindingPosition, @Nullable UUID entityId) {
        public Source { bindingPosition = Objects.requireNonNull(bindingPosition).immutable(); }
    }

    public FiniteAimTarget(UUID bodyId, Vec3 localMuzzle, Vec3 localForward, Vec3 worldTarget,
                           @Nullable CannonBallisticsCompat.ShotProfile profile) {
        this(bodyId, localMuzzle, localForward, worldTarget, profile, null);
    }

    public boolean sameSource(FiniteAimTarget other) {
        return other != null && bodyId.equals(other.bodyId) && Objects.equals(source, other.source);
    }
    public FiniteAimTarget {
        Objects.requireNonNull(bodyId);
        requireFinite(localMuzzle);
        requireFinite(localForward);
        requireFinite(worldTarget);
        if (!Double.isFinite(elevationCorrection) || Math.abs(elevationCorrection) > Math.PI / 2)
            throw new IllegalArgumentException("Invalid saved elevation");
        if (localForward.lengthSqr() < 1.0e-12) throw new IllegalArgumentException("Degenerate muzzle direction");
        localForward = localForward.normalize();
        if (parallelSight != null) {
            requireFinite(parallelSight);
            if (parallelSight.lengthSqr() < 1e-12) throw new IllegalArgumentException("Degenerate parallel sight");
            parallelSight = parallelSight.normalize();
        }
    }

    /** Resolves the assembled cannon's current containing structure, including nested assemblies. */
    public static @Nullable FiniteAimTarget from(Level level, BindingRef ref,
                                                @Nullable CannonBallisticsCompat.ShotProfile profile,
                                                Vec3 worldTarget) {
        try {
            Entity cannon = CannonMountCompat.getMountedCannonEntity(level, ref);
            if (cannon == null) return null;
            SubLevel body = Sable.HELPER.getContaining(cannon);
            if (body == null || body.isRemoved()) return null;
            Vec3 muzzle = profile == null ? CannonBallisticsCompat.getCurrentMuzzlePosition(level, ref)
                    : profile.muzzle();
            Vec3 forward = profile == null ? CannonBallisticsCompat.getCurrentForward(level, ref)
                    : profile.currentForward();
            if (muzzle == null || forward == null) return null;
            Vector3d localPoint = body.logicalPose().transformPositionInverse(vector(muzzle));
            Vector3d localRay = body.logicalPose().transformNormalInverse(vector(forward));
            return new FiniteAimTarget(body.getUniqueId(), vec(localPoint), vec(localRay), worldTarget,
                    profile, new Source(ref.pos(), cannon.getUUID()));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Uses holding kinematics during acquisition, so its own aiming speed is not fed back into aim. */
    public @Nullable Vec3 currentDirection(Level level) {
        return currentDirection(level, null);
    }

    /** Pose locks clear physical velocity; their inverse must use that same outgoing-shot frame. */
    public @Nullable Vec3 currentDirection(Level level, @Nullable Vec3 velocityOverride) {
        try {
            SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(bodyId);
            if (body == null || body.isRemoved()) return null;
            Vec3 muzzle = vec(body.logicalPose().transformPosition(vector(localMuzzle)));
            Vec3 forward = vec(body.logicalPose().transformNormal(vector(localForward)));
            Vec3 velocity = !compensateCarrierVelocity ? Vec3.ZERO : velocityOverride == null
                    ? BearingStabilizer.stableMuzzleVelocity(level, bodyId, muzzle, forward) : velocityOverride;
            if (velocity == null) velocity = vec(Sable.HELPER.getVelocity(level, body, vector(localMuzzle), new Vector3d())
                    .div(20.0));
            Vec3 target = movingRay == null ? worldTarget : movingRay.currentTarget(level);
            return target == null ? null : direction(muzzle, forward, velocity, target);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public @Nullable Vec3 currentGeometricDirection(Level level) {
        if (parallelSight != null) return parallelSight;
        try {
            SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(bodyId);
            if (body == null || body.isRemoved()) return null;
            Vec3 muzzle = vec(body.logicalPose().transformPosition(vector(localMuzzle)));
            Vec3 target = movingRay == null ? worldTarget : movingRay.currentTarget(level);
            return target == null || target.distanceToSqr(muzzle) < 1e-12
                    ? null : target.subtract(muzzle).normalize();
        } catch (RuntimeException | LinkageError ignored) { return null; }
    }

    /** The raised sight before velocity compensation; drop is removed separately by the HUD. */
    public @Nullable Vec3 currentRaisedSightDirection(Level level) {
        Vec3 geometric = currentGeometricDirection(level);
        return geometric == null ? null : LaserRangeCompensation.raise(geometric, elevationCorrection);
    }

    /** BodyState positions are centers of mass in the predictor's translated world coordinates. */
    public @Nullable Vector3d predictedDirection(ArticulatedServoPredictor.BodyState body,
                                                Vector3d modelOrigin, Vector3d localCOM) {
        return predictedDirection(body,modelOrigin,localCOM,worldTarget);
    }

    public @Nullable Vector3d predictedDirection(ArticulatedServoPredictor.BodyState body,
                                                Vector3d modelOrigin, Vector3d localCOM, Vec3 target) {
        return predictedDirection(body,modelOrigin,localCOM,target,null);
    }

    public @Nullable Vector3d predictedDirection(ArticulatedServoPredictor.BodyState body,
                                                Vector3d modelOrigin, Vector3d localCOM, Vec3 target,
                                                @Nullable Vec3 holdingVelocity) {
        if (body == null || modelOrigin == null || localCOM == null
                || !modelOrigin.isFinite() || !localCOM.isFinite()) return null;
        Vector3d offset = body.orientation().transform(vector(localMuzzle).sub(localCOM));
        Vec3 muzzle = vec(body.position().add(modelOrigin).add(offset));
        Vec3 forward = vec(body.orientation().transform(vector(localForward)));
        Vec3 velocity = !compensateCarrierVelocity ? Vec3.ZERO : holdingVelocity != null ? holdingVelocity
                : vec(body.angularVelocity().cross(offset).add(body.linearVelocity()).div(20.0));
        Vec3 requested = direction(muzzle, forward, velocity, target);
        return requested == null ? null : vector(requested);
    }

    private @Nullable Vec3 direction(Vec3 muzzle, Vec3 forward, Vec3 velocity, Vec3 target) {
        if (!finite(muzzle) || !finite(forward) || !finite(velocity)) return null;
        Vec3 displacement = target.subtract(muzzle);
        if (displacement.lengthSqr() < 1.0e-12 && parallelSight == null) return null;
        Vec3 requested = fixedElevation
                ? LaserRangeCompensation.launchDirection(profile, muzzle, forward, velocity, target, elevationCorrection,
                        parallelSight)
                : profile == null ? displacement.normalize() : CannonBallisticsCompat.solveLaunchDirection(
                        CannonBallisticsCompat.withFrame(profile, muzzle, forward, velocity), target);
        return requested == null || !finite(requested) || requested.lengthSqr() < 1.0e-12
                ? null : requested.normalize();
    }

    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
    private static Vec3 vec(Vector3d value) { return new Vec3(value.x, value.y, value.z); }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private static void requireFinite(Vec3 value) {
        if (!finite(value)) throw new IllegalArgumentException("Non-finite finite aim point");
    }
}
