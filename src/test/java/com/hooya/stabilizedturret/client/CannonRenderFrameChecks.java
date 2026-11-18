package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster.RayHit;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import com.hooya.stabilizedturret.content.controller.CannonProjectileFlight;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonSightDirection;
import com.hooya.stabilizedturret.network.AimGroupSessionPayload;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.UUID;

public final class CannonRenderFrameChecks {
    public static void main(String[] args) {
        matchesBufferedAccelerationAndRotation();
        interpolationClockDistinguishesRepeatedPoses();
        cachedFaceUsesCurrentRenderedLaunch();
        cachedLodUsesCurrentRenderedLaunch();
        compensatedSightRetainsMeasuredTrackingError();
        compensationTransitionsFollowIndependentRenderedAxes();
        skySightDoesNotBecomeAGroundImpact();
        // A side-facing barrel and a separate turret camera, at the reported 53 m / 16x.
        Vector3d localCOM=new Vector3d(20000000,70,-20000000);
        Vec3 localMuzzle=new Vec3(localCOM.x+3,localCOM.y+4,localCOM.z);
        Vec3 forward=new Vec3(1,-4.0/53.0,0).normalize();
        var frame=new AimGroupSessionPayload.MuzzleFrame(new UUID(1,2),localMuzzle,forward,12);
        double maximum=0,oldMaximum=0;
        for (double speed : new double[]{-0.6,0.6}) for (double alpha : new double[]{0,.25,.5,.75,1}) {
            Pose3d previous=new Pose3d(new Vector3d(30,80,40),new Quaterniond(),new Vector3d(localCOM),new Vector3d(1));
            Pose3d current=new Pose3d(previous);
            current.position().add(0,0,speed);
            Pose3d render=new Pose3d(previous).lerp(current,alpha);
            Vec3 camera=render.transformPosition(new Vec3(localCOM.x,localCOM.y+4,localCOM.z));
            CannonRenderFrame launch=CannonRenderFrame.from(frame,render,Vec3.ZERO);
            Vec3 impact=CannonProjectileFlight.trace(launch.origin(),launch.velocity(),0,0,false,20,-64,
                    (from,to)->{
                        double t=(80-from.y)/(to.y-from.y);
                        return t>=0 && t<=1 ? from.lerp(to,t) : null;
                    });
            require(impact!=null,"rendered barrel hits actual terrain");
            Vec3 expected=launch.origin().add(forward.scale(4/-forward.y));
            double error=impact.distanceTo(expected);
            maximum=Math.max(maximum,error);
            require(error<1e-8,"rendered impact shares the physical pose and trajectory");
            // Old world marker interpolates an extra previous server sample while the scope follows Sable.
            Vec3 old=expected.add(0,0,-speed);
            double radians=Math.acos(Math.clamp(old.subtract(camera).normalize().dot(expected.subtract(camera).normalize()),-1,1));
            double pixels=720/Math.tan(Math.atan(Math.tan(Math.toRadians(70)/2)/16))*Math.tan(radians);
            oldMaximum=Math.max(oldMaximum,pixels);
            require(pixels>100,"stale finite world marker creates a large opposite-direction error at 16x");
            require(CannonRenderFrame.from(frame,render,new Vec3(0,0,speed)).velocity()
                    .subtract(launch.velocity()).distanceTo(new Vec3(0,0,speed))<1e-10,
                    "real forward/reverse projectile inheritance remains visible in the HUD");
        }
        System.out.printf("PASS: render-time muzzle frame error %.12f blocks; stale 16x marker %.3f pixels; compensation removed from measured sight, tracking errors preserved, sky uses a direction ray%n",maximum,oldMaximum);
    }

