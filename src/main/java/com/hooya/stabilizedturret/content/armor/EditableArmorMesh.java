package com.hooya.stabilizedturret.content.armor;

import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.UnaryOperator;

/** Welded editable topology of the actual perforated solid, without the base document's point limit. */
public record EditableArmorMesh(List<Vec3> vertices,List<Triangle> triangles,List<Integer> kinds,List<Edge> guides) {
    public static final int MAX_VERTICES=8192,MAX_TRIANGLES=16384;
    public record Triangle(int a,int b,int c){public int[] indices(){return new int[]{a,b,c};}}
    public record Edge(int a,int b){public Edge{if(a>b){int t=a;a=b;b=t;}}}
    public EditableArmorMesh(List<Vec3> vertices,List<Triangle> triangles){this(vertices,triangles,Collections.nCopies(vertices.size(),0),List.of());}
    public EditableArmorMesh {
        vertices=List.copyOf(vertices);triangles=List.copyOf(triangles);kinds=List.copyOf(kinds);guides=List.copyOf(guides);
        if(vertices.size()>MAX_VERTICES||triangles.size()>MAX_TRIANGLES||guides.size()>MAX_TRIANGLES*3)throw new IllegalArgumentException("mesh_limit");
        if(kinds.size()!=vertices.size()||kinds.stream().anyMatch(k->k< -1||k>2))throw new IllegalArgumentException("mesh");
        for(var p:vertices)if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||!Double.isFinite(p.z)
                ||p.x<0||p.x>1||p.y<0||p.y>1||p.z<0||p.z>1)throw new IllegalArgumentException("range");
        for(var t:triangles)for(int i:t.indices())if(i<0||i>=vertices.size())throw new IllegalArgumentException("mesh");
        for(var e:guides)if(e.a<0||e.b>=vertices.size()||e.a==e.b)throw new IllegalArgumentException("mesh");
    }
    private record Key(long x,long y,long z){Key(Vec3 p){this(Math.round(p.x*1e8),Math.round(p.y*1e8),Math.round(p.z*1e8));}}
    private static int index(Vec3 point,List<Vec3> points,Map<Key,Integer> indices){
        var p=new Vec3(Math.clamp(point.x,0,1),Math.clamp(point.y,0,1),Math.clamp(point.z,0,1));
        return indices.computeIfAbsent(new Key(p),key->{points.add(p);return points.size()-1;});
    }
    public static EditableArmorMesh from(ArmorMesh mesh){return ArmorMeshSimplifier.compact(triangulated(mesh),null);}
    public static EditableArmorMesh from(ArmorMesh mesh,EditableArmorMesh reference){return ArmorMeshSimplifier.compact(triangulated(mesh),reference);}
    public static EditableArmorMesh reference(com.caca.copypoly.api.PolyShape document){
        var points=new ArrayList<Vec3>();var kinds=new ArrayList<Integer>();var ids=new HashMap<Integer,Integer>();
        for(var v:document.vertices()){ids.put(v.id(),points.size());points.add(new Vec3(v.position().x()/16,v.position().y()/16,v.position().z()/16));kinds.add(v.type()==com.caca.copypoly.api.VertexType.CUSTOM?1:0);}
        var guides=document.connections().stream().map(e->new Edge(ids.get(e.source()),ids.get(e.target()))).distinct().toList();
        return new EditableArmorMesh(points,List.of(),kinds,guides);
    }
    static EditableArmorMesh triangulated(ArmorMesh mesh){
        var points=new ArrayList<Vec3>();var indices=new LinkedHashMap<Key,Integer>();
        for(var face:mesh.faces())for(var point:face.vertices())index(point,points,indices);
        var triangles=new ArrayList<Triangle>();
        var index = new EdgePointIndex(points);
        // Split T-junctions introduced by CSG. Adjacent polygons must share actual edge vertices.
        for(var face:mesh.faces()){
            var perimeter=new ArrayList<Integer>();
            for(int i=0;i<face.vertices().size();i++){
                var a=face.vertices().get(i);var b=face.vertices().get((i+1)%face.vertices().size());var edge=b.subtract(a);double length=edge.lengthSqr();
                if(length<1e-18)continue;
                var along=new TreeMap<Double,Integer>();along.put(0.0,index(a,points,indices));
                index.addInteriorPoints(a,b,edge,length,along);
                for(int id:along.values())if(perimeter.isEmpty()||perimeter.getLast()!=id)perimeter.add(id);
            }
            if(perimeter.size()<3)continue;
            if(perimeter.size()==3){triangles.add(new Triangle(perimeter.get(0),perimeter.get(1),perimeter.get(2)));continue;}
            Vec3 center=Vec3.ZERO;for(int id:perimeter)center=center.add(points.get(id));int c=index(center.scale(1.0/perimeter.size()),points,indices);
            for(int i=0;i<perimeter.size();i++)triangles.add(new Triangle(c,perimeter.get(i),perimeter.get((i+1)%perimeter.size())));
        }
        return new EditableArmorMesh(points,triangles);
    }

    /** Limits T-junction searches to the smallest coordinate range enclosing the edge. */
    private static final class EdgePointIndex {
        private static final double PADDING = 2e-8;
        private record Point(Vec3 position, int id) {}
        private final List<Point> original;
        private final List<List<Point>> axes;

        EdgePointIndex(List<Vec3> vertices) {
            var points = new ArrayList<Point>(vertices.size());
            for (int i = 0; i < vertices.size(); i++) points.add(new Point(vertices.get(i), i));
            original = points;
            if (points.size() <= 32) {
                axes = List.of();
            } else {
                axes = List.of(sorted(points, 0), sorted(points, 1), sorted(points, 2));
            }
        }

        void addInteriorPoints(Vec3 a, Vec3 b, Vec3 edge, double length, NavigableMap<Double, Integer> along) {
            List<Point> candidates = original;
            int from = 0;
            int to = candidates.size();
            for (int axis = 0; axis < axes.size(); axis++) {
                List<Point> sorted = axes.get(axis);
                double lower = Math.min(coord(a, axis), coord(b, axis)) - PADDING;
                double upper = Math.max(coord(a, axis), coord(b, axis)) + PADDING;
                int start = lowerBound(sorted, axis, lower);
                int end = lowerBound(sorted, axis, upper);
                if (end - start < to - from) {
                    candidates = sorted;
                    from = start;
                    to = end;
                }
            }
            for (int i = from; i < to; i++) {
                Point point = candidates.get(i);
                Vec3 p = point.position();
                double t = p.subtract(a).dot(edge) / length;
                if (t > 1e-8 && t < 1 - 1e-8 && a.add(edge.scale(t)).distanceToSqr(p) < 1e-16) {
                    // Original insertion order wins when several points have the same projection.
                    along.merge(t, point.id(), Math::max);
                }
            }
        }

        private static List<Point> sorted(List<Point> points, int axis) {
            var sorted = new ArrayList<>(points);
            sorted.sort(Comparator.comparingDouble(point -> coord(point.position(), axis)));
            return sorted;
        }

        private static int lowerBound(List<Point> points, int axis, double value) {
            int lo = 0;
            int hi = points.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (coord(points.get(mid).position(), axis) < value) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }
    }
    /** Only real creases and explicit player connections are editor lines; triangulation stays internal. */
    public List<Edge> edges(){var normals=new LinkedHashMap<Edge,Vec3>();var edges=new LinkedHashSet<Edge>();for(var t:triangles){var n=ArmorMesh.unit(vertices.get(t.b).subtract(vertices.get(t.a)).cross(vertices.get(t.c).subtract(vertices.get(t.a))));
        for(var e:List.of(new Edge(t.a,t.b),new Edge(t.b,t.c),new Edge(t.c,t.a))){var previous=normals.putIfAbsent(e,n);if(previous!=null&&previous.dot(n)<1-1e-8)edges.add(e);}}
        var counts=new HashMap<Edge,Integer>();for(var t:triangles)for(var e:List.of(new Edge(t.a,t.b),new Edge(t.b,t.c),new Edge(t.c,t.a)))counts.merge(e,1,Integer::sum);
        counts.forEach((e,n)->{if(n!=2)edges.add(e);});edges.addAll(guides);return List.copyOf(edges);}
    public EditableArmorMesh simplifyVertices() { return ArmorMeshSimplifier.simplify(this); }
    public EditableArmorMesh withTriangles(List<Triangle> value){return new EditableArmorMesh(vertices,value,kinds,guides);}
    /** Adds a player-drawn cut by flipping only invisible diagonals on the same planar patch. */
    public EditableArmorMesh connect(Edge line){
        var links=new LinkedHashSet<>(guides);if(links.remove(line))return new EditableArmorMesh(vertices,triangles,kinds,List.copyOf(links));
        var faces=new ArrayList<>(triangles);Vec3 origin=vertices.get(line.a),end=vertices.get(line.b),normal=null;
        for(var t:faces)if(t.a==line.a||t.b==line.a||t.c==line.a){var n=ArmorMesh.unit(vertices.get(t.b).subtract(vertices.get(t.a)).cross(vertices.get(t.c).subtract(vertices.get(t.a))));if(Math.abs(n.dot(end.subtract(origin)))<1e-8){normal=n;break;}}
        if(normal==null)throw new IllegalArgumentException("edge");int omit=Math.abs(normal.x)>=Math.abs(normal.y)&&Math.abs(normal.x)>=Math.abs(normal.z)?0:Math.abs(normal.y)>=Math.abs(normal.z)?1:2;
        for(int step=0;step<=triangles.size();step++){
            var incident=new LinkedHashMap<Edge,List<Integer>>();for(int i=0;i<faces.size();i++){var t=faces.get(i);for(var e:List.of(new Edge(t.a,t.b),new Edge(t.b,t.c),new Edge(t.c,t.a)))incident.computeIfAbsent(e,k->new ArrayList<>()).add(i);}
            if(incident.containsKey(line)){links.add(line);return new EditableArmorMesh(vertices,faces,kinds,List.copyOf(links));}
            boolean flipped=false;
            for(var entry:incident.entrySet()){
                var e=entry.getKey();var uses=entry.getValue();if(uses.size()!=2||links.contains(e)||!crossesLine(line,e,omit))continue;
                var first=faces.get(uses.get(0));var second=faces.get(uses.get(1));int a=other(first,e),b=other(second,e);var replacement=new Edge(a,b);
                if(a==b||crossesLine(line,replacement,omit)||!crossesLine(e,replacement,omit))continue;
                boolean planar=true;for(int id:new int[]{first.a,first.b,first.c,second.a,second.b,second.c})if(Math.abs(normal.dot(vertices.get(id).subtract(origin)))>1e-8){planar=false;break;}if(!planar)continue;
                faces.set(uses.get(0),oriented(a,b,e.a,normal));faces.set(uses.get(1),oriented(b,a,e.b,normal));flipped=true;break;
            }
            if(!flipped)break;
        }throw new IllegalArgumentException("edge");
    }
    private static int other(Triangle t,Edge e){return t.a!=e.a&&t.a!=e.b?t.a:t.b!=e.a&&t.b!=e.b?t.b:t.c;}
    private Triangle oriented(int a,int b,int c,Vec3 normal){return vertices.get(b).subtract(vertices.get(a)).cross(vertices.get(c).subtract(vertices.get(a))).dot(normal)>0?new Triangle(a,b,c):new Triangle(a,c,b);}
    private boolean crossesLine(Edge a,Edge b,int omit){if(a.a==b.a||a.a==b.b||a.b==b.a||a.b==b.b)return false;var p=vertices.get(a.a);var q=vertices.get(a.b);var r=vertices.get(b.a);var s=vertices.get(b.b);return cross2(p,q,r,omit)*cross2(p,q,s,omit)< -1e-18&&cross2(r,s,p,omit)*cross2(r,s,q,omit)< -1e-18;}
    public ArmorMesh mesh(){
        var result=new ArrayList<ArmorMesh.Face>();for(var t:triangles){var a=vertices.get(t.a);var b=vertices.get(t.b);var c=vertices.get(t.c);
            if(b.subtract(a).cross(c.subtract(a)).lengthSqr()>1e-18)result.add(new ArmorMesh.Face(List.of(a,b,c)));}
        return new ArmorMesh(List.copyOf(result));
    }
    public EditableArmorMesh move(Set<Integer> selection,Vec3 delta){
        return move(selection, delta, false);
    }
    public EditableArmorMesh move(Set<Integer> selection,Vec3 delta,boolean quantize){
        var points=new ArrayList<>(vertices);
        for(int id:selection){
            var p=points.get(id).add(delta);
            points.set(id,quantize ? new Vec3(delta.x==0?p.x:subpixel(p.x),delta.y==0?p.y:subpixel(p.y),delta.z==0?p.z:subpixel(p.z)) : p);
        }
        return new EditableArmorMesh(points,triangles,kinds,guides);
    }
    private static double subpixel(double coordinate) {
        return Math.round(Math.clamp(coordinate,0,1)*16000)/16000.0;
    }
    public EditableArmorMesh split(Edge edge){
        var points=new ArrayList<>(vertices);int id=points.size();points.add(vertices.get(edge.a).lerp(vertices.get(edge.b),.5));var faces=new ArrayList<Triangle>();
        for(var t:triangles){int[] v=t.indices();boolean split=false;for(int i=0;i<3;i++)if(new Edge(v[i],v[(i+1)%3]).equals(edge)){
            faces.add(new Triangle(v[i],id,v[(i+2)%3]));faces.add(new Triangle(id,v[(i+1)%3],v[(i+2)%3]));split=true;break;}
            if(!split)faces.add(t);}
        var tags=new ArrayList<>(kinds);tags.add(1);var links=new ArrayList<Edge>();for(var e:guides){if(e.equals(edge)){links.add(new Edge(e.a,id));links.add(new Edge(e.b,id));}else links.add(e);}
        return new EditableArmorMesh(points,faces,tags,links);
    }
    public EditableArmorMesh transform(UnaryOperator<Vec3> operation,boolean reverse){
        return new EditableArmorMesh(vertices.stream().map(operation).map(p->new Vec3(Math.clamp(p.x,0,1),Math.clamp(p.y,0,1),Math.clamp(p.z,0,1))).toList(),
                reverse?triangles.stream().map(t->new Triangle(t.a,t.c,t.b)).toList():triangles,kinds,guides);
    }
    /** Closed oriented surfaces and non-intersecting faces are required before server application. */
    public void validate(){
        if(vertices.size()<4||triangles.size()<4)throw new IllegalArgumentException("mesh");
        var counts=new HashMap<Edge,Integer>();var winding=new HashMap<Edge,Integer>();var unique=new HashSet<List<Integer>>();
        for(var t:triangles){var a=vertices.get(t.a);var b=vertices.get(t.b);var c=vertices.get(t.c);
            if(b.subtract(a).cross(c.subtract(a)).lengthSqr()<1e-18)throw new IllegalArgumentException("degenerate");
            int[] v=t.indices();int[] sorted=v.clone();Arrays.sort(sorted);if(!unique.add(Arrays.stream(sorted).boxed().toList()))throw new IllegalArgumentException("duplicate");
            for(int i=0;i<3;i++){var edge=new Edge(v[i],v[(i+1)%3]);counts.merge(edge,1,Integer::sum);winding.merge(edge,v[i]==edge.a?1:-1,Integer::sum);}
        }
        for(var edge:counts.keySet())if(counts.get(edge)!=2||winding.get(edge)!=0)throw new IllegalArgumentException("open_mesh");
        double signed=0;var bounds=new ArrayList<Bounds>(triangles.size());
        for(var t:triangles){Vec3 a=vertices.get(t.a),b=vertices.get(t.b),c=vertices.get(t.c);signed+=a.dot(b.cross(c))/6;bounds.add(new Bounds(new Vec3[]{a,b,c}));}
        if(signed<1e-8)throw new IllegalArgumentException("flat");
        // Sweep the cached bounds rather than allocating six points for every possible pair.
        bounds.sort(Comparator.comparingDouble(b->b.min[0]));
        for(int i=0;i<bounds.size();i++){var a=bounds.get(i);for(int j=i+1;j<bounds.size();j++){var b=bounds.get(j);
            if(b.min[0]>a.max[0]+1e-9)break;
            if(a.max[1]<b.min[1]-1e-9||b.max[1]<a.min[1]-1e-9||a.max[2]<b.min[2]-1e-9||b.max[2]<a.min[2]-1e-9)continue;
            if(intersects(a.points,b.points))throw new IllegalArgumentException("intersection");
        }}
    }
    private static final class Bounds {final Vec3[] points;final double[] min=new double[3],max=new double[3];Bounds(Vec3[] points){this.points=points;for(int a=0;a<3;a++){min[a]=Math.min(coord(points[0],a),Math.min(coord(points[1],a),coord(points[2],a)));max[a]=Math.max(coord(points[0],a),Math.max(coord(points[1],a),coord(points[2],a)));}}}
    private static boolean intersects(Vec3[] a,Vec3[] b){
        Vec3 normal=ArmorMesh.unit(a[1].subtract(a[0]).cross(a[2].subtract(a[0])));
        boolean coplanar=Arrays.stream(b).allMatch(p->Math.abs(normal.dot(p.subtract(a[0])))<1e-8);
        if(coplanar){int axis=Math.abs(normal.x)>=Math.abs(normal.y)&&Math.abs(normal.x)>=Math.abs(normal.z)?0:Math.abs(normal.y)>=Math.abs(normal.z)?1:2;
            for(int i=0;i<3;i++)for(int j=0;j<3;j++)if(cross2(a[i],a[(i+1)%3],b[j],axis)*cross2(a[i],a[(i+1)%3],b[(j+1)%3],axis)<-1e-16
                    &&cross2(b[j],b[(j+1)%3],a[i],axis)*cross2(b[j],b[(j+1)%3],a[(i+1)%3],axis)<-1e-16)return true;
            return inside2(a[0].add(a[1]).add(a[2]).scale(1.0/3),b,axis)||inside2(b[0].add(b[1]).add(b[2]).scale(1.0/3),a,axis);
        }
        for(int i=0;i<3;i++)if(segment(a[i],a[(i+1)%3],b)||segment(b[i],b[(i+1)%3],a))return true;
        return false;
    }
    private static double coord(Vec3 p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
    private static double cross2(Vec3 a,Vec3 b,Vec3 c,int omit){int x=(omit+1)%3,y=(omit+2)%3;return (coord(b,x)-coord(a,x))*(coord(c,y)-coord(a,y))-(coord(b,y)-coord(a,y))*(coord(c,x)-coord(a,x));}
    private static boolean inside2(Vec3 p,Vec3[] t,int axis){double a=cross2(t[0],t[1],p,axis),b=cross2(t[1],t[2],p,axis),c=cross2(t[2],t[0],p,axis);return a>1e-9&&b>1e-9&&c>1e-9||a< -1e-9&&b< -1e-9&&c< -1e-9;}
    private static boolean segment(Vec3 start,Vec3 end,Vec3[] t){
        Vec3 d=end.subtract(start),e1=t[1].subtract(t[0]),e2=t[2].subtract(t[0]),normal=ArmorMesh.unit(e1.cross(e2));
        double first=normal.dot(start.subtract(t[0])),last=normal.dot(end.subtract(t[0]));
        // Judge coplanar joins in world units, including the very thin faces made by CSG.
        if(!(first>1e-8&&last< -1e-8||first< -1e-8&&last>1e-8))return false;
        Vec3 p=d.cross(e2);double det=e1.dot(p);if(Math.abs(det)<1e-18)return false;
        Vec3 s=start.subtract(t[0]);double u=s.dot(p)/det;if(u<=1e-8||u>=1-1e-8)return false;var q=s.cross(e1);double v=d.dot(q)/det;
        if(v<=1e-8||u+v>=1-1e-8)return false;double f=e2.dot(q)/det;return f>1e-8&&f<1-1e-8;
    }
    public void encode(RegistryFriendlyByteBuf buf){buf.writeVarInt(vertices.size());for(var p:vertices){buf.writeDouble(p.x);buf.writeDouble(p.y);buf.writeDouble(p.z);}buf.writeVarInt(triangles.size());for(var t:triangles){buf.writeVarInt(t.a);buf.writeVarInt(t.b);buf.writeVarInt(t.c);}for(int k:kinds)buf.writeByte(k);buf.writeVarInt(guides.size());for(var e:guides){buf.writeVarInt(e.a);buf.writeVarInt(e.b);}}
    public static EditableArmorMesh decode(RegistryFriendlyByteBuf buf){
        int n=buf.readVarInt();if(n<0||n>MAX_VERTICES)throw new IllegalArgumentException("mesh_limit");var vertices=new ArrayList<Vec3>(n);for(int i=0;i<n;i++)vertices.add(new Vec3(buf.readDouble(),buf.readDouble(),buf.readDouble()));
        int m=buf.readVarInt();if(m<0||m>MAX_TRIANGLES)throw new IllegalArgumentException("mesh_limit");var triangles=new ArrayList<Triangle>(m);for(int i=0;i<m;i++)triangles.add(new Triangle(buf.readVarInt(),buf.readVarInt(),buf.readVarInt()));var kinds=new ArrayList<Integer>();for(int i=0;i<n;i++)kinds.add((int)buf.readByte());int count=buf.readVarInt();if(count<0||count>MAX_TRIANGLES*3)throw new IllegalArgumentException("mesh_limit");var guides=new ArrayList<Edge>();for(int i=0;i<count;i++)guides.add(new Edge(buf.readVarInt(),buf.readVarInt()));return new EditableArmorMesh(vertices,triangles,kinds,guides);
    }
    public CompoundTag save(){var tag=new CompoundTag();var points=new ListTag();for(var p:vertices){points.add(DoubleTag.valueOf(p.x));points.add(DoubleTag.valueOf(p.y));points.add(DoubleTag.valueOf(p.z));}tag.put("Vertices",points);tag.putIntArray("Triangles",triangles.stream().flatMapToInt(t->Arrays.stream(t.indices())).toArray());tag.putIntArray("Kinds",kinds);tag.putIntArray("Guides",guides.stream().flatMapToInt(e->java.util.stream.IntStream.of(e.a,e.b)).toArray());return tag;}
    public static EditableArmorMesh load(CompoundTag tag){var list=tag.getList("Vertices",Tag.TAG_DOUBLE);int[] faces=tag.getIntArray("Triangles");if(list.size()%3!=0||list.size()>MAX_VERTICES*3||faces.length%3!=0||faces.length>MAX_TRIANGLES*3)throw new IllegalArgumentException("mesh_limit");
        var vertices=new ArrayList<Vec3>();for(int i=0;i<list.size();i+=3)vertices.add(new Vec3(list.getDouble(i),list.getDouble(i+1),list.getDouble(i+2)));var triangles=new ArrayList<Triangle>();for(int i=0;i<faces.length;i+=3)triangles.add(new Triangle(faces[i],faces[i+1],faces[i+2]));var kinds=tag.contains("Kinds")?Arrays.stream(tag.getIntArray("Kinds")).boxed().toList():Collections.nCopies(vertices.size(),-1);int[] links=tag.getIntArray("Guides");if(links.length%2!=0||links.length>MAX_TRIANGLES*6)throw new IllegalArgumentException("mesh_limit");var guides=new ArrayList<Edge>();for(int i=0;i<links.length;i+=2)guides.add(new Edge(links[i],links[i+1]));return new EditableArmorMesh(vertices,triangles,kinds,guides);}
}
