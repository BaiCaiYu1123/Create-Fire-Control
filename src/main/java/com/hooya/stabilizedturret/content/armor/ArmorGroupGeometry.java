package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.api.VertexType;
import com.caca.copypoly.geometry.Polyhedron;
import com.caca.copypoly.geometry.ShapeBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Joins the occupied cell solids and cuts an edited solid back along their original grid. */
public final class ArmorGroupGeometry {
    private static final int MAX_CSG_FACES = 4096;
    private static final double EPS = 1e-8;

    public record Cell(BlockPos offset, ArmorMesh mesh, EditableArmorMesh controls) {}
    public record Slice(EditableArmorMesh mesh) {
        public PolyShape document() {
            return shape(mesh, ShapeBounds.BLOCK, 1);
        }
    }

    private record Position(long x, long y, long z) {
        Position(Vec3 p) { this(Math.round(p.x * 1e8), Math.round(p.y * 1e8), Math.round(p.z * 1e8)); }
    }

    private ArmorGroupGeometry() {}

    public static PolyShape join(ShapeBounds bounds, List<Cell> cells) {
        if (!bounds.multiBlock() || cells.size() < 2) throw invalid("multi_single");
        if (cells.stream().mapToLong(cell -> cell.mesh.faces().size()).sum() > MAX_CSG_FACES)
            throw invalid("multi_complex");
        var meshes = new ArrayList<ArmorMesh>();
        var points = new ArrayList<Vec3>();
        var kinds = new ArrayList<Integer>();
        var links = new ArrayList<EditableArmorMesh.Edge>();
        double size = Math.max(bounds.x(), Math.max(bounds.y(), bounds.z())) / 16.0;
        for (var cell : cells) {
            Vec3 offset = Vec3.atLowerCornerOf(cell.offset);
            meshes.add(cell.mesh.transform(p -> p.add(offset)));
            int first = points.size();
            for (var p : cell.controls.vertices()) points.add(p.add(offset).scale(1 / size));
            kinds.addAll(cell.controls.kinds());
            for (var link : cell.controls.guides()) links.add(new EditableArmorMesh.Edge(first + link.a(), first + link.b()));
        }

        // Pairwise union removes opposing coincident surfaces, including partly overlapping grid faces.
        while (meshes.size() > 1) {
            var joined = new ArrayList<ArmorMesh>();
            for (int i = 0; i < meshes.size(); i += 2) {
                var mesh = i + 1 == meshes.size() ? meshes.get(i) : meshes.get(i).union(meshes.get(i + 1));
                requireFaceBudget(mesh);
                joined.add(mesh);
            }
            meshes = joined;
        }
        var welded = EditableArmorMesh.triangulated(meshes.getFirst().transform(p -> p.scale(1 / size)));
        var reference = controlsOnSurface(welded, points, kinds, links);
        var compact = ArmorMeshSimplifier.compact(welded, reference);
        PolyShape result = shape(compact, bounds, size);
        result.compile();
        return result;
    }

    public static List<Slice> cut(PolyShape edit, List<BlockPos> offsets) {
        if (!edit.bounds().multiBlock()) throw invalid("range");
        var compiled = edit.compile().geometry();
        ArmorMesh solid = ArmorMesh.of(compiled);
        var cutter = new ArmorMesh.CellCutter(solid);
        List<Slice> result = new ArrayList<>();
        double covered = 0;
        for (var offset : offsets) {
            Vec3 min = Vec3.atLowerCornerOf(offset);
            ArmorMesh clipped = cutter.intersect(min);
            double volume = clipped.signedVolume();
            if (volume <= EPS) throw invalid("multi_empty");
            covered += volume;
            requireFaceBudget(clipped);
            var local = clipped.transform(p -> p.subtract(min));
            var welded = EditableArmorMesh.triangulated(local);
            var reference = sliceControls(edit, welded, min);
            var compact = ArmorMeshSimplifier.compact(welded, reference);
            var slice = rounded(compact);
            slice.validate();
            result.add(new Slice(slice));
        }
        double volume = solid.signedVolume();
        if (Math.abs(covered - volume) > EPS * Math.max(8, offsets.size()))
            throw invalid("multi_outside");
        return List.copyOf(result);
    }

    private static EditableArmorMesh sliceControls(PolyShape edit, EditableArmorMesh welded, Vec3 min) {
        List<Vec3> points = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        Map<Integer, Integer> ids = new LinkedHashMap<>();
        for (var v : edit.vertices()) {
            Vec3 p = new Vec3(v.position().x()/16,v.position().y()/16,v.position().z()/16).subtract(min);
            ids.put(v.id(),points.size());
            points.add(p);
            kinds.add(v.type() == VertexType.CUSTOM ? 1 : 0);
        }
        var guides = edit.connections().stream().filter(e -> edit.isDrawnEdge(e.source(),e.target()))
            .map(e -> new EditableArmorMesh.Edge(ids.get(e.source()),ids.get(e.target()))).toList();
        return controlsOnSurface(welded, points, kinds, guides);
    }