    private static void compensatedSightRetainsMeasuredTrackingError() {
        Vec3 geometric=new Vec3(1,.1,0).normalize();
        for (double range:new double[]{23,160}) for (double speed:new double[]{-.6,.6}) {
            var profile=new CannonBallisticsCompat.ShotProfile(Vec3.ZERO,geometric,new Vec3(0,0,speed),
                    8,-.05,0,false,"test","test",8,1,400,1,1,0);
            Vec3 solved=CannonBallisticsCompat.solveLaunchDirection(profile,geometric.scale(range));
            require(solved!=null,"compensated solution exists");
            close(CannonSightDirection.direction(solved,solved,geometric),geometric,"acquired correction removal");
            for (double error:new double[]{-2,0,2}) {
                Vector3d ray=new Quaterniond().rotationXYZ(Math.toRadians(error),Math.toRadians(error*.5),0)
                        .transform(new Vector3d(solved.x,solved.y,solved.z));
                Vec3 measured=new Vec3(ray.x,ray.y,ray.z);
                Vec3 sight=CannonSightDirection.direction(measured,solved,geometric);
                require(Math.abs(angle(sight,geometric)-angle(measured,solved))<1e-7,
                        "actual barrel tracking error remains visible instead of pinning to head input");
            }
        }
        Vec3 com=new Vec3(20000000,70,-20000000),bore=aim(90,8),sight=aim(90,0);
        var state=motion(new Vec3(4,70,8),com,Vec3.ZERO,Vec3.ZERO,new Quaterniond());
        var frame=new AimGroupSessionPayload.MuzzleFrame(new UUID(3,4),com.add(20,4,0),bore,8,state);
        var group=new AimGroupSessionPayload.GroupAim(4,70,8,bore.x,bore.y,bore.z,null,frame,
                new AimGroupSessionPayload.SightAim(bore,sight,10));
        var history=new CannonSightDirection.History(); history.add(10,bore,sight);
        for (double alpha:new double[]{0,.5,1}) {
            Pose3d render=pose(state); render.orientation().rotateXYZ(alpha*.2,alpha*-.3,alpha*.1);
            Vec3 actual=CannonRenderFrame.from(frame,render,Vec3.ZERO).direction();
            close(CannonRenderFrame.sightDirection(group,render,history,10),
                    CannonSightDirection.direction(actual,bore,sight),"HUD uses the actual interpolated bore");
        }
        Pose3d rolled=pose(state); rolled.orientation().rotateAxis(.7,bore.x,bore.y,bore.z);
        close(CannonRenderFrame.sightDirection(group,rolled,history,10),sight,
                "bore roll cannot rotate world gravity correction");
    }

    private static void compensationTransitionsFollowIndependentRenderedAxes() {
        var history=new CannonSightDirection.History();
        history.add(10,aim(0,5),aim(0,0));
        history.add(12,aim(0,12),aim(0,0));
        for (int repeat=0;repeat<4;repeat++)
            close(history.direction(aim(0,5),10),aim(0,0),"a future correction cannot move an older render pose");
        close(history.direction(aim(0,8.5),11),aim(0,0),"correction and bore share Sable's interpolation clock");
        close(history.direction(aim(0,12.21),12),aim(0,.21),
                "a finite physical residual cannot leave a stale partial correction");
        history.add(14,aim(0,0),aim(0,0));
        close(history.direction(aim(0,6),13),aim(0,0),"finite to sky is continuous at the displayed physics time");
        history.add(16,aim(0,20),aim(0,0));
        close(history.direction(aim(0,10),15),aim(0,0),"sky to near block is continuous at the displayed physics time");
        history.add(18,aim(0,24),aim(0,20));
        close(history.direction(aim(0,22),17),aim(0,10),"genuine acquired pitch motion remains visible");
        var other=new CannonSightDirection.History(); other.add(18,aim(-30,-2),aim(-30,-5));
        close(other.direction(aim(-30,-2),18),aim(-30,-5),"second turret's correction is independent");
        close(history.direction(aim(0,24),18),aim(0,20),"second turret cannot change first turret");
        history.add(18,aim(0,24),aim(0,21));
        close(history.direction(aim(0,24),18),aim(0,21),"a repeated sample tick replaces its correction");
        history.add(8,aim(0,5),aim(0,0));
        close(history.direction(aim(0,24),18),aim(0,21),"late older packets cannot become the newest correction");
        var sleeping=new CannonSightDirection.History();
        for (int tick=40;tick<=90;tick++) sleeping.add(tick,aim(0,6),aim(0,(tick-40)*.02));
        close(sleeping.direction(aim(0,6),20),aim(0,1),
                "stopped/outdated physics clock uses the latest acquired correction, not an arbitrary oldest packet");
        var frame=new AimGroupSessionPayload.MuzzleFrame(new UUID(1,2),Vec3.ZERO,aim(0,8),8);
        var raw=new AimGroupSessionPayload.GroupAim(0,0,0,0,0,1,null,frame);
        close(CannonRenderFrame.sightDirection(raw,new Pose3d(),history,18),aim(0,8),
                "disabled compensation and legacy rays bypass all correction history");
    }

