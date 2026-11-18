package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonProjectileFlight;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/** COM/plot-space conversion and actual muzzle velocity regressions for finite aim. */
public final class FiniteAimTargetChecks {
    private FiniteAimTargetChecks() {}

    public static void main(String[] args) {
        geometryUsesLocalCOMAndWorldOrigin();
        worldHeldBarrelDoesNotInheritCarrierRotation();
        translationAndPointAngularVelocityRemainPhysical();
        predictedHoldingDoesNotFeedTraverseBackIntoCompensation();
        muzzleCompensationCanBeDisabled();
        invalidPredictedFrameFallsBack();
        sourcesDistinguishDevicesOnOneBody();
        movingSightRayUsesPredictedCameraPose();
        surfaceChangesAreNewAcquisition();
        pitchDoesNotChaseAzimuth();
        thirdPersonBoomDoesNotRotateWithBody();
        physicalSurfaceFollowsItsOwnPose();
        System.out.println("PASS: finite targets use actual muzzle frame, COM and physical point velocity; muzzle compensation controls drop and drift together without altering projectile motion");
    }

    private static void pitchDoesNotChaseAzimuth() {
        for (Quaterniond parent:new Quaterniond[]{new Quaterniond(),new Quaterniond().rotationXYZ(.3,.8,-.2)}) {
            Vector3d heading=parent.transform(new Vector3d(0,0,1));
            Vector3d axis=parent.transform(new Vector3d(1,0,0));
            Vector3d held=parent.transform(new Vector3d(0,Math.sin(Math.toRadians(10)),Math.cos(Math.toRadians(10))));
            for (double yaw:new double[]{0,90,160,180,-160,-90}) {
                double y=Math.toRadians(yaw),e=Math.toRadians(-10);
                Vector3d requested=parent.transform(new Vector3d(Math.sin(y)*Math.cos(e),Math.sin(e),Math.cos(y)*Math.cos(e)));
                double error=BearingServoMath.signedDirectionAngle(BearingServoMath.pitchDirection(held,heading,parent),
                        BearingServoMath.pitchDirection(requested,heading,parent),axis);
                require(Math.abs(error-Math.toRadians(20))<1e-10,"side/rear targets change elevation by 20 degrees, never a pitch half-turn");
                Vector3d next=BearingServoMath.advancePitchDirection(held,requested,axis,heading,parent,Math.toRadians(5));
                Vector3d local=parent.transformInverse(next);
                require(Math.abs(Math.asin(local.y)-Math.toRadians(5))<1e-10,"pitch acquisition respects the five-degree step on a tilted carrier");
            }
        }
    }

    private static void thirdPersonBoomDoesNotRotateWithBody() {
        Vector3d com=new Vector3d(20000000,70,-20000000),origin=new Vector3d(100,80,50);
        Vec3 eye=new Vec3(com.x,com.y+4,com.z),boom=new Vec3(-8,3,-16);
        Vec3 sight=new Vec3(1,-.1,0).normalize(),surface=new Vec3(150,70,50);
        var ray=new MovingAimRay(new UUID(0,25),eye,sight,surface,new Vec3(0,1,0),boom);
        for (double move:new double[]{-.6,.6}) {
            Quaterniond rotation=new Quaterniond().rotationXYZ(.15,Math.PI/2,-.1);
            var state=body(new Vector3d(0,0,move),rotation,new Vector3d(),new Vector3d());
            Vec3 hit=ray.predictedTarget(state,origin,com);
            Vector3d anchor=rotation.transform(new Vector3d(0,4,0)).add(origin).add(0,0,move).add(vector(boom));
            require(hit!=null && vector(hit).sub(anchor).normalize().distance(vector(sight))<1e-10,
                    "forward/reverse and body rotation retain the world third-person boom");
        }
    }

