package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Runtime solid geometry, independent of the original block editor's point limit. */
public record ArmorMesh(List<Face> faces) {
    private static final double EPS = 1e-8;
    static Vec3 unit(Vec3 vector){double length=vector.length();return length<1e-15?Vec3.ZERO:vector.scale(1/length);}
    private static Vec3 normal(List<Vec3> vertices){Vec3 best=Vec3.ZERO;for(int i=1;i+1<vertices.size();i++){var n=vertices.get(i).subtract(vertices.getFirst()).cross(vertices.get(i+1).subtract(vertices.getFirst()));if(n.lengthSqr()>best.lengthSqr())best=n;}return unit(best);}
    public record Face(List<Vec3> vertices, Vec3 normal) {
        public Face(List<Vec3> vertices) {
            this(List.copyOf(vertices), ArmorMesh.normal(vertices));
        }
        Face flip() { var reversed = new ArrayList<>(vertices); Collections.reverse(reversed); return new Face(reversed, normal.reverse()); }
    }
    public static ArmorMesh of(Polyhedron poly) {
        return new ArmorMesh(poly.triangles().stream().map(t -> new Face(Arrays.stream(t.indices())
                .mapToObj(i -> { var p = poly.points().get(i); return new Vec3(p.x()/16, p.y()/16, p.z()/16); }).toList())).toList());
    }
    public double volume() { return Math.clamp(Math.abs(signedVolume()), 0, 1); }

    double signedVolume() {
        double volume = 0;
        for (var face : faces) for (int i = 1; i+1 < face.vertices.size(); i++)
            volume += face.vertices.getFirst().dot(face.vertices.get(i).cross(face.vertices.get(i+1))) / 6;
        return volume;
    }
    public record Hit(double fraction, Vec3 normal) {}
    public record Range(double min,double max) {}
    /** Occupied X intervals, used for contact decomposition of a user-edited surface. */
    public List<Range> scanline(double y,double z){
        record Crossing(double x,int sign){} var crossings=new ArrayList<Crossing>();
        for(var face:faces){if(Math.abs(face.normal.x)<EPS)continue;
            Vec3 first=face.vertices.getFirst();double x=first.x-(face.normal.y*(y-first.y)+face.normal.z*(z-first.z))/face.normal.x;var point=new Vec3(x,y,z);boolean inside=true;
            for(int i=0;i<face.vertices.size();i++){var a=face.vertices.get(i);var b=face.vertices.get((i+1)%face.vertices.size());if(b.subtract(a).cross(point.subtract(a)).dot(face.normal)<-EPS){inside=false;break;}}
            if(inside)crossings.add(new Crossing(x,face.normal.x<0?1:-1));
        }
        crossings.sort(Comparator.comparingDouble(Crossing::x));var intervals=new ArrayList<Range>();double start=0;boolean inside=false;
        for(int i=0;i<crossings.size();){double x=crossings.get(i).x;int sign=0;do{sign+=crossings.get(i++).sign;}while(i<crossings.size()&&Math.abs(crossings.get(i).x-x)<1e-8);
            if(sign>0&&!inside){start=x;inside=true;}else if(sign<0&&inside){if(x-start>EPS)intervals.add(new Range(start,x));inside=false;}}
        return intervals;
    }
    public Hit clip(Vec3 start, Vec3 end) {
        Vec3 ray = end.subtract(start); Hit nearest = null;
        for (var face : faces) {
            double denominator = face.normal.dot(ray);
            if (Math.abs(denominator) < EPS) continue;
            double t = face.normal.dot(face.vertices.getFirst().subtract(start)) / denominator;
            if (t < 1e-8 || t > 1+EPS || nearest != null && t >= nearest.fraction) continue;
            Vec3 point = start.add(ray.scale(t)); boolean inside = true;
            for (int i=0; i<face.vertices.size(); i++) {
                Vec3 a=face.vertices.get(i), b=face.vertices.get((i+1)%face.vertices.size());
                if (b.subtract(a).cross(point.subtract(a)).dot(face.normal) < -EPS) { inside=false; break; }
            }
            if (inside) nearest = new Hit(t, face.normal);
        }
        return nearest;
    }
    public ArmorMesh subtract(ArmorHole hole) {
        if (faces.isEmpty()) return this;
        Node a = new Node(faces), b = new Node(hole.prism());
        a.invert(); a.clipTo(b); b.clipTo(a); b.invert(); b.clipTo(a); b.invert();
        a.build(b.all()); a.invert(); return new ArmorMesh(List.copyOf(a.all()));
    }

    ArmorMesh transform(java.util.function.UnaryOperator<Vec3> position) {
        return new ArmorMesh(faces.stream().map(face ->
            new Face(face.vertices.stream().map(position).toList(),face.normal)).toList());
    }

    ArmorMesh union(ArmorMesh other) {
        Node a = groupTree(faces), b = groupTree(other.faces);
        a.clipTo(b);
        b.clipTo(a);
        b.invert();
        b.clipTo(a);
        b.invert();
        var result = a.all();
        result.addAll(b.all());
        return new ArmorMesh(List.copyOf(result));
    }