    private static void skySightDoesNotBecomeAGroundImpact() {
        Vec3 sky=new Vec3(1,.3,0).normalize(),origin=new Vec3(0,70,0);
        var surface=new CannonMarkerSurface(new Vec3(0,64,0),new Vec3(0,1,0));
        var launch=new CannonRenderFrame(origin,sky,sky.scale(8));
        require(surface.trajectory(launch,-.05,0,false,400,-64)!=null,"old sky trajectory falls to ground");
        var history=new CannonSightDirection.History(); history.add(10,sky,sky);
        Vec3 sight=history.direction(sky,10);
        require(surface.ray(origin,sight,4096)==null,"sky sight is a straight direction ray, not a ground impact");
        close(sight,sky,"zero sky correction");
    }

    private static Vec3 aim(double yaw,double pitch) {
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
        return new Vec3(Math.sin(y)*Math.cos(p),Math.sin(p),Math.cos(y)*Math.cos(p));
    }
    private static void close(Vec3 actual,Vec3 expected,String message) {
        require(actual.distanceTo(expected)<1e-9,message+": "+actual+" vs "+expected);
    }
    private static double angle(Vec3 from,Vec3 to) {
        return Math.acos(Math.clamp(from.normalize().dot(to.normalize()),-1,1));
    }
    private static void cachedFaceUsesCurrentRenderedLaunch() {
        var surface=new CannonMarkerSurface(new Vec3(0,64,0),new Vec3(0,1,0));
        for (double speed:new double[]{-.6,.6}) for (double alpha:new double[]{0,.25,.5,.75,1}) {
            Vec3 origin=new Vec3(0,70,speed*alpha),direction=new Vec3(1,-.1,0).normalize();
            var launch=new CannonRenderFrame(origin,direction,direction.scale(12).add(0,0,speed));
            Vec3 actual=surface.trajectory(launch,-.05,.001,false,100,-64);
            Vec3 traced=CannonProjectileFlight.trace(origin,launch.velocity(),-.05,.001,false,100,-64,(from,to)->{
                if (from.y<64 || to.y>64) return null;
                return from.lerp(to,(from.y-64)/(from.y-to.y));
            });
            require(actual!=null && traced!=null && actual.distanceTo(traced)<1e-10,
                    "cached face follows the rendered muzzle and real inherited forward/reverse velocity every frame");
            Vec3 ray=surface.ray(origin,direction,4096);
            require(ray!=null && Math.abs(ray.y-64)<1e-10,"direct ray remains on the true loaded face");
        }
        require(surface.ray(new Vec3(0,70,0),new Vec3(1,0,0),4096)==null,"empty sky does not invent a plane contact");
    }

    private static void cachedLodUsesCurrentRenderedLaunch() {
        Vec3 com = new Vec3(20000000, 70, -20000000);
        var frame = new AimGroupSessionPayload.MuzzleFrame(new UUID(10, 11), com, new Vec3(0, 0, 1), 8);
        AABB bounds = new AABB(-8, 64, 24000, 8, 80, 24016);
        var cached = new RayHit(new Vec3(0, 70, 24000), bounds);
        int changes = 0;
        Vec3 previous = null;
        // Multiple rendered frames share one 50 ms physics tick and one LOD query.
        for (int i = 0; i <= 24; i++) {
            double alpha = i / 24.0;
            Pose3d pose = new Pose3d(new Vector3d(alpha * .6, 70, 0),
                    new Quaterniond().rotationY(alpha * .0002),
                    new Vector3d(com.x, com.y, com.z), new Vector3d(1));
            CannonRenderFrame launch = CannonRenderFrame.from(frame, pose, Vec3.ZERO);
            Vec3 marker = cached.ray(launch.origin(), launch.direction(), AimRayLimits.LOD_DISTANCE);
            Vec3 expected = launch.origin().add(launch.direction().scale(
                    (bounds.minZ - launch.origin().z) / launch.direction().z));
            require(marker != null && marker.distanceTo(expected) < 1e-8,
                    "LOD marker uses this frame's translated/rotated physical muzzle");
            if (previous != null && marker.distanceToSqr(previous) > 1e-6) changes++;
            previous = marker;
        }
        require(changes == 24, "LOD marker changes on every rendered frame within the same game tick");
        require(cached.ray(new Vec3(9, 70, 0), new Vec3(0, 0, 1), AimRayLimits.LOD_DISTANCE) == null,
                "leaving the cached voxel invalidates it for an immediate terrain re-query");
        require(cached.ray(new Vec3(0, 70, 0), new Vec3(0, 1, 0), AimRayLimits.LOD_DISTANCE) == null,
                "sky does not inherit a stale LOD terrain point");
        require(cached.ray(new Vec3(0, 70, 0), new Vec3(0, 0, -1), AimRayLimits.LOD_DISTANCE) == null,
                "a voxel behind the rendered barrel is invalidated");
        require(cached.ray(new Vec3(0, 70, 0), new Vec3(0, 0, 1), 23999) == null,
                "cached contact cannot extend the query range");
        close(cached.ray(new Vec3(0, 70, 0), new Vec3(0, 0, 1), 24000), cached.point(),
                "a hit exactly at maximum distance remains finite");
    }

