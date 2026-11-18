// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// Copyright (c) 2026 BaiCaiYu
package com.caca.copypoly.client;

import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Stable-ID element topology and projection-only marquee selection, including hidden elements. */
final class PolyEditorTopology {
    private final PolyShape shape;
    final List<Polyhedron.Triangle> faces;
    final List<PolyShape.Connection> edges;
    private final List<Set<Integer>> patches;
    static PolyShape.Connection edge(int a,int b){return new PolyShape.Connection(Math.min(a,b),Math.max(a,b));}
    PolyEditorTopology(PolyShape shape) {
        this.shape=shape;List<Polyhedron.Triangle> surface;
        try{surface=shape.surfaceTriangles();}catch(IllegalArgumentException invalid){surface=List.of();}faces=surface;
        Map<PolyShape.Connection,List<Integer>> incident=new LinkedHashMap<>();
        for(int i=0;i<faces.size();i++){int[] ids=faces.get(i).indices();for(int j=0;j<3;j++)incident.computeIfAbsent(edge(ids[j],ids[(j+1)%3]),key->new ArrayList<>()).add(i);}
        Set<PolyShape.Connection> wires=new LinkedHashSet<>();for(var c:shape.connections())wires.add(edge(c.source(),c.target()));
        List<List<Integer>> adjacent=new ArrayList<>();for(int i=0;i<faces.size();i++)adjacent.add(new ArrayList<>());
        incident.forEach((e,indices)->{
            boolean boundary=indices.size()!=2 || !coplanar(indices.get(0),indices.get(1)) || shape.isDrawnEdge(e.source(),e.target());
            if(boundary)wires.add(e);else{adjacent.get(indices.get(0)).add(indices.get(1));adjacent.get(indices.get(1)).add(indices.get(0));}
        });edges=List.copyOf(wires);
        patches=new ArrayList<>(Collections.nCopies(faces.size(),null));
        for(int i=0;i<faces.size();i++)if(patches.get(i)==null){Set<Integer> group=new LinkedHashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(i);while(!queue.isEmpty()){int face=queue.removeFirst();if(group.add(face))queue.addAll(adjacent.get(face));}var immutable=Collections.unmodifiableSet(group);for(int face:group)patches.set(face,immutable);}
    }
    Vec3 point(int id){return PolyGizmo.position(shape.vertex(id).position());}
    private Vec3 normal(int i){var t=faces.get(i);return point(t.b()).subtract(point(t.a())).cross(point(t.c()).subtract(point(t.a()))).normalize();}
    private boolean coplanar(int a,int b){var n=normal(a);if(n.dot(normal(b))<1-1e-8)return false;return Math.abs(n.dot(point(faces.get(b).a()).subtract(point(faces.get(a).a()))))<1e-7;}
    Set<Integer> patch(int face){return patches.get(face);}
    PolyShape.Connection pickEdge(double x,double y,Function<Vec3,Vec3> project,Predicate<Vec3> visible,double radius) {
        PolyShape.Connection best=null;double distance=radius,depth=Double.POSITIVE_INFINITY;
        for(var e:edges){Vec3 a=project.apply(point(e.source())),b=project.apply(point(e.target()));if(a==null||b==null)continue;double dx=b.x-a.x,dy=b.y-a.y,n=dx*dx+dy*dy;if(n<1e-12)continue;double t=Math.clamp(((x-a.x)*dx+(y-a.y)*dy)/n,0,1);Vec3 p=a.lerp(b,t);double d=Math.hypot(x-p.x,y-p.y);
            if(d<=radius&&(best==null||d<distance-.25||Math.abs(d-distance)<=.25&&p.z<depth)&&visible.test(p)){best=e;distance=d;depth=p.z;}}
        return best;
    }
    record Box(double minX,double minY,double maxX,double maxY) {
        boolean contains(Vec3 p){return p!=null&&p.x>=minX&&p.x<=maxX&&p.y>=minY&&p.y<=maxY;}
        boolean line(Vec3 a,Vec3 b){
            if(a==null||b==null)return false;if(contains(a)||contains(b))return true;double lo=0,hi=1,dx=b.x-a.x,dy=b.y-a.y;
            double[] p={-dx,dx,-dy,dy},q={a.x-minX,maxX-a.x,a.y-minY,maxY-a.y};
            for(int i=0;i<4;i++){if(Math.abs(p[i])<1e-12){if(q[i]<0)return false;}else if(p[i]<0)lo=Math.max(lo,q[i]/p[i]);else hi=Math.min(hi,q[i]/p[i]);}return lo<=hi;
        }
        boolean triangle(Vec3 a,Vec3 b,Vec3 c){if(a==null||b==null||c==null)return false;if(line(a,b)||line(b,c)||line(c,a))return true;if(Math.abs(cross(a,b,c))<1e-12)return false;Vec3 p=new Vec3((minX+maxX)/2,(minY+maxY)/2,0);double u=cross(a,b,p),v=cross(b,c,p),w=cross(c,a,p);return u>=0&&v>=0&&w>=0||u<=0&&v<=0&&w<=0;}
        private static double cross(Vec3 a,Vec3 b,Vec3 c){return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    }
    Set<PolyShape.Connection> edgesIn(Box box,Function<Vec3,Vec3> project){Set<PolyShape.Connection> result=new LinkedHashSet<>();for(var e:edges)if(box.line(project.apply(point(e.source())),project.apply(point(e.target()))))result.add(e);return result;}
    Set<Integer> facesIn(Box box,Function<Vec3,Vec3> project){Set<Integer> result=new LinkedHashSet<>();for(int i=0;i<faces.size();i++){var t=faces.get(i);if(!result.contains(i)&&box.triangle(project.apply(point(t.a())),project.apply(point(t.b())),project.apply(point(t.c()))))result.addAll(patch(i));}return result;}
}
