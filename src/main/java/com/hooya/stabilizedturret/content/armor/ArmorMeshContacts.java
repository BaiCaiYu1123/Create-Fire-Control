package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A quarter-pixel contact grid, generated from the edited geometry rather than the original bore. */
public final class ArmorMeshContacts {
    private static final int N=64;
    private static final int[] STRIDE = {1, N, N * N};
    private record Row(int x0,int x1,int z){}
    private record Slab(int x0,int x1,int y0,int y1){}
    public static List<AABB> build(ArmorMesh mesh){
        var boundary=boundaryCells(mesh);
        var rays=rasterize(mesh);
        var rows=new HashMap<Row,List<int[]>>();
        for(int z=0;z<N;z++)for(int y=0;y<N;y++){
            var intervals=rays[z*N+y];
            for(var range:intervals){int x0=Math.clamp((int)Math.ceil(range.min()*N-1e-7),0,N),x1=Math.clamp((int)Math.floor(range.max()*N+1e-7),0,N);if(x1<=x0)continue;
                int start=x0;for(int x=x0;x<=x1;x++){
                    if(x<x1&&!boundary[(z*N+y)*N+x])continue;
                    if(x>start){var list=rows.computeIfAbsent(new Row(start,x,z),k->new ArrayList<>());
                        if(!list.isEmpty()&&list.getLast()[1]==y)list.getLast()[1]=y+1;else list.add(new int[]{y,y+1});}
                    start=x+1;
                }
            }
        }
        var slabs=new HashMap<Slab,List<Integer>>();rows.forEach((row,spans)->{for(var span:spans)slabs.computeIfAbsent(new Slab(row.x0,row.x1,span[0],span[1]),k->new ArrayList<>()).add(row.z);});
        var result=new ArrayList<AABB>();slabs.forEach((slab,zs)->{zs.sort(Integer::compare);int begin=zs.getFirst(),end=begin+1;
            for(int i=1;i<=zs.size();i++){if(i<zs.size()&&zs.get(i)==end){end++;continue;}result.add(new AABB(slab.x0/(double)N,slab.y0/(double)N,begin/(double)N,slab.x1/(double)N,slab.y1/(double)N,end/(double)N));if(i<zs.size()){begin=zs.get(i);end=begin+1;}}
        });return List.copyOf(result);
    }
    private record Crossing(double x,int sign){}
    @SuppressWarnings("unchecked")
    private static List<ArmorMesh.Range>[] rasterize(ArmorMesh mesh){
        var hits=(ArrayList<Crossing>[])new ArrayList<?>[N*N];
        for(var face:mesh.faces()){
            var n=face.normal();if(Math.abs(n.x)<1e-10)continue;var first=face.vertices().getFirst();
            double ymin=1,ymax=0,zmin=1,zmax=0;for(var p:face.vertices()){ymin=Math.min(ymin,p.y);ymax=Math.max(ymax,p.y);zmin=Math.min(zmin,p.z);zmax=Math.max(zmax,p.z);}
            int y0=Math.clamp((int)Math.ceil(ymin*N-.5-1e-8),0,N-1),y1=Math.clamp((int)Math.floor(ymax*N-.5+1e-8),0,N-1),z0=Math.clamp((int)Math.ceil(zmin*N-.5-1e-8),0,N-1),z1=Math.clamp((int)Math.floor(zmax*N-.5+1e-8),0,N-1);
            for(int z=z0;z<=z1;z++)for(int y=y0;y<=y1;y++){
                double py=(y+.5)/N,pz=(z+.5)/N,px=first.x-(n.y*(py-first.y)+n.z*(pz-first.z))/n.x;boolean inside=true;
                for(int i=0;i<face.vertices().size();i++){var a=face.vertices().get(i);var b=face.vertices().get((i+1)%face.vertices().size());double ex=b.x-a.x,ey=b.y-a.y,ez=b.z-a.z,dx=px-a.x,dy=py-a.y,dz=pz-a.z;
                    if((ey*dz-ez*dy)*n.x+(ez*dx-ex*dz)*n.y+(ex*dy-ey*dx)*n.z< -1e-10){inside=false;break;}}
                if(inside){int cell=z*N+y;if(hits[cell]==null)hits[cell]=new ArrayList<>();hits[cell].add(new Crossing(px,n.x<0?1:-1));}
            }
        }
        var result=(List<ArmorMesh.Range>[])new List<?>[N*N];for(int cell=0;cell<result.length;cell++){
            var list=hits[cell];if(list==null){result[cell]=List.of();continue;}list.sort(Comparator.comparingDouble(Crossing::x));var intervals=new ArrayList<ArmorMesh.Range>();double start=0;boolean inside=false;
            for(int i=0;i<list.size();){double x=list.get(i).x;int sign=0;do{sign+=list.get(i++).sign;}while(i<list.size()&&Math.abs(list.get(i).x-x)<1e-8);
                if(sign>0&&!inside){start=x;inside=true;}else if(sign<0&&inside){if(x-start>1e-9)intervals.add(new ArmorMesh.Range(start,x));inside=false;}}
            result[cell]=intervals;
        }return result;
    }
    /** Exclude every cell with a surface through its interior, including sub-grid edited tunnels. */
    static boolean[] boundaryCells(ArmorMesh mesh){
        var result=new boolean[N*N*N];
        for(var face:mesh.faces())for(int i=1;i+1<face.vertices().size();i++){
            Vec3[] t={face.vertices().getFirst(),face.vertices().get(i),face.vertices().get(i+1)};
            int[] min=new int[3],max=new int[3];
            for(int a=0;a<3;a++){double lo=Math.min(coord(t[0],a),Math.min(coord(t[1],a),coord(t[2],a))),hi=Math.max(coord(t[0],a),Math.max(coord(t[1],a),coord(t[2],a)));
                min[a]=Math.clamp((int)Math.floor(lo*N+1e-7),0,N);max[a]=Math.clamp((int)Math.ceil(hi*N-1e-7)-1,-1,N-1);}
            Vec3[] axes=new Vec3[10];axes[0]=t[1].subtract(t[0]).cross(t[2].subtract(t[0]));int index=1;
            for(int e=0;e<3;e++){var d=t[(e+1)%3].subtract(t[e]);axes[index++]=d.cross(new Vec3(1,0,0));axes[index++]=d.cross(new Vec3(0,1,0));axes[index++]=d.cross(new Vec3(0,0,1));}
            double[] low=new double[10],high=new double[10],radius=new double[10];for(int a=0;a<axes.length;a++){var axis=axes[a];double v0=axis.dot(t[0]),v1=axis.dot(t[1]),v2=axis.dot(t[2]);low[a]=Math.min(v0,Math.min(v1,v2));high[a]=Math.max(v0,Math.max(v1,v2));radius[a]=(Math.abs(axis.x)+Math.abs(axis.y)+Math.abs(axis.z))*(.5/N-1e-9);}
            markBoundary(result, min, max, axes, low, high, radius);
        }return result;
    }