    private static EditableArmorMesh controlsOnSurface(EditableArmorMesh welded, List<Vec3> points,
                                                       List<Integer> kinds, List<EditableArmorMesh.Edge> links) {
        var actual = new LinkedHashMap<Position,Vec3>();
        for (var p : welded.vertices()) actual.put(new Position(p),p);
        var ids = new LinkedHashMap<Position,Integer>();
        var keptPoints = new ArrayList<Vec3>();
        var keptKinds = new ArrayList<Integer>();
        var remap = new LinkedHashMap<Integer,Integer>();
        for (int i = 0; i < points.size(); i++) {
            var key = new Position(points.get(i));
            Vec3 p = actual.get(key);
            if (p == null) continue;
            Integer id = ids.get(key);
            int kind = Math.max(0,kinds.get(i));
            if (id == null) {
                id = keptPoints.size();
                ids.put(key,id);
                keptPoints.add(p);
                keptKinds.add(kind);
            } else if (kind == 1) {
                keptKinds.set(id,kind);
            }
            remap.put(i,id);
        }
        var keptLinks = new LinkedHashSet<EditableArmorMesh.Edge>();
        for (var link : links) {
            Integer a = remap.get(link.a()), b = remap.get(link.b());
            if (a != null && b != null && !a.equals(b)) keptLinks.add(new EditableArmorMesh.Edge(a,b));
        }
        return new EditableArmorMesh(keptPoints,List.of(),keptKinds,List.copyOf(keptLinks));
    }

    private static double pixel(double blockCoordinate) {
        // Both sides of a grid cut must round a half-tick the same way.
        return Math.floor(blockCoordinate * 16000 + .5 + 1e-8) / 1000.0;
    }

    private static EditableArmorMesh rounded(EditableArmorMesh mesh) {
        var points = mesh.vertices().stream().map(p -> new Vec3(pixel(p.x)/16, pixel(p.y)/16, pixel(p.z)/16)).toList();
        var index = new LinkedHashMap<Vec3,Integer>();
        var kinds = new ArrayList<Integer>();
        int[] remap = new int[points.size()];
        for (int i = 0; i < remap.length; i++) {
            var p = points.get(i);
            Integer id = index.get(p);
            if (id == null) {
                id = index.size();
                index.put(p,id);
                kinds.add(mesh.kinds().get(i));
            } else if (mesh.kinds().get(i) == 1) {
                kinds.set(id,1);
            }
            remap[i] = id;
        }
        var triangles = new ArrayList<EditableArmorMesh.Triangle>();
        for (var t : mesh.triangles()) {
            int a = remap[t.a()], b = remap[t.b()], c = remap[t.c()];
            if (a != b && b != c && a != c) triangles.add(new EditableArmorMesh.Triangle(a,b,c));
        }
        var guides = mesh.guides().stream().map(e -> new EditableArmorMesh.Edge(remap[e.a()],remap[e.b()]))
            .filter(e -> e.a() != e.b()).distinct().toList();
        return new EditableArmorMesh(List.copyOf(index.keySet()),triangles,kinds,guides);
    }

    private static PolyShape shape(EditableArmorMesh mesh, ShapeBounds bounds, double size) {
        if (mesh.vertices().size() > bounds.maxPoints() || mesh.triangles().size() > bounds.maxTriangles()
                || mesh.guides().size() > bounds.maxLinks())
            throw invalid(bounds.multiBlock() ? "multi_complex" : "multi_cell_limit");
        var builder = PolyShape.builder(bounds);
        for (int i = 0; i < mesh.vertices().size(); i++) {
            Vec3 p = mesh.vertices().get(i).scale(size);
            builder.addVertex(mesh.kinds().get(i) == 1 ? VertexType.CUSTOM : VertexType.NORMAL,
                bounds.point(pixel(p.x),pixel(p.y),pixel(p.z)));
        }
        builder.meshFaces(mesh.triangles().stream().map(t -> new Polyhedron.Triangle(t.a(),t.b(),t.c())).toList());
        var existing = new HashSet<EditableArmorMesh.Edge>();
        for (var t : mesh.triangles()) {
            existing.add(new EditableArmorMesh.Edge(t.a(),t.b()));
            existing.add(new EditableArmorMesh.Edge(t.b(),t.c()));
            existing.add(new EditableArmorMesh.Edge(t.c(),t.a()));
        }
        for (var guide : mesh.guides()) if (existing.contains(guide)) builder.guide(guide.a(),guide.b());
        return builder.build();
    }

    private static void requireFaceBudget(ArmorMesh mesh) {
        if (mesh.faces().size() > MAX_CSG_FACES) throw invalid("multi_complex");
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
