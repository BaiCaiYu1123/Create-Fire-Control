// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.geometry;

import java.util.*;

/** Immutable convex hull or closed editable mesh on an exact 0.001-pixel lattice. */
public final class Polyhedron {
    public static final int MAX_POINTS = 256;
    public static final int MAX_BOXES = 64;
    public static final int MAX_TRIANGLES = 1344;
    private static final int EXHAUSTIVE_HULL_LIMIT = 64;
    public static final int PIXEL_SCALE = 1000;
    public static final int MAX_PACKED_POINTS = 1 + 3 * MAX_POINTS;
    private static final int PRECISE_POINTS = -1;

    public static final class Point implements Comparable<Point> {
        private final double x, y, z;

        public Point(double x, double y, double z) {
            this(x, y, z, ShapeBounds.BLOCK);
        }

        static Point inBounds(double x, double y, double z, ShapeBounds bounds) {
            return new Point(x, y, z, bounds);
        }

        private Point(double x, double y, double z, ShapeBounds bounds) {
            this.x = coordinate(x, bounds.x());
            this.y = coordinate(y, bounds.y());
            this.z = coordinate(z, bounds.z());
        }

        private static double coordinate(double value, int extent) {
            double ticks = value * PIXEL_SCALE;
            if (!Double.isFinite(value) || value < 0 || value > extent || Math.abs(ticks - Math.rint(ticks)) > 1e-7)
                throw new IllegalArgumentException("range");
            return Math.round(ticks) / (double) PIXEL_SCALE;
        }

        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public int tx() { return (int) Math.round(x * PIXEL_SCALE); }
        public int ty() { return (int) Math.round(y * PIXEL_SCALE); }
        public int tz() { return (int) Math.round(z * PIXEL_SCALE); }

        public boolean legacyPackable() {
            return x <= 16 && y <= 16 && z <= 16 && tx() % 100 == 0 && ty() % 100 == 0 && tz() % 100 == 0;
        }

        /** Legacy point encoding. Use the document or geometry codec for three-decimal coordinates. */
        public int packed() {
            if (!legacyPackable()) throw new IllegalArgumentException("range");
            if (tx() % PIXEL_SCALE == 0 && ty() % PIXEL_SCALE == 0 && tz() % PIXEL_SCALE == 0)
                return (int) x << 10 | (int) y << 5 | (int) z;
            return 1 << 24 | (tx() / 100) << 16 | (ty() / 100) << 8 | (tz() / 100);
        }

        public static Point unpack(int n) {
            if ((n & 1 << 24) != 0) {
                if (n < 0 || n >>> 25 != 0) throw new IllegalArgumentException("range");
                return new Point((n >> 16 & 255) / 10.0, (n >> 8 & 255) / 10.0, (n & 255) / 10.0);
            }
            if (n < 0 || n >>> 15 != 0) throw new IllegalArgumentException("range");
            return new Point(n >> 10, n >> 5 & 31, n & 31);
        }

        @Override public int compareTo(Point other) {
            int c = Double.compare(x, other.x);
            if (c == 0) c = Double.compare(y, other.y);
            return c == 0 ? Double.compare(z, other.z) : c;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Point p && x == p.x && y == p.y && z == p.z;
        }

        @Override public int hashCode() {
            return (Double.hashCode(x) * 31 + Double.hashCode(y)) * 31 + Double.hashCode(z);
        }

        @Override public String toString() { return "Point[x=" + x + ", y=" + y + ", z=" + z + "]"; }
        public double axis(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
        public Point withAxis(int axis, double value) { return ShapeBounds.BLOCK.withAxis(this, axis, value); }
    }