    private static void surfaceChangesAreNewAcquisition() {
        UUID id=new UUID(0,15);
        var ray=new MovingAimRay(id,Vec3.ZERO,new Vec3(1,-.1,0),new Vec3(50,-5,0),new Vec3(-1,0,0));
        require(ray.sameSurface(new MovingAimRay(id,new Vec3(0,1,0),ray.worldDirection(),new Vec3(50,-4,2),ray.surfaceNormal())),
                "movement on the selected face retains unlimited platform compensation");
        require(!ray.sameSurface(new MovingAimRay(id,Vec3.ZERO,ray.worldDirection(),new Vec3(200,-20,0),ray.surfaceNormal())),
                "same-view near-to-far depth change retains operator acquisition limits");
        require(!ray.sameSurface(new MovingAimRay(new UUID(0,16),Vec3.ZERO,ray.worldDirection(),ray.surfacePoint(),ray.surfaceNormal())),
                "another camera body cannot inherit finite acquisition");
    }

    private static void physicalSurfaceFollowsItsOwnPose() {
        UUID cameraId = new UUID(0, 30), targetId = new UUID(0, 31);
        Vec3 localPoint = new Vec3(20000000, 70, 20000000), localNormal = new Vec3(0, 0, -1);
        var face = new MovingAimRay.PhysicalSurface(targetId, localPoint, localNormal);
        var ray = new MovingAimRay(cameraId, Vec3.ZERO, new Vec3(.1, 0, 1),
                new Vec3(0, 2, 10), localNormal, Vec3.ZERO, face);
        for (double turn : new double[]{-.3, 0, .3}) {
            var pose = new dev.ryanhcode.sable.companion.math.Pose3d(new Vector3d(0, 2, 10),
                    new Quaterniond().rotationY(turn), vector(localPoint), new Vector3d(1));
            Vector3d camera = new Vector3d(.8, 2, 2);
            Vec3 hit = ray.intersect(camera, pose);
            require(hit != null && Math.abs(pose.transformPositionInverse(hit).z - localPoint.z) < 1e-7,
                    "moving physical face is intersected in its own pose and stays under the sight");
            require(vector(hit).sub(camera).normalize().distance(vector(ray.worldDirection())) < 1e-10,
                    "camera translation preserves the world sight direction at close range");
        }
        var sameFace = new MovingAimRay(cameraId, Vec3.ZERO, ray.worldDirection(), new Vec3(3, 2, 9),
                new Vec3(.2, 0, -1), Vec3.ZERO, new MovingAimRay.PhysicalSurface(targetId,
                localPoint.add(1, 0, 0), localNormal));
        require(ray.sameSurface(sameFace), "target rotation/translation is automatic tracking on the same local face");
        var otherBody = new MovingAimRay(cameraId, Vec3.ZERO, ray.worldDirection(), ray.surfacePoint(),
                ray.surfaceNormal(), Vec3.ZERO, new MovingAimRay.PhysicalSurface(new UUID(0, 32), localPoint, localNormal));
        require(!ray.sameSurface(otherBody), "overlapping physical structures cannot share acquisition");
    }

    private static void movingSightRayUsesPredictedCameraPose() {
        UUID cameraId=new UUID(0,9);
        Vector3d origin=new Vector3d(100,80,-70),localCOM=new Vector3d(20000000,60,20000000);
        Vec3 localCamera=new Vec3(localCOM.x+2,localCOM.y+4,localCOM.z-1);
        Vec3 direction=new Vec3(1,-4.0/53.0,0).normalize();
        Vec3 start=new Vec3(102,84,-71);
        Vec3 surface=start.add(direction.scale(53/direction.x));
        var ray=new MovingAimRay(cameraId,localCamera,direction,surface,new Vec3(0,1,0));
        for (double movement : new double[]{-0.6,0.6}) {
            for (double pitch : new double[]{-.25,0,.25}) {
                Quaterniond rotation=new Quaterniond().rotationZ(pitch);
                var state=body(new Vector3d(0,0,movement),rotation,new Vector3d(),new Vector3d());
                Vec3 hit=ray.predictedTarget(state,origin,localCOM);
                Vector3d camera=rotation.transform(new Vector3d(2,4,-1)).add(origin).add(0,0,movement);
                require(hit!=null && Math.abs(hit.y-surface.y)<1e-10,
                        "moving sight stays on the actual block-face plane");
                require(vector(hit).sub(camera).normalize().distance(vector(direction))<1e-10,
                        "sideways forward/reverse and tilt preserve the sight ray at the predicted camera pose");
            }
        }
        var old=surface.subtract(start.add(0,0,.6)).normalize();
        require(Math.abs(old.z)>.01,"a frozen world hit reproduces the observed lateral marker drift");
        require(new MovingAimRay(cameraId,localCamera,new Vec3(1,0,0),surface,new Vec3(0,1,0))
                .predictedTarget(body(new Vector3d(),new Quaterniond(),new Vector3d(),new Vector3d()),origin,localCOM)==null,
                "parallel/sky rays cannot invent a finite plane target");
    }

