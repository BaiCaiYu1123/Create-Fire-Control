// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca

package com.caca.copypoly.api;

import com.caca.copypoly.geometry.Polyhedron;
import com.caca.copypoly.geometry.RegionVertexRemoval;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimplifyVerticesTest {
    @Test void convexDocumentDiscardsUnusedControls() {
        var shape=PolyShape.fromGeometry(Polyhedron.CUBE);
        var b=shape.toBuilder();
        b.addNormalVertex(8,16,8);
        b.addNormalVertex(8,0,0);
        b.addNormalVertex(8,8,8);
        b.addCustomVertex(1,1,1);
        var simplified=b.build().simplifyVertices();
        assertEquals(shape.vertices(),simplified.vertices());
        assertFalse(simplified.hasExplicitBaseFaces());
        assertSame(simplified,simplified.simplifyVertices());
    }

    @Test void straightCreaseAndPlanarFanAreDissolved() {
        var shape=refined(0,false);
        var simplified=shape.simplifyVertices();
        assertEquals(Polyhedron.CUBE_POINTS,simplified.vertices().stream().map(PolyShape.Vertex::position).toList());
        assertEquals(12,simplified.surfaceTriangles().size());
        assertTrue(RegionVertexRemoval.sameSurface(shape.compile().geometry(),simplified.compile().geometry()));
    }

    @Test void aThousandthOfAPixelAndDrawnGuidesAreRetained() {
        for (var refined : List.of(refined(.001,false),refined(0,true))) {
            var simplified=refined.simplifyVertices();
            assertEquals(9,simplified.vertices().size());
            assertEquals(refined.vertex(9),simplified.vertex(9));
            assertEquals(refined.connections(),simplified.connections());
            assertTrue(RegionVertexRemoval.sameSurface(refined.compile().geometry(),simplified.compile().geometry()));
        }
    }

    @Test void aLineDocumentKeepsTheEditableReference() {
        var builder=refined(0,false).toBuilder();
        builder.connect(2,7);
        var drawn=builder.build();
        var simplified=drawn.simplifyVertices();
        assertTrue(simplified.lineMesh());
        assertEquals(drawn.connections(),simplified.connections());
        assertEquals(drawn.pendingConnections(),simplified.pendingConnections());
        assertTrue(simplified.isDrawnEdge(2,7));
        assertTrue(simplified.vertices().size()<drawn.vertices().size());
        var restored=simplified.toBuilder().disconnect(2,7).build();
        assertTrue(RegionVertexRemoval.sameSurface(Polyhedron.CUBE,restored.compile().geometry()));
    }

    private static PolyShape refined(double lift,boolean guide) {
        var builder=PolyShape.fromGeometry(Polyhedron.CUBE).toBuilder();
        int middle=builder.addNormalVertex(0,0,8);
        var faces=new ArrayList<Polyhedron.Triangle>();
        for (var t : Polyhedron.CUBE.triangles()) {
            int[] p=t.indices();
            if (!t.uses(0) || !t.uses(1)) {
                faces.add(t);
                continue;
            }
            for (int i=0;i<3;i++) {
                int a=p[i], b=p[(i+1)%3], c=p[(i+2)%3];
                if (Math.min(a,b)==0 && Math.max(a,b)==1) {
                    faces.add(new Polyhedron.Triangle(a,middle,c));
                    faces.add(new Polyhedron.Triangle(middle,b,c));
                    break;
                }
            }
        }
        var roof=faces.stream().filter(t -> java.util.Arrays.stream(t.indices())
            .allMatch(i -> i<8 && Polyhedron.CUBE_POINTS.get(i).y()==16)).findFirst().orElseThrow();
        var a=Polyhedron.CUBE_POINTS.get(roof.a());
        var b=Polyhedron.CUBE_POINTS.get(roof.b());
        var c=Polyhedron.CUBE_POINTS.get(roof.c());
        int inner=builder.addCustomVertex((a.x()*2+b.x()+c.x())/4,16-lift,(a.z()*2+b.z()+c.z())/4);
        faces.remove(roof);
        faces.add(new Polyhedron.Triangle(roof.a(),roof.b(),inner));
        faces.add(new Polyhedron.Triangle(roof.b(),roof.c(),inner));
        faces.add(new Polyhedron.Triangle(roof.c(),roof.a(),inner));
        builder.meshFaces(faces);
        if(guide)builder.guide(inner,roof.a());
        return builder.build();
    }
}