    private static void markBoundary(boolean[] result, int[] min, int[] max, Vec3[] axes,
                                     double[] low, double[] high, double[] radius) {
        Vec3 normal = axes[0];
        int sweep = dominantAxis(normal);
        int u = (sweep + 1) % 3;
        int v = (sweep + 2) % 3;
        if (min[0] > max[0] || min[1] > max[1] || min[2] > max[2]) return;
        boolean narrow = radius[0] >= 1e-15 && max[sweep] - min[sweep] > 3;
        double slope = coord(normal, sweep);
        double uSlope = coord(normal, u);
        double vSlope = coord(normal, v);
        for (int j = min[v]; j <= max[v]; j++) {
            double pv = (j + .5) / N;
            for (int i = min[u]; i <= max[u]; i++) {
                double pu = (i + .5) / N;
                int start = min[sweep];
                int end = max[sweep];
                if (narrow) {
                    double offset = uSlope * pu + vSlope * pv;
                    double first = (low[0] - radius[0] - offset) / slope * N - .5;
                    double last = (high[0] + radius[0] - offset) / slope * N - .5;
                    // Round outward before running the exact same separating-axis test.
                    start = Math.max(start, (int) Math.floor(Math.min(first, last)));
                    end = Math.min(end, (int) Math.ceil(Math.max(first, last)));
                }
                int row = i * STRIDE[u] + j * STRIDE[v];
                for (int k = start; k <= end; k++) {
                    int cell = row + k * STRIDE[sweep];
                    if (result[cell]) continue;
                    double t = (k + .5) / N;
                    double x = sweep == 0 ? t : u == 0 ? pu : pv;
                    double y = sweep == 1 ? t : u == 1 ? pu : pv;
                    double z = sweep == 2 ? t : u == 2 ? pu : pv;
                    if (intersectsCell(x, y, z, axes, low, high, radius)) result[cell] = true;
                }
            }
        }
    }

    private static int dominantAxis(Vec3 normal) {
        double x = Math.abs(normal.x), y = Math.abs(normal.y), z = Math.abs(normal.z);
        if (x >= y && x >= z) return 0;
        return y >= z ? 1 : 2;
    }

    private static boolean intersectsCell(double x, double y, double z, Vec3[] axes,
                                          double[] low, double[] high, double[] radius) {
        for (int i = 0; i < axes.length; i++) {
            if (radius[i] < 1e-15) continue;
            Vec3 axis = axes[i];
            double center = axis.x * x + axis.y * y + axis.z * z;
            if (low[i] - center >= radius[i] || high[i] - center <= -radius[i]) return false;
        }
        return true;
    }
    private static double coord(Vec3 p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
    private ArmorMeshContacts(){}
}