    private static void geometryUsesLocalCOMAndWorldOrigin() {
        Vector3d localCOM = new Vector3d(20000100, 60, -20000100);
        Vec3 localMuzzle = new Vec3(localCOM.x + 4, localCOM.y + 1, localCOM.z + 8);
        Vector3d modelOrigin = new Vector3d(10000000, 80, -9000000);
        Vector3d bodyPosition = new Vector3d(2, 3, 4);
        Quaterniond orientation = new Quaterniond().rotationXYZ(0.2, -0.8, 0.1);
        Vector3d offset = orientation.transform(new Vector3d(4, 1, 8));
        Vector3d targetDelta = new Vector3d(30, 5, 120);
        Vec3 target = vec(new Vector3d(modelOrigin).add(bodyPosition).add(offset).add(targetDelta));
        var source = source(localMuzzle, target, null);
        var body = body(bodyPosition, orientation, new Vector3d(), new Vector3d());
        Vector3d actual = source.predictedDirection(body, modelOrigin, localCOM);
        require(actual != null && actual.distance(targetDelta.normalize()) < 1.0e-11,
                "finite geometry converts plot point through local COM exactly once");
    }

    private static void worldHeldBarrelDoesNotInheritCarrierRotation() {
        var source = source(new Vec3(0, 0, 8), new Vec3(0, 0, 100), profile());
        var stationaryBarrel = body(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d());
        Vector3d direction = source.predictedDirection(stationaryBarrel, new Vector3d(), new Vector3d());
        require(direction != null && direction.distance(new Vector3d(0, 0, 1)) < 1.0e-10,
                "world-held barrel remains on target even when the external carrier turns");
        double falseVelocity = Math.toRadians(120) * 8 / 20;
        Vec3 oldDirection = CannonBallisticsCompat.solveLaunchDirection(
                CannonBallisticsCompat.withFrame(profile(), new Vec3(0, 0, 8), new Vec3(0, 0, 1),
                        new Vec3(falseVelocity, 0, 0)), new Vec3(0, 0, 100));
        require(oldDirection != null && Math.abs(Math.toDegrees(Math.asin(oldDirection.x))) > 6.0,
                "the old virtual carrier point produces the reported opposite deflection");
    }

    private static void translationAndPointAngularVelocityRemainPhysical() {
        Vector3d linear = new Vector3d(4, -2, 1);
        Vector3d angular = new Vector3d(0, 0.03, 0);
        Vec3 localMuzzle = new Vec3(0, 0, 8);
        Vec3 target = new Vec3(0, 0, 100);
        var source = source(localMuzzle, target, profile());
        var barrel = body(new Vector3d(), new Quaterniond(), linear, angular);
        Vector3d actual = source.predictedDirection(barrel, new Vector3d(), new Vector3d());
        Vec3 pointVelocity = vec(new Vector3d(angular).cross(new Vector3d(0, 0, 8)).add(linear).div(20));
        Vec3 expected = CannonBallisticsCompat.solveLaunchDirection(
                CannonBallisticsCompat.withFrame(profile(), localMuzzle, new Vec3(0, 0, 1), pointVelocity), target);
        require(actual != null && expected != null && actual.distance(vector(expected)) < 1.0e-12,
                "real body translation and angular point motion remain in finite ballistics");
        require(Math.abs(actual.x) > 0.02, "real translation receives nonzero lateral compensation");
    }

    private static void invalidPredictedFrameFallsBack() {
        var source = source(new Vec3(0, 0, 8), new Vec3(0, 0, 100), profile());
        var barrel = body(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d());
        require(source.predictedDirection(barrel, new Vector3d(Double.NaN, 0, 0), new Vector3d()) == null,
                "invalid model origin rejects the optional target");
    }

