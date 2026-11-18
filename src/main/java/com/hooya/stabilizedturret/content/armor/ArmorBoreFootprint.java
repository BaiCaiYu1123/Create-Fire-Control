package com.hooya.stabilizedturret.content.armor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One bore shared by the neighboring cells of the penetrated longitudinal layer. */
public final class ArmorBoreFootprint {
    private static final double EPS=1e-8;
    public record Cut(BlockPos offset,ArmorHole hole) {}

    public static List<Cut> cuts(ArmorHole bore) {
        var direction=bore.direction();
        int axis=Math.abs(direction.x)>=Math.abs(direction.y)&&Math.abs(direction.x)>=Math.abs(direction.z)?0:
                Math.abs(direction.y)>=Math.abs(direction.z)?1:2;
        double along=coord(direction,axis);
        double[] low={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        double[] high={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        // Intersect all four corner rays with the two planes of this block layer.
        // Sideways spread includes obliquity; the next layer still needs its own penetration.
        for(int u:new int[]{-1,1})for(int v:new int[]{-1,1}) {
            var corner=bore.center().add(bore.u().scale(u*bore.width()/2)).add(bore.v().scale(v*bore.width()/2));
            for(int plane=0;plane<=1;plane++) {
                var point=corner.add(direction.scale((plane-coord(corner,axis))/along));
                for(int a=0;a<3;a++){low[a]=Math.min(low[a],coord(point,a));high[a]=Math.max(high[a],coord(point,a));}
            }
        }
        int[] first=new int[3],last=new int[3];
        for(int a=0;a<3;a++) {
            first[a]=a==axis?0:(int)Math.floor(low[a]+EPS);
            last[a]=a==axis?0:(int)Math.floor(high[a]-EPS);
        }
        var result=new ArrayList<Cut>();
        for(int x=first[0];x<=last[0];x++)for(int y=first[1];y<=last[1];y++)for(int z=first[2];z<=last[2];z++) {
            var offset=new BlockPos(x,y,z);
            if(bore.classify(new AABB(offset))<0)continue;
            var center=bore.center().subtract(Vec3.atLowerCornerOf(offset));
            // Moving the origin along the same bore preserves its square and angle,
            // and keeps the per-block NBT origin close to that block after an oblique hit.
            center=center.add(direction.scale(new Vec3(.5,.5,.5).subtract(center).dot(direction)));
            result.add(new Cut(offset,new ArmorHole(center,bore.u(),bore.v(),bore.width())));
        }
        return List.copyOf(result);
    }

    private static double coord(Vec3 p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
    private ArmorBoreFootprint() {}
}