    /** Reuses the edited solid's partition tree while intersecting individual cells. */
    static final class CellCutter {
        private final Node solid;
        CellCutter(ArmorMesh mesh) { solid = groupTree(mesh.faces); }

        ArmorMesh intersect(Vec3 min) {
            var box = new ArrayList<Face>();
            for (var face : Polyhedron.CUBE.faces()) {
                box.add(new Face(face.vertices().stream().map(p -> min.add(p.x()/16,p.y()/16,p.z()/16)).toList()));
            }
            Node a = solid.copy(), b = new Node(box);
            a.invert();
            b.clipTo(a);
            b.invert();
            a.clipTo(b);
            b.clipTo(a);
            var polygons = a.all();
            polygons.addAll(b.all());
            return new ArmorMesh(polygons.stream().map(Face::flip).toList());
        }
    }

    private static Node groupTree(List<Face> polygons) {
        var tree = new Node();
        tree.build(polygons, new GroupBudget(), 0);
        return tree;
    }

    private static final class GroupBudget {
        private int remaining = 350_000;
        void use(int polygonCount, int depth) {
            remaining -= polygonCount;
            if (remaining < 0 || depth >= 384) throw new IllegalArgumentException("multi_complex");
        }
    }

    private static final class Node {
        Vec3 normal; double distance; List<Face> polygons = new ArrayList<>(); Node front, back;
        Node() {}
        Node(List<Face> faces) { build(faces); }
        Node copy() {
            var result = new Node();
            result.normal = normal;
            result.distance = distance;
            result.polygons = new ArrayList<>(polygons);
            if (front != null) result.front = front.copy();
            if (back != null) result.back = back.copy();
            return result;
        }
        void invert() {
            polygons = new ArrayList<>(polygons.stream().map(Face::flip).toList());
            if (normal != null) { normal=normal.reverse(); distance=-distance; }
            if (front!=null) front.invert(); if (back!=null) back.invert();
            Node swap=front; front=back; back=swap;
        }
        List<Face> all() {
            var result=new ArrayList<>(polygons); if(front!=null)result.addAll(front.all()); if(back!=null)result.addAll(back.all()); return result;
        }
        List<Face> clip(List<Face> source) {
            if(normal==null)return new ArrayList<>(source);
            var f=new ArrayList<Face>(); var b=new ArrayList<Face>();
            for(var polygon:source)split(polygon,f,b,f,b);
            if(front!=null) f=new ArrayList<>(front.clip(f));
            if(back!=null) b=new ArrayList<>(back.clip(b)); else b.clear();
            f.addAll(b); return f;
        }
        void clipTo(Node other) { polygons=other.clip(polygons); if(front!=null)front.clipTo(other); if(back!=null)back.clipTo(other); }
        void build(List<Face> source) { build(source,null,0); }
        void build(List<Face> source, GroupBudget budget, int depth) {
            if(source.isEmpty())return;
            if(budget!=null)budget.use(source.size(),depth);
            if(normal==null) { normal=source.getFirst().normal; distance=normal.dot(source.getFirst().vertices.getFirst()); }
            var f=new ArrayList<Face>(); var b=new ArrayList<Face>();
            for(var polygon:source)split(polygon,polygons,polygons,f,b);
            if(!f.isEmpty()){if(front==null)front=new Node();front.build(f,budget,depth+1);}
            if(!b.isEmpty()){if(back==null)back=new Node();back.build(b,budget,depth+1);}
        }
        void split(Face polygon,List<Face> coplanarFront,List<Face> coplanarBack,List<Face> f,List<Face> b) {
            int kind=0; int[] types=new int[polygon.vertices.size()];
            for(int i=0;i<types.length;i++) { double t=normal.dot(polygon.vertices.get(i))-distance; types[i]=t < -EPS?2:t>EPS?1:0; kind|=types[i]; }
            if(kind==0){(normal.dot(polygon.normal)>0?coplanarFront:coplanarBack).add(polygon);return;}
            if(kind==1){f.add(polygon);return;} if(kind==2){b.add(polygon);return;}
            var fp=new ArrayList<Vec3>(); var bp=new ArrayList<Vec3>();
            for(int i=0;i<types.length;i++) {
                int j=(i+1)%types.length, ti=types[i], tj=types[j]; Vec3 vi=polygon.vertices.get(i), vj=polygon.vertices.get(j);
                if(ti!=2)fp.add(vi);if(ti!=1)bp.add(vi);
                if((ti|tj)==3) { Vec3 p=vi.lerp(vj,(distance-normal.dot(vi))/normal.dot(vj.subtract(vi)));fp.add(p);bp.add(p); }
            }
            add(fp,polygon.normal,f); add(bp,polygon.normal,b);
        }
        void add(List<Vec3> points,Vec3 n,List<Face> out) {
            var clean=new ArrayList<Vec3>(); for(var p:points)if(clean.isEmpty()||clean.getLast().distanceToSqr(p)>1e-18)clean.add(p);
            if(clean.size()>1&&clean.getFirst().distanceToSqr(clean.getLast())<1e-18)clean.removeLast();
            if(clean.size()>=3)out.add(new Face(List.copyOf(clean),n));
        }
    }
}