    private static void interpolationClockDistinguishesRepeatedPoses() {
        Vec3 com=new Vec3(20000000,70,-20000000);
        var forward=motion(new Vec3(0,80,0),com,new Vec3(0,0,12),Vec3.ZERO,new Quaterniond());
        var reverse=motion(new Vec3(0,80,0),com,new Vec3(0,0,-12),Vec3.ZERO,new Quaterniond());
        var frame=new AimGroupSessionPayload.MuzzleFrame(new UUID(1,2),com.add(3,4,0),new Vec3(1,0,0),12,reverse);
        var history=java.util.List.of(new CannonRenderFrame.TimedMotion(10,forward),
                new CannonRenderFrame.TimedMotion(11,forward),new CannonRenderFrame.TimedMotion(20,reverse),
                new CannonRenderFrame.TimedMotion(21,reverse));
        require(CannonRenderFrame.carrierVelocityAt(frame,pose(forward),history,10,11,.5)
                .distanceTo(new Vec3(0,0,.6))<1e-10,"delayed forward frame retains forward velocity at a repeated position");
        require(CannonRenderFrame.carrierVelocityAt(frame,pose(reverse),history,20,21,.5)
                .distanceTo(new Vec3(0,0,-.6))<1e-10,"reverse frame retains reverse velocity at the same position");
    }

    private static void matchesBufferedAccelerationAndRotation() {
        Vec3 com=new Vec3(20000000,70,-20000000);
        var a=motion(new Vec3(0,80,0),com,Vec3.ZERO,Vec3.ZERO,new Quaterniond());
        var b=motion(new Vec3(0,80,.1),com,new Vec3(0,0,4),Vec3.ZERO,new Quaterniond());
        var c=motion(new Vec3(0,80,.5),com,new Vec3(0,0,12),Vec3.ZERO,new Quaterniond());
        var future=motion(new Vec3(0,80,1.3),com,new Vec3(0,0,20),Vec3.ZERO,new Quaterniond());
        var frame=new AimGroupSessionPayload.MuzzleFrame(new UUID(1,2),com.add(3,4,0),new Vec3(1,0,0),12,future);
        for (double alpha : new double[]{0,.25,.5,.75,1}) {
            Pose3d pose=pose(b).lerp(pose(c),alpha);
            Vec3 actual=CannonRenderFrame.carrierVelocity(frame,pose,java.util.List.of(a,b,c,future));
            require(actual.distanceTo(new Vec3(0,0,(4+8*alpha)/20))<1e-10,
                    "buffered render uses its own instantaneous acceleration sample, without average-speed or future-packet bias");
        }
        var stop=motion(new Vec3(0,80,.6),com,Vec3.ZERO,Vec3.ZERO,new Quaterniond());
        require(CannonRenderFrame.carrierVelocity(frame,pose(stop),java.util.List.of(b,c,stop)).length()<1e-10,
                "real stopped body has zero inherited velocity, without a pose-delta tail");
        var r0=motion(new Vec3(0,80,0),com,Vec3.ZERO,new Vec3(0,2,0),new Quaterniond());
        var r1=motion(new Vec3(0,80,0),com,Vec3.ZERO,new Vec3(0,2,0),new Quaterniond().rotationY(.1));
        Pose3d rotated=pose(r0).lerp(pose(r1),.3);
        Vector3d offset=rotated.transformNormal(new Vector3d(3,4,0));
        Vector3d expected=new Vector3d(0,2,0).cross(offset).div(20);
        require(CannonRenderFrame.carrierVelocity(frame,rotated,java.util.List.of(r0,r1))
                .distanceTo(new Vec3(expected.x,expected.y,expected.z))<1e-10,
                "rotation-only buffered poses use physical angular point velocity at the current rendered muzzle");
    }
    private static AimGroupSessionPayload.BodyMotion motion(Vec3 position,Vec3 com,Vec3 linear,Vec3 angular,Quaterniond q) {
        return new AimGroupSessionPayload.BodyMotion(position,com,linear,angular,q);
    }
    private static Pose3d pose(AimGroupSessionPayload.BodyMotion m) {
        return new Pose3d(new Vector3d(m.position().x,m.position().y,m.position().z),m.orientation(),
                new Vector3d(m.localCOM().x,m.localCOM().y,m.localCOM().z),new Vector3d(1));
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
