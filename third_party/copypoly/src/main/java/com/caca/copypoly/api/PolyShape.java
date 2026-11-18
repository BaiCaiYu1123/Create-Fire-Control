// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.api;

import com.caca.copypoly.geometry.Polyhedron;
import com.caca.copypoly.geometry.MeshOpenings;
import com.caca.copypoly.geometry.ShapeBounds;
import com.caca.copypoly.geometry.RegionVertexRemoval;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Immutable editable shape document. Vertex IDs survive deletion of other vertices.
 * New connections are undirected cuts of a retained reference surface. Either vertex
 * type can participate; closed regions are triangulated automatically and retain their
 * topology while moving. Legacy directed custom-vertex documents remain readable.
 */
public final class PolyShape {
    public static final int MAX_CONNECTIONS=ShapeBounds.BLOCK.maxLinks();
    public static final int MAX_PACKED = maxPacked(ShapeBounds.BLOCK);
    public static int maxPacked(ShapeBounds bounds) { return 8 + 8 * bounds.maxPoints() + 3 * bounds.maxTriangles() + 2 * bounds.maxLinks(); }
    public record Vertex(int id,Polyhedron.Point position,VertexType type) {
        public Vertex { Objects.requireNonNull(position);Objects.requireNonNull(type);if(id<0 || id>1_000_000)throw invalid(); }
    }
    public record Connection(int source,int target) {}
    public enum PendingReason { NEEDS_CONNECTIONS, TARGET_NOT_ACTIVE, INVALID_BOUNDARY, INVALID_SURFACE }
    public record Compilation(Polyhedron geometry,Set<Integer> activeCustomVertices,Map<Integer,PendingReason> pending) {}
    private final List<Vertex> vertices;
    private final List<Connection> connections;
    private final List<Polyhedron.Triangle> baseFaces;
    private final boolean fixedBase,managed,manualMesh,lineMesh;
    private final Map<Integer,Polyhedron.Point> referencePositions;
    private final int nextId;
    private final ShapeBounds bounds;
    private volatile Compilation cached;
    private volatile SurfaceNetwork.Result cachedLines;

