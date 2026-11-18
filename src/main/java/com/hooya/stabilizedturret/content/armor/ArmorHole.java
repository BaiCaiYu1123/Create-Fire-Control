package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import com.simibubi.create.content.contraptions.StructureTransform;
import java.util.*;

/** A square bore, measured perpendicular to the shot, retained in block-local coordinates. */
public record ArmorHole(Vec3 center, Vec3 u, Vec3 v, double width) {
    public static final double WIDTH = 2.0/16;
    public static final double LEGACY_WIDTH = 3.0/16;
    public ArmorHole(Vec3 center,Vec3 u,Vec3 v){this(center,u,v,LEGACY_WIDTH);}
    public ArmorHole {
        if (!finite(center)||!finite(u)||!finite(v)||u.lengthSqr()<.5||v.lengthSqr()<.5
                ||!Double.isFinite(width)||width<=0||width>4)
            throw new IllegalArgumentException("Invalid armor bore");
        u=u.normalize();v=v.subtract(u.scale(v.dot(u))).normalize();
        if(v.lengthSqr()<.5)throw new IllegalArgumentException("Parallel bore axes");
    }
    public static ArmorHole along(Vec3 center,Vec3 direction) {
        return along(center,direction,WIDTH);
    }
    public static ArmorHole along(Vec3 center,Vec3 direction,double width) {
        if(!finite(direction)||direction.lengthSqr()<1e-12)throw new IllegalArgumentException("Invalid shot direction");
        Vec3 d=direction.normalize();
        double dominant=Math.abs(d.x)>=Math.abs(d.y)&&Math.abs(d.x)>=Math.abs(d.z)?d.x:Math.abs(d.y)>=Math.abs(d.z)?d.y:d.z;
        if(dominant<0)d=d.reverse();
        Vec3 u=d.cross(Math.abs(d.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0)).normalize();
        return new ArmorHole(center,u,d.cross(u).normalize(),width);
    }
    public Vec3 direction(){return u.cross(v).normalize();}
    public boolean contains(Vec3 p) {var q=p.subtract(center);return Math.abs(q.dot(u))<width/2-1e-9&&Math.abs(q.dot(v))<width/2-1e-9;}
    public boolean sameBore(ArmorHole other) {
        var delta=center.subtract(other.center);
        return Math.abs(width-other.width)<1e-8&&Math.abs(u.dot(other.u))>1-1e-8&&Math.abs(v.dot(other.v))>1-1e-8
                &&Math.abs(delta.dot(u))<1e-8&&Math.abs(delta.dot(v))<1e-8;
    }
    public boolean covers(ArmorHole other){
        var delta=other.center.subtract(center);
        return Math.abs(u.dot(other.u))>1-1e-8&&Math.abs(v.dot(other.v))>1-1e-8
                &&Math.abs(delta.dot(u))+other.width/2<=width/2+1e-8
                &&Math.abs(delta.dot(v))+other.width/2<=width/2+1e-8;
    }
    public ArmorHole transform(StructureTransform transform) {
        Vec3 c=transform.applyWithoutOffset(center);
        return new ArmorHole(c,transform.applyWithoutOffset(center.add(u)).subtract(c),transform.applyWithoutOffset(center.add(v)).subtract(c),width);
    }
    /** -1 disjoint, 1 entirely removed, 0 intersects the boundary. */
    public int classify(AABB box) {
        boolean inside=true;
        for(Vec3 axis:List.of(u,v)) {
            double mid=axis.dot(box.getCenter().subtract(center));
            double radius=(Math.abs(axis.x)*box.getXsize()+Math.abs(axis.y)*box.getYsize()+Math.abs(axis.z)*box.getZsize())/2;
            if(mid-radius>=width/2-1e-10||mid+radius<=-width/2+1e-10)return -1;
            inside&=Math.abs(mid)+radius<=width/2+1e-10;
        }
        return inside?1:0;
    }
    public AABB axisAlignedBox(){
        int a=dominant(u),b=dominant(v);if(a==b||Math.abs(component(u,a))<1-1e-9||Math.abs(component(v,b))<1-1e-9)return null;
        double[] low={-2,-2,-2},high={3,3,3};for(int axis:new int[]{a,b}){low[axis]=component(center,axis)-width/2;high[axis]=component(center,axis)+width/2;}
        return new AABB(low[0],low[1],low[2],high[0],high[1],high[2]);
    }
    private static int dominant(Vec3 p){return Math.abs(p.x)>=Math.abs(p.y)&&Math.abs(p.x)>=Math.abs(p.z)?0:Math.abs(p.y)>=Math.abs(p.z)?1:2;}
    private static double component(Vec3 p,int a){return a==0?p.x:a==1?p.y:p.z;}
    List<ArmorMesh.Face> prism() {
        Vec3 d=direction().scale(4), a=u.scale(width/2), b=v.scale(width/2);
        Vec3[] p=new Vec3[8]; for(int i=0;i<8;i++)p[i]=center.add(a.scale((i&1)==0?-1:1)).add(b.scale((i&2)==0?-1:1)).add(d.scale((i&4)==0?-1:1));
        int[][] faces={{0,4,6,2},{1,3,7,5},{0,1,5,4},{2,6,7,3},{0,2,3,1},{4,5,7,6}};
        return Arrays.stream(faces).map(f->new ArmorMesh.Face(Arrays.stream(f).mapToObj(i->p[i]).toList())).toList();
    }
    private static boolean finite(Vec3 p){return Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
}
