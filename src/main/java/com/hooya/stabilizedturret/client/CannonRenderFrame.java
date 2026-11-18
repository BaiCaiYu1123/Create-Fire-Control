package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.AimGroupSessionPayload;
import com.hooya.stabilizedturret.content.controller.CannonSightDirection;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import java.util.List;

/** The HUD and its camera use the same interpolated physical pose. */
public record CannonRenderFrame(Vec3 origin,Vec3 direction,Vec3 velocity) {
    public record TimedMotion(double tick,AimGroupSessionPayload.BodyMotion motion) {}

    public static Vec3 carrierVelocityAt(AimGroupSessionPayload.MuzzleFrame frame,Pose3dc pose,
                                         List<TimedMotion> history,double lastTick,double currentTick,double alpha) {
        var a=motionAt(history,lastTick); var b=motionAt(history,currentTick);
        Vec3 linear=a.linearVelocity().lerp(b.linearVelocity(),alpha);
        Vec3 angular=a.angularVelocity().lerp(b.angularVelocity(),alpha);
        Vec3 com=a.localCOM().lerp(b.localCOM(),alpha);
        return pointVelocity(frame,pose,linear,angular,com);
    }

    private static AimGroupSessionPayload.BodyMotion motionAt(List<TimedMotion> history,double tick) {
        if (tick<=history.getFirst().tick()) return history.getFirst().motion();
        for (int i=1;i<history.size();i++) {
            var a=history.get(i-1); var b=history.get(i);
            if (tick>b.tick()) continue;
            double alpha=Math.clamp((tick-a.tick())/Math.max(1e-12,b.tick()-a.tick()),0,1);
            var x=a.motion(); var y=b.motion();
            return new AimGroupSessionPayload.BodyMotion(x.position().lerp(y.position(),alpha),
                    x.localCOM().lerp(y.localCOM(),alpha),x.linearVelocity().lerp(y.linearVelocity(),alpha),
                    x.angularVelocity().lerp(y.angularVelocity(),alpha),x.orientation().slerp(y.orientation(),alpha));
        }
        return history.getLast().motion();
    }

    /** Matches velocity samples to the rendered body pose, including Sable's buffered pose delay. */
    public static Vec3 carrierVelocity(AimGroupSessionPayload.MuzzleFrame frame,Pose3dc pose,
                                      List<AimGroupSessionPayload.BodyMotion> history) {
        if (history.isEmpty()) return Vec3.ZERO;
        var selected=history.getLast();
        Vec3 linear=selected.linearVelocity(),angular=selected.angularVelocity(),com=selected.localCOM();
        double best=Double.POSITIVE_INFINITY;
        Vector3d position=new Vector3d(pose.position());
        Quaterniond rotation=new Quaterniond(pose.orientation());
        for (int i=history.size()-1;i>0;i--) {
            var a=history.get(i-1); var b=history.get(i);
            Vector3d delta=vector(b.position().subtract(a.position()));
            double alpha;
            if (delta.lengthSquared()>1e-12) alpha=Math.clamp(position.sub(vector(a.position()),new Vector3d()).dot(delta)/delta.lengthSquared(),0,1);
            else {
                Quaterniond span=a.orientation().conjugate().mul(b.orientation()).normalize();
                if (span.w<0) span.mul(-1);
                Vector3d axis=new Vector3d(span.x,span.y,span.z);
                double sine=axis.length();
                if (sine<1e-10) alpha=1;
                else {
                    axis.div(sine);
                    Quaterniond at=a.orientation().conjugate().mul(rotation).normalize();
                    if (at.w<0) at.mul(-1);
                    double angle=2*Math.atan2(new Vector3d(at.x,at.y,at.z).dot(axis),at.w);
                    alpha=Math.clamp(angle/(2*Math.atan2(sine,span.w)),0,1);
                }
            }
            Vec3 interpolated=a.position().lerp(b.position(),alpha);
            Quaterniond q=a.orientation().slerp(b.orientation(),alpha).normalize();
            double dot=Math.min(1,Math.abs(q.dot(rotation)));
            double angle=2*Math.acos(dot);
            double score=position.distanceSquared(vector(interpolated))+angle*angle;
            if (score>=best) continue;
            best=score;
            linear=a.linearVelocity().lerp(b.linearVelocity(),alpha);
            angular=a.angularVelocity().lerp(b.angularVelocity(),alpha);
            com=a.localCOM().lerp(b.localCOM(),alpha);
        }
        return pointVelocity(frame,pose,linear,angular,com);
    }

    private static Vec3 pointVelocity(AimGroupSessionPayload.MuzzleFrame frame,Pose3dc pose,
                                      Vec3 linear,Vec3 angular,Vec3 com) {
        Vector3d offset=pose.transformNormal(vector(frame.localOrigin().subtract(com)));
        Vector3d velocity=vector(angular).cross(offset).add(vector(linear)).div(20);
        return new Vec3(velocity.x,velocity.y,velocity.z);
    }

    private static Vector3d vector(Vec3 v) { return new Vector3d(v.x,v.y,v.z); }

    /** Remove only the achieved correction sampled at the displayed physics time. */
    public static Vec3 sightDirection(AimGroupSessionPayload.GroupAim group, Pose3dc renderPose,
                                      CannonSightDirection.History history,double renderTick) {
        Vec3 bore = from(group.frame(), renderPose, Vec3.ZERO).direction();
        return group.sightAim()==null ? bore : history.direction(bore,renderTick);
    }

    public static CannonRenderFrame from(AimGroupSessionPayload.MuzzleFrame frame,Pose3dc renderPose,
                                          Vec3 carrierVelocity) {
        Vec3 origin=renderPose.transformPosition(frame.localOrigin());
        Vec3 local=frame.localForward();
        Vector3d ray=renderPose.transformNormal(new Vector3d(local.x,local.y,local.z));
        Vec3 direction=new Vec3(ray.x,ray.y,ray.z).normalize();
        return new CannonRenderFrame(origin,direction,direction.scale(frame.muzzleSpeed()).add(carrierVelocity));
    }
}
