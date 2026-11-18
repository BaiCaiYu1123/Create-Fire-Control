package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.geometry.Polyhedron;
import com.caca.copypoly.geometry.ShapeBounds;
import com.caca.copypoly.geometry.VertexEditing;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArmorGroupGeometryTest {
    private static ArmorGroupGeometry.Cell cube(BlockPos offset) {
        var cube = Polyhedron.CUBE;
        return new ArmorGroupGeometry.Cell(offset,ArmorMesh.of(cube),EditableArmorMesh.reference(PolyShape.fromGeometry(cube)));
    }

    @Test void missingMiddleControlsAreReconstructedFromTheLongSlope() {
        var offsets = List.of(new BlockPos(1,0,0),new BlockPos(2,0,0),BlockPos.ZERO);
        var bounds = new ShapeBounds(48,16,16);
        var joined = ArmorGroupGeometry.join(bounds,offsets.stream().map(ArmorGroupGeometryTest::cube).toList());
        var middle = joined.vertices().stream().filter(v -> v.position().x() > 0 && v.position().x() < 48).map(PolyShape.Vertex::id).toList();
        assertFalse(middle.isEmpty());
        var dissolved = joined.toBuilder().dissolveVertices(middle).build();
        assertEquals(8,dissolved.vertices().size());
        var slope = dissolved.transformPositions(p -> p.x() == 0 && p.y() == 16 ? bounds.withAxis(p,1,4.124) : p);
        assertEquals(slope,PolyShape.fromPacked(slope.packed(),bounds));
        var cuts = ArmorGroupGeometry.cut(slope,offsets);
        for (var cut : cuts) cut.document().compile();

        var first = cuts.get(2).document().compile().geometry();
        var center = cuts.get(0).document().compile().geometry();
        var last = cuts.get(1).document().compile().geometry();
        assertTrue(first.points().contains(new Polyhedron.Point(16,8.083,0)));
        assertTrue(center.points().contains(new Polyhedron.Point(0,8.083,0)));
        assertTrue(center.points().contains(new Polyhedron.Point(16,12.041,16)));
        assertTrue(last.points().contains(new Polyhedron.Point(0,12.041,16)));
        assertSame(center,Polyhedron.fromPacked(center.packed(),center.packedTriangles()));

        var reopened = new ArrayList<ArmorGroupGeometry.Cell>();
        for (int i=0;i<cuts.size();i++) {
            var doc = cuts.get(i).document();
            reopened.add(new ArmorGroupGeometry.Cell(offsets.get(i),ArmorMesh.of(doc.compile().geometry()),EditableArmorMesh.reference(doc)));
        }
        assertTrue(ArmorGroupGeometry.join(bounds,reopened).compile().geometry().contains(24,9,8));
    }

    @Test void rampCanTaperToGroundWithoutDroppingTheFirstBlock() {
        var offsets = List.of(BlockPos.ZERO, new BlockPos(1,0,0),new BlockPos(2,0,0));
        var bounds = new ShapeBounds(48,16,16);
        var joined = ArmorGroupGeometry.join(bounds,offsets.stream().map(ArmorGroupGeometryTest::cube).toList());
        var seams = joined.vertices().stream().filter(v -> v.position().x() > 0 && v.position().x() < 48).map(PolyShape.Vertex::id).toList();
        var hull = joined.toBuilder().dissolveVertices(seams).build();
        var wedge = hull.transformPositions(p -> p.x()==0 ? bounds.withAxis(p,1,0) : p);
        var slices = ArmorGroupGeometry.cut(wedge,offsets);
        assertEquals(3,slices.size());
        assertTrue(slices.getFirst().document().compile().geometry().contains(8,2,8));
        assertFalse(slices.getFirst().document().compile().geometry().contains(8,4,8));
    }

    @Test void automaticSimplificationClearsOnlyGridSeamControls() {
        var offsets = List.of(BlockPos.ZERO, new BlockPos(1,0,0),new BlockPos(2,0,0));
        var joined = ArmorGroupGeometry.join(new ShapeBounds(48,16,16),offsets.stream().map(ArmorGroupGeometryTest::cube).toList());
        assertTrue(joined.vertices().size()>8);
        var simplified = joined.simplifyVertices();
        assertEquals(8,simplified.vertices().size());
        var slices = ArmorGroupGeometry.cut(simplified,offsets);
        for (var slice : slices) assertEquals(1,slice.mesh().mesh().volume(),1e-9);
    }

    @Test void damagedMeshSimplifiesASplitCrease() {
        var original=EditableArmorMesh.from(ArmorMesh.of(Polyhedron.CUBE));
        var withExtra=original.split(original.edges().getFirst());
        var simplified=withExtra.simplifyVertices();
        assertEquals(original.vertices(),simplified.vertices());
        assertEquals(original.mesh().volume(),simplified.mesh().volume(),1e-9);
    }

    @Test void concaveLayoutsKeepEmptyCells() {
        var offsets = List.of(BlockPos.ZERO,new BlockPos(1,0,0),new BlockPos(0,0,1));
        var bounds = new ShapeBounds(32,16,32);
        var joined = ArmorGroupGeometry.join(bounds,offsets.stream().map(ArmorGroupGeometryTest::cube).toList());
        assertFalse(joined.compile().geometry().contains(24,8,24));
        assertEquals(3,ArmorGroupGeometry.cut(joined,offsets).size());

        var corners = new ArrayList<Polyhedron.Point>();
        for (int x : new int[]{0,32}) for (int y : new int[]{0,16}) for (int z : new int[]{0,32}) corners.add(bounds.point(x,y,z));
        var overflow = PolyShape.fromGeometry(Polyhedron.of(corners,bounds));
        assertEquals("multi_outside",assertThrows(IllegalArgumentException.class,() -> ArmorGroupGeometry.cut(overflow,offsets)).getMessage());
    }

    @Test void ringStaysOpenThroughTheMiddle() {
        var offsets = new ArrayList<BlockPos>();
        for (int x=0;x<3;x++) for (int z=0;z<3;z++) if (x!=1 || z!=1) offsets.add(new BlockPos(x,0,z));
        var joined = ArmorGroupGeometry.join(new ShapeBounds(48,16,48),offsets.stream().map(ArmorGroupGeometryTest::cube).toList());
        assertFalse(joined.compile().geometry().contains(24,8,24));
        assertEquals(8,ArmorGroupGeometry.cut(joined,offsets).size());
    }

    @Test void coordinateCodecsAndShiftRetainThreeDecimals() {
        var builder = PolyShape.fromGeometry(Polyhedron.CUBE).toBuilder();
        builder.moveVertex(0,.125,.002,.003);
        var shape = builder.build();
        assertEquals(shape,PolyShape.fromPacked(shape.packed()));
        var points = List.of(new Polyhedron.Point(.123,1,1),new Polyhedron.Point(4.789,1,1));
        assertEquals(.223,VertexEditing.translate(points,0,.125,true).getFirst().x(),0);
        assertEquals(5.789,VertexEditing.translate(points,0,.999,false).getLast().x(),0);
        assertEquals("4.789",VertexEditing.format(4.789));
    }
}
