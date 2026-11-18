package com.hooya.stabilizedturret.content.missile.impact;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.hooya.stabilizedturret.content.controller.SableMissileFuze;

/** Nonblocking nearest-hit swept collision query. */
public final class CollisionDetector {
    public static Vec3 sweep(ServerLevel level, Vec3 from, Vec3 to, Vec3 target, double proximityRadius, boolean proximity) {
        if (level==null||from==null||to==null) return null;
        Vec3 best=null; double bestD=Double.POSITIVE_INFINITY;
        BlockHitResult block=level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,CollisionContext.empty()));
        if (block.getType()!=HitResult.Type.MISS) { best=block.getLocation(); bestD=from.distanceToSqr(best); }
        EntityHitResult hit=ProjectileUtil.getEntityHitResult(level,null,from,to,new AABB(from,to).inflate(Math.max(0.0,proximityRadius)),e->e.isAlive()&&e.isPickable());
        if(hit!=null&&from.distanceToSqr(hit.getLocation())<bestD){best=hit.getLocation();bestD=from.distanceToSqr(best);}
        SableMissileFuze.Contact sableContact=SableMissileFuze.firstBlockContact(level,from,to);
        Vec3 sable=sableContact == null ? null : sableContact.position();
        if(sable!=null&&from.distanceToSqr(sable)<bestD){best=sable;bestD=from.distanceToSqr(sable);}
        if(proximity&&target!=null&&distanceToSegmentSqr(target,from,to)<=proximityRadius*proximityRadius){Vec3 p=closest(target,from,to);if(from.distanceToSqr(p)<bestD)best=p;}
        return best;
    }
    private static Vec3 closest(Vec3 p,Vec3 a,Vec3 b){Vec3 d=b.subtract(a);double l=d.lengthSqr();return a.add(d.scale(l<1e-12?0:Math.max(0,Math.min(1,p.subtract(a).dot(d)/l))));}
    private static double distanceToSegmentSqr(Vec3 p,Vec3 a,Vec3 b){return p.distanceToSqr(closest(p,a,b));}
    private CollisionDetector(){}
}
