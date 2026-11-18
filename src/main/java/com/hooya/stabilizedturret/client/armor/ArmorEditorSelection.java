package com.hooya.stabilizedturret.client.armor;

import com.hooya.stabilizedturret.content.armor.EditableArmorMesh;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Screen-space element picking; marquee selection deliberately ignores occlusion. */
final class ArmorEditorSelection {
    enum Mode { VERTEX, EDGE, FACE }
    record SegmentPoint(double distance,Vec3 projected) {}
    record FaceHit(int triangle,Vec3 point,double distance) {}
    private final EditableArmorMesh mesh;
    private final List<EditableArmorMesh.Edge> edges;
    private final List<Set<Integer>> patches;
    ArmorEditorSelection(EditableArmorMesh mesh) {
        this.mesh=mesh;edges=mesh.edges();patches=patches(mesh,edges);
    }
    List<EditableArmorMesh.Edge> edges(){return edges;}
    Set<Integer> patch(int triangle){return patches.get(triangle);}
    int vertex(double x,double y,Function<Vec3,Vec3> project,Predicate<Vec3> visible,double radius) {
        int hit=-1;double best=radius,depth=Double.POSITIVE_INFINITY;
        for(int i=0;i<mesh.vertices().size();i++) {
            Vec3 p=project.apply(mesh.vertices().get(i));if(p==null)continue;
            double d=Math.hypot(x-p.x,y-p.y);
            if(d<=radius && (hit<0 || d<best-.5 || Math.abs(d-best)<=.5 && p.z<depth) && visible.test(p)){hit=i;best=d;depth=p.z;}
        }
        return hit;
    }
    EditableArmorMesh.Edge edge(double x,double y,Function<Vec3,Vec3> project,Predicate<Vec3> visible,double radius) {
        EditableArmorMesh.Edge hit=null;double best=radius,depth=Double.POSITIVE_INFINITY;
        for(var edge:edges) {
            var p=segmentPoint(x,y,project.apply(mesh.vertices().get(edge.a())),project.apply(mesh.vertices().get(edge.b())));
            if(p==null || p.distance()>radius)continue;
            if(p.distance()<=radius && (hit==null || p.distance()<best-.25 || Math.abs(p.distance()-best)<=.25 && p.projected().z<depth)) {
                if(visible.test(p.projected())){hit=edge;best=p.distance();depth=p.projected().z;}
            }
        }
        return hit;
    }
    FaceHit face(Vec3 origin,Vec3 direction) {
        if(origin==null || direction==null)return null;
        FaceHit hit=null;
        for(int i=0;i<mesh.triangles().size();i++) {
            var t=mesh.triangles().get(i);Vec3 a=mesh.vertices().get(t.a()),b=mesh.vertices().get(t.b()),c=mesh.vertices().get(t.c());
            Vec3 e=b.subtract(a),f=c.subtract(a),p=direction.cross(f);double det=e.dot(p);if(Math.abs(det)<1e-12)continue;
            Vec3 delta=origin.subtract(a);double u=delta.dot(p)/det;if(u< -1e-8 || u>1+1e-8)continue;
            Vec3 q=delta.cross(e);double v=direction.dot(q)/det;if(v< -1e-8 || u+v>1+1e-8)continue;
            double distance=f.dot(q)/det;
            if(distance>1e-8 && (hit==null || distance<hit.distance()))hit=new FaceHit(i,origin.add(direction.scale(distance)),distance);
        }
        return hit;
    }
    static SegmentPoint segmentPoint(double x,double y,Vec3 a,Vec3 b) {
        if(a==null || b==null)return null;
        double dx=b.x-a.x,dy=b.y-a.y,n=dx*dx+dy*dy;if(!Double.isFinite(n) || n<1e-16)return null;
        double t=Math.clamp(((x-a.x)*dx+(y-a.y)*dy)/n,0,1);Vec3 p=a.lerp(b,t);
        return new SegmentPoint(Math.hypot(x-p.x,y-p.y),p);
    }
    record Box(double minX,double minY,double maxX,double maxY) {
        static Box between(double x0,double y0,double x1,double y1){return new Box(Math.min(x0,x1),Math.min(y0,y1),Math.max(x0,x1),Math.max(y0,y1));}
        boolean contains(Vec3 p){return p!=null && p.x>=minX && p.x<=maxX && p.y>=minY && p.y<=maxY;}
        boolean intersects(Vec3 a,Vec3 b) {
            if(a==null || b==null)return false;if(contains(a) || contains(b))return true;
            double lo=0,hi=1,dx=b.x-a.x,dy=b.y-a.y;
            double[] p={-dx,dx,-dy,dy},q={a.x-minX,maxX-a.x,a.y-minY,maxY-a.y};
            for(int i=0;i<4;i++){if(Math.abs(p[i])<1e-12){if(q[i]<0)return false;}else if(p[i]<0)lo=Math.max(lo,q[i]/p[i]);else hi=Math.min(hi,q[i]/p[i]);}
            return lo<=hi;
        }
        boolean intersects(Vec3 a,Vec3 b,Vec3 c) {
            if(a==null || b==null || c==null)return false;
            if(intersects(a,b) || intersects(b,c) || intersects(c,a))return true;
            double area=cross(a,b,c);if(Math.abs(area)<1e-10)return false;
            var p=new Vec3((minX+maxX)/2,(minY+maxY)/2,0);
            double u=cross(a,b,p),v=cross(b,c,p),w=cross(c,a,p);
            return u>=0 && v>=0 && w>=0 || u<=0 && v<=0 && w<=0;
        }
    }
    private static double cross(Vec3 a,Vec3 b,Vec3 c){return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    Set<Integer> verticesIn(Box box,Function<Vec3,Vec3> project) {
        Set<Integer> result=new LinkedHashSet<>();for(int i=0;i<mesh.vertices().size();i++)if(box.contains(project.apply(mesh.vertices().get(i))))result.add(i);return result;
    }
    Set<EditableArmorMesh.Edge> edgesIn(Box box,Function<Vec3,Vec3> project) {
        Set<EditableArmorMesh.Edge> result=new LinkedHashSet<>();for(var e:edges)if(box.intersects(project.apply(mesh.vertices().get(e.a())),project.apply(mesh.vertices().get(e.b()))))result.add(e);return result;
    }
    Set<Integer> facesIn(Box box,Function<Vec3,Vec3> project) {
        Set<Integer> result=new LinkedHashSet<>();
        for(int i=0;i<mesh.triangles().size();i++) {
            if(result.contains(i))continue;var t=mesh.triangles().get(i);
            if(box.intersects(project.apply(mesh.vertices().get(t.a())),project.apply(mesh.vertices().get(t.b())),project.apply(mesh.vertices().get(t.c()))))result.addAll(patch(i));
        }
        return result;
    }
    private static List<Set<Integer>> patches(EditableArmorMesh mesh,List<EditableArmorMesh.Edge> boundaries) {
        Set<EditableArmorMesh.Edge> cuts=new HashSet<>(boundaries);Map<EditableArmorMesh.Edge,List<Integer>> incident=new HashMap<>();
        for(int i=0;i<mesh.triangles().size();i++) {int[] t=mesh.triangles().get(i).indices();for(int j=0;j<3;j++)incident.computeIfAbsent(new EditableArmorMesh.Edge(t[j],t[(j+1)%3]),key->new ArrayList<>()).add(i);}
        List<List<Integer>> adjacent=new ArrayList<>();for(int i=0;i<mesh.triangles().size();i++)adjacent.add(new ArrayList<>());
        incident.forEach((edge,faces)->{if(!cuts.contains(edge) && faces.size()==2){adjacent.get(faces.get(0)).add(faces.get(1));adjacent.get(faces.get(1)).add(faces.get(0));}});
        List<Set<Integer>> patches=new ArrayList<>(Collections.nCopies(mesh.triangles().size(),null));
        for(int i=0;i<patches.size();i++)if(patches.get(i)==null) {
            Set<Integer> group=new LinkedHashSet<>();ArrayDeque<Integer> pending=new ArrayDeque<>();pending.add(i);
            while(!pending.isEmpty()){int face=pending.removeFirst();if(!group.add(face))continue;pending.addAll(adjacent.get(face));}
            Set<Integer> immutable=Collections.unmodifiableSet(group);for(int face:group)patches.set(face,immutable);
        }
        return patches;
    }
}