    private static void predictedHoldingDoesNotFeedTraverseBackIntoCompensation() {
        double oldWorst=0;
        for (double length:new double[]{1,6,20,40}) for (double traverse:new double[]{-.5,0,.5}) {
            Vec3 muzzle=new Vec3(0,0,length),target=new Vec3(0,0,180);
            var source=source(muzzle,target,profile());
            Vector3d linear=new Vector3d(4,-2,1);
            var base=body(new Vector3d(),new Quaterniond(),linear,new Vector3d());
            var barrel=body(new Vector3d(),new Quaterniond(),linear,new Vector3d(traverse,.3,0));
            var holding=new StableMuzzleVelocity.Prediction(0,java.util.List.of(
                    new StableMuzzleVelocity.JointFrame(0,1,new Vector3d(0,1,0),new Vector3d()),
                    new StableMuzzleVelocity.JointFrame(0,1,new Vector3d(1,0,0),new Vector3d())));
            Vector3d velocity=holding.velocity(java.util.List.of(base,barrel),vector(muzzle),new Vector3d(0,0,1));
            require(velocity!=null && velocity.distance(linear)<1e-12,
                    "predicted holding retains carrier translation and excludes each trial's head traverse");
            Vector3d actual=source.predictedDirection(barrel,new Vector3d(),new Vector3d(),target,vec(velocity.div(20)));
            Vec3 expected=CannonBallisticsCompat.solveLaunchDirection(
                    CannonBallisticsCompat.withFrame(profile(),muzzle,new Vec3(0,0,1),vec(new Vector3d(linear).div(20))),target);
            require(actual!=null && expected!=null && actual.distance(vector(expected))<1e-10,
                    "current and predicted compensation use the same holding velocity regardless of barrel length");
            Vector3d old=source.predictedDirection(barrel,new Vector3d(),new Vector3d());
            require(old!=null,"old actual-velocity inverse is reachable");
            oldWorst=Math.max(oldWorst,old.distance(actual));
        }
        require(oldWorst>.03,"actual traverse point velocity recreates a length-dependent compensated bias");
        System.out.println("PASS: finite predicted holding separates operator traverse from barrel-length compensation");
    }

    private static void muzzleCompensationCanBeDisabled() {
        Vec3 muzzle = new Vec3(0, 0, 8), target = new Vec3(0, 5, 100);
        var ammunition = new CannonBallisticsCompat.ShotProfile(Vec3.ZERO, new Vec3(0, 0, 1), Vec3.ZERO,
                8, -0.05, 0, false, "test", "test", 8, 1, 200, 1, 1, 0);
        var enabled = source(muzzle, target, ammunition);
        // The controller omits the aiming profile when full muzzle compensation
        // is off; actual ammunition remains available for the projectile trace.
        var disabled = source(muzzle, target, null).withCarrierVelocityCompensation(false);
        var stationary = body(new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d());
        Vector3d reference = enabled.predictedDirection(stationary, new Vector3d(), new Vector3d());
        Vec3 geometry = target.subtract(muzzle).normalize();
        require(reference != null && reference.y > geometry.y,
                "enabled muzzle compensation corrects drop even on a stationary vehicle");
        for (double speed : new double[]{20, -20}) {
            var moving = body(new Vector3d(), new Quaterniond(), new Vector3d(speed, -2, 0), new Vector3d(0, .03, 0));
            Vector3d compensated = enabled.predictedDirection(moving, new Vector3d(), new Vector3d());
            Vector3d uncompensated = disabled.predictedDirection(moving, new Vector3d(), new Vector3d());
            require(uncompensated != null && uncompensated.distance(vector(geometry)) < 1e-12,
                    "disabled bearing inverse points directly at the block without gravity or carrier correction");
            require(compensated != null && Math.abs(compensated.x) > .1,
                    "default enabled inverse retains real lateral compensation");
            Vec3 physicalVelocity = vec(new Vector3d(0, .03, 0).cross(vector(muzzle))
                    .add(speed, -2, 0).div(20));
            Vec3 compensatedImpact = targetPlaneImpact(muzzle,
                    vec(compensated).scale(ammunition.muzzleSpeed()).add(physicalVelocity), ammunition, target.z);
            Vec3 uncompensatedImpact = targetPlaneImpact(muzzle,
                    vec(uncompensated).scale(ammunition.muzzleSpeed()).add(physicalVelocity), ammunition, target.z);
            require(compensatedImpact != null && compensatedImpact.distanceTo(target) < .12,
                    "enabled prediction uses actual projectile motion and lands at the selected block");
            require(uncompensatedImpact != null && Math.abs(uncompensatedImpact.x - target.x) > 10
                            && uncompensatedImpact.y < target.y - 1,
                    "disabling aiming compensation preserves real projectile drift and drop");
        }
        var ray = new MovingAimRay(new UUID(0, 2), Vec3.ZERO, new Vec3(0, 0, 1), target, new Vec3(0, 0, -1));
        require(!disabled.withMovingRay(ray).compensateCarrierVelocity(),
                "moving camera updates retain the selected compensation policy");
        require(enabled.compensateCarrierVelocity() && disabled.sameSource(enabled),
                "policy is per target and does not alter device identity or other targets");
    }