    public record Plane(long x, long y, long z, long d) {
        public double distance(double a, double b, double c) { return x * a + y * b + z * c - d / (double) PIXEL_SCALE; }
        long side(Point p) { return x * p.tx() + y * p.ty() + z * p.tz() - d; }
    }
    public record Face(Plane plane, List<Point> vertices) {}
    public record Triangle(int a, int b, int c) {
        public Triangle reversed() { return new Triangle(a,c,b); }
        public boolean uses(int i) { return a==i || b==i || c==i; }
        public int[] indices() { return new int[]{a,b,c}; }
    }
    public record Edge(Point a, Point b) {}
    /** Box coordinates use the same 0..16 units as points. */
    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}

    private static final Map<List<Point>, Polyhedron> CACHE = new LinkedHashMap<>(256, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<List<Point>, Polyhedron> e) { return size() > 256; }
    };
    public static final List<Point> CUBE_POINTS = List.of(new Point(0,0,0), new Point(0,0,16), new Point(0,16,0),
        new Point(0,16,16), new Point(16,0,0), new Point(16,0,16), new Point(16,16,0), new Point(16,16,16));
    public static final Polyhedron CUBE = of(CUBE_POINTS);
    private final List<Point> points;
    private final List<Face> faces;
    private final Box bounds;
    private final ShapeBounds editBounds;
    private final List<Triangle> topology;
    private final boolean closed;
    private volatile List<Edge> edges;
    private volatile List<Box> boxes;
    private record MeshKey(List<Point> points,List<Triangle> topology,ShapeBounds bounds) {}
    private static final Map<MeshKey,Polyhedron> MESH_CACHE = new LinkedHashMap<>(256,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<MeshKey,Polyhedron> e) { return size()>256; }
    };

    public List<Point> points() { return points; }
    public ShapeBounds editBounds() { return editBounds; }
    public List<Face> faces() { return faces; }
    public boolean freeform() { return topology != null; }
    public List<Triangle> triangles() { return topology == null ? MeshGeometry.triangulateHull(this,points) : topology; }
    public int[] packedTriangles() { return topology == null ? new int[0] : topology.stream().flatMapToInt(t->Arrays.stream(t.indices())).toArray(); }
    public int[] packed() {
        if (points.stream().allMatch(Point::legacyPackable)) return points.stream().mapToInt(Point::packed).toArray();
        if (editBounds.multiBlock()) throw new IllegalArgumentException("range");
        int[] data = new int[1 + 3 * points.size()];
        data[0] = PRECISE_POINTS;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            data[1 + 3 * i] = p.tx();
            data[2 + 3 * i] = p.ty();
            data[3 + 3 * i] = p.tz();
        }
        return data;
    }

    private static List<Point> unpackPoints(int[] data) {
        if (data.length > 0 && data[0] == PRECISE_POINTS) {
            if (data.length > MAX_PACKED_POINTS || (data.length - 1) % 3 != 0) throw new IllegalArgumentException("count");
            List<Point> points = new ArrayList<>();
            for (int i = 1; i < data.length; i += 3)
                points.add(new Point(data[i] / (double) PIXEL_SCALE, data[i + 1] / (double) PIXEL_SCALE, data[i + 2] / (double) PIXEL_SCALE));
            return points;
        }
        if (data.length > MAX_POINTS) throw new IllegalArgumentException("count");
        return Arrays.stream(data).mapToObj(Point::unpack).toList();
    }

    public static Polyhedron fromPacked(int[] data) {
        return of(unpackPoints(data));
    }

    public static Polyhedron fromPacked(int[] data,int[] triangles) {
        if(triangles.length==0)return fromPacked(data);
        if(triangles.length>MAX_TRIANGLES*3 || triangles.length%3!=0)throw new IllegalArgumentException("mesh");
        List<Triangle> faces=new ArrayList<>();
        for(int i=0;i<triangles.length;i+=3)faces.add(new Triangle(triangles[i],triangles[i+1],triangles[i+2]));
        return mesh(unpackPoints(data),faces);
    }

    private static void checkBounds(List<Point> points, List<Triangle> triangles, ShapeBounds bounds) {
        if (points.size() > bounds.maxPoints() || triangles.size() > bounds.maxTriangles()) throw new IllegalArgumentException("count");
        points.forEach(bounds::require);
    }

    public static Polyhedron mesh(List<Point> points,List<Triangle> triangles) {
        return mesh(points, triangles, ShapeBounds.BLOCK);
    }

    public static Polyhedron mesh(List<Point> points,List<Triangle> triangles,ShapeBounds bounds) {
        checkBounds(points, triangles, bounds);
        var key=new MeshKey(List.copyOf(points),List.copyOf(triangles),bounds);
        synchronized(MESH_CACHE){var known=MESH_CACHE.get(key);if(known!=null)return known;}
        var built=new Polyhedron(key.points,key.topology,true,bounds);
        synchronized(MESH_CACHE){MESH_CACHE.put(key,built);}
        return built;
    }
    /** Closed arbitrary mesh with winding inferred consistently, including nested cavities. */
    public static Polyhedron orientedMesh(List<Point> points,List<Triangle> triangles) {
        return orientedMesh(points, triangles, ShapeBounds.BLOCK);
    }
    public static Polyhedron orientedMesh(List<Point> points,List<Triangle> triangles,ShapeBounds bounds) {
        checkBounds(points, triangles, bounds);
        return mesh(points,MeshGeometry.orient(points,triangles),bounds);
    }
    /** Display-only open surface. Never use an unfinished surface for solid collision. */
    public static Polyhedron surface(List<Point> points,List<Triangle> triangles) {
        return surface(points, triangles, ShapeBounds.BLOCK);
    }
    public static Polyhedron surface(List<Point> points,List<Triangle> triangles,ShapeBounds bounds) {
        checkBounds(points, triangles, bounds);
        List<Triangle> drawable=new ArrayList<>();
        for(var t:triangles){
            for(int id:t.indices())if(id<0 || id>=points.size())throw new IllegalArgumentException("mesh");
            try{trianglePlane(points.get(t.a()),points.get(t.b()),points.get(t.c()));drawable.add(t);}catch(IllegalArgumentException collapsed){/* A collapsed draft face remains in its document for repair. */}
        }
        return new Polyhedron(List.copyOf(points),List.copyOf(drawable),false,bounds);
    }
    public boolean closed(){return closed;}
    /** Triangulates a simple planar polygon in the supplied perimeter order. */
    public static List<Triangle> triangulatePolygon(List<Point> points,List<Integer> perimeter) {
        return MeshGeometry.polygon(points,perimeter);
    }
    public static Polyhedron freeformFromHull(List<Point> points) {
        var hull=of(points);
        return mesh(points,MeshGeometry.attachUnused(points,MeshGeometry.triangulateHull(hull,points)));
    }
    public static Polyhedron editedMesh(List<Point> points,List<Triangle> topology) {
        return mesh(points,MeshGeometry.attachUnused(points,topology));
    }
    public Polyhedron editVertices(List<Point> points) {
        if(!freeform())return of(points,editBounds);
        if(points.size()<this.points.size())throw new IllegalArgumentException("mesh");
        return mesh(points,MeshGeometry.attachUnused(points,topology),editBounds);
    }
    public Polyhedron removeVertex(int index) {
        return freeform()?MeshGeometry.removeVertex(points,topology,index,editBounds):of(java.util.stream.IntStream.range(0,points.size()).filter(i->i!=index).mapToObj(points::get).toList(),editBounds);
    }
    public static Polyhedron removeMeshVertex(List<Point> points,List<Triangle> topology,int index) {
        return removeMeshVertex(points,topology,index,ShapeBounds.BLOCK);
    }
    public static Polyhedron removeMeshVertex(List<Point> points,List<Triangle> topology,int index,ShapeBounds bounds) {
        return MeshGeometry.removeVertex(points,topology,index,bounds);
    }
    public Polyhedron transformPoints(java.util.function.UnaryOperator<Point> transform) {
        List<Point> mapped=points.stream().map(transform).toList();
        if(!freeform())return of(mapped,editBounds);
        var faces=MeshGeometry.signedVolume(mapped,topology)<0?topology.stream().map(Triangle::reversed).toList():topology;
        return mesh(mapped,faces,editBounds);
    }
    public static Polyhedron of(Collection<Point> input) {
        return of(input, ShapeBounds.BLOCK);
    }
    public static Polyhedron of(Collection<Point> input, ShapeBounds bounds) {
        input.forEach(bounds::require);
        if (input.size() > bounds.maxPoints()) throw new IllegalArgumentException("count");
        List<Point> key = List.copyOf(new TreeSet<>(input));
        if (key.size() < 4) throw new IllegalArgumentException("count");
        if (bounds.multiBlock()) return new Polyhedron(key, bounds);
        synchronized (CACHE) {
            Polyhedron known = CACHE.get(key);
            if (known != null) return known;
        }
        Polyhedron built = new Polyhedron(key,bounds);
        synchronized (CACHE) { CACHE.put(key, built); }
        return built;
    }
    private Polyhedron(List<Point> pts,ShapeBounds editBounds) {
        this.editBounds = editBounds;
        points = pts;
        topology = null;
        closed=true;
        Set<Plane> planes = pts.size() > EXHAUSTIVE_HULL_LIMIT
                ? IncrementalHull.planes(pts) : exhaustivePlanes(pts);
        List<Face> result=new ArrayList<>();
        for(Plane plane:planes) result.add(new Face(plane, polygon(pts,plane)));
        faces=List.copyOf(result);
        bounds=new Box(pts.stream().mapToDouble(Point::x).min().orElseThrow(), pts.stream().mapToDouble(Point::y).min().orElseThrow(),
            pts.stream().mapToDouble(Point::z).min().orElseThrow(), pts.stream().mapToDouble(Point::x).max().orElseThrow(),
            pts.stream().mapToDouble(Point::y).max().orElseThrow(), pts.stream().mapToDouble(Point::z).max().orElseThrow());
    }

    private static Set<Plane> exhaustivePlanes(List<Point> pts) {
        Set<Plane> planes = new LinkedHashSet<>();
        boolean volume = false;
        // Exact integer half-space tests avoid epsilon cracks and ambiguous coplanar triangles.
        for (int i=0;i<pts.size()-2;i++) for(int j=i+1;j<pts.size()-1;j++) for(int k=j+1;k<pts.size();k++) {
            Point a=pts.get(i), b=pts.get(j), c=pts.get(k);
            long ux=b.tx()-a.tx(), uy=b.ty()-a.ty(), uz=b.tz()-a.tz(), vx=c.tx()-a.tx(), vy=c.ty()-a.ty(), vz=c.tz()-a.tz();
            long nx=uy*vz-uz*vy, ny=uz*vx-ux*vz, nz=ux*vy-uy*vx;
            long g=gcd(gcd(Math.abs(nx),Math.abs(ny)),Math.abs(nz));
            if(g==0) continue;
            nx/=g; ny/=g; nz/=g;
            long d=nx*a.tx()+ny*a.ty()+nz*a.tz();
            boolean positive=false, negative=false;
            for(Point p:pts) {
                long side=nx*p.tx()+ny*p.ty()+nz*p.tz()-d;
                positive |= side>0; negative |= side<0;
                if(positive && negative) break;
            }
            volume |= positive || negative;
            if(positive && negative || !positive && !negative) continue;
            planes.add(positive ? new Plane(-nx,-ny,-nz,-d) : new Plane(nx,ny,nz,d));
        }
        if(!volume || planes.size()<4) throw new IllegalArgumentException("flat");
        return planes;
    }
    private Polyhedron(List<Point> pts,List<Triangle> topology,boolean closed,ShapeBounds editBounds) {
        this.editBounds=editBounds;
        points=pts;this.topology=topology;
        this.closed=closed;
        faces=closed?MeshGeometry.validate(pts,topology):MeshGeometry.surfaceFaces(pts,topology);
        List<Point> used=faces.stream().flatMap(f->f.vertices.stream()).toList();
        if(used.isEmpty()){bounds=new Box(0,0,0,0,0,0);return;}
        bounds=new Box(used.stream().mapToDouble(Point::x).min().orElseThrow(),used.stream().mapToDouble(Point::y).min().orElseThrow(),used.stream().mapToDouble(Point::z).min().orElseThrow(),
            used.stream().mapToDouble(Point::x).max().orElseThrow(),used.stream().mapToDouble(Point::y).max().orElseThrow(),used.stream().mapToDouble(Point::z).max().orElseThrow());
    }
    static Plane trianglePlane(Point a,Point b,Point c) {
        long ux=b.tx()-a.tx(),uy=b.ty()-a.ty(),uz=b.tz()-a.tz(),vx=c.tx()-a.tx(),vy=c.ty()-a.ty(),vz=c.tz()-a.tz();
        long x=uy*vz-uz*vy,y=uz*vx-ux*vz,z=ux*vy-uy*vx,g=gcd(gcd(Math.abs(x),Math.abs(y)),Math.abs(z));
        if(g==0)throw new IllegalArgumentException("mesh");
        x/=g;y/=g;z/=g;return new Plane(x,y,z,x*a.tx()+y*a.ty()+z*a.tz());
    }
    public List<Edge> edges() {
        if(edges!=null)return edges;
        record Pair(Point a,Point b) {}
        Map<Pair,List<Plane>> planes=new LinkedHashMap<>();
        for(var face:faces)for(int i=0;i<face.vertices.size();i++) {
            var a=face.vertices.get(i);var b=face.vertices.get((i+1)%face.vertices.size());
            var key=a.compareTo(b)<0?new Pair(a,b):new Pair(b,a);
            planes.computeIfAbsent(key,k->new ArrayList<>()).add(face.plane);
        }
        List<Edge> result=new ArrayList<>();
        for(var entry:planes.entrySet()) {
            var adjacent=entry.getValue();
            if(adjacent.size()==2 && adjacent.getFirst().equals(adjacent.getLast()))continue;
            result.add(new Edge(entry.getKey().a,entry.getKey().b));
        }
        return edges=List.copyOf(result);
    }
    private static long gcd(long a,long b) { while(b!=0) {long t=a%b;a=b;b=t;} return a; }
    public static int dominant(Plane p) {
        long x=Math.abs(p.x),y=Math.abs(p.y),z=Math.abs(p.z);
        return y>=x && y>=z ? 1 : x>=z ? 0 : 2;
    }
    private static int u(Point p,int axis) { return axis==0 ? p.tz() : p.tx(); }
    private static int v(Point p,int axis) { return axis==1 ? p.tz() : p.ty(); }
    private static long cross(Point a,Point b,Point c,int axis) {
        return (long)(u(b,axis)-u(a,axis))*(v(c,axis)-v(a,axis))-(long)(v(b,axis)-v(a,axis))*(u(c,axis)-u(a,axis));
    }
    private static List<Point> polygon(List<Point> points,Plane plane) {
        int axis=dominant(plane);
        List<Point> p=points.stream().filter(t->plane.side(t)==0)
            .sorted(Comparator.comparingInt((Point t)->u(t,axis)).thenComparingInt(t->v(t,axis))).toList();
        List<Point> h=new ArrayList<>();
        for(Point t:p) { while(h.size()>=2 && cross(h.get(h.size()-2),h.getLast(),t,axis)<=0) h.removeLast(); h.add(t); }
        int lower=h.size();
        for(int i=p.size()-2;i>=0;i--) { Point t=p.get(i); while(h.size()>lower && cross(h.get(h.size()-2),h.getLast(),t,axis)<=0) h.removeLast(); h.add(t); }
        h.removeLast();
        // The projected hull is counter-clockwise. Match its normal to the outward plane.
        if ((axis == 0 ? -plane.x : axis == 1 ? -plane.y : plane.z) < 0) Collections.reverse(h);
        return List.copyOf(h);
    }
    public boolean contains(double x,double y,double z) {
        if(!closed)return false;
        if(freeform())return MeshGeometry.contains(faces,x,y,z);
        return faces.stream().allMatch(f->f.plane.distance(x,y,z)<=1e-8);
    }
    public List<Box> collisionBoxes() {
        if(!closed)return List.of();
        List<Box> b=boxes;
        if(b!=null) return b;
        for(int resolution:new int[]{8,4,2}) {
            b=voxelize(resolution);
            if(b.size()<=MAX_BOXES) break;
        }
        boxes=List.copyOf(b);
        return boxes;
    }
    private List<Box> voxelize(int n) {
        double step=16.0/n;
        boolean[][][] cells=new boolean[n][n][n];
        for(int x=0;x<n;x++) for(int y=0;y<n;y++) for(int z=0;z<n;z++) {
            double x0=Math.max(x*step,bounds.x0), y0=Math.max(y*step,bounds.y0), z0=Math.max(z*step,bounds.z0);
            double x1=Math.min((x+1)*step,bounds.x1), y1=Math.min((y+1)*step,bounds.y1), z1=Math.min((z+1)*step,bounds.z1);
            if(x0>=x1 || y0>=y1 || z0>=z1) continue;
            boolean hit;
            if(freeform())hit=MeshGeometry.intersectsCell(faces,x0,y0,z0,x1,y1,z1);
            else {
            hit=true;
            // Conservative plane rejection: never loses thin valid hulls (unlike center sampling).
            for(Face f:faces) {Plane p=f.plane; if(p.distance(p.x>=0?x0:x1,p.y>=0?y0:y1,p.z>=0?z0:z1)>=0) {hit=false;break;}}
            }
            cells[x][y][z]=hit;
        }
        List<Box> result=new ArrayList<>();
        for(int x=0;x<n;x++) for(int y=0;y<n;y++) for(int z=0;z<n;z++) {
            if(!cells[x][y][z]) continue;
            int xe=x+1,ye=y+1,ze=z+1;
            while(xe<n && cells[xe][y][z]) xe++;
            outer: while(ye<n) {for(int a=x;a<xe;a++) if(!cells[a][ye][z]) break outer; ye++;}
            outer: while(ze<n) {for(int a=x;a<xe;a++) for(int b=y;b<ye;b++) if(!cells[a][b][ze]) break outer; ze++;}
            for(int a=x;a<xe;a++) for(int b=y;b<ye;b++) for(int c=z;c<ze;c++) cells[a][b][c]=false;
            result.add(new Box(Math.max(x*step,bounds.x0),Math.max(y*step,bounds.y0),Math.max(z*step,bounds.z0),
                Math.min(xe*step,bounds.x1),Math.min(ye*step,bounds.y1),Math.min(ze*step,bounds.z1)));
        }
        return result;
    }
    public static List<Point> parse(String text) {
        return parse(text, ShapeBounds.BLOCK);
    }
    public static List<Point> parse(String text,ShapeBounds bounds) {
        List<Point> result=new ArrayList<>();
        for(String line:text.strip().split("\\R|;")) {
            if(line.isBlank()) continue;
            String[] xyz=line.strip().replace("(","").replace(")","").split("[\\s,，]+");
            if(xyz.length!=3) throw new IllegalArgumentException("format");
            try {result.add(bounds.point(Double.parseDouble(xyz[0]),Double.parseDouble(xyz[1]),Double.parseDouble(xyz[2])));}
            catch(NumberFormatException e) {throw new IllegalArgumentException("format");}
            if(result.size()>bounds.maxPoints()) throw new IllegalArgumentException("count");
        }
        return result;
    }
}