    private PolyShape(List<Vertex> vertices,List<Connection> connections,List<Polyhedron.Triangle> baseFaces,
                      boolean fixedBase,int nextId,boolean managed,boolean manualMesh,boolean lineMesh,
                      Map<Integer,Polyhedron.Point> referencePositions,ShapeBounds bounds) {
        this.bounds = bounds;
        this.vertices=List.copyOf(vertices);this.connections=List.copyOf(connections);this.baseFaces=List.copyOf(baseFaces);
        this.fixedBase=fixedBase;this.nextId=nextId;this.managed=managed;this.manualMesh=manualMesh;this.lineMesh=lineMesh;this.referencePositions=Map.copyOf(referencePositions);
        if(vertices.size()>bounds.maxPoints() || connections.size()>bounds.maxLinks() || baseFaces.size()>bounds.maxTriangles() || nextId<0 || nextId>1_000_001)throw invalid();
        vertices.forEach(v -> bounds.require(v.position));
        referencePositions.values().forEach(bounds::require);
        Map<Integer,Vertex> ids=new HashMap<>();
        for(Vertex vertex:vertices)if(vertex.id>=nextId || ids.put(vertex.id,vertex)!=null)throw invalid();
        Set<Connection> unique=new HashSet<>();
        for(Connection link:connections)if(!unique.add(link) || link.source==link.target || !ids.containsKey(link.source) || !ids.containsKey(link.target) || lineMesh && link.source>link.target || !lineMesh && !manualMesh && ids.get(link.source).type!=VertexType.CUSTOM)throw invalid();
        for(var face:baseFaces)for(int id:face.indices())if(!ids.containsKey(id) || !manualMesh && !lineMesh && ids.get(id).type!=VertexType.NORMAL)throw invalid();
        if(!fixedBase && !baseFaces.isEmpty())throw invalid();
        if(lineMesh && (!fixedBase || manualMesh || !referencePositions.keySet().equals(ids.keySet())))throw invalid();
        if(!lineMesh && !referencePositions.isEmpty())throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("graph");}
    public List<Vertex> vertices(){return vertices;}
    public List<Connection> connections(){return connections;}
    public List<Polyhedron.Triangle> baseFaces(){return baseFaces;}
    public boolean hasExplicitBaseFaces(){return fixedBase;}
    public boolean managed(){return managed;}
    public boolean manualMesh(){return manualMesh;}
    public boolean lineMesh(){return lineMesh;}
    public Vertex vertex(int id){return vertices.stream().filter(v->v.id==id).findFirst().orElseThrow(PolyShape::invalid);}
    public List<Integer> targets(int id){return lineMesh || manualMesh?connections.stream().filter(c->c.source==id || c.target==id).map(c->c.source==id?c.target:c.source).toList():connections.stream().filter(c->c.source==id).map(Connection::target).toList();}
    public boolean isDrawnEdge(int a,int b){return lineMesh?lines().cutSegments().contains(edge(a,b)):connections.stream().anyMatch(c->edge(c.source,c.target)==edge(a,b));}
    public Set<Connection> pendingConnections(){return lineMesh?lines().pendingLinks():Set.of();}
    public Map<Integer,PendingReason> pendingVertices(){return lineMesh?lines().pending():compile().pending();}
    private SurfaceNetwork.Result lines(){var value=cachedLines;if(value==null)cachedLines=value=SurfaceNetwork.compile(vertices,baseFaces,referencePositions,connections);return value;}
    public Builder toBuilder(){return new Builder(this);}
    public ShapeBounds bounds(){return bounds;}
    public static Builder builder(){return builder(ShapeBounds.BLOCK);}
    public static Builder builder(ShapeBounds bounds){return new Builder(bounds);}
    public static PolyShape fromGeometry(Polyhedron geometry) {
        List<Vertex> vertices=new ArrayList<>();
        for(int i=0;i<geometry.points().size();i++)vertices.add(new Vertex(i,geometry.points().get(i),VertexType.NORMAL));
        var shape=new PolyShape(vertices,List.of(),geometry.freeform()?geometry.triangles():List.of(),geometry.freeform(),vertices.size(),false,false,false,Map.of(),geometry.editBounds());
        shape.cached=new Compilation(geometry,Set.of(),Map.of());return shape;
    }
    public PolyShape withPositions(Map<Integer,Polyhedron.Point> positions) {
        var builder=toBuilder();for(var entry:positions.entrySet())if(!vertex(entry.getKey()).position.equals(entry.getValue()))builder.moveVertex(entry.getKey(),entry.getValue());return builder.build();
    }
    public PolyShape transformPositions(UnaryOperator<Polyhedron.Point> transform) {
        var builder=toBuilder();for(var vertex:vertices)builder.moveVertex(vertex.id,transform.apply(vertex.position));
        if(lineMesh)referencePositions.forEach((id,p)->builder.reference.put(id,transform.apply(p)));
        if(fixedBase) {
            double volume=0;
            for(var t:baseFaces) {
                var a=builder.get(t.a()).position;var b=builder.get(t.b()).position;var c=builder.get(t.c()).position;
                volume+=a.x()*(b.y()*c.z()-b.z()*c.y())+a.y()*(b.z()*c.x()-b.x()*c.z())+a.z()*(b.x()*c.y()-b.y()*c.x());
            }
            if(volume<0)builder.faces.replaceAll(Polyhedron.Triangle::reversed);
        }
        return builder.build();
    }
    public Compilation compile() {
        Compilation result=cached;if(result!=null)return result;
        Map<Integer,Integer> index=new HashMap<>();for(int i=0;i<vertices.size();i++)index.put(vertices.get(i).id,i);
        List<Polyhedron.Point> points=vertices.stream().map(Vertex::position).toList();
        if(lineMesh) {
            var lines=lines();var geometry=orientSurface(points,lines.triangles().stream().map(t->new Polyhedron.Triangle(index.get(t.a()),index.get(t.b()),index.get(t.c()))).toList());
            Set<Integer> active=new LinkedHashSet<>();for(var v:vertices)if(v.type==VertexType.CUSTOM && lines.active().contains(v.id))active.add(v.id);
            return cached=new Compilation(geometry,Set.copyOf(active),lines.pending());
        }
        if(manualMesh) {
            var triangles=baseFaces.stream().map(t->new Polyhedron.Triangle(index.get(t.a()),index.get(t.b()),index.get(t.c()))).toList();
            var geometry=orientSurface(points,triangles);
            Set<Integer> used=new HashSet<>();for(var t:baseFaces)for(int id:t.indices())used.add(id);
            Set<Integer> active=new LinkedHashSet<>();Map<Integer,PendingReason> pending=new LinkedHashMap<>();
            for(var v:vertices)if(v.type==VertexType.CUSTOM){if(used.contains(v.id))active.add(v.id);else pending.put(v.id,PendingReason.NEEDS_CONNECTIONS);}
            return cached=new Compilation(geometry,Set.copyOf(active),Map.copyOf(pending));
        }
        List<Polyhedron.Triangle> surface;
        Polyhedron hull=null;
        if(fixedBase)surface=new ArrayList<>(baseFaces);
        else {
            var normal=vertices.stream().filter(v->v.type==VertexType.NORMAL).toList();
            hull=Polyhedron.of(normal.stream().map(Vertex::position).toList(),bounds);
            List<Integer> ids=hull.points().stream().map(p->normal.stream().filter(v->v.position.equals(p)).findFirst().orElseThrow().id).toList();
            surface=new ArrayList<>(hull.triangles().stream().map(t->new Polyhedron.Triangle(ids.get(t.a()),ids.get(t.b()),ids.get(t.c()))).toList());
        }
        Polyhedron geometry=(!fixedBase && vertices.stream().allMatch(v->v.type==VertexType.NORMAL))?hull:geometry(points,index,surface);
        // Validate a fixed base even with no custom vertices; explicit mesh API must be safe.
        if(fixedBase)geometry=geometry(points,index,surface);
        Set<Integer> active=new LinkedHashSet<>();Map<Integer,PendingReason> pending=new LinkedHashMap<>();
        List<Vertex> remaining=new ArrayList<>(vertices.stream().filter(v->v.type==VertexType.CUSTOM).toList());
        for(int pass=0;pass<vertices.size() && !remaining.isEmpty();pass++) {
            boolean progress=false;
            for(var custom:new ArrayList<>(remaining)) {
                var targets=targets(custom.id);
                if(targets.size()<2){pending.put(custom.id,PendingReason.NEEDS_CONNECTIONS);remaining.remove(custom);continue;}
                Set<Integer> used=new HashSet<>();for(var t:surface)for(int id:t.indices())used.add(id);
                if(!used.containsAll(targets)){pending.put(custom.id,PendingReason.TARGET_NOT_ACTIVE);continue;}
                var candidate=split(surface,custom.id,new LinkedHashSet<>(targets));
                if(candidate==null){pending.put(custom.id,PendingReason.INVALID_BOUNDARY);continue;}
                try {
                    var compiled=geometry(points,index,candidate);surface=candidate;geometry=compiled;
                    active.add(custom.id);pending.remove(custom.id);remaining.remove(custom);progress=true;
                }catch(IllegalArgumentException bad){pending.put(custom.id,PendingReason.INVALID_SURFACE);}
            }
            if(!progress)break;
        }
        return cached=new Compilation(geometry,Set.copyOf(active),Map.copyOf(pending));
    }
    private Polyhedron orientSurface(List<Polyhedron.Point> points,List<Polyhedron.Triangle> triangles) {
        if (bounds.multiBlock()) {
            // A ramp tip can meet its bottom edge. Weld those coincident controls only in the solid.
            var indices = new HashMap<Polyhedron.Point,Integer>();
            int[] welded = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                Integer first = indices.putIfAbsent(points.get(i),i);
                welded[i] = first == null ? i : first;
            }
            triangles = triangles.stream().map(t -> new Polyhedron.Triangle(welded[t.a()],welded[t.b()],welded[t.c()]))
                .filter(t -> t.a() != t.b() && t.b() != t.c() && t.a() != t.c()).toList();
        }
        return Polyhedron.orientedMesh(points,triangles,bounds);
    }

    /** Actual triangulated surface with stable vertex IDs. Also works for an open manual draft. */
    public List<Polyhedron.Triangle> surfaceTriangles() {
        if(lineMesh)return lines().triangles();
        if(manualMesh)return baseFaces;
        var geometry=compile().geometry();
        if(geometry.freeform())return geometry.triangles().stream().map(t->new Polyhedron.Triangle(vertices.get(t.a()).id,vertices.get(t.b()).id,vertices.get(t.c()).id)).toList();
        var ids=geometry.points().stream().map(p->vertices.stream().filter(v->v.type==VertexType.NORMAL && v.position.equals(p)).findFirst().orElseThrow().id).toList();
        return geometry.triangles().stream().map(t->new Polyhedron.Triangle(ids.get(t.a()),ids.get(t.b()),ids.get(t.c()))).toList();
    }
    /** Simplify without discarding the saved cuts or changing the solid they define. */
    public PolyShape simplifyVertices() {
        var geometry = compile().geometry();
        Map<Integer,Integer> indices = new HashMap<>();
        for (int i = 0; i < vertices.size(); i++) indices.put(vertices.get(i).id,i);
        Set<Integer> keep = new HashSet<>();
        for (var c : connections) {
            keep.add(indices.get(c.source));
            keep.add(indices.get(c.target));
        }
        if (lineMesh) for (long e : lines().cutSegments()) {
            keep.add(indices.get((int)(e >> 32)));
            keep.add(indices.get((int)e));
        }

        if (!fixedBase) {
            // The convex hull already omits interior, coplanar and collinear controls.
            var normal = vertices.stream().filter(v -> v.type == VertexType.NORMAL).map(Vertex::position).toList();
            var hull = geometry.freeform() ? Polyhedron.of(normal,bounds) : geometry;
            Set<Polyhedron.Point> corners = new HashSet<>();
            for (var f : hull.faces()) corners.addAll(f.vertices());
            for (int i = 0; i < vertices.size(); i++) {
                if (vertices.get(i).type == VertexType.NORMAL && corners.remove(vertices.get(i).position)) keep.add(i);
            }
            return simplifiedKeeping(keep, List.of(), geometry);
        }

        var points = vertices.stream().map(Vertex::position).toList();
        var simplified = RegionVertexRemoval.simplify(points,geometry.triangles(),keep);
        if (lineMesh || !manualMesh && !connections.isEmpty()) {
            // Both the restored reference and the current surface must tolerate a removal.
            for (int i = 0; i < points.size(); i++) if (!simplified.removed().contains(i)) keep.add(i);
            var rest = lineMesh ? vertices.stream().map(v -> referencePositions.get(v.id)).toList() : points;
            var indexed = baseFaces.stream().map(t -> new Polyhedron.Triangle(indices.get(t.a()),indices.get(t.b()),indices.get(t.c()))).toList();
            try {
                var reference = Polyhedron.orientedMesh(rest,indexed,bounds);
                simplified = RegionVertexRemoval.simplify(rest,reference.triangles(),keep);
            } catch (IllegalArgumentException invalidReference) {
                return this;
            }
        }
        if (simplified.removed().isEmpty()) return this;
        Set<Integer> retained = new HashSet<>();
        for (int i = 0; i < vertices.size(); i++) if (!simplified.removed().contains(i)) retained.add(i);
        var simplifiedFaces = simplified.faces().stream().map(t -> new Polyhedron.Triangle(
            vertices.get(t.a()).id,vertices.get(t.b()).id,vertices.get(t.c()).id)).toList();
        return simplifiedKeeping(retained,simplifiedFaces,geometry);
    }

    private PolyShape simplifiedKeeping(Set<Integer> retained,List<Polyhedron.Triangle> faces,Polyhedron geometry) {
        if (retained.size() == vertices.size()) return this;
        var kept = new ArrayList<Vertex>();
        var rest = new LinkedHashMap<Integer,Polyhedron.Point>();
        for (int i = 0; i < vertices.size(); i++) if (retained.contains(i)) {
            var vertex = vertices.get(i);
            kept.add(vertex);
            if (lineMesh) rest.put(vertex.id,referencePositions.get(vertex.id));
        }
        var simplified = new PolyShape(kept,connections,faces,fixedBase,nextId,true,manualMesh,lineMesh,rest,bounds);
        try {
            var compiled = simplified.compile();
            var active = compile().activeCustomVertices();
            if (kept.stream().anyMatch(v -> active.contains(v.id) && !compiled.activeCustomVertices().contains(v.id))
                    || !pendingConnections().equals(simplified.pendingConnections())
                    || !RegionVertexRemoval.sameSurface(geometry,compiled.geometry())) return this;
            return simplified;
        } catch (IllegalArgumentException invalidMesh) {
            return this;
        }
    }

    /** Freeze current visible topology before explicit face edits, keeping vertex IDs and types. */
    public PolyShape editableMesh() {
        if(manualMesh)return this;
        // Explicit editing bakes drawn cuts. Their undirected links are no longer needed.
        var cuts=lineMesh?lines().cutSegments().stream().map(e->new Connection((int)(e>>32),(int)(long)e)).toList():connections;
        return new PolyShape(vertices,cuts,surfaceTriangles(),true,nextId,true,true,false,Map.of(),bounds);
    }
    /** Display-only representation of an unfinished manual surface; compile() is still mandatory before application. */
    public Polyhedron draftSurface() {
        if(!manualMesh && !lineMesh)return compile().geometry();
        Map<Integer,Integer> index=new HashMap<>();for(int i=0;i<vertices.size();i++)index.put(vertices.get(i).id,i);
        return Polyhedron.surface(vertices.stream().map(Vertex::position).toList(),(lineMesh?lines().triangles():baseFaces).stream().map(t->new Polyhedron.Triangle(index.get(t.a()),index.get(t.b()),index.get(t.c()))).toList(),bounds);
    }
    private Polyhedron geometry(List<Polyhedron.Point> points,Map<Integer,Integer> index,List<Polyhedron.Triangle> faces) {
        return Polyhedron.mesh(points,faces.stream().map(t->new Polyhedron.Triangle(index.get(t.a()),index.get(t.b()),index.get(t.c()))).toList(),bounds);
    }
    private static long edge(int a,int b){return (long)Math.min(a,b)<<32|Math.max(a,b)&0xffffffffL;}
    private static List<Polyhedron.Triangle> split(List<Polyhedron.Triangle> base,int id,Set<Integer> targets) {
        List<Polyhedron.Triangle> result=new ArrayList<>();
        if(targets.size()==2) {
            var pair=targets.stream().toList();int count=0;
            for(var triangle:base) {
                if(!triangle.uses(pair.get(0)) || !triangle.uses(pair.get(1))){result.add(triangle);continue;}
                int[] p=triangle.indices();
                for(int i=0;i<3;i++)if(edge(p[i],p[(i+1)%3])==edge(pair.get(0),pair.get(1))) {
                    result.add(new Polyhedron.Triangle(p[i],id,p[(i+2)%3]));result.add(new Polyhedron.Triangle(id,p[(i+1)%3],p[(i+2)%3]));count++;break;
                }
            }
            return count==2?result:null;
        }
        List<Polyhedron.Triangle> patch=base.stream().filter(t->Arrays.stream(t.indices()).allMatch(targets::contains)).toList();
        if(patch.isEmpty())return null;
        Map<Long,Integer> count=new HashMap<>();Map<Long,int[]> directed=new LinkedHashMap<>();
        for(var t:patch){int[] p=t.indices();for(int i=0;i<3;i++){long e=edge(p[i],p[(i+1)%3]);count.merge(e,1,Integer::sum);directed.putIfAbsent(e,new int[]{p[i],p[(i+1)%3]});}}
        Map<Integer,Integer> next=new LinkedHashMap<>();
        for(var entry:directed.entrySet())if(count.get(entry.getKey())==1)if(next.put(entry.getValue()[0],entry.getValue()[1])!=null)return null;
        if(!next.keySet().equals(targets) || new HashSet<>(next.values()).size()!=targets.size())return null;
        int start=next.keySet().iterator().next(),current=start;Set<Integer> visited=new HashSet<>();
        do{if(!visited.add(current) || !next.containsKey(current))return null;current=next.get(current);}while(current!=start);
        if(visited.size()!=targets.size())return null;
        // A disk has F = 2V - B - 2; with boundary-only vertices this is B - 2.
        if(patch.size()!=targets.size()-2)return null;
        for(var t:base)if(!patch.contains(t))result.add(t);
        for(var entry:next.entrySet())result.add(new Polyhedron.Triangle(entry.getKey(),entry.getValue(),id));
        return result;
    }
    public int[] packed() {
        boolean precise = bounds.multiBlock() || vertices.stream().anyMatch(v -> !v.position.legacyPackable())
            || referencePositions.values().stream().anyMatch(p -> !p.legacyPackable());
        List<Integer> data = new ArrayList<>();
        Collections.addAll(data, precise ? 4 : lineMesh || manualMesh ? 3 : 1,
            lineMesh ? 3 : manualMesh ? 2 : fixedBase ? 1 : 0, nextId, vertices.size());
        for (var vertex : vertices) {
            data.add(vertex.id);
            writePoint(data, vertex.position, precise);
            data.add(vertex.type.ordinal());
            if (lineMesh) writePoint(data, referencePositions.get(vertex.id), precise);
        }
        data.add(baseFaces.size());
        for (var t : baseFaces) for (int id : t.indices()) data.add(id);
        data.add(connections.size());
        for (var link : connections) Collections.addAll(data, link.source, link.target);
        return data.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void writePoint(List<Integer> data, Polyhedron.Point point, boolean precise) {
        if (precise) Collections.addAll(data, point.tx(), point.ty(), point.tz());
        else data.add(point.packed());
    }

    public static PolyShape fromPacked(int[] data) {
        return fromPacked(data, ShapeBounds.BLOCK);
    }

    public static PolyShape fromPacked(int[] data, ShapeBounds bounds) {
        if (data.length > maxPacked(bounds)) throw invalid();
        class Reader {
            int i;
            int next() { if (i >= data.length) throw invalid(); return data[i++]; }
            int size(int max) { int n = next(); if (n < 0 || n > max) throw invalid(); return n; }
            Polyhedron.Point point(int version) {
                if (version < 4) return Polyhedron.Point.unpack(next());
                double scale = Polyhedron.PIXEL_SCALE;
                return bounds.point(next() / scale, next() / scale, next() / scale);
            }
        }
        var r = new Reader();
        int version = r.next();
        if (version < 1 || version > 4) throw invalid();
        int fixed = r.size(Math.min(version, 3)), next = r.next(), n = r.size(bounds.maxPoints());
        List<Vertex> vertices = new ArrayList<>();
        Map<Integer,Polyhedron.Point> rest = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int id = r.next();
            var point = r.point(version);
            vertices.add(new Vertex(id, point, VertexType.values()[r.size(1)]));
            if (fixed == 3) rest.put(id, r.point(version));
        }
        int f = r.size(bounds.maxTriangles());
        List<Polyhedron.Triangle> faces = new ArrayList<>();
        for (int i = 0; i < f; i++) faces.add(new Polyhedron.Triangle(r.next(), r.next(), r.next()));
        int links = r.size(bounds.maxLinks());
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < links; i++) connections.add(new Connection(r.next(), r.next()));
        if (r.i != data.length) throw invalid();
        return new PolyShape(vertices, connections, faces, fixed > 0, next, true, fixed == 2, fixed == 3, rest, bounds);
    }
    @Override public boolean equals(Object other){return other instanceof PolyShape s && bounds.equals(s.bounds) && fixedBase==s.fixedBase && manualMesh==s.manualMesh && lineMesh==s.lineMesh && nextId==s.nextId && vertices.equals(s.vertices) && connections.equals(s.connections) && baseFaces.equals(s.baseFaces) && referencePositions.equals(s.referencePositions);}
    @Override public int hashCode(){return Objects.hash(vertices,connections,baseFaces,fixedBase,manualMesh,lineMesh,referencePositions,nextId,bounds);}

    /** Mutable construction/edit helper. build() permits a draft; compile()/API apply validates its surface. */
    public static final class Builder {
        private final List<Vertex> vertices=new ArrayList<>();
        private final List<Connection> links=new ArrayList<>();
        private final List<Polyhedron.Triangle> faces=new ArrayList<>();
        private int nextId;private boolean fixed,manual,line;
        private final Map<Integer,Polyhedron.Point> reference=new LinkedHashMap<>();
        private final Set<Integer> lockedReference=new HashSet<>();
        private final ShapeBounds bounds;
        private Builder(ShapeBounds bounds){this.bounds=bounds;}
        private Builder(PolyShape source){bounds=source.bounds;vertices.addAll(source.vertices);links.addAll(source.connections);faces.addAll(source.baseFaces);nextId=source.nextId;fixed=source.fixedBase;manual=source.manualMesh;line=source.lineMesh;reference.putAll(source.referencePositions);if(line)lockedReference.addAll(source.lines().active());}
        private Vertex get(int id){return vertices.stream().filter(v->v.id==id).findFirst().orElseThrow(PolyShape::invalid);}
        public int addVertex(VertexType type,Polyhedron.Point position) {
            if(vertices.size()>=bounds.maxPoints() || nextId>1_000_000)throw new IllegalArgumentException("count");
            int id=nextId++;vertices.add(new Vertex(id,position,type));if(line)reference.put(id,position);else if(fixed && !manual && type==VertexType.NORMAL)attachNormalVertices();return id;
        }
        public int addNormalVertex(double x,double y,double z){return addVertex(VertexType.NORMAL,bounds.point(x,y,z));}
        public int addCustomVertex(double x,double y,double z){return addVertex(VertexType.CUSTOM,bounds.point(x,y,z));}
        public Builder moveVertex(int id,Polyhedron.Point point){var old=get(id);vertices.set(vertices.indexOf(old),new Vertex(id,point,old.type));if(line && !lockedReference.contains(id))reference.put(id,point);if(fixed && !manual && !line && old.type==VertexType.NORMAL && faces.stream().noneMatch(t->t.uses(id)))attachNormalVertices();return this;}
        public Builder moveVertex(int id,double x,double y,double z){return moveVertex(id,bounds.point(x,y,z));}
        private void enableLines() {
            if(line)return;
            var original=new PolyShape(vertices,links,faces,fixed,nextId,true,manual,false,Map.of(),bounds);
            var baseline=manual?original:new PolyShape(vertices,List.of(),faces,fixed,nextId,true,false,false,Map.of(),bounds);
            List<Polyhedron.Triangle> base;
            try{base=baseline.surfaceTriangles();}catch(IllegalArgumentException draft){if(!"count".equals(draft.getMessage()) && !"flat".equals(draft.getMessage()))throw draft;base=List.of();}
            faces.clear();faces.addAll(base);
            Set<Connection> undirected=new LinkedHashSet<>();for(var c:links)undirected.add(SurfaceNetwork.connection(c.source,c.target));links.clear();links.addAll(undirected);
            for(var v:vertices)reference.put(v.id,v.position);for(var t:base)for(int id:t.indices())lockedReference.add(id);fixed=true;manual=false;line=true;
        }
        /** Undirected cut between either vertex type. The reference surface is retained for restoration. */
        public Builder connect(int first,int second){get(first);get(second);if(first==second)throw invalid();enableLines();var link=SurfaceNetwork.connection(first,second);if(!links.contains(link))links.add(link);lockedReference.addAll(build().lines().active());return this;}
        public Builder disconnect(int first,int second){if(line || manual)links.removeIf(c->edge(c.source,c.target)==edge(first,second));else links.remove(new Connection(first,second));return this;}
        public Builder removeVertex(int id){
            var removed=get(id);
            if(line) {
                if(faces.stream().anyMatch(t->t.uses(id))) {
                    List<Polyhedron.Triangle> indexed=faces.stream().map(t->new Polyhedron.Triangle(vertices.indexOf(get(t.a())),vertices.indexOf(get(t.b())),vertices.indexOf(get(t.c())))).toList();
                    var repaired=Polyhedron.removeMeshVertex(vertices.stream().map(v->reference.get(v.id)).toList(),indexed,vertices.indexOf(removed),bounds);
                    var kept=vertices.stream().filter(v->v.id!=id).toList();faces.clear();faces.addAll(repaired.triangles().stream().map(t->new Polyhedron.Triangle(kept.get(t.a()).id,kept.get(t.b()).id,kept.get(t.c()).id)).toList());
                }
                reference.remove(id);
            }
            else if(manual)faces.removeIf(t->t.uses(id));
            else if(fixed && removed.type==VertexType.NORMAL && faces.stream().anyMatch(t->t.uses(id))) {
                var normal=vertices.stream().filter(v->v.type==VertexType.NORMAL).toList();
                List<Polyhedron.Triangle> indexed=faces.stream().map(t->new Polyhedron.Triangle(normal.indexOf(get(t.a())),normal.indexOf(get(t.b())),normal.indexOf(get(t.c())))).toList();
                var repaired=Polyhedron.removeMeshVertex(normal.stream().map(Vertex::position).toList(),indexed,normal.indexOf(removed),bounds);
                var kept=normal.stream().filter(v->v.id!=id).toList();
                faces.clear();faces.addAll(repaired.triangles().stream().map(t->new Polyhedron.Triangle(kept.get(t.a()).id,kept.get(t.b()).id,kept.get(t.c()).id)).toList());
            }
            vertices.removeIf(v->v.id==id);links.removeIf(l->l.source==id || l.target==id);return this;
        }

        /**
         * Dissolve selected controls of a combined solid. Preserve existing faces wherever a
         * plane-preserving contraction is possible, then reconnect the remaining vertex rings.
         */
        public Builder dissolveVertices(Collection<Integer> selected) {
            if (line) bakeLines();
            if (!manual) throw invalid();
            List<Vertex> kept = new ArrayList<>(vertices);
            List<Polyhedron.Triangle> indexed = faces.stream().map(t -> new Polyhedron.Triangle(
                vertices.indexOf(get(t.a())), vertices.indexOf(get(t.b())), vertices.indexOf(get(t.c())))).toList();
            var remaining = new LinkedHashSet<>(selected);
            while (!remaining.isEmpty()) {
                boolean progress = false;
                for (int id : List.copyOf(remaining)) {
                    int index = kept.indexOf(get(id));
                    var points = kept.stream().map(Vertex::position).toList();
                    var repaired = RegionVertexRemoval.dissolve(points, indexed, index, bounds);
                    if (repaired == null) continue;
                    indexed = repaired;
                    kept.remove(index);
                    remaining.remove(id);
                    progress = true;
                }
                if (!progress) {
                    int id = remaining.iterator().next();
                    int index = kept.indexOf(get(id));
                    var points = kept.stream().map(Vertex::position).toList();
                    indexed = Polyhedron.removeMeshVertex(points, indexed, index, bounds).triangles();
                    kept.remove(index);
                    remaining.remove(id);
                }
            }
            faces.clear();
            for (var t : indexed) faces.add(new Polyhedron.Triangle(kept.get(t.a()).id, kept.get(t.b()).id, kept.get(t.c()).id));
            vertices.clear();
            vertices.addAll(kept);
            links.removeIf(link -> selected.contains(link.source) || selected.contains(link.target));
            return this;
        }

        /** A line on a fixed mesh whose subdivision already exists. */
        public Builder guide(int first, int second) {
            if (!manual || first == second) throw invalid();
            get(first);
            get(second);
            var link = SurfaceNetwork.connection(first, second);
            if (!links.contains(link)) links.add(link);
            return this;
        }

        private void attachNormalVertices() {
            // Compatibility for old fixed meshes. New custom vertices never use this path.
            var normal=vertices.stream().filter(v->v.type==VertexType.NORMAL).toList();
            try {
                var indexed=faces.stream().map(t->new Polyhedron.Triangle(normal.indexOf(get(t.a())),normal.indexOf(get(t.b())),normal.indexOf(get(t.c())))).toList();
                var geometry=Polyhedron.editedMesh(normal.stream().map(Vertex::position).toList(),indexed);
                faces.clear();faces.addAll(geometry.triangles().stream().map(t->new Polyhedron.Triangle(normal.get(t.a()).id,normal.get(t.b()).id,normal.get(t.c()).id)).toList());
            }catch(IllegalArgumentException draft){/* An API builder may still be assembling its base faces. */}
        }
        /** Explicit base triangles use stable NORMAL vertex IDs, with consistent outward winding. */
        public Builder triangle(int a,int b,int c){if(line)bakeLines();fixed=true;faces.add(new Polyhedron.Triangle(a,b,c));return this;}
        public Builder baseFaces(List<Polyhedron.Triangle> triangles){if(line)bakeLines();fixed=true;faces.clear();faces.addAll(triangles);return this;}
        /** Full topology control. Faces may reference either vertex type; open drafts are allowed until compile(). */
        public Builder meshFaces(List<Polyhedron.Triangle> triangles){fixed=true;manual=true;line=false;reference.clear();links.clear();faces.clear();faces.addAll(triangles);return this;}
        /** Add a simple planar polygon, in perimeter selection order. A triangle can have any orientation. */
        public Builder face(List<Integer> perimeter) {
            if(line)bakeLines();
            manual=true;fixed=true;List<Integer> indices=perimeter.stream().map(id->vertices.indexOf(get(id))).toList();
            var added=Polyhedron.triangulatePolygon(vertices.stream().map(Vertex::position).toList(),indices);
            for(var t:added) {
                var face=new Polyhedron.Triangle(vertices.get(t.a()).id,vertices.get(t.b()).id,vertices.get(t.c()).id);
                if(faces.stream().noneMatch(old->Arrays.equals(Arrays.stream(old.indices()).sorted().toArray(),Arrays.stream(face.indices()).sorted().toArray())))faces.add(face);
            }
            addPerimeter(perimeter);
            return this;
        }
        /** Build a face without selection order; hole boundaries take precedence, warped polygons are triangulated. */
        public Builder faceAutomatically(Collection<Integer> selected) {
            if(line)bakeLines();
            var indexed=faces.stream().map(t->new Polyhedron.Triangle(vertices.indexOf(get(t.a())),vertices.indexOf(get(t.b())),vertices.indexOf(get(t.c())))).toList();
            var added=MeshOpenings.triangulateFace(vertices.stream().map(Vertex::position).toList(),indexed,selected.stream().map(id->vertices.indexOf(get(id))).toList());
            manual=true;fixed=true;
            for(var t:added){var face=new Polyhedron.Triangle(vertices.get(t.a()).id,vertices.get(t.b()).id,vertices.get(t.c()).id);
                if(faces.stream().noneMatch(old->Arrays.equals(Arrays.stream(old.indices()).sorted().toArray(),Arrays.stream(face.indices()).sorted().toArray())))faces.add(face);}
            Map<Long,Integer> boundary=new LinkedHashMap<>();for(var t:added){int[] p=t.indices();for(int i=0;i<3;i++)boundary.merge(edge(vertices.get(p[i]).id,vertices.get(p[(i+1)%3]).id),1,Integer::sum);}
            boundary.forEach((e,n)->{if(n==1){var link=new Connection((int)(e>>32),(int)(long)e);if(!links.contains(link))links.add(link);}});
            return this;
        }
        private void addPerimeter(List<Integer> perimeter){for(int i=0;i<perimeter.size();i++){var link=SurfaceNetwork.connection(perimeter.get(i),perimeter.get((i+1)%perimeter.size()));if(!links.contains(link))links.add(link);}}
        private void bakeLines(){var compiled=build().lines();faces.clear();faces.addAll(compiled.triangles());links.clear();for(long e:compiled.cutSegments())links.add(new Connection((int)(e>>32),(int)e));reference.clear();lockedReference.clear();line=false;manual=true;fixed=true;}
        /** Remove triangle indices from surfaceTriangles(). Convert with editableMesh() first. */
        public Builder removeFaces(Collection<Integer> selected){if(!manual)throw invalid();for(int i=faces.size()-1;i>=0;i--)if(selected.contains(i))faces.remove(i);return this;}
        public Builder useConvexBase(){fixed=false;manual=false;line=false;reference.clear();links.removeIf(c->get(c.source).type!=VertexType.CUSTOM);faces.clear();return this;}
        public PolyShape build(){return new PolyShape(vertices,links,faces,fixed,nextId,true,manual,line,reference,bounds);}
    }
}