    private static Vec3 targetPlaneImpact(Vec3 muzzle, Vec3 velocity,
                                          CannonBallisticsCompat.ShotProfile ammunition, double planeZ) {
        return CannonProjectileFlight.trace(muzzle, velocity, ammunition.gravity(), ammunition.drag(),
                ammunition.quadraticDrag(), ammunition.maxFlightTicks(), -64, (from, to) -> {
                    double dz = to.z - from.z;
                    if (Math.abs(dz) < 1e-12) return null;
                    double fraction = (planeZ - from.z) / dz;
                    return fraction >= 0 && fraction <= 1 ? from.lerp(to, fraction) : null;
                });
    }

    private static void sourcesDistinguishDevicesOnOneBody() {
        UUID body = new UUID(0, 1), firstEntity = new UUID(0, 2), secondEntity = new UUID(0, 3);
        var first = new FiniteAimTarget(body, Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 100),
                profile(), new FiniteAimTarget.Source(new BlockPos(1, 2, 3), firstEntity));
        var otherMount = new FiniteAimTarget(body, Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 100),
                profile(), new FiniteAimTarget.Source(new BlockPos(2, 2, 3), firstEntity));
        var rebuiltCannon = new FiniteAimTarget(body, Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 100),
                profile(), new FiniteAimTarget.Source(new BlockPos(1, 2, 3), secondEntity));
        var updatedFrame = new FiniteAimTarget(body, new Vec3(0, 0, 8), new Vec3(0, 0, 1), new Vec3(1, 0, 100),
                null, new FiniteAimTarget.Source(new BlockPos(1, 2, 3), firstEntity));
        require(!first.sameSource(otherMount) && !first.sameSource(rebuiltCannon),
                "another mount or rebuilt cannon cannot inherit a finite acquisition on the same body");
        require(first.sameSource(updatedFrame), "muzzle, target and ammunition changes retain the same actual device");
    }

    private static FiniteAimTarget source(Vec3 muzzle, Vec3 target, CannonBallisticsCompat.ShotProfile profile) {
        return new FiniteAimTarget(new UUID(0, 1), muzzle, new Vec3(0, 0, 1), target, profile);
    }

    private static CannonBallisticsCompat.ShotProfile profile() {
        return new CannonBallisticsCompat.ShotProfile(Vec3.ZERO, new Vec3(0, 0, 1), Vec3.ZERO,
                8, 0, 0, false, "test", "test", 8, 1, 200, 1, 1, 0);
    }

    private static ArticulatedServoPredictor.BodyState body(Vector3d position, Quaterniond orientation,
                                                           Vector3d linear, Vector3d angular) {
        return new ArticulatedServoPredictor.BodyState(100, new Matrix3d().scaling(1000), position,
                orientation, linear, angular);
    }

    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
    private static Vec3 vec(Vector3d value) { return new Vec3(value.x, value.y, value.z); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
