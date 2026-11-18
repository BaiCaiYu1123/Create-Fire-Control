package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.CannonProjectileFlight;
import net.minecraft.world.phys.Vec3;

/** A previously queried block face, intersected again with the current rendered launch. */
public record CannonMarkerSurface(Vec3 point,Vec3 normal) {
    public Vec3 ray(Vec3 origin,Vec3 direction,double range) {
        double denominator=direction.dot(normal);
        if (denominator>=-1e-10) return null;
        double distance=point.subtract(origin).dot(normal)/denominator;
        return distance>=0 && distance<=range ? origin.add(direction.scale(distance)) : null;
    }

    public Vec3 trajectory(CannonRenderFrame launch,double gravity,double drag,boolean quadratic,int ticks,double minY) {
        return CannonProjectileFlight.trace(launch.origin(),launch.velocity(),gravity,drag,quadratic,ticks,minY,
                (from,to)->{
                    double a=from.subtract(point).dot(normal),b=to.subtract(point).dot(normal);
                    if (a<0 || b>0 || a-b<1e-12) return null;
                    return from.lerp(to,a/(a-b));
                });
    }
}
