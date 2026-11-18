package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.phys.Vec3;
import java.util.*;
import static com.hooya.stabilizedturret.content.armor.EditableArmorMesh.*;

/** Removes CSG/triangulation vertices by plane-preserving manifold edge contractions. */
final class ArmorMeshSimplifier {
    static EditableArmorMesh compact(EditableArmorMesh source,EditableArmorMesh reference){
        var points=source.vertices();var protect=new HashSet<Integer>();
        if(reference!=null)for(int i=0;i<points.size();i++)for(int j=0;j<reference.vertices().size();j++)
            if(reference.kinds().get(j)>=0&&points.get(i).distanceToSqr(reference.vertices().get(j))<1e-16){protect.add(i);break;}
        var faces=contract(points,source.triangles(),protect,1e-9);
        var used=new HashSet<Integer>();for(var t:faces)for(int i:t.indices())used.add(i);
        var result=new ArrayList<Vec3>();var kinds=new ArrayList<Integer>();var mapping=new HashMap<Integer,Integer>();var referenceMap=new HashMap<Integer,Integer>();
        // Original controls keep their order and normal/custom distinction; new bore corners are appended.
        if(reference!=null)for(int j=0;j<reference.vertices().size();j++){
            if(reference.kinds().get(j)<0)continue;var p=reference.vertices().get(j);int old=-1;for(int i:used)if(points.get(i).distanceToSqr(p)<1e-16){old=i;break;}
            int id=result.size();result.add(p);kinds.add(reference.kinds().get(j));referenceMap.put(j,id);if(old>=0)mapping.put(old,id);
        }
        for(int i=0;i<points.size();i++)if(used.contains(i)&&!mapping.containsKey(i)){mapping.put(i,result.size());result.add(points.get(i));kinds.add(reference==null?0:2);}
        var triangles=faces.stream().map(t->new Triangle(mapping.get(t.a()),mapping.get(t.b()),mapping.get(t.c()))).toList();
        var guides=new ArrayList<Edge>();if(reference!=null)for(var e:reference.guides())if(referenceMap.containsKey(e.a())&&referenceMap.containsKey(e.b()))guides.add(new Edge(referenceMap.get(e.a()),referenceMap.get(e.b())));
        return new EditableArmorMesh(result,triangles,kinds,guides);
    }
    static EditableArmorMesh simplify(EditableArmorMesh source) {
        source.validate();
        Set<Integer> protectedVertices = new HashSet<>();
        for (var g : source.guides()) {
            protectedVertices.add(g.a());
            protectedVertices.add(g.b());
        }
        var faces = contract(source.vertices(),source.triangles(),protectedVertices,1e-12);
        Set<Integer> used = new HashSet<>(protectedVertices);
        for (var t : faces) for (int id : t.indices()) used.add(id);
        if (used.size() == source.vertices().size()) return source;

        int[] index = new int[source.vertices().size()];
        var vertices = new ArrayList<Vec3>();
        var kinds = new ArrayList<Integer>();
        for (int i = 0; i < index.length; i++) if (used.contains(i)) {
            index[i] = vertices.size();
            vertices.add(source.vertices().get(i));
            kinds.add(source.kinds().get(i));
        }
        var simplified = new EditableArmorMesh(vertices,
            faces.stream().map(t -> new Triangle(index[t.a()],index[t.b()],index[t.c()])).toList(),
            kinds,source.guides().stream().map(e -> new Edge(index[e.a()],index[e.b()])).toList());
        simplified.validate();
        return simplified;
    }

    private static List<Triangle> contract(List<Vec3> points,List<Triangle> faces,Set<Integer> protect,double planeTolerance) {
        boolean progress=true;
        while(progress){progress=false;
            var incident=new ArrayList<List<Integer>>(points.size());var neighbors=new ArrayList<Set<Integer>>(points.size());
            for(int i=0;i<points.size();i++){incident.add(new ArrayList<>());neighbors.add(new LinkedHashSet<>());}
            for(int f=0;f<faces.size();f++){int[] v=faces.get(f).indices();for(int i=0;i<3;i++){incident.get(v[i]).add(f);neighbors.get(v[i]).add(v[(i+1)%3]);neighbors.get(v[i]).add(v[(i+2)%3]);}}
            for(int v=0;v<points.size()&&!progress;v++){
                if(protect.contains(v)||incident.get(v).isEmpty())continue;
                for(int target:neighbors.get(v)){
                    int common=0;for(int n:neighbors.get(v))if(neighbors.get(target).contains(n))common++;if(common!=2)continue;
                    boolean valid=true;for(int f:incident.get(v)){
                        var t=faces.get(f);Vec3 a=points.get(t.a()),b=points.get(t.b()),c=points.get(t.c()),normal=ArmorMesh.unit(b.subtract(a).cross(c.subtract(a)));
                        if(Math.abs(normal.dot(points.get(target).subtract(points.get(v))))>planeTolerance){valid=false;break;}
                        if(t.a()==target||t.b()==target||t.c()==target)continue;
                        Vec3 na=points.get(t.a()==v?target:t.a()),nb=points.get(t.b()==v?target:t.b()),nc=points.get(t.c()==v?target:t.c());
                        if(nb.subtract(na).cross(nc.subtract(na)).dot(normal)<=1e-14){valid=false;break;}
                    }
                    if(!valid)continue;
                    var next=new ArrayList<Triangle>();for(var t:faces){int a=t.a()==v?target:t.a(),b=t.b()==v?target:t.b(),c=t.c()==v?target:t.c();if(a!=b&&b!=c&&a!=c)next.add(new Triangle(a,b,c));}
                    faces=next;progress=true;break;
                }
            }
        }
        return faces;
    }
    private ArmorMeshSimplifier(){}
}
