package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArmorMeshOutlineTest {
    @Test void cubeAndSquareBoreShowOnlyTheirRealCreases() {
        var cube = ArmorMesh.of(Polyhedron.CUBE);
        assertEquals(12, ArmorMeshOutline.build(cube).size());
        var bored = cube.subtract(ArmorHole.along(new Vec3(.5,.5,0), new Vec3(0,0,1)));
        assertEquals(24, ArmorMeshOutline.build(bored).size());
        sameOutline(bored);
    }

    @Test void obliqueIntersectingAndEdgeBoresMatchTheEditableSurface() {
        var mesh = ArmorMesh.of(Polyhedron.CUBE);
        for (var hole : List.of(
                ArmorHole.along(new Vec3(.5,.5,.5), new Vec3(.6,.23,1)),
                ArmorHole.along(new Vec3(.5,.5,.5), new Vec3(1,.1,.2)),
                ArmorHole.along(new Vec3(.99,.5,0), new Vec3(0,0,1)),
                ArmorHole.along(new Vec3(.35,.35,.5), new Vec3(.2,-.1,1)))) {
            mesh = mesh.subtract(hole);
            sameOutline(mesh);
        }
    }

    @Test void editedSlopingHoleAndSplitControlsKeepTheSameVisibleSurface() {
        var bored = ArmorMesh.of(Polyhedron.CUBE).subtract(ArmorHole.along(new Vec3(.5,.5,.5), new Vec3(.6,.2,1)));
        var edit = EditableArmorMesh.from(bored);
        edit = edit.split(edit.edges().getFirst()).transform(p -> new Vec3(p.x,p.y*.6,p.z), false);
        sameOutline(edit.mesh());
    }

    @Test void coplanarTJunctionsCancelWithoutHidingOpenBoundaryOrBridgingGaps() {
        Vec3 a = new Vec3(0,0,0), b = new Vec3(1,0,0), c = new Vec3(1,1,0), d = new Vec3(0,1,0), m = new Vec3(.5,.5,0);
        var mesh = new ArmorMesh(List.of(new ArmorMesh.Face(List.of(a,b,c)),
                new ArmorMesh.Face(List.of(a,m,d)), new ArmorMesh.Face(List.of(m,c,d))));
        assertEquals(4, ArmorMeshOutline.build(mesh).size());
        var disjoint = new ArmorMesh(List.of(new ArmorMesh.Face(List.of(a,new Vec3(.3,0,0),new Vec3(0,.3,0))),
                new ArmorMesh.Face(List.of(new Vec3(.7,0,0),b,new Vec3(1,.3,0)))));
        assertEquals(6, ArmorMeshOutline.build(disjoint).size());
    }

    @Test void complexRuntimeSelectionDoesNotDependOnEditorMeshLimits() {
        var faces = new ArrayList<ArmorMesh.Face>();
        int n = 40;
        for (var face : ArmorMesh.of(Polyhedron.CUBE).faces()) {
            var a = face.vertices().getFirst();
            var u = face.vertices().get(1).subtract(a).scale(1.0/n);
            var v = face.vertices().get(2).subtract(a).scale(1.0/n);
            for (int i = 0; i < n; i++) for (int j = 0; j < n-i; j++) {
                var p = a.add(u.scale(i)).add(v.scale(j));
                faces.add(new ArmorMesh.Face(List.of(p,p.add(u),p.add(v))));
                if (i+j < n-1) faces.add(new ArmorMesh.Face(List.of(p.add(u),p.add(u).add(v),p.add(v))));
            }
        }
        var shape = new ArmorVoxelShape(List.of(new net.minecraft.world.phys.AABB(0,0,0,1,1,1)), new ArmorMesh(faces));
        for (int frame = 0; frame < 3; frame++) {
            int[] count = {0};
            shape.forAllEdges((x0,y0,z0,x1,y1,z1) -> count[0]++);
            assertEquals(12, count[0]);
        }
    }

    private static void sameOutline(ArmorMesh mesh) {
        var editable = EditableArmorMesh.from(mesh);
        var expected = editable.edges().stream().map(e -> new ArmorMeshOutline.Line(editable.vertices().get(e.a()), editable.vertices().get(e.b()))).toList();
        var actual = ArmorMeshOutline.build(mesh);
        for (var line : expected) assertCovered(line, actual);
        for (var line : actual) assertCovered(line, expected);
    }

    private static void assertCovered(ArmorMeshOutline.Line line, List<ArmorMeshOutline.Line> candidates) {
        var direction = line.b().subtract(line.a());
        double length = direction.lengthSqr();
        var ranges = new ArrayList<double[]>();
        for (var candidate : candidates) {
            var a = candidate.a().subtract(line.a());
            var b = candidate.b().subtract(line.a());
            if (a.cross(direction).lengthSqr() > length*1e-14 || b.cross(direction).lengthSqr() > length*1e-14) continue;
            double first = a.dot(direction)/length, last = b.dot(direction)/length;
            ranges.add(new double[]{Math.min(first,last), Math.max(first,last)});
        }
        ranges.sort(Comparator.comparingDouble(r -> r[0]));
        double covered = 0;
        for (var range : ranges) if (range[0] <= covered+1e-7) covered = Math.max(covered,range[1]);
        assertTrue(covered >= 1-1e-7, () -> "Missing crease " + line + " covered=" + ranges.stream().map(Arrays::toString).toList());
    }
}
