package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.phys.*;
import java.util.List;

/** The outermost two contacts of a line; gaps between them deliberately count as armor. */
public record ArmorRayEnvelope(double entry,double exit) {
    public double length(){return Math.max(0,exit-entry);}
    public static ArmorRayEnvelope mesh(ArmorMesh mesh,Vec3 origin,Vec3 direction){
        double first=Double.POSITIVE_INFINITY,last=Double.NEGATIVE_INFINITY;
        for(var face:mesh.faces()){
            var n=face.normal();double denominator=n.dot(direction);if(Math.abs(denominator)<1e-10)continue;
            double t=n.dot(face.vertices().getFirst().subtract(origin))/denominator;
            if(t>=first&&t<=last)continue;
            double px=origin.x+direction.x*t,py=origin.y+direction.y*t,pz=origin.z+direction.z*t;
            boolean inside=true;
            for(int i=0;i<face.vertices().size();i++){
                var a=face.vertices().get(i);var b=face.vertices().get((i+1)%face.vertices().size());
                double x=b.x-a.x,y=b.y-a.y,z=b.z-a.z,dx=px-a.x,dy=py-a.y,dz=pz-a.z;
                if((y*dz-z*dy)*n.x+(z*dx-x*dz)*n.y+(x*dy-y*dx)*n.z< -1e-8){inside=false;break;}
            }
            if(inside){first=Math.min(first,t);last=Math.max(last,t);}
        }
        return result(first,last);
    }
    public static ArmorRayEnvelope boxes(List<AABB> boxes,Vec3 origin,Vec3 direction){
        double first=Double.POSITIVE_INFINITY,last=Double.NEGATIVE_INFINITY;
        for(var box:boxes){
            double low=Double.NEGATIVE_INFINITY,high=Double.POSITIVE_INFINITY;
            for(int axis=0;axis<3;axis++){
                double p=coord(origin,axis),d=coord(direction,axis),min=axis==0?box.minX:axis==1?box.minY:box.minZ,max=axis==0?box.maxX:axis==1?box.maxY:box.maxZ;
                if(Math.abs(d)<1e-12){if(p<min-1e-8||p>max+1e-8){low=1;high=0;break;}continue;}
                double a=(min-p)/d,b=(max-p)/d;low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b));
                if(high<low)break;
            }
            if(high-low>1e-9){first=Math.min(first,low);last=Math.max(last,high);}
        }
        return result(first,last);
    }
    private static ArmorRayEnvelope result(double first,double last){return Double.isFinite(first)&&Double.isFinite(last)&&last-first>1e-9?new ArmorRayEnvelope(first,last):new ArmorRayEnvelope(0,0);}
    private static double coord(Vec3 p,int a){return a==0?p.x:a==1?p.y:p.z;}
}
